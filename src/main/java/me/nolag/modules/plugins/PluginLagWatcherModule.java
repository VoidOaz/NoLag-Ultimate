package me.nolag.modules.plugins;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.TimedRegisteredListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

public final class PluginLagWatcherModule {

    private final NoLag plugin;
    private volatile boolean enabled = true;
    private volatile boolean notifyOps = true;
    private volatile double msptAlertThreshold = 8.0;
    private volatile long alertCooldownMs = 60_000L;
    private volatile int samplingIntervalTicks = 10;
    private volatile PluginPrefix[] pluginPrefixes = new PluginPrefix[0];
    private volatile Thread serverMainThread;
    private final AtomicLong totalStackSamples = new AtomicLong();
    private volatile long lastStackSampleTimestamp;
    private long lastAnalyzedStackSampleCount;
    private TaskHandle monitorTask;
    private ScheduledExecutorService samplerExecutor;
    private ScheduledFuture<?> samplerFuture;
    private final Map<String, PluginMetrics> metricsMap = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAlertTimestamps = new ConcurrentHashMap<>();
    private final Map<RegisteredListener, Long> lastTimedListenerNanos = new IdentityHashMap<>();

    public PluginLagWatcherModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        start();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("features.plugin-lag-watcher.enabled", true);
        notifyOps = cfg.getBoolean("features.plugin-lag-watcher.notify-ops", true);
        msptAlertThreshold = Math.max(2.0, cfg.getDouble("features.plugin-lag-watcher.mspt-alert-threshold", 8.0));
        long cooldownSeconds = Math.max(10L, cfg.getLong("features.plugin-lag-watcher.alert-cooldown-seconds", 60L));
        alertCooldownMs = cooldownSeconds * 1000L;
        samplingIntervalTicks = Math.max(4, cfg.getInt("features.plugin-lag-watcher.sampling-interval-ticks", 10));
    }

    public synchronized void start() {
        cancelTasks();
        if (!enabled) {
            return;
        }
        refreshPluginIndex();
        if (plugin.getSchedulerAdapter().isFolia()) {
            plugin.getSchedulerAdapter().runGlobal(() -> serverMainThread = Thread.currentThread());
        } else {
            serverMainThread = Thread.currentThread();
        }
        long intervalMillis = Math.max(100L, samplingIntervalTicks * 50L);
        samplerExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "NoLag-PluginLagSampler");
            thread.setDaemon(true);
            return thread;
        });
        samplerFuture = samplerExecutor.scheduleAtFixedRate(
                this::sampleThreadStack,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
        monitorTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::analyzeAndCheckAlerts,
                60L,
                60L
        );
    }

    public synchronized void stop() {
        cancelTasks();
        serverMainThread = null;
    }

    private void cancelTasks() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
        if (samplerFuture != null) {
            samplerFuture.cancel(true);
            samplerFuture = null;
        }
        if (samplerExecutor != null) {
            samplerExecutor.shutdownNow();
            samplerExecutor = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getTotalStackSamples() {
        return totalStackSamples.get();
    }

    public long getLastStackSampleTimestamp() {
        return lastStackSampleTimestamp;
    }

    public boolean hasStackSampler() {
        return samplerFuture != null && !samplerFuture.isCancelled();
    }

    private void refreshPluginIndex() {
        List<PluginPrefix> prefixes = new ArrayList<>();
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate == null || candidate == plugin || !candidate.isEnabled()) {
                continue;
            }
            String mainClass = candidate.getDescription().getMain();
            if (mainClass == null || mainClass.isBlank()) {
                continue;
            }
            int lastDot = mainClass.lastIndexOf('.');
            boolean exactClass = lastDot < 0;
            String prefix = exactClass ? mainClass : mainClass.substring(0, lastDot);
            if (prefix.isBlank()) {
                continue;
            }
            prefixes.add(new PluginPrefix(prefix, exactClass, candidate.getName()));
            metricsMap.computeIfAbsent(candidate.getName(), key -> new PluginMetrics(candidate));
        }
        prefixes.sort(Comparator.comparingInt((PluginPrefix entry) -> entry.prefix().length()).reversed());
        pluginPrefixes = prefixes.toArray(PluginPrefix[]::new);
    }

    private void sampleThreadStack() {
        if (!enabled) {
            return;
        }
        Thread target = serverMainThread;
        if (target == null || !target.isAlive()) {
            return;
        }
        StackTraceElement[] stack;
        try {
            stack = target.getStackTrace();
        } catch (Throwable ignored) {
            return;
        }
        totalStackSamples.incrementAndGet();
        lastStackSampleTimestamp = System.currentTimeMillis();
        if (stack == null || stack.length == 0) {
            return;
        }
        PluginPrefix[] prefixes = pluginPrefixes;
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            for (PluginPrefix entry : prefixes) {
                boolean matches = entry.exactClass()
                        ? className.equals(entry.prefix()) || className.startsWith(entry.prefix() + "$")
                        : className.startsWith(entry.prefix() + ".");
                if (!matches) {
                    continue;
                }
                PluginMetrics metrics = metricsMap.get(entry.name());
                if (metrics != null) {
                    metrics.recordStackSample();
                }
                return;
            }
        }
    }

    private void analyzeAndCheckAlerts() {
        if (!enabled) {
            return;
        }
        refreshPluginIndex();
        double currentTps = plugin.getTPSMonitor().getTPS();
        double currentMspt = plugin.getTPSMonitor().getMSPT();
        inspectRegisteredListeners();
        long sampledTotal = totalStackSamples.get();
        long expectedSamples = Math.max(1L, sampledTotal - lastAnalyzedStackSampleCount);
        lastAnalyzedStackSampleCount = sampledTotal;
        for (Plugin other : Bukkit.getPluginManager().getPlugins()) {
            if (other == null || other == plugin || !other.isEnabled()) {
                continue;
            }
            PluginMetrics metrics = metricsMap.computeIfAbsent(other.getName(), key -> new PluginMetrics(other));
            metrics.rollWindow(currentMspt, expectedSamples);
            double msptImpact = metrics.getEstimatedMspt();
            long sampleCount = metrics.getWindowSamples();
            int listenerCount = metrics.getListenerCount();
            int listenerGrowth = metrics.getListenerGrowth();
            if (msptImpact >= msptAlertThreshold) {
                String reason = String.format(Locale.ROOT, "High estimated tick execution time (~%.2f ms MSPT)", msptImpact);
                triggerAlert(other, "High MSPT Usage", msptImpact, currentTps, currentMspt, reason);
                continue;
            }
            if (currentTps < 18.0 && sampleCount >= 4 && msptImpact >= msptAlertThreshold * 0.6) {
                String reason = String.format(Locale.ROOT, "Frequent main-thread activity (%.1f%% sampled activity) while server TPS is dropping", metrics.getActivityPercent());
                triggerAlert(other, "Lag Spike Contributor", msptImpact, currentTps, currentMspt, reason);
                continue;
            }
            if (listenerGrowth >= 25 && listenerCount > 80) {
                String reason = String.format(Locale.ROOT, "Rapid event listener growth (%d active listeners, +%d new)", listenerCount, listenerGrowth);
                triggerAlert(other, "Suspected Listener Leak", msptImpact, currentTps, currentMspt, reason);
            }
        }
    }

    private void inspectRegisteredListeners() {
        Map<Plugin, Integer> listenerCounts = new HashMap<>();
        Set<RegisteredListener> seenListeners = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (HandlerList handlerList : HandlerList.getHandlerLists()) {
                for (RegisteredListener listener : handlerList.getRegisteredListeners()) {
                    Plugin owner = listener.getPlugin();
                    if (owner == null || owner == plugin || !owner.isEnabled()) {
                        continue;
                    }
                    listenerCounts.merge(owner, 1, Integer::sum);
                    seenListeners.add(listener);
                    if (listener instanceof TimedRegisteredListener timedListener) {
                        long currentNanos;
                        try {
                            currentNanos = Math.max(0L, timedListener.getTotalTime());
                        } catch (Throwable ignored) {
                            continue;
                        }
                        Long previousNanos = lastTimedListenerNanos.put(listener, currentNanos);
                        if (previousNanos != null) {
                            long delta = currentNanos >= previousNanos ? currentNanos - previousNanos : currentNanos;
                            if (delta > 0L) {
                                metricsMap.computeIfAbsent(owner.getName(), key -> new PluginMetrics(owner)).recordTimedListenerNanos(delta);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        lastTimedListenerNanos.keySet().removeIf(listener -> !seenListeners.contains(listener));
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate == null || candidate == plugin) {
                continue;
            }
            PluginMetrics metrics = metricsMap.computeIfAbsent(candidate.getName(), key -> new PluginMetrics(candidate));
            metrics.updateListenerCount(listenerCounts.getOrDefault(candidate, 0));
        }
    }

    private void triggerAlert(Plugin targetPlugin, String alertType, double msptImpact, double serverTps, double serverMspt, String reason) {
        String name = targetPlugin.getName();
        long now = System.currentTimeMillis();
        Long lastAlert = lastAlertTimestamps.get(name);
        if (lastAlert != null && now - lastAlert < alertCooldownMs) {
            return;
        }
        lastAlertTimestamps.put(name, now);
        String version = targetPlugin.getDescription().getVersion();
        plugin.getLogger().warning(String.format(
                Locale.ROOT,
                "[PluginLagWatcher] PERFORMANCE WARNING: Plugin '%s' (v%s) may be contributing to performance degradation. Issue: %s | Estimated MSPT impact: %.2fms (Server MSPT: %.2fms, TPS: %.2f) | Details: %s",
                name, version, alertType, msptImpact, serverMspt, serverTps, reason
        ));
        if (!notifyOps) {
            return;
        }
        String prefix = ChatColor.translateAlternateColorCodes('&', "&8[&6&lNoLag-Watcher&8] ");
        String line1 = prefix + ChatColor.RED + ChatColor.BOLD + "PERFORMANCE WARNING!";
        String line2 = ChatColor.GRAY + "• Plugin: " + ChatColor.YELLOW + ChatColor.BOLD + name + ChatColor.GRAY + " (v" + version + ")";
        String line3 = ChatColor.GRAY + "• Detected issue: " + ChatColor.RED + alertType;
        String line4 = ChatColor.GRAY + "• Estimated MSPT impact: " + ChatColor.YELLOW + String.format(Locale.ROOT, "~%.2f ms", msptImpact)
                + ChatColor.GRAY + " | Server TPS: " + (serverTps < 18.0 ? ChatColor.RED : ChatColor.GREEN) + String.format(Locale.ROOT, "%.2f", serverTps);
        String line5 = ChatColor.GRAY + "• Details: " + ChatColor.WHITE + reason;
        String line6 = ChatColor.GRAY + "• Inspect: " + ChatColor.AQUA + "/nolag plugins";
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null) {
                continue;
            }
            Runnable notifyPlayer = () -> {
                if (player.isOp() || player.hasPermission("nolag.admin") || player.hasPermission("nolag.plugins")) {
                    player.sendMessage(ChatColor.DARK_GRAY + "----------------------------------------");
                    player.sendMessage(line1);
                    player.sendMessage(line2);
                    player.sendMessage(line3);
                    player.sendMessage(line4);
                    player.sendMessage(line5);
                    player.sendMessage(line6);
                    player.sendMessage(ChatColor.DARK_GRAY + "----------------------------------------");
                }
            };
            if (plugin.getSchedulerAdapter().isFolia()) {
                plugin.getSchedulerAdapter().runEntity(player, notifyPlayer);
            } else {
                notifyPlayer.run();
            }
        }
    }

    public List<PluginProfile> getTopLaggingPlugins() {
        List<PluginProfile> profiles = new ArrayList<>();
        for (PluginMetrics metrics : metricsMap.values()) {
            Plugin target = metrics.plugin;
            if (target == null || target == plugin || !target.isEnabled()) {
                continue;
            }
            profiles.add(new PluginProfile(
                    target.getName(),
                    target.getDescription().getVersion(),
                    metrics.getEstimatedMspt(),
                    metrics.getActivityPercent(),
                    metrics.getListenerCount(),
                    metrics.getRiskLevel()
            ));
        }
        profiles.sort(Comparator.comparingDouble(PluginProfile::msptImpact).reversed()
                .thenComparing(Comparator.comparingDouble(PluginProfile::activityPercent).reversed())
                .thenComparing(PluginProfile::name, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(profiles);
    }

    public record PluginProfile(
            String name,
            String version,
            double msptImpact,
            double activityPercent,
            int listenerCount,
            RiskLevel riskLevel
    ) {}

    public enum RiskLevel {
        CLEAN("&aCLEAN"),
        MODERATE("&eMODERATE"),
        HIGH("&cHIGH"),
        CRITICAL("&4CRITICAL");

        private final String display;

        RiskLevel(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }
    }

    private record PluginPrefix(String prefix, boolean exactClass, String name) {}

    private static final class PluginMetrics {
        private final Plugin plugin;
        private final AtomicLong stackHits = new AtomicLong();
        private final AtomicLong timedNanos = new AtomicLong();
        private volatile int listenerCount;
        private volatile int previousListenerCount;
        private volatile double estimatedMspt;
        private volatile double activityPercent;
        private volatile long lastWindowSamples;

        private PluginMetrics(Plugin plugin) {
            this.plugin = plugin;
        }

        private void recordStackSample() {
            stackHits.incrementAndGet();
        }

        private void recordTimedListenerNanos(long nanos) {
            timedNanos.addAndGet(nanos);
        }

        private void updateListenerCount(int count) {
            previousListenerCount = listenerCount;
            listenerCount = count;
        }

        private int getListenerCount() {
            return listenerCount;
        }

        private int getListenerGrowth() {
            return Math.max(0, listenerCount - previousListenerCount);
        }

        private void rollWindow(double serverMspt, long expectedSamples) {
            long hits = stackHits.getAndSet(0L);
            long nanos = timedNanos.getAndSet(0L);
            lastWindowSamples = hits;
            activityPercent = Math.min(100.0, hits * 100.0 / expectedSamples);
            double basisMspt = Math.max(1.0, Math.max(0.0, serverMspt));
            double fromActivity = activityPercent / 100.0 * basisMspt;
            double fromTimed = nanos / 1_000_000.0 / 60.0;
            double currentEstimate = Math.max(fromActivity, fromTimed);
            if (currentEstimate <= 0.0) {
                estimatedMspt *= 0.35;
                if (estimatedMspt < 0.02) {
                    estimatedMspt = 0.0;
                }
            } else if (estimatedMspt == 0.0) {
                estimatedMspt = currentEstimate;
            } else {
                estimatedMspt = estimatedMspt * 0.45 + currentEstimate * 0.55;
            }
        }

        private double getEstimatedMspt() {
            return Math.max(0.0, estimatedMspt);
        }

        private double getActivityPercent() {
            return activityPercent;
        }

        private long getWindowSamples() {
            return lastWindowSamples;
        }

        private RiskLevel getRiskLevel() {
            if (estimatedMspt >= 8.0) return RiskLevel.CRITICAL;
            if (estimatedMspt >= 4.0 || activityPercent >= 35.0) return RiskLevel.HIGH;
            if (estimatedMspt >= 1.5 || activityPercent >= 15.0) return RiskLevel.MODERATE;
            return RiskLevel.CLEAN;
        }
    }
}
