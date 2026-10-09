package me.nolag.modules.redstone;

import me.nolag.platform.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * High-confidence redstone lag-machine detector and neutralizer.
 *
 * <p>The detector is deliberately conservative. A single fast redstone clock is not
 * automatically treated as a lag machine because legitimate 1-tick clocks and compact
 * farms can legitimately generate high event rates. A block is neutralized only when
 * several independent signals agree:</p>
 *
 * <ul>
 *   <li>the block itself is toggling repeatedly inside a sliding window;</li>
 *   <li>the signal exhibits clock-like alternation / short intervals;</li>
 *   <li>nearby redstone machinery provides a plausible machine topology;</li>
 *   <li>the containing chunk has sustained weighted redstone activity;</li>
 *   <li>for area-assisted mitigation, multiple hot blocks participate in the same burst.</li>
 * </ul>
 *
 * <p>Mitigation is non-destructive: when a source is confirmed, the event's new current
 * is reset to its previous current. Paper documents that changing the event's new current
 * prevents most of the normal action caused by the redstone update.</p>
 *
 * <p>All event-path state is allocation-light and uses compact numeric keys instead of
 * per-event coordinate strings. The internal maps are concurrent so the detector remains
 * safe when redstone events are delivered by region threads on Folia. When a
 * {@link SchedulerAdapter} is supplied, maintenance runs on its global scheduler.</p>
 */
public final class RedstoneLagDetectorModule implements Listener {

    // ---------------------------------------------------------------------------------
    // Conservative defaults
    // ---------------------------------------------------------------------------------

    private static final long CLOCK_WINDOW_NANOS = 1_000_000_000L;
    private static final long RAPID_INTERVAL_NANOS = 125_000_000L; // ~2.5 ticks at 20 TPS
    private static final long PROBE_INTERVAL_NANOS = 500_000_000L;
    private static final long DISTINCT_BLOCK_WINDOW_NANOS = 1_000_000_000L;
    private static final long CLEANUP_QUIET_NANOS = 8_000_000_000L;
    private static final long LOG_COOLDOWN_NANOS = 5_000_000_000L;

    private static final int BUCKET_COUNT = 12;
    private static final long BUCKET_NANOS = 100_000_000L;
    private static final int MAX_BLOCK_SAMPLES = 256;

    private static final int DEFAULT_CHUNK_WARN_SCORE = 60;
    private static final int DEFAULT_CHUNK_BLOCK_SCORE = 250;
    private static final long DEFAULT_HALF_LIFE_MILLIS = 20_000L;
    private static final long DEFAULT_CLOCK_WINDOW_MILLIS = 1_000L;
    private static final int DEFAULT_CLOCK_TRIGGER_COUNT = 6;
    private static final long DEFAULT_FREEZE_MILLIS = 5_000L;
    private static final int DEFAULT_MIN_EVENTS = 5;

    private static final int MAX_TRACKED_BLOCKS_PER_WORLD = 50_000;
    private static final int MAX_TRACKED_CHUNKS_PER_WORLD = 8_000;
    private static final int PRUNE_LIMIT_PER_WORLD = 192;

    // ---------------------------------------------------------------------------------
    // Tuning
    // ---------------------------------------------------------------------------------

    private final int chunkWarnScore;
    private final int chunkBlockScore;
    private final long chunkScoreHalfLifeNanos;

    private final long clockWindowNanos;
    private final int clockTriggerCount;
    private final long baseFreezeNanos;
    private final int minEventsBeforeWeighing;

    // Derived high-confidence thresholds.
    private final int singleBlockRateThreshold;
    private final int areaAssistedRateThreshold;

    // ---------------------------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------------------------

    private final Plugin owner;
    private final SchedulerAdapter schedulerAdapter;
    private final Map<UUID, WorldState> worlds = new ConcurrentHashMap<>();

    /** Optional admin exemptions. These are cold-path structures; the event path only probes them. */
    private final Set<String> exemptWorlds = ConcurrentHashMap.newKeySet();
    private final Set<String> exemptBlocks = ConcurrentHashMap.newKeySet();

    private final LongAdder observedTransitions = new LongAdder();
    private final LongAdder suppressedTransitions = new LongAdder();
    private final LongAdder confirmedMachines = new LongAdder();
    private final LongAdder areaAssistedSuppressions = new LongAdder();
    private final LongAdder detectorErrors = new LongAdder();

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private volatile SchedulerAdapter.TaskHandle maintenanceTask;
    private volatile long lastLogNanos;

