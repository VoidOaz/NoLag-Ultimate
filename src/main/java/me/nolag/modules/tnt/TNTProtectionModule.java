package me.nolag.modules.tnt;

import java.util.Iterator;
import me.nolag.NoLag;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
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
    private volatile boolean tntProtectionEnabled = true;
    private volatile boolean antiTntChainEnabled = true;
    private volatile int maxPrimedTntPerChunk = 25;
    private volatile int yieldZeroThreshold = 50;
    private volatile float yieldZeroValue = 0.0f;
    private volatile boolean disableExplosionFire = true;

    public TNTProtectionModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.tntProtectionEnabled = cfg.getBoolean("features.tnt-protection", true);
        this.antiTntChainEnabled = cfg.getBoolean("features.anti-tnt-chain", true);
        this.maxPrimedTntPerChunk = Math.max(1, cfg.getInt("tnt-protection.max-primed-tnt-per-chunk", 25));
        this.yieldZeroThreshold = Math.max(1, cfg.getInt("tnt-protection.yield-zero-threshold", 50));
        this.yieldZeroValue = (float) cfg.getDouble("tnt-protection.yield-zero-value", 0.0);
        this.disableExplosionFire = cfg.getBoolean("tnt-protection.disable-explosion-fire", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTNTSpawn(EntitySpawnEvent event) {
        if (!tntProtectionEnabled) {
            return;
        }

        Entity spawned = event.getEntity();
        if (!(spawned instanceof TNTPrimed) && !(spawned instanceof ExplosiveMinecart)) {
            return;
        }

        Chunk chunk = event.getLocation().getChunk();
        if (!chunk.isLoaded()) {
            return;
        }

        int tntCount = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) {
                tntCount++;
                // The spawn event's entity is not guaranteed to be present in the
                // chunk array yet, so block when the existing count already hits the cap.
                if (tntCount >= maxPrimedTntPerChunk) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTExplode(EntityExplodeEvent event) {
        if (!antiTntChainEnabled) {
            return;
        }

        if (event.blockList().size() >= yieldZeroThreshold) {
            event.setYield(yieldZeroValue);
        }

        Iterator<Block> iterator = event.blockList().iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (block.getType() == Material.TNT) {
                iterator.remove(); // Prevent triggering adjacent TNT blocks in explosion
                if (block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                    block.setType(Material.AIR, false); // Safely remove block without exploding
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (disableExplosionFire) {
            if (event.getCause() == IgniteCause.EXPLOSION) {
                event.setCancelled(true);
            }
        }
    }
}
