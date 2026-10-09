package me.nolag.modules.flood;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Fluid flood and machine protection for NoLag-Ultimate 21.6.0.
 *
 * The hot fluid-flow path is intentionally kept allocation-light. It only performs
 * work after a world has become "hot" due to a recent fluid source, dispenser pulse,
 * or piston-machine signal. Deep block inspection is reserved for piston/dispenser
 * events and confirmed incidents, rather than every fluid update.
 *
 * A machine is only broken after multiple independent signals agree:
 * mechanical carriage + repeated piston activity + fluid payload/source evidence,
 * with optional flow-burst and movement evidence adding confidence. This is designed
 * to protect against large self-extending flood machines while avoiding broad scans
 * or blanket cancellation of normal water/lava builds.
 */
public final class FluidFloodProtectionModule implements Listener {

    private static final long MAINTENANCE_TICKS = 100L;
    private static final long NANO_PER_MILLI = 1_000_000L;

    private static final Set<Material> FLUIDS = EnumSet.of(Material.WATER, Material.LAVA);
    private static final Set<Material> ICE_MATERIALS = EnumSet.of(
            Material.ICE,
            Material.PACKED_ICE,
            Material.BLUE_ICE,
            Material.FROSTED_ICE
    );
    private static final Set<Material> CARRIAGE_BLOCKS = EnumSet.of(
            Material.SLIME_BLOCK,
            Material.HONEY_BLOCK
    );
    private static final Set<Material> CORE_COMPONENTS = EnumSet.of(
            Material.PISTON,
            Material.STICKY_PISTON,
            Material.OBSERVER,
            Material.DISPENSER,
            Material.DROPPER,
            Material.SLIME_BLOCK,
            Material.HONEY_BLOCK,
            Material.REDSTONE_BLOCK,
            Material.REPEATER,
            Material.COMPARATOR
    );
    private static final Set<Material> REDSTONE_COMPONENTS = EnumSet.of(
            Material.REDSTONE_BLOCK,
            Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH,
            Material.REPEATER,
            Material.COMPARATOR,
            Material.OBSERVER
    );

    private final NoLag plugin;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Map<UUID, WorldState> worlds = new HashMap<>();

    private final TaskHandle maintenanceTask;
    private volatile Settings settings;
    private volatile boolean runtimeDisabled;

