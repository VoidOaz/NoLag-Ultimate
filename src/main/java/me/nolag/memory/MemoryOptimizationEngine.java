package me.nolag.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;

public final class MemoryOptimizationEngine {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private final NoLag plugin;
    private TaskHandle maintenanceTask;
    private volatile boolean enabled = true;
    private volatile long maintenanceCooldownMs = 30_000L;
    private volatile long lastMaintenanceMs = 0L;

    public MemoryOptimizationEngine(NoLag plugin) {
        this.plugin = plugin;
        reload();
        start();
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("features.memory-optimization.enabled", true);
        long configuredCooldownSeconds = Math.max(5L, plugin.getConfig().getLong("features.memory-optimization.trim-cooldown-seconds", 30L));
        maintenanceCooldownMs = configuredCooldownSeconds * 1_000L;
    }

    public void start() {
        stop();

        if (!enabled) {
            return;
        }

        int intervalSeconds = Math.max(10, plugin.getConfig().getInt("features.memory-optimization.scan-interval-seconds", 30));
        long intervalTicks = 20L * intervalSeconds;

        maintenanceTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::performMaintenance,
                intervalTicks,
                intervalTicks
        );
    }

    public void stop() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public MemorySnapshot getSnapshot() {
        MemoryUsage heapUsage = MEMORY_BEAN.getHeapMemoryUsage();

        long maxBytes = heapUsage.getMax();

        if (maxBytes <= 0L) {
            maxBytes = Runtime.getRuntime().maxMemory();
        }

        if (maxBytes <= 0L) {
            maxBytes = heapUsage.getCommitted();
        }

        long usedBytes = Math.max(0L, heapUsage.getUsed());

        long maxMb = Math.max(1L, maxBytes / 1048576L);
        long usedMb = usedBytes / 1048576L;
        long freeMb = Math.max(0L, maxMb - usedMb);

        double usagePercent = maxBytes > 0L ? usedBytes * 100.0D / maxBytes : 0.0D;

        long gcCount = 0L;

        for (GarbageCollectorMXBean gc : GC_BEANS) {
            long count = gc.getCollectionCount();

            if (count > 0L) {
                gcCount += count;
            }
        }

        Pressure pressure;

        if (usagePercent >= 85.0D) {
            pressure = Pressure.HIGH;
        } else if (usagePercent >= 70.0D) {
            pressure = Pressure.MEDIUM;
        } else {
            pressure = Pressure.LOW;
        }

        String activity = switch (pressure) {
            case HIGH -> "high";
            case MEDIUM -> "moderate";
            case LOW -> "normal";
        };

        return new MemorySnapshot(
                maxMb,
                usedMb,
                freeMb,
                usagePercent,
                gcCount,
                activity,
                pressure
        );
    }

    public void performMaintenance() {
        if (!enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastMaintenanceMs < maintenanceCooldownMs) {
            return;
        }
        lastMaintenanceMs = now;

        MemorySnapshot snapshot = getSnapshot();

        if (snapshot.pressure() == Pressure.LOW) {
            return;
        }

        if (plugin.getItemExpiryModule() != null) {
            plugin.getItemExpiryModule().trimCaches();
        }

        if (plugin.getFluidFloodModule() != null) {
            plugin.getFluidFloodModule().trimCaches();
        }

        if (plugin.getNetherChunkModule() != null) {
            plugin.getNetherChunkModule().trimCaches();
        }

        if (plugin.getCleanupManager() != null) {
            plugin.getCleanupManager().trimCaches();
        }
    }

    public enum Pressure {
        LOW,
        MEDIUM,
        HIGH
    }

    public record MemorySnapshot(
            long maxMb,
            long usedMb,
            long freeMb,
            double usagePercent,
            long gcCount,
            String gcActivity,
            Pressure pressure
    ) {}
}