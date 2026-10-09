package me.nolag.version;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;

/**
 * Validates server Minecraft version compatibility for NoLag-Ultimate 21.6.0.
 * Allows the explicitly listed Minecraft releases targeted by this build.
 */
public final class VersionChecker {

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?$");

    private static final Pattern VERSION_SEARCH_PATTERN =
            Pattern.compile("\\b\\d+\\.\\d+(?:\\.\\d+)?\\b");

    private static final Set<String> SUPPORTED_VERSIONS;

    static {
        LinkedHashSet<String> versions = new LinkedHashSet<>();

        // 1.21.1+ Series
        versions.add("1.21.1");
        versions.add("1.21.2");
        versions.add("1.21.3");
        versions.add("1.21.4");
        versions.add("1.21.5");
        versions.add("1.21.6");
        versions.add("1.21.7");
        versions.add("1.21.8");
        versions.add("1.21.9");
        versions.add("1.21.10");
        versions.add("1.21.11");

        // Mojang's new 26.x release numbering
        versions.add("26.1");
        versions.add("26.1.1");
        versions.add("26.1.2");
        versions.add("26.2");

        SUPPORTED_VERSIONS = Collections.unmodifiableSet(versions);
    }

    private final String serverVersionString;
    private final int major;
    private final int minor;
    private final int patch;
    private final boolean supported;

    public VersionChecker() {
        this.serverVersionString = detectVersionString();

        int[] parsed = parseVersion(serverVersionString);

        this.major = parsed[0];
        this.minor = parsed[1];
        this.patch = parsed[2];

        this.supported = checkSupported(serverVersionString, major, minor, patch);
    }

    private static String detectVersionString() {
        // Primary source: actual Minecraft version.
        try {
            String version = Bukkit.getMinecraftVersion();
            if (version != null && !version.isBlank()) {
                return version.trim();
            }
        } catch (Throwable ignored) {
        }

        // Fallback: Bukkit version.
        try {
            String version = Bukkit.getBukkitVersion();
            if (version != null && !version.isBlank()) {
                Matcher matcher = VERSION_SEARCH_PATTERN.matcher(version);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback: full Bukkit/Paper version string.
        try {
            String version = Bukkit.getVersion();
            if (version != null && !version.isBlank()) {
                Matcher matcher = VERSION_SEARCH_PATTERN.matcher(version);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int[] parseVersion(String version) {
        if (version == null || version.isBlank()) {
            return new int[]{-1, -1, -1};
        }

        Matcher matcher = VERSION_PATTERN.matcher(version.trim());
        if (!matcher.matches()) {
            return new int[]{-1, -1, -1};
        }

        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            int patch = matcher.group(3) != null
                    ? Integer.parseInt(matcher.group(3))
                    : 0;

            return new int[]{major, minor, patch};
        } catch (NumberFormatException ignored) {
            return new int[]{-1, -1, -1};
        }
    }

    /**
     * Only explicitly listed releases are treated as supported. Unknown versions
     * remain loadable in compatibility mode, but are not advertised as supported.
     */
    private static boolean checkSupported(String version, int major, int minor, int patch) {
        return version != null && SUPPORTED_VERSIONS.contains(version.trim());
    }

    public boolean isSupported() {
        return supported;
    }

    public static boolean isVersionSupported(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }

        String normalized = version.trim();
        return SUPPORTED_VERSIONS.contains(normalized);
    }

    public static Set<String> getSupportedVersions() {
        return SUPPORTED_VERSIONS;
    }

    public String getServerVersionString() {
        return serverVersionString;
    }

    public String getFormattedVersion() {
        if (major < 0 || minor < 0 || patch < 0) {
            return "Unknown";
        }

        if (patch == 0) {
            return String.format(
                    Locale.ROOT,
                    "%d.%d",
                    major,
                    minor
            );
        }

        return String.format(
                Locale.ROOT,
                "%d.%d.%d",
                major,
                minor,
                patch
        );
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