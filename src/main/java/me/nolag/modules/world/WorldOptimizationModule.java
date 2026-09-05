package me.nolag.modules.world;

import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

public final class WorldOptimizationModule implements Listener {

    private final NoLag plugin;

    public WorldOptimizationModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWoodBreak(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("features.fast-leaf-decay", true)) {
            return;
        }

        Block block = event.getBlock();
        if (Tag.LOGS.isTagged(block.getType())) {
            var world = block.getWorld();
            int bx = block.getX();
            int by = block.getY();
            int bz = block.getZ();

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    return;
                }
                for (int x = -4; x <= 4; x++) {
                    for (int y = -2; y <= 4; y++) {
                        for (int z = -4; z <= 4; z++) {
                            Block relative = world.getBlockAt(bx + x, by + y, bz + z);
                            if (Tag.LEAVES.isTagged(relative.getType())) {
                                if (relative.getBlockData() instanceof Leaves leaves) {
                                    // Do NOT decay player-placed persistent leaves!
                                    if (!leaves.isPersistent() && leaves.getDistance() >= 7) {
                                        relative.breakNaturally();
                                    }
                                }
                            }
                        }
                    }
                }
            }, 6L);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean("features.void-teleport", true)) {
            return;
        }

        Location to = event.getTo();
        if (to == null) {
            return;
        }

        Location from = event.getFrom();
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ()) {
            Player player = event.getPlayer();
            // Don't rescue creative/spectator players
            if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            if (player.hasPermission("nolag.unsafe.bypass")) {
                return;
            }

            int minHeight = player.getWorld().getMinHeight();
            if (to.getY() < (minHeight - 2.0)) {
                Location spawn = player.getRespawnLocation();
                if (spawn == null || spawn.getWorld() == null) {
                    spawn = player.getWorld().getSpawnLocation();
                }

                player.setFallDistance(0.0F);
                player.setVelocity(new Vector(0, 0, 0));
                player.teleport(spawn);

                String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + "&aYou have been safely rescued from the void!"));
            }
        }
    }
}

