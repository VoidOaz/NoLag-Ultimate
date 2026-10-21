package me.nolag;

import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.nolag.commands.NoLagCommand;
import me.nolag.gui.DashboardGUI;
import me.nolag.memory.MemoryOptimizationEngine;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import me.nolag.modules.antiexploit.NetherChunkProtectionModule;
import me.nolag.modules.armorstand.ArmorStandModule;
import me.nolag.modules.autosave.AutosaveOptimizerModule;
import me.nolag.modules.chunk.ChunkOptimizationModule;
import me.nolag.modules.command.CommandSecurityModule;
import me.nolag.modules.culling.EntityCullingModule;
import me.nolag.modules.entity.EntityAIOptimizationModule;
import me.nolag.modules.flood.FluidFloodProtectionModule;
import me.nolag.modules.hopper.HopperOptimizationModule;
import me.nolag.modules.item.ItemExpiryModule;
import me.nolag.modules.item.ItemStackerModule;
import me.nolag.modules.mobstacker.MobStackerModule;
import me.nolag.modules.physics.PhysicsOptimizationModule;
import me.nolag.modules.redstone.RedstoneLagDetectorModule;
import me.nolag.modules.plugins.PluginLagWatcherModule;
import me.nolag.modules.ram.RamFixerModule;
import me.nolag.modules.tnt.TNTProtectionModule;
import me.nolag.modules.world.WorldOptimizationModule;
import me.nolag.performance.PerformanceImpactAnalyzer;
import me.nolag.platform.PlatformDetector;
import me.nolag.platform.SchedulerAdapter;
import me.nolag.tps.TPSMonitor;
import me.nolag.version.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class NoLag extends JavaPlugin {

    private SchedulerAdapter schedulerAdapter;
    private VersionChecker versionChecker;
    private TPSMonitor tpsMonitor;
    private PerformanceImpactAnalyzer performanceAnalyzer;
    private MemoryOptimizationEngine memoryOptimizationEngine;
    private RamFixerModule ramFixerModule;
    private EntityCullingModule entityCullingModule;
    private MobStackerModule mobStackerModule;
    private ItemStackerModule itemStackerModule;
    private ItemExpiryModule itemExpiryModule;
    private ChunkOptimizationModule chunkModule;
    private HopperOptimizationModule hopperModule;
    private PhysicsOptimizationModule physicsModule;
    private TNTProtectionModule tntModule;
    private ArmorStandModule armorStandModule;
    private WorldOptimizationModule worldModule;
    private CommandSecurityModule commandSecurityModule;
    private EntityAIOptimizationModule entityAIModule;
    private DashboardGUI dashboardGUI;
    private NetherChunkProtectionModule netherChunkModule;
    private FluidFloodProtectionModule fluidFloodModule;
    private RedstoneLagDetectorModule redstoneLagDetectorModule;
    private PluginLagWatcherModule pluginWatcherModule;
    private AutosaveOptimizerModule autosaveOptimizerModule;
    private Metrics metrics;

    @Override
    public void onEnable() {
        if (!PlatformDetector.enforceSupportedPlatform(this)) {
            return;
        }

        var detectedPlatform = PlatformDetector.detect();
        getLogger().info("Detected server platform: " + PlatformDetector.getPlatformName(detectedPlatform));


        this.schedulerAdapter = new SchedulerAdapter(this);
        if (this.schedulerAdapter.isFolia() && !this.schedulerAdapter.isFoliaSchedulerReady()) {
            getLogger().severe("Folia scheduler bridge could not be initialized; disabling NoLag to avoid unsafe cross-region operations.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (this.schedulerAdapter.isFolia()) {
            getLogger().info("Folia scheduler bridge is active. Entity-sensitive writes use entity schedulers; mob stacking uses region-owned chunk scans.");
            getLogger().info("Folia item stacking is limited to a capped scan of the item's current chunk to avoid cross-region queries.");
            getLogger().warning("Folia-safe profile: full-world entity/cleanup sweeps, entity culling, deep RamFixer scans, fluid-flood correlation, Nether traversal protection and staggered autosave remain disabled where their state is not region-partitioned.");
        }


        this.versionChecker = new VersionChecker();
        if (!versionChecker.isSupported()) {
            getLogger().warning("=========================================================");
            getLogger().warning(" [NoLag-Ultimate] UNTESTED MINECRAFT VERSION DETECTED!");
            getLogger().warning(" Detected Version: " + versionChecker.getServerVersionString());
            getLogger().warning(" Running in maximum compatibility mode.");
            getLogger().warning("=========================================================");
        }


        saveDefaultConfig();


        this.tpsMonitor = new TPSMonitor(this);
        this.performanceAnalyzer = new PerformanceImpactAnalyzer(this);
        this.memoryOptimizationEngine = new MemoryOptimizationEngine(this);


        this.ramFixerModule = new RamFixerModule(this);
        this.entityCullingModule = new EntityCullingModule(this);
        this.mobStackerModule = new MobStackerModule(this);
        this.itemStackerModule = new ItemStackerModule(this);
        this.itemExpiryModule = new ItemExpiryModule(this);
        this.chunkModule = new ChunkOptimizationModule(this);
        this.hopperModule = new HopperOptimizationModule(this);
        this.physicsModule = new PhysicsOptimizationModule(this);
        this.tntModule = new TNTProtectionModule(this);
        this.armorStandModule = new ArmorStandModule(this);
        this.worldModule = new WorldOptimizationModule(this);
        this.commandSecurityModule = new CommandSecurityModule(this);
        this.entityAIModule = new EntityAIOptimizationModule(this);
        this.dashboardGUI = new DashboardGUI(this);
        this.netherChunkModule = new NetherChunkProtectionModule(this);
        this.fluidFloodModule = new FluidFloodProtectionModule(this);
        this.redstoneLagDetectorModule = new RedstoneLagDetectorModule(this, this.schedulerAdapter);
        this.pluginWatcherModule = new PluginLagWatcherModule(this);
        this.autosaveOptimizerModule = new AutosaveOptimizerModule(this);


        var pm = getServer().getPluginManager();
        pm.registerEvents(ramFixerModule, this);
        pm.registerEvents(entityCullingModule, this);
        pm.registerEvents(mobStackerModule, this);
        pm.registerEvents(itemStackerModule, this);
        pm.registerEvents(itemExpiryModule, this);
        pm.registerEvents(chunkModule, this);
        pm.registerEvents(hopperModule, this);
        pm.registerEvents(physicsModule, this);
        pm.registerEvents(tntModule, this);
        pm.registerEvents(armorStandModule, this);
        pm.registerEvents(worldModule, this);
        pm.registerEvents(commandSecurityModule, this);
        pm.registerEvents(entityAIModule, this);
        pm.registerEvents(dashboardGUI, this);
        pm.registerEvents(netherChunkModule, this);
        pm.registerEvents(fluidFloodModule, this);
        pm.registerEvents(redstoneLagDetectorModule, this);
        pm.registerEvents(autosaveOptimizerModule, this);


        this.itemExpiryModule.start();
        this.autosaveOptimizerModule.start();


        if (getConfig().getBoolean("bstats.enabled", true)) {
            try {
                int bStatsId = 34793;
                this.metrics = new Metrics(this, bStatsId);
                this.metrics.addCustomChart(new SimplePie("server_platform",
                        () -> PlatformDetector.getPlatformName(PlatformDetector.detect())));
                this.metrics.addCustomChart(new SimplePie("folia_enabled",
                        () -> schedulerAdapter.isFolia() ? "Folia" : "Non-Folia"));
                this.metrics.addCustomChart(new SimplePie("minecraft_version",
                        () -> versionChecker.getServerVersionString()));
                this.metrics.addCustomChart(new SimplePie("plugin_version",
                        () -> getDescription().getVersion()));
                getLogger().info("bStats metrics enabled (Plugin ID: " + bStatsId + ").");
            } catch (Throwable t) {
                getLogger().warning("Could not initialize bStats metrics: " + t.getMessage());
            }
        }


        PluginCommand cmd = getCommand("nolag");
        if (cmd != null) {
            NoLagCommand executor = new NoLagCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        try {
            getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            getServer().getMessenger().registerOutgoingPluginChannel(this, "nolag:main");
        } catch (Throwable ignored) {}


        getLogger().info("=========================================================");
        getLogger().info(" NoLag-Ultimate v" + getDescription().getVersion() + " successfully activated!");
        getLogger().info(" Running on MC " + versionChecker.getServerVersionString() + " (" + PlatformDetector.getPlatformName(detectedPlatform) + " Optimized)");
        getLogger().info(" Core optimization layers are active; platform-specific features use safe fallbacks.");
        getLogger().info("=========================================================");
    }

    @Override
    public void onDisable() {
        if (tpsMonitor != null) {
            tpsMonitor.stop();
        }
        if (performanceAnalyzer != null) {
            performanceAnalyzer.stop();
        }
        if (memoryOptimizationEngine != null) {
            memoryOptimizationEngine.stop();
        }
        if (ramFixerModule != null) {
            ramFixerModule.stop();
        }
        if (entityCullingModule != null) {
            entityCullingModule.stop();
        }
        if (mobStackerModule != null) {
            mobStackerModule.stop();
        }
        if (chunkModule != null) {
            chunkModule.shutdown();
        }
        if (physicsModule != null) {
            physicsModule.stop();
        }
        if (entityAIModule != null) {
            entityAIModule.stop();
        }
        if (itemExpiryModule != null) {
            itemExpiryModule.stop();
        }
        if (netherChunkModule != null) {
            netherChunkModule.stop();
        }
        if (fluidFloodModule != null) {
            fluidFloodModule.stop();
        }
        if (redstoneLagDetectorModule != null) {
            redstoneLagDetectorModule.shutdown();
        }
        if (pluginWatcherModule != null) {
            pluginWatcherModule.stop();
        }
        if (autosaveOptimizerModule != null) {
            autosaveOptimizerModule.stop();
        }

        if (schedulerAdapter != null) {
            schedulerAdapter.cancelAll();
        }

        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        } catch (Throwable ignored) {}

        getLogger().info("NoLag-Ultimate disabled cleanly.");
    }

    public void reloadPluginConfig() {
        reloadConfig();

        if (tpsMonitor != null) {
            tpsMonitor.start();
        }
        if (performanceAnalyzer != null) {
            performanceAnalyzer.start();
        }
        if (memoryOptimizationEngine != null) {
            memoryOptimizationEngine.start();
        }
        if (ramFixerModule != null) {
            ramFixerModule.reload();
            ramFixerModule.startTask();
        }
        if (entityCullingModule != null) {
            entityCullingModule.reload();
            entityCullingModule.startTask();
        }
        if (mobStackerModule != null) {
            mobStackerModule.reload();
            mobStackerModule.startTask();
        }
        if (chunkModule != null) {
            chunkModule.startTasks();
        }
        if (entityAIModule != null) {
            entityAIModule.reload();
            entityAIModule.startTask();
        }
        if (itemExpiryModule != null) {
            itemExpiryModule.reload();
            itemExpiryModule.start();
        }
        if (itemStackerModule != null) {
            itemStackerModule.reload();
        }
        if (hopperModule != null) {
            hopperModule.reload();
        }
        if (physicsModule != null) {
            physicsModule.reload();
        }
        if (tntModule != null) {
            tntModule.reload();
        }
        if (armorStandModule != null) {
            armorStandModule.reload();
        }
        if (worldModule != null) {
            worldModule.reload();
        }
        if (commandSecurityModule != null) {
            commandSecurityModule.reload();
        }
        if (netherChunkModule != null) {
            netherChunkModule.reload();
        }
        if (fluidFloodModule != null) {
            fluidFloodModule.reload();
        }
        if (redstoneLagDetectorModule != null) {
            redstoneLagDetectorModule.reload();
        }
        if (pluginWatcherModule != null) {
            pluginWatcherModule.reload();
            pluginWatcherModule.start();
        }
        if (autosaveOptimizerModule != null) {
            autosaveOptimizerModule.reload();
            autosaveOptimizerModule.start();
        }
    }

    public void broadcastToNetwork(String message) {
        Runnable dispatch = () -> {
            Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
            if (player == null) return;

            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Message");
            out.writeUTF("all");
            out.writeUTF(color("&8[&6NoLag-Network&8] &f" + message));
            byte[] payload = out.toByteArray();
            if (schedulerAdapter != null && schedulerAdapter.isFolia()) {
                schedulerAdapter.runEntity(player, () -> player.sendPluginMessage(this, "BungeeCord", payload));
            } else {
                player.sendPluginMessage(this, "BungeeCord", payload);
            }
        };


        if (schedulerAdapter != null && schedulerAdapter.isFolia()) {
            schedulerAdapter.runGlobal(dispatch);
        } else {
            dispatch.run();
        }
    }

    public String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    public SchedulerAdapter getSchedulerAdapter() {
        return schedulerAdapter;
    }

    public VersionChecker getVersionChecker() {
        return versionChecker;
    }

    public TPSMonitor getTPSMonitor() {
        return tpsMonitor;
    }

    public PerformanceImpactAnalyzer getPerformanceAnalyzer() {
        return performanceAnalyzer;
    }

    public MemoryOptimizationEngine getMemoryOptimizationEngine() {
        return memoryOptimizationEngine;
    }

    public RamFixerModule getRamFixerModule() {
        return ramFixerModule;
    }

    public EntityCullingModule getEntityCullingModule() {
        return entityCullingModule;
    }

    public MobStackerModule getMobStackerModule() {
        return mobStackerModule;
    }

    public ItemStackerModule getItemStackerModule() {
        return itemStackerModule;
    }

    public ItemExpiryModule getItemExpiryModule() {
        return itemExpiryModule;
    }

    public ChunkOptimizationModule getChunkModule() {
        return chunkModule;
    }

    public HopperOptimizationModule getHopperModule() {
        return hopperModule;
    }

    public PhysicsOptimizationModule getPhysicsModule() {
        return physicsModule;
    }

    public TNTProtectionModule getTntModule() {
        return tntModule;
    }

    public ArmorStandModule getArmorStandModule() {
        return armorStandModule;
    }

    public WorldOptimizationModule getWorldModule() {
        return worldModule;
    }

    public CommandSecurityModule getCommandSecurityModule() {
        return commandSecurityModule;
    }

    public EntityAIOptimizationModule getEntityAIModule() {
        return entityAIModule;
    }

    public DashboardGUI getDashboardGUI() {
        return dashboardGUI;
    }

    public NetherChunkProtectionModule getNetherChunkModule() {
        return netherChunkModule;
    }

    public FluidFloodProtectionModule getFluidFloodModule() {
        return fluidFloodModule;
    }

    public PluginLagWatcherModule getPluginWatcherModule() {
        return pluginWatcherModule;
    }

    public AutosaveOptimizerModule getAutosaveOptimizerModule() {
        return autosaveOptimizerModule;
    }

    public Metrics getMetrics() {
        return metrics;
    }
}
