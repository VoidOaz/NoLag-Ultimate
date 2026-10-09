package me.nolag.modules.cleanup;

import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Golem;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Budgeted ground-item/entity cleanup manager.
 *
 * <p>Cleanup no longer builds an unbounded entity queue by scanning every loaded
 * chunk synchronously. The scanner itself is sliced across ticks, so even a
 * manual cleanup on a large server does not create a one-tick world scan.</p>
 */
public final class CleanupManager implements Listener {

    private static final long DEFAULT_SCAN_BUDGET_NANOS = 2_000_000L;
    private final NoLag plugin;

    private int countdownSeconds;
    private TaskHandle timerTask;
    private TaskHandle cleanupTask;
    private CleanupRun activeRun;
    private long lastEmergencyCleanupTime;

    public CleanupManager(NoLag plugin) {
        this.plugin = plugin;
        resetTimer();
        startTimer();
    }

    public void startTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        resetTimer();
        timerTask = plugin.getSchedulerAdapter().runGlobalTimer(this::tickTimer, 20L, 20L);
    }

    private void tickTimer() {
        if (countdownSeconds <= 0) {
            performCleanup(false);
            countdownSeconds = cleanupIntervalSeconds() * 60;
            return;
        }

        checkAndBroadcast(countdownSeconds);

        if (!plugin.getSchedulerAdapter().isFolia()) {
            double tps = plugin.getTPSMonitor().getTPS();
            double threshold = plugin.getConfig().getDouble("settings.tps-threshold", 16.0D);
            long cooldownMs = Math.max(1L, plugin.getConfig().getLong("settings.emergency-cleanup-cooldown", 60L)) * 1000L;
            long now = System.currentTimeMillis();

            if (tps < threshold && countdownSeconds > 30 && now - lastEmergencyCleanupTime >= cooldownMs) {
                CleanupResult result = performCleanup(true);
                if (result.started()) {
                    lastEmergencyCleanupTime = now;
                    String warning = plugin.getConfig()
                            .getString("messages.low-tps-warning", "&4&lWARNING! &cLow TPS detected (&e%tps%&c). Emergency cleanup starting...")
                            .replace("%tps%", String.format(Locale.ROOT, "%.1f", tps));
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', warning));
                    countdownSeconds = cleanupIntervalSeconds() * 60;
                }
            }
        }

        countdownSeconds--;
    }

    public void stopTimer() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        cancelActiveRun();
    }

    public void resetTimer() {
        countdownSeconds = cleanupIntervalSeconds() * 60;
    }

    private int cleanupIntervalSeconds() {
        return Math.max(1, plugin.getConfig().getInt("settings.cleanup-interval", 5));
    }

    private void checkAndBroadcast(int remaining) {
        int[] intervals = {300, 120, 60, 30, 10};
        String[] keys = {"five-minutes", "two-minutes", "one-minute", "thirty-seconds", "ten-seconds"};
        for (int i = 0; i < intervals.length; i++) {
            if (remaining == intervals[i]) {
                String msg = plugin.getConfig().getString("announcements." + keys[i], "");
                if (msg != null && !msg.isEmpty()) {
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
                return;
            }
        }
    }

    /** Starts a fully sliced scan; actual removals happen on subsequent ticks. */
    public CleanupResult performCleanup(boolean emergency) {
        if (plugin.getSchedulerAdapter().isFolia()) {
            return new CleanupResult(0, 0, false);
        }
        if (activeRun != null) {
            return new CleanupResult(0, 0, false);
        }

        boolean removeItems = plugin.getConfig().getBoolean("features.remove-items", true);
        boolean removeMobs = plugin.getConfig().getBoolean("features.remove-mobs", false) || emergency;
        if (!removeItems && !removeMobs) {
            return new CleanupResult(0, 0, false);
        }

        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        worlds.removeIf(w -> w == null);
        if (worlds.isEmpty()) {
            return new CleanupResult(0, 0, false);
        }

        int maxRemovalsPerTick = clampBatchSize(plugin.getConfig().getInt(
                emergency ? "settings.cleanup-entities-per-tick-emergency" : "settings.cleanup-entities-per-tick",
                emergency ? 1000 : 300));
        double budgetMs = plugin.getConfig().getDouble("settings.cleanup-slice-budget-ms-per-tick", 2.0D);
        long budgetNanos = Math.max(250_000L, Math.min(8_000_000L,
                Math.round(Math.max(0.25D, budgetMs) * 1_000_000.0D)));

        activeRun = new CleanupRun(worlds, removeItems, removeMobs, maxRemovalsPerTick, budgetNanos);
        cleanupTask = plugin.getSchedulerAdapter().runGlobalTimer(this::processCleanupSlice, 1L, 1L);
        return new CleanupResult(0, 0, true);
    }

    private void processCleanupSlice() {
        CleanupRun run = activeRun;
        if (run == null) return;

        if (run.runSlice()) {
            int items = run.itemsRemoved;
            int mobs = run.mobsRemoved;
            long elapsed = run.elapsedNanos();
            cancelActiveRun();

            String prefix = plugin.getConfig().getString("messages.prefix", "&8[&6NoLag&8] ");
            String msg = prefix + "&fCleanup complete: &e" + items + " items &7and &e" + mobs + " mobs &7cleared.";
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', msg));
            if (plugin.getPerformanceAnalyzer() != null && plugin.getPerformanceAnalyzer().isEnabled()) {
                plugin.getPerformanceAnalyzer().record("Cleanup", elapsed, 1);
            }
        }
    }

    private void cancelActiveRun() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        activeRun = null;
    }

    private boolean isProtectedItem(Item item) {
        if (item == null || !item.isValid() || item.isDead()) return true;

        ItemMeta meta = item.getItemStack().getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return true;
        }

        String customName = item.getCustomName();
        if (customName == null || customName.isBlank()) {
            return false;
        }

        // NoLag's own expiry metadata is deliberately not considered a protection marker.
        if (plugin.getItemExpiryModule() != null
                && item.getPersistentDataContainer().has(plugin.getItemExpiryModule().getExpireKey(), PersistentDataType.LONG)) {
            return false;
        }

        // Entity-level custom names are usually intentional player/plug-in labels.
        return true;
    }

    private boolean isEligibleForCleanup(LivingEntity le) {
        if (le == null || !le.isValid() || le.isDead()) return false;
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) return false;
        if (le instanceof Villager || le instanceof WanderingTrader || le instanceof Golem || le instanceof Allay) return false;
        if (le instanceof Tameable tameable && tameable.isTamed()) return false;
        if (le instanceof AbstractHorse horse && horse.isTamed()) return false;
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) return false;
        if (le.getCustomName() != null
                && plugin.getMobStackerModule() != null
                && !plugin.getMobStackerModule().hasStackData(le)) return false;
        return true;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    /** Retained for lifecycle compatibility; the new scanner has no unbounded queue. */
    public void trimCaches() {
        // Intentionally empty. The active scan is stateful and should not be discarded
        // merely because the memory subsystem runs its cache maintenance pass.
    }

    private static int clampBatchSize(int value) {
        int normalized = value > 0 ? value : 300;
        return Math.max(1, Math.min(5000, normalized));
    }

    public record CleanupResult(int items, int mobs, boolean started) {
        public CleanupResult(int items, int mobs) {
            this(items, mobs, false);
        }
    }

    private final class CleanupRun {
        private final List<World> worlds;
        private final boolean removeItems;
        private final boolean removeMobs;
        private final int maxRemovalsPerTick;
        private final long budgetNanos;
        private final long startedNanos = System.nanoTime();

        private int worldIndex;
        private Chunk[] chunks;
        private int chunkIndex;
        private World currentWorld;
        private int itemsRemoved;
        private int mobsRemoved;
        private boolean finished;

        private CleanupRun(List<World> worlds, boolean removeItems, boolean removeMobs,
                           int maxRemovalsPerTick, long budgetNanos) {
            this.worlds = worlds;
            this.removeItems = removeItems;
            this.removeMobs = removeMobs;
            this.maxRemovalsPerTick = maxRemovalsPerTick;
            this.budgetNanos = Math.max(250_000L, Math.min(8_000_000L, budgetNanos));
        }

        boolean runSlice() {
            if (finished) return true;
            long deadline = System.nanoTime() + budgetNanos;
            int removalsThisTick = 0;

            while (currentWorld != null || worldIndex < worlds.size()) {
                if (currentWorld == null) {
                    currentWorld = worlds.get(worldIndex++);
                    try {
                        chunks = currentWorld.getLoadedChunks();
                    } catch (Throwable ignored) {
                        chunks = null;
                    }
                    chunkIndex = 0;
                    if (chunks == null || chunks.length == 0) {
                        currentWorld = null;
                        continue;
                    }
                }

                while (chunkIndex < chunks.length) {
                    Chunk chunk = chunks[chunkIndex++];
                    if (chunk == null || !chunk.isLoaded()) continue;

                    Entity[] entities;
                    try {
                        entities = chunk.getEntities();
                    } catch (Throwable ignored) {
                        continue;
                    }

                    for (Entity entity : entities) {
                        if (entity == null || !entity.isValid() || entity.isDead()) continue;

                        boolean remove = false;
                        if (removeItems && entity instanceof Item item) {
                            remove = !isProtectedItem(item);
                        } else if (removeMobs && entity instanceof LivingEntity living) {
                            remove = isEligibleForCleanup(living);
                        }

                        if (!remove) continue;
                        try {
                            entity.remove();
                            if (entity instanceof Item) itemsRemoved++;
                            else if (entity instanceof LivingEntity) mobsRemoved++;
                            removalsThisTick++;
                        } catch (Throwable ignored) {
                        }

                        if (removalsThisTick >= maxRemovalsPerTick || System.nanoTime() >= deadline) {
                            return false;
                        }
                    }

                    if (System.nanoTime() >= deadline) return false;
                }

                currentWorld = null;
                chunks = null;
            }

            finished = true;
            return true;
        }

        long elapsedNanos() {
            return Math.max(1L, System.nanoTime() - startedNanos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Kept as a listener so this class remains easy to extend with player-local
        // cleanup policies without introducing another listener registration.
    }
}
