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

    public HopperOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperMoveItem(InventoryMoveItemEvent event) {
        if (!plugin.getConfig().getBoolean("features.hopper-throttling", true)) {
            return;
        }

        if (event.getSource().getType() != InventoryType.HOPPER && event.getDestination().getType() != InventoryType.HOPPER) {
            return;
        }

        double tps = plugin.getTPSMonitor().getTPS();
        double lightTPS = plugin.getConfig().getDouble("advanced-optimization.hopper.light-throttling-tps", 16.0);
        double heavyTPS = plugin.getConfig().getDouble("advanced-optimization.hopper.heavy-throttling-tps", 14.0);

        if (tps >= lightTPS) {
            return;
        }

        double skipChance = (tps < heavyTPS) ? 0.75 : 0.40;
        if (ThreadLocalRandom.current().nextDouble() < skipChance) {
            event.setCancelled(true);
        }
    }
}


