package me.nolag.modules.tnt;

import java.util.Iterator;
import me.nolag.NoLag;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockIgniteEvent.IgniteCause;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;

public final class TNTProtectionModule implements Listener {

    private final NoLag plugin;

    public TNTProtectionModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTNTSpawn(EntitySpawnEvent event) {
        if (!plugin.getConfig().getBoolean("features.tnt-protection", true)) {
            return;
        }

        EntityType type = event.getEntityType();
        if (type == EntityType.TNT || type == EntityType.MINECART) {
            int maxTNT = plugin.getConfig().getInt("tnt-protection.max-primed-tnt-per-chunk", 25);
            long tntCount = 0;
            for (var entity : event.getLocation().getChunk().getEntities()) {
                if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) {
                    tntCount++;
                    if (tntCount > maxTNT) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTExplode(EntityExplodeEvent event) {
        if (!plugin.getConfig().getBoolean("features.anti-tnt-chain", true)) {
            return;
        }

        int threshold = plugin.getConfig().getInt("tnt-protection.yield-zero-threshold", 50);
        float yieldVal = (float) plugin.getConfig().getDouble("tnt-protection.yield-zero-value", 0.0);

        if (event.blockList().size() >= threshold) {
            event.setYield(yieldVal);
        }

        Iterator<Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (block.getType() == Material.TNT) {
                iterator.remove(); 
                block.setType(Material.AIR, false); 
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (plugin.getConfig().getBoolean("tnt-protection.disable-explosion-fire", true)) {
            if (event.getCause() == IgniteCause.EXPLOSION) {
                event.setCancelled(true);
            }
        }
    }
}