    // 26-neighbour cube. It is used only during candidate evaluation, never for every event.
    private static final int[] NEIGHBOUR_OFFSETS = buildNeighbourOffsets();

    // ---------------------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------------------

    public RedstoneLagDetectorModule(Plugin owner) {
        this(owner, null,
                DEFAULT_CHUNK_WARN_SCORE,
                DEFAULT_CHUNK_BLOCK_SCORE,
                DEFAULT_HALF_LIFE_MILLIS,
                DEFAULT_CLOCK_WINDOW_MILLIS,
                DEFAULT_CLOCK_TRIGGER_COUNT,
                DEFAULT_FREEZE_MILLIS,
                DEFAULT_MIN_EVENTS);
    }

    public RedstoneLagDetectorModule(Plugin owner, SchedulerAdapter schedulerAdapter) {
        this(owner, schedulerAdapter,
                DEFAULT_CHUNK_WARN_SCORE,
                DEFAULT_CHUNK_BLOCK_SCORE,
                DEFAULT_HALF_LIFE_MILLIS,
                DEFAULT_CLOCK_WINDOW_MILLIS,
                DEFAULT_CLOCK_TRIGGER_COUNT,
                DEFAULT_FREEZE_MILLIS,
                DEFAULT_MIN_EVENTS);
    }

    /**
     * Backward-compatible constructor. Existing values are retained, but they are now
     * interpreted as conservative inputs to a multi-signal detector instead of single rules.
     */
    public RedstoneLagDetectorModule(Plugin owner,
                                     int chunkWarnScore,
                                     int chunkBlockScore,
                                     long chunkScoreHalfLifeMillis,
                                     long clockWindowMillis,
                                     int clockTriggerCount,
                                     long clockFreezeMillis,
                                     int minEventsBeforeWeighing) {
        this(owner, null,
                chunkWarnScore,
                chunkBlockScore,
                chunkScoreHalfLifeMillis,
                clockWindowMillis,
                clockTriggerCount,
                clockFreezeMillis,
                minEventsBeforeWeighing);
    }

    public RedstoneLagDetectorModule(Plugin owner,
                                     SchedulerAdapter schedulerAdapter,
                                     int chunkWarnScore,
                                     int chunkBlockScore,
                                     long chunkScoreHalfLifeMillis,
                                     long clockWindowMillis,
                                     int clockTriggerCount,
                                     long clockFreezeMillis,
                                     int minEventsBeforeWeighing) {
        if (owner == null) {
            throw new IllegalArgumentException("owner cannot be null");
        }

        this.owner = owner;
        this.schedulerAdapter = schedulerAdapter;

        this.chunkWarnScore = Math.max(1, chunkWarnScore);
        this.chunkBlockScore = Math.max(this.chunkWarnScore + 1, chunkBlockScore);
        this.chunkScoreHalfLifeNanos = Math.max(1_000_000_000L, chunkScoreHalfLifeMillis * 1_000_000L);

        this.clockWindowNanos = clampNanos(clockWindowMillis * 1_000_000L, 250_000_000L, 5_000_000_000L);
        this.clockTriggerCount = clamp(clockTriggerCount, 4, 32);
        this.baseFreezeNanos = clampNanos(clockFreezeMillis * 1_000_000L, 1_000_000_000L, 120_000_000_000L);
        this.minEventsBeforeWeighing = clamp(minEventsBeforeWeighing, 3, 32);

        // Important: 6 events/sec is far too permissive for modern redstone.
        // We derive a considerably stronger threshold while keeping configuration semantics.
        this.singleBlockRateThreshold = Math.max(12, this.clockTriggerCount * 2);
        this.areaAssistedRateThreshold = Math.max(5, (this.clockTriggerCount * 3) / 2);

        startMaintenanceTask();
    }

    // ---------------------------------------------------------------------------------
    // Event path
    // ---------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent event) {
        if (shutdown.get() || event == null || event.isAsynchronous()) {
            return;
        }

