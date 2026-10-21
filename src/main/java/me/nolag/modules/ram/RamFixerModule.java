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
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;


public final class RamFixerModule implements Listener, RamFixerService {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();


    public enum PressureTier {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }


    private final NoLag plugin;
    private static final int FOLIA_REGION_TASKS_PER_TICK = 24;
    private final Set<FoliaChunkKey> foliaLoadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> foliaKnownPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, FoliaPlayerPosition> foliaPlayerPositions = new ConcurrentHashMap<>();
    private volatile FoliaSweepState foliaSweepState;
    private TaskHandle foliaDispatchTask;

    private volatile boolean enabled = true;
    private volatile long monitorIntervalTicks = 20L;
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
    private volatile long sweepSliceBudgetNanos = 2_000_000L;
    private volatile int ghostChunkSafetyPaddingChunks = 2;
    private volatile long fullSweepMinIntervalMs = 300_000L;
    private volatile boolean verboseLogging = false;


    private TaskHandle monitorTask;
    private TaskHandle sliceTask;

    private volatile long lastExplicitGcTimestamp;
    private volatile boolean foliaManualSweepRunning;
    private volatile long lastFullSweepTimestamp;
    private volatile PressureTier currentTier = PressureTier.LOW;


    private SweepState activeSweep;


    private volatile long totalFreedMb;
    private volatile int autoSweepCount;
    private volatile int manualSweepCount;
    private volatile int ghostChunksUnloaded;
    private volatile int deadEntitiesRemoved;
    private volatile int emergencyGcCount;
    private volatile int skippedGcCount;


    private double lastWatchSample;
    private long lastWatchTime;
    private int risingStreak;
    private long lastCacheTrimTimestamp;

