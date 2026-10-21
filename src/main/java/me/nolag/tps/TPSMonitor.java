package me.nolag.tps;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.World;

public final class TPSMonitor {

    private final NoLag plugin;
    private final AtomicLong lastTickTimestamp = new AtomicLong(System.currentTimeMillis());
    private volatile double currentTPS = 20.0;
    private volatile double currentMSPT = 10.0;
    private TaskHandle trackerTask;
    private TaskHandle spikeMonitorTask;
    private long lastLagSpikeLogTime = 0L;
    private static final long SPIKE_LOG_COOLDOWN_MS = 10_000L;
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private long lastServerThreadCpuNanos = -1L;
    private long lastCpuSampleTimestamp = -1L;
    private long serverThreadId = -1L;

    public TPSMonitor(NoLag plugin) {
        this.plugin = plugin;
        start();
    }

    public void start() {
        stop();


        this.trackerTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::updateMetrics,
                20L,
                20L
        );


        double threshold = plugin.getConfig().getDouble("atlas.lag-spike-threshold-ms", 150.0);
        if (plugin.getConfig().getBoolean("settings.debug", false) || threshold > 0) {
            this.spikeMonitorTask = plugin.getSchedulerAdapter().runGlobalTimer(
                    new Runnable() {
                        private long lastTick = System.currentTimeMillis();

                        @Override
                        public void run() {
                            long now = System.currentTimeMillis();
                            long duration = now - lastTick;
                            lastTick = now;

                            double spikeThreshold = plugin.getConfig().getDouble("atlas.lag-spike-threshold-ms", 150.0);
                            if (spikeThreshold > 0 && duration >= spikeThreshold) {
                                handleLagSpike(duration, now);
                            }
                        }
                    },
                    1L,
                    1L
            );
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
        long now = System.currentTimeMillis();
        long diff = now - lastTickTimestamp.getAndSet(now);

        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray != null && tpsArray.length > 0 && Double.isFinite(tpsArray[0]) && tpsArray[0] > 0.0) {
                currentTPS = Math.min(20.0, Math.max(0.0, tpsArray[0]));
                updatedTPS = true;
            }
        } catch (Throwable ignored) {
        }

        try {
            double nativeMspt = Bukkit.getAverageTickTime();
            if (Double.isFinite(nativeMspt) && nativeMspt > 0.0) {
                currentMSPT = Math.max(0.0, nativeMspt);
                updatedMSPT = true;
            }
        } catch (Throwable ignored) {
        }

        if (!updatedTPS && diff > 0L && diff < 10_000L) {
            double calculatedTPS = 20_000.0 / diff;
            currentTPS = currentTPS * 0.8 + Math.min(20.0, Math.max(0.0, calculatedTPS)) * 0.2;
        }

        if (!updatedMSPT) {
            double cpuEstimate = sampleServerThreadCpuMspt(now);
            if (cpuEstimate > 0.0 && Double.isFinite(cpuEstimate)) {
                currentMSPT = Math.max(0.0, cpuEstimate);
            } else {
                currentMSPT = 1000.0 / Math.max(0.1, currentTPS);
            }
        } else {
            sampleServerThreadCpuMspt(now);
        }
    }

    private double sampleServerThreadCpuMspt(long now) {
        long currentThreadId = Thread.currentThread().getId();
        long cpuNow;
        try {
            if (!THREAD_BEAN.isThreadCpuTimeSupported()) {
                return 0.0;
            }
            if (!THREAD_BEAN.isThreadCpuTimeEnabled()) {
                THREAD_BEAN.setThreadCpuTimeEnabled(true);
            }
            cpuNow = THREAD_BEAN.getThreadCpuTime(currentThreadId);
        } catch (Throwable ignored) {
            return 0.0;
        }
        if (cpuNow < 0L) {
            return 0.0;
        }
        long previousCpu = lastServerThreadCpuNanos;
        long previousTimestamp = lastCpuSampleTimestamp;
        long previousThreadId = serverThreadId;
        lastServerThreadCpuNanos = cpuNow;
        lastCpuSampleTimestamp = now;
        serverThreadId = currentThreadId;
        if (previousCpu < 0L || previousTimestamp < 0L || previousThreadId != currentThreadId) {
            return 0.0;
        }
        long elapsedMs = Math.max(1L, now - previousTimestamp);
        long elapsedTicks = Math.max(1L, Math.round(elapsedMs / 50.0));
        long cpuDelta = Math.max(0L, cpuNow - previousCpu);
        return (cpuDelta / 1_000_000.0) / elapsedTicks;
    }

    private void handleLagSpike(long durationMs, long now) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        if (now - lastLagSpikeLogTime < SPIKE_LOG_COOLDOWN_MS) {
            return;
        }
        lastLagSpikeLogTime = now;

        World heaviestWorld = null;
        int maxEntities = -1;

        for (World world : Bukkit.getWorlds()) {
            if (world == null) continue;
            int entityCount = getEntityCountSafe(world);
            if (entityCount > maxEntities) {
                maxEntities = entityCount;
                heaviestWorld = world;
            }
        }

        if (heaviestWorld != null) {
            int chunkCount = getLoadedChunkCount(heaviestWorld);
            plugin.getLogger().warning(String.format(
                    Locale.ROOT,
                    "[NoLag] Lag Spike detected! Tick duration: %dms | World: %s (%d entities, %d loaded chunks)",
                    durationMs,
                    heaviestWorld.getName(),
                    maxEntities,
                    chunkCount
            ));
        }
    }


    public static int getEntityCountSafe(World world) {
        if (world == null) return 0;
        try {
            return world.getEntityCount();
        } catch (Throwable ignored) {
            try {
                return world.getEntities().size();
            } catch (Throwable ignoredAgain) {
                return 0;
            }
        }
    }

    public static int getLoadedChunkCount(World world) {
        if (world == null) return 0;
        try {
            return world.getChunkCount();
        } catch (Exception | NoSuchMethodError ignored) {
            try {
                return world.getLoadedChunks().length;
            } catch (Exception e) {
                return 0;
            }
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

        long usedBytes = Math.max(0L, heapUsage.getUsed());
        long maxMb = Math.max(1, maxBytes / (1024 * 1024));
        long usedMb = usedBytes / (1024 * 1024);
        long freeMb = Math.max(0, maxMb - usedMb);
        double usagePercent = maxMb > 0 ? ((double) usedMb / (double) maxMb) * 100.0 : 0.0;

        return new MemoryStats(maxMb, usedMb, freeMb, usagePercent);
    }

    public record MemoryStats(long maxMb, long usedMb, long freeMb, double usagePercent) {}
}
