package me.nolag.modules.item;

import me.nolag.NoLag;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;

public final class ItemStackerModule implements Listener {

    private final NoLag plugin;

    public ItemStackerModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!plugin.getConfig().getBoolean("features.item-stacker", true)) {
            return;
        }

        Item newItem = event.getEntity();
        ItemStack stack = newItem.getItemStack();
        int maxStackSize = stack.getMaxStackSize();

        if (maxStackSize <= 1 || stack.getAmount() >= maxStackSize) {
            return;
        }


        for (Entity nearby : newItem.getNearbyEntities(2.0, 2.0, 2.0)) {
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
                        other.remove();
                    } else {
                        stack.setAmount(maxStackSize);
                        newItem.setPickupDelay(minDelay);
                        otherStack.setAmount(total - maxStackSize);
                        other.setItemStack(otherStack);
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

