package me.nolag.modules.mobstacker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter.TaskHandle;
import org.bukkit.*;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boss;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Strider;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.NPC;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;


public final class MobStackerModule implements Listener {

    private static final long DEFAULT_SLICE_BUDGET_NANOS = 1_000_000L;
    private static final Set<EntityType> BLACKLISTED_TYPES = Set.of(
            EntityType.PLAYER,
            EntityType.ARMOR_STAND,
            EntityType.ENDER_DRAGON,
            EntityType.WITHER,
            EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN,
            EntityType.GIANT
    );

    private final NoLag plugin;
    private final NamespacedKey stackKey;
    private final NamespacedKey noStackKey;
    private final NamespacedKey originalNameKey;
    private final NamespacedKey originalNameVisibleKey;

    private TaskHandle triggerTask;
    private TaskHandle scanTask;
    private StackScan activeScan;

    private final Map<ChunkKey, Long> pendingFoliaChunkScans = new ConcurrentHashMap<>();
    private final AtomicLong foliaScanGeneration = new AtomicLong();

    private volatile boolean enabled = false;
    private volatile boolean unsafeVirtualMerging = false;
    private volatile int maxStackSize = 50;
    private volatile int minMobsToStack = 8;
    private volatile double stackRadius = 5.0;
    private volatile double stackRadiusSq = 25.0;
    private volatile int checkIntervalMinutes = 3;
    private volatile long sliceBudgetNanos = DEFAULT_SLICE_BUDGET_NANOS;
    private volatile String mobSeparatedMessage = "&8[&6NoLag&8] &7- One mob separated! Remaining: &e%amount%";

    public MobStackerModule(NoLag plugin) {
        this.plugin = plugin;
        this.stackKey = new NamespacedKey(plugin, "stack_amount");
        this.noStackKey = new NamespacedKey(plugin, "no_stack_temp");
        this.originalNameKey = new NamespacedKey(plugin, "original_mob_name");
        this.originalNameVisibleKey = new NamespacedKey(plugin, "original_mob_name_visible");
        reload();
        startTask();
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.enabled = cfg.getBoolean("features.mob-stacker.enabled", false);
        // No reliable per-mob serialization/restore contract exists for virtual stacks.
        // Existing stacks still unwind; merging is disabled even when enabled=true.
        this.unsafeVirtualMerging = false;
        if (enabled) {
            plugin.getLogger().warning("[MobStacker] New virtual merges disabled: unique mob state, drops and third-party data cannot be restored losslessly. Existing virtual stacks still unwind.");
        }
        this.maxStackSize = Math.max(2, cfg.getInt("features.mob-stacker.max-stack-size", 50));
        this.minMobsToStack = Math.max(2, cfg.getInt("features.mob-stacker.min-mobs-to-stack", 8));
        this.stackRadius = Math.max(1.0, cfg.getDouble("features.mob-stacker.radius", 5.0));
        this.stackRadiusSq = stackRadius * stackRadius;
        this.checkIntervalMinutes = Math.max(1, cfg.getInt("features.mob-stacker.check-interval", 3));
        this.sliceBudgetNanos = Math.max(
                250_000L,
                Math.min(
                        4_000_000L,
                        Math.round(cfg.getDouble("features.mob-stacker.slice-budget-ms", 1.0D) * 1_000_000.0D)
                )
        );
        this.mobSeparatedMessage = cfg.getString(
                "messages.mob-separated",
                "&8[&6NoLag&8] &7- One mob separated! Remaining: &e%amount%"
        );
    }

    public void startTask() {
        stop();
        if (!enabled || !unsafeVirtualMerging) return;
        if (plugin.getSchedulerAdapter().isFolia()) {


            return;
        }

        long intervalTicks = checkIntervalMinutes * 60L * 20L;
        triggerTask = plugin.getSchedulerAdapter().runGlobalTimer(
                this::beginStackingRun,
                200L,
                intervalTicks
        );
    }

    private void beginStackingRun() {
        if (!enabled || activeScan != null) return;

        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        worlds.removeIf(world -> world == null);
        if (worlds.isEmpty()) return;

        activeScan = new StackScan(worlds);
        scanTask = plugin.getSchedulerAdapter().runGlobalTimer(this::processStackingSlice, 1L, 1L);
    }

