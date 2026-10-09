package me.nolag.modules.ram;

/**
 * Read-only telemetry surface exposed by the RamFixer engine so that other
 * subsystems (GUI, commands) can display live statistics without touching
 * internal state directly.
 */
public interface RamFixerService {

    /** Total heap megabytes reclaimed by automated + manual sweeps since startup. */
    long getTotalFreedMb();

    /** Number of automated pressure sweeps executed since startup. */
    int getAutoSweepCount();

    /** Number of ghost chunks unloaded by the RamFixer engine since startup. */
    int getGhostChunksUnloaded();

    /** Current heap usage percentage (0-100). */
    double getCurrentUsagePercent();
}