    public FluidFloodProtectionModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        this.maintenanceTask = plugin.getSchedulerAdapter().isFolia()
                ? null
                : plugin.getSchedulerAdapter().runGlobalTimer(this::maintenance, 20L, MAINTENANCE_TICKS);
    }

    public void reload() {
        Settings next = Settings.load(plugin);
        this.settings = next;
        this.runtimeDisabled = plugin.getSchedulerAdapter().isFolia();

        if (runtimeDisabled && next.enabled) {
            plugin.getLogger().warning("[NoLag] FluidFloodProtection is disabled on Folia because its flood correlation state is global.");
        }

        for (PlayerState playerState : players.values()) {
            playerState.trim(next.nowNanos());
        }
    }

    public void stop() {
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
        }
        players.clear();
        worlds.clear();
    }

    public void trimCaches() {
        maintenance();
    }

    /**
     * Ultra-cheap cancellation path for chunks currently under an emergency fluid
     * throttle. This executes before any burst bookkeeping.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Settings cfg = settings;
        if (runtimeDisabled || !cfg.enabled || !cfg.protectFlow) {
            return;
        }

        Block source = event.getBlock();
        Material sourceType = source.getType();
        if (!FLUIDS.contains(sourceType)) {
            return;
        }

        World world = source.getWorld();
        WorldState state = worlds.get(world.getUID());
        if (state == null) {
            return;
        }

        long now = System.nanoTime();
        Block target = event.getToBlock();
        int targetChunkX = target.getX() >> 4;
        int targetChunkZ = target.getZ() >> 4;
        int sourceChunkX = source.getX() >> 4;
        int sourceChunkZ = source.getZ() >> 4;

        if (state.isEmergency(sourceChunkX, sourceChunkZ, targetChunkX, targetChunkZ, now)) {
            event.setCancelled(true);
            return;
        }

        if (!state.isWatched(sourceChunkX, sourceChunkZ, targetChunkX, targetChunkZ, now)) {
            return;
        }

        long cellKey = packChunk(targetChunkX, targetChunkZ);
        FlowCell cell = state.getFlowCell(cellKey, cfg.maxFlowCellsPerWorld);
        if (cell == null) {
            return;
        }

        cell.record(now, sourceType == Material.LAVA, packBlock(target.getX(), target.getY(), target.getZ()));
        if (now < cell.nextAnalysisNanos) {
            return;
        }

        cell.nextAnalysisNanos = now + cfg.flowAnalysisIntervalNanos;
        int burst = cell.burstCount(now, cfg.flowWindowNanos);
        if (burst < cfg.flowBurstThreshold) {
            return;
        }

        MachineTrack machine = state.findNearestMachine(target.getX(), target.getY(), target.getZ(), cfg.machineTrackRadiusBlocks, now, cfg.machineTrackTtlNanos);
        if (machine != null) {
            machine.noteFlowBurst(burst, sourceType == Material.LAVA);
            if (machine.isBreakable(cfg)) {
                event.setCancelled(true);
                neutralizeMachine(world, state, machine, target, now, "confirmed fluid flow burst");
            }
            return;
        }

        // A player-driven flood without a mechanical machine can still be throttled,
        // but only after a high source count and a sustained flow burst agree.
        if (burst >= cfg.criticalFlowBurstThreshold
                && cell.sourceSignals(now, cfg.sourceWindowNanos) >= cfg.minimumSourceSignals) {
            state.addEmergency(targetChunkX, targetChunkZ, cfg.emergencyChunkRadius, now + cfg.emergencyThrottleNanos);
            event.setCancelled(true);
        }
    }

    /** Detects high-confidence moving piston/carriage flood machines. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlock(), event.getDirection(), event.getBlocks(), event, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlock(), event.getDirection(), event.getBlocks(), event, true);
    }

    private void handlePiston(Block piston, BlockFace direction, List<Block> movedBlocks,
                              org.bukkit.event.Cancellable event, boolean retracting) {
        Settings cfg = settings;
        if (runtimeDisabled || !cfg.enabled || !cfg.protectFlyingMachineFlood) {
            return;
        }

        Material pistonType = piston.getType();
        boolean stickyPiston = pistonType == Material.STICKY_PISTON;
        boolean carriage = containsCarriage(movedBlocks);
        if (!stickyPiston && !carriage) {
            return;
        }

        long now = System.nanoTime();
        World world = piston.getWorld();
        WorldState state = worlds.computeIfAbsent(world.getUID(), ignored -> new WorldState(world.getUID()));
        state.addWatch(piston.getX() >> 4, piston.getZ() >> 4, 1, now + cfg.watchWindowNanos);

        MachineSignature signature = quickInspectMachine(piston, movedBlocks);
        if (!signature.isMachineLike()) {
            return;
        }

        MachineTrack track = state.findOrCreateTrack(piston, now, cfg.machineTrackRadiusBlocks, cfg.maxMachineTracks);
        if (track.shouldInspect(now)) {
            signature = inspectMachine(piston, direction, movedBlocks, cfg.machineInspectionRadius);
            track.nextInspectionNanos = now + cfg.machineInspectionCooldownNanos;
            if (!signature.isMachineLike()) {
                return;
            }
        }
        track.observe(piston, direction, retracting, signature, now, cfg.machinePulseWindowNanos);

        if (track.isBreakable(cfg)) {
            event.setCancelled(true);
            neutralizeMachine(world, state, track, piston, now, "high-confidence moving fluid machine");
        }
    }

    /** Detects rapid dispenser-based fluid clocks without scanning the world per pulse. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent event) {
        Settings cfg = settings;
        if (runtimeDisabled || !cfg.enabled || !cfg.protectDispenserFlood) {
            return;
        }

        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        Material type = item.getType();
        if (type != Material.WATER_BUCKET && type != Material.LAVA_BUCKET) {
            return;
        }

        Block dispenser = event.getBlock();
        long now = System.nanoTime();
        World world = dispenser.getWorld();
        WorldState state = worlds.computeIfAbsent(world.getUID(), ignored -> new WorldState(world.getUID()));
        state.addWatch(dispenser.getX() >> 4, dispenser.getZ() >> 4, 1, now + cfg.watchWindowNanos);

        long key = packBlock(dispenser.getX(), dispenser.getY(), dispenser.getZ());
        DispenserTrack tracker = state.dispenserTracks.computeIfAbsent(key, ignored -> new DispenserTrack());
        tracker.record(now, type == Material.LAVA_BUCKET, cfg.dispenserWindowNanos);

        if (tracker.pulses(now, cfg.dispenserWindowNanos) < cfg.dispenserMaxPulses) {
            return;
        }

        MachineSignature signature = inspectLocalMachine(dispenser, cfg.machineInspectionRadius);
        if (!signature.dispenserOrDropper || signature.redstoneSignals < cfg.minimumDispenserRedstoneSignals) {
            return;
        }

        event.setCancelled(true);
        state.addEmergency(dispenser.getX() >> 4, dispenser.getZ() >> 4,
                cfg.emergencyChunkRadius, now + cfg.emergencyThrottleNanos);
        breakMachineCore(dispenser, cfg.machineComponentRadius, cfg.machineComponentLimit);
        clearRecentFluid(state, dispenser, cfg.machineClearRadius);
        tracker.lastTriggeredNanos = now;

        if (cfg.logViolations) {
            plugin.getLogger().warning("[FloodProtection] Blocked a rapid dispenser fluid clock at "
                    + world.getName() + " (" + dispenser.getX() + ", " + dispenser.getY() + ", " + dispenser.getZ() + ")");
        }
    }

    /** Tracks player-created water/lava source bursts. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Settings cfg = settings;
        if (runtimeDisabled || !cfg.enabled) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission(cfg.bypassPermission)) {
            return;
        }

        Material bucket = event.getBucket();
        if (bucket != Material.WATER_BUCKET && bucket != Material.LAVA_BUCKET) {
            return;
        }

        Block target = event.getBlockClicked();
        BlockFace face = event.getBlockFace();
        if (target == null || face == null) {
            return;
        }
        Block placement = target.getRelative(face);
        if (!sameWorld(player.getWorld(), placement.getWorld())) {
            return;
        }

        long now = System.nanoTime();
        PlayerState playerState = players.computeIfAbsent(player.getUniqueId(),
                ignored -> new PlayerState(player.getUniqueId()));
        playerState.touch(now, placement, bucket == Material.LAVA_BUCKET);

        WorldState worldState = worlds.computeIfAbsent(placement.getWorld().getUID(),
                ignored -> new WorldState(placement.getWorld().getUID()));
        worldState.addWatch(placement.getX() >> 4, placement.getZ() >> 4, 1, now + cfg.watchWindowNanos);
        FlowCell sourceCell = worldState.getFlowCell(
                packChunk(placement.getX() >> 4, placement.getZ() >> 4), cfg.maxFlowCellsPerWorld);
        if (sourceCell != null) {
            sourceCell.sourceCounter.record(now, 1);
            sourceCell.recordSourceBlock(packBlock(placement.getX(), placement.getY(), placement.getZ()));
        }

        int sources = playerState.sources(now, cfg.sourceWindowNanos);
        if (sources < cfg.playerSourceBurstThreshold) {
            return;
        }

        MachineTrack machine = worldState.findNearestMachine(
                placement.getX(), placement.getY(), placement.getZ(),
                cfg.machineTrackRadiusBlocks, now, cfg.machineTrackTtlNanos
        );
        if (machine != null && machine.hasFluidEvidence) {
            machine.ownerId = player.getUniqueId();
            machine.notePlayerSourceBurst(sources, now);
            if (machine.isBreakable(cfg)) {
                event.setCancelled(true);
                neutralizeMachine(placement.getWorld(), worldState, machine, placement, now, "confirmed player-driven flood machine");
            }
            return;
        }

        if (playerState.compactSources(cfg.sourceCompactRadiusBlocks) >= cfg.compactSourceThreshold
                && sources >= cfg.criticalSourceBurstThreshold) {
            event.setCancelled(true);
            worldState.addEmergency(placement.getX() >> 4, placement.getZ() >> 4,
                    cfg.emergencyChunkRadius, now + cfg.emergencyThrottleNanos);
            clearRecentFluid(worldState, placement, cfg.machineClearRadius);
            sendMessage(player, cfg, "violation", "&eExcessive fluid source placement was blocked.");
        }
    }

    /** Tracks ice payloads; actual neutralization still requires a machine signal. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIcePlace(BlockPlaceEvent event) {
        Settings cfg = settings;
        if (runtimeDisabled || !cfg.enabled || !cfg.protectIce) {
            return;
        }

        Block block = event.getBlockPlaced();
        if (!ICE_MATERIALS.contains(block.getType())) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission(cfg.bypassPermission)) {
            return;
        }

        long now = System.nanoTime();
        WorldState state = worlds.computeIfAbsent(block.getWorld().getUID(), ignored -> new WorldState(block.getWorld().getUID()));
        state.addWatch(block.getX() >> 4, block.getZ() >> 4, 1, now + cfg.watchWindowNanos);
        FlowCell cell = state.getFlowCell(packChunk(block.getX() >> 4, block.getZ() >> 4), cfg.maxFlowCellsPerWorld);
        if (cell != null) {
            cell.sourceCounter.record(now, 1);
            cell.recordSourceBlock(packBlock(block.getX(), block.getY(), block.getZ()));
        }

        MachineTrack machine = state.findNearestMachine(block.getX(), block.getY(), block.getZ(),
                cfg.machineTrackRadiusBlocks, now, cfg.machineTrackTtlNanos);
        if (machine == null) {
            return;
        }

        machine.ownerId = player.getUniqueId();
        machine.noteIceSource(now);
        if (machine.isBreakable(cfg)) {
            event.setCancelled(true);
            neutralizeMachine(block.getWorld(), state, machine, block, now, "confirmed ice flood machine");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
    }

    private void neutralizeMachine(World world, WorldState state, MachineTrack machine,
                                   Block center, long now, String reason) {
        Settings cfg = settings;
        machine.lastNeutralizedNanos = now;
        machine.breakTriggered = true;

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        state.addEmergency(centerChunkX, centerChunkZ, cfg.emergencyChunkRadius,
                now + cfg.emergencyThrottleNanos);

        if (cfg.breakMachineComponents) {
            breakMachineCore(center, cfg.machineComponentRadius, cfg.machineComponentLimit);
        }
        if (cfg.neutralizeFluid) {
            clearRecentFluid(state, center, cfg.machineClearRadius);
        }
        if (cfg.neutralizeIce) {
            clearRecentIce(state, center, cfg.machineClearRadius);
        }

        sendMessageByTrack(machine, cfg, "machine-neutralized", "&cA suspicious fluid machine was neutralized.");

        if (cfg.logViolations) {
            plugin.getLogger().warning("[FloodProtection] Neutralized " + reason
                    + " | world=" + world.getName()
                    + " | x=" + center.getX()
                    + " y=" + center.getY()
                    + " z=" + center.getZ()
                    + " | confidence=" + machine.confidence(cfg));
        }
    }

    private void clearRecentFluid(WorldState state, Block center, int radius) {
        int r = Math.max(1, radius);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        state.flowCells.forEachCell(cell -> {
            int cellX = (int) (cell.chunkKey >> 32);
            int cellZ = (int) cell.chunkKey;
            if (Math.abs(cellX - centerChunkX) > 1 || Math.abs(cellZ - centerChunkZ) > 1) {
                return;
            }
            cell.recentTargetsForEach(packed -> {
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                if (Math.abs(x - center.getX()) > r
                        || Math.abs(y - center.getY()) > r
                        || Math.abs(z - center.getZ()) > r) {
                    return;
                }
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return;
                }
                Block block = world.getBlockAt(x, y, z);
                if (FLUIDS.contains(block.getType())) {
                    block.setType(Material.AIR, false);
                }
            });
        });

        for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
            for (int y = center.getY() - 1; y <= center.getY() + 1; y++) {
                for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (FLUIDS.contains(block.getType())) {
                        block.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void clearRecentIce(WorldState state, Block center, int radius) {
        World world = center.getWorld();
        if (world == null) {
            return;
        }
        int r = Math.max(1, radius);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        state.flowCells.forEachCell(cell -> {
            int cellX = (int) (cell.chunkKey >> 32);
            int cellZ = (int) cell.chunkKey;
            if (Math.abs(cellX - centerChunkX) > 1 || Math.abs(cellZ - centerChunkZ) > 1) {
                return;
            }
            cell.recentSourcesForEach(packed -> {
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                if (Math.abs(x - center.getX()) > r
                        || Math.abs(y - center.getY()) > r
                        || Math.abs(z - center.getZ()) > r) {
                    return;
                }
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return;
                }
                Block block = world.getBlockAt(x, y, z);
                if (ICE_MATERIALS.contains(block.getType())) {
                    block.setType(Material.AIR, false);
                }
            });
        });
    }

    /**
     * Removes only mechanical core blocks. It deliberately avoids broad redstone,
     * hoppers, rails and ordinary structure blocks to reduce collateral damage.
     */
    private int breakMachineCore(Block center, int radius, int limit) {
        if (radius <= 0 || limit <= 0) {
            return 0;
        }

        World world = center.getWorld();
        if (world == null) {
            return 0;
        }

        int broken = 0;
        List<BlockCandidate> candidates = new ArrayList<>(24);
        int minY = Math.max(world.getMinHeight(), center.getY() - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, center.getY() + radius);

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (!CORE_COMPONENTS.contains(type)) {
                        continue;
                    }
                    int priority = componentPriority(type);
                    int dx = x - center.getX();
                    int dy = y - center.getY();
                    int dz = z - center.getZ();
                    double distanceSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    candidates.add(new BlockCandidate(block, priority, distanceSq));
                }
            }
        }

        candidates.sort((a, b) -> {
            int priority = Integer.compare(b.priority, a.priority);
            if (priority != 0) {
                return priority;
            }
            return Double.compare(a.distanceSquared, b.distanceSquared);
        });

        for (BlockCandidate candidate : candidates) {
            if (broken >= limit) {
                break;
            }
            Block block = candidate.block;
            if (!world.isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }
            if (CORE_COMPONENTS.contains(block.getType())) {
                block.setType(Material.AIR, false);
                broken++;
            }
        }
        return broken;
    }

    private MachineSignature quickInspectMachine(Block piston, List<Block> movedBlocks) {
        boolean sticky = piston.getType() == Material.STICKY_PISTON;
        boolean slime = containsCarriage(movedBlocks);
        boolean fluid = false;
        boolean ice = false;
        boolean dispenser = false;
        boolean observer = false;
        int redstone = 0;

        for (Block block : movedBlocks) {
            Material type = block.getType();
            slime |= CARRIAGE_BLOCKS.contains(type);
            fluid |= FLUIDS.contains(type);
            ice |= ICE_MATERIALS.contains(type);
            dispenser |= type == Material.DISPENSER || type == Material.DROPPER;
            observer |= type == Material.OBSERVER;
            if (REDSTONE_COMPONENTS.contains(type)) redstone++;
        }

        if (sticky || slime) {
            for (BlockFace face : BlockFace.values()) {
                if (face == BlockFace.SELF) continue;
                Material type = piston.getRelative(face).getType();
                slime |= CARRIAGE_BLOCKS.contains(type);
                fluid |= FLUIDS.contains(type);
                ice |= ICE_MATERIALS.contains(type);
                dispenser |= type == Material.DISPENSER || type == Material.DROPPER;
                observer |= type == Material.OBSERVER;
                if (REDSTONE_COMPONENTS.contains(type)) redstone++;
            }
        }

        return new MachineSignature(sticky, slime, fluid, ice, dispenser, observer, redstone,
                piston.getY() >= settings.verticalDuplicationY);
    }

    private MachineSignature inspectMachine(Block piston, BlockFace direction, List<Block> movedBlocks, int radius) {
        boolean slimeOrHoney = containsCarriage(movedBlocks);
        boolean fluid = false;
        boolean ice = false;
        boolean dispenser = false;
        boolean observer = false;
        int redstoneSignals = 0;

        for (Block block : movedBlocks) {
            Material type = block.getType();
            slimeOrHoney |= CARRIAGE_BLOCKS.contains(type);
            fluid |= FLUIDS.contains(type);
            ice |= ICE_MATERIALS.contains(type);
            dispenser |= type == Material.DISPENSER || type == Material.DROPPER;
            observer |= type == Material.OBSERVER;
            if (REDSTONE_COMPONENTS.contains(type)) {
                redstoneSignals++;
            }
        }

        if (radius > 0) {
            int minY = Math.max(piston.getWorld().getMinHeight(), piston.getY() - radius);
            int maxY = Math.min(piston.getWorld().getMaxHeight() - 1, piston.getY() + radius);
            for (int x = piston.getX() - radius; x <= piston.getX() + radius; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = piston.getZ() - radius; z <= piston.getZ() + radius; z++) {
                        Material type = piston.getWorld().getBlockAt(x, y, z).getType();
                        slimeOrHoney |= CARRIAGE_BLOCKS.contains(type);
                        fluid |= FLUIDS.contains(type);
                        ice |= ICE_MATERIALS.contains(type);
                        dispenser |= type == Material.DISPENSER || type == Material.DROPPER;
                        observer |= type == Material.OBSERVER;
                        if (REDSTONE_COMPONENTS.contains(type)) {
                            redstoneSignals++;
                        }
                    }
                }
            }
        }

        // Check a short payload line ahead of the piston even when the full signature
        // is found in a wider neighborhood. This favors actual carriage direction.
        if (direction != null) {
            Block cursor = piston;
            for (int i = 0; i < Math.max(2, radius + 1); i++) {
                cursor = cursor.getRelative(direction);
                Material type = cursor.getType();
                fluid |= FLUIDS.contains(type);
                ice |= ICE_MATERIALS.contains(type);
            }
        }

        boolean highY = piston.getY() >= settings.verticalDuplicationY;
        return new MachineSignature(stickyPistonNearby(piston), slimeOrHoney, fluid, ice,
                dispenser, observer, redstoneSignals, highY);
    }

    private MachineSignature inspectLocalMachine(Block center, int radius) {
        boolean fluid = false;
        boolean ice = false;
        boolean dispenser = false;
        boolean observer = false;
        boolean slimeOrHoney = false;
        boolean sticky = false;
        int redstoneSignals = 0;

        int minY = Math.max(center.getWorld().getMinHeight(), center.getY() - radius);
        int maxY = Math.min(center.getWorld().getMaxHeight() - 1, center.getY() + radius);
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    Material type = center.getWorld().getBlockAt(x, y, z).getType();
                    fluid |= FLUIDS.contains(type);
                    ice |= ICE_MATERIALS.contains(type);
                    dispenser |= type == Material.DISPENSER || type == Material.DROPPER;
                    observer |= type == Material.OBSERVER;
                    sticky |= type == Material.STICKY_PISTON;
                    slimeOrHoney |= CARRIAGE_BLOCKS.contains(type);
                    if (REDSTONE_COMPONENTS.contains(type)) {
                        redstoneSignals++;
                    }
                }
            }
        }
        return new MachineSignature(sticky, slimeOrHoney, fluid, ice, dispenser, observer, redstoneSignals,
                center.getY() >= settings.verticalDuplicationY);
    }

    private boolean stickyPistonNearby(Block center) {
        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.SELF) {
                continue;
            }
            if (center.getRelative(face).getType() == Material.STICKY_PISTON) {
                return true;
            }
        }
        return center.getType() == Material.STICKY_PISTON;
    }

    private static boolean containsCarriage(List<Block> blocks) {
        for (Block block : blocks) {
            if (CARRIAGE_BLOCKS.contains(block.getType())) {
                return true;
            }
        }
        return false;
    }

    private void maintenance() {
        if (runtimeDisabled) {
            return;
        }
        Settings cfg = settings;
        long now = System.nanoTime();

        Iterator<Map.Entry<UUID, PlayerState>> playersIterator = players.entrySet().iterator();
        while (playersIterator.hasNext()) {
            PlayerState state = playersIterator.next().getValue();
            state.trim(now);
            if (now - state.lastActionNanos > cfg.playerStateTtlNanos) {
                playersIterator.remove();
            }
        }

        Iterator<Map.Entry<UUID, WorldState>> worldIterator = worlds.entrySet().iterator();
        while (worldIterator.hasNext()) {
            WorldState state = worldIterator.next().getValue();
            state.trim(now, cfg);
            if (state.isEmpty()) {
                worldIterator.remove();
            }
        }
    }

    private void sendMessage(Player player, Settings cfg, String key, String fallback) {
        if (player == null || !cfg.playerMessages) {
            return;
        }
        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
        String message = plugin.getConfig().getString("messages.flood." + key, fallback);
        player.sendMessage(plugin.color(prefix + message));
    }

    private void sendMessageByTrack(MachineTrack machine, Settings cfg, String key, String fallback) {
        if (!cfg.playerMessages || machine.ownerId == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(machine.ownerId);
        if (player != null) {
            sendMessage(player, cfg, key, fallback);
        }
    }

    private static boolean sameWorld(World a, World b) {
        return a != null && b != null && a.getUID().equals(b.getUID());
    }

    private static int componentPriority(Material type) {
        return switch (type) {
            case OBSERVER -> 6;
            case STICKY_PISTON -> 6;
            case PISTON -> 5;
            case DISPENSER, DROPPER -> 5;
            case SLIME_BLOCK, HONEY_BLOCK -> 4;
            case REDSTONE_BLOCK -> 4;
            case REPEATER, COMPARATOR -> 3;
            default -> 1;
        };
    }

    private static long packChunk(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static long packBlock(int x, int y, int z) {
        long px = ((long) x & 0x3FFFFFFL) << 38;
        long pz = ((long) z & 0x3FFFFFFL) << 12;
        long py = y & 0xFFFL;
        return px | pz | py;
    }

    private static int unpackX(long packed) {
        int x = (int) (packed >> 38);
        return x << 6 >> 6;
    }

    private static int unpackY(long packed) {
        return (int) (packed & 0xFFFL);
    }

    private static int unpackZ(long packed) {
        int z = (int) ((packed >> 12) & 0x3FFFFFFL);
        return z << 6 >> 6;
    }

    @FunctionalInterface
    private interface TargetConsumer {
        void accept(long packed);
    }

    @FunctionalInterface
    private interface FlowCellConsumer {
        void accept(FlowCell cell);
    }

    /**
     * Small primitive long -> FlowCell table used by the hottest event path.
     * It avoids Long boxing and keeps lookups allocation-free under flood load.
     */
    private static final class FlowCellTable {
        final long[] keys;
        final FlowCell[] values;
        final boolean[] used;
        final int mask;
        int size;

        FlowCellTable(int requestedCapacity) {
            int capacity = 1;
            int target = Math.max(32, requestedCapacity * 2);
            while (capacity < target) {
                capacity <<= 1;
            }
            this.keys = new long[capacity];
            this.values = new FlowCell[capacity];
            this.used = new boolean[capacity];
            this.mask = capacity - 1;
        }

        FlowCell getOrCreate(long key, int maxEntries) {
            int slot = findSlot(key);
            if (used[slot]) {
                return values[slot];
            }
            if (size >= Math.min(maxEntries, mask)) {
                evictOldest();
                slot = findSlot(key);
                if (used[slot]) {
                    return values[slot];
                }
            }
            FlowCell cell = new FlowCell(key);
            used[slot] = true;
            keys[slot] = key;
            values[slot] = cell;
            size++;
            return cell;
        }

        void forEachCell(FlowCellConsumer consumer) {
            for (int i = 0; i < values.length; i++) {
                if (used[i]) {
                    consumer.accept(values[i]);
                }
            }
        }

        void removeAt(int index) {
            if (!used[index]) return;
            used[index] = false;
            values[index] = null;
            size--;
            int next = (index + 1) & mask;
            while (used[next]) {
                long key = keys[next];
                FlowCell value = values[next];
                used[next] = false;
                values[next] = null;
                size--;
                int destination = findSlot(key);
                used[destination] = true;
                keys[destination] = key;
                values[destination] = value;
                size++;
                next = (next + 1) & mask;
            }
        }

        void clear() {
            java.util.Arrays.fill(used, false);
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        private void evictOldest() {
            int oldestIndex = -1;
            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < values.length; i++) {
                if (used[i] && values[i].lastEventNanos < oldest) {
                    oldest = values[i].lastEventNanos;
                    oldestIndex = i;
                }
            }
            if (oldestIndex >= 0) {
                removeAt(oldestIndex);
            }
        }

        private int findSlot(long key) {
            int index = mix64ToInt(key) & mask;
            while (used[index] && keys[index] != key) {
                index = (index + 1) & mask;
            }
            return index;
        }

        private static int mix64ToInt(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }

    private static final class WorldState {
        final UUID worldId;
        final FlowCellTable flowCells = new FlowCellTable(256);
        final Map<Long, DispenserTrack> dispenserTracks = new HashMap<>(8);
        final List<MachineTrack> machineTracks = new ArrayList<>(8);
        final EmergencyZone[] emergencies = new EmergencyZone[8];
        final WatchZone[] watches = new WatchZone[12];

        WorldState(UUID worldId) {
            this.worldId = worldId;
        }

        FlowCell getFlowCell(long key, int maxCells) {
            return flowCells.getOrCreate(key, maxCells);
        }

        MachineTrack findOrCreateTrack(Block piston, long now, int radiusBlocks, int maxTracks) {
            MachineTrack best = null;
            double bestDistance = Double.MAX_VALUE;
            for (MachineTrack track : machineTracks) {
                if (now - track.lastPulseNanos > 3_000L * NANO_PER_MILLI) {
                    continue;
                }
                double distance = track.distanceSquared(piston.getX(), piston.getY(), piston.getZ());
                if (distance <= (double) radiusBlocks * radiusBlocks && distance < bestDistance) {
                    bestDistance = distance;
                    best = track;
                }
            }
            if (best != null) {
                return best;
            }

            if (machineTracks.size() >= maxTracks) {
                MachineTrack oldest = machineTracks.get(0);
                for (MachineTrack track : machineTracks) {
                    if (track.lastPulseNanos < oldest.lastPulseNanos) {
                        oldest = track;
                    }
                }
                machineTracks.remove(oldest);
            }

            MachineTrack track = new MachineTrack(piston.getX(), piston.getY(), piston.getZ(), now);
            machineTracks.add(track);
            return track;
        }

        MachineTrack findNearestMachine(int x, int y, int z, int radiusBlocks, long now, long ttlNanos) {
            MachineTrack best = null;
            double bestDistance = Double.MAX_VALUE;
            double maxDistance = (double) radiusBlocks * radiusBlocks;
            for (MachineTrack track : machineTracks) {
                if (now - track.lastPulseNanos > ttlNanos) {
                    continue;
                }
                double distance = track.distanceSquared(x, y, z);
                if (distance <= maxDistance && distance < bestDistance) {
                    best = track;
                    bestDistance = distance;
                }
            }
            return best;
        }

        void addWatch(int chunkX, int chunkZ, int radius, long untilNanos) {
            int safeRadius = Math.max(0, radius);
            for (int i = 0; i < watches.length; i++) {
                WatchZone zone = watches[i];
                if (zone == null) {
                    watches[i] = new WatchZone(chunkX, chunkZ, safeRadius, untilNanos);
                    return;
                }
                if (zone.overlaps(chunkX, chunkZ, safeRadius)) {
                    zone.untilNanos = Math.max(zone.untilNanos, untilNanos);
                    zone.radius = Math.max(zone.radius, safeRadius);
                    return;
                }
            }
            int oldestIndex = 0;
            for (int i = 1; i < watches.length; i++) {
                if (watches[i].untilNanos < watches[oldestIndex].untilNanos) {
                    oldestIndex = i;
                }
            }
            watches[oldestIndex] = new WatchZone(chunkX, chunkZ, safeRadius, untilNanos);
        }

        boolean isWatched(int sourceChunkX, int sourceChunkZ, int targetChunkX, int targetChunkZ, long now) {
            boolean watched = false;
            for (int i = 0; i < watches.length; i++) {
                WatchZone zone = watches[i];
                if (zone == null) {
                    continue;
                }
                if (zone.untilNanos <= now) {
                    watches[i] = null;
                    continue;
                }
                watched |= zone.contains(sourceChunkX, sourceChunkZ) || zone.contains(targetChunkX, targetChunkZ);
            }
            return watched;
        }

        void addEmergency(int chunkX, int chunkZ, int radius, long untilNanos) {
            for (int i = 0; i < emergencies.length; i++) {
                EmergencyZone zone = emergencies[i];
                if (zone == null) {
                    emergencies[i] = new EmergencyZone(chunkX, chunkZ, Math.max(0, radius), untilNanos);
                    return;
                }
                if (zone.overlaps(chunkX, chunkZ, radius)) {
                    zone.untilNanos = Math.max(zone.untilNanos, untilNanos);
                    zone.radius = Math.max(zone.radius, Math.max(0, radius));
                    return;
                }
            }
            int oldestIndex = 0;
            for (int i = 1; i < emergencies.length; i++) {
                if (emergencies[i].untilNanos < emergencies[oldestIndex].untilNanos) {
                    oldestIndex = i;
                }
            }
            emergencies[oldestIndex] = new EmergencyZone(chunkX, chunkZ, Math.max(0, radius), untilNanos);
        }

        boolean isEmergency(int sourceChunkX, int sourceChunkZ, int targetChunkX, int targetChunkZ, long now) {
            boolean blocked = false;
            for (int i = 0; i < emergencies.length; i++) {
                EmergencyZone zone = emergencies[i];
                if (zone == null) {
                    continue;
                }
                if (zone.untilNanos <= now) {
                    emergencies[i] = null;
                    continue;
                }
                blocked |= zone.contains(sourceChunkX, sourceChunkZ) || zone.contains(targetChunkX, targetChunkZ);
            }
            return blocked;
        }

        void trim(long now, Settings cfg) {
            for (int i = 0; i < watches.length; i++) {
                if (watches[i] != null && watches[i].untilNanos <= now) {
                    watches[i] = null;
                }
            }
            for (int i = 0; i < emergencies.length; i++) {
                if (emergencies[i] != null && emergencies[i].untilNanos <= now) {
                    emergencies[i] = null;
                }
            }

            for (int i = flowCells.values.length - 1; i >= 0; i--) {
                if (!flowCells.used[i]) {
                    continue;
                }
                FlowCell cell = flowCells.values[i];
                if (cell.lastEventNanos == 0L || now - cell.lastEventNanos > cfg.flowCellTtlNanos) {
                    flowCells.removeAt(i);
                } else {
                    cell.compact(now, cfg.flowWindowNanos);
                }
            }

            Iterator<Map.Entry<Long, DispenserTrack>> dispenserIterator = dispenserTracks.entrySet().iterator();
            while (dispenserIterator.hasNext()) {
                DispenserTrack track = dispenserIterator.next().getValue();
                if (now - track.lastPulseNanos > cfg.dispenserTtlNanos) {
                    dispenserIterator.remove();
                } else {
                    track.trim(now, cfg.dispenserWindowNanos);
                }
            }

            machineTracks.removeIf(track -> now - track.lastPulseNanos > cfg.machineTrackTtlNanos);
        }

        boolean isEmpty() {
            for (EmergencyZone zone : emergencies) {
                if (zone != null) {
                    return false;
                }
            }
            for (WatchZone zone : watches) {
                if (zone != null) {
                    return false;
                }
            }
            return flowCells.size == 0 && dispenserTracks.isEmpty() && machineTracks.isEmpty();
        }


    }

    private static final class FlowCell {
        final long chunkKey;
        final BucketCounter flowCounter = new BucketCounter(20, 250L * NANO_PER_MILLI);
        final BucketCounter sourceCounter = new BucketCounter(20, 250L * NANO_PER_MILLI);
        final long[] recentTargets = new long[96];
        final long[] recentSourceBlocks = new long[32];
        int recentTargetHead;
        int recentTargetSize;
        int recentSourceHead;
        int recentSourceSize;
        long nextAnalysisNanos;
        long lastEventNanos;
        int lavaFlows;
        int waterFlows;

        FlowCell(long chunkKey) {
            this.chunkKey = chunkKey;
        }

        void record(long now, boolean lava, long targetKey) {
            flowCounter.record(now, 1);
            lastEventNanos = now;
            if (lava) {
                lavaFlows++;
            } else {
                waterFlows++;
            }
            recentTargetAdd(targetKey);
        }

        int burstCount(long now, long windowNanos) {
            return flowCounter.total(now, windowNanos);
        }

        int sourceSignals(long now, long windowNanos) {
            return sourceCounter.total(now, windowNanos);
        }

        void recordSourceBlock(long key) {
            int index = (recentSourceHead + recentSourceSize) % recentSourceBlocks.length;
            if (recentSourceSize == recentSourceBlocks.length) {
                recentSourceBlocks[recentSourceHead] = key;
                recentSourceHead = (recentSourceHead + 1) % recentSourceBlocks.length;
                return;
            }
            recentSourceBlocks[index] = key;
            recentSourceSize++;
        }

        void recentTargetsForEach(TargetConsumer consumer) {
            for (int i = 0; i < recentTargetSize; i++) {
                consumer.accept(recentTargets[(recentTargetHead + i) % recentTargets.length]);
            }
        }

        void recentSourcesForEach(TargetConsumer consumer) {
            for (int i = 0; i < recentSourceSize; i++) {
                consumer.accept(recentSourceBlocks[(recentSourceHead + i) % recentSourceBlocks.length]);
            }
        }

        private void recentTargetAdd(long key) {
            int index = (recentTargetHead + recentTargetSize) % recentTargets.length;
            if (recentTargetSize == recentTargets.length) {
                recentTargets[recentTargetHead] = key;
                recentTargetHead = (recentTargetHead + 1) % recentTargets.length;
                return;
            }
            recentTargets[index] = key;
            recentTargetSize++;
        }

        void compact(long now, long windowNanos) {
            flowCounter.compact(now);
            sourceCounter.compact(now);
            if (now - lastEventNanos > windowNanos) {
                lavaFlows = 0;
                waterFlows = 0;
                recentTargetHead = 0;
                recentTargetSize = 0;
                recentSourceHead = 0;
                recentSourceSize = 0;
            }
        }
    }

    private static final class MachineTrack {
        int x;
        int y;
        int z;
        int lastX;
        int lastY;
        int lastZ;
        BlockFace lastDirection = BlockFace.SELF;
        UUID ownerId;
        long lastPulseNanos;
        long nextInspectionNanos;
        long lastNeutralizedNanos;
        int pistonPulses;
        int stickySignals;
        int observerSignals;
        int carriageSignals;
        int fluidSignals;
        int iceSignals;
        int dispenserSignals;
        int redstoneSignals;
        int flowBurstSignals;
        int playerSourceSignals;
        double travelledBlocks;
        boolean moving;
        boolean hasFluidEvidence;
        boolean breakTriggered;

        MachineTrack(int x, int y, int z, long now) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastPulseNanos = now;
        }

        boolean shouldInspect(long now) {
            return now >= nextInspectionNanos;
        }

        void observe(Block piston, BlockFace direction, boolean retracting,
                     MachineSignature signature, long now, long pulseWindowNanos) {
            if (now - lastPulseNanos > pulseWindowNanos) {
                pistonPulses = 0;
                stickySignals = 0;
                observerSignals = 0;
                carriageSignals = 0;
                fluidSignals = 0;
                iceSignals = 0;
                dispenserSignals = 0;
                redstoneSignals = 0;
                flowBurstSignals = 0;
                playerSourceSignals = 0;
                travelledBlocks = 0.0D;
                moving = false;
                hasFluidEvidence = false;
            }

            int dx = piston.getX() - lastX;
            int dy = piston.getY() - lastY;
            int dz = piston.getZ() - lastZ;
            double distance = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            if (distance > 0.0D) {
                travelledBlocks += distance;
                if (travelledBlocks >= 2.0D) {
                    moving = true;
                }
            }

            this.x = piston.getX();
            this.y = piston.getY();
            this.z = piston.getZ();
            this.lastX = piston.getX();
            this.lastY = piston.getY();
            this.lastZ = piston.getZ();
            this.lastDirection = direction == null ? BlockFace.SELF : direction;
            this.lastPulseNanos = now;
            this.pistonPulses++;

            if (signature.stickyPiston) stickySignals++;
            if (signature.observer) observerSignals++;
            if (signature.slimeOrHoney) carriageSignals++;
            if (signature.fluid) {
                fluidSignals++;
                hasFluidEvidence = true;
            }
            if (signature.ice) {
                iceSignals++;
                hasFluidEvidence = true;
            }
            if (signature.dispenserOrDropper) dispenserSignals++;
            redstoneSignals += Math.min(6, signature.redstoneSignals);
            if (signature.fluid || signature.ice) {
                hasFluidEvidence = true;
            }
        }

        void noteFlowBurst(int flowBurst, boolean lava) {
            flowBurstSignals += Math.min(flowBurst, 32);
            hasFluidEvidence = true;
        }

        void notePlayerSourceBurst(int sources, long now) {
            playerSourceSignals += Math.min(sources, 32);
            hasFluidEvidence = true;
            lastPulseNanos = now;
        }

        void noteIceSource(long now) {
            iceSignals++;
            hasFluidEvidence = true;
            lastPulseNanos = now;
        }

        int confidence(Settings cfg) {
            int score = 0;
            if (stickySignals > 0) score += 2;
            if (observerSignals > 0) score += 2;
            if (carriageSignals > 0) score += 2;
            if (hasFluidEvidence) score += 2;
            if (pistonPulses >= cfg.machineMinPulses) score += 2;
            if (moving) score += 2;
            if (flowBurstSignals >= cfg.flowBurstConfidenceBonus) score += 3;
            if (playerSourceSignals >= cfg.playerSourceConfidenceBonus) score += 2;
            if (dispenserSignals > 0 && redstoneSignals > 0) score += 2;
            if (y >= cfg.verticalDuplicationY) score += 1;
            return Math.min(score, 20);
        }

        boolean isBreakable(Settings cfg) {
            if (breakTriggered) {
                return false;
            }
            if (!hasFluidEvidence) {
                return false;
            }
            if (pistonPulses < cfg.machineMinPulses) {
                return false;
            }
            int confidence = confidence(cfg);
            if (confidence < cfg.machineBreakConfidence) {
                return false;
            }
            if (lastNeutralizedNanos > 0L
                    && System.nanoTime() - lastNeutralizedNanos < cfg.machineNeutralizeCooldownNanos) {
                return false;
            }
            // Require at least one non-fluid mechanical signature as a final guard.
            return carriageSignals > 0 && (stickySignals > 0 || observerSignals > 0 || dispenserSignals > 0);
        }

        double distanceSquared(int px, int py, int pz) {
            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final class DispenserTrack {
        final BucketCounter pulses = new BucketCounter(20, 100L * NANO_PER_MILLI);
        long lastPulseNanos;
        long lastTriggeredNanos;
        boolean hasLava;

        void record(long now, boolean lava, long windowNanos) {
            pulses.record(now, 1);
            lastPulseNanos = now;
            hasLava |= lava;
        }

        int pulses(long now, long windowNanos) {
            return pulses.total(now, windowNanos);
        }

        void trim(long now, long windowNanos) {
            pulses.compact(now);
        }
    }

    private static final class PlayerState {
        final UUID playerId;
        final BucketCounter sourceBurst = new BucketCounter(20, 250L * NANO_PER_MILLI);
        final ArrayDeque<SourcePoint> points = new ArrayDeque<>(64);
        long lastActionNanos;
        int totalSources;

        PlayerState(UUID playerId) {
            this.playerId = playerId;
        }

        void touch(long now, Block location, boolean lava) {
            lastActionNanos = now;
            totalSources++;
            sourceBurst.record(now, 1);
            points.addLast(new SourcePoint(location.getX(), location.getY(), location.getZ(), now, lava));
            while (points.size() > 64) {
                points.pollFirst();
            }
        }

        int sources(long now, long windowNanos) {
            return sourceBurst.total(now, windowNanos);
        }

        int compactSources(int radius) {
            if (points.isEmpty()) {
                return 0;
            }
            SourcePoint newest = points.peekLast();
            int count = 0;
            for (SourcePoint point : points) {
                if (Math.abs(point.x - newest.x) <= radius
                        && Math.abs(point.y - newest.y) <= radius
                        && Math.abs(point.z - newest.z) <= radius) {
                    count++;
                }
            }
            return count;
        }

        void trim(long now) {
            sourceBurst.compact(now);
            while (!points.isEmpty() && now - points.peekFirst().timeNanos > 90L * 1_000L * NANO_PER_MILLI) {
                points.pollFirst();
            }
        }
    }

    /**
     * Fixed-size time-bucket counter. It provides O(1) updates on hot event paths
     * and avoids storing one object/timestamp per fluid update.
     */
    private static final class BucketCounter {
        private final long[] bucketIds;
        private final int[] counts;
        private final long bucketNanos;

        BucketCounter(int bucketCount, long bucketNanos) {
            this.bucketIds = new long[Math.max(4, bucketCount)];
            this.counts = new int[this.bucketIds.length];
            this.bucketNanos = bucketNanos;
            java.util.Arrays.fill(this.bucketIds, Long.MIN_VALUE);
        }

        void record(long now, int amount) {
            long bucketId = now / bucketNanos;
            int index = Math.floorMod(bucketId, bucketIds.length);
            if (bucketIds[index] != bucketId) {
                bucketIds[index] = bucketId;
                counts[index] = 0;
            }
            counts[index] = Math.min(Integer.MAX_VALUE - 1, counts[index] + Math.max(0, amount));
        }

        int total(long now, long windowNanos) {
            long currentBucket = now / bucketNanos;
            long bucketSpan = Math.max(1L, (windowNanos + bucketNanos - 1L) / bucketNanos);
            int total = 0;
            for (int i = 0; i < bucketIds.length; i++) {
                long id = bucketIds[i];
                if (id == Long.MIN_VALUE || currentBucket - id >= bucketSpan || id > currentBucket) {
                    continue;
                }
                total = Math.min(Integer.MAX_VALUE - 1, total + counts[i]);
            }
            return total;
        }

        void compact(long now) {
            long currentBucket = now / bucketNanos;
            long maxAge = bucketIds.length;
            for (int i = 0; i < bucketIds.length; i++) {
                long id = bucketIds[i];
                if (id != Long.MIN_VALUE && (id > currentBucket || currentBucket - id >= maxAge)) {
                    bucketIds[i] = Long.MIN_VALUE;
                    counts[i] = 0;
                }
            }
        }
    }

    private static final class WatchZone {
        final int centerChunkX;
        final int centerChunkZ;
        int radius;
        long untilNanos;

        WatchZone(int centerChunkX, int centerChunkZ, int radius, long untilNanos) {
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.radius = radius;
            this.untilNanos = untilNanos;
        }

        boolean contains(int chunkX, int chunkZ) {
            return Math.abs(chunkX - centerChunkX) <= radius
                    && Math.abs(chunkZ - centerChunkZ) <= radius;
        }

        boolean overlaps(int chunkX, int chunkZ, int otherRadius) {
            return Math.abs(chunkX - centerChunkX) <= radius + otherRadius
                    && Math.abs(chunkZ - centerChunkZ) <= radius + otherRadius;
        }
    }

    private static final class EmergencyZone {
        final int centerChunkX;
        final int centerChunkZ;
        int radius;
        long untilNanos;

        EmergencyZone(int centerChunkX, int centerChunkZ, int radius, long untilNanos) {
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.radius = radius;
            this.untilNanos = untilNanos;
        }

        boolean contains(int chunkX, int chunkZ) {
            return Math.abs(chunkX - centerChunkX) <= radius
                    && Math.abs(chunkZ - centerChunkZ) <= radius;
        }

        boolean overlaps(int chunkX, int chunkZ, int otherRadius) {
            return Math.abs(chunkX - centerChunkX) <= radius + otherRadius
                    && Math.abs(chunkZ - centerChunkZ) <= radius + otherRadius;
        }
    }

    private record MachineSignature(
            boolean stickyPiston,
            boolean slimeOrHoney,
            boolean fluid,
            boolean ice,
            boolean dispenserOrDropper,
            boolean observer,
            int redstoneSignals,
            boolean highY
    ) {
        boolean isMachineLike() {
            return slimeOrHoney && (stickyPiston || observer || dispenserOrDropper);
        }
    }

    private record BlockCandidate(Block block, int priority, double distanceSquared) {}
    private record SourcePoint(int x, int y, int z, long timeNanos, boolean lava) {}

    private static final class Settings {
        final boolean enabled;
        final boolean protectFlow;
        final boolean protectIce;
        final boolean protectDispenserFlood;
        final boolean protectFlyingMachineFlood;
        final boolean neutralizeFluid;
        final boolean neutralizeIce;
        final boolean breakMachineComponents;
        final boolean logViolations;
        final boolean playerMessages;
        final boolean adaptiveLimits;
        final String bypassPermission;

        final int maxFlowCellsPerWorld;
        final int maxTrackedSourceBlocks;
        final int machineInspectionRadius;
        final int machineTrackRadiusBlocks;
        final int maxMachineTracks;
        final int machineMinPulses;
        final int machineBreakConfidence;
        final int machineComponentRadius;
        final int machineComponentLimit;
        final int machineClearRadius;
        final int emergencyChunkRadius;
        final int flowBurstThreshold;
        final int criticalFlowBurstThreshold;
        final int minimumSourceSignals;
        final int playerSourceBurstThreshold;
        final int criticalSourceBurstThreshold;
        final int compactSourceThreshold;
        final int sourceCompactRadiusBlocks;
        final int minimumDispenserRedstoneSignals;
        final int dispenserMaxPulses;
        final int flowBurstConfidenceBonus;
        final int playerSourceConfidenceBonus;
        final int verticalDuplicationY;

        final long flowAnalysisIntervalNanos;
        final long flowWindowNanos;
        final long flowCellTtlNanos;
        final long machinePulseWindowNanos;
        final long machineInspectionCooldownNanos;
        final long machineTrackTtlNanos;
        final long machineNeutralizeCooldownNanos;
        final long emergencyThrottleNanos;
        final long watchWindowNanos;
        final long sourceWindowNanos;
        final long dispenserWindowNanos;
        final long dispenserTtlNanos;
        final long playerStateTtlNanos;

        Settings(NoLag plugin) {
            String base = "features.fluid-flood-protection.";
            var cfg = plugin.getConfig();

            enabled = cfg.getBoolean(base + "enabled", true);
            protectFlow = cfg.getBoolean(base + "protect-flow", true);
            protectIce = cfg.getBoolean(base + "protect-ice", true);
            protectDispenserFlood = cfg.getBoolean(base + "protect-dispenser-flood", true);
            protectFlyingMachineFlood = cfg.getBoolean(base + "protect-flying-machine-flood", true);
            neutralizeFluid = cfg.getBoolean(base + "neutralize-fluid", true);
            neutralizeIce = cfg.getBoolean(base + "neutralize-ice", true);
            breakMachineComponents = cfg.getBoolean(base + "break-machine-components", true);
            logViolations = cfg.getBoolean(base + "log-violations", false);
            playerMessages = cfg.getBoolean(base + "player-messages", true);
            adaptiveLimits = cfg.getBoolean(base + "adaptive-limits", true);
            bypassPermission = cfg.getString(base + "bypass-permission", "nolag.flood.bypass");

            maxFlowCellsPerWorld = clamp(cfg.getInt(base + "max-flow-cells-per-world", 128), 16, 512);
            maxTrackedSourceBlocks = clamp(cfg.getInt(base + "max-tracked-source-blocks", 32), 8, 128);
            machineInspectionRadius = clamp(cfg.getInt(base + "machine-inspection-radius", 2), 1, 4);
            machineTrackRadiusBlocks = clamp(cfg.getInt(base + "machine-track-radius", 10), 4, 16);
            maxMachineTracks = clamp(cfg.getInt(base + "max-machine-tracks", 48), 8, 128);
            machineMinPulses = clamp(cfg.getInt(base + "machine-min-pulses", 3), 2, 8);
            machineBreakConfidence = clamp(cfg.getInt(base + "machine-break-confidence", 9), 7, 16);
            machineComponentRadius = clamp(cfg.getInt(base + "machine-component-radius", 3), 1, 5);
            machineComponentLimit = clamp(cfg.getInt(base + "machine-component-limit", 12), 1, 32);
            machineClearRadius = clamp(cfg.getInt(base + "machine-clear-radius", 4), 1, 8);
            emergencyChunkRadius = clamp(cfg.getInt(base + "emergency-chunk-radius", 1), 0, 2);
            flowBurstThreshold = clamp(cfg.getInt(base + "flow-burst-threshold", 48), 16, 256);
            criticalFlowBurstThreshold = Math.max(flowBurstThreshold, clamp(cfg.getInt(base + "critical-flow-burst-threshold", 96), 24, 512));
            minimumSourceSignals = clamp(cfg.getInt(base + "minimum-source-signals", 8), 2, 64);
            playerSourceBurstThreshold = clamp(cfg.getInt(base + "player-source-burst-threshold", 12), 4, 64);
            criticalSourceBurstThreshold = Math.max(playerSourceBurstThreshold,
                    clamp(cfg.getInt(base + "critical-source-burst-threshold", 20), 6, 96));
            compactSourceThreshold = clamp(cfg.getInt(base + "compact-source-threshold", 8), 3, 32);
            sourceCompactRadiusBlocks = clamp(cfg.getInt(base + "source-compact-radius-blocks", 4), 2, 8);
            minimumDispenserRedstoneSignals = clamp(cfg.getInt(base + "minimum-dispenser-redstone-signals", 2), 1, 8);
            dispenserMaxPulses = clamp(cfg.getInt(base + "dispenser-max-pulses", 10), 6, 32);
            flowBurstConfidenceBonus = clamp(cfg.getInt(base + "flow-burst-confidence-bonus", 32), 8, 128);
            playerSourceConfidenceBonus = clamp(cfg.getInt(base + "player-source-confidence-bonus", 12), 4, 64);
            verticalDuplicationY = cfg.getInt(base + "vertical-duplication-y", 280);

            flowAnalysisIntervalNanos = millis(cfg.getLong(base + "flow-analysis-interval-ms", 200L), 50L, 1_000L);
            flowWindowNanos = millis(cfg.getLong(base + "flow-window-ms", 1_000L), 250L, 5_000L);
            flowCellTtlNanos = millis(cfg.getLong(base + "flow-cell-ttl-ms", 6_000L), 1_000L, 30_000L);
            machinePulseWindowNanos = millis(cfg.getLong(base + "machine-pulse-window-ms", 1_800L), 500L, 5_000L);
            machineInspectionCooldownNanos = millis(cfg.getLong(base + "machine-inspection-cooldown-ms", 250L), 50L, 1_000L);
            machineTrackTtlNanos = millis(cfg.getLong(base + "machine-track-ttl-ms", 4_000L), 1_000L, 15_000L);
            machineNeutralizeCooldownNanos = millis(cfg.getLong(base + "machine-neutralize-cooldown-ms", 5_000L), 1_000L, 30_000L);
            emergencyThrottleNanos = millis(cfg.getLong(base + "emergency-throttle-ms", 3_500L), 500L, 15_000L);
            watchWindowNanos = millis(cfg.getLong(base + "watch-window-ms", 6_000L), 1_000L, 30_000L);
            sourceWindowNanos = millis(cfg.getLong(base + "source-window-ms", 2_500L), 500L, 10_000L);
            dispenserWindowNanos = millis(cfg.getLong(base + "dispenser-window-ms", 1_200L), 250L, 5_000L);
            dispenserTtlNanos = millis(cfg.getLong(base + "dispenser-ttl-ms", 10_000L), 2_000L, 60_000L);
            playerStateTtlNanos = millis(cfg.getLong(base + "player-state-expire-ms", 90_000L), 10_000L, 600_000L);
        }

        static Settings load(NoLag plugin) {
            return new Settings(plugin);
        }

        long nowNanos() {
            return System.nanoTime();
        }

        private static long millis(long value, long min, long max) {
            long clamped = Math.max(min, Math.min(max, value));
            return clamped * NANO_PER_MILLI;
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static long clamp(long value, long min, long max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
