package me.nolag.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import me.nolag.NoLag;
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
        String prefix = color(plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] &r"));
        String noPerm = color(plugin.getConfig().getString("messages.no-permission", "&cYou do not have permission to use this command!"));

        if (args.length == 0) {
            double tps = plugin.getTPSMonitor().getTPS();
            double mspt = plugin.getTPSMonitor().getMSPT();
            String tpsColor = tps >= 18.0 ? "&a" : (tps >= 15.0 ? "&e" : "&c");

            sender.sendMessage(color("&8&m----------------------------------------"));
            sender.sendMessage(color("&6&lNoLag-Ultimate &7v" + plugin.getDescription().getVersion() + " &8| &eServer Optimizer"));
            sender.sendMessage(color("&7• Live TPS: " + tpsColor + String.format(Locale.ROOT, "%.2f", tps) + " &7| MSPT: &f" + String.format(Locale.ROOT, "%.2f", mspt) + "ms"));
            sender.sendMessage(color("&7• Commands: &f/nolag <reload|cleanup|tps|dashboard|stats|version>"));
            sender.sendMessage(color("&8&m----------------------------------------"));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload":
                if (!sender.hasPermission("nolag.reload")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                plugin.reloadPluginConfig();
                sender.sendMessage(prefix + color(plugin.getConfig().getString("messages.reload-success", "&aConfiguration successfully reloaded!")));
                break;

            case "cleanup":
                if (!sender.hasPermission("nolag.cleanup")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                var res = plugin.getCleanupManager().performCleanup(false);
                sender.sendMessage(prefix + color("&aManual cleanup completed! Removed &e" + res.items() + " items &aand &e" + res.mobs() + " mobs."));
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
                plugin.getDashboardGUI().open(player);
                break;

            case "stats":
                if (!sender.hasPermission("nolag.admin")) {
                    sender.sendMessage(noPerm);
                    return true;
                }
                TPSMonitor.MemoryStats mem = TPSMonitor.getMemoryStats();
                int entities = 0;
                int chunks = 0;
                for (World w : Bukkit.getWorlds()) {
                    entities += w.getEntityCount();
                    chunks += w.getLoadedChunks().length;
                }
                sender.sendMessage(color("&8&m----------------------------------------"));
                sender.sendMessage(color("&6&lNoLag Real-time Server Diagnostics:"));
                sender.sendMessage(color("&7• RAM: &f" + mem.usedMb() + "MB / " + mem.maxMb() + "MB (&e" + String.format(Locale.ROOT, "%.1f%%", mem.usagePercent()) + "&7)"));
                sender.sendMessage(color("&7• Entities: &e" + entities + " &7| Chunks: &e" + chunks));
                sender.sendMessage(color("&7• View Distance: &b" + plugin.getChunkModule().getCurrentViewDistance()));
                sender.sendMessage(color("&8&m----------------------------------------"));
                break;

            case "version":
                sender.sendMessage(prefix + color("&7NoLag-Ultimate Version &a" + plugin.getDescription().getVersion()));
                sender.sendMessage(prefix + color("&7Running on Minecraft &b" + plugin.getVersionChecker().getServerVersionString() + " &7(Paper/Folia Optimized)"));
                break;

            default:
                sender.sendMessage(prefix + color("&cUnknown subcommand. Use: &f/nolag <reload|cleanup|tps|dashboard|stats|version>"));
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            if (sender.hasPermission("nolag.reload")) completions.add("reload");
            if (sender.hasPermission("nolag.cleanup")) completions.add("cleanup");
            if (sender.hasPermission("nolag.tps")) completions.add("tps");
            if (sender.hasPermission("nolag.dashboard")) completions.add("dashboard");
            if (sender.hasPermission("nolag.admin")) completions.add("stats");
            completions.add("version");

            String query = args[0].toLowerCase(Locale.ROOT);
            return completions.stream().filter(s -> s.startsWith(query)).toList();
        }
        return Collections.emptyList();
    }

    private String color(String msg) {
        return msg == null ? "" : ChatColor.translateAlternateColorCodes('&', msg);
    }
}

