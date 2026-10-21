package me.nolag.modules.chunk;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;


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
    private static final int BUDGET_CHECK_INTERVAL = 4;
    private static final int HYSTERESIS_MIN_SPAN = 2;


    private static final int MAX_UNLOAD_ATTEMPTS_PER_SLICE = 16;


    private static final int PLAYER_GRID_CELL_SIZE = 16;


    private static final int PLAYER_GRID_LINEAR_SCAN_THRESHOLD = 12;


    private static final int PLAYER_PROTECTION_REFRESH_SLICES = 4;


    private static final double CLEANER_HIGH_LOAD_MSPT = 35.0D;
    private static final double CLEANER_VERY_HIGH_LOAD_MSPT = 42.0D;
    private static final double CLEANER_CRITICAL_LOAD_MSPT = 48.0D;

    private final NoLag plugin;
    private final ViewApi viewApi;
    private final boolean foliaServer;


    private final Map<UUID, WorldBaseline> worldBaselines;
    private final Map<UUID, PlayerBaseline> playerBaselines;


    private final Map<UUID, DistanceState> appliedWorldDistances;
    private final Map<UUID, PlayerDistanceState> appliedPlayerDistances;

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

    private volatile boolean distanceModified;
    private volatile boolean shuttingDown;

    private int goodRecoveryStreak;
    private volatile double smoothedTps;
    private volatile boolean smoothedTpsSeeded;


    private long controllerCycleCounter;
    private long lastStepCycle;

    private final AtomicLong cleanerChunksScanned = new AtomicLong();
    private final AtomicLong cleanerChunksUnloaded = new AtomicLong();
    private final AtomicLong cleanerUnloadFailures = new AtomicLong();
    private final AtomicLong controllerCycles = new AtomicLong();
    private final AtomicLong distanceStepsApplied = new AtomicLong();
    private final AtomicLong redundantApplySkips = new AtomicLong();


    private final AtomicLong cleanerSlices = new AtomicLong();
    private final AtomicLong cleanerSlicesCompleted = new AtomicLong();
    private final AtomicLong cleanerBudgetOverruns = new AtomicLong();
    private final AtomicLong cleanerUnloadAttempts = new AtomicLong();
    private final AtomicLong cleanerProtectedChunks = new AtomicLong();
    private final AtomicLong cleanerForceLoadedSkips = new AtomicLong();
    private final AtomicLong cleanerTotalSliceNanos = new AtomicLong();
    private final AtomicLong cleanerMaxSliceNanos = new AtomicLong();
    private final AtomicLong playerBiasPasses = new AtomicLong();

    public ChunkOptimizationModule(NoLag plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }

        this.plugin = plugin;
        this.foliaServer = plugin.getSchedulerAdapter().isFolia();
        this.worldBaselines = createTrackingMap(foliaServer);
        this.playerBaselines = createTrackingMap(foliaServer);
        this.appliedWorldDistances = createTrackingMap(foliaServer);
        this.appliedPlayerDistances = createTrackingMap(foliaServer);
        this.viewApi = new ViewApi(plugin.getLogger());
        this.serverViewBaseline = safeServerViewDistance();
        this.serverSimulationBaseline = safeServerSimulationDistance();
        this.currentViewDistance = this.serverViewBaseline;
        this.settings = Settings.load(plugin.getConfig(), plugin.getLogger());

        startTasks();
    }


    private static <K, V> Map<K, V> createTrackingMap(boolean concurrent) {
        return concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
    }


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

        if (!cfg.chunkOptimizerEnabled) {
            if (wasModified) {
                restoreViewDistance();
            }
            clearTracking();
            currentViewDistance = safeServerViewDistance();
            return;
        }


        if (!wasModified) {
            clearTracking();
        }

        currentViewDistance = clamp(
                currentViewDistance <= 0 ? safeServerViewDistance() : currentViewDistance,
                cfg.minChunks,
                cfg.maxChunks
        );


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

        if (cfg.cleanerEnabled && !foliaServer) {
            cleanerCycleTask = plugin.getSchedulerAdapter().runGlobalTimer(
                    this::beginCleanupCycle,
                    Math.max(200L, cfg.cleanerIntervalTicks),
                    Math.max(200L, cfg.cleanerIntervalTicks)
            );
        }


        cacheTrimTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::trimCaches,
                12000L,
                12000L
        );
    }


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


    public void trimCaches() {
        if (shuttingDown) {
            return;
        }

        playerBaselines.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
        appliedPlayerDistances.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);


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
        if (task == null) {
            return;
        }

        try {
            if (!task.isCancelled()) {
                task.cancel();
            }
        } catch (RuntimeException exception) {
            if (plugin.getLogger().isLoggable(Level.FINE)) {
                plugin.getLogger().log(
                        Level.FINE,
                        "[NoLag][ChunkOptimizer] Could not cancel a module task cleanly.",
                        exception
                );
            }
        }
    }


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
            job.requestWorldInvalidation(id);
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
        return cfg != null && cfg.chunkOptimizerEnabled;
    }


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
        double mspt = 10.0D;
        try {
            mspt = plugin.getTPSMonitor().getMSPT();
        } catch (Throwable ignored) {}

        int current = currentViewDistance;
        int target = resolveTarget(cfg, tps, 0.0D);


        if (mspt >= 52.0D) {
            target = Math.min(target, cfg.minChunks);
        } else if (mspt >= 45.0D) {
            target = Math.min(target, Math.max(cfg.minChunks, current - 2));
        } else if (mspt >= 39.0D) {
            target = Math.min(target, Math.max(cfg.minChunks, current - 1));
        }

        int next = current;

        if (target < current) {

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
        int baseSimulation = simulationForWorldTarget(baseView, cfg);
        playerBiasPasses.incrementAndGet();

        for (Player player : players) {
            if (player == null) continue;
            if (foliaServer) {
                plugin.getSchedulerAdapter().runEntity(player, () -> {
                    if (!player.isOnline() || shuttingDown) return;
                    int target = calculatePlayerTargetDistance(player, baseView, cfg);
                    int sim = simulationForPlayerTargetFromBaseSimulation(target, baseSimulation, cfg);
                    applyToPlayerOwned(player, target, sim, cfg.affectSendDistance);
                });
            } else {
                if (!player.isOnline()) continue;
                int target = calculatePlayerTargetDistance(player, baseView, cfg);
                int sim = simulationForPlayerTargetFromBaseSimulation(target, baseSimulation, cfg);
                applyToPlayer(player, target, sim, cfg.affectSendDistance);
            }
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


            org.bukkit.util.Vector velocity = player.getVelocity();
            double speedSq = velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ();
            if (speedSq >= cfg.fastTravelThresholdSq) {
                return Math.max(cfg.minChunks, base - cfg.speedReduction);
            }
        } catch (RuntimeException ignored) {

        }

        return base;
    }

    private int simulationForWorldTarget(int viewDistance, Settings cfg) {
        if (!cfg.manageSimulationDistance) {
            return clampSimulationDistance(serverSimulationBaseline);
        }

        double mspt = 10.0D;
        try {
            mspt = plugin.getTPSMonitor().getMSPT();
        } catch (Throwable ignored) {}

        if (mspt >= 50.0D) {
            return clampSimulationDistance(Math.min(3, viewDistance));
        } else if (mspt >= 42.0D) {
            return clampSimulationDistance(Math.min(5, viewDistance - 2));
        }

        return clampSimulationDistance(Math.min(viewDistance, viewDistance - 1));
    }

    private int simulationForPlayerTarget(int playerViewDistance, int baseDistance, Settings cfg) {
        int baseSimulation = simulationForWorldTarget(baseDistance, cfg);
        return simulationForPlayerTargetFromBaseSimulation(playerViewDistance, baseSimulation, cfg);
    }


    private int simulationForPlayerTargetFromBaseSimulation(
            int playerViewDistance,
            int baseSimulationDistance,
            Settings cfg
    ) {
        if (cfg == null || !cfg.manageSimulationDistance) {
            return clampSimulationDistance(serverSimulationBaseline);
        }

        int targetSimulation = Math.min(baseSimulationDistance, playerViewDistance - 1);
        return clampSimulationDistance(targetSimulation);
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
            if (player == null) continue;
            if (foliaServer) {
                plugin.getSchedulerAdapter().runEntity(player, () -> {
                    if (!player.isOnline() || shuttingDown) return;
                    int target = cfg == null ? view : calculatePlayerTargetDistance(player, view, cfg);
                    int sim = cfg == null
                            ? clampSimulationDistance(Math.max(SERVER_MIN_SIMULATION_DISTANCE, view - 1))
                            : simulationForPlayerTargetFromBaseSimulation(target, worldSimulation, cfg);
                    applyToPlayerOwned(player, target, sim, affectSend);
                });
            } else {
                if (!player.isOnline()) continue;
                int target = cfg == null ? view : calculatePlayerTargetDistance(player, view, cfg);
                int sim = cfg == null
                        ? clampSimulationDistance(Math.max(SERVER_MIN_SIMULATION_DISTANCE, view - 1))
                        : simulationForPlayerTargetFromBaseSimulation(target, worldSimulation, cfg);
                applyToPlayer(player, target, sim, affectSend);
            }
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

        if (foliaServer) {


            for (Map.Entry<UUID, PlayerBaseline> entry : playerBaselines.entrySet()) {
                Player player = Bukkit.getPlayer(entry.getKey());
                PlayerBaseline baseline = entry.getValue();
                if (player == null || baseline == null) continue;
                plugin.getSchedulerAdapter().runEntity(player, () -> {
                    if (player.isOnline()) {
                        restorePlayerOwned(player, baseline);
                    }
                });
            }
        } else {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player == null || !player.isOnline()) continue;
                PlayerBaseline baseline = playerBaselines.get(player.getUniqueId());
                if (baseline != null) {
                    restorePlayerOwned(player, baseline);
                }
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
        if (player == null) return;
        if (foliaServer) {
            plugin.getSchedulerAdapter().runEntity(player,
                    () -> applyToPlayerOwned(player, viewDistance, simulationDistance, affectSendDistance));
            return;
        }
        applyToPlayerOwned(player, viewDistance, simulationDistance, affectSendDistance);
    }


    private void applyToPlayerOwned(Player player, int viewDistance, int simulationDistance, boolean affectSendDistance) {
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


    private void restorePlayerOwned(Player player, PlayerBaseline baseline) {
        if (player == null || baseline == null || !player.isOnline()) {
            return;
        }

        viewApi.setPlayerSendViewDistance(player, clampSendViewDistance(baseline.sendViewDistance()));
        viewApi.setPlayerViewDistance(player, clampViewDistance(baseline.viewDistance()));
        viewApi.setPlayerSimulationDistance(player, clampSimulationDistance(baseline.simulationDistance()));
        appliedPlayerDistances.remove(player.getUniqueId());
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


    private void beginCleanupCycle() {
        if (shuttingDown || activeJob != null || foliaServer) {
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
        long configuredBudget = cfg == null ? DEFAULT_CLEANER_BUDGET_NANOS : cfg.cleanerBudgetNanos;
        long budget = resolveCleanerBudgetNanos(configuredBudget);
        if (job.runSlice(Math.max(250_000L, budget))) {
            cancelCleanupJob();
        }
    }


    private long resolveCleanerBudgetNanos(long configuredBudgetNanos) {
        long budget = Math.max(250_000L, configuredBudgetNanos);
        double mspt;

        try {
            mspt = plugin.getTPSMonitor().getMSPT();
        } catch (RuntimeException ignored) {

            return budget;
        }

        if (!Double.isFinite(mspt) || mspt < 0.0D) {
            return budget;
        }
        if (mspt >= CLEANER_CRITICAL_LOAD_MSPT) {
            return Math.min(budget, 250_000L);
        }
        if (mspt >= CLEANER_VERY_HIGH_LOAD_MSPT) {
            return Math.min(budget, 350_000L);
        }
        if (mspt >= CLEANER_HIGH_LOAD_MSPT) {
            return Math.min(budget, 600_000L);
        }
        return budget;
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
        private int slicesUntilProtectionRefresh;


        private final LongBucketIndex playerBuckets = new LongBucketIndex();


        private final Set<UUID> invalidatedWorlds = ConcurrentHashMap.newKeySet();

        CleanupJob(List<World> worlds, Settings cfg) {
            this.worlds = worlds;
            this.cfg = cfg;


        }

        void requestWorldInvalidation(UUID id) {
            if (id != null) {
                invalidatedWorlds.add(id);
            }
        }


        private void consumeWorldInvalidations() {
            if (invalidatedWorlds.isEmpty()) {
                return;
            }

            UUID activeId = currentWorldId;
            if (activeId != null && invalidatedWorlds.remove(activeId)) {
                currentWorld = null;
                currentWorldId = null;
                currentChunks = null;
                chunkIndex = 0;
            }

            for (int i = worldIndex; i < worlds.size(); i++) {
                World candidate = worlds.get(i);
                if (candidate != null && invalidatedWorlds.remove(candidate.getUID())) {
                    worlds.set(i, null);
                }
            }
        }

        private void advanceWorld() {
            while (worldIndex < worlds.size()) {
                World candidate = worlds.get(worldIndex++);
                if (candidate == null) {
                    continue;
                }

                UUID candidateId = candidate.getUID();
                if (invalidatedWorlds.remove(candidateId)) {
                    continue;
                }

                currentWorld = candidate;
                currentWorldId = candidate.getUID();
                currentChunks = candidate.getLoadedChunks();
                chunkIndex = 0;
                slicesUntilProtectionRefresh = 0;
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
            playerBuckets.clear();
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


            for (Player player : players) {
                if (player == null || !player.isOnline()) {
                    continue;
                }
                int index = playerCount;
                int chunkX = floorChunk(player.getX());
                int chunkZ = floorChunk(player.getZ());
                playerChunkX[index] = chunkX;
                playerChunkZ[index] = chunkZ;
                addPlayerToBucket(index, chunkX, chunkZ);
                playerCount++;
            }
        }


        private void addPlayerToBucket(int playerIndex, int chunkX, int chunkZ) {
            int cellX = Math.floorDiv(chunkX, PLAYER_GRID_CELL_SIZE);
            int cellZ = Math.floorDiv(chunkZ, PLAYER_GRID_CELL_SIZE);
            long key = cellKey(cellX, cellZ);
            playerBuckets.getOrCreate(key).add(playerIndex);
        }


        boolean runSlice(long budgetNanos) {
            long started = System.nanoTime();
            cleanerSlices.incrementAndGet();
            boolean done = false;

            try {
                done = runSliceInternal(Math.max(250_000L, budgetNanos));
                return done;
            } finally {
                long elapsed = Math.max(0L, System.nanoTime() - started);
                cleanerTotalSliceNanos.addAndGet(elapsed);
                updateMaximum(cleanerMaxSliceNanos, elapsed);
                if (elapsed > Math.max(250_000L, budgetNanos)) {
                    cleanerBudgetOverruns.incrementAndGet();
                }
                if (done) {
                    cleanerSlicesCompleted.incrementAndGet();
                }
            }
        }

        private boolean runSliceInternal(long budgetNanos) {
            if (finished) {
                return true;
            }


            long started = System.nanoTime();
            long deadline = started > Long.MAX_VALUE - budgetNanos ? Long.MAX_VALUE : started + budgetNanos;

            consumeWorldInvalidations();
            if (currentWorld == null || currentChunks == null) {
                advanceWorld();
                if (finished) {
                    return true;
                }
                if (System.nanoTime() >= deadline) {
                    return false;
                }
            }

            if (slicesUntilProtectionRefresh <= 0) {
                buildProtectionIndex(currentWorld);
                slicesUntilProtectionRefresh = PLAYER_PROTECTION_REFRESH_SLICES;
            }
            slicesUntilProtectionRefresh--;


            if (System.nanoTime() >= deadline) {
                return false;
            }

            int processed = 0;
            int unloadAttempts = 0;
            int hardChunkLimit = Math.min(CLEANER_HARD_CHUNK_LIMIT, cfg.cleanerHardChunkLimit);

            final World world = currentWorld;
            final Chunk[] chunks = currentChunks;
            final int len = chunks.length;

            for (int i = chunkIndex; i < len; i++) {
                Chunk chunk = chunks[i];
                if (chunk != null && chunk.isLoaded()) {
                    if (chunk.isForceLoaded()) {
                        cleanerForceLoadedSkips.incrementAndGet();
                    } else {
                        int x = chunk.getX();
                        int z = chunk.getZ();
                        if (isPlayerProtected(world, chunk, x, z)) {
                            cleanerProtectedChunks.incrementAndGet();
                        } else {
                            tryUnload(world, chunk, x, z);
                            unloadAttempts++;
                            cleanerUnloadAttempts.incrementAndGet();
                        }
                    }
                }
                chunks[i] = null;
                processed++;
                cleanerChunksScanned.incrementAndGet();

                boolean chunkLimitReached = processed >= hardChunkLimit;
                boolean unloadLimitReached = unloadAttempts >= MAX_UNLOAD_ATTEMPTS_PER_SLICE;
                boolean timeToCheck = (processed & (BUDGET_CHECK_INTERVAL - 1)) == 0;

                if (chunkLimitReached || unloadLimitReached || (timeToCheck && System.nanoTime() >= deadline)) {
                    chunkIndex = i + 1;
                    return false;
                }
            }


            currentWorld = null;
            currentWorldId = null;
            currentChunks = null;
            chunkIndex = 0;
            playerCount = 0;
            playerBuckets.clear();
            slicesUntilProtectionRefresh = 0;
            return false;
        }

        private boolean isPlayerProtected(World world, Chunk chunk, int x, int z) {
            if (playerCount == 0) {
                return false;
            }


            int viewerState = viewApi.hasPlayersSeeingChunk(world, x, z);
            if (viewerState == 1) {
                return true;
            }


            return hasNearbyPlayer(x, z, playerChunkX, playerChunkZ, playerCount, protectRadius);
        }

        private void tryUnload(World world, Chunk chunk, int chunkX, int chunkZ) {
            try {
                if (foliaServer) {


                    Location regionAnchor = new Location(
                            world,
                            ((double) chunkX * 16.0D) + 8.0D,
                            0.0D,
                            ((double) chunkZ * 16.0D) + 8.0D
                    );

                    plugin.getSchedulerAdapter().runRegion(regionAnchor, () -> {
                        try {


                            if (chunk == null || !chunk.isLoaded() || chunk.isForceLoaded()) {
                                return;
                            }

                            int currentViewerState = viewApi.hasPlayersSeeingChunk(world, chunkX, chunkZ);
                            if (currentViewerState == 1) {
                                cleanerProtectedChunks.incrementAndGet();
                                return;
                            }

                            if (chunk.unload(true)) {
                                cleanerChunksUnloaded.incrementAndGet();
                            } else {
                                cleanerUnloadFailures.incrementAndGet();
                            }
                        } catch (Throwable ignored) {
                            cleanerUnloadFailures.incrementAndGet();
                        }
                    });
                    return;
                }


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
            if (count <= 0 || radius < 0) {
                return false;
            }

            if (count <= PLAYER_GRID_LINEAR_SCAN_THRESHOLD) {
                return hasNearbyPlayerLinear(chunkX, chunkZ, px, pz, count, radius);
            }

            int minimumCellX = Math.floorDiv(chunkX - radius, PLAYER_GRID_CELL_SIZE);
            int maximumCellX = Math.floorDiv(chunkX + radius, PLAYER_GRID_CELL_SIZE);
            int minimumCellZ = Math.floorDiv(chunkZ - radius, PLAYER_GRID_CELL_SIZE);
            int maximumCellZ = Math.floorDiv(chunkZ + radius, PLAYER_GRID_CELL_SIZE);

            for (int cellX = minimumCellX; cellX <= maximumCellX; cellX++) {
                for (int cellZ = minimumCellZ; cellZ <= maximumCellZ; cellZ++) {
                    IntBucket bucket = playerBuckets.get(cellKey(cellX, cellZ));
                    if (bucket == null) {
                        continue;
                    }

                    for (int candidate = 0, size = bucket.size(); candidate < size; candidate++) {
                        int playerIndex = bucket.get(candidate);
                        int dx = px[playerIndex] - chunkX;
                        if (dx < -radius || dx > radius) {
                            continue;
                        }

                        int dz = pz[playerIndex] - chunkZ;
                        if (dz >= -radius && dz <= radius) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }


        private boolean hasNearbyPlayerLinear(
                int chunkX,
                int chunkZ,
                int[] px,
                int[] pz,
                int count,
                int radius
        ) {
            for (int i = 0; i < count; i++) {
                int dx = px[i] - chunkX;
                if (dx < -radius || dx > radius) {
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


    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFF_FFFFL);
    }


    private static void updateMaximum(AtomicLong maximum, long candidate) {
        long observed = maximum.get();
        while (candidate > observed) {
            if (maximum.compareAndSet(observed, candidate)) {
                return;
            }
            observed = maximum.get();
        }
    }


    private static final class LongBucketIndex {
        private static final int INITIAL_CAPACITY = 16;
        private static final int MAX_CAPACITY = 1 << 26;

        private long[] keys = new long[INITIAL_CAPACITY];
        private IntBucket[] values = new IntBucket[INITIAL_CAPACITY];
        private final List<IntBucket> reusableBuckets = new ArrayList<>();
        private int reusableBucketCount;
        private int size;
        private int threshold = thresholdFor(INITIAL_CAPACITY);

        void clear() {
            for (int i = 0; i < values.length; i++) {
                IntBucket bucket = values[i];
                if (bucket != null) {
                    bucket.clear();
                    if (reusableBucketCount == reusableBuckets.size()) {
                        reusableBuckets.add(bucket);
                    } else {
                        reusableBuckets.set(reusableBucketCount, bucket);
                    }
                    reusableBucketCount++;
                }
            }
            Arrays.fill(values, null);
            size = 0;
        }

        IntBucket get(long key) {
            int mask = values.length - 1;
            int slot = mixToIndex(key, mask);

            while (true) {
                IntBucket value = values[slot];
                if (value == null) {
                    return null;
                }
                if (keys[slot] == key) {
                    return value;
                }
                slot = (slot + 1) & mask;
            }
        }

        IntBucket getOrCreate(long key) {
            int mask = values.length - 1;
            int slot = mixToIndex(key, mask);

            while (true) {
                IntBucket value = values[slot];
                if (value == null) {
                    break;
                }
                if (keys[slot] == key) {
                    return value;
                }
                slot = (slot + 1) & mask;
            }

            if (size + 1 > threshold && values.length < MAX_CAPACITY) {
                grow();
                mask = values.length - 1;
                slot = mixToIndex(key, mask);
                while (values[slot] != null) {
                    slot = (slot + 1) & mask;
                }
            }

            IntBucket created;
            if (reusableBucketCount > 0) {
                created = reusableBuckets.get(--reusableBucketCount);
                created.clear();
            } else {
                created = new IntBucket();
            }
            keys[slot] = key;
            values[slot] = created;
            size++;
            return created;
        }

        private void grow() {
            if (values.length >= MAX_CAPACITY) {
                return;
            }

            long[] oldKeys = keys;
            IntBucket[] oldValues = values;
            int newCapacity = oldValues.length << 1;
            keys = new long[newCapacity];
            values = new IntBucket[newCapacity];
            threshold = thresholdFor(newCapacity);
            size = 0;

            int mask = newCapacity - 1;
            for (int i = 0; i < oldValues.length; i++) {
                IntBucket value = oldValues[i];
                if (value == null) {
                    continue;
                }

                long key = oldKeys[i];
                int slot = mixToIndex(key, mask);
                while (values[slot] != null) {
                    slot = (slot + 1) & mask;
                }
                keys[slot] = key;
                values[slot] = value;
                size++;
            }
        }

        private static int thresholdFor(int capacity) {
            return (capacity * 3) >>> 2;
        }

        private static int mixToIndex(long key, int mask) {
            long value = key;
            value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
            value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return ((int) value) & mask;
        }
    }


    private static final class IntBucket {
        private int[] values = new int[4];
        private int size;

        void clear() {
            size = 0;
        }

        void add(int value) {
            if (size == values.length) {
                int nextCapacity = values.length + (values.length >> 1) + 1;
                int[] expanded = new int[nextCapacity];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = value;
        }

        int get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
            }
            return values[index];
        }

        int size() {
            return size;
        }
    }


    private static final class ViewApi {
        private final Logger logger;

        private volatile MethodHandle worldSetView;
        private volatile MethodHandle worldSetSimulation;
        private volatile MethodHandle worldGetView;
        private volatile MethodHandle worldGetSimulation;

        private volatile MethodHandle playerSetSendView;
        private volatile MethodHandle playerSetView;
        private volatile MethodHandle playerSetSimulation;
        private volatile MethodHandle playerGetSendView;
        private volatile MethodHandle playerGetView;
        private volatile MethodHandle playerGetSimulation;

        private volatile MethodHandle worldPlayersSeeingChunk;

        private volatile boolean worldSetViewFailureLogged;
        private volatile boolean worldSetSimulationFailureLogged;
        private volatile boolean playerSetSendFailureLogged;
        private volatile boolean playerSetViewFailureLogged;
        private volatile boolean playerSetSimulationFailureLogged;

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


    public long getCleanerSlices() {
        return cleanerSlices.get();
    }


    public long getCleanerSlicesCompleted() {
        return cleanerSlicesCompleted.get();
    }


    public long getCleanerBudgetOverruns() {
        return cleanerBudgetOverruns.get();
    }


    public long getCleanerUnloadAttempts() {
        return cleanerUnloadAttempts.get();
    }


    public long getCleanerProtectedChunks() {
        return cleanerProtectedChunks.get();
    }


    public long getCleanerForceLoadedSkips() {
        return cleanerForceLoadedSkips.get();
    }


    public long getCleanerTotalSliceNanos() {
        return cleanerTotalSliceNanos.get();
    }


    public long getCleanerMaxSliceNanos() {
        return cleanerMaxSliceNanos.get();
    }


    public long getPlayerBiasPasses() {
        return playerBiasPasses.get();
    }

    public double getSmoothedTps() {
        return smoothedTpsSeeded ? smoothedTps : INVALID_TPS;
    }
}
