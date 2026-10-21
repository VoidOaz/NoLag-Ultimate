package me.nolag.modules.item;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
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


public final class ItemExpiryModule implements Listener {

    public static final int DEFAULT_LIFETIME_SECONDS = 180;

    private final NoLag plugin;
    private final NamespacedKey expireKey;
    private final Map<UUID, ExpiryEntry> trackedItems = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile int lifetimeSeconds = DEFAULT_LIFETIME_SECONDS;
    private volatile boolean protectNamedItems = true;

    public ItemExpiryModule(NoLag plugin) {
        this.plugin = plugin;
        this.expireKey = new NamespacedKey(plugin, "item_expire_at");
        reload();
    }


    public void reload() {
        var cfg = plugin.getConfig();


        if (cfg.isConfigurationSection("features.item-expiry")) {
            this.enabled = cfg.getBoolean("features.item-expiry.enabled", true);
            this.lifetimeSeconds = Math.max(5, cfg.getInt("features.item-expiry.lifetime-seconds", DEFAULT_LIFETIME_SECONDS));
            this.protectNamedItems = cfg.getBoolean("features.item-expiry.protect-named-items", true);
        } else if (cfg.isConfigurationSection("item-expiry")) {
            this.enabled = cfg.getBoolean("item-expiry.enabled", true);
            this.lifetimeSeconds = Math.max(5, cfg.getInt("item-expiry.lifetime-seconds", DEFAULT_LIFETIME_SECONDS));
            this.protectNamedItems = cfg.getBoolean("item-expiry.protect-named-items", true);
        } else {
            this.enabled = true;
            this.lifetimeSeconds = DEFAULT_LIFETIME_SECONDS;
            this.protectNamedItems = true;
        }
    }


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
        boolean folia = plugin.getSchedulerAdapter().isFolia();
        for (Map.Entry<UUID, ExpiryEntry> mapEntry : trackedItems.entrySet()) {
            UUID id = mapEntry.getKey();
            ExpiryEntry current = mapEntry.getValue();
            if (current == null) {
                trackedItems.remove(id);
                continue;
            }

            Item item = current.getItem();
            if (item == null) {
                trackedItems.remove(id, current);
                continue;
            }

            if (folia) {


                TaskHandle validation = plugin.getSchedulerAdapter().runEntity(item, () -> {
                    if (trackedItems.get(id) != current) return;
                    if (!item.isValid() || item.isDead()) {
                        trackedItems.remove(id, current);
                        return;
                    }
                    scheduleExpiry(item, current.expireAtMillis());
                });
                if (validation == TaskHandle.EMPTY) {
                    trackedItems.remove(id, current);
                }
            } else {
                if (!item.isValid() || item.isDead()) {
                    trackedItems.remove(id, current);
                    continue;
                }
                scheduleExpiry(item, current.expireAtMillis());
            }
        }
    }


    public void trimCaches() {
        boolean folia = plugin.getSchedulerAdapter().isFolia();
        if (folia) {


            trackedItems.entrySet().removeIf(entry -> {
                ExpiryEntry expiry = entry.getValue();
                return expiry == null
                        || expiry.getItem() == null
                        || (expiry.task() != null && expiry.task().isCancelled());
            });
            return;
        }

        trackedItems.entrySet().removeIf(entry -> {
            ExpiryEntry expiry = entry.getValue();
            if (expiry == null) return true;
            Item item = expiry.getItem();
            if (item == null || !item.isValid() || item.isDead()) return true;
            TaskHandle task = expiry.task();
            return task != null && task.isCancelled();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) return;

        Item item = event.getEntity();
        if (item == null || !item.isValid()) return;

        if (isProtected(item)) return;

        long now = System.currentTimeMillis();
        long storedExpiry = getStoredExpiry(item, 0L);
        long targetExpiry = (storedExpiry > now) ? storedExpiry : (now + getConfiguredLifetimeMillis());
        registerItemAt(item, targetExpiry);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled) return;

        Chunk chunk = event.getChunk();
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                continue;
            }
            if (isProtected(item)) {
                continue;
            }

            long now = System.currentTimeMillis();
            long expiry = getStoredExpiry(item, now + getConfiguredLifetimeMillis());


            if (now >= expiry) {
                try {
                    item.remove();
                } catch (Throwable ignored) {}
                continue;
            }

            registerItemAt(item, expiry);
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
    public void onItemPickup(EntityPickupItemEvent event) {
        if (event.getItem() != null) {
            unregisterItem(event.getItem().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (event.getEntity() != null) {
            unregisterItem(event.getEntity().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && (item.isDead() || !item.isValid())) {
            unregisterItem(item.getUniqueId());
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


        long mergedExpiry = Math.max(sourceExpiry, targetExpiry);
        registerItemAt(target, mergedExpiry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getConfiguredLifetimeSeconds() {
        return lifetimeSeconds;
    }

    public long getConfiguredLifetimeMillis() {
        return lifetimeSeconds * 1000L;
    }

    public NamespacedKey getExpireKey() {
        return expireKey;
    }

    public int getTrackedItemCount() {
        return trackedItems.size();
    }

    public boolean isProtectNamedItems() {
        return protectNamedItems;
    }

    public boolean isProtected(Item item) {
        if (item == null || !item.isValid() || item.isDead()) {
            return true;
        }

        if (protectNamedItems) {
            ItemStack stack = item.getItemStack();
            if (stack != null && stack.hasItemMeta()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null && meta.hasDisplayName()) {
                    return true;
                }
            }
        }

        return false;
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

    public void registerItem(Item item) {
        registerItem(item, lifetimeSeconds);
    }

    public void registerItem(Item item, long lifetimeSecs) {
        if (item == null || !item.isValid() || item.isDead()) return;
        long expiry = System.currentTimeMillis() + Math.max(1L, lifetimeSecs) * 1000L;
        registerItemAt(item, expiry);
    }

    private void registerItemAt(Item item, long expiryAtMillis) {
        if (!enabled || item == null || !item.isValid() || item.isDead()) return;

        long expiry = Math.max(System.currentTimeMillis() + 500L, expiryAtMillis);


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
        if (!enabled || item == null || !item.isValid()) return;

        long remainingMs = expiryAtMillis - System.currentTimeMillis();

        long delayTicks = Math.max(1L, (remainingMs + 49L) / 50L);

        UUID id = item.getUniqueId();

        TaskHandle handle = plugin.getSchedulerAdapter().runEntityLater(item, () -> processScheduledExpiry(id), delayTicks);
        trackedItems.computeIfPresent(id, (key, current) -> new ExpiryEntry(current.itemRef(), current.expireAtMillis(), handle));
    }

    private void processScheduledExpiry(UUID id) {
        if (!enabled || id == null) return;

        ExpiryEntry entry = trackedItems.remove(id);
        if (entry == null) return;

        Item item = entry.getItem();
        if (item == null || !item.isValid() || item.isDead()) {
            return;
        }

        long remaining = entry.expireAtMillis() - System.currentTimeMillis();
        if (remaining <= 0L) {

            try {
                item.remove();
            } catch (Throwable ignored) {
            }
        } else {

            scheduleExpiry(item, entry.expireAtMillis());
        }
    }

    private long getStoredExpiry(Item item, long fallback) {
        try {
            Long stored = item.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
            return stored == null || stored <= 0L ? fallback : stored;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private record ExpiryEntry(WeakReference<Item> itemRef, long expireAtMillis, TaskHandle task) {
        Item getItem() {
            return itemRef != null ? itemRef.get() : null;
        }
    }
}