    public RamFixerModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        startTask();
    }


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
        allowExplicitGcOnManualCommand = cfg.getBoolean("features.ram-fixer.allow-explicit-gc", true);
        allowEmergencyGc = cfg.getBoolean("features.ram-fixer.allow-emergency-gc", true);
        manualGcAfterSweep = cfg.getBoolean("features.ram-fixer.manual-gc-after-sweep", true);
        manualGcThresholdPercent = clampDouble(
                cfg.getDouble("features.ram-fixer.manual-gc-threshold-percent", 50.0), 30.0, 95.0);
        manualGcDelayTicks = clampLong(
                cfg.getLong("features.ram-fixer.manual-gc-delay-ticks", 20L), 5L, 100L);
        maxManualChunkUnloads = (int) clampLong(
                cfg.getLong("features.ram-fixer.max-manual-chunk-unloads", 512L), 16L, 4096L);
        gcCooldownMs = Math.max(15_000L, cfg.getLong("features.ram-fixer.gc-cooldown-seconds", 60L) * 1000L);

        long sliceBudgetMs = clampLong(cfg.getLong("features.ram-fixer.slice-budget-ms", 2L), 1L, 8L);
        sweepSliceBudgetNanos = sliceBudgetMs * 1_000_000L;

        ghostChunkSafetyPaddingChunks = (int) clampLong(cfg.getLong("features.ram-fixer.ghost-chunk-safety-padding", 2L), 0L, 8L);


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
        if (foliaDispatchTask != null) {
            foliaDispatchTask.cancel();
            foliaDispatchTask = null;
        }
        foliaSweepState = null;
        synchronized (this) {
            foliaManualSweepRunning = false;
        }
        activeSweep = null;
    }

    public boolean isEnabled() {
        return enabled;
    }


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


                trimAllCachesIfDue(now);
            }
            case MEDIUM -> {
                trimAllCachesIfDue(now);


                if (ghostChunkEvictionEnabled && canStartSweep(now)) {
                    launchSweep(SweepKind.GHOST_CHUNKS_ONLY, false);
                }
            }
            case HIGH -> {
                trimAllCachesIfDue(now);
                if (canStartSweep(now) && launchSweep(SweepKind.FULL_AUTOMATED, false)) {
                    autoSweepCount++;
                    lastFullSweepTimestamp = now;
                }
            }
            case CRITICAL -> {
                trimAllCachesIfDue(now);
                if (canStartSweep(now) && launchSweep(SweepKind.FULL_AUTOMATED, false)) {
                    autoSweepCount++;
                    lastFullSweepTimestamp = now;
                }
                if (allowEmergencyGc && tryAcquireGcSlot(now)) {
                    if (requestExplicitGc()) {
                        emergencyGcCount++;
                        log("[RamFixer] CRITICAL heap pressure (" + fmt(percent)
                                + "%). Issuing guarded emergency compaction.");
                    }
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
        if (plugin.getSchedulerAdapter().isFolia()) {
            return !foliaManualSweepRunning && foliaSweepState == null
                    && now - lastFullSweepTimestamp >= fullSweepMinIntervalMs;
        }
        return activeSweep == null && now - lastFullSweepTimestamp >= fullSweepMinIntervalMs;
    }


    private void updatePromotionWatch(double percent, long now) {
        if (lastWatchTime != 0L) {
            double delta = percent - lastWatchSample;
            long dtMs = Math.max(1L, now - lastWatchTime);


            double ratePerMinute = delta * 60000.0 / dtMs;
            if (ratePerMinute > 1.5 && percent > mediumThresholdPercent) {
                risingStreak++;
            } else {
                risingStreak = 0;
            }
            if (risingStreak >= 8 && canStartSweep(now)) {
                risingStreak = 0;
                if (launchSweep(SweepKind.FULL_AUTOMATED, false)) {
                    log("[RamFixer] Sustained heap growth detected (" + fmt(percent) + "%). Forcing preventive sweep.");
                    autoSweepCount++;
                    lastFullSweepTimestamp = now;
                }
            }
        }
        lastWatchSample = percent;
        lastWatchTime = now;
    }


    private enum SweepKind {
        GHOST_CHUNKS_ONLY,
        FULL_AUTOMATED,
        MANUAL
    }


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


        int[] px = new int[8];
        int[] pz = new int[8];
        int playerCount;
        int protectRadius = 8;

        Chunk activeChunk;
        Entity[] activeEntities;
        int entityCursor;
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
                Location spawn = null;
                try {
                    spawn = candidate.getSpawnLocation();
                } catch (Throwable ignored) {
                }
                if (spawn != null) {
                    spawnChunkX = spawn.getBlockX() >> 4;
                    spawnChunkZ = spawn.getBlockZ() >> 4;
                } else {
                    spawnChunkX = Integer.MIN_VALUE;
                    spawnChunkZ = Integer.MIN_VALUE;
                }
                activeChunk = null;
                activeEntities = null;
                entityCursor = 0;
                rebuildProximity(candidate);
                return;
            }
            currentWorld = null;
            currentChunks = null;
            activeChunk = null;
            activeEntities = null;
            entityCursor = 0;
        }

        private void rebuildProximity(World world) {
            List<Player> players;
            try {
                players = world.getPlayers();
            } catch (Throwable ignored) {
                players = List.of();
            }
            int n = players.size();
            if (px.length < n) {
                px = new int[Math.max(16, n * 2)];
                pz = new int[Math.max(16, n * 2)];
            }
            playerCount = 0;
            int maxView = 8;
            for (int i = 0; i < n; i++) {
                Player p = players.get(i);
                if (p == null || !p.isOnline()) continue;
                if (playerCount >= px.length) break;
                try {
                    Location location = p.getLocation();
                    px[playerCount] = location.getBlockX() >> 4;
                    pz[playerCount] = location.getBlockZ() >> 4;
                    playerCount++;
                    int viewDistance = p.getViewDistance();
                    if (viewDistance > maxView) maxView = viewDistance;
                } catch (Throwable ignored) {
                }
            }
            protectRadius = maxView;
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

    private boolean launchSweep(SweepKind kind, boolean manual) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            synchronized (this) {
                if (foliaManualSweepRunning || foliaSweepState != null) {
                    return false;
                }
                foliaManualSweepRunning = true;
            }
            TaskHandle startHandle = plugin.getSchedulerAdapter().runGlobal(
                    () -> beginFoliaSweep(null, false, kind));
            if (startHandle.isCancelled()) {
                synchronized (this) {
                    foliaManualSweepRunning = false;
                }
                return false;
            }
            return true;
        }
        if (activeSweep != null) {
            if (manual) {
                trimAllCaches();
            }
            return false;
        }
        SweepState state = new SweepState(kind, manual, getUsedMemoryBytes(), null);
        state.setProtectRadius(currentViewDistanceGuess());
        activeSweep = state;
        if (manual) {
            manualSweepCount++;
        }
        cancelSliceTask();
        sliceTask = plugin.getSchedulerAdapter().runGlobalTimer(this::processSlice, 1L, 1L);
        return true;
    }

    private boolean launchManualSweep(Consumer<RamFixResult> completion, long beforeBytes) {
        if (plugin.getSchedulerAdapter().isFolia() || activeSweep != null) {
            return false;
        }

        SweepState state = new SweepState(SweepKind.MANUAL, true, beforeBytes, completion);
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
        SweepState state = activeSweep;
        if (state == null || !enabled) {
            cancelSliceTask();
            activeSweep = null;
            return;
        }
        try {
            processSliceWork(state);
        } catch (Throwable failure) {
            cancelSliceTask();
            if (activeSweep == state) {
                activeSweep = null;
            }
            if (verboseLogging) {
                log("[RamFixer] Sweep aborted safely after " + failure.getClass().getSimpleName());
            }
        }
    }

    private void processSliceWork(SweepState state) {
        if (state.currentWorld != null) {
            state.rebuildProximity(state.currentWorld);
            state.setProtectRadius(currentViewDistanceGuess());
        }
        long deadline = System.nanoTime() + sweepSliceBudgetNanos;
        boolean entityCleanup = (state.kind != SweepKind.GHOST_CHUNKS_ONLY && deadEntitySweepEnabled) || state.manual;
        boolean inspectEntities = entityCleanup || ghostChunkEvictionEnabled || state.manual;
        int padding = ghostChunkSafetyPaddingChunks;

        while (state.currentWorld != null) {
            if (System.nanoTime() >= deadline) {
                return;
            }

            Chunk[] chunks = state.currentChunks;
            if (chunks == null || (state.activeChunk == null && state.chunkCursor >= chunks.length)) {
                state.advanceWorld();
                continue;
            }

            if (state.activeChunk == null) {
                int slot = state.chunkCursor++;
                state.activeChunk = chunks[slot];
                chunks[slot] = null;
                state.chunksScanned++;
            }

            Chunk chunk = state.activeChunk;
            if (chunk == null || !chunk.isLoaded()) {
                clearActiveChunk(state);
                continue;
            }

            if (state.kind == SweepKind.MANUAL && state.chunksUnloaded >= maxManualChunkUnloads) {
                completeSweep(state);
                return;
            }

            if (inspectEntities) {
                if (state.activeEntities == null) {
                    try {
                        state.activeEntities = chunk.getEntities();
                    } catch (Throwable ignored) {
                        clearActiveChunk(state);
                        continue;
                    }
                    if (state.activeEntities == null) {
                        clearActiveChunk(state);
                        continue;
                    }
                    state.entitiesChecked += state.activeEntities.length;
                    state.entityCursor = 0;
                }
                while (state.entityCursor < state.activeEntities.length) {
                    Entity entity = state.activeEntities[state.entityCursor++];
                    if (entityCleanup && entity instanceof Item && (entity.isDead() || !entity.isValid())) {
                        try {
                            entity.remove();
                            state.deadRemoved++;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (System.nanoTime() >= deadline) {
                        return;
                    }
                }
                if (state.activeEntities.length > 0) {
                    clearActiveChunk(state);
                    continue;
                }
            }

            boolean canEvict = ghostChunkEvictionEnabled || state.manual;
            if (canEvict) {
                boolean nearSpawn = state.spawnChunkX != Integer.MIN_VALUE
                        && Math.abs(chunk.getX() - state.spawnChunkX) <= 2
                        && Math.abs(chunk.getZ() - state.spawnChunkZ) <= 2;
                if (!nearSpawn) {
                    if (!state.nearby(chunk.getX(), chunk.getZ(), padding)) {
                        boolean forceLoaded = false;
                        try {
                            forceLoaded = chunk.isForceLoaded();
                        } catch (Throwable ignored) {
                        }
                        if (!forceLoaded) {
                            try {
                                if (chunk.unload(true)) {
                                    state.chunksUnloaded++;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            clearActiveChunk(state);
        }

        completeSweep(state);
    }

    private void clearActiveChunk(SweepState state) {
        state.activeChunk = null;
        state.activeEntities = null;
        state.entityCursor = 0;
    }

    private void completeSweep(SweepState s) {
        cancelSliceTask();
        activeSweep = null;
        s.currentChunks = null;
        s.activeChunk = null;
        s.activeEntities = null;

        if (s.kind == SweepKind.MANUAL) {
            trimAllCaches();
            long now = System.currentTimeMillis();
            boolean cleanupChangedState = s.chunksUnloaded > 0 || s.deadRemoved > 0;
            boolean pressureRequiresGc = getCurrentUsagePercent() >= manualGcThresholdPercent;
            boolean gcTriggered = false;
            if (allowExplicitGcOnManualCommand && manualGcAfterSweep
                    && (pressureRequiresGc || cleanupChangedState)
                    && tryAcquireGcSlot(now)) {
                gcTriggered = requestExplicitGc();
            }

            final boolean finalGcTriggered = gcTriggered;
            if (s.completion != null) {
                plugin.getSchedulerAdapter().runGlobalLater(
                        () -> finishManualSweep(s, finalGcTriggered), Math.max(15L, manualGcDelayTicks));
            } else {
                plugin.getSchedulerAdapter().runGlobalLater(
                        () -> finalizeSweepTelemetry(s, finalGcTriggered), Math.max(15L, manualGcDelayTicks));
            }
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
        long freed = Math.max(0L, beforeMb - afterMb);
        return new RamFixResult(
                beforeMb, afterMb, maxMb, freed, percentBefore, percentAfter,
                gcTriggered, s.chunksScanned, ghostChunksUnloaded, deadEntitiesRemoved, currentTier,
                s.chunksUnloaded, s.deadRemoved);
    }

    private void cancelSliceTask() {
        if (sliceTask != null) {
            sliceTask.cancel();
            sliceTask = null;
        }
    }


    public RamFixResult performMemorySweep(boolean manualCommand) {
        long before = getUsedMemoryBytes();
        long max = getMaxMemoryBytes();
        trimAllCaches();

        if (manualCommand && !plugin.getSchedulerAdapter().isFolia()) {
            boolean started = launchManualSweep(null, before);
            if (started) {
                return snapshotResult(before, max, false);
            }
        }

        long after = getUsedMemoryBytes();
        return snapshotResult(before, max, false);
    }


    public boolean startManualSweep(Consumer<RamFixResult> completion) {
        if (!enabled) {
            return false;
        }

        if (plugin.getSchedulerAdapter().isFolia()) {
            synchronized (this) {
                if (foliaManualSweepRunning) {
                    return false;
                }
                foliaManualSweepRunning = true;
            }
            TaskHandle startHandle = plugin.getSchedulerAdapter().runGlobal(() -> beginFoliaSweep(completion));
            if (startHandle.isCancelled()) {
                synchronized (this) {
                    foliaManualSweepRunning = false;
                }
                return false;
            }
            return true;
        }

        if (activeSweep != null) {
            return false;
        }
        long before = getUsedMemoryBytes();
        trimAllCaches();
        return launchManualSweep(completion, before);
    }

    private void beginFoliaSweep(Consumer<RamFixResult> completion) {
        beginFoliaSweep(completion, true, SweepKind.MANUAL);
    }

    private void beginFoliaSweep(Consumer<RamFixResult> completion, boolean manual, SweepKind kind) {
        long before = getUsedMemoryBytes();
        long max = getMaxMemoryBytes();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            foliaKnownPlayers.add(id);
            plugin.getSchedulerAdapter().runEntity(player, () -> updateFoliaPlayerPosition(player));
        }

        FoliaSweepState state = new FoliaSweepState(
                new ConcurrentLinkedQueue<>(foliaLoadedChunks), before, max, completion, manual, kind);
        foliaSweepState = state;
        foliaDispatchTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::dispatchFoliaSweepSlice, 1L, 1L);
        if (foliaDispatchTask.isCancelled()) {
            foliaSweepState = null;
            synchronized (this) {
                foliaManualSweepRunning = false;
            }
        }
    }

    private void dispatchFoliaSweepSlice() {
        FoliaSweepState state = foliaSweepState;
        if (state == null || !enabled) {
            if (foliaDispatchTask != null) {
                foliaDispatchTask.cancel();
                foliaDispatchTask = null;
            }
            synchronized (this) {
                foliaManualSweepRunning = false;
            }
            return;
        }

        int dispatched = 0;
        FoliaChunkKey key;
        while (dispatched < FOLIA_REGION_TASKS_PER_TICK && (key = state.queue.poll()) != null) {
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                foliaLoadedChunks.remove(key);
                continue;
            }
            FoliaChunkKey chunkKey = key;
            Location anchor = new Location(world, chunkKey.chunkX() * 16.0D + 8.0D, 0.0D,
                    chunkKey.chunkZ() * 16.0D + 8.0D);
            state.pending.incrementAndGet();
            TaskHandle handle = plugin.getSchedulerAdapter().runRegion(anchor,
                    () -> inspectFoliaChunk(state, chunkKey, world));
            if (handle.isCancelled()) {
                state.pending.decrementAndGet();
                state.scanned.incrementAndGet();
            }
            dispatched++;
        }

        if (state.queue.isEmpty() && state.pending.get() == 0) {
            if (foliaDispatchTask != null) {
                foliaDispatchTask.cancel();
                foliaDispatchTask = null;
            }
            finishFoliaSweep(state);
        }
    }

    private void inspectFoliaChunk(FoliaSweepState state, FoliaChunkKey key, World world) {
        try {
            if (!enabled || foliaSweepState != state) {
                return;
            }
            if (!world.isChunkLoaded(key.chunkX(), key.chunkZ())) {
                foliaLoadedChunks.remove(key);
                return;
            }
            if (foliaKnownPlayers.size() > foliaPlayerPositions.size()) {
                return;
            }

            Chunk chunk = world.getChunkAt(key.chunkX(), key.chunkZ());
            if (chunk == null || !chunk.isLoaded()) {
                foliaLoadedChunks.remove(key);
                return;
            }
            state.scanned.incrementAndGet();
            if (chunk.isForceLoaded() || isFoliaChunkProtected(key)) {
                return;
            }

            Location spawn = world.getSpawnLocation();
            if (spawn != null && Math.abs((spawn.getBlockX() >> 4) - key.chunkX()) <= 2
                    && Math.abs((spawn.getBlockZ() >> 4) - key.chunkZ()) <= 2) {
                return;
            }

            Entity[] entities = chunk.getEntities();
            boolean hasLiveEntity = false;
            boolean removeDeadItems = state.manual
                    || state.kind != SweepKind.GHOST_CHUNKS_ONLY && deadEntitySweepEnabled;
            for (Entity entity : entities) {
                if (removeDeadItems && entity instanceof Item && (entity.isDead() || !entity.isValid())) {
                    entity.remove();
                    state.deadRemoved.incrementAndGet();
                } else if (entity != null && entity.isValid() && !entity.isDead()) {
                    hasLiveEntity = true;
                }
            }
            if (!hasLiveEntity && chunk.unload(true)) {
                state.unloaded.incrementAndGet();
                foliaLoadedChunks.remove(key);
            }
        } catch (Throwable failure) {
            if (verboseLogging) {
                log("[RamFixer] Region sweep skipped a chunk after " + failure.getClass().getSimpleName());
            }
        } finally {
            state.pending.decrementAndGet();
        }
    }

    private boolean isFoliaChunkProtected(FoliaChunkKey key) {
        int safetyRadius = Math.max(2, ghostChunkSafetyPaddingChunks + currentViewDistanceGuess());
        for (FoliaPlayerPosition player : foliaPlayerPositions.values()) {
            if (!key.worldId().equals(player.worldId())) {
                continue;
            }
            int radius = Math.max(safetyRadius, player.viewDistance() + ghostChunkSafetyPaddingChunks);
            if (Math.abs(player.chunkX() - key.chunkX()) <= radius
                    && Math.abs(player.chunkZ() - key.chunkZ()) <= radius) {
                return true;
            }
        }
        return false;
    }

    private void finishFoliaSweep(FoliaSweepState state) {
        if (foliaSweepState != state) {
            return;
        }
        foliaSweepState = null;
        boolean changed = state.unloaded.get() > 0 || state.deadRemoved.get() > 0;
        boolean gcTriggered = false;
        if (state.manual && allowExplicitGcOnManualCommand && manualGcAfterSweep
                && (getCurrentUsagePercent() >= manualGcThresholdPercent || changed)
                && tryAcquireGcSlot(System.currentTimeMillis())) {
            gcTriggered = requestExplicitGc();
        }
        final boolean gcIssued = gcTriggered;
        plugin.getSchedulerAdapter().runGlobalLater(() -> {
            long after = getUsedMemoryBytes();
            long maxAfter = getMaxMemoryBytes();
            long beforeMb = state.beforeBytes / 1048576L;
            long afterMb = after / 1048576L;
            long maxMb = maxAfter / 1048576L;
            long freed = Math.max(0L, beforeMb - afterMb);
            totalFreedMb += freed;
            ghostChunksUnloaded += state.unloaded.get();
            deadEntitiesRemoved += state.deadRemoved.get();
            if (state.manual) {
                manualSweepCount++;
            }
            lastFullSweepTimestamp = System.currentTimeMillis();
            RamFixResult result = new RamFixResult(
                    beforeMb, afterMb, maxMb, freed,
                    state.beforeBytes * 100D / Math.max(1L, state.maxBytes),
                    after * 100D / Math.max(1L, maxAfter), gcIssued,
                    state.scanned.get(), ghostChunksUnloaded, deadEntitiesRemoved, currentTier,
                    state.unloaded.get(), state.deadRemoved.get());
            synchronized (this) {
                foliaManualSweepRunning = false;
            }
            if (state.completion != null) {
                state.completion.accept(result);
            }
        }, Math.max(15L, manualGcDelayTicks));
    }

    private void updateFoliaPlayerPosition(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        UUID id = player.getUniqueId();
        foliaKnownPlayers.add(id);
        foliaPlayerPositions.put(id, new FoliaPlayerPosition(
                location.getWorld().getUID(), location.getBlockX() >> 4,
                location.getBlockZ() >> 4, Math.max(2, player.getViewDistance())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        Chunk chunk = event.getChunk();
        foliaLoadedChunks.add(new FoliaChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        Chunk chunk = event.getChunk();
        foliaLoadedChunks.remove(new FoliaChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null || from.getWorld() == null
                || to.getWorld().equals(from.getWorld())
                && (to.getBlockX() >> 4) == (from.getBlockX() >> 4)
                && (to.getBlockZ() >> 4) == (from.getBlockZ() >> 4)) {
            return;
        }
        updateFoliaPlayerPosition(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            updateFoliaPlayerPosition(event.getPlayer());
        }
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

    private boolean requestExplicitGc() {
        try {
            System.gc();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }


    private void trimAllCachesIfDue(long now) {
        if (now - lastCacheTrimTimestamp < 5_000L) return;
        lastCacheTrimTimestamp = now;
        trimAllCaches();
    }

    public void trimAllCaches() {


        if (plugin.getSchedulerAdapter().isFolia()) return;
        if (plugin.getItemExpiryModule() != null) plugin.getItemExpiryModule().trimCaches();
        if (plugin.getFluidFloodModule() != null) plugin.getFluidFloodModule().trimCaches();
        if (plugin.getNetherChunkModule() != null) plugin.getNetherChunkModule().trimCaches();
        if (plugin.getEntityCullingModule() != null) plugin.getEntityCullingModule().trimCaches();
        if (!plugin.getSchedulerAdapter().isFolia() && plugin.getChunkModule() != null) plugin.getChunkModule().trimCaches();
        if (plugin.getWorldModule() != null) plugin.getWorldModule().trimCaches();
    }


    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            UUID worldId = event.getWorld().getUID();
            foliaLoadedChunks.removeIf(key -> key.worldId().equals(worldId));
            foliaPlayerPositions.entrySet().removeIf(entry -> entry.getValue().worldId().equals(worldId));
        }
        trimAllCaches();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            UUID id = event.getPlayer().getUniqueId();
            foliaKnownPlayers.remove(id);
            foliaPlayerPositions.remove(id);
        }
        plugin.getSchedulerAdapter().runGlobalLater(this::trimAllCaches, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            foliaKnownPlayers.add(event.getPlayer().getUniqueId());
            updateFoliaPlayerPosition(event.getPlayer());
        }
        plugin.getSchedulerAdapter().runGlobalLater(this::trimAllCaches, 100L);
    }


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


    private record FoliaChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    private record FoliaPlayerPosition(UUID worldId, int chunkX, int chunkZ, int viewDistance) {
    }

    private static final class FoliaSweepState {
        final ConcurrentLinkedQueue<FoliaChunkKey> queue;
        final long beforeBytes;
        final long maxBytes;
        final Consumer<RamFixResult> completion;
        final boolean manual;
        final SweepKind kind;
        final AtomicInteger pending = new AtomicInteger();
        final AtomicInteger scanned = new AtomicInteger();
        final AtomicInteger unloaded = new AtomicInteger();
        final AtomicInteger deadRemoved = new AtomicInteger();

        FoliaSweepState(ConcurrentLinkedQueue<FoliaChunkKey> queue, long beforeBytes, long maxBytes,
                        Consumer<RamFixResult> completion, boolean manual, SweepKind kind) {
            this.queue = queue;
            this.beforeBytes = beforeBytes;
            this.maxBytes = maxBytes;
            this.completion = completion;
            this.manual = manual;
            this.kind = kind;
        }
    }

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
            PressureTier tier,
            int chunksUnloadedThisSweep,
            int deadEntitiesRemovedThisSweep
    ) {
        public RamFixResult(long usedBeforeMb, long usedAfterMb, long maxMb, long freedMb,
                            double percentBefore, double percentAfter, boolean gcTriggered,
                            int chunksScannedInFlight, int totalGhostChunksUnloaded,
                            int totalDeadEntitiesRemoved, PressureTier tier) {
            this(usedBeforeMb, usedAfterMb, maxMb, freedMb, percentBefore, percentAfter, gcTriggered,
                    chunksScannedInFlight, totalGhostChunksUnloaded, totalDeadEntitiesRemoved, tier, 0, 0);
        }


        public int entitiesChecked() {
            return chunksScannedInFlight;
        }
    }
}
