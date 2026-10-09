package me.nolag.performance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;

public final class PerformanceImpactAnalyzer {

    public static final String CHUNK = "Chunk";
    public static final String CHUNK_GENERATION = "Chunk Generation";
    public static final String HOPPER = "Hopper";
    public static final String ENTITIES = "Entities";
    public static final String REDSTONE = "Redstone";
    public static final String PHYSICS = "Physics";
    public static final String FLUID = "Fluid";
    public static final String TNT = "TNT";
    public static final String CLEANUP = "Cleanup";
    public static final String CULLING = "Culling";
    public static final String MEMORY = "Memory";
    public static final String OTHER = "Other";

    private static final List<String> ORDER = List.of(
            CHUNK,
            CHUNK_GENERATION,
            ENTITIES,
            HOPPER,
            REDSTONE,
            PHYSICS,
            FLUID,
            TNT,
            CULLING,
            CLEANUP,
            MEMORY,
            OTHER
    );

    private final NoLag plugin;
    private final Map<String, CategoryStats> categoryStats = new ConcurrentHashMap<>();

    private TaskHandle sampleTask;
    private volatile boolean enabled = true;

    public PerformanceImpactAnalyzer(NoLag plugin) {
        this.plugin = plugin;

        for (String category : ORDER) {
            categoryStats.put(category, new CategoryStats(category));
        }

        start();
    }

    public void start() {
        stop();

        enabled = plugin.getConfig()
                .getBoolean("features.performance-analyzer.enabled", true);

        if (!enabled) {
            return;
        }

        sampleTask = plugin.getSchedulerAdapter()
                .runGlobalTimer(this::flushWindow, 20L, 20L);
    }

    public void stop() {
        if (sampleTask != null) {
            sampleTask.cancel();
            sampleTask = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void record(String categoryKey, long elapsedNanos) {
        record(categoryKey, elapsedNanos, 1);
    }

    public void record(String categoryKey, long elapsedNanos, int callCount) {
        if (!enabled || elapsedNanos <= 0L || callCount <= 0) {
            return;
        }

        String key = normalize(categoryKey);
        CategoryStats stats = categoryStats.computeIfAbsent(key, CategoryStats::new);

        stats.record(elapsedNanos, callCount);
    }

    public Timing startTiming(String category) {
        return new Timing(category, System.nanoTime(), this);
    }

    public Map<String, SystemSample> snapshot() {
        Map<String, SystemSample> result = new LinkedHashMap<>();

        List<String> keys = new ArrayList<>(ORDER);

        for (String key : categoryStats.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }

        for (String key : keys) {
            CategoryStats stats = categoryStats.get(key);

            if (stats != null) {
                result.put(key, stats.snapshot());
            } else {
                result.put(key, new SystemSample(key, 0L, 0.0D, 0.0D, 0.0D, 0.0D));
            }
        }

        return Collections.unmodifiableMap(result);
    }

    public void flushWindow() {
        for (CategoryStats stats : categoryStats.values()) {
            stats.rollWindow();
        }
    }

    private static String normalize(String categoryKey) {
        if (categoryKey == null || categoryKey.isBlank()) {
            return OTHER;
        }

        // Internal callers already use canonical category constants.
        // Avoid trim()/lowercase() allocations on these hot paths.
        String canonical = switch (categoryKey) {
            case CHUNK, CHUNK_GENERATION, HOPPER, ENTITIES, REDSTONE, PHYSICS, FLUID, TNT, CLEANUP, CULLING, MEMORY, OTHER -> categoryKey;
            default -> null;
        };
        if (canonical != null) return canonical;

        String key = categoryKey.trim().toLowerCase(Locale.ROOT);

        return switch (key) {
            case "chunk" -> CHUNK;
            case "chunkgeneration", "chunk-generation", "chunk generation" -> CHUNK_GENERATION;
            case "hopper" -> HOPPER;
            case "entity", "entities", "entityai", "mobai" -> ENTITIES;
            case "redstone" -> REDSTONE;
            case "physics" -> PHYSICS;
            case "fluid" -> FLUID;
            case "tnt" -> TNT;
            case "cleanup" -> CLEANUP;
            case "culling", "entityculling", "entity-culling" -> CULLING;
            case "memory", "ram", "ramfixer" -> MEMORY;
            default -> categoryKey;
        };
    }

    public record Timing(
            String category,
            long startNanos,
            PerformanceImpactAnalyzer analyzer
    ) implements AutoCloseable {

        public Timing(String category, PerformanceImpactAnalyzer analyzer) {
            this(category, System.nanoTime(), analyzer);
        }

        @Override
        public void close() {
            if (analyzer != null && analyzer.isEnabled()) {
                analyzer.record(category, System.nanoTime() - startNanos);
            }
        }
    }

    public record SystemSample(
            String category,
            long calls,
            double averageMs,
            double totalMs,
            double estimatedMspt,
            double activityIndex
    ) {}

    private static final class CategoryStats {

        private final String categoryName;

        private final LongAdder totalNanos = new LongAdder();
        private final LongAdder totalCalls = new LongAdder();

        private final LongAdder windowNanos = new LongAdder();
        private final LongAdder windowCalls = new LongAdder();

        private volatile double smoothedMspt;
        private volatile long lastWindowCalls;

        CategoryStats(String categoryName) {
            this.categoryName = categoryName;
        }

        void record(long elapsedNanos, int calls) {
            totalNanos.add(elapsedNanos);
            totalCalls.add(calls);

            windowNanos.add(elapsedNanos);
            windowCalls.add(calls);
        }

        void rollWindow() {
            long nanos = windowNanos.sumThenReset();
            long calls = windowCalls.sumThenReset();

            lastWindowCalls = calls;

            double currentMspt = (nanos / 1_000_000.0D) / 20.0D;

            if (smoothedMspt == 0.0D) {
                smoothedMspt = currentMspt;
            } else {
                smoothedMspt = (smoothedMspt * 0.65D) + (currentMspt * 0.35D);
            }
        }

        SystemSample snapshot() {
            long calls = totalCalls.sum();

            double totalMs = totalNanos.sum() / 1_000_000.0D;
            double averageMs = calls > 0 ? totalMs / calls : 0.0D;

            double activity = Math.min(
                    100.0D,
                    Math.max(
                            0.0D,
                            lastWindowCalls / 50.0D
                    )
            );

            return new SystemSample(
                    categoryName,
                    calls,
                    averageMs,
                    totalMs,
                    smoothedMspt,
                    activity
            );
        }
    }
}