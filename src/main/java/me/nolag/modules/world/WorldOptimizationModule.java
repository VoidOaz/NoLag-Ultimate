package me.nolag.modules.world;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
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

    private final NoLag plugin;
    // FIXED: Add timeout cleanup to prevent unbounded growth of player tracking map
    private final Map<UUID, Long> lastVoidRescueTime = new ConcurrentHashMap<>();
    private static final long RESCUE_TIMEOUT_MS = 300_000L; // 5 minute timeout for stale entries

    private volatile boolean fastLeafDecayEnabled = true;
    private volatile boolean voidTeleportEnabled = true;

    public WorldOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.fastLeafDecayEnabled = cfg.getBoolean("features.fast-leaf-decay", true);
        this.voidTeleportEnabled = cfg.getBoolean("features.void-teleport", true);
    }

    // FIXED: Add cache trimming to prevent memory leaks from stale rescue timestamps
    public void trimCaches() {
        long now = System.currentTimeMillis();
        lastVoidRescueTime.entrySet().removeIf(entry -> (now - entry.getValue()) > RESCUE_TIMEOUT_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastVoidRescueTime.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWoodBreak(BlockBreakEvent event) {
        if (!fastLeafDecayEnabled) {
            return;
        }

        Block block = event.getBlock();
        if (Tag.LOGS.isTagged(block.getType())) {
            var world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();

            plugin.getSchedulerAdapter().runRegionLater(block.getLocation(), () -> {
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    return;
                }
                for (int x = -4; x <= 4; x++) {
                    for (int z = -4; z <= 4; z++) {
                        int cx = (bx + x) >> 4;
                        int cz = (bz + z) >> 4;
                        if (!world.isChunkLoaded(cx, cz)) {
                            continue;
                        }
                        for (int y = -2; y <= 4; y++) {
                            Block relative = world.getBlockAt(bx + x, by + y, bz + z);
                            if (Tag.LEAVES.isTagged(relative.getType())) {
                                if (relative.getBlockData() instanceof Leaves leaves) {
                                    // Do NOT decay player-placed persistent leaves!
                                    if (!leaves.isPersistent() && leaves.getDistance() >= 7) {
                                        relative.setType(org.bukkit.Material.AIR, false);
                                    }
                                }
                            }
                        }
                    }
                }
            }, 6L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!voidTeleportEnabled) {
            return;
        }

        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }

        Location from = event.getFrom();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            Player player = event.getPlayer();
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (player.hasPermission("nolag.unsafe.bypass")) {
                return;
            }

            int minHeight = to.getWorld().getMinHeight();
            if (to.getY() < (minHeight - 2.0)) {
                long now = System.currentTimeMillis();
                Long lastRescue = lastVoidRescueTime.get(player.getUniqueId());
                if (lastRescue != null && (now - lastRescue) < 2000L) {
                    return; // Prevent multiple rescues in consecutive move events
                }
                lastVoidRescueTime.put(player.getUniqueId(), now);

                Location spawn = player.getRespawnLocation();
                if (spawn == null || spawn.getWorld() == null) {
                    spawn = to.getWorld().getSpawnLocation();
                }

                player.setFallDistance(0.0F);
                player.setVelocity(new Vector(0, 0, 0));

                Location targetSpawn = spawn;
                plugin.getSchedulerAdapter().runEntity(player, () -> {
                    player.teleport(targetSpawn);
                    String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + "&aYou have been safely rescued from the void!"));
                });
            }
        }
    }
}
