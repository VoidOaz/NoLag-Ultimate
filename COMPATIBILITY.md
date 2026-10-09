# NoLag-Ultimate 21.6.0 — Compatibility targets

This build explicitly recognizes these Minecraft Java Edition server versions:

- 1.21.1
- 1.21.2
- 1.21.3
- 1.21.4
- 1.21.5
- 1.21.6
- 1.21.7
- 1.21.8
- 1.21.9
- 1.21.10
- 1.21.11
- 26.1
- 26.1.1
- 26.1.2
- 26.2

The 26.x numbers use Mojang's newer release numbering; they are intentionally included as real version identifiers.

## Java runtime

- Minecraft 1.21.1–1.21.11 server releases require Java 21.
- Minecraft 26.1+ server releases require Java 25.
- The plugin is kept at Java 21 bytecode to preserve compatibility with the older target series; Java 25 can run Java 21 bytecode.

## Server software

The project is a Bukkit/Paper plugin, not a Fabric, Forge, NeoForge, Quilt, Velocity, or BungeeCord proxy plugin. The code detects Paper, Purpur, Spigot/Bukkit, and Folia; some global-scan/optimization features intentionally disable themselves in Folia-safe mode.

## Testing note

This archive updates the plugin's version recognition and targets its compile-time API at the minimum listed server API. It has not been launched on every listed server version in this environment. Treat the list as intended compatibility targets, not a claim that each release was individually integration-tested. Test on a staging server and keep backups before production deployment, especially when enabling block-interaction protections.
