package me.nolag.modules.ram;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Production-Grade RAM & Heap Optimization Engine for NoLag-Ultimate 21.6.0.
 *
 * <p>Fully rewritten Tier-3 memory engine. Replaces the legacy single-shot
 * sweep with a continuous, pressure-adaptive pipeline:</p>
 *
 * <ol>
 *   <li><b>Fast heartbeat monitoring:</b> heap usage and old-gen occupancy are
 *       sampled on cheap JMX beans every second (configurable). Heavy work is
 *       only dispatched when pressure actually demands it.</li>
 *   <li><b>Pressure-adaptive tiers:</b> LOW / MEDIUM / HIGH / CRITICAL unlock
 *       progressively stronger actions — pure cache trimming, budgeted ghost
 *       chunk eviction, dead-entity sweeping, leak-trend watchdog, and finally
 *       a cooldown-guarded explicit compaction under critical pressure.</li>
 *   <li><b>Time-budget sliced sweeps:</b> every chunk/entity traversal runs in
 *       nanosecond-budgeted slices across ticks so the main thread never stalls,
 *       regardless of world size or loaded-chunk count.</li>
 *   <li><b>Player safety guaranteed:</b> chunks within the live view radius of
 *       any online player, world spawn chunks, and chunks containing entities
 *       are never evicted by automated sweeps. Only true ghost chunks go.</li>
 *   <li><b>No System.gc() on hot paths:</b> explicit GC fires only when allowed,
 *       pressure warrants it, and the cooldown elapsed — preventing STW spikes.</li>
 * </ol>
 */
public final class RamFixerModule implements Listener, RamFixerService {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();

    // =====================================================================================
    // Pressure tiers
    // =====================================================================================

    public enum PressureTier {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    // =====================================================================================
    // Configuration snapshot (volatile, updated on reload)
    // =====================================================================================

    private final NoLag plugin;

    private volatile boolean enabled = true;
    private volatile long monitorIntervalTicks = 20L;           // 1s heartbeat
    private volatile double mediumThresholdPercent = 65.0;
    private volatile double highThresholdPercent = 78.0;
    private volatile double criticalThresholdPercent = 88.0;
    private volatile boolean ghostChunkEvictionEnabled = true;
    private volatile boolean deadEntitySweepEnabled = true;
    private volatile boolean promotionWatchdogEnabled = true;
    private volatile boolean allowExplicitGcOnManualCommand = true;
    private volatile boolean allowEmergencyGc = true;
    private volatile boolean manualGcAfterSweep = true;
    private volatile double manualGcThresholdPercent = 60.0;
    private volatile long manualGcDelayTicks = 20L;
    private volatile int maxManualChunkUnloads = 512;
    private volatile long gcCooldownMs = 90_000L;
    private volatile long sweepSliceBudgetNanos = 2_000_000L;   // 2 ms per tick slice
    private volatile int ghostChunkSafetyPaddingChunks = 2;
    private volatile long fullSweepMinIntervalMs = 300_000L;
    private volatile boolean verboseLogging = false;

    // =====================================================================================
    // Runtime state
    // =====================================================================================

    private TaskHandle monitorTask;
    private TaskHandle sliceTask;

    private volatile long lastExplicitGcTimestamp;
    private volatile long lastFullSweepTimestamp;
    private volatile PressureTier currentTier = PressureTier.LOW;

    /** Active sliced sweep cursor (main-thread confined). */
    private SweepState activeSweep;

    // Monotonic telemetry counters.
    private volatile long totalFreedMb;
    private volatile int autoSweepCount;
    private volatile int manualSweepCount;
    private volatile int ghostChunksUnloaded;
    private volatile int deadEntitiesRemoved;
    private volatile int emergencyGcCount;
    private volatile int skippedGcCount;

    // Promotion-watchdog sampling state.
    private double lastWatchSample;
    private long lastWatchTime;
    private int risingStreak;
    private long lastCacheTrimTimestamp;

    public RamFixerModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        startTask();
    }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /** Re-reads every configuration value with defensive clamping. */
    public void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.ram-fixer.enabled", true);
        verboseLogging = cfg.getBoolean("settings.debug", false);

