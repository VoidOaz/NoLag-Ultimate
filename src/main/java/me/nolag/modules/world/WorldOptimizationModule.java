package me.nolag.modules.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;


public final class WorldOptimizationModule implements Listener {

    private static final long RESCUE_COOLDOWN_NANOS = 2_000_000_000L;
    private static final long RESCUE_ENTRY_TTL_NANOS = 300_000_000_000L;


    private final Map<UUID, Long> lastVoidRescueTime = new ConcurrentHashMap<>();
    private final java.util.Set<LeafScanKey> pendingLeafScans = ConcurrentHashMap.newKeySet();
    private record LeafScanKey(UUID world, int chunkX, int chunkZ, int bx, int by, int bz) { }

    private final NoLag plugin;
    private volatile boolean fastLeafDecayEnabled = true;
    private volatile boolean voidTeleportEnabled = true;

    public WorldOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var config = plugin.getConfig();
        // Delayed block breaks cannot reliably consult every region/claim plugin.
        // Until there is a compatible permission integration, let vanilla decay leaves.
        fastLeafDecayEnabled = false;
        if (config.getBoolean("features.fast-leaf-decay", false)) {
            plugin.getLogger().warning("Fast leaf decay is unavailable without claim-aware permission checks; using vanilla decay.");
        }
        voidTeleportEnabled = config.getBoolean("features.void-teleport", true);
    }


    public void trimCaches() {
        final long now = System.nanoTime();
        lastVoidRescueTime.entrySet().removeIf(entry -> now - entry.getValue() > RESCUE_ENTRY_TTL_NANOS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastVoidRescueTime.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWoodBreak(BlockBreakEvent event) {
        if (!fastLeafDecayEnabled) {
            return;
        }

        final Block brokenBlock = event.getBlock();
        if (!Tag.LOGS.isTagged(brokenBlock.getType())) {
            return;
        }

        final World world = brokenBlock.getWorld();
        final int blockX = brokenBlock.getX();
        final int blockY = brokenBlock.getY();
        final int blockZ = brokenBlock.getZ();


        final int minChunkX = (blockX - 4) >> 4;
        final int maxChunkX = (blockX + 4) >> 4;
        final int minChunkZ = (blockZ - 4) >> 4;
        final int maxChunkZ = (blockZ + 4) >> 4;
        final int taskY = Math.max(world.getMinHeight(), Math.min(blockY, world.getMaxHeight() - 1));

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int scanChunkX = chunkX;
                final int scanChunkZ = chunkZ;
                final Location regionLocation = new Location(
                        world,
                        (scanChunkX << 4) + 8.0,
                        taskY,
                        (scanChunkZ << 4) + 8.0
                );

                LeafScanKey key = new LeafScanKey(world.getUID(), scanChunkX, scanChunkZ,
                        blockX >> 3, blockY >> 3, blockZ >> 3);
                if (!pendingLeafScans.add(key)) continue;
                var task = plugin.getSchedulerAdapter().runRegionLater(regionLocation, () -> {
                    try {
                        if (fastLeafDecayEnabled) {
                            decayLeavesInChunk(world, blockX, blockY, blockZ, scanChunkX, scanChunkZ);
                        }
                    } finally {
                        pendingLeafScans.remove(key);
                    }
                }, 6L);
                if (task == null || task.isCancelled()) pendingLeafScans.remove(key);
            }
        }
    }


    private void decayLeavesInChunk(
            World world,
            int originX,
            int originY,
            int originZ,
            int chunkX,
            int chunkZ
    ) {

        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return;
        }

        final int chunkMinX = chunkX << 4;
        final int chunkMaxX = chunkMinX + 15;
        final int chunkMinZ = chunkZ << 4;
        final int chunkMaxZ = chunkMinZ + 15;

        final int minX = Math.max(originX - 4, chunkMinX);
        final int maxX = Math.min(originX + 4, chunkMaxX);
        final int minZ = Math.max(originZ - 4, chunkMinZ);
        final int maxZ = Math.min(originZ + 4, chunkMaxZ);
        final int minY = Math.max(world.getMinHeight(), originY - 2);
        final int maxY = Math.min(world.getMaxHeight() - 1, originY + 4);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    final Block block = world.getBlockAt(x, y, z);
                    if (!Tag.LEAVES.isTagged(block.getType())) {
                        continue;
                    }

                    final BlockData data = block.getBlockData();
                    if (!(data instanceof Leaves leaves)) {
                        continue;
                    }


                    if (!leaves.isPersistent() && leaves.getDistance() >= 7) {


                        // Let vanilla calculate natural leaf loot and drop items.
                        block.breakNaturally();
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!voidTeleportEnabled) {
            return;
        }

        final Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }

        final Location from = event.getFrom();


        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        final Player player = event.getPlayer();
        final GameMode gameMode = player.getGameMode();
        if (gameMode == GameMode.CREATIVE || gameMode == GameMode.SPECTATOR
                || player.hasPermission("nolag.unsafe.bypass")) {
            return;
        }

        final World sourceWorld = to.getWorld();
        if (to.getY() >= sourceWorld.getMinHeight() - 2.0) {
            return;
        }

        final long now = System.nanoTime();
        final UUID playerId = player.getUniqueId();
        final Long lastRescue = lastVoidRescueTime.get(playerId);
        if (lastRescue != null && now - lastRescue < RESCUE_COOLDOWN_NANOS) {
            return;
        }
        lastVoidRescueTime.put(playerId, now);


        plugin.getSchedulerAdapter().runEntity(player, () -> {
            if (!player.isOnline() || !player.isValid()) {
                lastVoidRescueTime.remove(playerId, now);
                return;
            }

            if (player.getWorld() != sourceWorld
                    || player.getLocation().getY() >= sourceWorld.getMinHeight() - 2.0) {
                lastVoidRescueTime.remove(playerId, now);
                return;
            }

            Location destination = player.getRespawnLocation();
            if (destination == null || destination.getWorld() == null) {
                destination = sourceWorld.getSpawnLocation();
            }
            destination = destination.clone();

            player.setFallDistance(0.0F);
            player.setVelocity(new Vector(0.0, 0.0, 0.0));
            player.teleportAsync(destination).thenAccept(success -> {
                if (!Boolean.TRUE.equals(success)) return;


                plugin.getSchedulerAdapter().runEntity(player, () -> {
                    if (!player.isOnline()) return;
                    String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                    if (prefix == null) {
                        prefix = "&8[&6NoLag&8] ";
                    }
                    player.sendMessage(ChatColor.translateAlternateColorCodes(
                            '&', prefix + "&aYou have been safely rescued from the void!"));
                });
            });
        });
    }
}
