package me.nolag.gui;

import java.util.List;
import java.util.Locale;
import me.nolag.NoLag;
import me.nolag.tps.TPSMonitor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class DashboardGUI implements Listener {

    private final NoLag plugin;
    private static final String GUI_TITLE = ChatColor.DARK_GRAY + "NoLag Performance Dashboard";

    public static final class DashboardHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    public DashboardGUI(NoLag plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        DashboardHolder holder = new DashboardHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, GUI_TITLE);
        holder.setInventory(inv);

        // Fill background with black stained glass panes
        ItemStack filler = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }

        // Slot 11: TPS & MSPT
        double tps = plugin.getTPSMonitor().getTPS();
        double mspt = plugin.getTPSMonitor().getMSPT();
        String tpsColor = tps >= 18.0 ? "§a" : (tps >= 15.0 ? "§e" : "§c");
        String msptColor = mspt < 40.0 ? "§a" : (mspt < 50.0 ? "§e" : "§c");

        List<String> tpsLore = List.of(
                "§7Current TPS: " + tpsColor + String.format(Locale.ROOT, "%.2f", tps) + " §7/ §a20.0",
                "§7Tick MSPT: " + msptColor + String.format(Locale.ROOT, "%.2f", mspt) + " ms",
                "",
                "§8Status: " + (mspt < 40.0 ? "§aOptimal Performance" : "§cUnder Load")
        );
        inv.setItem(11, createItem(Material.CLOCK, "§b§lServer Tick Health", tpsLore));

        // Slot 13: RAM / Memory
        TPSMonitor.MemoryStats mem = TPSMonitor.getMemoryStats();
        String memColor = mem.usagePercent() < 70.0 ? "§a" : (mem.usagePercent() < 85.0 ? "§e" : "§c");
        List<String> memLore = List.of(
                "§7Used RAM: " + memColor + mem.usedMb() + " MB §7/ §f" + mem.maxMb() + " MB",
                "§7Free RAM: §f" + mem.freeMb() + " MB",
                "§7Usage: " + memColor + String.format(Locale.ROOT, "%.1f%%", mem.usagePercent()),
                "",
                "§8JVM Heap: §764-bit G1GC/ZGC"
        );
        inv.setItem(13, createItem(Material.EMERALD, "§a§lMemory & JVM Usage", memLore));

        // Slot 15: World & Entities
        int totalEntities = 0;
        int totalChunks = 0;
        for (World world : Bukkit.getWorlds()) {
            totalEntities += world.getEntityCount();
            totalChunks += world.getLoadedChunks().length;
        }
        List<String> worldLore = List.of(
                "§7Active Entities: §e" + totalEntities,
                "§7Loaded Chunks: §e" + totalChunks,
                "§7Online Players: §a" + Bukkit.getOnlinePlayers().size(),
                "§7View Distance: §b" + plugin.getChunkModule().getCurrentViewDistance()
        );
        inv.setItem(15, createItem(Material.BEACON, "§6§lWorld & Entity Stats", worldLore));

        // Slot 21: Manual Cleanup Action
        List<String> cleanupLore = List.of(
                "§7Click to immediately perform",
                "§7a ground item & mob cleanup cycle.",
                "",
                "§e▶ Click to run cleanup!"
        );
        inv.setItem(21, createItem(Material.NETHER_STAR, "§e§lPerform Cleanup", cleanupLore));

        // Slot 23: Reload Configuration
        List<String> reloadLore = List.of(
                "§7Click to reload configuration files",
                "§7and restart optimization tasks.",
                "",
                "§e▶ Click to reload!"
        );
        inv.setItem(23, createItem(Material.REDSTONE_TORCH, "§c§lReload Configuration", reloadLore));

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof DashboardHolder) {
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 27) {
                return;
            }

            if (slot == 21) {
                // Manual cleanup
                if (player.hasPermission("nolag.cleanup")) {
                    player.closeInventory();
                    var res = plugin.getCleanupManager().performCleanup(false);
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &aManual cleanup executed! Removed &e" + res.items() + " items &aand &e" + res.mobs() + " mobs."));
                } else {
                    player.sendMessage(ChatColor.RED + "You do not have permission to run cleanup!");
                }
            } else if (slot == 23) {
                // Reload
                if (player.hasPermission("nolag.reload")) {
                    player.closeInventory();
                    plugin.reloadPluginConfig();
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &aConfiguration reloaded!"));
                } else {
                    player.sendMessage(ChatColor.RED + "You do not have permission to reload!");
                }
            }
        }
    }

    private static ItemStack createItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) {
                meta.setLore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }
}

