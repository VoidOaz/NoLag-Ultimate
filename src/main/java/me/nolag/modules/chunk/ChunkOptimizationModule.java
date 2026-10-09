package me.nolag.modules.chunk;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adaptive view/simulation-distance and loaded-chunk hygiene module.
 *
 * <p>The module is deliberately conservative around Bukkit/Paper APIs:</p>
 * <ul>
 *   <li>All mutable state is owned by the server-thread task that executes it.</li>
 *   <li>Optional Paper methods are resolved once with MethodHandles rather than
 *       using reflective Method.invoke() on every player/chunk.</li>
 *   <li>Player distance writes are dirty-gated, so stable players cost only cache lookups.</li>
 *   <li>The cleaner is time-budgeted and protects nearby players, force-loaded chunks,
 *       and chunks that Bukkit/Paper says a player can currently see.</li>
 * </ul>
 *
 * <p>Important: Bukkit cannot selectively stream individual chunks in a directional FOV.
 * The directional component therefore uses movement speed to bias the per-player distance;
 * it does not falsely claim to implement a true cone/FOV chunk stream.</p>
 */
public final class ChunkOptimizationModule implements Listener {

    private static final int SERVER_MIN_VIEW_DISTANCE = 2;
    private static final int SERVER_MAX_VIEW_DISTANCE = 32;
    private static final int SERVER_MIN_SIMULATION_DISTANCE = 2;
    private static final int SERVER_MAX_SIMULATION_DISTANCE = 32;

    private static final int FALLBACK_VIEW_DISTANCE = 8;
    private static final double INVALID_TPS = -1.0D;
    private static final double MAX_VALID_TPS = 25.0D;
    private static final String ANALYZER_KEY = "Chunk";

    private static final long DEFAULT_CLEANER_BUDGET_NANOS = 1_000_000L;
    private static final int CLEANER_HARD_CHUNK_LIMIT = 4096;
    private static final int BUDGET_CHECK_INTERVAL = 8;
    private static final int HYSTERESIS_MIN_SPAN = 2;

    private final NoLag plugin;
    private final ViewApi viewApi;

    private final Map<UUID, WorldBaseline> worldBaselines = new HashMap<>();
    private final Map<UUID, PlayerBaseline> playerBaselines = new HashMap<>();

    /** Last value pushed by this module. This is not a source-of-truth cache for the server. */
    private final Map<UUID, DistanceState> appliedWorldDistances = new HashMap<>();
    private final Map<UUID, PlayerDistanceState> appliedPlayerDistances = new HashMap<>();

    private final int serverViewBaseline;
    private final int serverSimulationBaseline;

    private volatile Settings settings;
    private volatile int currentViewDistance;
    private volatile CleanupJob activeJob;

    private TaskHandle dynamicDistanceTask;
    private TaskHandle playerBiasTask;
    private TaskHandle cleanerCycleTask;
    private TaskHandle cleanupJobTask;
    private TaskHandle cacheTrimTask;

    private boolean distanceModified;
    private boolean shuttingDown;

    private int goodRecoveryStreak;
    private double smoothedTps;
    private boolean smoothedTpsSeeded;

    /** Counts real controller executions, not candidate distance changes. */
    private long controllerCycleCounter;
    private long lastStepCycle;

    private final AtomicLong cleanerChunksScanned = new AtomicLong();
    private final AtomicLong cleanerChunksUnloaded = new AtomicLong();
    private final AtomicLong cleanerUnloadFailures = new AtomicLong();
    private final AtomicLong controllerCycles = new AtomicLong();
    private final AtomicLong distanceStepsApplied = new AtomicLong();
    private final AtomicLong redundantApplySkips = new AtomicLong();

    public ChunkOptimizationModule(NoLag plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }

        this.plugin = plugin;
        this.viewApi = new ViewApi(plugin.getLogger());
        this.serverViewBaseline = safeServerViewDistance();
        this.serverSimulationBaseline = safeServerSimulationDistance();
        this.currentViewDistance = this.serverViewBaseline;
        this.settings = Settings.load(plugin.getConfig(), plugin.getLogger());

