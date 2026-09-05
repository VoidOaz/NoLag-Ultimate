package me.nolag.modules.mobstacker;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class MobStackerModule implements Listener {

    private final NoLag plugin;
    private final NamespacedKey stackKey;
    private final NamespacedKey noStackKey;
    private BukkitTask task;

    private static final Set<EntityType> BLACKLISTED_TYPES = Set.of(
            EntityType.PLAYER,
            EntityType.ARMOR_STAND,
            EntityType.ENDER_DRAGON,
            EntityType.WITHER,
            EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN,
            EntityType.GIANT
    );

    public MobStackerModule(NoLag plugin) {
        this.plugin = plugin;
        this.stackKey = new NamespacedKey(plugin, "stack_amount");
        this.noStackKey = new NamespacedKey(plugin, "no_stack_temp");
        startTask();
    }

    public void startTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        if (!plugin.getConfig().getBoolean("features.mob-stacker.enabled", true)) {
            return;
        }

        int intervalMinutes = Math.max(1, plugin.getConfig().getInt("features.mob-stacker.check-interval", 3));
        long intervalTicks = intervalMinutes * 60L * 20L;

        this.task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.getConfig().getBoolean("features.mob-stacker.enabled", true)) {
                    return;
                }
                for (World world : Bukkit.getWorlds()) {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        if (chunk.isEntitiesLoaded()) {
                            performStacking(chunk);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 200L, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isEligible(Entity entity) {
        if (!(entity instanceof LivingEntity le) || !entity.isValid() || entity.isDead()) {
            return false;
        }
        if (BLACKLISTED_TYPES.contains(entity.getType())) {
            return false;
        }
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) {
            return false;
        }
        if (le instanceof Villager || le instanceof WanderingTrader) {
            return false;
        }
        if (le instanceof Tameable tameable && tameable.isTamed()) {
            return false;
        }
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) {
            return false;
        }
        // If entity has temporary no-stack tag (e.g. freshly unstacked)
        if (le.getPersistentDataContainer().has(noStackKey, PersistentDataType.BYTE)) {
            return false;
        }
        // If entity has a custom name from nametag and is not a NoLag stack, don't stack it
        if (le.getCustomName() != null && !hasStackData(le)) {
            return false;
        }
        return true;
    }

    public void performStacking(Chunk chunk) {
        int minMobs = plugin.getConfig().getInt("features.mob-stacker.min-mobs-to-stack", 8);
        int maxStackSize = plugin.getConfig().getInt("features.mob-stacker.max-stack-size", 50);
        double radius = plugin.getConfig().getDouble("features.mob-stacker.radius", 5.0);
        double radiusSq = radius * radius;

        Entity[] entities = chunk.getEntities();
        if (entities.length < minMobs) {
            return;
        }

        Map<EntityType, LivingEntity> representativeMap = new HashMap<>();
        Map<EntityType, Integer> countMap = new HashMap<>();

        for (Entity entity : entities) {
            if (!isEligible(entity)) {
                continue;
            }

            LivingEntity le = (LivingEntity) entity;
            EntityType type = entity.getType();

            if (!representativeMap.containsKey(type)) {
                representativeMap.put(type, le);
                countMap.put(type, getStackAmount(le));
            } else {
                LivingEntity rep = representativeMap.get(type);
                if (rep.isValid() && !rep.isDead() && rep.getLocation().distanceSquared(le.getLocation()) <= radiusSq) {
                    int currentCount = countMap.getOrDefault(type, 1);
                    if (currentCount < maxStackSize) {
                        int leAmount = getStackAmount(le);
                        int space = maxStackSize - currentCount;
                        int transfer = Math.min(leAmount, space);
                        countMap.put(type, currentCount + transfer);

                        if (transfer >= leAmount) {
                            le.remove();
                        } else {
                            setStackAmount(le, leAmount - transfer);
                        }
                    }
                }
            }
        }

        for (Map.Entry<EntityType, LivingEntity> entry : representativeMap.entrySet()) {
            LivingEntity rep = entry.getValue();
            if (rep.isValid() && !rep.isDead()) {
                setStackAmount(rep, Math.min(maxStackSize, countMap.get(entry.getKey())));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!plugin.getConfig().getBoolean("features.mob-stacker.enabled", true)) {
            return;
        }

        Entity spawned = event.getEntity();
        if (!isEligible(spawned)) {
            return;
        }

        LivingEntity le = (LivingEntity) spawned;
        int maxStackSize = plugin.getConfig().getInt("features.mob-stacker.max-stack-size", 50);
        double radius = plugin.getConfig().getDouble("features.mob-stacker.radius", 5.0);

        for (Entity nearby : le.getNearbyEntities(radius, radius, radius)) {
            if (nearby.getType() == le.getType() && isEligible(nearby)) {
                LivingEntity nearbyLe = (LivingEntity) nearby;
                // Check age compatibility
                if (le instanceof Ageable ageA && nearbyLe instanceof Ageable ageB && (ageA.isAdult() != ageB.isAdult())) {
                    continue;
                }
                int currentAmount = getStackAmount(nearbyLe);
                if (currentAmount < maxStackSize) {
                    setStackAmount(nearbyLe, currentAmount + 1);
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!hasStackData(entity)) {
            return;
        }

        int amount = getStackAmount(entity);
        if (amount > 1) {
            int remaining = amount - 1;
            // Spawn next mob in stack on the next tick to prevent death chain recursion
            EntityType type = entity.getType();
            var loc = entity.getLocation();

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (loc.getWorld() == null || !loc.getChunk().isLoaded()) {
                    return;
                }
                LivingEntity nextMob = (LivingEntity) loc.getWorld().spawn(loc, type.getEntityClass(), spawned -> {
                    if (spawned instanceof LivingEntity living) {
                        living.getPersistentDataContainer().set(noStackKey, PersistentDataType.BYTE, (byte) 1);
                        // Preserve characteristics
                        if (entity instanceof Ageable oldAge && living instanceof Ageable newAge) {
                            if (oldAge.isAdult()) newAge.setAdult(); else newAge.setBaby();
                        }
                        if (entity instanceof Sheep oldSheep && living instanceof Sheep newSheep) {
                            newSheep.setColor(oldSheep.getColor());
                        }
                        if (entity instanceof Slime oldSlime && living instanceof Slime newSlime) {
                            newSlime.setSize(oldSlime.getSize());
                        }
                    }
                });

                if (nextMob != null) {
                    setStackAmount(nextMob, remaining);
                    // Remove temporary no-stack tag after a brief delay
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (nextMob.isValid()) {
                            nextMob.getPersistentDataContainer().remove(noStackKey);
                        }
                    }, 40L);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("nolag.unstack")) {
            return;
        }

        if (event.getRightClicked() instanceof LivingEntity le && isEligible(le)) {
            int amount = getStackAmount(le);
            if (amount > 1) {
                event.setCancelled(true);
                setStackAmount(le, amount - 1);

                EntityType type = le.getType();
                var loc = le.getLocation().add(0.5, 0, 0.5);

                LivingEntity separated = (LivingEntity) le.getWorld().spawn(loc, type.getEntityClass(), spawned -> {
                    if (spawned instanceof LivingEntity living) {
                        living.getPersistentDataContainer().set(noStackKey, PersistentDataType.BYTE, (byte) 1);
                        if (le instanceof Ageable oldAge && living instanceof Ageable newAge) {
                            if (oldAge.isAdult()) newAge.setAdult(); else newAge.setBaby();
                        }
                    }
                });

                if (separated != null) {
                    setStackAmount(separated, 1);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (separated.isValid()) {
                            separated.getPersistentDataContainer().remove(noStackKey);
                        }
                    }, 100L);
                }

                String msg = plugin.getConfig().getString(
                        "messages.mob-separated",
                        "&8[&6NoLag&8] &7- One mob separated! Remaining: &e%amount%"
                ).replace("%amount%", String.valueOf(amount - 1));

                player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            }
        }
    }

    public void setStackAmount(LivingEntity entity, int amount) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (amount <= 1) {
            pdc.remove(stackKey);
            entity.setCustomName(null);
            entity.setCustomNameVisible(false);
        } else {
            pdc.set(stackKey, PersistentDataType.INTEGER, amount);
            String rawType = entity.getType().name().replace("_", " ").toLowerCase(Locale.ROOT);
            String[] words = rawType.split("\\s+");
            StringBuilder formatted = new StringBuilder();
            for (String word : words) {
                if (!word.isEmpty()) {
                    formatted.append(Character.toUpperCase(word.charAt(0)))
                            .append(word.substring(1))
                            .append(" ");
                }
            }
            entity.setCustomName(ChatColor.translateAlternateColorCodes('&', "&b&l" + formatted.toString().trim() + " &e&lX" + amount));
            entity.setCustomNameVisible(true);
        }
    }

    public int getStackAmount(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        Integer val = pdc.get(stackKey, PersistentDataType.INTEGER);
        return val != null ? val : 1;
    }

    public boolean hasStackData(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(stackKey, PersistentDataType.INTEGER);
    }
}