        try {
            handleRedstone(event);
        } catch (RuntimeException ex) {
            // Fail-open: a detector bug must never become a redstone bug.
            detectorErrors.increment();
            logDetectorError(ex);
        }
    }

    private void handleRedstone(BlockRedstoneEvent event) {
        final Block block = event.getBlock();
        if (block == null) {
            return;
        }

        final int oldCurrent = event.getOldCurrent();
        final int newCurrent = event.getNewCurrent();

        // BlockRedstoneEvent is about current changes. Do not spend detector budget on no-ops.
        if (oldCurrent == newCurrent) {
            return;
        }

        final int machineWeight = machineWeight(block.getType());
        final boolean suppressible = isSuppressibleComponent(block.getType());

        // Interactive sources are useful for chunk activity, but individually suppressing
        // buttons/levers/pressure plates is a high false-positive strategy.
        if (machineWeight == 0 && !suppressible) {
            return;
        }

        final String worldName = block.getWorld().getName();
        if (exemptWorlds.contains(worldName)) {
            return;
        }

        final WorldState worldState = worlds.computeIfAbsent(
                block.getWorld().getUID(), ignored -> new WorldState());

        final long now = System.nanoTime();
        final long blockKey = packBlock(block.getX(), block.getY(), block.getZ());
        final long chunkKey = packChunk(block.getChunk().getX(), block.getChunk().getZ());

        if (!exemptBlocks.isEmpty() && exemptBlocks.contains(makeBlockKey(worldName, block))) {
            return;
        }

        final ChunkHeat chunkHeat = worldState.chunks.computeIfAbsent(chunkKey, ignored -> new ChunkHeat());
        chunkHeat.record(now, machineWeight);

        observedTransitions.increment();

        // Interactive/source blocks still participate in chunk-level evidence.
        if (!suppressible) {
            return;
        }

        final BlockHeat blockHeat = worldState.blocks.computeIfAbsent(
                blockKey, ignored -> new BlockHeat(Math.min(MAX_BLOCK_SAMPLES, Math.max(32, this.clockTriggerCount * 8))));

        if (blockHeat.isFrozen(now)) {
            event.setNewCurrent(oldCurrent);
            suppressedTransitions.increment();
            return;
        }

        blockHeat.observe(now, oldCurrent, newCurrent, this.clockWindowNanos);

        // Do not evaluate every event. The state machine only becomes expensive after a
        // block has accumulated enough evidence to be a plausible clock.
        if (blockHeat.sampleCount < minEventsBeforeWeighing) {
            maybePruneLazy(worldState, now);
            return;
        }

        if (blockHeat.sampleCount >= areaAssistedRateThreshold) {
            chunkHeat.markHotBlock(now, blockKey);
        }

        if (now < blockHeat.nextEvaluationNanos) {
            return;
        }
        blockHeat.nextEvaluationNanos = now + 50_000_000L; // evaluate at most every 50 ms

        final DetectionResult result = evaluate(block, worldState, blockHeat, chunkHeat, now, machineWeight);
        if (result.confidence >= 100) {
            final long freeze = calculateFreezeNanos(blockHeat, result.areaAssisted);
            blockHeat.freezeUntilNanos = now + freeze;
            blockHeat.confirmationStrikes++;
            blockHeat.confidence = result.confidence;

            confirmedMachines.increment();
            if (result.areaAssisted) {
                areaAssistedSuppressions.increment();
            }

            event.setNewCurrent(oldCurrent);
            suppressedTransitions.increment();

            logSuppression(block, result, freeze);
            return;
        }

        blockHeat.confidence = Math.max(blockHeat.confidence * 0.97D, result.confidence);
        blockHeat.lastScoreNanos = now;

        if ((observedTransitions.sum() & 1023L) == 0L) {
            maybePruneLazy(worldState, now);
        }
    }

    // ---------------------------------------------------------------------------------
    // Detection model
    // ---------------------------------------------------------------------------------

    private DetectionResult evaluate(Block block,
                                     WorldState worldState,
                                     BlockHeat blockHeat,
                                     ChunkHeat chunkHeat,
                                     long now,
                                     int machineWeight) {
        blockHeat.window.updateStats(now, this.clockWindowNanos);
        final int sampleCount = blockHeat.window.size;
        if (sampleCount < minEventsBeforeWeighing) {
            return DetectionResult.NONE;
        }

        if (now >= blockHeat.nextProbeNanos) {
            blockHeat.localMachineWeight = inspectLocalMachine(block);
            blockHeat.nextProbeNanos = now + PROBE_INTERVAL_NANOS;
        }

        final int localWeight = blockHeat.localMachineWeight;
        final int weightedChunkEvents = chunkHeat.weightedEvents(now);
        final int totalChunkEvents = chunkHeat.totalEvents(now);
        final int distinctHotBlocks = chunkHeat.distinctHotBlockCount(now);
        final double sustainedHeat = chunkHeat.sustainedHeat(now, this.chunkScoreHalfLifeNanos);

        double score = 0.0D;
        boolean areaAssisted = false;

        final double ratePerSecond = sampleCount * (1_000_000_000.0D / this.clockWindowNanos);
        // 1) Individual block clock evidence.
        if (ratePerSecond >= singleBlockRateThreshold) {
            score += 32.0D;
        } else if (ratePerSecond >= singleBlockRateThreshold * 0.75D) {
            score += 18.0D;
        }

        if (blockHeat.window.rapidIntervals >= Math.max(3, clockTriggerCount / 2)) {
            score += 18.0D;
        }

        if (blockHeat.window.binaryAlternations >= Math.max(4, clockTriggerCount / 2)) {
            score += 18.0D;
        }

        if (blockHeat.window.largeTransitions >= Math.max(3, clockTriggerCount / 3)) {
            score += 8.0D;
        }

        // 2) Local topology evidence. A rapidly-changing isolated redstone component is
        // much less suspicious than the same clock embedded in actuator/control hardware.
        if (localWeight >= 4) {
            score += 8.0D;
        }
        if (localWeight >= 8) {
            score += 12.0D;
        }
        if (machineWeight >= 4) {
            score += 5.0D;
        }

        // 3) Chunk evidence. This is what prevents a tiny legitimate clock from being
        // treated as a lag machine solely because it oscillates quickly.
        if (weightedChunkEvents >= chunkWarnScore) {
            score += 8.0D;
        }
        if (weightedChunkEvents >= chunkBlockScore) {
            score += 24.0D;
        }
        if (sustainedHeat >= chunkWarnScore * 2.0D) {
            score += 5.0D;
        }
        if (sustainedHeat >= chunkBlockScore * 2.0D) {
            score += 10.0D;
        }
        if (totalChunkEvents >= 40) {
            score += 5.0D;
        }

        // 4) Area-assisted confirmation. Multiple high-activity blocks in one burst is a
        // much stronger signature of a lag machine than one clock alone.
        if (weightedChunkEvents >= chunkBlockScore
                && distinctHotBlocks >= 3
                && sampleCount >= areaAssistedRateThreshold) {
            score += 25.0D;
            areaAssisted = true;
        }

        // An extremely strong local machine may still be actionable even before the whole
        // chunk crosses the block score. This catches compact hidden machines.
        final boolean compactMachine = ratePerSecond >= singleBlockRateThreshold
                && blockHeat.window.binaryAlternations >= Math.max(4, clockTriggerCount / 2)
                && localWeight >= 10
                && weightedChunkEvents >= Math.max(chunkWarnScore, chunkBlockScore / 2);

        if (compactMachine) {
            score += 20.0D;
        }

        // Hard gates: confidence can only reach 100 when the signal structure itself is
        // sufficiently convincing. This is intentional; score alone is not the detector.
        final boolean hasClockShape = blockHeat.window.binaryAlternations >= Math.max(4, clockTriggerCount / 2)
                && blockHeat.window.rapidIntervals >= Math.max(3, clockTriggerCount / 3);
        final boolean hasMachineContext = localWeight >= 8 || weightedChunkEvents >= chunkBlockScore;

        if (!hasClockShape || !hasMachineContext) {
            return new DetectionResult(Math.min(score, 94.0D), false,
                    ratePerSecond, weightedChunkEvents, distinctHotBlocks);
        }

        final boolean hardConfirmed = areaAssisted || compactMachine;
        if (!hardConfirmed) {
            // Soft suspicion is intentionally capped below the action threshold.
            score = Math.min(score, 99.0D);
        } else {
            score = Math.max(score, 100.0D);
        }

        return new DetectionResult(Math.min(score, 100.0D), areaAssisted,
                ratePerSecond, weightedChunkEvents, distinctHotBlocks);
    }

    private long calculateFreezeNanos(BlockHeat state, boolean areaAssisted) {
        final int strike = Math.min(6, state.confirmationStrikes);
        long multiplier = 1L << strike;
        long duration = Math.min(120_000_000_000L, baseFreezeNanos * multiplier);
        if (areaAssisted) {
            duration = Math.min(120_000_000_000L, duration + baseFreezeNanos / 2L);
        }
        return duration;
    }

    private int inspectLocalMachine(Block block) {
        int total = 0;
        int machineryCount = 0;

        for (int i = 0; i < NEIGHBOUR_OFFSETS.length; i += 3) {
            final int dx = NEIGHBOUR_OFFSETS[i];
            final int dy = NEIGHBOUR_OFFSETS[i + 1];
            final int dz = NEIGHBOUR_OFFSETS[i + 2];
            final Material material = block.getRelative(dx, dy, dz).getType();
            final int weight = machineWeight(material);
            total += weight;
            if (weight >= 2) {
                machineryCount++;
            }
        }

        // Distinct machinery is more important than raw wire density.
        if (machineryCount >= 3) {
            total += 2;
        }
        if (machineryCount >= 6) {
            total += 3;
        }
        return Math.min(32, total);
    }

    // ---------------------------------------------------------------------------------
    // Maintenance / lifecycle
    // ---------------------------------------------------------------------------------

    private void startMaintenanceTask() {
        if (schedulerAdapter != null) {
            maintenanceTask = schedulerAdapter.runGlobalTimer(this::maintenance, 100L, 100L);
            return;
        }

        // Do not use Bukkit's classic scheduler on Folia. A Folia install can still use the
        // detector without a SchedulerAdapter; hard caps + lazy pruning keep it bounded.
        if (isFoliaRuntime()) {
            maintenanceTask = SchedulerAdapter.TaskHandle.EMPTY;
            return;
        }

        maintenanceTask = new BukkitTaskHandleAdapter(
                Bukkit.getScheduler().runTaskTimer(owner, this::maintenance, 100L, 100L));
    }

    private void maintenance() {
        if (shutdown.get()) {
            return;
        }

        final long now = System.nanoTime();
        for (Map.Entry<UUID, WorldState> entry : worlds.entrySet()) {
            final WorldState state = entry.getValue();
            state.prune(now);
            if (state.isEmpty()) {
                worlds.remove(entry.getKey(), state);
            }
        }
    }

    private void maybePruneLazy(WorldState state, long now) {
        if (state.blocks.size() <= MAX_TRACKED_BLOCKS_PER_WORLD
                && state.chunks.size() <= MAX_TRACKED_CHUNKS_PER_WORLD) {
            return;
        }
        state.pruneLimited(now, PRUNE_LIMIT_PER_WORLD);
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }

        final SchedulerAdapter.TaskHandle task = maintenanceTask;
        if (task != null && !task.isCancelled()) {
            try {
                task.cancel();
            } catch (Throwable ignored) {
                // Shutdown must remain fail-safe even if a third-party scheduler wrapper misbehaves.
            }
        }

        worlds.clear();
        exemptWorlds.clear();
        exemptBlocks.clear();
    }

    // ---------------------------------------------------------------------------------
    // Public inspection / admin API
    // ---------------------------------------------------------------------------------

    public boolean isSuspicious(Block block) {
        if (block == null) return false;
        final WorldState state = worlds.get(block.getWorld().getUID());
        if (state == null) return false;

        final ChunkHeat heat = state.chunks.get(packChunk(block.getChunk().getX(), block.getChunk().getZ()));
        if (heat == null) return false;
        return heat.weightedEvents(System.nanoTime()) >= chunkWarnScore;
    }

    public boolean isActiveClock(Block block) {
        if (block == null) return false;
        final WorldState state = worlds.get(block.getWorld().getUID());
        if (state == null) return false;

        final BlockHeat heat = state.blocks.get(packBlock(block.getX(), block.getY(), block.getZ()));
        return heat != null && heat.isFrozen(System.nanoTime());
    }

    /** Returns the most recent confidence score in the [0,100] range. */
    public double getConfidence(Block block) {
        if (block == null) return 0.0D;
        final WorldState state = worlds.get(block.getWorld().getUID());
        if (state == null) return 0.0D;
        final BlockHeat heat = state.blocks.get(packBlock(block.getX(), block.getY(), block.getZ()));
        return heat == null ? 0.0D : heat.confidence;
    }

    public void clearArea(Block block) {
        if (block == null) return;
        final WorldState state = worlds.get(block.getWorld().getUID());
        if (state == null) return;

        final long chunkKey = packChunk(block.getChunk().getX(), block.getChunk().getZ());
        state.chunks.remove(chunkKey);

        // Cold admin operation: remove all tracked blocks in this chunk.
        for (Map.Entry<Long, BlockHeat> entry : state.blocks.entrySet()) {
            final long packed = entry.getKey();
            if (chunkFromBlockKey(packed) == chunkKey) {
                state.blocks.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public void unfreeze(Block block) {
        if (block == null) return;
        final WorldState state = worlds.get(block.getWorld().getUID());
        if (state == null) return;
        final BlockHeat heat = state.blocks.get(packBlock(block.getX(), block.getY(), block.getZ()));
        if (heat != null) {
            heat.freezeUntilNanos = 0L;
            heat.confirmationStrikes = 0;
            heat.confidence *= 0.5D;
            heat.window.clear();
        }
    }

    public void exemptWorld(String worldName) {
        if (worldName != null && !worldName.isEmpty()) {
            exemptWorlds.add(worldName);
        }
    }

    public void unexemptWorld(String worldName) {
        if (worldName != null) {
            exemptWorlds.remove(worldName);
        }
    }

    public void exemptBlock(Block block) {
        if (block != null) {
            exemptBlocks.add(makeBlockKey(block.getWorld().getName(), block));
        }
    }

    public void unexemptBlock(Block block) {
        if (block != null) {
            exemptBlocks.remove(makeBlockKey(block.getWorld().getName(), block));
        }
    }

    public long getObservedTransitions() {
        return observedTransitions.sum();
    }

    public long getSuppressedTransitions() {
        return suppressedTransitions.sum();
    }

    public long getConfirmedMachines() {
        return confirmedMachines.sum();
    }

    public long getAreaAssistedSuppressions() {
        return areaAssistedSuppressions.sum();
    }

    public long getDetectorErrors() {
        return detectorErrors.sum();
    }

    public int getTrackedWorldCount() {
        return worlds.size();
    }

    public int getTrackedBlockCount() {
        int total = 0;
        for (WorldState state : worlds.values()) {
            total += state.blocks.size();
        }
        return total;
    }

    public int getTrackedChunkCount() {
        int total = 0;
        for (WorldState state : worlds.values()) {
            total += state.chunks.size();
        }
        return total;
    }

    private void logDetectorError(RuntimeException ex) {
        final long now = System.nanoTime();
        if (now - lastLogNanos < LOG_COOLDOWN_NANOS) {
            return;
        }
        lastLogNanos = now;
        owner.getLogger().warning("[NoLag][Redstone] detector failed open: "
                + ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
    }

    // ---------------------------------------------------------------------------------
    // Material classification
    // ---------------------------------------------------------------------------------

    /**
     * Returns a weighted contribution. The weight is intentionally higher for components
     * that can amplify work (pistons) or form clocks (repeaters/observers/comparators).
     */
    private static int machineWeight(Material material) {
        if (material == null) return 0;

        return switch (material) {
            case PISTON, STICKY_PISTON -> 5;
            case OBSERVER -> 4;
            case REPEATER, COMPARATOR -> 3;
            case REDSTONE_TORCH, REDSTONE_WALL_TORCH -> 3;
            case TARGET, REDSTONE_LAMP, NOTE_BLOCK -> 2;
            case DISPENSER, DROPPER -> 2;
            case REDSTONE_WIRE, REDSTONE_BLOCK, HOPPER -> 1;
            case POWERED_RAIL, ACTIVATOR_RAIL, DETECTOR_RAIL -> 1;
            default -> 0;
        };
    }

    private static boolean isSuppressibleComponent(Material material) {
        if (material == null) return false;
        final String name = material.name();

        // Player-operated inputs are useful chunk evidence, but directly suppressing them
        // is a high false-positive strategy.
        if ("LEVER".equals(name)
                || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE")
                || name.endsWith("_TRIPWIRE_HOOK")) {
            return false;
        }

        return machineWeight(material) > 0;
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    private void logSuppression(Block block, DetectionResult result, long freezeNanos) {
        final long now = System.nanoTime();
        if (now - lastLogNanos < LOG_COOLDOWN_NANOS) {
            return;
        }
        lastLogNanos = now;

        owner.getLogger().warning(
                "[NoLag][Redstone] suppressed high-confidence machine at "
                        + block.getWorld().getName() + ':'
                        + block.getX() + ':' + block.getY() + ':' + block.getZ()
                        + " confidence=" + Math.round(result.confidence)
                        + " blockRate=" + String.format(java.util.Locale.ROOT, "%.1f", result.blockRate)
                        + "/s chunkWeighted=" + result.chunkWeightedEvents
                        + " hotBlocks=" + result.hotBlocks
                        + " freeze=" + (freezeNanos / 1_000_000L) + "ms");
    }

    private static String makeBlockKey(String worldName, Block block) {
        return worldName + ':' + block.getX() + ':' + block.getY() + ':' + block.getZ();
    }

    private static long packBlock(int x, int y, int z) {
        // Minecraft's classic BlockPos packing: 26 bits X, 12 bits Y, 26 bits Z.
        return ((long) (x & 0x3FFFFFF) << 38)
                | ((long) (y & 0xFFF) << 26)
                | (z & 0x3FFFFFFL);
    }

    private static long chunkFromBlockKey(long packed) {
        final int x = (int) (packed >> 38);
        final int z = (int) ((packed << 26) >> 38);
        return packChunk(x >> 4, z >> 4);
    }

    private static long packChunk(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static long clampNanos(long value, long min, long max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isFoliaRuntime() {
        try {
            Bukkit.class.getMethod("getGlobalRegionScheduler");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int[] buildNeighbourOffsets() {
        final int[] offsets = new int[26 * 3];
        int cursor = 0;
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    offsets[cursor++] = x;
                    offsets[cursor++] = y;
                    offsets[cursor++] = z;
                }
            }
        }
        return offsets;
    }

    // ---------------------------------------------------------------------------------
    // State objects
    // ---------------------------------------------------------------------------------

    private static final class WorldState {
        final Map<Long, BlockHeat> blocks = new ConcurrentHashMap<>();
        final Map<Long, ChunkHeat> chunks = new ConcurrentHashMap<>();

        void prune(long now) {
            pruneLimited(now, PRUNE_LIMIT_PER_WORLD * 4);
        }

        void pruneLimited(long now, int limit) {
            int removed = 0;

            for (Map.Entry<Long, BlockHeat> entry : blocks.entrySet()) {
                if (removed >= limit) break;
                final BlockHeat heat = entry.getValue();
                if (!heat.isFrozen(now) && now - heat.lastSeenNanos > CLEANUP_QUIET_NANOS) {
                    if (blocks.remove(entry.getKey(), heat)) {
                        removed++;
                    }
                }
            }

            removed = 0;
            for (Map.Entry<Long, ChunkHeat> entry : chunks.entrySet()) {
                if (removed >= Math.max(32, limit / 4)) break;
                final ChunkHeat heat = entry.getValue();
                if (now - heat.lastEventNanos > CLEANUP_QUIET_NANOS) {
                    if (chunks.remove(entry.getKey(), heat)) {
                        removed++;
                    }
                }
            }
        }

        boolean isEmpty() {
            return blocks.isEmpty() && chunks.isEmpty();
        }
    }

    private static final class BlockHeat {
        final SlidingWindow window;

        volatile long lastSeenNanos;
        volatile long freezeUntilNanos;

        long nextEvaluationNanos;
        long nextProbeNanos;
        long lastScoreNanos;

        int sampleCount;
        int localMachineWeight;
        int confirmationStrikes;
        volatile double confidence;

        BlockHeat(int sampleCapacity) {
            this.window = new SlidingWindow(sampleCapacity);
        }

        void observe(long now, int oldCurrent, int newCurrent, long windowNanos) {
            this.window.add(now, newCurrent >= 8, Math.abs(newCurrent - oldCurrent) >= 8);
            this.window.expire(now, windowNanos);
            this.sampleCount = this.window.size;
            this.lastSeenNanos = now;
        }

        boolean isFrozen(long now) {
            return now < freezeUntilNanos;
        }
    }

    private static final class SlidingWindow {
        final long[] timestamps;
        final byte[] states;
        final byte[] amplitudes;
        int head;
        int size;

        // Reused analysis fields; no temporary object is allocated per evaluation.
        int rapidIntervals;
        int binaryAlternations;
        int largeTransitions;

        SlidingWindow(int capacity) {
            timestamps = new long[Math.max(16, capacity)];
            states = new byte[timestamps.length];
            amplitudes = new byte[timestamps.length];
        }

        void add(long now, boolean powered, boolean largeTransition) {
            int tail = (head + size) % timestamps.length;
            if (size == timestamps.length) {
                head = (head + 1) % timestamps.length;
                size--;
            }

            timestamps[tail] = now;
            states[tail] = (byte) (powered ? 1 : 0);
            amplitudes[tail] = (byte) (largeTransition ? 1 : 0);
            size++;
        }

        void expire(long now, long windowNanos) {
            while (size > 0 && now - timestamps[head] > windowNanos) {
                head = (head + 1) % timestamps.length;
                size--;
            }
        }

        void updateStats(long now, long windowNanos) {
            expire(now, windowNanos);

            rapidIntervals = 0;
            binaryAlternations = 0;
            largeTransitions = 0;

            if (size == 0) {
                return;
            }

            int previousIndex = head;
            byte previousState = states[previousIndex];
            largeTransitions = amplitudes[previousIndex];

            for (int i = 1; i < size; i++) {
                int index = (head + i) % timestamps.length;
                long dt = timestamps[index] - timestamps[previousIndex];

                if (dt <= RAPID_INTERVAL_NANOS) {
                    rapidIntervals++;
                }
                if (states[index] != previousState) {
                    binaryAlternations++;
                }
                largeTransitions += amplitudes[index];

                previousState = states[index];
                previousIndex = index;
            }
        }

        void clear() {
            head = 0;
            size = 0;
            rapidIntervals = 0;
            binaryAlternations = 0;
            largeTransitions = 0;
        }
    }

    private static final class ChunkHeat {
        final int[] totalBuckets = new int[BUCKET_COUNT];
        final int[] weightedBuckets = new int[BUCKET_COUNT];
        final Set<Long> hotBlocks = new HashSet<>(16);

        long bucketStartNanos = Long.MIN_VALUE;
        long hotBlockWindowStartNanos = Long.MIN_VALUE;
        long lastEventNanos;
        long lastHeatUpdateNanos = Long.MIN_VALUE;

        int cursor;
        int totalEvents;
        int weightedEvents;
        double sustainedHeat;

        void record(long now, int machineWeight) {
            rotate(now);

            totalBuckets[cursor]++;
            totalEvents++;

            int weight = Math.max(0, machineWeight);
            if (weight > 0) {
                weightedBuckets[cursor] += weight;
                weightedEvents += weight;
                sustainedHeat = Math.min(100_000.0D, sustainedHeat + weight);
            }

            lastEventNanos = now;
        }

        void markHotBlock(long now, long blockKey) {
            if (hotBlockWindowStartNanos == Long.MIN_VALUE
                    || now - hotBlockWindowStartNanos >= DISTINCT_BLOCK_WINDOW_NANOS) {
                hotBlocks.clear();
                hotBlockWindowStartNanos = now;
            }

            if (hotBlocks.size() < 256) {
                hotBlocks.add(blockKey);
            }
        }

        int distinctHotBlockCount(long now) {
            if (hotBlockWindowStartNanos != Long.MIN_VALUE
                    && now - hotBlockWindowStartNanos >= DISTINCT_BLOCK_WINDOW_NANOS) {
                hotBlocks.clear();
                hotBlockWindowStartNanos = now;
            }
            return hotBlocks.size();
        }

        int weightedEvents(long now) {
            rotate(now);
            return weightedEvents;
        }

        int totalEvents(long now) {
            rotate(now);
            return totalEvents;
        }

        double sustainedHeat(long now, long halfLifeNanos) {
            if (lastHeatUpdateNanos == Long.MIN_VALUE) {
                lastHeatUpdateNanos = now;
                return sustainedHeat;
            }

            long elapsed = now - lastHeatUpdateNanos;
            if (elapsed <= 0L) {
                return sustainedHeat;
            }

            double factor = Math.pow(0.5D, elapsed / (double) halfLifeNanos);
            sustainedHeat *= factor;
            lastHeatUpdateNanos = now;
            return sustainedHeat;
        }

        private void rotate(long now) {
            if (bucketStartNanos == Long.MIN_VALUE) {
                bucketStartNanos = now;
                cursor = 0;
                return;
            }

            long elapsed = now - bucketStartNanos;
            if (elapsed < BUCKET_NANOS) {
                return;
            }

            long steps = elapsed / BUCKET_NANOS;
            if (steps >= BUCKET_COUNT) {
                for (int i = 0; i < BUCKET_COUNT; i++) {
                    totalBuckets[i] = 0;
                    weightedBuckets[i] = 0;
                }
                totalEvents = 0;
                weightedEvents = 0;
                cursor = 0;
                bucketStartNanos = now;
                return;
            }

            for (int i = 0; i < steps; i++) {
                cursor = (cursor + 1) % BUCKET_COUNT;
                totalEvents -= totalBuckets[cursor];
                weightedEvents -= weightedBuckets[cursor];
                totalBuckets[cursor] = 0;
                weightedBuckets[cursor] = 0;
            }

            bucketStartNanos += steps * BUCKET_NANOS;
        }
    }

    private record DetectionResult(double confidence,
                                   boolean areaAssisted,
                                   double blockRate,
                                   int chunkWeightedEvents,
                                   int hotBlocks) {
        static final DetectionResult NONE = new DetectionResult(0.0D, false, 0.0D, 0, 0);
    }

    // ---------------------------------------------------------------------------------
    // Bukkit fallback task handle
    // ---------------------------------------------------------------------------------

    private static final class BukkitTaskHandleAdapter implements SchedulerAdapter.TaskHandle {
        private final org.bukkit.scheduler.BukkitTask task;

        BukkitTaskHandleAdapter(org.bukkit.scheduler.BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return task == null || task.isCancelled();
        }
    }
}
