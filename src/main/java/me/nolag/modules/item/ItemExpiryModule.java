package me.nolag.modules.item;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter;
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
import java.util.concurrent.ConcurrentLinkedQueue;


public final class ItemExpiryModule implements Listener {

    public static final int DEFAULT_LIFETIME_SECONDS = 180;

    private final NoLag plugin;
    private final NamespacedKey expireKey;
    private final Map<UUID, ExpiryEntry> trackedItems = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<UUID> sweepQueue = new ConcurrentLinkedQueue<>();
    private final java.util.Set<UUID> queuedIds = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> pendingExpirations = new ConcurrentHashMap<>();
    private SchedulerAdapter.TaskHandle sweepTask;

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
        stop();
        reload();
        if (!enabled) {
            trackedItems.clear();
            return;
        }
        // P-05 fix: Use a centralized timer sweep instead of allocating thousands of individual task handles
        sweepTask = plugin.getSchedulerAdapter().runGlobalTimer(this::performExpirySweep, 20L, 1L);
    }

    public void stop() {
        if (sweepTask != null) {
            sweepTask.cancel();
            sweepTask = null;
        }
        cancelScheduledTasks(true);
    }

    private void performExpirySweep() {
        sweepExpired(128);
    }

    /** Queue a bounded manual cleanup of expired tracked items. Returns the tasks accepted. */
    public int requestCleanup() {
        return sweepExpired(2048);
    }

    private int sweepExpired(int budget) {
        if (!enabled) return 0;
        int scheduled = 0;
        long now = System.currentTimeMillis();
        // Process a bounded, rotating set of entries. Re-queue only still-tracked
        // items, otherwise a fixed prefix could starve later items forever.
        int toInspect = Math.min(budget, sweepQueue.size());
        for (int i = 0; i < toInspect; i++) {
            UUID id = sweepQueue.poll();
            if (id == null) return scheduled;
            queuedIds.remove(id);
            ExpiryEntry entry = trackedItems.get(id);
            if (entry == null) continue;
            Item item = entry.getItem();
            if (item == null) {
                trackedItems.remove(id, entry);
                continue;
            }
            // Retry entity tasks that were cancelled without executing their callback.
            if (now >= entry.expireAtMillis() && pendingExpirations.compute(id,
                    (ignored, since) -> since == null || now - since > 10_000L ? now : since) == now) {
                TaskHandle handle = plugin.getSchedulerAdapter().runEntity(item, () -> {
                    try {
                        processScheduledExpiry(id, entry.expireAtMillis());
                    } finally {
                        pendingExpirations.remove(id, now);
                    }
                });
                if (handle == null || handle.isCancelled()) pendingExpirations.remove(id, now);
                else scheduled++;
            }
            if (trackedItems.containsKey(id) && queuedIds.add(id)) sweepQueue.offer(id);
        }
        return scheduled;
    }

    private void cancelScheduledTasks(boolean clearEntries) {
        if (clearEntries) {
            trackedItems.clear();
            sweepQueue.clear();
            queuedIds.clear();
            pendingExpirations.clear();
        }
    }

    public void trimCaches() {
        // Do not touch Folia-owned entity state from a global cleanup thread.
        if (plugin.getSchedulerAdapter().isFolia()) {
            trackedItems.entrySet().removeIf(entry -> entry.getValue().getItem() == null);
        } else {
            trackedItems.entrySet().removeIf(entry -> {
                Item item = entry.getValue().getItem();
                return item == null || !item.isValid() || item.isDead();
            });
        }
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
        if (source == null || target == null) return;
        long fallback = System.currentTimeMillis() + getConfiguredLifetimeMillis();
        long sourceExpiry = getStoredExpiry(source, fallback);
        long targetExpiry = getStoredExpiry(target, fallback);
        UUID sourceId = source.getUniqueId();
        // MONITOR is not a reliable commit boundary: other MONITOR handlers
        // may still cancel the merge. Reconcile only after vanilla has committed.
        plugin.getSchedulerAdapter().runEntityLater(target, () -> {
            if (!enabled || !target.isValid() || target.isDead()) return;
            if (source.isValid() && !source.isDead()) return; // merge was cancelled
            unregisterItem(sourceId);
            registerItemAt(target, Math.max(sourceExpiry, targetExpiry));
        }, 1L);
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
        trackedItems.remove(uuid);
    }

    public void registerItem(Item item) {
        registerItem(item, lifetimeSeconds);
    }

    public void registerItem(Item item, long lifetimeSecs) {
        if (item == null || !item.isValid() || item.isDead()) return;
        long expiry = System.currentTimeMillis() + Math.max(1L, lifetimeSecs) * 1000L;
        registerItemAt(item, expiry);
    }

    /** Keep the persistent timestamp and the sweep entry in sync on the entity owner thread. */
    public void updateExpiry(Item item, long expiryAtMillis) {
        registerItemAt(item, expiryAtMillis);
    }

    private void registerItemAt(Item item, long expiryAtMillis) {
        if (!enabled || item == null || !item.isValid() || item.isDead()) return;
        item.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG, expiryAtMillis);
        UUID id = item.getUniqueId();
        ExpiryEntry entry = new ExpiryEntry(new WeakReference<>(item), expiryAtMillis);
        trackedItems.put(id, entry);
        if (queuedIds.add(id)) sweepQueue.offer(id);
    }

    private void processScheduledExpiry(UUID id, long expectedExpiry) {
        if (!enabled || id == null) return;

        ExpiryEntry entry = trackedItems.get(id);
        if (entry == null || entry.expireAtMillis() != expectedExpiry) return;

        Item item = entry.getItem();
        if (item == null || !item.isValid() || item.isDead()) {
            trackedItems.remove(id, entry);
            return;
        }

        long stored = getStoredExpiry(item, entry.expireAtMillis());
        if (stored > entry.expireAtMillis()) {
            registerItemAt(item, stored);
            return;
        }
        long remaining = entry.expireAtMillis() - System.currentTimeMillis();
        if (remaining <= 0L) {
            trackedItems.remove(id, entry);
            if (isProtected(item)) return;
            try {
                item.remove();
            } catch (Throwable ignored) {
            }
        } else {
            // The central sweep revisits the item without scheduling individual tasks.
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

    private record ExpiryEntry(WeakReference<Item> itemRef, long expireAtMillis) {
        Item getItem() {
            return itemRef != null ? itemRef.get() : null;
        }
    }
}
