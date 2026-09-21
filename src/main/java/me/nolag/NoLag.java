package me.nolag;

import com.google.common.collect.Iterables;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import me.nolag.commands.NoLagCommand;
import me.nolag.gui.DashboardGUI;
import me.nolag.modules.armorstand.ArmorStandModule;
import me.nolag.modules.antiexploit.NetherChunkProtectionModule;
import me.nolag.modules.chunk.ChunkOptimizationModule;
import me.nolag.modules.cleanup.CleanupManager;
import me.nolag.modules.command.CommandSecurityModule;
import me.nolag.modules.entity.EntityAIOptimizationModule;
import me.nolag.modules.flood.FluidFloodProtectionModule;
import me.nolag.modules.hopper.HopperOptimizationModule;
import me.nolag.modules.item.ItemStackerModule;
import me.nolag.modules.mobstacker.MobStackerModule;
import me.nolag.modules.physics.PhysicsOptimizationModule;
import me.nolag.modules.tnt.TNTProtectionModule;
import me.nolag.modules.world.WorldOptimizationModule;
import me.nolag.tps.TPSMonitor;
import me.nolag.version.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class NoLag extends JavaPlugin {

    private VersionChecker versionChecker;
    private TPSMonitor tpsMonitor;
    private CleanupManager cleanupManager;
    private MobStackerModule mobStackerModule;
    private ItemStackerModule itemStackerModule;
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

    @Override
    public void onEnable() {
        // 1. Version Compatibility Enforcement
        this.versionChecker = new VersionChecker();
        if (!versionChecker.isSupported()) {
            getLogger().warning("=========================================================");
            getLogger().warning(" [NoLag-Ultimate] UNSUPPORTED MINECRAFT VERSION DETECTED!");
            getLogger().warning(" Detected Version: " + versionChecker.getServerVersionString());
            getLogger().warning(" Recommended Minimum: Minecraft 1.20.6");
            getLogger().warning(" Plugin will run in compatibility mode.");
            getLogger().warning("=========================================================");
        }

        // 2. Save and load configuration
        saveDefaultConfig();

        // 3. Initialize TPS & Diagnostics Monitor
        this.tpsMonitor = new TPSMonitor(this);

        // 4. Initialize Core Optimization Modules
        this.cleanupManager = new CleanupManager(this);
        this.mobStackerModule = new MobStackerModule(this);
        this.itemStackerModule = new ItemStackerModule(this);
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

        // 5. Register Event Listeners
        var pm = getServer().getPluginManager();
        pm.registerEvents(mobStackerModule, this);
        pm.registerEvents(itemStackerModule, this);
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

        // 6. Register Commands and Channels
        PluginCommand cmd = getCommand("nolag");
        if (cmd != null) {
            NoLagCommand executor = new NoLagCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getServer().getMessenger().registerOutgoingPluginChannel(this, "nolag:main");

        // 7. Success Banner
        getLogger().info("=========================================================");
        getLogger().info(" NoLag-Ultimate v" + getDescription().getVersion() + " successfully activated!");
        getLogger().info(" Running on MC " + versionChecker.getServerVersionString() + " (Paper/Folia Optimized)");
        getLogger().info(" All optimization layers active & ready.");
        getLogger().info("=========================================================");
    }

    @Override
    public void onDisable() {
        if (tpsMonitor != null) {
            tpsMonitor.stop();
        }
        if (cleanupManager != null) {
            cleanupManager.stopTimer();
        }
        if (mobStackerModule != null) {
            mobStackerModule.stop();
        }
        if (chunkModule != null) {
            chunkModule.stopTasks();
        }
        if (physicsModule != null) {
            physicsModule.stop();
        }
        if (entityAIModule != null) {
            entityAIModule.stop();
        }
        if (netherChunkModule != null) {
            netherChunkModule.stop();
        }
        if (fluidFloodModule != null) {
            fluidFloodModule.stop();
        }

        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        } catch (Throwable ignored) {
        }

        getLogger().info("NoLag-Ultimate disabled cleanly.");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        if (tpsMonitor != null) {
            tpsMonitor.start();
        }
        if (cleanupManager != null) {
            cleanupManager.startTimer();
        }
        if (mobStackerModule != null) {
            mobStackerModule.startTask();
        }
        if (chunkModule != null) {
            chunkModule.startTasks();
        }
        if (entityAIModule != null) {
            entityAIModule.startTask();
        }
    }

    public void broadcastToNetwork(String message) {
        Player player = Iterables.getFirst(Bukkit.getOnlinePlayers(), null);
        if (player != null) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Message");
            out.writeUTF("all");
            out.writeUTF(color("&8[&6NoLag-Network&8] &f" + message));
            player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
        }
    }

    public String color(String text) {
        return text == null ? "" : ChatColor.translateAlternateColorCodes('&', text);
    }

    // Getters for Managers and Modules
    public VersionChecker getVersionChecker() {
        return versionChecker;
    }

    public TPSMonitor getTPSMonitor() {
        return tpsMonitor;
    }

    public CleanupManager getCleanupManager() {
        return cleanupManager;
    }

    public MobStackerModule getMobStackerModule() {
        return mobStackerModule;
    }

    public ItemStackerModule getItemStackerModule() {
        return itemStackerModule;
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
}