    private void processStackingSlice() {
        StackScan scan = activeScan;
        if (scan == null) return;

        if (scan.runSlice(sliceBudgetNanos)) {
            if (scanTask != null) {
                scanTask.cancel();
                scanTask = null;
            }
            activeScan = null;
        }
    }

    public void stop() {
        if (triggerTask != null) {
            triggerTask.cancel();
            triggerTask = null;
        }
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        activeScan = null;


        foliaScanGeneration.incrementAndGet();
        pendingFoliaChunkScans.clear();
    }

    public boolean isEligible(Entity entity) {
        if (!(entity instanceof LivingEntity le) || !entity.isValid() || entity.isDead()) return false;
        if (BLACKLISTED_TYPES.contains(entity.getType())) return false;
        if (le instanceof Player || le instanceof ArmorStand || le instanceof Boss || le instanceof NPC) return false;
        if (le instanceof Villager || le instanceof WanderingTrader) return false;
        if (le instanceof Tameable tameable && tameable.isTamed()) return false;
        if (le.isLeashed() || !le.getPassengers().isEmpty() || le.isInsideVehicle()) return false;
        if (le.getPersistentDataContainer().has(noStackKey, PersistentDataType.BYTE)) return false;
        return le.getCustomName() == null || hasStackData(le);
    }

    // B-04 fix: Extended compatibility contract to preserve distinct mob traits and drops
    public boolean isCompatible(LivingEntity a, LivingEntity b) {
        if (a == null || b == null || !a.isValid() || !b.isValid() || a.isDead() || b.isDead()) {
            return false;
        }
        if (a.getType() != b.getType()) {
            return false;
        }
        if (!isEligible(a) || !isEligible(b)) {
            return false;
        }

        // Equipment check: preserve custom/dropped gear
        org.bukkit.inventory.EntityEquipment eqA = a.getEquipment();
        org.bukkit.inventory.EntityEquipment eqB = b.getEquipment();
        if (hasAnyEquipment(eqA) || hasAnyEquipment(eqB)) {
            return false;
        }

        // Health difference check
        if (Math.abs(a.getHealth() - b.getHealth()) > 0.5D) {
            return false;
        }

        // Potion effects check
        if (!a.getActivePotionEffects().isEmpty() || !b.getActivePotionEffects().isEmpty()) {
            return false;
        }

        // Specific mob traits and variants
        if (a instanceof Ageable ageA && b instanceof Ageable ageB && ageA.isAdult() != ageB.isAdult()) {
            return false;
        }
        if (a instanceof Sheep sA && b instanceof Sheep sB && sA.getColor() != sB.getColor()) {
            return false;
        }
        if (a instanceof Slime slA && b instanceof Slime slB && slA.getSize() != slB.getSize()) {
            return false;
        }
        if (a instanceof Creeper cA && b instanceof Creeper cB && cA.isPowered() != cB.isPowered()) {
            return false;
        }
        if (a instanceof Zombie zA && b instanceof Zombie zB && zA.isBaby() != zB.isBaby()) {
            return false;
        }
        if (a instanceof Pig pA && b instanceof Pig pB && pA.hasSaddle() != pB.hasSaddle()) {
            return false;
        }
        if (a instanceof Strider stA && b instanceof Strider stB && stA.hasSaddle() != stB.hasSaddle()) {
            return false;
        }
        if (a instanceof Wolf wA && b instanceof Wolf wB && (wA.isAngry() != wB.isAngry() || wA.getCollarColor() != wB.getCollarColor())) {
            return false;
        }

        return true;
    }

    private boolean hasAnyEquipment(org.bukkit.inventory.EntityEquipment eq) {
        if (eq == null) return false;
        return (eq.getItemInMainHand() != null && eq.getItemInMainHand().getType() != Material.AIR)
                || (eq.getItemInOffHand() != null && eq.getItemInOffHand().getType() != Material.AIR)
                || (eq.getHelmet() != null && eq.getHelmet().getType() != Material.AIR)
                || (eq.getChestplate() != null && eq.getChestplate().getType() != Material.AIR)
                || (eq.getLeggings() != null && eq.getLeggings().getType() != Material.AIR)
                || (eq.getBoots() != null && eq.getBoots().getType() != Material.AIR);
    }

