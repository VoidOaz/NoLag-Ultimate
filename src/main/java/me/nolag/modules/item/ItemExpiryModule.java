package me.nolag.modules.item;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;

/**
 * Entity-scheduled ground-item lifetime manager.
 *
 * <p>The previous implementation walked every tracked item every second and
 * performed location/chunk/name work on the server thread. This version sleeps
 * until each individual item's expiry, using the entity scheduler on Folia and
 * the normal Bukkit scheduler elsewhere. Countdown names remain optional and
 * are intentionally opt-in because changing entity metadata is comparatively
 * expensive.</p>
 */
public final class ItemExpiryModule implements Listener {

    private static final int DEFAULT_LIFETIME_SECONDS = 120;
    private static final int DEFAULT_UPDATE_INTERVAL_SECONDS = 1;

    private final NoLag plugin;
    private final NamespacedKey expireKey;
    private final Map<UUID, ExpiryEntry> trackedItems = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile int lifetimeSeconds = DEFAULT_LIFETIME_SECONDS;
    private volatile int updateIntervalSeconds = DEFAULT_UPDATE_INTERVAL_SECONDS;
    private volatile String renameFormat = "";

    public ItemExpiryModule(NoLag plugin) {
        this.plugin = plugin;
        this.expireKey = new NamespacedKey(plugin, "item_expire_at");
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        boolean legacyPresent = cfg.isConfigurationSection("features.item-cleanup");

        this.enabled = cfg.getBoolean("features.item-expiry.enabled",
                legacyPresent && cfg.getBoolean("features.item-cleanup.enabled", false));
        this.lifetimeSeconds = Math.max(10, cfg.getInt("features.item-expiry.lifetime-seconds",
                cfg.getInt("features.item-cleanup.lifetime-seconds", DEFAULT_LIFETIME_SECONDS)));
        this.updateIntervalSeconds = Math.max(1, cfg.getInt("features.item-expiry.update-interval-seconds",
                cfg.getInt("features.item-cleanup.update-interval-seconds", DEFAULT_UPDATE_INTERVAL_SECONDS)));
        this.renameFormat = cfg.getString("features.item-expiry.rename-format",
                cfg.getString("features.item-cleanup.rename-format", ""));
    }

    /** Re-reads config and reschedules already tracked entities without a world-wide scan. */
    public void start() {
        cancelScheduledTasks(false);
        reload();
        if (!enabled) {
            trackedItems.clear();
            return;
        }
        rescheduleTrackedItems();
    }

    public void stop() {
        cancelScheduledTasks(true);
    }

    private void cancelScheduledTasks(boolean clearEntries) {
        for (ExpiryEntry entry : trackedItems.values()) {
            if (entry != null && entry.task() != null) {
                try {
                    entry.task().cancel();
                } catch (Throwable ignored) {
                }
            }
        }
        if (clearEntries) {
            trackedItems.clear();
        }
    }

    private void rescheduleTrackedItems() {
        for (Map.Entry<UUID, ExpiryEntry> mapEntry : trackedItems.entrySet()) {
            ExpiryEntry current = mapEntry.getValue();
            if (current == null) {
                trackedItems.remove(mapEntry.getKey(), null);
                continue;
            }
            Item item = current.getItem();
            if (item == null || !item.isValid() || item.isDead()) {
                trackedItems.remove(mapEntry.getKey(), current);
                continue;
            }
            scheduleExpiry(item, current.expireAtMillis());
        }
    }

