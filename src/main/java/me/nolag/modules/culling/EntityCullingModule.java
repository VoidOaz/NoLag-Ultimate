package me.nolag.modules.culling;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class EntityCullingModule implements Listener {

    private final NoLag plugin;
    private final Map<UUID, Set<UUID>> hiddenEntitiesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> nearbyEntitiesByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> inspectionCursor = new ConcurrentHashMap<>();

    private TaskHandle cullingTask;
    private volatile boolean enabled = true;
    private volatile double maxDistance = 48.0;
    private volatile double safetyDistance = 8.0;
    private volatile double fovDotThreshold = 0.15;
    private volatile int tickInterval = 5;
    private volatile long totalCulled;

    public EntityCullingModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
        startTask();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("features.entity-culling.enabled", true);
        this.maxDistance = Math.max(8.0, cfg.getDouble("features.entity-culling.max-distance", 48.0));
        this.safetyDistance = Math.max(2.0, Math.min(maxDistance, cfg.getDouble("features.entity-culling.safety-distance", 8.0)));
        this.fovDotThreshold = Math.max(-1.0, Math.min(1.0, cfg.getDouble("features.entity-culling.fov-dot-threshold", 0.15)));
        this.tickInterval = Math.max(2, cfg.getInt("features.entity-culling.tick-interval", 5));
    }

    public void startTask() {
        if (cullingTask != null) {
            cullingTask.cancel();
            cullingTask = null;
        }
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) return;

        cullingTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::processCullingTick,
                20L,
                tickInterval
        );
    }

    public void stop() {
        if (cullingTask != null) {
            cullingTask.cancel();
            cullingTask = null;
        }
        restoreAllEntities();
        hiddenEntitiesByPlayer.clear();
        nearbyEntitiesByPlayer.clear();
        inspectionCursor.clear();
    }

    public boolean isEnabled() {
        return enabled && !plugin.getSchedulerAdapter().isFolia();
    }

    public long getTotalCulled() {
        return totalCulled;
    }

    private void processCullingTick() {
        if (!enabled || plugin.getSchedulerAdapter().isFolia()) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || !player.isOnline()) continue;
            // H-01 fix: Restore previously hidden entities if player is now in spectator mode
            if (player.getGameMode() == GameMode.SPECTATOR) {
                restoreForPlayer(player);
                continue;
            }

            UUID playerId = player.getUniqueId();
            Set<UUID> hidden = hiddenEntitiesByPlayer.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
            Set<UUID> currentNearby = nearbyEntitiesByPlayer.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet());
            currentNearby.clear();

            Location eye = player.getEyeLocation();
            Vector direction = eye.getDirection();
            World world = player.getWorld();

            // Rotate the bounded inspection window so every nearby entity is eventually checked.
            java.util.List<Entity> nearby = player.getNearbyEntities(maxDistance, maxDistance, maxDistance);
            for (Entity candidate : nearby) {
                if (candidate != null && candidate.isValid() && !candidate.isDead()) {
                    currentNearby.add(candidate.getUniqueId());
                }
            }
            int count = nearby.size();
            int start = count == 0 ? 0 : Math.floorMod(inspectionCursor.getOrDefault(playerId, 0), count);
            int inspected = Math.min(64, count);
            inspectionCursor.put(playerId, count == 0 ? 0 : (start + inspected) % count);
            for (int i = 0; i < inspected; i++) {
                Entity entity = nearby.get((start + i) % count);
                if (entity == null || entity == player || !entity.isValid() || entity.isDead()) continue;
                if (entity instanceof Player || isExcluded(entity)) continue;
                if (entity.getWorld() != world) continue;

                UUID id = entity.getUniqueId();

                Location entityLocation = entity.getLocation();
                double dx = entityLocation.getX() - eye.getX();
                double dy = entityLocation.getY() - eye.getY();
                double dz = entityLocation.getZ() - eye.getZ();
                double distanceSq = dx * dx + dy * dy + dz * dz;

                if (distanceSq <= safetyDistance * safetyDistance) {
                    restoreForPlayer(player, entity, hidden);
                    continue;
                }

                double dot = (direction.getX() * dx + direction.getY() * dy + direction.getZ() * dz)
                        / Math.sqrt(distanceSq);

                if (dot >= fovDotThreshold) {
                    restoreForPlayer(player, entity, hidden);
                } else {
                    hideForPlayer(player, entity, hidden);
                }
            }

            for (UUID id : new java.util.ArrayList<>(hidden)) {
                if (!currentNearby.contains(id)) {
                    Entity entity = Bukkit.getEntity(id);
                    if (entity != null) {
                        restoreForPlayer(player, entity, hidden);
                    } else {
                        hidden.remove(id);
                    }
                }
            }
        }
    }

    private boolean isExcluded(Entity entity) {
        if (entity instanceof Item || entity instanceof ArmorStand || entity instanceof Boss || entity instanceof NPC) return true;
        if (entity instanceof LivingEntity living) {
            if (living instanceof Tameable tameable && tameable.isTamed()) return true;
            return living.getCustomName() != null;
        }
        return false;
    }

    private void hideForPlayer(Player player, Entity entity, Set<UUID> hidden) {
        UUID id = entity.getUniqueId();
        if (!hidden.add(id)) return;
        try {
            player.hideEntity(plugin, entity);
            totalCulled++;
        } catch (Throwable failure) {
            hidden.remove(id);
        }
    }

    private void restoreForPlayer(Player player, Entity entity, Set<UUID> hidden) {
        UUID id = entity.getUniqueId();
        if (!hidden.contains(id)) return;
        try {
            player.showEntity(plugin, entity);
            hidden.remove(id);
        } catch (Throwable ignored) {
            // Keep the record for a retry on the next sweep.
        }
    }

    private void restoreForPlayer(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        Set<UUID> hidden = hiddenEntitiesByPlayer.remove(playerId);
        nearbyEntitiesByPlayer.remove(playerId);
        inspectionCursor.remove(playerId);
        if (hidden != null && !hidden.isEmpty()) {
            for (UUID entityId : hidden) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    try {
                        player.showEntity(plugin, entity);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(org.bukkit.event.player.PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SPECTATOR) {
            restoreForPlayer(event.getPlayer());
        }
    }

    private void restoreAllEntities() {
        if (plugin.getSchedulerAdapter().isFolia()) return;
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenEntitiesByPlayer.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            for (UUID entityId : Set.copyOf(entry.getValue())) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    try {
                        player.showEntity(plugin, entity);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        hiddenEntitiesByPlayer.remove(id);
        nearbyEntitiesByPlayer.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        for (Set<UUID> hidden : hiddenEntitiesByPlayer.values()) {
            hidden.remove(id);
        }
        for (Set<UUID> nearby : nearbyEntitiesByPlayer.values()) {
            nearby.remove(id);
        }
    }


    public void trimCaches() {

        hiddenEntitiesByPlayer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        nearbyEntitiesByPlayer.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    public int getCulledEntityCount() {
        int count = 0;
        for (Set<UUID> set : hiddenEntitiesByPlayer.values()) {
            count += set.size();
        }
        return count;
    }

    public Map<UUID, Set<UUID>> getHiddenEntitiesSnapshot() {
        Map<UUID, Set<UUID>> snapshot = new java.util.HashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenEntitiesByPlayer.entrySet()) {
            snapshot.put(entry.getKey(), Collections.unmodifiableSet(Set.copyOf(entry.getValue())));
        }
        return Collections.unmodifiableMap(snapshot);
    }
}
