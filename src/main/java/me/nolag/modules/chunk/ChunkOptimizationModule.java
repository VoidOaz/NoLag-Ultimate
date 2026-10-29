package me.nolag.modules.chunk;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Consumer;

/**
 * Chunk performance controller built on top of the server's native chunk pipeline.
 *
 * <p>This module intentionally does not replace Minecraft/Paper internals and never
 * force-unloads chunks. It combines three native distance controls (view, send and
 * simulation), a hysteretic pressure controller, bounded ahead-of-player requests for
 * already-generated chunks, and lightweight chunk lifecycle counters. Chunk I/O,
 * generation, lighting, tracking and unload decisions remain owned by Paper.</p>
 */
public final class ChunkOptimizationModule implements Listener {
    private static final int MIN_DISTANCE = 2;
    private static final int MAX_DISTANCE = 32;
    private static final int MAX_PRESSURE_LEVEL = 8;
    private static final int MAX_VIEW_REDUCTION = 6;
    private static final int MAX_SIMULATION_REDUCTION = 8;
    private static final long CONTROL_INTERVAL_TICKS = 100L;
    private static final int HEALTHY_CHECKS_TO_RECOVER = 3;
    private static final long PLAYER_PREFETCH_COOLDOWN_NANOS = 300_000_000L;
    private static final int MAX_PREFETCH_IN_FLIGHT = 64;
    private static final String ANALYZER_KEY = "ChunkNative";

    private final NoLag plugin;
    private final boolean foliaServer;
    private final int serverViewBaseline;
    private final NativeChunkApi nativeApi;
    private final int serverSimulationBaseline;

    private final ConcurrentMap<UUID, WorldBaseline> worldBaselines = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerBaseline> playerBaselines = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, WorldDistanceState> appliedWorldDistances = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, PlayerDistanceState> appliedPlayerDistances = new ConcurrentHashMap<>();

    // A bounded set of pending native chunk requests; callback completion removes entries.
    private final ConcurrentMap<ChunkRequestKey, Long> prefetchInFlight = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> lastPrefetchNanosByPlayer = new ConcurrentHashMap<>();

    private final AtomicLong controllerCycles = new AtomicLong();
    private final AtomicLong distanceStepsApplied = new AtomicLong();
    private final AtomicLong redundantApplySkips = new AtomicLong();
    private final AtomicLong chunkLoadEvents = new AtomicLong();
    private final AtomicLong chunkUnloadEvents = new AtomicLong();
    private final AtomicLong newlyGeneratedChunkLoads = new AtomicLong();
    private final AtomicLong prefetchRequests = new AtomicLong();
    private final AtomicLong prefetchCompletions = new AtomicLong();
    private final AtomicLong prefetchFailures = new AtomicLong();
    private final AtomicLong prefetchSkipped = new AtomicLong();

    private volatile Settings settings;
    private volatile TaskHandle controllerTask;
    private volatile boolean shuttingDown;
    private volatile boolean distanceModified;
    private volatile int pressureLevel;
    private int healthyStreak;

