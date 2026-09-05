package me.nolag.modules.chunk;

import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class ChunkOptimizationModule implements Listener {

    private final NoLag plugin;
    private int currentViewDistance = 8;
    private BukkitTask dynamicDistanceTask;
    private BukkitTask chunkCleanerTask;

    public ChunkOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        startTasks();
    }

    public void startTasks() {
        stopTasks();

        // 1. Dynamic View Distance
        if (plugin.getConfig().getBoolean("features.dynamic-view-distance", true)) {
            this.dynamicDistanceTask = new BukkitRunnable() {
                @Override
                public void run() {
                    updateViewDistance();
                }
            }.runTaskTimer(plugin, 600L, 600L); // Check every 30s
        }

        // 2. Chunk Cleaner
        if (plugin.getConfig().getBoolean("optimization.chunk-cleaner.enabled", true)) {
            int intervalMinutes = Math.max(1, plugin.getConfig().getInt("optimization.chunk-cleaner.interval-minutes", 8));
            long intervalTicks = intervalMinutes * 60L * 20L;

            this.chunkCleanerTask = new BukkitRunnable() {
                @Override
                public void run() {
                    cleanInactiveChunks();
                }
            }.runTaskTimer(plugin, 1200L, intervalTicks);
        }
    }

    public void stopTasks() {
        if (dynamicDistanceTask != null) {
            dynamicDistanceTask.cancel();
            dynamicDistanceTask = null;
        }
        if (chunkCleanerTask != null) {
            chunkCleanerTask.cancel();
            chunkCleanerTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getConfig().getBoolean("features.dynamic-view-distance", true)) {
            Player player = event.getPlayer();
            try {
                player.setViewDistance(this.currentViewDistance);
                player.setSimulationDistance(Math.max(3, this.currentViewDistance - 1));
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateViewDistance() {
        double tps = plugin.getTPSMonitor().getTPS();
        int minVD = plugin.getConfig().getInt("settings.view-distance.min", 4);
        int maxVD = plugin.getConfig().getInt("settings.view-distance.max", 8);

        int targetDistance;
        if (tps < 15.0) {
            targetDistance = minVD;
        } else if (tps < 18.0) {
            targetDistance = Math.max(minVD, (minVD + maxVD) / 2);
        } else {
            targetDistance = maxVD;
        }

        if (targetDistance != this.currentViewDistance) {
            this.currentViewDistance = targetDistance;
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    player.setViewDistance(this.currentViewDistance);
                    player.setSimulationDistance(Math.max(3, this.currentViewDistance - 1));
                } catch (Throwable ignored) {
                }
            }

            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                plugin.getLogger().info("[NoLag] Dynamic View Distance set to: " + this.currentViewDistance);
            }
        }
    }

    public int cleanInactiveChunks() {
        int unloadedCount = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                // If chunk has no players nearby and is not force loaded
                if (!chunk.isForceLoaded() && !world.isChunkInUse(chunk.getX(), chunk.getZ())) {
                    if (world.unloadChunkRequest(chunk.getX(), chunk.getZ())) {
                        unloadedCount++;
                    }
                }
            }
        }

        if (unloadedCount > 0 && plugin.getConfig().getBoolean("settings.debug", false)) {
            plugin.getLogger().info("[NoLag] Chunk Cleaner unloaded " + unloadedCount + " inactive chunks.");
        }
        return unloadedCount;
    }

    public int getCurrentViewDistance() {
        return this.currentViewDistance;
    }
}

