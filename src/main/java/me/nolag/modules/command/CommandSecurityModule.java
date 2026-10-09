package me.nolag.modules.command;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.event.player.PlayerQuitEvent;

public final class CommandSecurityModule implements Listener {

    private final NoLag plugin;
    private final Map<UUID, Long> lastCommandWarningTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastElytraWarningTime = new ConcurrentHashMap<>();

    private volatile boolean commandFilterEnabled = true;
    private volatile boolean notifyAdmins = true;
    private volatile String commandFilterMessage = "&cThis command is restricted due to lag risk!";
    private volatile String adminNotifyMessage = "&e[NoLag] Player &c{player} &etried to use restricted command: &c/{command}";
    private final Set<String> blockedCommands = new HashSet<>();

    private volatile boolean elytraProtectionEnabled = true;
    private volatile double elytraTpsThreshold = 15.0;
    private volatile String elytraMessage = "&cElytra is disabled due to low server performance! (TPS: {tps})";

    public CommandSecurityModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.commandFilterEnabled = cfg.getBoolean("advanced-optimization.command-filter.enabled", true);
        this.notifyAdmins = cfg.getBoolean("advanced-optimization.command-filter.notify-admins", true);
        this.commandFilterMessage = cfg.getString(
                "advanced-optimization.command-filter.message",
                "&cThis command is restricted due to lag risk!"
        );
        this.adminNotifyMessage = cfg.getString(
                "advanced-optimization.command-filter.admin-notify-message",
                "&e[NoLag] Player &c{player} &etried to use restricted command: &c/{command}"
        );

        this.blockedCommands.clear();
        List<String> rawBlocked = cfg.getStringList("advanced-optimization.command-filter.blocked-commands");
        for (String cmd : rawBlocked) {
            if (cmd != null && !cmd.isBlank()) {
                String clean = cmd.trim().toLowerCase(Locale.ROOT).replace("/", "");
                blockedCommands.add(clean);
                if (clean.contains(":")) {
                    blockedCommands.add(clean.substring(clean.indexOf(':') + 1));
                }
            }
        }

        this.elytraProtectionEnabled = cfg.getBoolean("advanced-optimization.elytra-protection.enabled", true);
        this.elytraTpsThreshold = cfg.getDouble("advanced-optimization.elytra-protection.tps-threshold", 15.0);
        this.elytraMessage = cfg.getString(
                "advanced-optimization.elytra-protection.message",
                "&cElytra is disabled due to low server performance! (TPS: {tps})"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastCommandWarningTime.remove(id);
        lastElytraWarningTime.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (!commandFilterEnabled) {
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

        // Fast slash trimming
        int start = 0;
        while (start < raw.length() && raw.charAt(start) == '/') {
            start++;
        }
        if (start >= raw.length()) {
            return;
        }

        int spaceIdx = raw.indexOf(' ', start);
        String cmdOnly = spaceIdx > 0 ? raw.substring(start, spaceIdx) : raw.substring(start);
        String strippedCmd = cmdOnly.contains(":") ? cmdOnly.substring(cmdOnly.indexOf(':') + 1) : cmdOnly;

        if (blockedCommands.contains(cmdOnly) || blockedCommands.contains(strippedCmd)) {
            event.setCancelled(true);

            long now = System.currentTimeMillis();
            Long lastWarn = lastCommandWarningTime.get(player.getUniqueId());
            boolean sendWarning = (lastWarn == null || (now - lastWarn) > 1500L);

            if (sendWarning) {
                lastCommandWarningTime.put(player.getUniqueId(), now);

                String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + commandFilterMessage));

                if (notifyAdmins) {
                    String adminMsg = adminNotifyMessage
                            .replace("{player}", player.getName())
                            .replace("{command}", cmdOnly);
                    String formatted = ChatColor.translateAlternateColorCodes('&', adminMsg);

                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.hasPermission("nolag.commandfilter.notify")) {
                            online.sendMessage(formatted);
                        }
                    }
                    plugin.getLogger().warning("[CommandFilter] Blocked restricted command /" + cmdOnly + " by " + player.getName());
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onElytraFly(EntityToggleGlideEvent event) {
        if (!elytraProtectionEnabled) {
            return;
        }

        if (event.getEntity() instanceof Player player) {
            if (player.hasPermission("nolag.elytra.bypass")) {
                return;
            }

            double currentTPS = plugin.getTPSMonitor().getTPS();
            if (currentTPS < elytraTpsThreshold && event.isGliding()) {
                event.setCancelled(true);

                long now = System.currentTimeMillis();
                Long lastWarn = lastElytraWarningTime.get(player.getUniqueId());
                if (lastWarn == null || (now - lastWarn) > 3000L) {
                    lastElytraWarningTime.put(player.getUniqueId(), now);
                    String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                    String msg = elytraMessage.replace("{tps}", String.format(Locale.ROOT, "%.1f", currentTPS));

                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + msg));
                }
            }
        }
    }
}