    public ChunkOptimizationModule(NoLag plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }
        this.plugin = plugin;
        this.foliaServer = plugin.getSchedulerAdapter().isFolia();
        this.serverViewBaseline = clampDistance(readServerViewDistance());
        this.nativeApi = new NativeChunkApi(plugin.getLogger());
        this.serverSimulationBaseline = clampDistance(readServerSimulationDistance());
        this.settings = Settings.load(plugin.getConfig());
        startTasks();
    }

    /** Re-reads the single user-facing switch and recreates the controller task. */
    public synchronized void startTasks() {
        boolean wasModified = distanceModified;
        stopTasks();
        shuttingDown = false;
        settings = Settings.load(plugin.getConfig());
        healthyStreak = 0;

        if (!settings.enabled) {
            if (wasModified) {
                restoreNativeDistances();
            }
            clearTracking();
            pressureLevel = 0;
            return;
        }

        if (!wasModified) {
            clearTracking();
            pressureLevel = 0;
            captureWorldBaselines();
        }
        applyCurrentDistances();
        controllerTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::controlCycle,
                CONTROL_INTERVAL_TICKS,
                CONTROL_INTERVAL_TICKS
        );
        plugin.getLogger().info("[NoLag][ChunkOptimizer] Native chunk pressure controller enabled; "
                + "bounded existing-chunk prefetch=" + (!foliaServer && nativeApi.supportsAsyncChunkRequests()
                && plugin.getConfig().getBoolean("chunk-optimizer.enable-prefetch", false)) + ".");
    }

    public synchronized void stopTasks() {
        TaskHandle task = controllerTask;
        controllerTask = null;
        if (task != null) {
            try {
                if (!task.isCancelled()) {
                    task.cancel();
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE, "[NoLag][ChunkOptimizer] Failed to cancel controller task.", exception);
            }
        }
    }

    public synchronized void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        stopTasks();
        restoreNativeDistances();
        clearTracking();
        pressureLevel = 0;
    }

    private void controlCycle() {
        if (shuttingDown || settings == null || !settings.enabled) {
            return;
        }

        long started = System.nanoTime();
        controllerCycles.incrementAndGet();
        double tps = plugin.getTPSMonitor() == null ? 20.0D : plugin.getTPSMonitor().getTPS();
        double mspt = plugin.getTPSMonitor() == null ? 0.0D : plugin.getTPSMonitor().getMSPT();
        int previousPressure = pressureLevel;

        if (!Double.isFinite(tps)) {
            tps = 20.0D;
        }
        if (!Double.isFinite(mspt) || mspt < 0.0D) {
            mspt = 0.0D;
        }

        // Critical overload reacts faster. Recovery is deliberately slower to prevent flicker.
        if (mspt >= 65.0D || tps <= 12.0D) {
            pressureLevel = Math.min(MAX_PRESSURE_LEVEL, pressureLevel + 2);
            healthyStreak = 0;
        } else if (mspt >= 50.0D || tps <= 16.0D) {
            pressureLevel = Math.min(MAX_PRESSURE_LEVEL, pressureLevel + 1);
            healthyStreak = 0;
        } else if (mspt >= 40.0D || tps < 18.0D) {
            pressureLevel = Math.min(MAX_PRESSURE_LEVEL, pressureLevel + 1);
            healthyStreak = 0;
        } else if (mspt <= 32.0D && tps >= 19.0D) {
            healthyStreak++;
            if (healthyStreak >= HEALTHY_CHECKS_TO_RECOVER) {
                pressureLevel = Math.max(0, pressureLevel - 1);
                healthyStreak = 0;
            }
        } else {
            healthyStreak = 0;
        }

        if (pressureLevel != previousPressure) {
            distanceStepsApplied.addAndGet(Math.abs(pressureLevel - previousPressure));
            applyCurrentDistances();
        }

        if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
            plugin.getPerformanceAnalyzer().record(ANALYZER_KEY, System.nanoTime() - started, safeOnlinePlayerCount());
        }
    }

    private int currentViewReduction() {
        return Math.min(MAX_VIEW_REDUCTION,
                (pressureLevel * MAX_VIEW_REDUCTION + MAX_PRESSURE_LEVEL - 1) / MAX_PRESSURE_LEVEL);
    }

    private int currentSimulationReduction() {
        return Math.min(MAX_SIMULATION_REDUCTION, pressureLevel);
    }

    private void captureWorldBaselines() {
        for (World world : Bukkit.getWorlds()) {
            if (world != null) {
                captureWorldBaseline(world);
            }
        }
        // Player data is entity-owned on Folia, so capture it via each entity scheduler.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null) {
                runForPlayer(player, () -> capturePlayerBaseline(player));
            }
        }
    }

    private void applyCurrentDistances() {
        if (shuttingDown || settings == null || !settings.enabled) {
            return;
        }

        int viewReduction = currentViewReduction();
        int simulationReduction = currentSimulationReduction();
        for (World world : Bukkit.getWorlds()) {
            if (world != null) {
                applyWorldDistances(world, viewReduction, simulationReduction);
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null) {
                runForPlayer(player, () -> applyPlayerDistance(player, viewReduction));
            }
        }
    }

    private void applyWorldDistances(World world, int viewReduction, int simulationReduction) {
        WorldBaseline baseline = captureWorldBaseline(world);
        int targetView = reducedDistance(baseline.viewDistance(), viewReduction);
        int effectiveSendBaseline = baseline.sendViewDistance() >= MIN_DISTANCE
                ? baseline.sendViewDistance() : baseline.viewDistance();
        int targetSend = viewReduction == 0 && baseline.sendViewDistance() == -1
                ? -1 : reducedDistance(effectiveSendBaseline, viewReduction);
        int targetSimulation = reducedDistance(baseline.simulationDistance(), simulationReduction);
        WorldDistanceState target = new WorldDistanceState(targetView, targetSend, targetSimulation);
        WorldDistanceState previous = appliedWorldDistances.get(world.getUID());

        if (target.equals(previous)) {
            // Server state can be changed by another plugin between our checks.
            WorldDistanceState observed = new WorldDistanceState(
                    nativeApi.getWorldViewDistance(world, baseline.viewDistance()),
                    nativeApi.getWorldSendViewDistance(world, baseline.sendViewDistance()),
                    nativeApi.getWorldSimulationDistance(world, baseline.simulationDistance()));
            if (target.equals(observed)) {
                redundantApplySkips.incrementAndGet();
                return;
            }
            appliedWorldDistances.remove(world.getUID());
        }

        // The baseline is recorded without writing anything when no pressure adjustment is needed.
        WorldDistanceState original = new WorldDistanceState(
                baseline.viewDistance(), baseline.sendViewDistance(), baseline.simulationDistance());
        if (previous == null && target.equals(original)) {
            appliedWorldDistances.put(world.getUID(), target);
            return;
        }

        boolean viewWritten = nativeApi.setWorldViewDistance(world, target.viewDistance());
        boolean sendWritten = nativeApi.setWorldSendViewDistance(world, target.sendViewDistance());
        boolean simulationWritten = nativeApi.setWorldSimulationDistance(world, target.simulationDistance());

        // H-04 fix: Read back actual values applied to ensure cache accurately reflects server state
        int actualView = nativeApi.getWorldViewDistance(world, baseline.viewDistance());
        int actualSend = nativeApi.getWorldSendViewDistance(world, baseline.sendViewDistance());
        int actualSimulation = nativeApi.getWorldSimulationDistance(world, baseline.simulationDistance());
        WorldDistanceState actualState = new WorldDistanceState(actualView, actualSend, actualSimulation);
        if (viewWritten && sendWritten && simulationWritten && actualState.equals(target)) {
            appliedWorldDistances.put(world.getUID(), actualState);
        } else {
            // Missing/failing API must be retried rather than cached as success.
            appliedWorldDistances.remove(world.getUID());
        }
        if (!actualState.equals(original)) {
            distanceModified = true;
        }
    }

    private void applyPlayerDistance(Player player, int viewReduction) {
        if (player == null || !player.isOnline() || shuttingDown || settings == null || !settings.enabled) {
            return;
        }

        PlayerBaseline baseline = capturePlayerBaseline(player);
        PlayerDistanceState previous = appliedPlayerDistances.get(player.getUniqueId());
        if (viewReduction == 0) {
            if (previous != null) {
                nativeApi.setPlayerViewDistance(player, normalizeRestoreDistance(baseline.viewDistance()));
                appliedPlayerDistances.remove(player.getUniqueId());
            }
            return;
        }

        WorldBaseline worldBaseline = worldBaselineFor(player.getWorld());
        int baseView = baseline.viewDistance() >= MIN_DISTANCE
                ? baseline.viewDistance() : worldBaseline.viewDistance();
        int baseSend = baseline.sendViewDistance() >= MIN_DISTANCE
                ? baseline.sendViewDistance()
                : (worldBaseline.sendViewDistance() >= MIN_DISTANCE
                    ? worldBaseline.sendViewDistance() : worldBaseline.viewDistance());
        int targetView = reducedDistance(baseView, viewReduction);
        PlayerDistanceState target = new PlayerDistanceState(targetView, baseSend);

        if (previous != null && target.viewDistance() == previous.viewDistance()) {
            // Nether protection owns the player send distance; compare our view setting only.
            int observedView = nativeApi.getPlayerViewDistance(player, baseView);
            if (target.viewDistance() == observedView) {
                redundantApplySkips.incrementAndGet();
                return;
            }
            appliedPlayerDistances.remove(player.getUniqueId());
        }
        nativeApi.setPlayerViewDistance(player, target.viewDistance());

        int actualPlayerView = nativeApi.getPlayerViewDistance(player, baseView);
        int actualPlayerSend = nativeApi.getPlayerSendViewDistance(player, baseSend);
        PlayerDistanceState actualState = new PlayerDistanceState(actualPlayerView, actualPlayerSend);
        appliedPlayerDistances.put(player.getUniqueId(), actualState);
        if (actualPlayerView != baseView) {
            distanceModified = true;
        }
    }

    private WorldBaseline captureWorldBaseline(World world) {
        return worldBaselines.computeIfAbsent(world.getUID(), ignored -> {
            int view = clampDistance(nativeApi.getWorldViewDistance(world, serverViewBaseline));
            int send = normalizeBaselineSendDistance(nativeApi.getWorldSendViewDistance(world, view));
            int simulation = clampDistance(nativeApi.getWorldSimulationDistance(world, serverViewBaseline));
            return new WorldBaseline(view, send, simulation);
        });
    }

    private PlayerBaseline capturePlayerBaseline(Player player) {
        return playerBaselines.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerBaseline(
                nativeApi.getPlayerViewDistance(player, -1),
                nativeApi.getPlayerSendViewDistance(player, -1)
        ));
    }

    private WorldBaseline worldBaselineFor(World world) {
        if (world == null) {
            return new WorldBaseline(serverViewBaseline, -1, serverViewBaseline);
        }
        WorldBaseline baseline = worldBaselines.get(world.getUID());
        return baseline != null ? baseline : captureWorldBaseline(world);
    }

    private void restoreNativeDistances() {
        if (!distanceModified) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world == null) {
                continue;
            }
            WorldBaseline baseline = worldBaselines.get(world.getUID());
            if (baseline == null) {
                continue;
            }
            nativeApi.setWorldViewDistance(world, baseline.viewDistance());
            nativeApi.setWorldSendViewDistance(world, baseline.sendViewDistance());
            nativeApi.setWorldSimulationDistance(world, baseline.simulationDistance());
            appliedWorldDistances.remove(world.getUID());
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) {
                continue;
            }
            PlayerBaseline baseline = playerBaselines.get(player.getUniqueId());
            if (baseline != null) {
                runForPlayer(player, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    nativeApi.setPlayerViewDistance(player, normalizeRestoreDistance(baseline.viewDistance()));
                    appliedPlayerDistances.remove(player.getUniqueId());
                });
            }
        }
        distanceModified = false;
    }

    private void restoreSingleWorld(World world) {
        WorldBaseline baseline = worldBaselines.get(world.getUID());
        if (baseline == null) {
            return;
        }
        nativeApi.setWorldViewDistance(world, baseline.viewDistance());
        nativeApi.setWorldSendViewDistance(world, baseline.sendViewDistance());
        nativeApi.setWorldSimulationDistance(world, baseline.simulationDistance());
    }

    private void runForPlayer(Player player, Runnable action) {
        if (foliaServer) {
            plugin.getSchedulerAdapter().runEntity(player, action);
        } else {
            action.run();
        }
    }

    /** Called by RamFixer only on non-Folia servers; all other cache cleanup is event-driven. */
    public void trimCaches() {
        if (shuttingDown || foliaServer) {
            return;
        }
        worldBaselines.keySet().removeIf(id -> Bukkit.getWorld(id) == null);
        appliedWorldDistances.keySet().removeIf(id -> Bukkit.getWorld(id) == null);
        playerBaselines.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        appliedPlayerDistances.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        lastPrefetchNanosByPlayer.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        // in-flight requests are strictly capped and are removed by their completion callbacks.
    }

    private void clearTracking() {
        worldBaselines.clear();
        playerBaselines.clear();
        appliedWorldDistances.clear();
        appliedPlayerDistances.clear();
        prefetchInFlight.clear();
        lastPrefetchNanosByPlayer.clear();
        distanceModified = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!isActive()) {
            return;
        }
        Player player = event.getPlayer();
        runForPlayer(player, () -> {
            capturePlayerBaseline(player);
            applyPlayerDistance(player, currentViewReduction());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerBaselines.remove(id);
        appliedPlayerDistances.remove(id);
        lastPrefetchNanosByPlayer.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!isActive()) {
            return;
        }
        applyWorldDistances(event.getWorld(), currentViewReduction(), currentSimulationReduction());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        restoreSingleWorld(world);
        UUID id = world.getUID();
        worldBaselines.remove(id);
        appliedWorldDistances.remove(id);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!isActive()) {
            return;
        }
        Player player = event.getPlayer();
        runForPlayer(player, () -> applyPlayerDistance(player, currentViewReduction()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isActive() || foliaServer || !nativeApi.supportsAsyncChunkRequests()
                || !plugin.getConfig().getBoolean("chunk-optimizer.enable-prefetch", false)) {
            return;
        }
        if (event.getTo() == null || event.getFrom().getWorld() == null
                || event.getFrom().getWorld() != event.getTo().getWorld()) {
            return;
        }

        if (plugin.getTPSMonitor() != null && plugin.getTPSMonitor().getTPS() < 17.0D) {
            prefetchSkipped.incrementAndGet();
            return;
        }

        var from = event.getFrom();
        var to = event.getTo();
        int fromChunkX = from.getBlockX() >> 4;
        int fromChunkZ = from.getBlockZ() >> 4;
        int toChunkX = to.getBlockX() >> 4;
        int toChunkZ = to.getBlockZ() >> 4;
        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastPrefetchCleanupNanos > 10_000_000_000L) {
            lastPrefetchCleanupNanos = now;
            prefetchInFlight.entrySet().removeIf(entry -> now - entry.getValue() > 10_000_000_000L);
        }

        // Teleports and large jumps should not enqueue speculative work behind the player.
        if (Math.abs((long) to.getBlockX() - from.getBlockX()) > 32L
                || Math.abs((long) to.getBlockZ() - from.getBlockZ()) > 32L) {
            prefetchSkipped.incrementAndGet();
            return;
        }

        Long last = lastPrefetchNanosByPlayer.get(event.getPlayer().getUniqueId());
        if (last != null && now - last < PLAYER_PREFETCH_COOLDOWN_NANOS) {
            prefetchSkipped.incrementAndGet();
            return;
        }

        int deltaChunkX = Integer.compare(toChunkX, fromChunkX);
        int deltaChunkZ = Integer.compare(toChunkZ, fromChunkZ);
        int moveBlockX = to.getBlockX() - from.getBlockX();
        int moveBlockZ = to.getBlockZ() - from.getBlockZ();
        int targetChunkX;
        int targetChunkZ;
        if (Math.abs(moveBlockX) >= Math.abs(moveBlockZ)) {
            targetChunkX = toChunkX + (deltaChunkX == 0 ? Integer.compare(moveBlockX, 0) : deltaChunkX);
            targetChunkZ = toChunkZ;
        } else {
            targetChunkX = toChunkX;
            targetChunkZ = toChunkZ + (deltaChunkZ == 0 ? Integer.compare(moveBlockZ, 0) : deltaChunkZ);
        }

        World world = to.getWorld();
        if (world == null || world.isChunkLoaded(targetChunkX, targetChunkZ)) {
            prefetchSkipped.incrementAndGet();
            return;
        }
        try {
            // Never generate terrain speculatively. Prefetch only chunks already generated on disk.
            if (!world.isChunkGenerated(targetChunkX, targetChunkZ)) {
                prefetchSkipped.incrementAndGet();
                return;
            }
        } catch (RuntimeException | LinkageError exception) {
            prefetchSkipped.incrementAndGet();
            return;
        }

        if (prefetchInFlight.size() >= MAX_PREFETCH_IN_FLIGHT) {
            prefetchSkipped.incrementAndGet();
            return;
        }

        ChunkRequestKey key = new ChunkRequestKey(world.getUID(), targetChunkX, targetChunkZ);
        if (prefetchInFlight.putIfAbsent(key, now) != null) {
            prefetchSkipped.incrementAndGet();
            return;
        }
        lastPrefetchNanosByPlayer.put(event.getPlayer().getUniqueId(), now);
        prefetchRequests.incrementAndGet();
        try {
            nativeApi.requestExistingChunk(world, targetChunkX, targetChunkZ, chunk -> {
                prefetchInFlight.remove(key);
                if (shuttingDown || settings == null || !settings.enabled) {
                    return;
                }
                if (chunk == null) {
                    prefetchFailures.incrementAndGet();
                } else {
                    prefetchCompletions.incrementAndGet();
                }
            });
        } catch (RuntimeException | LinkageError exception) {
            prefetchInFlight.remove(key);
            prefetchFailures.incrementAndGet();
            nativeApi.logPrefetchFailureOnce(exception);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!isActive()) {
            return;
        }
        chunkLoadEvents.incrementAndGet();
        if (event.isNewChunk()) {
            newlyGeneratedChunkLoads.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (isActive()) {
            chunkUnloadEvents.incrementAndGet();
        }
    }

    private boolean isActive() {
        Settings cfg = settings;
        return !shuttingDown && cfg != null && cfg.enabled;
    }

    private int safeOnlinePlayerCount() {
        try {
            return Bukkit.getOnlinePlayers().size();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static int reducedDistance(int baseline, int reduction) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, baseline) - Math.max(0, reduction));
    }

    private static int normalizeBaselineSendDistance(int distance) {
        return distance == -1 ? -1 : clampDistance(distance);
    }

    private static int normalizeRestoreDistance(int distance) {
        return distance == -1 ? -1 : clampDistance(distance);
    }

    private static int clampDistance(int distance) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
    }

    private static int readServerViewDistance() {
        try {
            return Bukkit.getViewDistance();
        } catch (RuntimeException exception) {
            return 8;
        }
    }

    private int readServerSimulationDistance() {
        try {
            for (World world : Bukkit.getWorlds()) {
                if (world != null) {
                    return nativeApi.getWorldSimulationDistance(world, serverViewBaseline);
                }
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not read initial simulation distance; using view distance as fallback.", exception);
        }
        return serverViewBaseline;
    }

    public int getCurrentViewDistance() {
        return reducedDistance(serverViewBaseline, currentViewReduction());
    }

    public int getCurrentSimulationDistance() {
        return reducedDistance(serverSimulationBaseline, currentSimulationReduction());
    }

    public int getPressureLevel() {
        return pressureLevel;
    }

    public boolean isChunkOptimizerEnabled() {
        Settings cfg = settings;
        return cfg != null && cfg.enabled;
    }

    public long getControllerCycles() { return controllerCycles.get(); }
    public long getDistanceStepsApplied() { return distanceStepsApplied.get(); }
    public long getRedundantApplySkips() { return redundantApplySkips.get(); }
    public long getChunkLoadEvents() { return chunkLoadEvents.get(); }
    public long getChunkUnloadEvents() { return chunkUnloadEvents.get(); }
    public long getNewlyGeneratedChunkLoads() { return newlyGeneratedChunkLoads.get(); }
    public long getPrefetchRequests() { return prefetchRequests.get(); }
    public long getPrefetchCompletions() { return prefetchCompletions.get(); }
    public long getPrefetchFailures() { return prefetchFailures.get(); }
    public long getPrefetchSkipped() { return prefetchSkipped.get(); }
    public int getPrefetchInFlight() { return prefetchInFlight.size(); }

    // Legacy GUI/command counters retained for binary/source compatibility with existing UI code.
    public boolean isCleanerRunning() { return false; }
    public long getCleanerChunksScanned() { return 0L; }
    public long getCleanerChunksUnloaded() { return 0L; }
    public long getCleanerUnloadFailures() { return 0L; }
    public long getCleanerSlices() { return 0L; }
    public long getCleanerSlicesCompleted() { return 0L; }
    public long getCleanerBudgetOverruns() { return 0L; }
    public long getCleanerUnloadAttempts() { return 0L; }
    public long getCleanerProtectedChunks() { return 0L; }
    public long getCleanerForceLoadedSkips() { return 0L; }
    public long getCleanerTotalSliceNanos() { return 0L; }
    public long getCleanerMaxSliceNanos() { return 0L; }
    public long getPlayerBiasPasses() { return 0L; }

    private volatile long lastPrefetchCleanupNanos;

    private record WorldBaseline(int viewDistance, int sendViewDistance, int simulationDistance) {}
    private record PlayerBaseline(int viewDistance, int sendViewDistance) {}
    private record PlayerDistanceState(int viewDistance, int sendViewDistance) {}
    private record WorldDistanceState(int viewDistance, int sendViewDistance, int simulationDistance) {}
    private record ChunkRequestKey(UUID worldId, int x, int z) {}

    private record Settings(boolean enabled) {
        static Settings load(FileConfiguration config) {
            return new Settings(config == null || config.getBoolean("chunk-optimizer.enabled", true));
        }
    }

    /** Optional Paper methods are resolved reflectively, so compatible forks fail safely. */
    private static final class NativeChunkApi {
        private final Logger logger;
        private final Method worldGetView;
        private final Method worldSetView;
        private final Method worldGetSendView;
        private final Method worldSetSendView;
        private final Method worldGetSimulation;
        private final Method worldSetSimulation;
        private final Method playerGetView;
        private final Method playerSetView;
        private final Method playerGetSendView;
        private final Method playerSetSendView;
        private final Method asyncChunkRequest;
        private final java.util.Set<String> warned = ConcurrentHashMap.newKeySet();

        private NativeChunkApi(Logger logger) {
            this.logger = logger;
            worldGetView = findMethod(World.class, "getViewDistance");
            worldSetView = findMethod(World.class, "setViewDistance", int.class);
            worldGetSendView = findMethod(World.class, "getSendViewDistance");
            worldSetSendView = findMethod(World.class, "setSendViewDistance", int.class);
            worldGetSimulation = findMethod(World.class, "getSimulationDistance");
            worldSetSimulation = findMethod(World.class, "setSimulationDistance", int.class);
            playerGetView = findMethod(Player.class, "getViewDistance");
            playerSetView = findMethod(Player.class, "setViewDistance", int.class);
            playerGetSendView = findMethod(Player.class, "getSendViewDistance");
            playerSetSendView = findMethod(Player.class, "setSendViewDistance", int.class);
            asyncChunkRequest = findMethod(World.class, "getChunkAtAsync",
                    int.class, int.class, boolean.class, boolean.class, Consumer.class);
        }

        int getWorldViewDistance(World world, int fallback) {
            return readInt(worldGetView, world, fallback);
        }

        int getWorldSendViewDistance(World world, int fallback) {
            return readInt(worldGetSendView, world, fallback);
        }

        int getWorldSimulationDistance(World world, int fallback) {
            return readInt(worldGetSimulation, world, fallback);
        }

        boolean setWorldViewDistance(World world, int value) {
            return writeInt(worldSetView, world, value, "World#setViewDistance");
        }

        boolean setWorldSendViewDistance(World world, int value) {
            return worldSetSendView != null && writeInt(worldSetSendView, world, value, "World#setSendViewDistance");
        }

        boolean setWorldSimulationDistance(World world, int value) {
            return worldSetSimulation != null && writeInt(worldSetSimulation, world, value, "World#setSimulationDistance");
        }

        int getPlayerViewDistance(Player player, int fallback) {
            return readInt(playerGetView, player, fallback);
        }

        int getPlayerSendViewDistance(Player player, int fallback) {
            return readInt(playerGetSendView, player, fallback);
        }

        boolean setPlayerViewDistance(Player player, int value) {
            return writeInt(playerSetView, player, value, "Player#setViewDistance");
        }

        boolean setPlayerSendViewDistance(Player player, int value) {
            return playerSetSendView != null && writeInt(playerSetSendView, player, value, "Player#setSendViewDistance");
        }

        boolean supportsAsyncChunkRequests() {
            return asyncChunkRequest != null;
        }

        @SuppressWarnings("unchecked")
        void requestExistingChunk(World world, int x, int z, Consumer<Chunk> callback) throws RuntimeException {
            if (asyncChunkRequest == null) {
                throw new IllegalStateException("Native async chunk API is unavailable");
            }
            try {
                // gen=false and urgent=false: no speculative terrain generation or priority boost.
                asyncChunkRequest.invoke(world, x, z, false, false, callback);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw new IllegalStateException("Native async chunk request failed", exception);
            }
        }

        void logPrefetchFailureOnce(Throwable throwable) {
            warnOnce("async chunk request", "Native existing-chunk prefetch failed: " + throwable.getMessage(), throwable);
        }

        private int readInt(Method method, Object receiver, int fallback) {
            if (method == null || receiver == null) {
                return fallback;
            }
            try {
                Object value = method.invoke(receiver);
                return value instanceof Number number ? number.intValue() : fallback;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                warnOnce(method.getDeclaringClass().getSimpleName() + "#" + method.getName(),
                        "Could not read optional native chunk setting " + method.getName() + "; using fallback.", exception);
                return fallback;
            }
        }

        private boolean writeInt(Method method, Object receiver, int value, String displayName) {
            if (method == null || receiver == null) {
                warnOnce(displayName, "Optional native API " + displayName + " is unavailable on this server.", null);
                return false;
            }
            try {
                method.invoke(receiver, value);
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                warnOnce(displayName, "Could not apply native chunk setting " + displayName + ".", exception);
                return false;
            }
        }

        private void warnOnce(String key, String message, Throwable throwable) {
            if (!warned.add(key)) {
                return;
            }
            if (throwable == null) {
                logger.warning("[NoLag][ChunkOptimizer] " + message);
            } else {
                logger.log(Level.WARNING, "[NoLag][ChunkOptimizer] " + message, throwable);
            }
        }

        private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
            try {
                return owner.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException | SecurityException ignored) {
                return null;
            }
        }
    }
}
