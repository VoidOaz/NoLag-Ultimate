#!/usr/bin/env python3
"""Offline safety regression guard; complements, but cannot replace, Paper/Folia integration tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import sys
root = Path(__file__).resolve().parents[1]
src = root / 'src/main/java/me/nolag'


def java(path):
    return (src / path).read_text(encoding='utf-8')


def check(name, ok):
    if not ok:
        raise AssertionError(name)
    print('PASS', name)


cfg = (root / 'src/main/resources/config.yml').read_text(encoding='utf-8')
ET.parse(root / 'pom.xml')
check('valid pom.xml', True)
try:
    import yaml
    settings = yaml.safe_load(cfg)
    check('valid YAML', bool(settings))
    check('no redundant autosave by default', not settings['features']['autosave-optimizer']['enabled'])
    check('redstone detector requires explicit opt-in', not settings['features']['redstone-lag-detector']['enabled'])
    check('chunk unloading opt-in', not settings['features']['ram-fixer']['ghost-chunk-eviction'])
except ImportError:
    print('SKIP yaml checks: install PyYAML')

item = java('modules/item/ItemStackerModule.java')
spawn = item.split('public void onItemSpawn(')[1].split('public void mergeSpawnedItem(')[0]
check('K-01 spawning does not delete an existing entity', 'other.remove()' not in spawn and 'runEntityLater' in spawn)
flood = java('modules/flood/FluidFloodProtectionModule.java')
check('K-02 no block deletion in flood module', not re.search(r'\.setType\s*\(\s*Material\.AIR', flood))
check('B-01 disperse honors legacy flags', 'if (cfg.breakMachineComponents)' in flood and 'if (cfg.neutralizeFluid)' in flood)
netty = java('modules/netty/NettyProtectionModule.java')
check('B-02 failed guard fails closed or tracks degraded', 'earlyStageInjector.hasChildGuard(channel)' in netty and 'channel.close();' in netty)
check('P-01 does not manually call World.save', '.save();' not in java('modules/autosave/AutosaveOptimizerModule.java'))
check('B-05 independent redstone enable switch', 'features.redstone-lag-detector.enabled' in java('modules/redstone/RedstoneLagDetectorModule.java'))
mobs = java('modules/mobstacker/MobStackerModule.java')
check('B-04/B-07 unsafe virtual merges disabled', 'this.unsafeVirtualMerging = false;' in mobs)
expiry = java('modules/item/ItemExpiryModule.java')
check('P-05 expiry uses a single rotating bounded queue', 'ConcurrentLinkedQueue<UUID>' in expiry and 'sweepQueue.poll()' in expiry and 'scheduleExpiry(' not in expiry)
check('H-06 merge reconciled after event', 'public void onItemMerge(' in expiry and 'runEntityLater(target' in expiry)
nether = java('modules/antiexploit/NetherChunkProtectionModule.java')
check('B-06 reload restarts maintenance', 'if (maintenanceTask == null)' in nether and 'startMaintenance();' in nether)
check('P-08 prefetch requires opt in', 'chunk-optimizer.enable-prefetch' in java('modules/chunk/ChunkOptimizationModule.java'))
check('P-13 lag spike does not scan worlds', 'Bukkit.getWorlds()' not in java('tps/TPSMonitor.java').split('private void handleLagSpike(')[1].split('public static int getEntityCountSafe(')[0])
print('Static regressions passed. Run Maven and staged server tests for runtime guarantees.')
