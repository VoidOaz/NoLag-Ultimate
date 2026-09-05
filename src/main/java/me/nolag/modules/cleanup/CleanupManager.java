package me.nolag.modules.cleanup;

import java.util.Locale;
import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class CleanupManager {

    private final NoLag plugin;
    private int countdownSeconds;
    private BukkitTask timerTask;
    private long lastEmergencyCleanupTime = 0L;

    public CleanupManager(NoLag plugin) {
        this.plugin = plugin;
        this.countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5)) * 60;
        startTimer();
    }

    public void startTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        this.countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5)) * 60;

        this.timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (countdownSeconds <= 0) {
                    performCleanup(false);
                    countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5)) * 60;
                } else {
                    checkAndBroadcast(countdownSeconds);

                    double tps = plugin.getTPSMonitor().getTPS();
                    double threshold = plugin.getConfig().getDouble("settings.tps-threshold", 16.0);
                    long cooldownMs = plugin.getConfig().getLong("settings.emergency-cleanup-cooldown", 60) * 1000L;
                    long now = System.currentTimeMillis();

                    // Emergency cleanup if TPS falls below threshold with cooldown guard
                    if (tps < threshold && countdownSeconds > 30 && (now - lastEmergencyCleanupTime >= cooldownMs)) {
                        lastEmergencyCleanupTime = now;
                        String warning = plugin.getConfig()
                                .getString("messages.low-tps-warning", "&4&lWARNING! &cLow TPS detected (&e%tps%&c). Emergency cleanup starting...")
                                .replace("%tps%", String.format(Locale.ROOT, "%.1f", tps));
                        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', warning));
                        performCleanup(true);
                        countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5)) * 60;
                    }

                    countdownSeconds--;
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    public void resetTimer() {
        this.countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5)) * 60;
    }

    private void checkAndBroadcast(int remaining) {
        int[] intervals = {300, 120, 60, 30, 10};
        String[] keys = {"five-minutes", "two-minutes", "one-minute", "thirty-seconds", "ten-seconds"};

        for (int i = 0; i < intervals.length; i++) {
            if (remaining == intervals[i]) {
                String msg = plugin.getConfig().getString("announcements." + keys[i], "");
                if (msg != null && !msg.isEmpty()) {
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
                break;
            }
        }
    }

    public CleanupResult performCleanup(boolean emergency) {
        int itemsRemoved = 0;
        int mobsRemoved = 0;

        boolean removeItems = plugin.getConfig().getBoolean("features.remove-items", true);
        boolean removeMobs = plugin.getConfig().getBoolean("features.remove-mobs", false) || emergency;

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (removeItems && entity instanceof Item item) {
                    item.remove();
                    itemsRemoved++;
                } else if (removeMobs && entity instanceof LivingEntity le) {
                    if (isEligibleForCleanup(le)) {
                        le.remove();
                        mobsRemoved++;
                    }
                }
            }
        }

        String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
        String logMsg = itemsRemoved + " items and " + mobsRemoved + " mobs cleared.";
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', prefix + "&fCleanup complete: &e" + logMsg));

        return new CleanupResult(itemsRemoved, mobsRemoved);
    }

    private boolean isEligibleForCleanup(LivingEntity le) {
        if (!le.isValid() || le.isDead()) {
            return false;
        }
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) {
            return false;
        }
        if (le instanceof Villager || le instanceof WanderingTrader || le instanceof Golem || le instanceof Allay) {
            return false;
        }
        if (le instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        if (le instanceof AbstractHorse horse && horse.isTamed()) {
            return false;
        }
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) {
            return false;
        }
        // If entity has a custom name (and is not a NoLag mob stack), preserve it
        if (le.getCustomName() != null && !plugin.getMobStackerModule().hasStackData(le)) {
            return false;
        }
        return true;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public record CleanupResult(int items, int mobs) {}
}

