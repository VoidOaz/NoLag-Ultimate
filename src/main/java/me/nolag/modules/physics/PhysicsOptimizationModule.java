package me.nolag.modules.physics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class PhysicsOptimizationModule implements Listener {

    private final NoLag plugin;

    private final Map<Long, Integer> redstoneActivity = new ConcurrentHashMap<>();
    private BukkitTask resetTask;

    public PhysicsOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        startResetTask();
    }

    private void startResetTask() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        this.resetTask = new BukkitRunnable() {
            @Override
            public void run() {
                redstoneActivity.clear();
            }
        }.runTaskTimer(plugin, 40L, 40L); 
    }

    public void stop() {
        if (resetTask != null) {
            resetTask.cancel();
            resetTask = null;
        }
        redstoneActivity.clear();
    }

    private static long packLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return 0L;
        long worldHash = (long) (loc.getWorld().getName().hashCode() & 0xFFFF); 
        long wx = (long) (loc.getBlockX() & 0xFFFFF);                           
        long wz = (long) (loc.getBlockZ() & 0xFFFFF);                           
        long wy = (long) (loc.getBlockY() & 0xFF);                              
        return (worldHash << 48) | (wx << 28) | (wz << 8) | wy;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        if (!plugin.getConfig().getBoolean("features.anti-lag-machine", true)) {
            return;
        }

        Block block = event.getBlock();
        long key = packLocation(block.getLocation());
        int currentCount = redstoneActivity.merge(key, 1, Integer::sum);
        int maxUpdates = plugin.getConfig().getInt("settings.max-redstone-updates", 20);

        if (currentCount > maxUpdates) {
            redstoneActivity.remove(key);
            event.setNewCurrent(0); 

            Location loc = block.getLocation();
            plugin.getLogger().warning("[NoLag] Redstone clock suppressed at " +
                    loc.getWorld().getName() + " (" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");

            block.setType(Material.AIR, false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        double freezeTPS = plugin.getConfig().getDouble("advanced-optimization.physics-freeze-tps", 15.0);
        if (plugin.getTPSMonitor().getTPS() < freezeTPS) {
            Material type = event.getChangedType();
            if (isSuppressedPhysicsType(type)) {
                event.setCancelled(true);
            }
        }
    }

    private boolean isSuppressedPhysicsType(Material type) {
        if (type == Material.SAND || type == Material.GRAVEL || type == Material.SUSPICIOUS_SAND || type == Material.SUSPICIOUS_GRAVEL
                || type == Material.WATER || type == Material.LAVA || type == Material.REDSTONE_WIRE
                || type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH
                || type == Material.REPEATER || type == Material.COMPARATOR || type == Material.OBSERVER
                || type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL
                || type == Material.POINTED_DRIPSTONE || type == Material.DRAGON_EGG) {
            return true;
        }
        return Tag.CONCRETE_POWDER.isTagged(type);
    }
}
