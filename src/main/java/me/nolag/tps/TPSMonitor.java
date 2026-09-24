package me.nolag.tps;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class TPSMonitor {

    private final NoLag plugin;
    private final AtomicLong lastTickTimestamp = new AtomicLong(System.currentTimeMillis());
    private volatile double currentTPS = 20.0;
    private volatile double currentMSPT = 10.0;
    private BukkitTask trackerTask;
    private BukkitTask spikeMonitorTask;

    public TPSMonitor(NoLag plugin) {
        this.plugin = plugin;
        start();
    }

    public void start() {
        stop();


        this.trackerTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateMetrics();
            }
        }.runTaskTimer(plugin, 20L, 20L);


        double threshold = plugin.getConfig().getDouble("atlas.lag-spike-threshold-ms", 150.0);
        if (plugin.getConfig().getBoolean("settings.debug", false) || threshold > 0) {
            this.spikeMonitorTask = new BukkitRunnable() {
                private long lastTick = System.currentTimeMillis();

                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    long duration = now - lastTick;
                    lastTick = now;

                    double spikeThreshold = plugin.getConfig().getDouble("atlas.lag-spike-threshold-ms", 150.0);
                    if (spikeThreshold > 0 && duration >= spikeThreshold) {
                        handleLagSpike(duration);
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
        }
    }

    public void stop() {
        if (trackerTask != null) {
            trackerTask.cancel();
            trackerTask = null;
        }
        if (spikeMonitorTask != null) {
            spikeMonitorTask.cancel();
            spikeMonitorTask = null;
        }
    }

    private void updateMetrics() {
        boolean updatedTPS = false;
        boolean updatedMSPT = false;


        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray != null && tpsArray.length > 0) {
                this.currentTPS = Math.min(20.0, Math.max(0.0, tpsArray[0]));
                updatedTPS = true;
            }
        } catch (Throwable ignored) {
        }


        try {
            this.currentMSPT = Math.max(0.0, Bukkit.getAverageTickTime());
            updatedMSPT = true;
        } catch (Throwable ignored) {
        }


        long now = System.currentTimeMillis();
        long diff = now - lastTickTimestamp.getAndSet(now);
        if (!updatedTPS) {
            if (diff > 50L && diff < 10000L) {
                double calculatedTPS = (20.0 * 1000.0) / diff;
                this.currentTPS = (this.currentTPS * 0.8) + (Math.min(20.0, calculatedTPS) * 0.2);
            }
        }

        if (!updatedMSPT) {
            this.currentMSPT = 1000.0 / Math.max(0.1, this.currentTPS);
        }
    }

    private void handleLagSpike(long durationMs) {
        World heaviestWorld = null;
        int maxEntities = -1;

        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            int entityCount = world.getEntityCount();
            if (entityCount > maxEntities) {
                maxEntities = entityCount;
                heaviestWorld = world;
            }
        }

        if (heaviestWorld != null && maxEntities >= 0) {
            plugin.getLogger().warning(String.format(
                    Locale.ROOT,
                    "[NoLag] Lag Spike detected! Tick duration: %dms | World: %s (%d entities, %d loaded chunks)",
                    durationMs,
                    heaviestWorld.getName(),
                    maxEntities,
                    heaviestWorld.getLoadedChunks().length
            ));
        }
    }

    public double getTPS() {
        return this.currentTPS;
    }

    public double getMSPT() {
        return this.currentMSPT;
    }

    public static MemoryStats getMemoryStats() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();

        long maxBytes = heapUsage.getMax();
        if (maxBytes <= 0) {
            maxBytes = Runtime.getRuntime().maxMemory();
        }
        if (maxBytes <= 0) {
            maxBytes = heapUsage.getCommitted();
        }

        long usedBytes = heapUsage.getUsed();
        long maxMb = Math.max(1, maxBytes / (1024 * 1024));
        long usedMb = usedBytes / (1024 * 1024);
        long freeMb = Math.max(0, maxMb - usedMb);
        double usagePercent = ((double) usedMb / (double) maxMb) * 100.0;

        return new MemoryStats(maxMb, usedMb, freeMb, usagePercent);
    }

    public record MemoryStats(long maxMb, long usedMb, long freeMb, double usagePercent) {}
}

