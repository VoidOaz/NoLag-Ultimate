package me.nolag.modules.flood;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * Fluid Flooding koruması - Water, Lava ve Ice ile yapılan aşırı flooding'i engeller.
 * Sadece bucket sayısını değil, block update yoğunluğunu da takip eder.
 * Normal farm/mechanic'leri bozmaz.
 */
public final class FluidFloodProtectionModule implements Listener {

    private final NoLag plugin;


    private final Map<UUID, FluidActivityData> playerFluidData = new ConcurrentHashMap<>();


    private final Map<String, Integer> chunkFluidActivity = new ConcurrentHashMap<>();


    private volatile int globalFluidUpdatesInWindow = 0;

    private BukkitTask cleanupTask;
    private BukkitTask globalResetTask;


    private static final int PLAYER_WINDOW_MS = 3000; 
    private static final int CHUNK_WINDOW_MS = 2000; 
    private static final int GLOBAL_WINDOW_MS = 1000; 


    private static final int MAX_PLAYER_BUCKET_USES = 12; 
    private static final int MAX_CHUNK_FLUID_SPREADS = 40; 
    private static final int MAX_GLOBAL_UPDATES = 100; 

    public FluidFloodProtectionModule(NoLag plugin) {
        this.plugin = plugin;
        startTasks();
    }

    private void startTasks() {

        this.cleanupTask = new BukkitRunnable() {
            @Override
            public void run() {
                cleanupPlayerData();
            }
        }.runTaskTimer(plugin, 60L, 60L); 


        this.globalResetTask = new BukkitRunnable() {
            @Override
            public void run() {
                globalFluidUpdatesInWindow = 0;
                chunkFluidActivity.clear();
            }
        }.runTaskTimer(plugin, GLOBAL_WINDOW_MS / 50, GLOBAL_WINDOW_MS / 50);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        if (globalResetTask != null) {
            globalResetTask.cancel();
            globalResetTask = null;
        }
        playerFluidData.clear();
        chunkFluidActivity.clear();
    }

    private void cleanupPlayerData() {
        long now = System.currentTimeMillis();
        playerFluidData.entrySet().removeIf(entry ->
            (now - entry.getValue().lastUpdateTime) > PLAYER_WINDOW_MS
        );
    }

    private String getChunkKey(Block block) {
        return block.getWorld().getName() + ":" + (block.getX() >> 4) + ":" + (block.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerBucketUse(PlayerInteractEvent event) {
        if (!plugin.getConfig().getBoolean("features.fluid-flood-protection.enabled", true)) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("nolag.flood.bypass")) {
            return;
        }

        Material item = event.getItem() != null ? event.getItem().getType() : null;
        if (item == null) return;

        boolean isFluidBucket = (item == Material.WATER_BUCKET ||
                                 item == Material.LAVA_BUCKET ||
                                 item == Material.POWDER_SNOW_BUCKET);

        if (!isFluidBucket) return;

        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        FluidActivityData data = playerFluidData.computeIfAbsent(playerId,
            k -> new FluidActivityData());

        if ((now - data.windowStart) > PLAYER_WINDOW_MS) {
            data.windowStart = now;
            data.bucketUses = 0;
            data.warningLevel = 0;
        }

        data.lastUpdateTime = now;
        data.bucketUses++;

        if (data.bucketUses > MAX_PLAYER_BUCKET_USES) {
            handleExcessiveBucketUse(player, data);
        }
    }

    private void handleExcessiveBucketUse(Player player, FluidActivityData data) {
        if (data.warningLevel == 0) {

            plugin.getLogger().info("[NoLag] Player " + player.getName() +
                " using buckets rapidly (" + data.bucketUses + " uses in " +
                (PLAYER_WINDOW_MS/1000) + "s)");
            data.warningLevel = 1;

            String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
            player.sendMessage(plugin.color(prefix +
                "&eRapid fluid placement detected. Please slow down."));
        } else {



            if (System.currentTimeMillis() - data.lastWarningTime > 2000L) {
                player.sendMessage(plugin.color("&cToo many fluid placements! Wait a moment."));
                data.lastWarningTime = System.currentTimeMillis();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (!plugin.getConfig().getBoolean("features.fluid-flood-protection.enabled", true)) {
            return;
        }

        Material type = event.getBlock().getType();
        if (type != Material.WATER && type != Material.LAVA) {
            return;
        }

        Block block = event.getBlock();
        String chunkKey = getChunkKey(block);


        globalFluidUpdatesInWindow++;


        int chunkCount = chunkFluidActivity.merge(chunkKey, 1, Integer::sum);


        double tps = plugin.getTPSMonitor().getTPS();
        boolean serverUnderLoad = tps < 17.0;

        int chunkLimit = serverUnderLoad ? (MAX_CHUNK_FLUID_SPREADS / 2) : MAX_CHUNK_FLUID_SPREADS;
        int globalLimit = serverUnderLoad ? (MAX_GLOBAL_UPDATES / 2) : MAX_GLOBAL_UPDATES;


        if (chunkCount > chunkLimit) {

            Block sourceBlock = getSourceBlock(block, type);
            if (sourceBlock == null || sourceBlock.getType() != type) {

                event.setCancelled(true);

                if (plugin.getConfig().getBoolean("settings.debug", false)) {
                    plugin.getLogger().info("[NoLag] Fluid spread cancelled at " +
                        block.getLocation() + " (chunk limit exceeded)");
                }
            }
            return;
        }


        if (globalFluidUpdatesInWindow > globalLimit) {
            Block sourceBlock = getSourceBlock(block, type);
            if (sourceBlock == null || sourceBlock.getType() != type) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        if (!plugin.getConfig().getBoolean("features.fluid-flood-protection.enabled", true)) {
            return;
        }

        Block block = event.getBlock();
        Material type = block.getType();

        if (type != Material.WATER && type != Material.LAVA) {
            return;
        }


        Block toBlock = event.getToBlock();
        if (toBlock.getType().isSolid() || toBlock.isLiquid()) {
            return;
        }


        String chunkKey = getChunkKey(block);
        globalFluidUpdatesInWindow++;

        int chunkCount = chunkFluidActivity.merge(chunkKey, 1, Integer::sum);

        double tps = plugin.getTPSMonitor().getTPS();
        boolean serverUnderLoad = tps < 17.0;

        int chunkLimit = serverUnderLoad ? (MAX_CHUNK_FLUID_SPREADS / 2) : MAX_CHUNK_FLUID_SPREADS;

        if (chunkCount > chunkLimit) {

            Block sourceBlock = getSourceBlock(block, type);
            if (sourceBlock == null || sourceBlock.getType() != type) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Verilen block'un source olup olmadığını kontrol eder.
     * Levelled block'lar (level > 0) source değildir.
     */
    private Block getSourceBlock(Block block, Material fluidType) {

        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.UP || face == BlockFace.DOWN) continue;

            Block adjacent = block.getRelative(face);
            if (adjacent.getType() == fluidType) {


                return adjacent;
            }
        }
        return null;
    }

    private static class FluidActivityData {
        long windowStart = System.currentTimeMillis();
        int bucketUses = 0;
        long lastUpdateTime = System.currentTimeMillis();
        long lastWarningTime = 0;
        int warningLevel = 0;
    }
}
