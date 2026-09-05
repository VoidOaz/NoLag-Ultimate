package me.nolag.modules.entity;

import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class EntityAIOptimizationModule implements Listener {

    private final NoLag plugin;
    private final NamespacedKey aiDisabledKey;
    private BukkitTask aiTask;

    public EntityAIOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        this.aiDisabledKey = new NamespacedKey(plugin, "ai_disabled");
        startTask();
    }

    public void startTask() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }

        this.aiTask = new BukkitRunnable() {
            @Override
            public void run() {
                optimizeEntityAI();
            }
        }.runTaskTimer(plugin, 200L, 200L); // Every 10 seconds
    }

    public void stop() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
    }

    private boolean isProtected(LivingEntity le) {
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) {
            return true;
        }
        if (le instanceof Villager || le instanceof WanderingTrader || le instanceof Golem || le instanceof Allay) {
            return true;
        }
        if (le instanceof Tameable tameable && tameable.isTamed()) {
            return true;
        }
        if (le instanceof AbstractHorse horse && horse.isTamed()) {
            return true;
        }
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) {
            return true;
        }
        if (le.getCustomName() != null && !plugin.getMobStackerModule().hasStackData(le)) {
            return true;
        }
        return false;
    }

    private void optimizeEntityAI() {
        if (!plugin.getConfig().getBoolean("optimization.activation-range.enabled", true)) {
            return;
        }

        double tps = plugin.getTPSMonitor().getTPS();
        double activationRange = plugin.getConfig().getDouble("optimization.activation-range.range", 48.0);
        double rangeSq = activationRange * activationRange;

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity le : world.getLivingEntities()) {
                if (isProtected(le) || !le.isValid() || le.isDead()) {
                    continue;
                }

                // If TPS is healthy (> 18.0), restore AI ONLY if disabled by NoLag
                if (tps >= 18.0) {
                    if (le.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) {
                        le.setAI(true);
                        le.getPersistentDataContainer().remove(aiDisabledKey);
                    }
                    continue;
                }

                // Check distance to nearest player
                boolean playerNear = false;
                for (Player player : world.getPlayers()) {
                    if (player.getLocation().distanceSquared(le.getLocation()) <= rangeSq) {
                        playerNear = true;
                        break;
                    }
                }

                if (!playerNear) {
                    if (le.hasAI()) {
                        le.setAI(false);
                        le.getPersistentDataContainer().set(aiDisabledKey, PersistentDataType.BYTE, (byte) 1);
                    }
                } else {
                    if (le.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) {
                        le.setAI(true);
                        le.getPersistentDataContainer().remove(aiDisabledKey);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEmergencySpawnCheck(EntitySpawnEvent event) {
        double emergencyMSPT = plugin.getConfig().getDouble("atlas.emergency-mspt", 55.0);
        if (plugin.getTPSMonitor().getMSPT() > emergencyMSPT) {
            Entity entity = event.getEntity();
            if (entity instanceof LivingEntity le && !(entity instanceof Player) && !isProtected(le)) {
                event.setCancelled(true);
            }
        }
    }
}

