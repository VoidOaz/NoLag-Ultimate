package me.nolag.modules.entity;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class EntityAIOptimizationModule implements Listener {

    private static final int DEFAULT_ENTITIES_PER_RUN = 500;

    private final NoLag plugin;
    private final NamespacedKey aiDisabledKey;
    private final Set<UUID> aiDisabledEntities = ConcurrentHashMap.newKeySet();

    private TaskHandle aiTask;
    private volatile boolean enabled = false;
    private volatile double activationRange = 48.0;
    private volatile double rangeSq = 48.0 * 48.0;
    private volatile int entitiesPerRun = DEFAULT_ENTITIES_PER_RUN;
    private volatile double emergencyMSPT = 55.0;

    private int worldCursor;
    private int chunkCursor;

    public EntityAIOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
        this.aiDisabledKey = new NamespacedKey(plugin, "ai_disabled");
        reload();
        startTask();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("optimization.activation-range.enabled", false);
        this.activationRange = Math.max(8.0, cfg.getDouble("optimization.activation-range.range", 48.0));
        this.rangeSq = activationRange * activationRange;
        this.entitiesPerRun = Math.max(50, cfg.getInt("optimization.activation-range.entities-per-run", DEFAULT_ENTITIES_PER_RUN));
        this.emergencyMSPT = Math.max(1.0, cfg.getDouble("atlas.emergency-mspt", 55.0));
    }

    public void startTask() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
        worldCursor = 0;
        chunkCursor = 0;

        if (!enabled || plugin.getSchedulerAdapter().isFolia()) {
            restoreDisabledEntities();
            return;
        }

        aiTask = plugin.getSchedulerAdapter().runGlobalTimer(this::optimizeEntityAI, 200L, 200L);
    }

    public void stop() {
        if (aiTask != null) {
            aiTask.cancel();
            aiTask = null;
        }
        restoreDisabledEntities();
    }

    private boolean isProtected(LivingEntity le) {
        if (le == null || !le.isValid() || le.isDead()) return true;
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) return true;
        if (le instanceof Villager || le instanceof WanderingTrader || le instanceof Golem || le instanceof Allay) return true;
        if (le instanceof Tameable tameable && tameable.isTamed()) return true;
        if (le instanceof AbstractHorse horse && horse.isTamed()) return true;
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) return true;
        var stacker = plugin.getMobStackerModule();
        return le.getCustomName() != null && stacker != null && !stacker.hasStackData(le);
    }

    private void optimizeEntityAI() {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) return;

        double tps = plugin.getTPSMonitor().getTPS();
        boolean restoreOnly = !enabled || tps >= 18.0D;

        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) return;
        if (worldCursor >= worlds.size()) {
            worldCursor = 0;
            chunkCursor = 0;
        }

        int processed = 0;
        int chunksVisited = 0;
        int worldsVisited = 0;
        while (processed < entitiesPerRun && chunksVisited < 32 && worldsVisited < worlds.size()) {
            if (worldCursor >= worlds.size()) {
                worldCursor = 0;
                chunkCursor = 0;
            }

            World world = worlds.get(worldCursor);
            Chunk[] chunks;
            try {
                chunks = world.getLoadedChunks();
            } catch (Throwable ignored) {
                worldCursor = (worldCursor + 1) % worlds.size();
                chunkCursor = 0;
                worldsVisited++;
                continue;
            }

            if (chunkCursor >= chunks.length) {
                worldCursor = (worldCursor + 1) % worlds.size();
                chunkCursor = 0;
                worldsVisited++;
                continue;
            }

            // P-07: Snapshot player locations once per chunk instead of 3 API
            // calls for each mob/player pair.
            java.util.List<org.bukkit.Location> playerLocations = new java.util.ArrayList<>();
            for (Player player : world.getPlayers()) {
                if (player != null && player.isOnline()) playerLocations.add(player.getLocation());
            }
            Chunk chunk = chunks[chunkCursor++];
            chunksVisited++;
            if (chunk == null || !chunk.isLoaded()) continue;

            Entity[] entities;
            try {
                entities = chunk.getEntities();
            } catch (Throwable ignored) {
                continue;
            }

            for (Entity entity : entities) {
                if (processed >= entitiesPerRun) return;
                if (!(entity instanceof LivingEntity le)) continue;
                if (le.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) {
                    aiDisabledEntities.add(le.getUniqueId());
                    if (restoreOnly || isProtected(le)) restoreEntityAI(le);
                }
                if (restoreOnly) continue;
                if (isProtected(le)) continue;
                processed++;

                org.bukkit.Location eloc = le.getLocation();
                double ex = eloc.getX();
                double ey = eloc.getY();
                double ez = eloc.getZ();
                boolean playerNear = false;

                for (org.bukkit.Location playerLocation : playerLocations) {
                    double px = playerLocation.getX();
                    double py = playerLocation.getY();
                    double pz = playerLocation.getZ();
                    double dx = px - ex;
                    double dy = py - ey;
                    double dz = pz - ez;
                    if ((dx * dx + dy * dy + dz * dz) <= rangeSq) {
                        playerNear = true;
                        break;
                    }
                }

                if (playerNear) {
                    restoreEntityAI(le);
                } else if (le.hasAI()) {
                    disableEntityAI(le);
                }
            }
        }
    }

    private void disableEntityAI(LivingEntity entity) {
        UUID id = entity.getUniqueId();
        if (!aiDisabledEntities.add(id)) return;

        plugin.getSchedulerAdapter().runEntity(entity, () -> {
            if (!aiDisabledEntities.contains(id) || !entity.isValid() || entity.isDead()) return;
            try {
                if (entity.hasAI()) {
                    entity.setAI(false);
                    entity.getPersistentDataContainer().set(aiDisabledKey, PersistentDataType.BYTE, (byte) 1);
                }
            } catch (Throwable ignored) {
                aiDisabledEntities.remove(id);
            }
        });
    }

    private void restoreEntityAI(LivingEntity entity) {
        UUID id = entity.getUniqueId();
        if (!aiDisabledEntities.contains(id)
                && !entity.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) return;
        // This module is inactive on Folia. Paper callers are already on the main thread;
        // postponing this work can leave mobs without AI when the plugin shuts down.
        try {
            if (entity.isValid() && !entity.hasAI()
                    && entity.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) {
                entity.setAI(true);
            }
            entity.getPersistentDataContainer().remove(aiDisabledKey);
            aiDisabledEntities.remove(id);
        } catch (Throwable failure) {
            plugin.getLogger().warning("Could not restore AI for " + id + ": " + failure.getMessage());
        }
    }

    private void restoreDisabledEntities() {
        if (plugin.getSchedulerAdapter().isFolia()) return;

        // Include markers left by a cancelled disable task or an earlier plugin run.
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()
                                && living.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)) {
                            restoreEntityAI(living);
                        }
                    }
                } catch (RuntimeException failure) {
                    plugin.getLogger().warning("Could not inspect chunk for AI restoration: " + failure.getMessage());
                }
            }
        }
        for (UUID id : new java.util.ArrayList<>(aiDisabledEntities)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                restoreEntityAI(living);
            } else {
                aiDisabledEntities.remove(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof LivingEntity living
                    && living.getPersistentDataContainer().has(aiDisabledKey, PersistentDataType.BYTE)
                    && (!enabled || plugin.getSchedulerAdapter().isFolia() || plugin.getTPSMonitor().getTPS() >= 18.0D)) {
                restoreEntityAI(living);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        aiDisabledEntities.remove(event.getEntity().getUniqueId());
    }

}