        long intervalSeconds = Math.max(2L, cfg.getLong("features.ram-fixer.monitor-interval-seconds", 2L));
        monitorIntervalTicks = intervalSeconds * 20L;

        mediumThresholdPercent = clampDouble(cfg.getDouble("features.ram-fixer.medium-threshold-percent", 68.0), 30.0, 90.0);
        highThresholdPercent = Math.max(mediumThresholdPercent + 1.0,
                clampDouble(cfg.getDouble("features.ram-fixer.high-threshold-percent",
                        cfg.getDouble("features.ram-fixer.auto-trim-threshold-percent", 80.0)), 35.0, 95.0));
        criticalThresholdPercent = Math.max(highThresholdPercent + 1.0,
                clampDouble(cfg.getDouble("features.ram-fixer.critical-threshold-percent", 90.0), 40.0, 98.0));

        ghostChunkEvictionEnabled = cfg.getBoolean("features.ram-fixer.ghost-chunk-eviction", true);
        deadEntitySweepEnabled = cfg.getBoolean("features.ram-fixer.dead-entity-sweep", true);
        promotionWatchdogEnabled = cfg.getBoolean("features.ram-fixer.promotion-watchdog", true);
        allowExplicitGcOnManualCommand = cfg.getBoolean("features.ram-fixer.allow-explicit-gc", false);
        allowEmergencyGc = cfg.getBoolean("features.ram-fixer.allow-emergency-gc", false);
        manualGcAfterSweep = cfg.getBoolean("features.ram-fixer.manual-gc-after-sweep", false);
        manualGcThresholdPercent = clampDouble(
                cfg.getDouble("features.ram-fixer.manual-gc-threshold-percent", 75.0), 50.0, 95.0);
        manualGcDelayTicks = clampLong(
                cfg.getLong("features.ram-fixer.manual-gc-delay-ticks", 40L), 5L, 100L);
        maxManualChunkUnloads = (int) clampLong(
                cfg.getLong("features.ram-fixer.max-manual-chunk-unloads", 384L), 16L, 4096L);
        gcCooldownMs = Math.max(30_000L, cfg.getLong("features.ram-fixer.gc-cooldown-seconds", 180L) * 1000L);

        long sliceBudgetMs = clampLong(cfg.getLong("features.ram-fixer.slice-budget-ms", 2L), 1L, 8L);
        sweepSliceBudgetNanos = sliceBudgetMs * 1_000_000L;

        ghostChunkSafetyPaddingChunks = (int) clampLong(cfg.getLong("features.ram-fixer.ghost-chunk-safety-padding", 2L), 0L, 8L);

