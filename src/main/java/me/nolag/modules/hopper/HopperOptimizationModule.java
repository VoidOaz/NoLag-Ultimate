package me.nolag.modules.hopper;

import java.util.concurrent.ThreadLocalRandom;

import me.nolag.NoLag;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;

public final class HopperOptimizationModule implements Listener {

    private final NoLag plugin;

    private volatile boolean enabled = true;
    private volatile double lightTPS = 16.0;
    private volatile double heavyTPS = 14.0;

    public HopperOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.hopper-throttling", true);
        lightTPS = clamp(cfg.getDouble("advanced-optimization.hopper.light-throttling-tps", 16.0), 1.0, 20.0);
        heavyTPS = clamp(cfg.getDouble("advanced-optimization.hopper.heavy-throttling-tps", 14.0), 1.0, lightTPS);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMoveItem(InventoryMoveItemEvent event) {
        if (!enabled) {
            return;
        }

        InventoryType source = event.getSource().getType();
        InventoryType destination = event.getDestination().getType();

        if (source != InventoryType.HOPPER && destination != InventoryType.HOPPER) {
            return;
        }

        double tps = plugin.getTPSMonitor().getTPS();
        if (tps >= lightTPS) {
            return;
        }

        long start = System.nanoTime();
        try {
            double skipChance;

            if (tps <= heavyTPS) {
                skipChance = 0.75D;
            } else {
                double difference = lightTPS - heavyTPS;
                double pressure = difference <= 0 ? 0 : (lightTPS - tps) / difference;
                skipChance = 0.40D + (pressure * 0.35D);
            }

            if (ThreadLocalRandom.current().nextDouble() < skipChance) {
                event.setCancelled(true);
            }
        } finally {
            // FIXED: Added null check for performance analyzer before calling methods on it
            var analyzer = plugin.getPerformanceAnalyzer();
            if (analyzer != null && analyzer.isEnabled()) {
                analyzer.record("Hopper", System.nanoTime() - start, 1);
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}