    /**
     * Cache maintenance is deliberately allocation-free and does not touch Bukkit
     * entity state from a global task. Stale entries are removed only when their
     * weak reference is gone or their task has already retired.
     */
    public void trimCaches() {
        trackedItems.entrySet().removeIf(entry -> {
            ExpiryEntry expiry = entry.getValue();
            if (expiry == null) return true;
            if (expiry.getItem() == null) return true;
            TaskHandle task = expiry.task();
            return task != null && task.isCancelled();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) return;

        Item item = event.getEntity();
        long now = System.currentTimeMillis();
        long storedExpiry = getStoredExpiry(item, 0L);
        registerItemAt(item, storedExpiry > now ? storedExpiry : now + getConfiguredLifetimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled) return;
        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) continue;
            long now = System.currentTimeMillis();
            long expiry = getStoredExpiry(item, now + getConfiguredLifetimeMillis());
            registerItemAt(item, expiry);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        unregisterItem(event.getItem() != null ? event.getItem().getUniqueId() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        unregisterItem(event.getEntity() != null ? event.getEntity().getUniqueId() : null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && (item.isDead() || !item.isValid())) {
            unregisterItem(item.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Item item) {
                unregisterItem(item.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (!enabled) return;

        Item source = event.getEntity();
        Item target = event.getTarget();
        if (source == null || target == null || !source.isValid() || !target.isValid()) return;

        long now = System.currentTimeMillis();
        long fallback = now + getConfiguredLifetimeMillis();
        long sourceExpiry = getStoredExpiry(source, fallback);
        long targetExpiry = getStoredExpiry(target, fallback);

        unregisterItem(source.getUniqueId());
        registerItemAt(target, Math.max(sourceExpiry, targetExpiry));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getConfiguredLifetimeSeconds() {
        return lifetimeSeconds;
    }

    public int getUpdateIntervalSeconds() {
        return updateIntervalSeconds;
    }

    public NamespacedKey getExpireKey() {
        return expireKey;
    }

    public int getTrackedItemCount() {
        return trackedItems.size();
    }

    public long getConfiguredLifetimeMillis() {
        return lifetimeSeconds * 1000L;
    }

    public void unregisterItem(UUID uuid) {
        if (uuid == null) return;
        ExpiryEntry entry = trackedItems.remove(uuid);
        if (entry != null && entry.task() != null) {
            try {
                entry.task().cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    public void registerItem(Item item, long lifetimeSecs) {
        if (item == null || !item.isValid()) return;
        long expiry = System.currentTimeMillis() + Math.max(1L, lifetimeSecs) * 1000L;
        registerItemAt(item, expiry);
    }

    public void registerItem(Item item) {
        registerItem(item, lifetimeSeconds);
    }

    private void registerItemAt(Item item, long expiryAtMillis) {
        if (!enabled || item == null || !item.isValid() || item.isDead()) return;

        long expiry = Math.max(System.currentTimeMillis() + 1000L, expiryAtMillis);
        item.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG, expiry);

        UUID id = item.getUniqueId();
        ExpiryEntry previous = trackedItems.get(id);
        if (previous != null && previous.task() != null) {
            try {
                previous.task().cancel();
            } catch (Throwable ignored) {
            }
        }

        ExpiryEntry entry = new ExpiryEntry(new WeakReference<>(item), expiry, null);
        trackedItems.put(id, entry);
        scheduleExpiry(item, expiry);
    }

    private void scheduleExpiry(Item item, long expiryAtMillis) {
        if (!enabled || item == null) return;

        long remainingMs = expiryAtMillis - System.currentTimeMillis();
        long delayTicks = Math.max(1L, Math.min(Integer.MAX_VALUE, (remainingMs + 49L) / 50L));
        if (!renameFormat.isBlank()) {
            delayTicks = Math.min(delayTicks, Math.max(1L, updateIntervalSeconds * 20L));
        }

        UUID id = item.getUniqueId();
        TaskHandle handle = plugin.getSchedulerAdapter().runEntityLater(item, () -> processScheduledItem(id), delayTicks);
        trackedItems.computeIfPresent(id, (key, current) -> new ExpiryEntry(current.itemRef(), current.expireAtMillis(), handle));
    }

    private void processScheduledItem(UUID id) {
        if (!enabled || id == null) return;

        ExpiryEntry entry = trackedItems.get(id);
        if (entry == null) return;

        Item item = entry.getItem();
        if (item == null || !item.isValid() || item.isDead()) {
            trackedItems.remove(id, entry);
            return;
        }

        long remaining = entry.expireAtMillis() - System.currentTimeMillis();
        if (remaining <= 0L) {
            try {
                item.remove();
            } catch (Throwable ignored) {
            }
            trackedItems.remove(id, entry);
            return;
        }

        if (!renameFormat.isBlank()) {
            updateCountdownName(item, remaining);
            scheduleExpiry(item, entry.expireAtMillis());
        } else {
            scheduleExpiry(item, entry.expireAtMillis());
        }
    }

    private void updateCountdownName(Item item, long remainingMs) {
        ItemStack stack = item.getItemStack();
        String displayName = null;
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            displayName = meta.getDisplayName();
        } else if (stack.getType() != null) {
            String rawName = stack.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            if (!rawName.isBlank()) {
                displayName = Character.toUpperCase(rawName.charAt(0)) + rawName.substring(1);
            }
        }
        if (displayName == null) displayName = "Item";

        long seconds = Math.max(1L, (remainingMs + 999L) / 1000L);
        String time = seconds >= 60L
                ? (seconds / 60L) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L)
                : seconds + "s";
        String rendered = ChatColor.translateAlternateColorCodes('&',
                renameFormat.replace("%time%", time).replace("%name%", displayName));

        if (!rendered.equals(item.getCustomName())) {
            item.setCustomName(rendered);
            item.setCustomNameVisible(true);
        }
    }

    private long getStoredExpiry(Item item, long fallback) {
        Long stored = item.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
        return stored == null || stored <= 0L ? fallback : stored;
    }

    private record ExpiryEntry(WeakReference<Item> itemRef, long expireAtMillis, TaskHandle task) {
        Item getItem() {
            return itemRef != null ? itemRef.get() : null;
        }
    }
}
