package me.nolag.modules.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class CommandSecurityModule implements Listener {

    private final NoLag plugin;
    private final Map<UUID, Long> lastWarningTime = new ConcurrentHashMap<>();

    public CommandSecurityModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("advanced-optimization.command-filter.enabled", true)) {
            return;
        }

        Player player = event.getPlayer();
        if (player.hasPermission("nolag.unsafe.bypass") || player.hasPermission("nolag.commandfilter.bypass")) {
            return;
        }

        String raw = event.getMessage().trim().toLowerCase(Locale.ROOT);
        if (!raw.startsWith("/")) {
            return;
        }


        String cleanCommand = raw.replaceFirst("^/+", "");
        String[] parts = cleanCommand.split("\\s+", 2);
        String cmdOnly = parts[0];


        String strippedCmd = cmdOnly.contains(":") ? cmdOnly.substring(cmdOnly.indexOf(':') + 1) : cmdOnly;

        List<String> blockedList = plugin.getConfig().getStringList("advanced-optimization.command-filter.blocked-commands");

        for (String blocked : blockedList) {
            String lowerBlocked = blocked.toLowerCase(Locale.ROOT).replace("/", "");
            if (cmdOnly.equals(lowerBlocked) || strippedCmd.equals(lowerBlocked)) {
                event.setCancelled(true);

                long now = System.currentTimeMillis();
                Long lastWarn = lastWarningTime.get(player.getUniqueId());
                boolean sendWarning = (lastWarn == null || (now - lastWarn) > 1500L);

                if (sendWarning) {
                    lastWarningTime.put(player.getUniqueId(), now);

                    String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                    String playerMsg = plugin.getConfig().getString(
                            "advanced-optimization.command-filter.message",
                            "&cThis command is restricted due to lag risk!"
                    );
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + playerMsg));

                    if (plugin.getConfig().getBoolean("advanced-optimization.command-filter.notify-admins", true)) {
                        String adminMsg = plugin.getConfig().getString(
                                "advanced-optimization.command-filter.admin-notify-message",
                                "&e[NoLag] Player &c{player} &etried to use restricted command: &c/{command}"
                        ).replace("{player}", player.getName()).replace("{command}", cmdOnly);

                        for (Player online : Bukkit.getOnlinePlayers()) {
                            if (online.hasPermission("nolag.commandfilter.notify")) {
                                online.sendMessage(ChatColor.translateAlternateColorCodes('&', adminMsg));
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onElytraFly(EntityToggleGlideEvent event) {
        if (!plugin.getConfig().getBoolean("advanced-optimization.elytra-protection.enabled", true)) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            if (player.hasPermission("nolag.elytra.bypass")) {
                return;
            }

            double threshold = plugin.getConfig().getDouble("advanced-optimization.elytra-protection.tps-threshold", 15.0);
            double currentTPS = plugin.getTPSMonitor().getTPS();

            if (currentTPS < threshold && event.isGliding()) {
                event.setCancelled(true);

                long now = System.currentTimeMillis();
                Long lastWarn = lastWarningTime.get(player.getUniqueId());
                if (lastWarn == null || (now - lastWarn) > 3000L) {
                    lastWarningTime.put(player.getUniqueId(), now);
                    String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                    String msg = plugin.getConfig().getString(
                            "advanced-optimization.elytra-protection.message",
                            "&cElytra is disabled due to low server performance! (TPS: {tps})"
                    ).replace("{tps}", String.format(Locale.ROOT, "%.1f", currentTPS));

                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + msg));
                }
            }
        }
    }
}

