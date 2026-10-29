package me.nolag.modules.tnt;

import me.nolag.NoLag;
import org.bukkit.Chunk;
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
    private volatile float yieldZeroValue = 1.0f;
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
        this.yieldZeroValue = (float) Math.max(0.0, Math.min(1.0,
                cfg.getDouble("tnt-protection.yield-zero-value", 1.0)));
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

        if (spawned instanceof TNTPrimed primed) {
            Entity source = primed.getSource();
            if (source instanceof org.bukkit.entity.Player player && player.hasPermission("nolag.tnt.bypass")) {
                return;
            }
        }

        Chunk chunk = event.getLocation().getChunk();
        if (!chunk.isLoaded()) {
            return;
        }

        int tntCount = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) {
                tntCount++;

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

        if ((event.getEntity() instanceof TNTPrimed || event.getEntity() instanceof ExplosiveMinecart)
                && yieldZeroValue < 1.0f && event.blockList().size() >= yieldZeroThreshold) {
            event.setYield(yieldZeroValue);
        }

        // Leave block mutations to the server's explosion pipeline. Removing TNT
        // here used to erase blocks without normal explosion/drop protection hooks.
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (tntProtectionEnabled && disableExplosionFire) {
            if (event.getCause() == IgniteCause.EXPLOSION) {
                event.setCancelled(true);
            }
        }
    }
}