    public void performStacking(Chunk chunk) {
        if (!unsafeVirtualMerging) return;
        if (chunk == null || !chunk.isLoaded() || !chunk.isEntitiesLoaded()) return;

        Entity[] entities = chunk.getEntities();
        if (entities.length < minMobsToStack) return;

        Map<EntityType, LivingEntity> representativeMap = new HashMap<>();
        Map<EntityType, Integer> countMap = new HashMap<>();

        for (Entity entity : entities) {
            if (!isEligible(entity)) continue;

            LivingEntity le = (LivingEntity) entity;
            EntityType type = entity.getType();
            LivingEntity rep = representativeMap.get(type);
            if (rep == null || !rep.isValid() || rep.isDead()) {
                representativeMap.put(type, le);
                countMap.put(type, getStackAmount(le));
                continue;
            }

            Location representativeLocation = rep.getLocation();
            Location entityLocation = le.getLocation();
            double dx = representativeLocation.getX() - entityLocation.getX();
            double dy = representativeLocation.getY() - entityLocation.getY();
            double dz = representativeLocation.getZ() - entityLocation.getZ();

            if ((dx * dx + dy * dy + dz * dz) > stackRadiusSq) continue;
            // B-04 fix: use comprehensive isCompatible check
            if (!isCompatible(rep, le)) continue;

            int currentCount = countMap.getOrDefault(type, 1);
            if (currentCount >= maxStackSize) continue;

            int leAmount = getStackAmount(le);
            int transfer = Math.min(leAmount, maxStackSize - currentCount);
            if (transfer <= 0) continue;

            countMap.put(type, currentCount + transfer);
            if (transfer >= leAmount) {
                le.remove();
            } else {
                setStackAmount(le, leAmount - transfer);
            }
        }

        for (Map.Entry<EntityType, LivingEntity> entry : representativeMap.entrySet()) {
            LivingEntity rep = entry.getValue();
            if (rep.isValid() && !rep.isDead()) {
                setStackAmount(rep, Math.min(maxStackSize, countMap.getOrDefault(entry.getKey(), 1)));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!enabled || !plugin.getSchedulerAdapter().isFolia()) return;
        scheduleFoliaChunkStack(event.getChunk());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!enabled || !unsafeVirtualMerging) return;

        Entity spawned = event.getEntity();
        if (!(spawned instanceof LivingEntity le) || !isEligible(spawned)) return;

        if (plugin.getSchedulerAdapter().isFolia()) {
            scheduleFoliaChunkStack(spawned.getChunk());
            return;
        }

        // B-07 fix: Defer stacking merge until entity spawn is finalized across all plugins.
        // If another plugin at HIGHEST cancels the spawn, the existing mob stack is NOT modified.
        plugin.getSchedulerAdapter().runEntityLater(le, () -> tryMergeSpawnedMob(le), 1L);
    }

    public void tryMergeSpawnedMob(LivingEntity le) {
        if (!unsafeVirtualMerging) return;
        if (!enabled || le == null || !le.isValid() || le.isDead() || !isEligible(le)) return;

        for (Entity nearby : le.getNearbyEntities(stackRadius, stackRadius, stackRadius)) {
            if (!(nearby instanceof LivingEntity nearbyLe)) continue;
            if (!isCompatible(le, nearbyLe)) continue;

            int currentAmount = getStackAmount(nearbyLe);
            if (currentAmount < maxStackSize) {
                setStackAmount(nearbyLe, currentAmount + 1);
                le.remove();
                return;
            }
        }
    }