        startTasks();
    }

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /**
     * Reload-safe task bootstrap. Calling this method repeatedly never leaves duplicate timers.
     */
    public void startTasks() {
        boolean wasModified = distanceModified;
        stopTasks();

        shuttingDown = false;
        Settings cfg = Settings.load(plugin.getConfig(), plugin.getLogger());
        settings = cfg;

        goodRecoveryStreak = 0;
        smoothedTps = 0.0D;
        smoothedTpsSeeded = false;
        controllerCycleCounter = 0L;
        lastStepCycle = 0L;

        if (!cfg.chunkOptimizerEnabled || plugin.getSchedulerAdapter().isFolia()) {
            if (wasModified) {
                restoreViewDistance();
            }
            clearTracking();
            currentViewDistance = safeServerViewDistance();
            return;
        }

        // A fresh optimizer session must capture the current server/plugin baselines.
        // During a live reload while the optimizer is already active we intentionally retain them.
        if (!wasModified) {
            clearTracking();
        }

        currentViewDistance = clamp(
                currentViewDistance <= 0 ? safeServerViewDistance() : currentViewDistance,
                cfg.minChunks,
                cfg.maxChunks
        );

        // Apply immediately so the module does not wait for the first controller interval.
        applyViewDistance(currentViewDistance);

        dynamicDistanceTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::updateViewDistance,
                cfg.checkIntervalTicks,
                cfg.checkIntervalTicks
        );

        if (cfg.directionBiasEnabled) {
            playerBiasTask = plugin.getSchedulerAdapter().runGlobalTimer(
                    this::updatePlayerBias,
                    cfg.biasIntervalTicks,
                    cfg.biasIntervalTicks
            );
        }

        if (cfg.cleanerEnabled) {
            cleanerCycleTask = plugin.getSchedulerAdapter().runGlobalTimer(
                    this::beginCleanupCycle,
                    Math.max(200L, cfg.cleanerIntervalTicks),
                    Math.max(200L, cfg.cleanerIntervalTicks)
            );
        }

        // Slow maintenance only; never part of the hot path.
        cacheTrimTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::trimCaches,
                12000L,
                12000L
        );
    }

    /** Cancels module-owned timers. Does not restore state; shutdown/reload handles restoration. */
    public void stopTasks() {
        cancelTask(dynamicDistanceTask);
        cancelTask(playerBiasTask);
        cancelTask(cleanerCycleTask);
        cancelTask(cleanupJobTask);
        cancelTask(cacheTrimTask);

        dynamicDistanceTask = null;
        playerBiasTask = null;
        cleanerCycleTask = null;
        cleanupJobTask = null;
        cacheTrimTask = null;

        activeJob = null;
    }

    /** Fully shuts down the module and restores captured baselines exactly once. */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }

        shuttingDown = true;
        stopTasks();
        restoreViewDistance();

        appliedWorldDistances.clear();
        appliedPlayerDistances.clear();
        worldBaselines.clear();
        playerBaselines.clear();
    }

    /**
     * Removes stale bookkeeping. Event-driven removal is preferred; this is only a safety net
     * for abnormal disconnects/world teardown.
     */
    public void trimCaches() {
        if (shuttingDown) {
            return;
        }

        playerBaselines.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
        appliedPlayerDistances.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);

        // WorldUnloadEvent normally owns removal. This is only a safety net for abnormal teardown.
        worldBaselines.entrySet().removeIf(entry -> Bukkit.getWorld(entry.getKey()) == null);
        appliedWorldDistances.entrySet().removeIf(entry -> Bukkit.getWorld(entry.getKey()) == null);
    }

    private void clearTracking() {
        worldBaselines.clear();
        playerBaselines.clear();
        appliedWorldDistances.clear();
        appliedPlayerDistances.clear();
        distanceModified = false;
    }

    private void cancelTask(TaskHandle task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    // =====================================================================================
    // Events
    // =====================================================================================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (shuttingDown || !isOptimizerActive()) {
            return;
        }

        Player player = event.getPlayer();
        capturePlayerBaseline(player);

        Settings cfg = settings;
        int target = calculatePlayerTargetDistance(player, currentViewDistance, cfg);
        int sim = simulationForPlayerTarget(target, currentViewDistance, cfg);
        applyToPlayer(player, target, sim, cfg.affectSendDistance);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerBaselines.remove(id);
        appliedPlayerDistances.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldLoad(WorldLoadEvent event) {
        if (shuttingDown || !isOptimizerActive()) {
            return;
        }

        World world = event.getWorld();
        int sim = simulationForWorldTarget(currentViewDistance, settings);
        applyToWorld(world, currentViewDistance, sim);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID id = event.getWorld().getUID();
        worldBaselines.remove(id);
        appliedWorldDistances.remove(id);

        CleanupJob job = activeJob;
        if (job != null) {
            job.invalidateWorld(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (shuttingDown || !isOptimizerActive()) {
            return;
        }

        Player player = event.getPlayer();
        Settings cfg = settings;
        int target = calculatePlayerTargetDistance(player, currentViewDistance, cfg);
        int sim = simulationForPlayerTarget(target, currentViewDistance, cfg);
        applyToPlayer(player, target, sim, cfg.affectSendDistance);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
            plugin.getPerformanceAnalyzer().record(ANALYZER_KEY, 15_000L, 1);
        }
    }

    private boolean isOptimizerActive() {
        Settings cfg = settings;
        return cfg != null && cfg.chunkOptimizerEnabled && !plugin.getSchedulerAdapter().isFolia();
    }

    // =====================================================================================
    // Controller
    // =====================================================================================

    private void updateViewDistance() {
        if (shuttingDown) {
            return;
        }

        Settings cfg = settings;
        if (cfg == null || !cfg.chunkOptimizerEnabled) {
            return;
        }

        long startNanos = System.nanoTime();
        long cycle = ++controllerCycleCounter;
        controllerCycles.incrementAndGet();

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            goodRecoveryStreak = 0;
            return;
        }

        double rawTps = readTps();
        if (rawTps == INVALID_TPS) {
            goodRecoveryStreak = 0;
            return;
        }

        double tps = smoothTps(rawTps, 0.5D);
        int current = currentViewDistance;
        int target = resolveTarget(cfg, tps, 0.0D);
        int next = current;

        if (target < current) {
            // Downward pressure is intentionally faster than recovery.
            goodRecoveryStreak = 0;
            int floor = Math.max(cfg.minChunks, current - cfg.maxStepPerCycle);
            next = Math.max(target, floor);
        } else if (target > current) {
            double safeTps = tps - cfg.recoveryMargin;
            int safeTarget = resolveTarget(cfg, safeTps, 0.0D);

            if (safeTarget > current + hysteresisBand(cfg)) {
                if (++goodRecoveryStreak >= cfg.recoveryChecks) {
                    next = Math.min(current + 1, safeTarget);
                    goodRecoveryStreak = 0;
                }
            } else {
                goodRecoveryStreak = 0;
            }
        } else {
            goodRecoveryStreak = 0;
        }

        if (next != current) {
            if (!cfg.rateLimitEnabled || tryAcquireStepSlot(cfg, cycle)) {
                currentViewDistance = next;
                distanceStepsApplied.incrementAndGet();
                applyViewDistance(next);
            }
        } else if (!distanceModified) {
            applyViewDistance(current);
        }

        if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
            plugin.getPerformanceAnalyzer().record(ANALYZER_KEY, System.nanoTime() - startNanos, players.size());
        }
    }

    private void updatePlayerBias() {
        if (shuttingDown) {
            return;
        }

        Settings cfg = settings;
        if (cfg == null || !cfg.chunkOptimizerEnabled || !cfg.directionBiasEnabled) {
            return;
        }

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        int baseView = currentViewDistance;
        for (Player player : players) {
            if (player == null || !player.isOnline()) {
                continue;
            }

            int target = calculatePlayerTargetDistance(player, baseView, cfg);
            int sim = simulationForPlayerTarget(target, baseView, cfg);
            applyToPlayer(player, target, sim, cfg.affectSendDistance);
        }
    }

    private int calculatePlayerTargetDistance(Player player, int baseDistance, Settings cfg) {
        int base = clamp(baseDistance, cfg.minChunks, cfg.maxChunks);
        if (!cfg.directionBiasEnabled || cfg.speedReduction <= 0) {
            return base;
        }

        if (player.isGliding() || player.isInsideVehicle()) {
            return Math.max(cfg.minChunks, base - cfg.speedReduction);
        }

        if (cfg.fastTravelThresholdSq <= 0.0D) {
            return base;
        }

        try {
            // getVelocity() is only used on the relatively infrequent bias interval.
            // The global controller never calls it, keeping the main control loop lean.
            org.bukkit.util.Vector velocity = player.getVelocity();
            double speedSq = velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ();
            if (speedSq >= cfg.fastTravelThresholdSq) {
                return Math.max(cfg.minChunks, base - cfg.speedReduction);
            }
        } catch (RuntimeException ignored) {
            // Some forks can reject entity access during unusual lifecycle transitions.
        }

        return base;
    }

    private int simulationForWorldTarget(int viewDistance, Settings cfg) {
        if (!cfg.manageSimulationDistance) {
            return clampSimulationDistance(serverSimulationBaseline);
        }
        return clampSimulationDistance(Math.min(viewDistance, viewDistance - 1));
    }

    private int simulationForPlayerTarget(int playerViewDistance, int baseDistance, Settings cfg) {
        if (!cfg.manageSimulationDistance) {
            return clampSimulationDistance(serverSimulationBaseline);
        }

        int baseSim = simulationForWorldTarget(baseDistance, cfg);
        int targetSim = Math.min(baseSim, playerViewDistance - 1);
        return clampSimulationDistance(targetSim);
    }

    private static int resolveTarget(Settings cfg, double tps, double margin) {
        double low = cfg.tpsLow + margin;
        double high = cfg.tpsHigh + margin;

        if (tps <= low) {
            return cfg.minChunks;
        }
        if (tps >= high) {
            return cfg.maxChunks;
        }

        double span = Math.max(0.5D, high - low);
        double ratio = (tps - low) / span;
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));

        return cfg.minChunks + (int) Math.round((cfg.maxChunks - cfg.minChunks) * ratio);
    }

    private static int hysteresisBand(Settings cfg) {
        int span = cfg.maxChunks - cfg.minChunks;
        if (span < HYSTERESIS_MIN_SPAN) {
            return 0;
        }
        return Math.max(0, cfg.hysteresisChunks);
    }

    private boolean tryAcquireStepSlot(Settings cfg, long cycle) {
        long minGap = cfg.minStepCycles;
        if (minGap <= 1L) {
            lastStepCycle = cycle;
            return true;
        }

        if (lastStepCycle == 0L || cycle - lastStepCycle >= minGap) {
            lastStepCycle = cycle;
            return true;
        }

        return false;
    }

    private double readTps() {
        try {
            double tps = plugin.getTPSMonitor().getTPS();
            return isValidTps(tps) ? tps : INVALID_TPS;
        } catch (RuntimeException ignored) {
            return INVALID_TPS;
        }
    }

    private double smoothTps(double sample, double alpha) {
        if (!smoothedTpsSeeded) {
            smoothedTpsSeeded = true;
            smoothedTps = sample;
        } else {
            smoothedTps += alpha * (sample - smoothedTps);
        }
        return smoothedTps;
    }

    // =====================================================================================
    // Distance application / restoration
    // =====================================================================================

    private void applyViewDistance(int viewDistance) {
        int view = clampViewDistance(viewDistance);
        Settings cfg = settings;
        boolean affectSend = cfg == null || cfg.affectSendDistance;

        int worldSimulation = cfg == null
                ? clampSimulationDistance(Math.max(SERVER_MIN_SIMULATION_DISTANCE, view - 1))
                : simulationForWorldTarget(view, cfg);

        List<World> worlds = Bukkit.getWorlds();
        for (int i = 0, n = worlds.size(); i < n; i++) {
            World world = worlds.get(i);
            if (world == null) {
                continue;
            }
            applyToWorld(world, view, worldSimulation);
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }

            int target = cfg == null ? view : calculatePlayerTargetDistance(player, view, cfg);
            int sim = cfg == null ? clampSimulationDistance(Math.max(SERVER_MIN_SIMULATION_DISTANCE, view - 1))
                    : simulationForPlayerTarget(target, view, cfg);
            applyToPlayer(player, target, sim, affectSend);
        }

        distanceModified = true;
    }

    private void restoreViewDistance() {
        if (!distanceModified) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }

            WorldBaseline baseline = worldBaselines.get(world.getUID());
            if (baseline != null) {
                forceToWorld(world, baseline.viewDistance(), baseline.simulationDistance());
            } else {
                forceToWorld(world, serverViewBaseline, serverSimulationBaseline);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) {
                continue;
            }

            PlayerBaseline baseline = playerBaselines.get(player.getUniqueId());
            if (baseline != null) {
                forceToPlayer(
                        player,
                        baseline.viewDistance(),
                        baseline.sendViewDistance(),
                        baseline.simulationDistance()
                );
            }
        }

        distanceModified = false;
    }

    private void applyToWorld(World world, int viewDistance, int simulationDistance) {
        if (world == null) {
            return;
        }

        int view = clampViewDistance(viewDistance);
        Settings cfg = settings;
        boolean manageSimulation = cfg == null || cfg.manageSimulationDistance;
        int sim = clampSimulationDistance(Math.min(simulationDistance, view));
        UUID id = world.getUID();

        DistanceState applied = appliedWorldDistances.get(id);
        if (applied != null
                && applied.viewDistance == view
                && (!manageSimulation || applied.simulationDistance == sim)) {
            redundantApplySkips.incrementAndGet();
            return;
        }

        captureWorldBaseline(world);

        viewApi.setWorldViewDistance(world, view);
        if (manageSimulation) {
            viewApi.setWorldSimulationDistance(world, sim);
        }
        appliedWorldDistances.put(id, new DistanceState(view, manageSimulation ? sim : Integer.MIN_VALUE));
    }

    private void applyToPlayer(Player player, int viewDistance, int simulationDistance, boolean affectSendDistance) {
        if (player == null || !player.isOnline()) {
            return;
        }

        int view = clampViewDistance(viewDistance);
        Settings cfg = settings;
        boolean manageSimulation = cfg == null || cfg.manageSimulationDistance;
        int sim = clampSimulationDistance(Math.min(simulationDistance, view));
        UUID id = player.getUniqueId();

        PlayerDistanceState applied = appliedPlayerDistances.get(id);
        if (applied != null
                && applied.viewDistance == view
                && (!manageSimulation || applied.simulationDistance == sim)
                && (!affectSendDistance || applied.sendMarker == view)) {
            redundantApplySkips.incrementAndGet();
            return;
        }

        capturePlayerBaseline(player);

        if (affectSendDistance) {
            viewApi.setPlayerSendViewDistance(player, view);
        }
        viewApi.setPlayerViewDistance(player, view);
        if (manageSimulation) {
            viewApi.setPlayerSimulationDistance(player, sim);
        }

        appliedPlayerDistances.put(
                id,
                new PlayerDistanceState(view, affectSendDistance ? view : -1, manageSimulation ? sim : Integer.MIN_VALUE)
        );
    }

    private void forceToWorld(World world, int viewDistance, int simulationDistance) {
        if (world == null) {
            return;
        }

        int view = clampViewDistance(viewDistance);
        int sim = clampSimulationDistance(simulationDistance);

        viewApi.setWorldViewDistance(world, view);
        viewApi.setWorldSimulationDistance(world, sim);
        appliedWorldDistances.put(world.getUID(), new DistanceState(view, sim));
    }

    private void forceToPlayer(Player player, int viewDistance, int sendViewDistance, int simulationDistance) {
        if (player == null || !player.isOnline()) {
            return;
        }

        int view = clampViewDistance(viewDistance);
        int send = clampSendViewDistance(sendViewDistance);
        int sim = clampSimulationDistance(simulationDistance);

        viewApi.setPlayerSendViewDistance(player, send);
        viewApi.setPlayerViewDistance(player, view);
        viewApi.setPlayerSimulationDistance(player, sim);

        appliedPlayerDistances.put(player.getUniqueId(), new PlayerDistanceState(view, send, sim));
    }

    private void captureWorldBaseline(World world) {
        if (world == null) {
            return;
        }

        worldBaselines.computeIfAbsent(
                world.getUID(),
                ignored -> new WorldBaseline(
                        viewApi.getWorldViewDistance(world, serverViewBaseline),
                        viewApi.getWorldSimulationDistance(world, serverSimulationBaseline)
                )
        );
    }

    private void capturePlayerBaseline(Player player) {
        if (player == null) {
            return;
        }

        playerBaselines.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new PlayerBaseline(
                        viewApi.getPlayerViewDistance(player, serverViewBaseline),
                        viewApi.getPlayerSendViewDistance(player, -1),
                        viewApi.getPlayerSimulationDistance(player, serverSimulationBaseline)
                )
        );
    }

    // =====================================================================================
    // Cleaner
    // =====================================================================================

    private void beginCleanupCycle() {
        if (shuttingDown || activeJob != null) {
            return;
        }

        Settings cfg = settings;
        if (cfg == null || !cfg.cleanerEnabled) {
            return;
        }

        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            return;
        }

        ArrayList<World> snapshot = new ArrayList<>(worlds.size());
        for (World world : worlds) {
            if (world != null) {
                snapshot.add(world);
            }
        }

        if (snapshot.isEmpty()) {
            return;
        }

        CleanupJob job = new CleanupJob(snapshot, cfg);
        activeJob = job;
        cleanupJobTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::processCleanupSlice,
                1L,
                1L
        );
    }

    private void processCleanupSlice() {
        if (shuttingDown) {
            cancelCleanupJob();
            return;
        }

        CleanupJob job = activeJob;
        if (job == null) {
            return;
        }

        Settings cfg = settings;
        long budget = cfg == null ? DEFAULT_CLEANER_BUDGET_NANOS : cfg.cleanerBudgetNanos;
        if (job.runSlice(Math.max(250_000L, budget))) {
            cancelCleanupJob();
        }
    }

    private void cancelCleanupJob() {
        cancelTask(cleanupJobTask);
        cleanupJobTask = null;
        activeJob = null;
    }

    private final class CleanupJob {
        private final List<World> worlds;
        private final Settings cfg;

        private int worldIndex;
        private World currentWorld;
        private UUID currentWorldId;
        private Chunk[] currentChunks;
        private int chunkIndex;
        private boolean finished;

        private int[] playerChunkX = new int[0];
        private int[] playerChunkZ = new int[0];
        private int playerCount;
        private int protectRadius;

        CleanupJob(List<World> worlds, Settings cfg) {
            this.worlds = worlds;
            this.cfg = cfg;
            advanceWorld();
        }

        void invalidateWorld(UUID id) {
            for (int i = worldIndex; i < worlds.size(); i++) {
                World world = worlds.get(i);
                if (world != null && id.equals(world.getUID())) {
                    worlds.set(i, null);
                }
            }

            if (currentWorldId != null && currentWorldId.equals(id)) {
                currentWorld = null;
                currentWorldId = null;
                currentChunks = null;
                chunkIndex = 0;
                advanceWorld();
            }
        }

        private void advanceWorld() {
            while (worldIndex < worlds.size()) {
                World candidate = worlds.get(worldIndex++);
                if (candidate == null) {
                    continue;
                }

                currentWorld = candidate;
                currentWorldId = candidate.getUID();
                currentChunks = candidate.getLoadedChunks();
                chunkIndex = 0;
                buildProtectionIndex(candidate);
                return;
            }

            currentWorld = null;
            currentWorldId = null;
            currentChunks = null;
            finished = true;
        }

        private void buildProtectionIndex(World world) {
            Collection<? extends Player> players = world.getPlayers();
            int count = players.size();

            playerCount = 0;
            protectRadius = count == 0 ? 0 : clamp(
                    currentViewDistance + cfg.cleanerSafetyPadding,
                    cfg.minChunks,
                    SERVER_MAX_VIEW_DISTANCE + 8
            );

            if (count == 0) {
                return;
            }

            if (playerChunkX.length < count) {
                playerChunkX = new int[Math.max(16, count)];
                playerChunkZ = new int[playerChunkX.length];
            }

            // Center coordinates are always captured once per world. On Paper, the exact
            // viewer-index API is preferred during chunk checks; the arrays remain the
            // compatibility fallback when that optional API is unavailable.
            for (Player player : players) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                playerChunkX[playerCount] = floorChunk(player.getX());
                playerChunkZ[playerCount] = floorChunk(player.getZ());
                playerCount++;
            }
        }

        boolean runSlice(long budgetNanos) {
            if (finished) {
                return true;
            }

            if (currentWorld == null || currentChunks == null) {
                advanceWorld();
                if (finished) {
                    return true;
                }
            }

            long deadline = System.nanoTime() + budgetNanos;
            int processed = 0;

            final World world = currentWorld;
            final Chunk[] chunks = currentChunks;
            final int len = chunks.length;

            for (int i = chunkIndex; i < len; i++) {
                Chunk chunk = chunks[i];
                if (chunk != null && chunk.isLoaded() && !chunk.isForceLoaded()) {
                    int x = chunk.getX();
                    int z = chunk.getZ();

                    if (!isPlayerProtected(world, chunk, x, z)) {
                        tryUnload(chunk);
                    }
                }

                processed++;
                cleanerChunksScanned.incrementAndGet();

                if ((processed & (BUDGET_CHECK_INTERVAL - 1)) == 0) {
                    if (System.nanoTime() >= deadline || processed >= Math.min(CLEANER_HARD_CHUNK_LIMIT, cfg.cleanerHardChunkLimit)) {
                        chunkIndex = i + 1;
                        return false;
                    }
                }
            }

            chunkIndex = len;
            advanceWorld();
            return finished;
        }

        private boolean isPlayerProtected(World world, Chunk chunk, int x, int z) {
            if (playerCount == 0) {
                return false;
            }

            // Paper exposes the exact viewer set; use it when available because it tracks
            // actual per-player visibility rather than a guessed radius.
            int viewerState = viewApi.hasPlayersSeeingChunk(world, x, z);
            if (viewerState == 1) {
                return true;
            }
            if (viewerState == 0) {
                return false;
            }

            return hasNearbyPlayer(x, z, playerChunkX, playerChunkZ, playerCount, protectRadius);
        }

        private void tryUnload(Chunk chunk) {
            try {
                // save=true is intentionally retained: a cleaner must never trade RAM pressure
                // for the risk of losing dirty chunk data.
                if (chunk.unload(true)) {
                    cleanerChunksUnloaded.incrementAndGet();
                } else {
                    cleanerUnloadFailures.incrementAndGet();
                }
            } catch (RuntimeException ignored) {
                cleanerUnloadFailures.incrementAndGet();
            }
        }

        private boolean hasNearbyPlayer(int chunkX, int chunkZ, int[] px, int[] pz, int count, int radius) {
            for (int i = 0; i < count; i++) {
                int dx = px[i] - chunkX;
                if (dx > radius || dx < -radius) {
                    continue;
                }

                int dz = pz[i] - chunkZ;
                if (dz >= -radius && dz <= radius) {
                    return true;
                }
            }
            return false;
        }
    }

    // =====================================================================================
    // View API bridge
    // =====================================================================================

    private static final class ViewApi {
        private final Logger logger;

        private MethodHandle worldSetView;
        private MethodHandle worldSetSimulation;
        private MethodHandle worldGetView;
        private MethodHandle worldGetSimulation;

        private MethodHandle playerSetSendView;
        private MethodHandle playerSetView;
        private MethodHandle playerSetSimulation;
        private MethodHandle playerGetSendView;
        private MethodHandle playerGetView;
        private MethodHandle playerGetSimulation;

        private MethodHandle worldPlayersSeeingChunk;

        private boolean worldSetViewFailureLogged;
        private boolean worldSetSimulationFailureLogged;
        private boolean playerSetSendFailureLogged;
        private boolean playerSetViewFailureLogged;
        private boolean playerSetSimulationFailureLogged;

        ViewApi(Logger logger) {
            this.logger = logger;

            worldSetView = find(World.class, "setViewDistance", void.class, int.class);
            worldSetSimulation = find(World.class, "setSimulationDistance", void.class, int.class);
            worldGetView = find(World.class, "getViewDistance", int.class);
            worldGetSimulation = find(World.class, "getSimulationDistance", int.class);

            playerSetSendView = find(Player.class, "setSendViewDistance", void.class, int.class);
            playerSetView = find(Player.class, "setViewDistance", void.class, int.class);
            playerSetSimulation = find(Player.class, "setSimulationDistance", void.class, int.class);
            playerGetSendView = find(Player.class, "getSendViewDistance", int.class);
            playerGetView = find(Player.class, "getViewDistance", int.class);
            playerGetSimulation = find(Player.class, "getSimulationDistance", int.class);

            worldPlayersSeeingChunk = find(World.class, "getPlayersSeeingChunk", Collection.class, int.class, int.class);
        }

        int getWorldViewDistance(World world, int fallback) {
            return invokeInt(worldGetView, world, fallback);
        }

        void setWorldViewDistance(World world, int distance) {
            invokeVoid(worldSetView, world, distance, "World#setViewDistance", FailureType.WORLD_VIEW);
        }

        int getWorldSimulationDistance(World world, int fallback) {
            return invokeInt(worldGetSimulation, world, fallback);
        }

        void setWorldSimulationDistance(World world, int distance) {
            invokeVoid(worldSetSimulation, world, distance, "World#setSimulationDistance", FailureType.WORLD_SIMULATION);
        }

        int getPlayerViewDistance(Player player, int fallback) {
            return invokeInt(playerGetView, player, fallback);
        }

        void setPlayerViewDistance(Player player, int distance) {
            invokeVoid(playerSetView, player, distance, "Player#setViewDistance", FailureType.PLAYER_VIEW);
        }

        int getPlayerSendViewDistance(Player player, int fallback) {
            return invokeInt(playerGetSendView, player, fallback);
        }

        void setPlayerSendViewDistance(Player player, int distance) {
            invokeVoid(playerSetSendView, player, distance, "Player#setSendViewDistance", FailureType.PLAYER_SEND);
        }

        int getPlayerSimulationDistance(Player player, int fallback) {
            return invokeInt(playerGetSimulation, player, fallback);
        }

        void setPlayerSimulationDistance(Player player, int distance) {
            invokeVoid(playerSetSimulation, player, distance, "Player#setSimulationDistance", FailureType.PLAYER_SIMULATION);
        }

        /**
         * Returns 1 when at least one player can currently see the chunk, 0 when none can,
         * and -1 when this optional API is unavailable or failed.
         */
        int hasPlayersSeeingChunk(World world, int chunkX, int chunkZ) {
            MethodHandle handle = worldPlayersSeeingChunk;
            if (handle == null || world == null) {
                return -1;
            }

            try {
                Object receiver = world;
                @SuppressWarnings("unchecked")
                Collection<Player> players = (Collection<Player>) handle.invokeExact(receiver, chunkX, chunkZ);
                return players != null && !players.isEmpty() ? 1 : 0;
            } catch (RuntimeException ex) {
                worldPlayersSeeingChunk = null;
                logOptionalFailure("World#getPlayersSeeingChunk", ex);
                return -1;
            } catch (Throwable ex) {
                worldPlayersSeeingChunk = null;
                logOptionalFailure("World#getPlayersSeeingChunk", ex);
                return -1;
            }
        }

        private int invokeInt(MethodHandle handle, Object receiver, int fallback) {
            if (handle == null || receiver == null) {
                return fallback;
            }
            try {
                return (int) handle.invokeExact(receiver);
            } catch (RuntimeException ex) {
                return fallback;
            } catch (Throwable ex) {
                return fallback;
            }
        }

        private void invokeVoid(MethodHandle handle, Object receiver, int distance, String apiName, FailureType failureType) {
            if (handle == null || receiver == null) {
                return;
            }

            try {
                handle.invokeExact(receiver, distance);
            } catch (RuntimeException ex) {
                switch (failureType) {
                    case WORLD_VIEW -> worldSetView = null;
                    case WORLD_SIMULATION -> worldSetSimulation = null;
                    case PLAYER_SEND -> playerSetSendView = null;
                    case PLAYER_VIEW -> playerSetView = null;
                    case PLAYER_SIMULATION -> playerSetSimulation = null;
                }
                logFailureOnce(failureType, apiName, ex);
            } catch (Throwable ex) {
                switch (failureType) {
                    case WORLD_VIEW -> worldSetView = null;
                    case WORLD_SIMULATION -> worldSetSimulation = null;
                    case PLAYER_SEND -> playerSetSendView = null;
                    case PLAYER_VIEW -> playerSetView = null;
                    case PLAYER_SIMULATION -> playerSetSimulation = null;
                }
                logFailureOnce(failureType, apiName, ex);
            }
        }

        private void logFailureOnce(FailureType type, String apiName, Throwable throwable) {
            if (logger == null || !logger.isLoggable(Level.WARNING)) {
                return;
            }

            boolean shouldLog = switch (type) {
                case WORLD_VIEW -> !worldSetViewFailureLogged;
                case WORLD_SIMULATION -> !worldSetSimulationFailureLogged;
                case PLAYER_SEND -> !playerSetSendFailureLogged;
                case PLAYER_VIEW -> !playerSetViewFailureLogged;
                case PLAYER_SIMULATION -> !playerSetSimulationFailureLogged;
            };
            if (!shouldLog) {
                return;
            }

            switch (type) {
                case WORLD_VIEW -> worldSetViewFailureLogged = true;
                case WORLD_SIMULATION -> worldSetSimulationFailureLogged = true;
                case PLAYER_SEND -> playerSetSendFailureLogged = true;
                case PLAYER_VIEW -> playerSetViewFailureLogged = true;
                case PLAYER_SIMULATION -> playerSetSimulationFailureLogged = true;
            }

            logger.log(Level.WARNING, "[NoLag][ChunkOptimizer] Disabling unavailable/failed API: " + apiName, throwable);
        }

        private void logOptionalFailure(String apiName, Throwable throwable) {
            if (logger != null && logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "[NoLag][ChunkOptimizer] Optional API unavailable: " + apiName, throwable);
            }
        }

        private static MethodHandle find(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
            try {
                MethodHandle handle = MethodHandles.publicLookup().findVirtual(
                        owner,
                        name,
                        MethodType.methodType(returnType, params)
                );

                // Adapt once so the hot path can use invokeExact with an Object receiver
                // without falling back to reflective invocation or signature checks.
                Class<?>[] adaptedParams = new Class<?>[params.length + 1];
                adaptedParams[0] = Object.class;
                System.arraycopy(params, 0, adaptedParams, 1, params.length);
                return handle.asType(MethodType.methodType(returnType, adaptedParams));
            } catch (NoSuchMethodException | IllegalAccessException | SecurityException ignored) {
                return null;
            }
        }

        private enum FailureType {
            WORLD_VIEW,
            WORLD_SIMULATION,
            PLAYER_SEND,
            PLAYER_VIEW,
            PLAYER_SIMULATION
        }
    }

    // =====================================================================================
    // Configuration / state
    // =====================================================================================

    private record WorldBaseline(int viewDistance, int simulationDistance) {}

    private record PlayerBaseline(int viewDistance, int sendViewDistance, int simulationDistance) {}

    private record DistanceState(int viewDistance, int simulationDistance) {}

    private record PlayerDistanceState(int viewDistance, int sendMarker, int simulationDistance) {}

    private static final class Settings {
        final boolean chunkOptimizerEnabled;
        final int minChunks;
        final int maxChunks;

        final boolean directionBiasEnabled;
        final int speedReduction;
        final double fastTravelThresholdSq;
        final int biasIntervalTicks;

        final boolean cleanerEnabled;
        final int cleanerIntervalTicks;
        final long cleanerBudgetNanos;
        final int cleanerSafetyPadding;
        final int cleanerHardChunkLimit;

        final double tpsLow;
        final double tpsHigh;
        final double recoveryMargin;
        final int recoveryChecks;
        final long checkIntervalTicks;

        final int maxStepPerCycle;
        final int hysteresisChunks;
        final boolean rateLimitEnabled;
        final long minStepCycles;

        final boolean affectSendDistance;
        final boolean manageSimulationDistance;

        private Settings(FileConfiguration cfg, Logger logger) {
            ConfigurationSection optSec = section(cfg, "chunk-optimizer");
            ConfigurationSection vdSec = section(cfg, "settings.view-distance");
            ConfigurationSection clnSec = section(cfg, "optimization.chunk-cleaner");

            chunkOptimizerEnabled = cfg.getBoolean(
                    "chunk-optimizer.enabled",
                    cfg.getBoolean("Chunk-Optimizer", cfg.getBoolean("chunk-optimizer", false))
            );

            int rawMin = readInt(optSec, "min-chunks", cfg.getInt("min-chunks", readInt(vdSec, "min", 2)));
            minChunks = clamp(rawMin, SERVER_MIN_VIEW_DISTANCE, SERVER_MAX_VIEW_DISTANCE);

            int rawMax = readInt(optSec, "max-chunks", cfg.getInt("max-chunks", readInt(vdSec, "max", 12)));
            maxChunks = clamp(Math.max(rawMax, minChunks), minChunks, SERVER_MAX_VIEW_DISTANCE);

            directionBiasEnabled = readBool(optSec, "direction-bias-enabled", cfg.getBoolean("direction-bias-enabled", true));
            speedReduction = clamp(readInt(optSec, "speed-reduction", cfg.getInt("speed-reduction", 2)), 0, 8);

            double fastThreshold = clampDouble(
                    readDouble(optSec, "fast-travel-threshold", cfg.getDouble("fast-travel-threshold", 1.2D)),
                    0.0D,
                    5.0D
            );
            fastTravelThresholdSq = fastThreshold * fastThreshold;
            biasIntervalTicks = clamp(readInt(optSec, "bias-interval-ticks", cfg.getInt("bias-interval-ticks", 20)), 5, 200);

            cleanerEnabled = readBool(clnSec, "enabled", cfg.getBoolean("chunk-cleaner.enabled", true));

            int intervalMinutes = Math.max(1, readInt(clnSec, "interval-minutes", cfg.getInt("chunk-cleaner.interval-minutes", 8)));
            long intervalTicks = safeMultiply(intervalMinutes, 60L * 20L);
            cleanerIntervalTicks = (int) clampLong(intervalTicks, 200L, Integer.MAX_VALUE);

            long budgetMs = Math.max(1L, readLong(clnSec, "slice-budget-ms", cfg.getLong("chunk-cleaner.slice-budget-ms", 1L)));
            cleanerBudgetNanos = clampLong(safeMultiply(budgetMs, 1_000_000L), 250_000L, 8_000_000L);
            cleanerSafetyPadding = clamp(readInt(clnSec, "safety-padding", cfg.getInt("chunk-cleaner.safety-padding", 1)), 0, 8);
            cleanerHardChunkLimit = clamp(readInt(clnSec, "max-chunks-per-slice", cfg.getInt("chunk-cleaner.max-chunks-per-slice", CLEANER_HARD_CHUNK_LIMIT)), 64, CLEANER_HARD_CHUNK_LIMIT);

            tpsLow = clampDouble(readDouble(vdSec, "tps-low", 15.0D), 1.0D, 24.0D);
            tpsHigh = clampDouble(readDouble(vdSec, "tps-high", 18.0D), tpsLow + 0.5D, 25.0D);
            recoveryMargin = clampDouble(readDouble(vdSec, "recovery-margin", 1.0D), 0.0D, 5.0D);
            recoveryChecks = clamp(readInt(vdSec, "recovery-checks", 2), 1, 10);

            long intervalSeconds = Math.max(1L, readLong(vdSec, "check-interval-seconds", 20L));
            checkIntervalTicks = clampLong(safeMultiply(intervalSeconds, 20L), 20L, 1200L);

            maxStepPerCycle = clamp(readInt(optSec, "max-step-per-cycle", 2), 1, 4);
            hysteresisChunks = clamp(readInt(optSec, "hysteresis-chunks", 1), 0, 4);
            rateLimitEnabled = readBool(optSec, "rate-limit-enabled", true);
            minStepCycles = clampLong(readLong(optSec, "min-step-cycles", 2L), 1L, 20L);

            affectSendDistance = readBool(vdSec, "affect-send-distance", true);
            manageSimulationDistance = readBool(optSec, "manage-simulation-distance", true);

            if (logger != null && logger.isLoggable(Level.FINE)) {
                logger.fine(
                        "[NoLag][ChunkOptimizer] Loaded: enabled=" + chunkOptimizerEnabled
                                + ", view=[" + minChunks + "," + maxChunks + "]"
                                + ", tps=[" + tpsLow + "," + tpsHigh + "]"
                                + ", bias=" + directionBiasEnabled + "@" + biasIntervalTicks + "t"
                                + ", cleaner=" + cleanerEnabled + "@" + (cleanerIntervalTicks / 1200) + "m"
                                + ", budget=" + (cleanerBudgetNanos / 1_000_000L) + "ms"
                                + ", sim=" + manageSimulationDistance
                );
            }
        }

        static Settings load(FileConfiguration cfg, Logger logger) {
            return new Settings(cfg, logger);
        }

        private static ConfigurationSection section(FileConfiguration cfg, String path) {
            return cfg != null && cfg.isConfigurationSection(path) ? cfg.getConfigurationSection(path) : null;
        }

        private static int readInt(ConfigurationSection section, String key, int fallback) {
            return section != null && section.contains(key) ? section.getInt(key, fallback) : fallback;
        }

        private static long readLong(ConfigurationSection section, String key, long fallback) {
            return section != null && section.contains(key) ? section.getLong(key, fallback) : fallback;
        }

        private static double readDouble(ConfigurationSection section, String key, double fallback) {
            return section != null && section.contains(key) ? section.getDouble(key, fallback) : fallback;
        }

        private static boolean readBool(ConfigurationSection section, String key, boolean fallback) {
            return section != null && section.contains(key) ? section.getBoolean(key, fallback) : fallback;
        }

        private static long safeMultiply(long value, long multiplier) {
            if (value <= 0L || multiplier <= 0L) {
                return 0L;
            }
            if (value > Long.MAX_VALUE / multiplier) {
                return Long.MAX_VALUE;
            }
            return value * multiplier;
        }
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private int safeServerViewDistance() {
        try {
            int distance = Bukkit.getViewDistance();
            return distance > 0 ? clampViewDistance(distance) : FALLBACK_VIEW_DISTANCE;
        } catch (RuntimeException ignored) {
            return FALLBACK_VIEW_DISTANCE;
        }
    }

    private int safeServerSimulationDistance() {
        try {
            int distance = Bukkit.getSimulationDistance();
            return distance > 0 ? clampSimulationDistance(distance) : FALLBACK_VIEW_DISTANCE;
        } catch (RuntimeException ignored) {
            return FALLBACK_VIEW_DISTANCE;
        }
    }

    private static boolean isValidTps(double tps) {
        return Double.isFinite(tps) && tps > 0.0D && tps <= MAX_VALID_TPS;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static long clampLong(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return value < min ? min : (value > max ? max : value);
    }

    private static int clampViewDistance(int value) {
        return clamp(value, SERVER_MIN_VIEW_DISTANCE, SERVER_MAX_VIEW_DISTANCE);
    }

    private static int clampSimulationDistance(int value) {
        return clamp(value, SERVER_MIN_SIMULATION_DISTANCE, SERVER_MAX_SIMULATION_DISTANCE);
    }

    /** send distance supports -1 on Paper meaning inherit/disabled; never silently convert it to 2. */
    private static int clampSendViewDistance(int value) {
        if (value == -1) {
            return -1;
        }
        return clampViewDistance(value);
    }

    private static int floorChunk(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }

    public int getCurrentViewDistance() {
        return currentViewDistance;
    }

    public boolean isChunkOptimizerEnabled() {
        Settings cfg = settings;
        return cfg != null && cfg.chunkOptimizerEnabled;
    }

    public boolean isCleanerRunning() {
        return activeJob != null;
    }

    public long getCleanerChunksScanned() {
        return cleanerChunksScanned.get();
    }

    public long getCleanerChunksUnloaded() {
        return cleanerChunksUnloaded.get();
    }

    public long getCleanerUnloadFailures() {
        return cleanerUnloadFailures.get();
    }

    public long getControllerCycles() {
        return controllerCycles.get();
    }

    public long getDistanceStepsApplied() {
        return distanceStepsApplied.get();
    }

    public long getRedundantApplySkips() {
        return redundantApplySkips.get();
    }

    public double getSmoothedTps() {
        return smoothedTpsSeeded ? smoothedTps : INVALID_TPS;
    }
}
