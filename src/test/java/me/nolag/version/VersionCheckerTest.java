package me.nolag.version;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VersionCheckerTest {
    @Test
    void onlyExplicitlyListedVersionsAreReportedAsSupported() {
        assertTrue(VersionChecker.isVersionSupported("1.21.8"));
        assertFalse(VersionChecker.isVersionSupported("1.22.99"));
        assertFalse(VersionChecker.isVersionSupported("27.0"));
        assertFalse(VersionChecker.isVersionSupported("1.21.999"));
        assertFalse(VersionChecker.isVersionSupported("invalid"));
    }
}
