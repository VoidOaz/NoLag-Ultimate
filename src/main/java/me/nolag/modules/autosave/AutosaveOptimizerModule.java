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
import java.util.concurrent.atomic.AtomicInteger;


public final class AutosaveOptimizerModule implements Listener {

    private final NoLag plugin;

    private volatile boolean enabled = false;
    private volatile long staggerIntervalTicks = 200L;
    private volatile double minTpsToSave = 15.0;

    private TaskHandle cycleTask;
    private final List<String> cursorWorldNames = new ArrayList<>();

    private int cursor;

    private final AtomicInteger savesPerformed = new AtomicInteger();
    private final AtomicInteger savesSkippedLowTps = new AtomicInteger();
    private volatile long lastSaveStartedAt;
    private volatile long lastSaveDurationMs;
    private volatile String lastSavedWorldName = "-";

    public AutosaveOptimizerModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        // P-01: Default to disabled to avoid redundant concurrent disk I/O on top of vanilla autosave.
        // Native autosave is authoritative; do not schedule supplemental full-world flushes.
        this.enabled = false;
        if (cfg.getBoolean("features.autosave-optimizer.enabled", false)) {
            plugin.getLogger().warning("Supplemental autosave is unavailable; native server autosave remains active.");
        }
        // S-02: Clean handling of deprecated key
        if (cfg.contains("features.autosave-optimizer.disable-vanilla-autosave")) {
            cfg.set("features.autosave-optimizer.disable-vanilla-autosave", null);
            plugin.saveConfig();
        }

        long staggerSeconds = Math.max(2L, cfg.getLong("features.autosave-optimizer.stagger-seconds-between-worlds", 15L));
        this.staggerIntervalTicks = staggerSeconds * 20L;

        this.minTpsToSave = clamp(cfg.getDouble("features.autosave-optimizer.min-tps-to-save", 16.0), 1.0, 20.0);
    }

    public void start() {
        stop();
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return;
        }

        refreshWorldCursor();

        cycleTask = plugin.getSchedulerAdapter().runGlobalTimer(this::cycle, staggerIntervalTicks, staggerIntervalTicks);
    }

    public void stop() {
        if (cycleTask != null) {
            cycleTask.cancel();
            cycleTask = null;
        }
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
        // No manual world saves. Server autosave remains enabled and authoritative.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            return;
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

    public long getLastSaveDurationMs() {
        return lastSaveDurationMs;
    }

    public String getStatusSummary() {
        if (plugin.getSchedulerAdapter().isFolia()) {
            return "Disabled on Folia (each region manages its own save cadence).";
        }
        if (!enabled) {
            return "Native server autosave in use; supplemental World.save() disabled.";
        }
        long secondsAgo = lastSaveStartedAt == 0L ? -1L : Math.max(0L, (System.currentTimeMillis() - lastSaveStartedAt) / 1000L);
        return String.format(Locale.ROOT,
                "Active (supplemental; world autosave settings untouched) | Worlds staggered: %d | Saves: %d | Skipped (low TPS): %d | Last: %s%s",
                cursorWorldNames.size(), savesPerformed.get(), savesSkippedLowTps.get(), lastSavedWorldName,
                secondsAgo < 0 ? "" : " (" + secondsAgo + "s ago)");
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