    private void scheduleFoliaChunkStack(Chunk chunk) {
        if (chunk == null || !chunk.isLoaded()) return;

        World world = chunk.getWorld();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        ChunkKey key = new ChunkKey(world.getUID(), chunkX, chunkZ);
        long generation = foliaScanGeneration.get();
        if (pendingFoliaChunkScans.putIfAbsent(key, generation) != null) return;


        Location anchor = new Location(world, (chunkX << 4) + 8.0D, 0.0D, (chunkZ << 4) + 8.0D);
        TaskHandle handle = plugin.getSchedulerAdapter().runRegionLater(anchor, () -> {
            pendingFoliaChunkScans.remove(key, generation);
            if (generation != foliaScanGeneration.get() || !enabled) return;
            try {
                if (chunk.isLoaded() && chunk.isEntitiesLoaded()) {
                    performStacking(chunk);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().fine("[MobStacker] Region scan skipped: " + ex.getMessage());
            }
        }, 1L);


        if (handle == null || handle.isCancelled()) {
            pendingFoliaChunkScans.remove(key, generation);
        }
    }

    private record ChunkKey(UUID worldId, int x, int z) { }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // Existing virtual stacks must still unwind when the scanner is disabled.
        if (!hasStackData(entity)) return;

        int amount = getStackAmount(entity);
        if (amount <= 1) {
            entity.getPersistentDataContainer().remove(stackKey);
            return;
        }

        int remaining = amount - 1;
        EntityType type = entity.getType();
        Location loc = entity.getLocation();
        World world = loc.getWorld();
        Class<? extends LivingEntity> entityClass = getLivingEntityClass(type);
        if (world == null || entityClass == null) return;
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;

        plugin.getSchedulerAdapter().runRegionLater(loc, () -> {
            try {
                LivingEntity nextMob = world.spawn(loc, entityClass, spawned -> {
                    spawned.getPersistentDataContainer().set(noStackKey, PersistentDataType.BYTE, (byte) 1);
                    if (entity instanceof Ageable oldAge && spawned instanceof Ageable newAge) {
                        if (oldAge.isAdult()) newAge.setAdult();
                        else newAge.setBaby();
                    }
                    if (entity instanceof Sheep oldSheep && spawned instanceof Sheep newSheep) {
                        newSheep.setColor(oldSheep.getColor());
                    }
                    if (entity instanceof Slime oldSlime && spawned instanceof Slime newSlime) {
                        newSlime.setSize(oldSlime.getSize());
                    }
                });

                if (nextMob != null) {
                    setStackAmount(nextMob, remaining);
                    plugin.getSchedulerAdapter().runEntityLater(nextMob, () ->
                            nextMob.getPersistentDataContainer().remove(noStackKey), 40L);
                }
            } catch (Throwable ignored) {
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("nolag.unstack")) return;

        if (!(event.getRightClicked() instanceof LivingEntity le) || !hasStackData(le)) return;

        int amount = getStackAmount(le);
        if (amount <= 1) return;

        event.setCancelled(true);

        EntityType type = le.getType();
        Location loc = le.getLocation().add(0.5, 0, 0.5);
        Class<? extends LivingEntity> entityClass = getLivingEntityClass(type);
        boolean separatedSuccessfully = false;
        if (entityClass != null) {
            try {
                LivingEntity separated = le.getWorld().spawn(loc, entityClass, spawned -> {
                    spawned.getPersistentDataContainer().set(noStackKey, PersistentDataType.BYTE, (byte) 1);
                    if (le instanceof Ageable oldAge && spawned instanceof Ageable newAge) {
                        if (oldAge.isAdult()) newAge.setAdult();
                        else newAge.setBaby();
                    }
                });
                if (separated != null) {
                    setStackAmount(le, amount - 1);
                    separatedSuccessfully = true;
                    plugin.getSchedulerAdapter().runEntityLater(separated, () ->
                            separated.getPersistentDataContainer().remove(noStackKey), 100L);
                }
            } catch (Throwable ignored) {
            }
        }

        if (!separatedSuccessfully) {
            player.sendMessage(ChatColor.RED + "Unable to separate this mob here; the stack was left unchanged.");
            return;
        }
        String msg = mobSeparatedMessage == null ? "" : mobSeparatedMessage.replace("%amount%", String.valueOf(amount - 1));
        if (!msg.isBlank()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    public void setStackAmount(LivingEntity entity, int amount) {
        if (entity == null) return;
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (amount <= 1) {
            pdc.remove(stackKey);
            String original = pdc.get(originalNameKey, PersistentDataType.STRING);
            Byte visible = pdc.get(originalNameVisibleKey, PersistentDataType.BYTE);
            if (original != null) {
                entity.setCustomName(original);
                entity.setCustomNameVisible(visible != null && visible != 0);
            } else if (entity.getCustomName() != null
                    && ChatColor.stripColor(entity.getCustomName()).matches(
                            "(?i)" + entity.getType().name().replace('_', ' ') + " X\\d+")) {
                // Old virtual stacks have no original-name snapshot. Clear only our label.
                entity.setCustomName(null);
                entity.setCustomNameVisible(false);
            }
            pdc.remove(originalNameKey);
            pdc.remove(originalNameVisibleKey);
            return;
        }

        if (!pdc.has(stackKey, PersistentDataType.INTEGER) && entity.getCustomName() != null) {
            pdc.set(originalNameKey, PersistentDataType.STRING, entity.getCustomName());
            pdc.set(originalNameVisibleKey, PersistentDataType.BYTE,
                    (byte) (entity.isCustomNameVisible() ? 1 : 0));
        }
        int safeAmount = Math.min(maxStackSize, amount);
        pdc.set(stackKey, PersistentDataType.INTEGER, safeAmount);

        String rawType = entity.getType().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        String[] words = rawType.split("\\s+");
        StringBuilder formatted = new StringBuilder(rawType.length() + 8);
        for (String word : words) {
            if (!word.isEmpty()) {
                formatted.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1))
                        .append(' ');
            }
        }
        String name = ChatColor.translateAlternateColorCodes('&',
                "&b&l" + formatted.toString().trim() + " &e&lX" + safeAmount);
        if (!name.equals(entity.getCustomName())) {
            entity.setCustomName(name);
            entity.setCustomNameVisible(true);
        }
    }

    public boolean hasStackData(LivingEntity entity) {
        if (entity == null) return false;
        Integer amount = entity.getPersistentDataContainer().get(stackKey, PersistentDataType.INTEGER);
        return amount != null && amount > 1;
    }

    public int getStackAmount(LivingEntity entity) {
        if (entity == null) return 1;
        Integer amount = entity.getPersistentDataContainer().get(stackKey, PersistentDataType.INTEGER);
        return amount == null ? 1 : Math.max(1, amount);
    }

    private Class<? extends LivingEntity> getLivingEntityClass(EntityType type) {
        try {
            Class<?> entityClass = type.getEntityClass();
            if (entityClass != null && LivingEntity.class.isAssignableFrom(entityClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends LivingEntity> cast = (Class<? extends LivingEntity>) entityClass;
                return cast;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public String getStackedDescription(LivingEntity entity) {
        if (!hasStackData(entity)) return "";
        int amount = getStackAmount(entity);
        return amount <= 1 ? "" : "x" + amount;
    }

    public String formatAmount(int amount) {
        return String.format(Locale.ROOT, "%,d", Math.max(0, amount));
    }

    private final class StackScan {
        private final List<World> worlds;
        private int worldIndex;
        private int chunkIndex;
        private Chunk[] chunks;
        private boolean finished;

        private StackScan(List<World> worlds) {
            this.worlds = worlds;
        }

        boolean runSlice(long budgetNanos) {
            if (finished) return true;
            long deadline = System.nanoTime() + Math.max(250_000L, budgetNanos);

            while (worldIndex < worlds.size()) {
                World world = worlds.get(worldIndex);
                if (chunks == null) {
                    try {
                        chunks = world.getLoadedChunks();
                    } catch (Throwable ignored) {
                        chunks = new Chunk[0];
                    }
                    chunkIndex = 0;
                }

                while (chunkIndex < chunks.length) {
                    Chunk chunk = chunks[chunkIndex++];
                    if (chunk != null && chunk.isLoaded() && chunk.isEntitiesLoaded()) {
                        performStacking(chunk);
                    }
                    if (System.nanoTime() >= deadline) return false;
                }

                worldIndex++;
                chunks = null;
            }

            finished = true;
            return true;
        }
    }
}
