package me.nolag.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import me.nolag.NoLag;
import me.nolag.modules.ram.RamFixerModule;
import me.nolag.tps.TPSMonitor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class NoLagCommand implements CommandExecutor, TabCompleter {

    private final NoLag plugin;

    public NoLagCommand(NoLag plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender == null) {
            return true;
        }

        String prefix = color(plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] &r"));
        String noPerm = color(plugin.getConfig().getString("messages.no-permission", "&cYou do not have permission to use this command!"));

        if (args == null || args.length == 0) {
            double tps = plugin.getTPSMonitor().getTPS();
            double mspt = plugin.getTPSMonitor().getMSPT();
            String tpsColor = tps >= 18.0 ? "&a" : (tps >= 15.0 ? "&e" : "&c");

            sender.sendMessage(color("&8&m----------------------------------------"));
            sender.sendMessage(color("&6&lNoLag-Ultimate &7v" + plugin.getDescription().getVersion() + " &8| &eServer Optimizer"));
            sender.sendMessage(color("&7• Live TPS: " + tpsColor + String.format(Locale.ROOT, "%.2f", tps) + " &7| MSPT: &f" + String.format(Locale.ROOT, "%.2f", mspt) + "ms"));
            sender.sendMessage(color("&7• Commands: &f/nolag <reload|cleanup|ramfix|tps|dashboard|stats|performance|items|plugins|autosave|netty|version>"));
            sender.sendMessage(color("&8&m----------------------------------------"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                if (!sender.hasPermission("nolag.reload")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                if (plugin.getSchedulerAdapter().isFolia()) {


                    plugin.getSchedulerAdapter().runGlobal(() -> {
                        plugin.reloadPluginConfig();
                        String success = prefix + color(plugin.getConfig().getString(
                                "messages.reload-success", "&aConfiguration successfully reloaded!"));
                        if (sender instanceof Player player) {
                            plugin.getSchedulerAdapter().runEntity(player, () -> player.sendMessage(success));
                        } else {
                            sender.sendMessage(success);
                        }
                    });
                } else {
                    plugin.reloadPluginConfig();
                    sender.sendMessage(prefix + color(plugin.getConfig().getString(
                            "messages.reload-success", "&aConfiguration successfully reloaded!")));
                }
                break;

            case "cleanup":
                if (!sender.hasPermission("nolag.cleanup")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                var expiry = plugin.getItemExpiryModule();
                int queued = expiry != null ? expiry.requestCleanup() : 0;
                sender.sendMessage(prefix + color("&eCleanup queued " + queued + " expired tracked items for entity-safe removal."));
                sender.sendMessage(prefix + color("&7Status: " + (expiry != null && expiry.isEnabled() ? "&aActive" : "&cDisabled")
                        + " &8| &7Item Countdown: &e" + (expiry != null ? expiry.getConfiguredLifetimeSeconds() : 180) + "s"
                        + " &8| &7Tracked: &b" + (expiry != null ? expiry.getTrackedItemCount() : 0) + " items"));
                break;

            case "ram":
            case "ramfix":
            case "ramfixer":
            case "memory":
                if (!sender.hasPermission("nolag.admin")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                boolean started = plugin.getRamFixerModule().startManualSweep(result -> {
                    Runnable report = () -> {
                        if (result == null) sender.sendMessage(prefix + color("&cRamFixer sweep failed. Check the server log."));
                        else sendRamFixSummary(sender, result);
                    };
                    if (plugin.getSchedulerAdapter().isFolia()) {
                        if (sender instanceof Player target) {
                            plugin.getSchedulerAdapter().runEntity(target, report);
                        } else {
                            plugin.getSchedulerAdapter().runGlobal(report);
                        }
                    } else {
                        report.run();
                    }
                });
                if (!started) {
                    sender.sendMessage(prefix + color("&cRamFixer is disabled or a sweep is already running. Check configuration or try again later."));
                } else {
                    sender.sendMessage(prefix + color("&eRamFixer sweep started. Heap usage may fluctuate while the server runs."));
                }
                break;

            case "tps":
                if (!sender.hasPermission("nolag.tps")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                double currentTPS = plugin.getTPSMonitor().getTPS();
                double currentMSPT = plugin.getTPSMonitor().getMSPT();
                String tColor = currentTPS >= 18.0 ? "&a" : (currentTPS >= 15.0 ? "&e" : "&c");
                String mColor = currentMSPT < 40.0 ? "&a" : (currentMSPT < 50.0 ? "&e" : "&c");
                sender.sendMessage(prefix + color("Live TPS: " + tColor + String.format(Locale.ROOT, "%.2f", currentTPS) + " &7| MSPT: " + mColor + String.format(Locale.ROOT, "%.2f", currentMSPT) + "ms"));
                break;

            case "dashboard":
            case "gui":
                if (!sender.hasPermission("nolag.dashboard")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(prefix + color("&cThis command can only be executed by in-game players!"));
                    return true;
                }
                if (plugin.getSchedulerAdapter().isFolia()) {
                    plugin.getSchedulerAdapter().runEntity(player, () -> plugin.getDashboardGUI().open(player));
                } else {
                    plugin.getDashboardGUI().open(player);
                }
                break;

            case "performance":
            case "perf":
                if (!sender.hasPermission("nolag.performance")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lPerformance Impact Analyzer"));
                sender.sendMessage(color("&7TPS: &f" + String.format(Locale.ROOT, "%.2f", plugin.getTPSMonitor().getTPS()) + " &8| MSPT: &f" + String.format(Locale.ROOT, "%.2f", plugin.getTPSMonitor().getMSPT()) + "ms"));
                var samples = plugin.getPerformanceAnalyzer().snapshot();
                if (samples.isEmpty()) {
                    sender.sendMessage(color("&7No activity recorded yet."));
                } else {
                    for (var entry : samples.entrySet()) {
                        var sample = entry.getValue();
                        sender.sendMessage(color("&7• " + entry.getKey() + ": &e" + String.format(Locale.ROOT, "%.2f", sample.totalMs()) + "ms total &8| &f" + sample.calls() + " calls &8| est. &b" + String.format(Locale.ROOT, "%.2f", sample.estimatedMspt()) + "ms/tick (NoLag code only)"));
                    }
                }
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "items":
            case "item":
            case "itemlifetime":
            case "item-expiry":
                if (!sender.hasPermission("nolag.items")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                var itemModule = plugin.getItemExpiryModule();
                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lItem Lifetime Monitor"));
                sender.sendMessage(color("&7Status: " + (itemModule.isEnabled() ? "&aEnabled" : "&cDisabled")));
                sender.sendMessage(color("&7Lifetime: &f" + (itemModule.getConfiguredLifetimeSeconds() / 60.0D) + " min &8| &7Tracked: &e" + itemModule.getTrackedItemCount() + " dropped items"));
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "stats":
                if (!sender.hasPermission("nolag.admin")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                TPSMonitor.MemoryStats mem = TPSMonitor.getMemoryStats();
                int entities = 0;
                int chunks = 0;
                boolean folia = plugin.getSchedulerAdapter().isFolia();
                if (!folia) {
                    for (World w : Bukkit.getWorlds()) {
                        if (w == null) continue;
                        entities += TPSMonitor.getEntityCountSafe(w);
                        chunks += TPSMonitor.getLoadedChunkCount(w);
                    }
                }
                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lNoLag Real-time Server Diagnostics:"));
                sender.sendMessage(color("&7• RAM: &f" + mem.usedMb() + "MB / " + mem.maxMb() + "MB (&e" + String.format(Locale.ROOT, "%.1f%%", mem.usagePercent()) + "&7)"));
                sender.sendMessage(folia
                        ? color("&7• Entities/Chunks: &8world-wide counters disabled in Folia safe mode")
                        : color("&7• Entities: &e" + entities + " &7| Chunks: &e" + chunks));
                sender.sendMessage(color("&7• View Distance: &b" + plugin.getChunkModule().getCurrentViewDistance()
                        + " &8| &7Simulation Distance: &b" + plugin.getChunkModule().getCurrentSimulationDistance()));
                sender.sendMessage(color("&7• Chunk Pressure: &e" + plugin.getChunkModule().getPressureLevel() + "/8"
                        + " &8| &7Native chunk loads/unloads observed: &e"
                        + plugin.getChunkModule().getChunkLoadEvents() + "/" + plugin.getChunkModule().getChunkUnloadEvents()));
                sender.sendMessage(color("&7• Existing-chunk prefetch: &e" + plugin.getChunkModule().getPrefetchCompletions()
                        + " completed &8| &e" + plugin.getChunkModule().getPrefetchInFlight() + " pending"));
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "plugins":
            case "plugin":
            case "lagwatch":
            case "inspect":
                if (!sender.hasPermission("nolag.admin") && !sender.hasPermission("nolag.plugins")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                var watcher = plugin.getPluginWatcherModule();
                if (watcher == null || !watcher.isEnabled()) {
                    sender.sendMessage(prefix + color("&cPlugin Lag Watcher is disabled in configuration."));
                    return true;
                }
                var topList = watcher.getTopLaggingPlugins();
                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lPlugin Performance & Lag Inspector:"));
                sender.sendMessage(color("&7Server: &b" + String.format(Locale.ROOT, "%.2f", plugin.getTPSMonitor().getTPS())
                        + " TPS &8| &b" + String.format(Locale.ROOT, "%.2f", plugin.getTPSMonitor().getMSPT())
                        + " MSPT &8| &7Stack samples: &e" + watcher.getTotalStackSamples()));
                sender.sendMessage(color(plugin.getSchedulerAdapter().isFolia()
                        ? "&8Estimated impact uses global-region stack samples and available listener timings."
                        : "&8Estimated MSPT impact uses asynchronous main-thread stack sampling and available listener timings."));
                if (watcher.getTotalStackSamples() < 4L) {
                    sender.sendMessage(color("&eThe profiler is warming up; allow several sampling intervals for useful estimates."));
                }
                if (topList.isEmpty()) {
                    sender.sendMessage(color("&7No enabled third-party plugins are available to inspect."));
                } else {
                    for (int i = 0; i < Math.min(10, topList.size()); i++) {
                        var prof = topList.get(i);
                        String risk = color(prof.riskLevel().getDisplay());
                        sender.sendMessage(color(String.format(Locale.ROOT,
                                "&7• &f%s &8(v%s): &e~%.2f ms estimated MSPT &8| &7Activity: &b%.1f%% &8| &7Listeners: &e%d &8| %s",
                                prof.name(), prof.version(), prof.msptImpact(), prof.activityPercent(), prof.listenerCount(), risk)));
                    }
                }
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "autosave":
                if (!sender.hasPermission("nolag.admin")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                sender.sendMessage(prefix + color("&6Autosave Optimizer: &f" + plugin.getAutosaveOptimizerModule().getStatusSummary()));
                break;

            case "netty":
            case "network":
            case "packet":
                if (!sender.hasPermission("nolag.netty") && !sender.hasPermission("nolag.admin")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                var netty = plugin.getNettyProtectionModule();
                if (netty == null) {
                    sender.sendMessage(prefix + color("&cNetty Protection Module is not loaded."));
                    return true;
                }

                if (args.length > 1) {
                    String sub = args[1].toLowerCase(Locale.ROOT);
                    if (sub.equals("reset")) {
                        if (!sender.hasPermission("nolag.netty.admin") && !sender.hasPermission("nolag.admin")) {
                            sender.sendMessage(noPerm);
                            return true;
                        }
                        netty.resetStatistics();
                        sender.sendMessage(prefix + color("&aNetty Protection statistics have been reset!"));
                        return true;
                    } else if (sub.equals("reload")) {
                        if (!sender.hasPermission("nolag.netty.admin") && !sender.hasPermission("nolag.admin")) {
                            sender.sendMessage(noPerm);
                            return true;
                        }
                        netty.reload();
                        sender.sendMessage(prefix + color("&aNetty Protection configuration reloaded!"));
                        return true;
                    }
                }

                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lNetty Enterprise Network & Packet Protection"));
                sender.sendMessage(color("&7• Status: " + (netty.isEnabled() ? "&aActive (Asynchronous Worker Pipeline)" : "&cDisabled")));
                sender.sendMessage(color("&7• Guarded Player Connections: &b" + netty.getActiveGuardedChannelCount()));
                sender.sendMessage(color("&7• Full injection failures (lifetime): &c" + netty.getUnprotectedChannelsCount()));
                sender.sendMessage(color("&7• Degraded early-guard-only channels: &e" + netty.getActiveDegradedChannelCount()));
                sender.sendMessage(color("&7• Crash payload inspection: &eheuristic, not exhaustive"));
                sender.sendMessage(color("&7• Inspected Packets: &e" + netty.getTotalPacketsInspected()));
                sender.sendMessage(color("&7• Blocked Flood Packets: &c" + netty.getTotalFloodPacketsBlocked()));
                sender.sendMessage(color("&7• Blocked Crash / Exploit Packets: &c" + netty.getTotalExploitsBlocked()));
                sender.sendMessage(color("&7• Throttled Bot Handshake Bursts: &c" + netty.getTotalBotBurstsBlocked()));
                sender.sendMessage(color("&7• Rejected Connection-Flood Sockets: &c" + netty.getTotalConnectionFloodsBlocked()
                        + " &8(limit " + netty.getMaxConcurrentConnectionsPerIp() + "/ip, " + netty.getTrackedConcurrentConnectionIpCount() + " IPs tracked)"));
                sender.sendMessage(color("&7• Packet Rate Limit: &f" + netty.getMaxPacketRatePerSecond() + "/s &8(burst " + netty.getPacketRateBurst() + "/s)"));
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "version":
                sender.sendMessage(prefix + color("&7NoLag-Ultimate Version &a" + plugin.getDescription().getVersion()));
                sender.sendMessage(prefix + color("&7Running on Minecraft &b" + plugin.getVersionChecker().getServerVersionString() + " &7(" + me.nolag.platform.PlatformDetector.getPlatformName(me.nolag.platform.PlatformDetector.detect()) + ")"));
                break;

            default:
                sender.sendMessage(prefix + color("&cUnknown subcommand. Use: &f/nolag <reload|cleanup|ramfix|tps|dashboard|stats|performance|items|plugins|autosave|netty|version>"));
                break;
        }

        return true;
    }

    private void sendRamFixSummary(CommandSender sender, RamFixerModule.RamFixResult result) {
        sender.sendMessage(color("&8&m----------------------------------------"));
        sender.sendMessage(color("&6&lRamFixer Optimization Summary:"));
        sender.sendMessage(color("&7• Heap Before: &c" + result.usedBeforeMb() + "MB &7/ &f" + result.maxMb() + "MB (&e" + String.format(Locale.ROOT, "%.1f%%", result.percentBefore()) + "&7)"));
        sender.sendMessage(color("&7• Heap After:  &a" + result.usedAfterMb() + "MB &7/ &f" + result.maxMb() + "MB (&e" + String.format(Locale.ROOT, "%.1f%%", result.percentAfter()) + "&7)"));
        sender.sendMessage(color("&7• Observed Heap Decrease: &a" + result.freedMb() + "MB &7| GC Request: " + (result.gcTriggered() ? "&aIssued" : "&7Skipped")));
        sender.sendMessage(color("&7• Deep Sweep: &e" + result.chunksScannedInFlight()
                + " &7chunks inspected, &e" + result.chunksUnloadedThisSweep()
                + " &7unloaded, &e" + result.deadEntitiesRemovedThisSweep() + " &7dead items removed"));
        if (result.freedMb() == 0L && result.chunksUnloadedThisSweep() == 0
                && result.deadEntitiesRemovedThisSweep() == 0) {
            sender.sendMessage(color("&7• Result: &eNo reclaimable heap/chunk candidates were found in this pass."));
        } else if (result.freedMb() == 0L) {
            sender.sendMessage(color("&7• Result: &eCleanup work completed; heap usage did not show a net decrease in this sample."));
        }
        sender.sendMessage(color("&7• Lifetime: &a" + plugin.getRamFixerModule().getTotalFreedMb() + "MB observed heap decrease &8| &e" + plugin.getRamFixerModule().getAutoSweepCount()
                + " auto sweeps &8| &e" + plugin.getRamFixerModule().getGhostChunksUnloaded() + " ghost chunks unloaded &8| &e"
                + plugin.getRamFixerModule().getDeadEntitiesRemoved() + " dead refs swept"));
        sender.sendMessage(color("&8&m----------------------------------------"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("nolag.reload")) completions.add("reload");
            if (sender.hasPermission("nolag.cleanup")) completions.add("cleanup");
            if (sender.hasPermission("nolag.admin")) completions.add("ramfix");
            if (sender.hasPermission("nolag.tps")) completions.add("tps");
            if (sender.hasPermission("nolag.dashboard")) completions.add("dashboard");
            if (sender.hasPermission("nolag.performance")) completions.add("performance");
            if (sender.hasPermission("nolag.items")) completions.add("items");
            if (sender.hasPermission("nolag.admin") || sender.hasPermission("nolag.plugins")) completions.add("plugins");
            if (sender.hasPermission("nolag.admin")) completions.add("stats");
            if (sender.hasPermission("nolag.admin")) completions.add("autosave");
            if (sender.hasPermission("nolag.netty") || sender.hasPermission("nolag.admin")) completions.add("netty");
            completions.add("version");

            String query = args[0].toLowerCase(Locale.ROOT);
            return completions.stream().filter(s -> s.startsWith(query)).toList();
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("netty") || args[0].equalsIgnoreCase("network"))) {
            List<String> sub = new ArrayList<>();
            sub.add("status");
            sub.add("stats");
            if (sender.hasPermission("nolag.netty.admin") || sender.hasPermission("nolag.admin")) {
                sub.add("reset");
                sub.add("reload");
            }
            String query = args[1].toLowerCase(Locale.ROOT);
            return sub.stream().filter(s -> s.startsWith(query)).toList();
        }
        return Collections.emptyList();
    }

    private String color(String msg) {
        return msg == null ? "" : ChatColor.translateAlternateColorCodes('&', msg);
    }
}
