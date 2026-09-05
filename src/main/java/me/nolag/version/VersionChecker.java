package me.nolag.version;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

public final class VersionChecker {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    // Minimum supported version: Minecraft 1.20.6
    private static final int MIN_MAJOR = 1;
    private static final int MIN_MINOR = 20;
    private static final int MIN_PATCH = 6;

    private final String serverVersionString;
    private final int major;
    private final int minor;
    private final int patch;
    private final boolean supported;

    public VersionChecker() {
        this.serverVersionString = detectVersionString();
        int[] parsed = parseVersion(this.serverVersionString);
        this.major = parsed[0];
        this.minor = parsed[1];
        this.patch = parsed[2];
        this.supported = checkSupported(this.major, this.minor, this.patch);
    }

    private static String detectVersionString() {
        try {
            // Paper / Modern Bukkit getMinecraftVersion()
            String mcVersion = Bukkit.getMinecraftVersion();
            if (mcVersion != null && !mcVersion.isBlank()) {
                return mcVersion.trim();
            }
        } catch (Throwable ignored) {
        }

        try {
            String bukkitVersion = Bukkit.getBukkitVersion();
            Matcher matcher = VERSION_PATTERN.matcher(bukkitVersion);
            if (matcher.find()) {
                return matcher.group(0);
            }
        } catch (Throwable ignored) {
        }

        try {
            String version = Bukkit.getVersion();
            Matcher matcher = VERSION_PATTERN.matcher(version);
            if (matcher.find()) {
                return matcher.group(0);
            }
        } catch (Throwable ignored) {
        }

        return "1.20.6"; // Fallback
    }

    private static int[] parseVersion(String ver) {
        if (ver == null || ver.isBlank()) {
            return new int[]{1, 20, 6};
        }
        Matcher matcher = VERSION_PATTERN.matcher(ver);
        if (matcher.find()) {
            try {
                int major = Integer.parseInt(matcher.group(1));
                int minor = Integer.parseInt(matcher.group(2));
                int patch = matcher.group(3) != null ? Integer.parseInt(matcher.group(3)) : 0;
                return new int[]{major, minor, patch};
            } catch (NumberFormatException ignored) {
            }
        }
        return new int[]{1, 20, 6};
    }

    private static boolean checkSupported(int major, int minor, int patch) {
        if (major != 1) {
            return false;
        }

        // Must be at least 1.20.6
        if (minor < MIN_MINOR) {
            return false;
        }
        if (minor == MIN_MINOR && patch < MIN_PATCH) {
            return false;
        }

        return true;
    }

    public boolean isSupported() {
        return this.supported;
    }

    public String getServerVersionString() {
        return this.serverVersionString;
    }

    public String getFormattedVersion() {
        return String.format(Locale.ROOT, "%d.%d.%d", this.major, this.minor, this.patch);
    }

    public int getMajor() {
        return major;
    }

    public int getMinor() {
        return minor;
    }

    public int getPatch() {
        return patch;
    }
}