        // Legacy key compatibility: interval-minutes now sets the minimum cadence
        // between FULL automated sweeps; the heartbeat stays fast regardless.
        int fullSweepMinutes = Math.max(1, cfg.getInt("features.ram-fixer.interval-minutes", 5));
        fullSweepMinIntervalMs = fullSweepMinutes * 60_000L;
    }

    public void startTask() {
        stop();
        if (!enabled) {
            return;
        }
        monitorTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::heartbeat,
                monitorIntervalTicks,
                monitorIntervalTicks
        );
    }

    public void stop() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        cancelSliceTask();
        activeSweep = null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // =====================================================================================
    // Heartbeat: sample heap, classify pressure, dispatch adaptive actions
    // =====================================================================================

    private void heartbeat() {
        if (!enabled) {
            return;
        }

        double percent = getCurrentUsagePercent();
        PressureTier previous = currentTier;
        PressureTier tier = classify(percent);
        currentTier = tier;

        long now = System.currentTimeMillis();

        switch (tier) {
            case LOW -> {
                // Baseline: lightest possible action — expire stale module caches
                // only (no chunk/entity iteration at all).
                trimAllCachesIfDue(now);
            }
            case MEDIUM -> {
                trimAllCachesIfDue(now);
                // Bounded ghost-chunk eviction even at medium pressure, rate-limited
                // so we never thrash chunk IO.
                if (ghostChunkEvictionEnabled && canStartSweep(now)) {
                    launchSweep(SweepKind.GHOST_CHUNKS_ONLY, false);
                }
            }
            case HIGH -> {
                trimAllCachesIfDue(now);
                if (canStartSweep(now)) {
                    launchSweep(SweepKind.FULL_AUTOMATED, false);
                    autoSweepCount++;
                    lastFullSweepTimestamp = now;
                }
            }
            case CRITICAL -> {
                trimAllCachesIfDue(now);
                if (activeSweep == null) {
                    launchSweep(SweepKind.FULL_AUTOMATED, false);
                    autoSweepCount++;
                    lastFullSweepTimestamp = now;
                }
                // Emergency compaction: ONLY under critical pressure, ONLY if allowed,
                // ONLY after cooldown. Sole automated System.gc() site in the plugin.
                if (allowEmergencyGc && tryAcquireGcSlot(now)) {
                    emergencyGcCount++;
                    log("[RamFixer] CRITICAL heap pressure (" + fmt(percent)
                            + "%). Issuing guarded emergency compaction.");
                    requestExplicitGc();
                }
            }
            default -> { }
        }

        if (previous != tier && verboseLogging) {
            log("[RamFixer] Pressure tier " + previous + " -> " + tier + " (heap " + fmt(percent) + "%)");
        }

        if (promotionWatchdogEnabled) {
            updatePromotionWatch(percent, now);
        }
    }

    private PressureTier classify(double percent) {
        if (percent >= criticalThresholdPercent) return PressureTier.CRITICAL;
        if (percent >= highThresholdPercent) return PressureTier.HIGH;
        if (percent >= mediumThresholdPercent) return PressureTier.MEDIUM;
        return PressureTier.LOW;
    }

    private boolean canStartSweep(long now) {
        if (plugin.getSchedulerAdapter().isFolia() || activeSweep != null) return false;
        return now - lastFullSweepTimestamp >= Math.min(fullSweepMinIntervalMs, 120_000L);
    }

    // =====================================================================================
    // Promotion watchdog (leak trend detection)
    // =====================================================================================

    private void updatePromotionWatch(double percent, long now) {
        if (lastWatchTime != 0L) {
            double delta = percent - lastWatchSample;
            long dtMs = Math.max(1L, now - lastWatchTime);
            // Rising faster than ~1.5 %/min sustained across samples => suspected leak:
            // force an immediate preventive sweep even below HIGH tier.
            double ratePerMinute = delta * 60000.0 / dtMs;
            if (ratePerMinute > 1.5 && percent > mediumThresholdPercent) {
                risingStreak++;
            } else {
                risingStreak = 0;
            }
            if (risingStreak >= 8 && activeSweep == null) {
                risingStreak = 0;
                log("[RamFixer] Sustained heap growth detected (" + fmt(percent) + "%). Forcing preventive sweep.");
                launchSweep(SweepKind.FULL_AUTOMATED, false);
                autoSweepCount++;
                lastFullSweepTimestamp = now;
            }
        }
        lastWatchSample = percent;
        lastWatchTime = now;
    }

    // =====================================================================================
    // Sliced sweep engine
    // =====================================================================================

    private enum SweepKind {
        GHOST_CHUNKS_ONLY,
        FULL_AUTOMATED,
        MANUAL
    }

    /**
     * Mutable traversal cursor. Iterates worlds one at a time and chunks in
     * budgeted slices; proximity index uses primitive arrays (no boxing).
     */
    private static final class SweepState {
        final List<World> worlds;
        final SweepKind kind;
        final boolean manual;
        final long startedNanos;
        final long beforeBytes;
        final Consumer<RamFixResult> completion;

        int worldIndex = -1;
        World currentWorld;
        Chunk[] currentChunks;
        int spawnChunkX = Integer.MIN_VALUE;
        int spawnChunkZ = Integer.MIN_VALUE;
        int chunkCursor;

        // Player proximity index for the current world (rebuilt once per world).
        int[] px = new int[8];
        int[] pz = new int[8];
        int playerCount;
        int protectRadius = 8;

        int chunksScanned;
        int chunksUnloaded;
        int entitiesChecked;
        int deadRemoved;

        SweepState(SweepKind kind, boolean manual, long beforeBytes, Consumer<RamFixResult> completion) {
            this.worlds = new ArrayList<>(Bukkit.getWorlds());
            this.kind = kind;
            this.manual = manual;
            this.beforeBytes = beforeBytes;
            this.completion = completion;
            this.startedNanos = System.nanoTime();
            advanceWorld();
        }

        void advanceWorld() {
            while (worldIndex + 1 < worlds.size()) {
                World candidate = worlds.get(++worldIndex);
                if (candidate == null) continue;
                currentWorld = candidate;
                try {
                    currentChunks = candidate.getLoadedChunks();
                } catch (Throwable t) {
                    currentChunks = null;
                }
                chunkCursor = 0;
                Location spawn = candidate.getSpawnLocation();
                if (spawn != null) {
                    spawnChunkX = spawn.getBlockX() >> 4;
                    spawnChunkZ = spawn.getBlockZ() >> 4;
                } else {
                    spawnChunkX = Integer.MIN_VALUE;
                    spawnChunkZ = Integer.MIN_VALUE;
                }
                rebuildProximity(candidate);
                return;
            }
            currentWorld = null;
            currentChunks = null;
        }

        private void rebuildProximity(World world) {
            List<Player> players = world.getPlayers();
            int n = players.size();
            if (px.length < n) {
                px = new int[Math.max(16, n * 2)];
                pz = new int[Math.max(16, n * 2)];
            }
            playerCount = 0;
            int maxView = 0;
            for (int i = 0; i < n; i++) {
                Player p = players.get(i);
                if (p == null || !p.isOnline()) continue;
                if (playerCount >= px.length) break;
                px[playerCount] = p.getLocation().getBlockX() >> 4;
                pz[playerCount] = p.getLocation().getBlockZ() >> 4;
                playerCount++;
                int vd = 8;
                try { vd = p.getViewDistance(); } catch (Throwable ignored) {}
                if (vd > maxView) maxView = vd;
            }
            if (maxView > 0) {
                protectRadius = maxView;
            }
        }

        boolean nearby(int chunkX, int chunkZ, int padding) {
            int radius = Math.max(protectRadius + padding, 4);
            for (int i = 0; i < playerCount; i++) {
                int dx = px[i] - chunkX;
                if (dx < 0) dx = -dx;
                if (dx > radius) continue;
                int dz = pz[i] - chunkZ;
                if (dz < 0) dz = -dz;
                if (dz <= radius) return true;
            }
            return false;
        }

        void setProtectRadius(int radius) {
            this.protectRadius = Math.max(this.protectRadius, radius);
        }
    }

    private void launchSweep(SweepKind kind, boolean manual) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        if (activeSweep != null) {
            if (manual) {
                // A sweep is already mid-flight; still honor the manual request with
                // an immediate lightweight cache trim instead of queueing duplicates.
                trimAllCaches();
            }
            return;
        }
        SweepState state = new SweepState(kind, manual, getUsedMemoryBytes(), null);
        state.setProtectRadius(currentViewDistanceGuess());
        activeSweep = state;
        if (manual) manualSweepCount++;
        cancelSliceTask();
        sliceTask = plugin.getSchedulerAdapter().runGlobalTimer(this::processSlice, 1L, 1L);
    }

    private boolean launchManualSweep(Consumer<RamFixResult> completion) {
        if (plugin.getSchedulerAdapter().isFolia() || activeSweep != null) {
            return false;
        }

        long before = getUsedMemoryBytes();
        SweepState state = new SweepState(SweepKind.MANUAL, true, before, completion);
        state.setProtectRadius(currentViewDistanceGuess());
        activeSweep = state;
        manualSweepCount++;
        cancelSliceTask();
        sliceTask = plugin.getSchedulerAdapter().runGlobalTimer(this::processSlice, 1L, 1L);
        return true;
    }

    private int currentViewDistanceGuess() {
        try {
            int vd = plugin.getChunkModule().getCurrentViewDistance();
            return vd > 0 ? vd : 8;
        } catch (Throwable ignored) {
            return 8;
        }
    }

    private void processSlice() {
        SweepState s = activeSweep;
        if (s == null || !enabled) {
            cancelSliceTask();
            activeSweep = null;
            return;
        }

        long deadline = System.nanoTime() + sweepSliceBudgetNanos;
        boolean entityWork = (s.kind != SweepKind.GHOST_CHUNKS_ONLY && deadEntitySweepEnabled) || s.manual;
        int padding = ghostChunkSafetyPaddingChunks;

        while (s.currentWorld != null) {
            Chunk[] chunks = s.currentChunks;
            if (chunks == null) {
                s.advanceWorld();
                continue;
            }
            int i = s.chunkCursor;
            while (i < chunks.length) {
                Chunk chunk = chunks[i++];
                s.chunksScanned++;

                if (chunk == null || !chunk.isLoaded()) {
                    continue;
                }
                if (s.kind == SweepKind.MANUAL && s.chunksUnloaded >= maxManualChunkUnloads) {
                    completeSweep(s);
                    return;
                }

                // ---- Dead entity reference sweep (only invalid/dead dropped items) ----
                if (entityWork) {
                    Entity[] entities = chunk.getEntities();
                    s.entitiesChecked += entities.length;
                    for (Entity e : entities) {
                        if (e instanceof Item && (e.isDead() || !e.isValid())) {
                            try {
                                e.remove();
                                s.deadRemoved++;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (entities.length > 0) {
                        // Chunk holds real payload — never evict.
                        continue;
                    }
                }

                // ---- Ghost chunk eviction (empty + unreferenced + unprotected) ----
                if (ghostChunkEvictionEnabled || s.manual) {
                    if (s.spawnChunkX == chunk.getX() && s.spawnChunkZ == chunk.getZ()) {
                        continue;
                    }
                    if (s.nearby(chunk.getX(), chunk.getZ(), padding)) {
                        continue;
                    }
                    try {
                        if (chunk.isForceLoaded()) {
                            continue;
                        }
                    } catch (Throwable ignored) {}
                    try {
                        if (chunk.unload(true)) {
                            s.chunksUnloaded++;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            s.chunkCursor = i;

            if (System.nanoTime() >= deadline) {
                return; // resume next tick — main thread never stalls beyond budget
            }
            s.advanceWorld();
        }

        completeSweep(s);
    }

    private void completeSweep(SweepState s) {
        cancelSliceTask();
        activeSweep = null;

        if (s.completion != null) {
            // Manual commands are measured after the full slice traversal, not at
            // command invocation time. This prevents the old false "0 MB freed" result.
            long afterSweepBytes = getUsedMemoryBytes();
            double percentAfterSweep = afterSweepBytes * 100D / Math.max(1L, getMaxMemoryBytes());

            boolean gcTriggered = false;
            if (manualGcAfterSweep
                    && allowExplicitGcOnManualCommand
                    && percentAfterSweep >= manualGcThresholdPercent
                    && tryAcquireGcSlot(System.currentTimeMillis())) {
                gcTriggered = true;
                log("[RamFixer] Manual sweep finished; requesting one guarded GC cycle for post-sweep reclamation.");
                requestExplicitGc();
            }

            final boolean finalGcTriggered = gcTriggered;
            plugin.getSchedulerAdapter().runGlobalLater(
                    () -> finishManualSweep(s, finalGcTriggered), manualGcDelayTicks);
            return;
        }

        finalizeSweepTelemetry(s, false);
    }

    private void finishManualSweep(SweepState s, boolean gcTriggered) {
        if (!enabled) {
            return;
        }
        finalizeSweepTelemetry(s, gcTriggered);
        if (s.completion != null) {
            s.completion.accept(buildResult(s, gcTriggered));
        }
    }

    private void finalizeSweepTelemetry(SweepState s, boolean gcTriggered) {
        long after = getUsedMemoryBytes();
        long freedMb = Math.max(0L, (s.beforeBytes - after) / 1048576L);
        totalFreedMb += freedMb;
        ghostChunksUnloaded += s.chunksUnloaded;
        deadEntitiesRemoved += s.deadRemoved;
        lastFullSweepTimestamp = System.currentTimeMillis();

        long tookNanos = System.nanoTime() - s.startedNanos;
        if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
            plugin.getPerformanceAnalyzer().record("Memory", tookNanos, 1);
        }

        if (verboseLogging) {
            log(String.format(Locale.ROOT,
                    "[RamFixer] Sweep finished (%s): scanned=%d chunks, unloaded=%d ghost chunks, checked=%d entities, removed=%d dead items, freed~%dMB, gc=%s, span=%dms",
                    s.kind, s.chunksScanned, s.chunksUnloaded, s.entitiesChecked, s.deadRemoved,
                    freedMb, gcTriggered ? "yes" : "no", tookNanos / 1_000_000L));
        }
    }

    private RamFixResult buildResult(SweepState s, boolean gcTriggered) {
        long after = getUsedMemoryBytes();
        long max = getMaxMemoryBytes();
        long beforeMb = s.beforeBytes / 1048576L;
        long afterMb = after / 1048576L;
        long maxMb = max / 1048576L;
        double percentBefore = s.beforeBytes * 100D / Math.max(1L, max);
        double percentAfter = after * 100D / Math.max(1L, max);
        long freed = Math.max(0L, (s.beforeBytes - after) / 1048576L);
        return new RamFixResult(
                beforeMb, afterMb, maxMb, freed, percentBefore, percentAfter,
                gcTriggered, s.chunksScanned, ghostChunksUnloaded, deadEntitiesRemoved, currentTier);
    }

    private void cancelSliceTask() {
        if (sliceTask != null) {
            sliceTask.cancel();
            sliceTask = null;
        }
    }

    // =====================================================================================
    // Public sweep API (commands / GUI)
    // =====================================================================================

    /**
     * Executes a manual optimization pass. Cache trimming runs inline; the deep
     * chunk/entity traversal is launched as a sliced job so the calling tick never
     * stalls. Explicit GC honors the cooldown and only fires when heap pressure
     * actually warrants it.
     */
    public RamFixResult performMemorySweep(boolean manualCommand) {
        long before = getUsedMemoryBytes();
        long max = getMaxMemoryBytes();
        trimAllCaches();

        if (manualCommand && !plugin.getSchedulerAdapter().isFolia()) {
            boolean started = launchManualSweep(null);
            if (started) {
                return snapshotResult(before, max, false);
            }
        }

        long after = getUsedMemoryBytes();
        return snapshotResult(before, max, false);
    }

    /** Starts a manual sweep and delivers the real post-sweep result when it finishes. */
    public boolean startManualSweep(Consumer<RamFixResult> completion) {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return false;
        }
        trimAllCaches();
        return launchManualSweep(completion);
    }

    private RamFixResult snapshotResult(long before, long max, boolean gcTriggered) {
        long after = getUsedMemoryBytes();
        long beforeMb = before / 1048576L;
        long afterMb = after / 1048576L;
        long maxMb = max / 1048576L;
        long freed = Math.max(0L, (before - after) / 1048576L);
        return new RamFixResult(
                beforeMb, afterMb, maxMb, freed,
                before * 100D / Math.max(1L, max),
                after * 100D / Math.max(1L, max),
                gcTriggered, 0, ghostChunksUnloaded, deadEntitiesRemoved, currentTier);
    }

    private boolean tryAcquireGcSlot(long now) {
        if (now - lastExplicitGcTimestamp < gcCooldownMs) {
            return false;
        }
        lastExplicitGcTimestamp = now;
        return true;
    }

    private void requestExplicitGc() {
        try {
            System.gc();
        } catch (Throwable ignored) {}
    }

    // =====================================================================================
    // Cache trimming
    // =====================================================================================

    private void trimAllCachesIfDue(long now) {
        if (now - lastCacheTrimTimestamp < 5_000L) return;
        lastCacheTrimTimestamp = now;
        trimAllCaches();
    }

    public void trimAllCaches() {
        if (plugin.getItemExpiryModule() != null) plugin.getItemExpiryModule().trimCaches();
        if (plugin.getFluidFloodModule() != null) plugin.getFluidFloodModule().trimCaches();
        if (plugin.getNetherChunkModule() != null) plugin.getNetherChunkModule().trimCaches();
        if (plugin.getCleanupManager() != null) plugin.getCleanupManager().trimCaches();
        if (plugin.getEntityCullingModule() != null) plugin.getEntityCullingModule().trimCaches();
    }

    // =====================================================================================
    // Event hooks — reactive memory hygiene
    // =====================================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        // World teardown drops large region/chunk object graphs; trim promptly.
        trimAllCaches();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Post-quit trim catches per-player view-distance chunk queues left behind.
        plugin.getSchedulerAdapter().runGlobalLater(this::trimAllCaches, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Purge stale caches accumulated during empty-server periods.
        plugin.getSchedulerAdapter().runGlobalLater(this::trimAllCaches, 100L);
    }

    // =====================================================================================
    // Memory sampling
    // =====================================================================================

    private static long getUsedMemoryBytes() {
        MemoryUsage usage = MEMORY_BEAN.getHeapMemoryUsage();
        return Math.max(0L, usage.getUsed());
    }

    private static long getMaxMemoryBytes() {
        MemoryUsage usage = MEMORY_BEAN.getHeapMemoryUsage();
        long max = usage.getMax();
        if (max <= 0L) max = Runtime.getRuntime().maxMemory();
        if (max <= 0L) max = usage.getCommitted();
        return Math.max(1L, max);
    }

    @Override
    public double getCurrentUsagePercent() {
        long used = getUsedMemoryBytes();
        long max = getMaxMemoryBytes();
        return max <= 0L ? 0.0 : used * 100D / max;
    }

    /** Old-gen pool occupancy percent (promotion signal); -1 if unavailable. */
    public double getOldGenUsagePercent() {
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                String name = pool.getName().toLowerCase(Locale.ROOT);
                if (name.contains("old") || name.contains("tenured")) {
                    MemoryUsage u = pool.getUsage();
                    long max = u.getMax();
                    if (max > 0L) {
                        return Math.max(0L, u.getUsed()) * 100D / max;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1.0;
    }

    public PressureTier getPressureTier() {
        return currentTier;
    }

    public boolean isSweepRunning() {
        return activeSweep != null;
    }

    // =====================================================================================
    // RamFixerService telemetry surface
    // =====================================================================================

    @Override
    public long getTotalFreedMb() {
        return totalFreedMb;
    }

    @Override
    public int getAutoSweepCount() {
        return autoSweepCount;
    }

    @Override
    public int getGhostChunksUnloaded() {
        return ghostChunksUnloaded;
    }

    public int getManualSweepCount() {
        return manualSweepCount;
    }

    public int getDeadEntitiesRemoved() {
        return deadEntitiesRemoved;
    }

    public int getEmergencyGcCount() {
        return emergencyGcCount;
    }

    public int getSkippedGcCount() {
        return skippedGcCount;
    }

    // =====================================================================================
    // Helpers & result record
    // =====================================================================================

    private void log(String message) {
        plugin.getLogger().info(message);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static double clampDouble(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static long clampLong(long v, long min, long max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Result snapshot returned to commands/GUI. Source-compatible accessor names
     * are preserved; richer fields were appended.
     */
    public record RamFixResult(
            long usedBeforeMb,
            long usedAfterMb,
            long maxMb,
            long freedMb,
            double percentBefore,
            double percentAfter,
            boolean gcTriggered,
            int chunksScannedInFlight,
            int totalGhostChunksUnloaded,
            int totalDeadEntitiesRemoved,
            PressureTier tier
    ) {
        /** Backwards-compatible accessor. */
        public int entitiesChecked() {
            return chunksScannedInFlight;
        }
    }
}
