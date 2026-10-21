package me.nolag.modules.autosave;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;


public final class AutosaveOptimizerModule implements Listener {

    private final NoLag plugin;

    private volatile boolean enabled = true;
    private volatile boolean disableVanillaAutosave = true;
    private volatile long staggerIntervalTicks = 200L;
    private volatile double minTpsToSave = 15.0;

    private TaskHandle cycleTask;
    private final List<String> cursorWorldNames = new ArrayList<>();

    private final Map<UUID, Boolean> originalAutosave = new HashMap<>();
    private int cursor;

    private final AtomicInteger savesPerformed = new AtomicInteger();
    private final AtomicInteger savesSkippedLowTps = new AtomicInteger();
    private volatile long lastSaveStartedAt;
    private volatile String lastSavedWorldName = "-";

    public AutosaveOptimizerModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("features.autosave-optimizer.enabled", true);
        this.disableVanillaAutosave = cfg.getBoolean("features.autosave-optimizer.disable-vanilla-autosave", true);

        long staggerSeconds = Math.max(2L, cfg.getLong("features.autosave-optimizer.stagger-seconds-between-worlds", 10L));
        this.staggerIntervalTicks = staggerSeconds * 20L;

        this.minTpsToSave = clamp(cfg.getDouble("features.autosave-optimizer.min-tps-to-save", 15.0), 1.0, 20.0);
    }

    public void start() {
        stop();
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return;
        }

        applyVanillaAutosaveState();
        refreshWorldCursor();

        cycleTask = plugin.getSchedulerAdapter().runGlobalTimer(this::cycle, staggerIntervalTicks, staggerIntervalTicks);
    }

    public void stop() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
        restoreVanillaAutosaveState();
    }

    private void applyVanillaAutosaveState() {
        if (!disableVanillaAutosave) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            try {
                originalAutosave.putIfAbsent(world.getUID(), world.isAutoSave());
                world.setAutoSave(false);
            } catch (Throwable ignored) {
            }
        }
    }

    private void restoreVanillaAutosaveState() {


        if (originalAutosave.isEmpty()) return;
        for (World world : Bukkit.getWorlds()) {
            try {
                Boolean previous = originalAutosave.remove(world.getUID());
                if (previous != null) {
                    world.setAutoSave(previous);
                }
            } catch (Throwable ignored) {
            }
        }
        originalAutosave.clear();
    }

    private void refreshWorldCursor() {
        cursorWorldNames.clear();
        for (World world : Bukkit.getWorlds()) {
            if (world != null) {
                cursorWorldNames.add(world.getName());
            }
        }
        cursor = 0;
    }

    private void cycle() {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return;
        }

        if (cursorWorldNames.isEmpty()) {
            refreshWorldCursor();
            if (cursorWorldNames.isEmpty()) {
                return;
            }
        }

        if (cursor >= cursorWorldNames.size()) {
            refreshWorldCursor();
            if (cursorWorldNames.isEmpty()) {
                return;
            }
        }

        String targetName = cursorWorldNames.get(cursor++);
        World target = Bukkit.getWorld(targetName);
        if (target == null) {

            return;
        }

        double tps = plugin.getTPSMonitor() != null ? plugin.getTPSMonitor().getTPS() : 20.0;
        if (tps < minTpsToSave) {
            savesSkippedLowTps.incrementAndGet();
            return;
        }

        lastSaveStartedAt = System.currentTimeMillis();
        lastSavedWorldName = target.getName();
        try {
            target.save();
            savesPerformed.incrementAndGet();
        } catch (Throwable t) {
            plugin.getLogger().warning("[AutosaveOptimizer] Failed to save world '" + target.getName() + "': " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return;
        }
        if (disableVanillaAutosave) {
            try {
                World world = event.getWorld();
                originalAutosave.putIfAbsent(world.getUID(), world.isAutoSave());
                world.setAutoSave(false);
            } catch (Throwable ignored) {
            }
        }
        refreshWorldCursor();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        refreshWorldCursor();
    }

    public boolean isEnabled() {
        return enabled && !plugin.getSchedulerAdapter().isFolia();
    }

    public String getStatusSummary() {
        if (plugin.getSchedulerAdapter().isFolia()) {
            return "Disabled on Folia (each region manages its own save cadence).";
        }
        if (!enabled) {
            return "Disabled in configuration.";
        }
        long secondsAgo = lastSaveStartedAt == 0L ? -1L : Math.max(0L, (System.currentTimeMillis() - lastSaveStartedAt) / 1000L);
        return String.format(Locale.ROOT,
                "Active | Worlds staggered: %d | Saves: %d | Skipped (low TPS): %d | Last: %s%s",
                cursorWorldNames.size(), savesPerformed.get(), savesSkippedLowTps.get(), lastSavedWorldName,
                secondsAgo < 0 ? "" : " (" + secondsAgo + "s ago)");
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
