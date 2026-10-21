package me.nolag.modules.ram;


public interface RamFixerService {


    long getTotalFreedMb();


    int getAutoSweepCount();


    int getGhostChunksUnloaded();


    double getCurrentUsagePercent();
}
