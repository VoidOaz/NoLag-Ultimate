package me.nolag.platform;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class PlatformDetector {

    private PlatformDetector() {

    }

    public enum Platform {
        PAPER,
        PURPUR,
        SPIGOT,
        BUKKIT,
        FOLIA,
        UNKNOWN
    }

    private static volatile Platform CACHED_PLATFORM = null;


    public static Platform detect() {
        if (CACHED_PLATFORM != null) {
            return CACHED_PLATFORM;
        }

        try {
            Server server = Bukkit.getServer();

            if (server == null) {
                return Platform.UNKNOWN;
            }

            String serverName = safeLower(server.getName());
            String serverVersion = safeLower(server.getVersion());
            String bukkitVersion = safeLower(Bukkit.getBukkitVersion());
            String serverClass = safeLower(server.getClass().getName());


            try {
                Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                CACHED_PLATFORM = Platform.FOLIA;
                return Platform.FOLIA;
            } catch (ClassNotFoundException ignored) {}

            if (containsAny(serverName, "folia")
                    || containsAny(serverVersion, "folia")
                    || containsAny(bukkitVersion, "folia")
                    || containsAny(serverClass, "folia", "threadedregions")) {
                CACHED_PLATFORM = Platform.FOLIA;
                return Platform.FOLIA;
            }


            if (containsAny(serverName, "purpur")
                    || containsAny(serverVersion, "purpur")
                    || containsAny(bukkitVersion, "purpur")
                    || containsAny(serverClass, "purpur")) {
                CACHED_PLATFORM = Platform.PURPUR;
                return Platform.PURPUR;
            }


            if (containsAny(serverName, "paper")
                    || containsAny(serverVersion, "paper")
                    || containsAny(bukkitVersion, "paper")
                    || containsAny(serverClass, "paper")) {
                CACHED_PLATFORM = Platform.PAPER;
                return Platform.PAPER;
            }


            if (containsAny(serverName, "spigot")
                    || containsAny(serverVersion, "spigot")
                    || containsAny(bukkitVersion, "spigot")
                    || containsAny(serverClass, "spigot")) {
                CACHED_PLATFORM = Platform.SPIGOT;
                return Platform.SPIGOT;
            }


            if (containsAny(serverName, "craftbukkit", "bukkit")
                    || containsAny(serverVersion, "craftbukkit", "bukkit")
                    || containsAny(bukkitVersion, "craftbukkit", "bukkit")
                    || containsAny(serverClass, "craftbukkit")) {
                CACHED_PLATFORM = Platform.BUKKIT;
                return Platform.BUKKIT;
            }

        } catch (Throwable ignored) {


        }

        CACHED_PLATFORM = Platform.UNKNOWN;
        return Platform.UNKNOWN;
    }


    public static boolean isSupported(Platform platform) {
        if (platform == null) {
            return false;
        }

        return switch (platform) {
            case PAPER,
                 PURPUR,
                 SPIGOT,
                 BUKKIT,
                 FOLIA -> true;

            case UNKNOWN -> false;
        };
    }


    public static boolean enforceSupportedPlatform(JavaPlugin plugin) {
        if (plugin == null) {
            return false;
        }

        Platform platform = detect();

        if (isSupported(platform)) {
            return true;
        }

        String platformName = getPlatformName(platform);

        plugin.getLogger().severe("========================================");
        plugin.getLogger().severe("UNSUPPORTED SERVER PLATFORM");
        plugin.getLogger().severe("Detected platform: " + platformName);
        plugin.getLogger().severe("");
        plugin.getLogger().severe(
                "Supported platforms: Paper, Purpur, Spigot, Bukkit/CraftBukkit, Folia"
        );
        plugin.getLogger().severe(
                "NoLag-Ultimate cannot run on this server platform."
        );
        plugin.getLogger().severe(
                "The plugin will now disable itself."
        );
        plugin.getLogger().severe("========================================");

        try {
            Bukkit.getPluginManager().disablePlugin(plugin);
        } catch (Throwable ignored) {}

        return false;
    }


    public static String getPlatformName(Platform platform) {
        if (platform == null) {
            return "Unknown";
        }

        return switch (platform) {
            case PAPER -> "Paper";
            case PURPUR -> "Purpur";
            case SPIGOT -> "Spigot";
            case BUKKIT -> "Bukkit/CraftBukkit";
            case FOLIA -> "Folia";
            case UNKNOWN -> "Unknown";
        };
    }

    private static String safeLower(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... identifiers) {
        if (value == null || value.isEmpty() || identifiers == null) {
            return false;
        }

        for (String identifier : identifiers) {
            if (identifier != null
                    && !identifier.isEmpty()
                    && value.contains(identifier.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }
}
