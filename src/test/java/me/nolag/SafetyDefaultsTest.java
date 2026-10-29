package me.nolag;

import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafetyDefaultsTest {
    @Test
    void destructiveAndHighOverheadFeaturesRequireOptIn() {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertFalse(cfg.getBoolean("features.autosave-optimizer.enabled"));
        assertFalse(cfg.getBoolean("features.redstone-lag-detector.enabled"));
        assertFalse(cfg.getBoolean("features.ram-fixer.ghost-chunk-eviction"));
        assertFalse(cfg.getBoolean("features.mob-stacker.enabled"));
        assertFalse(cfg.getBoolean("chunk-optimizer.enable-prefetch"));
        assertFalse(cfg.getBoolean("features.fast-leaf-decay"));
    }
}
