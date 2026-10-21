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


        ItemStack filler = createItem(Material.BLACK_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < 27; i++) {
            inv.setItem(i, filler);
        }


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

        var watcher = plugin.getPluginWatcherModule();
        List<String> pluginLore = new java.util.ArrayList<>();
        pluginLore.add("§7Server TPS: §b" + String.format(Locale.ROOT, "%.2f", tps));
        pluginLore.add("§7Server MSPT: §b" + String.format(Locale.ROOT, "%.2f", mspt) + " ms");
        pluginLore.add("§7Stack samples: §e" + (watcher == null ? 0L : watcher.getTotalStackSamples()));
        pluginLore.add("");
        if (watcher != null && watcher.getTotalStackSamples() < 4L) {
            pluginLore.add("§eProfiler is warming up");
        }
        if (watcher == null || !watcher.isEnabled()) {
            pluginLore.add("§cPlugin Lag Watcher is disabled");
        } else {
            var profiles = watcher.getTopLaggingPlugins();
            if (profiles.isEmpty()) {
                pluginLore.add("§7No enabled plugins to inspect");
            } else {
                for (int i = 0; i < Math.min(4, profiles.size()); i++) {
                    var profile = profiles.get(i);
                    pluginLore.add("§7" + profile.name() + ": §e~" + String.format(Locale.ROOT, "%.2f", profile.msptImpact())
                            + " ms §8| §b" + String.format(Locale.ROOT, "%.1f%%", profile.activityPercent()));
                }
            }
        }
        pluginLore.add("");
        pluginLore.add("§8Estimated values update continuously");
        inv.setItem(17, createItem(Material.COMPARATOR, "§d§lPlugin Lag Watcher", pluginLore));


        var itemModule = plugin.getItemExpiryModule();
        List<String> expiryLore = List.of(
                "§7Hidden per-item expiry countdown:",
                "§7Status: " + (itemModule != null && itemModule.isEnabled() ? "§aActive" : "§cDisabled"),
                "§7Lifetime: §e" + (itemModule != null ? itemModule.getConfiguredLifetimeSeconds() : 180) + "s",
                "§7Tracked Items: §b" + (itemModule != null ? itemModule.getTrackedItemCount() : 0),
                "",
                "§7Items despawn individually without server lag."
        );
        inv.setItem(20, createItem(Material.HOPPER, "§e§lItem Expiry System", expiryLore));


        List<String> ramLore = List.of(
                "§7Click to execute RamFixer engine:",
                "§7Trims chunk/entity/plugin caches",
                "§7and frees unreferenced heap memory.",
                "",
                "§e▶ Click to optimize RAM!"
        );
        inv.setItem(22, createItem(Material.EXPERIENCE_BOTTLE, "§b§lRamFixer Memory Sweep", ramLore));


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

            if (slot == 11) {
                open(player);
                return;
            }

            if (slot == 20) {
                var itemModule = plugin.getItemExpiryModule();
                player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                        "&8[&6NoLag&8] &eItem Expiry System: &a" + (itemModule != null && itemModule.isEnabled() ? "Active" : "Disabled")
                        + " &8| &7Timer: &e" + (itemModule != null ? itemModule.getConfiguredLifetimeSeconds() : 180) + "s"
                        + " &8| &7Tracked: &b" + (itemModule != null ? itemModule.getTrackedItemCount() : 0) + " items"));
            } else if (slot == 13 || slot == 22) {

                if (player.hasPermission("nolag.admin")) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &eRunning RamFixer optimization... The final result will be reported when finished."));
                    boolean started = plugin.getRamFixerModule().startManualSweep(res -> {
                        Runnable report = () -> player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &aRamFixer complete! Net heap reclaimed: &e" + res.freedMb()
                                + "MB &7| chunks inspected: &e" + res.chunksScannedInFlight()
                                + " &7| chunks unloaded: &e" + res.chunksUnloadedThisSweep()
                                + " &7| dead items removed: &e" + res.deadEntitiesRemovedThisSweep()
                                + " &7| heap: &f" + res.usedAfterMb() + "MB / " + res.maxMb() + "MB"
                                + " &7| GC request: " + (res.gcTriggered() ? "&aissued" : "&eskipped") + "&r"));
                        if (plugin.getSchedulerAdapter().isFolia()) {
                            plugin.getSchedulerAdapter().runEntity(player, report);
                        } else {
                            report.run();
                        }
                    });
                    if (!started) {
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &cRamFixer is already running or unavailable in this platform mode."));
                    }
                } else {
                    player.sendMessage(ChatColor.RED + "You do not have permission to run RamFixer!");
                }
            } else if (slot == 24) {

                if (player.hasPermission("nolag.reload")) {
                    player.closeInventory();
                    Runnable reloadAndNotify = () -> {
                        plugin.reloadPluginConfig();
                        plugin.getSchedulerAdapter().runEntity(player, () -> player.sendMessage(
                                ChatColor.translateAlternateColorCodes('&', "&8[&6NoLag&8] &aConfiguration reloaded!")));
                    };
                    if (plugin.getSchedulerAdapter().isFolia()) {
                        plugin.getSchedulerAdapter().runGlobal(reloadAndNotify);
                    } else {
                        reloadAndNotify.run();
                    }
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
