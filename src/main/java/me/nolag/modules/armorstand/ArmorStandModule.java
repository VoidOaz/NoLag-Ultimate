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
    private volatile boolean enabled = true;
    private volatile int limitPerChunk = 15;
    private volatile String limitMessage = "&cThis chunk has reached the Armor Stand limit (%limit%)!";

    public ArmorStandModule(NoLag plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.enabled = plugin.getConfig().getBoolean("features.armor-stand-limiter", true);
        this.limitPerChunk = Math.max(1, plugin.getConfig().getInt("settings.armor-stand-limit-per-chunk", 15));
        this.limitMessage = plugin.getConfig().getString(
                "messages.armor-limit-reached",
                "&cThis chunk has reached the Armor Stand limit (%limit%)!"
        );
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandPlace(EntityPlaceEvent event) {
        if (!enabled) {
            return;
        }

        if (event.getEntity() instanceof ArmorStand) {
            Player player = event.getPlayer();
            if (player != null && player.hasPermission("nolag.armorstand.bypass")) {
                return;
            }

            Chunk chunk = event.getBlock().getChunk();
            int count = 0;

            for (Entity entity : chunk.getEntities()) {
                if (entity instanceof ArmorStand) {
                    count++;
                    if (count >= limitPerChunk) {
                        break;
                    }
                }
            }

            if (count >= limitPerChunk) {
                event.setCancelled(true);
                if (player != null) {
                    String msg = limitMessage.replace("%limit%", String.valueOf(limitPerChunk));
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
            }
        }
    }
}
