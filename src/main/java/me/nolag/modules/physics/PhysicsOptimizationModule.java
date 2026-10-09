package me.nolag.modules.physics;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;

public final class PhysicsOptimizationModule implements Listener {

    private final NoLag plugin;
    // Map of packed coordinate key -> update count in current 2-second window
    private final Map<Long, Integer> redstoneActivity = new ConcurrentHashMap<>();
    private TaskHandle resetTask;

    private volatile boolean antiLagMachineEnabled = true;
    private volatile int maxRedstoneUpdates = 20;
    private static final int MAX_TRACKED_REDSTONE_BLOCKS = 50_000;
    private volatile double physicsFreezeTps = 15.0;

    public PhysicsOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        startResetTask();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.antiLagMachineEnabled = cfg.getBoolean("features.anti-lag-machine", true);
        this.maxRedstoneUpdates = Math.max(5, cfg.getInt("settings.max-redstone-updates", 20));
        this.physicsFreezeTps = cfg.getDouble("advanced-optimization.physics-freeze-tps", 15.0);
    }

    private void startResetTask() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        this.resetTask = plugin.getSchedulerAdapter().runGlobalTimer(
                redstoneActivity::clear,
                40L,
                40L
        );
    }

    public void stop() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        redstoneActivity.clear();
    }

    public static long packBlockCoordinates(World world, int x, int y, int z) {
        if (world == null) {
            return 0L;
        }
        UUID uid = world.getUID();
        long value = uid.getMostSignificantBits() ^ Long.rotateLeft(uid.getLeastSignificantBits(), 29);
        value = mix(value ^ ((long) x * 0x9E3779B97F4A7C15L));
        value = mix(value ^ ((long) y * 0xC2B2AE3D27D4EB4FL));
        return mix(value ^ ((long) z * 0x165667B19E3779F9L));
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!antiLagMachineEnabled) {
            return;
        }

        // A healthy server should not pay the cost of building/merging the
        // anti-clock map on every redstone event. Activate tracking only while
        // the server is under pressure.
        if (plugin.getTPSMonitor().getTPS() >= Math.min(18.0D, physicsFreezeTps + 3.0D)) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            Block block = event.getBlock();
            long key = packBlockCoordinates(block.getWorld(), block.getX(), block.getY(), block.getZ());
            int currentCount;
            if (redstoneActivity.size() >= MAX_TRACKED_REDSTONE_BLOCKS && !redstoneActivity.containsKey(key)) {
                return;
            }
            currentCount = redstoneActivity.merge(key, 1, Integer::sum);

            if (currentCount > maxRedstoneUpdates) {
                // Suppress update without destroying player's component
                event.setNewCurrent(event.getOldCurrent());

                if (plugin.getConfig().getBoolean("settings.debug", false)) {
                    plugin.getLogger().fine("[NoLag] Redstone update throttled at " +
                            block.getWorld().getName() + " (" + block.getX() + ", " + block.getY() + ", " + block.getZ() + ")");
                }
            }
        } finally {
            if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
                plugin.getPerformanceAnalyzer().record("Redstone", System.nanoTime() - startNanos, 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (plugin.getTPSMonitor().getTPS() >= physicsFreezeTps) {
            return;
        }

        long startNanos = System.nanoTime();
        try {
            Material type = event.getChangedType();
            if (isSuppressedPhysicsType(type)) {
                event.setCancelled(true);
            }
        } finally {
            if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
                plugin.getPerformanceAnalyzer().record("Physics", System.nanoTime() - startNanos, 1);
            }
        }
    }

    private boolean isSuppressedPhysicsType(Material type) {
        if (type == Material.SAND || type == Material.GRAVEL || type == Material.SUSPICIOUS_SAND || type == Material.SUSPICIOUS_GRAVEL
                || type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL
                || type == Material.POINTED_DRIPSTONE || type == Material.DRAGON_EGG) {
            return true;
        }
        return Tag.CONCRETE_POWDER.isTagged(type);
    }
}
