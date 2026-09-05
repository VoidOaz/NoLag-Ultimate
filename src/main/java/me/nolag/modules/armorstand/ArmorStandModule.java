package me.nolag.modules.armorstand;

import me.nolag.NoLag;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPlaceEvent;

public final class ArmorStandModule implements Listener {

    private final NoLag plugin;

    public ArmorStandModule(NoLag plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandPlace(EntityPlaceEvent event) {
        if (!plugin.getConfig().getBoolean("features.armor-stand-limiter", true)) {
            return;
        }

        if (event.getEntity() instanceof ArmorStand) {
            Player player = event.getPlayer();
            if (player != null && player.hasPermission("nolag.armorstand.bypass")) {
                return;
            }

            Chunk chunk = event.getBlock().getChunk();
            int limit = plugin.getConfig().getInt("settings.armor-stand-limit-per-chunk", 15);
            int count = 0;

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof ArmorStand) {
                    count++;
                }
            }

            if (count >= limit) {
                event.setCancelled(true);
                if (player != null) {
                    String msg = plugin.getConfig().getString(
                            "messages.armor-limit-reached",
                            "&cThis chunk has reached the Armor Stand limit (%limit%)!"
                    ).replace("%limit%", String.valueOf(limit));
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
            }
        }
    }
}

