package me.nolag.modules.item;

import java.util.Arrays;
import me.nolag.NoLag;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class ItemStackerModule implements Listener {


    private static final int MAX_FOLIA_CHUNK_CANDIDATES = 128;

    private final NoLag plugin;
    private final NamespacedKey expireKey;
    private volatile boolean enabled = true;

    public ItemStackerModule(NoLag plugin) {
        this.plugin = plugin;
        this.expireKey = new NamespacedKey(plugin, "item_expire_at");
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("features.item-stacker", true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!enabled) {
            return;
        }

        Item newItem = event.getEntity();
        if (newItem == null || !newItem.isValid()) {
            return;
        }

        ItemStack stack = newItem.getItemStack();
        int maxStackSize = stack.getMaxStackSize();

        if (maxStackSize <= 1 || stack.getAmount() >= maxStackSize) {
            return;
        }

        boolean folia = plugin.getSchedulerAdapter().isFolia();
        Location origin = folia ? newItem.getLocation() : null;
        Iterable<Entity> candidates;
        if (folia) {


            Chunk ownerChunk = origin.getChunk();
            candidates = Arrays.asList(ownerChunk.getEntities());
        } else {
            candidates = newItem.getNearbyEntities(2.0, 2.0, 2.0);
        }

        int examinedFoliaCandidates = 0;
        for (Entity nearby : candidates) {
            if (folia && examinedFoliaCandidates++ >= MAX_FOLIA_CHUNK_CANDIDATES) break;
            if (!(nearby instanceof Item) || !nearby.isValid() || nearby.isDead() || nearby.equals(newItem)) continue;
            if (folia) {
                Location candidateLocation = nearby.getLocation();
                if (Math.abs(candidateLocation.getX() - origin.getX()) > 2.0D
                        || Math.abs(candidateLocation.getY() - origin.getY()) > 2.0D
                        || Math.abs(candidateLocation.getZ() - origin.getZ()) > 2.0D) {
                    continue;
                }
            }
            if (nearby instanceof Item other && other.isValid() && !other.isDead() && !other.equals(newItem)) {
                if (other.getPickupDelay() > 1000) {
                    continue;
                }

                ItemStack otherStack = other.getItemStack();
                if (otherStack.isSimilar(stack)) {
                    int total = stack.getAmount() + otherStack.getAmount();
                    int minDelay = Math.min(newItem.getPickupDelay(), other.getPickupDelay());

                    if (total <= maxStackSize) {
                        stack.setAmount(total);
                        newItem.setPickupDelay(minDelay);

                        try {
                            Long a = newItem.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
                            Long b = other.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
                            long now = System.currentTimeMillis();
                            long lifetime = plugin.getItemExpiryModule() != null
                                    ? plugin.getItemExpiryModule().getConfiguredLifetimeMillis()
                                    : 180_000L;
                            long expireA = a != null ? a : now + lifetime;
                            long expireB = b != null ? b : now + lifetime;
                            long result = Math.max(expireA, expireB);
                            newItem.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG, result);
                        } catch (Throwable e) {

                            plugin.getLogger().fine("Failed to merge item expiry data: " + e.getMessage());
                        }

                        if (plugin.getItemExpiryModule() != null) {
                            plugin.getItemExpiryModule().unregisterItem(other.getUniqueId());
                        }
                        other.remove();
                    } else {
                        stack.setAmount(maxStackSize);
                        newItem.setPickupDelay(minDelay);
                        otherStack.setAmount(total - maxStackSize);
                        other.setItemStack(otherStack);
                        try {
                            Long a = newItem.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
                            Long b = other.getPersistentDataContainer().get(expireKey, PersistentDataType.LONG);
                            long now = System.currentTimeMillis();
                            long lifetime = plugin.getItemExpiryModule() != null
                                    ? plugin.getItemExpiryModule().getConfiguredLifetimeMillis()
                                    : 180_000L;
                            long expireA = a != null ? a : now + lifetime;
                            long expireB = b != null ? b : now + lifetime;
                            long result = Math.max(expireA, expireB);
                            newItem.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG, result);
                            other.getPersistentDataContainer().set(expireKey, PersistentDataType.LONG, result);
                        } catch (Throwable e) {

                            plugin.getLogger().fine("Failed to update item expiry data during split: " + e.getMessage());
                        }
                    }
                }
            }
            if (stack.getAmount() >= maxStackSize) {
                break;
            }
        }

        newItem.setItemStack(stack);
    }
}
