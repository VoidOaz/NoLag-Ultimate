package me.nolag.gui;

import java.util.List;
import java.util.Locale;
import me.nolag.NoLag;
import me.nolag.modules.ram.RamFixerModule;
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

        var performanceSnapshot = plugin.getPerformanceAnalyzer().snapshot();
        var performanceLore = new java.util.ArrayList<String>();
        performanceLore.add("§7TPS: " + tpsColor + String.format(Locale.ROOT, "%.2f", tps) + " §7/ §a20.00");
        performanceLore.add("§7MSPT: " + msptColor + String.format(Locale.ROOT, "%.2f", mspt) + " ms");
        performanceLore.add("");
        for (String system : java.util.List.of("Chunk", "Hopper", "Entities", "Redstone", "Fluid", "Memory")) {
            var sample = performanceSnapshot.get(system);
            if (sample != null && sample.calls() > 0) {
                performanceLore.add("§7" + system + ": §e" + String.format(Locale.ROOT, "%.2f", sample.estimatedMspt()) + " ms (est.)");
            }
        }
        inv.setItem(11, createItem(Material.CLOCK, "§b§lPerformance Impact", performanceLore));

        // Slot 13: RAM / Memory & RamFixer
        var memory = plugin.getMemoryOptimizationEngine().getSnapshot();
        String memColor = memory.usagePercent() < 70.0 ? "§a" : (memory.usagePercent() < 85.0 ? "§e" : "§c");
        var ramFixer = plugin.getRamFixerModule();
        List<String> memLore = new java.util.ArrayList<>(List.of(
                "§7Heap: " + memColor + memory.usedMb() + " / " + memory.maxMb() + " MB",
                "§7Usage: " + memColor + String.format(Locale.ROOT, "%.1f%%", memory.usagePercent()),
                "§7GC activity: §f" + memory.gcActivity(),
                "§7Pressure: §f" + memory.pressure().name().toLowerCase(Locale.ROOT),
                "§7RamFixer tier: §f" + ramFixer.getPressureTier().name().toLowerCase(Locale.ROOT)
                        + (ramFixer.isSweepRunning() ? " §8(sweeping…)" : ""),
                "§7Freed (lifetime): §a" + ramFixer.getTotalFreedMb() + " MB",
                "§7Ghost chunks: §e" + ramFixer.getGhostChunksUnloaded() + " §8| §7Auto sweeps: §e" + ramFixer.getAutoSweepCount(),
                "",
                "§e▶ Click to run RamFixer memory sweep!"
        ));
        inv.setItem(13, createItem(Material.EMERALD, "§a§lMemory & JVM Usage", memLore));

        // Slot 15: World & Entities
        boolean folia = plugin.getSchedulerAdapter().isFolia();
        int totalEntities = 0;
        int totalChunks = 0;
        int onlinePlayers = 0;
        if (!folia) {
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                totalEntities += TPSMonitor.getEntityCountSafe(world);
                totalChunks += TPSMonitor.getLoadedChunkCount(world);
            }
            onlinePlayers = Bukkit.getOnlinePlayers().size();
        }
        List<String> worldLore = folia
                ? List.of(
                    "§7World-wide entity/chunk totals: §8disabled",
                    "§7Online player count: §8managed by Folia",
                    "§7View Distance: §b" + plugin.getChunkModule().getCurrentViewDistance(),
                    "",
                    "§8Global scans are disabled in Folia mode for thread safety"
                )
                : List.of(
                    "§7Active Entities: §e" + totalEntities,
                    "§7Loaded Chunks: §e" + totalChunks,
                    "§7Online Players: §a" + onlinePlayers,
                    "§7View Distance: §b" + plugin.getChunkModule().getCurrentViewDistance(),
                    "",
                    "§8Live NoLag activity is sampled with low overhead"
                );
        inv.setItem(15, createItem(Material.BEACON, "§6§lWorld & Entity Stats", worldLore));

        // Slot 20: Manual Cleanup Action
        List<String> cleanupLore = List.of(
                "§7Click to immediately perform",
                "§7a ground item & mob cleanup cycle.",
                "",
                "§e▶ Click to run cleanup!"
        );
        inv.setItem(20, createItem(Material.NETHER_STAR, "§e§lPerform Cleanup", cleanupLore));

        // Slot 22: RamFixer Memory Sweep Action
        List<String> ramLore = List.of(
                "§7Click to execute RamFixer engine:",
                "§7Trims chunk/entity/plugin caches",
                "§7and frees unreferenced heap memory.",
                "",
                "§e▶ Click to optimize RAM!"
        );
        inv.setItem(22, createItem(Material.EXPERIENCE_BOTTLE, "§b§lRamFixer Memory Sweep", ramLore));

        // Slot 24: Reload Configuration
        List<String> reloadLore = List.of(
                "§7Click to reload configuration files",
                "§7and restart optimization tasks.",
                "",
                "§e▶ Click to reload!"
        );
        inv.setItem(24, createItem(Material.REDSTONE_TORCH, "§c§lReload Configuration", reloadLore));

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

            if (slot == 20) {
                // Manual cleanup
                if (player.hasPermission("nolag.cleanup")) {
                    player.closeInventory();
                    var res = plugin.getCleanupManager().performCleanup(false);
                    String msg = res.started()
                            ? "&8[&6NoLag&8] &aCleanup scan started. Results will be announced when the scan finishes."
                            : "&8[&6NoLag&8] &eCleanup is unavailable in the current platform mode.";
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                } else {
                    player.sendMessage(ChatColor.RED + "You do not have permission to run cleanup!");
                }
            } else if (slot == 13 || slot == 22) {
                // RamFixer
                if (player.hasPermission("nolag.admin")) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &eRunning RamFixer optimization... The final result will be reported when finished."));
                    boolean started = plugin.getRamFixerModule().startManualSweep(res ->
                            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &aRamFixer complete! Freed &e" + res.freedMb()
                                    + "MB &a(Heap: " + res.usedAfterMb() + "MB / " + res.maxMb() + "MB, "
                                    + "GC: " + (res.gcTriggered() ? "triggered" : "not required") + ")")));
                    if (!started) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &cRamFixer is already running or unavailable in this platform mode."));
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "You do not have permission to run RamFixer!");
                }
            } else if (slot == 24) {
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
