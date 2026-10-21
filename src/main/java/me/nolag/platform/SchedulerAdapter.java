package me.nolag.platform;

import me.nolag.NoLag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public final class SchedulerAdapter {

    private static final String FOLIA_GLOBAL_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";
    private static final String FOLIA_REGION_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler";
    private static final String FOLIA_ASYNC_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.AsyncScheduler";
    private static final String FOLIA_ENTITY_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.EntityScheduler";
    private static final String FOLIA_SCHEDULED_TASK =
            "io.papermc.paper.threadedregions.scheduler.ScheduledTask";

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    private final NoLag plugin;
    private final boolean foliaDetected;
    private final boolean foliaEnabled;
    private final Set<TaskHandle> activeTasks = ConcurrentHashMap.newKeySet();

    private final Object globalRegionScheduler;
    private final Object regionScheduler;
    private final Object asyncScheduler;

    private final MethodHandle globalRun;
    private final MethodHandle globalRunDelayed;
    private final MethodHandle globalRunAtFixedRate;

    private final MethodHandle regionRun;
    private final MethodHandle regionRunDelayed;
    private final MethodHandle regionRunAtFixedRate;

    private final MethodHandle asyncRunNow;
    private final MethodHandle asyncRunDelayed;
    private final MethodHandle asyncRunAtFixedRate;

    private final MethodHandle entityGetScheduler;
    private final MethodHandle entityRun;
    private final MethodHandle entityRunDelayed;
    private final MethodHandle entityRunAtFixedRate;

    private final MethodHandle scheduledCancel;
    private final MethodHandle scheduledIsCancelled;

    private volatile boolean foliaBindingWarningLogged;

    public SchedulerAdapter(NoLag plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin cannot be null");
        }

        this.plugin = plugin;
        this.foliaDetected = PlatformDetector.detect() == PlatformDetector.Platform.FOLIA;

        if (!foliaDetected) {
            globalRegionScheduler = null;
            regionScheduler = null;
            asyncScheduler = null;

            globalRun = null;
            globalRunDelayed = null;
            globalRunAtFixedRate = null;
            regionRun = null;
            regionRunDelayed = null;
            regionRunAtFixedRate = null;
            asyncRunNow = null;
            asyncRunDelayed = null;
            asyncRunAtFixedRate = null;
            entityGetScheduler = null;
            entityRun = null;
            entityRunDelayed = null;
            entityRunAtFixedRate = null;
            scheduledCancel = null;
            scheduledIsCancelled = null;
            foliaEnabled = false;
            return;
        }

        FoliaBindings bindings = createFoliaBindings();
        globalRegionScheduler = bindings.globalScheduler;
        regionScheduler = bindings.regionScheduler;
        asyncScheduler = bindings.asyncScheduler;
        globalRun = bindings.globalRun;
        globalRunDelayed = bindings.globalRunDelayed;
        globalRunAtFixedRate = bindings.globalRunAtFixedRate;
        regionRun = bindings.regionRun;
        regionRunDelayed = bindings.regionRunDelayed;
        regionRunAtFixedRate = bindings.regionRunAtFixedRate;
        asyncRunNow = bindings.asyncRunNow;
        asyncRunDelayed = bindings.asyncRunDelayed;
        asyncRunAtFixedRate = bindings.asyncRunAtFixedRate;
        entityGetScheduler = bindings.entityGetScheduler;
        entityRun = bindings.entityRun;
        entityRunDelayed = bindings.entityRunDelayed;
        entityRunAtFixedRate = bindings.entityRunAtFixedRate;
        scheduledCancel = bindings.scheduledCancel;
        scheduledIsCancelled = bindings.scheduledIsCancelled;
        foliaEnabled = bindings.valid;
    }

    private FoliaBindings createFoliaBindings() {
        try {
            Object global = invokeStatic(Bukkit.class, "getGlobalRegionScheduler");
            Object region = invokeStatic(Bukkit.class, "getRegionScheduler");
            Object async = invokeStatic(Bukkit.class, "getAsyncScheduler");

            if (global == null || region == null || async == null) {
                logFoliaBindingFailure("one or more scheduler instances are null");
                return FoliaBindings.invalid();
            }

            Class<?> globalType = Class.forName(FOLIA_GLOBAL_SCHEDULER);
            Class<?> regionType = Class.forName(FOLIA_REGION_SCHEDULER);
            Class<?> asyncType = Class.forName(FOLIA_ASYNC_SCHEDULER);
            Class<?> entityType = Class.forName(FOLIA_ENTITY_SCHEDULER);
            Class<?> scheduledTaskType = Class.forName(FOLIA_SCHEDULED_TASK);

            MethodHandle globalRun = bind(globalType, global, "run",
                    MethodType.methodType(Object.class, Plugin.class, Consumer.class),
                    Plugin.class, Consumer.class);
            MethodHandle globalRunDelayed = bind(globalType, global, "runDelayed",
                    MethodType.methodType(Object.class, Plugin.class, Consumer.class, long.class),
                    Plugin.class, Consumer.class, long.class);
            MethodHandle globalRunAtFixedRate = bind(globalType, global, "runAtFixedRate",
                    MethodType.methodType(Object.class, Plugin.class, Consumer.class, long.class, long.class),
                    Plugin.class, Consumer.class, long.class, long.class);

            MethodHandle regionRun = bind(regionType, region,
                    "run",
                    MethodType.methodType(Object.class, Plugin.class, Location.class, Consumer.class),
                    Plugin.class, Location.class, Consumer.class);
            MethodHandle regionRunDelayed = bind(regionType, region,
                    "runDelayed",
                    MethodType.methodType(Object.class, Plugin.class, Location.class, Consumer.class, long.class),
                    Plugin.class, Location.class, Consumer.class, long.class);
            MethodHandle regionRunAtFixedRate = null;
            try {
                regionRunAtFixedRate = bind(regionType, region,
                        "runAtFixedRate",
                        MethodType.methodType(Object.class, Plugin.class, Location.class, Consumer.class, long.class, long.class),
                        Plugin.class, Location.class, Consumer.class, long.class, long.class);
            } catch (Throwable ignored) {}

            MethodHandle asyncRunNow = bind(asyncType, async, "runNow",
                    MethodType.methodType(Object.class, Plugin.class, Consumer.class),
                    Plugin.class, Consumer.class);
            MethodHandle asyncRunDelayed = null;
            MethodHandle asyncRunAtFixedRate = null;
            try {
                asyncRunDelayed = bind(asyncType, async, "runDelayed",
                        MethodType.methodType(Object.class, Plugin.class, Consumer.class, long.class, TimeUnit.class),
                        Plugin.class, Consumer.class, long.class, TimeUnit.class);
                asyncRunAtFixedRate = bind(asyncType, async, "runAtFixedRate",
                        MethodType.methodType(Object.class, Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class),
                        Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
            } catch (Throwable ignored) {}

            MethodHandle entityGetScheduler = adapt(
                    unreflect(Entity.class.getMethod("getScheduler")),
                    MethodType.methodType(Object.class, Entity.class));


            MethodHandle entityRun = adapt(
                    unreflect(entityType.getMethod("run", Plugin.class, Consumer.class, Runnable.class)),
                    MethodType.methodType(Object.class, Object.class, Plugin.class, Consumer.class, Runnable.class));
            MethodHandle entityRunDelayed = adapt(
                    unreflect(entityType.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class)),
                    MethodType.methodType(Object.class, Object.class, Plugin.class, Consumer.class, Runnable.class, long.class));
            MethodHandle entityRunAtFixedRate = null;
            try {
                entityRunAtFixedRate = adapt(
                        unreflect(entityType.getMethod("runAtFixedRate", Plugin.class, Consumer.class, Runnable.class, long.class, long.class)),
                        MethodType.methodType(Object.class, Object.class, Plugin.class, Consumer.class, Runnable.class, long.class, long.class));
            } catch (Throwable ignored) {}

            MethodHandle scheduledCancel = adapt(
                    unreflect(scheduledTaskType.getMethod("cancel")),
                    MethodType.methodType(void.class, Object.class));
            MethodHandle scheduledIsCancelled = adapt(
                    unreflect(scheduledTaskType.getMethod("isCancelled")),
                    MethodType.methodType(boolean.class, Object.class));

            return new FoliaBindings(
                    true,
                    global,
                    region,
                    async,
                    globalRun,
                    globalRunDelayed,
                    globalRunAtFixedRate,
                    regionRun,
                    regionRunDelayed,
                    regionRunAtFixedRate,
                    asyncRunNow,
                    asyncRunDelayed,
                    asyncRunAtFixedRate,
                    entityGetScheduler,
                    entityRun,
                    entityRunDelayed,
                    entityRunAtFixedRate,
                    scheduledCancel,
                    scheduledIsCancelled
            );
        } catch (Throwable e) {
            logFoliaBindingFailure(e.getClass().getSimpleName() + ": " + safeMessage(e));
            return FoliaBindings.invalid();
        }
    }

    private static Object invokeStatic(Class<?> owner, String methodName) throws Throwable {
        Method method = owner.getMethod(methodName);
        return method.invoke(null);
    }

    private static MethodHandle bind(Class<?> owner, Object target, String name,
                                     MethodType adaptedType, Class<?>... parameterTypes)
            throws Throwable {
        Method method = owner.getMethod(name, parameterTypes);
        return adapt(unreflect(method).bindTo(target), adaptedType);
    }

    private static MethodHandle unreflect(Method method) throws IllegalAccessException {
        return LOOKUP.unreflect(method);
    }

    private static MethodHandle adapt(MethodHandle handle, MethodType targetType) {
        return handle.asType(targetType);
    }

    private void logFoliaBindingFailure(String reason) {
        if (foliaBindingWarningLogged) return;
        foliaBindingWarningLogged = true;
        plugin.getLogger().severe("Folia scheduler binding failed (" + reason + "). "
                + "No unsafe Bukkit fallback will be used; Folia-bound operations fail closed.");
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? "unknown error" : message;
    }


    public boolean isFolia() {
        return foliaDetected;
    }


    public boolean isFoliaSchedulerReady() {
        return foliaDetected && foliaEnabled;
    }

    public TaskHandle runGlobal(Runnable runnable) {
        if (runnable == null) return TaskHandle.EMPTY;

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
        } else {
            if (!foliaEnabled || globalRun == null) return TaskHandle.EMPTY;

            Object scheduled = invokeGlobalRun(new Callback(runnable));
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runGlobalLater(Runnable runnable, long delayTicks) {
        if (runnable == null) return TaskHandle.EMPTY;
        long safeDelay = Math.max(1L, delayTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
        } else {
            if (!foliaEnabled || globalRunDelayed == null) return TaskHandle.EMPTY;

            Object scheduled = invokeGlobalRunDelayed(new Callback(runnable), safeDelay);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runGlobalTimer(Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (runnable == null) return TaskHandle.EMPTY;
        long initial = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, initial, period));
        } else {
            if (!foliaEnabled || globalRunAtFixedRate == null) return TaskHandle.EMPTY;
            Object scheduled = invokeGlobalRunAtFixedRate(new Callback(runnable), initial, period);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }

        track(handle);
        return handle;
    }

    public TaskHandle runRegion(Location location, Runnable runnable) {
        if (runnable == null || location == null || location.getWorld() == null) {
            return TaskHandle.EMPTY;
        }

        final TaskHandle handle;
        if (!foliaDetected) {


            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
        } else {
            if (!foliaEnabled || regionRun == null) return TaskHandle.EMPTY;

            Object scheduled = invokeRegionRun(location, new Callback(runnable));
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runRegionLater(Location location, Runnable runnable, long delayTicks) {
        if (runnable == null || location == null || location.getWorld() == null) {
            return TaskHandle.EMPTY;
        }
        long safeDelay = Math.max(1L, delayTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
        } else {
            if (!foliaEnabled || regionRunDelayed == null) return TaskHandle.EMPTY;

            Object scheduled = invokeRegionRunDelayed(location, new Callback(runnable), safeDelay);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runRegionTimer(Location location, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (runnable == null || location == null || location.getWorld() == null) {
            return TaskHandle.EMPTY;
        }
        long initial = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, initial, period));
        } else {
            if (!foliaEnabled || regionRunAtFixedRate == null) return TaskHandle.EMPTY;

            Object scheduled = invokeRegionRunAtFixedRate(location, new Callback(runnable), initial, period);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }

        track(handle);
        return handle;
    }

    public TaskHandle runEntity(Entity entity, Runnable runnable) {
        if (runnable == null || entity == null) return TaskHandle.EMPTY;

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTask(plugin, runnable));
        } else {
            if (!foliaEnabled || entityGetScheduler == null || entityRun == null) return TaskHandle.EMPTY;

            Object scheduler = invokeEntityGetScheduler(entity);
            if (scheduler == null) return TaskHandle.EMPTY;

            Object scheduled = invokeEntityRun(scheduler, new Callback(runnable));
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runEntityLater(Entity entity, Runnable runnable, long delayTicks) {
        if (runnable == null || entity == null) return TaskHandle.EMPTY;
        long safeDelay = Math.max(1L, delayTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskLater(plugin, runnable, safeDelay));
        } else {
            if (!foliaEnabled || entityGetScheduler == null || entityRunDelayed == null) return TaskHandle.EMPTY;

            Object scheduler = invokeEntityGetScheduler(entity);
            if (scheduler == null) return TaskHandle.EMPTY;

            Object scheduled = invokeEntityRunDelayed(scheduler, new Callback(runnable), safeDelay);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runEntityTimer(Entity entity, Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (runnable == null || entity == null) return TaskHandle.EMPTY;
        long initial = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimer(plugin, runnable, initial, period));
        } else {
            if (!foliaEnabled || entityGetScheduler == null || entityRunAtFixedRate == null) return TaskHandle.EMPTY;

            Object scheduler = invokeEntityGetScheduler(entity);
            if (scheduler == null) return TaskHandle.EMPTY;

            Object scheduled = invokeEntityRunAtFixedRate(scheduler, new Callback(runnable), initial, period);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }

        track(handle);
        return handle;
    }

    public TaskHandle runAsync(Runnable runnable) {
        if (runnable == null) return TaskHandle.EMPTY;

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable));
        } else {
            if (!foliaEnabled || asyncRunNow == null) return TaskHandle.EMPTY;

            Object scheduled = invokeAsyncRunNow(new Callback(runnable));
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runAsyncLater(Runnable runnable, long delayTicks) {
        if (runnable == null) return TaskHandle.EMPTY;
        long safeDelay = Math.max(1L, delayTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, safeDelay));
        } else {
            if (!foliaEnabled || asyncRunDelayed == null) return TaskHandle.EMPTY;

            Object scheduled = invokeAsyncRunDelayed(new Callback(runnable), safeDelay * 50L, TimeUnit.MILLISECONDS);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }


        return handle;
    }

    public TaskHandle runAsyncTimer(Runnable runnable, long initialDelayTicks, long periodTicks) {
        if (runnable == null) return TaskHandle.EMPTY;
        long initial = Math.max(1L, initialDelayTicks);
        long period = Math.max(1L, periodTicks);

        final TaskHandle handle;
        if (!foliaDetected) {
            handle = new BukkitTaskHandle(Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, initial, period));
        } else {
            if (!foliaEnabled || asyncRunAtFixedRate == null) return TaskHandle.EMPTY;

            Object scheduled = invokeAsyncRunAtFixedRate(new Callback(runnable), initial * 50L, period * 50L, TimeUnit.MILLISECONDS);
            if (scheduled == null) return TaskHandle.EMPTY;
            handle = new FoliaTaskHandle(scheduled, scheduledCancel, scheduledIsCancelled);
        }

        track(handle);
        return handle;
    }

    private Object invokeGlobalRun(Consumer<Object> callback) {
        try {
            return (Object) globalRun.invokeExact((Plugin) plugin, callback);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeGlobalRunDelayed(Consumer<Object> callback, long delayTicks) {
        try {
            return (Object) globalRunDelayed.invokeExact((Plugin) plugin, callback, delayTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeGlobalRunAtFixedRate(Consumer<Object> callback, long initialDelayTicks, long periodTicks) {
        try {
            return (Object) globalRunAtFixedRate.invokeExact((Plugin) plugin, callback, initialDelayTicks, periodTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeRegionRun(Location location, Consumer<Object> callback) {
        try {
            return (Object) regionRun.invokeExact((Plugin) plugin, location, callback);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeRegionRunDelayed(Location location, Consumer<Object> callback, long delayTicks) {
        try {
            return (Object) regionRunDelayed.invokeExact((Plugin) plugin, location, callback, delayTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeRegionRunAtFixedRate(Location location, Consumer<Object> callback, long initialDelayTicks, long periodTicks) {
        try {
            return (Object) regionRunAtFixedRate.invokeExact((Plugin) plugin, location, callback, initialDelayTicks, periodTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeEntityGetScheduler(Entity entity) {
        try {
            return (Object) entityGetScheduler.invokeExact(entity);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeEntityRun(Object scheduler, Consumer<Object> callback) {
        try {
            return (Object) entityRun.invokeExact(scheduler, (Plugin) plugin, callback, (Runnable) null);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeEntityRunDelayed(Object scheduler, Consumer<Object> callback, long delayTicks) {
        try {
            return (Object) entityRunDelayed.invokeExact(scheduler, (Plugin) plugin, callback, (Runnable) null, delayTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeEntityRunAtFixedRate(Object scheduler, Consumer<Object> callback, long initialDelayTicks, long periodTicks) {
        try {
            return (Object) entityRunAtFixedRate.invokeExact(scheduler, (Plugin) plugin, callback, (Runnable) null, initialDelayTicks, periodTicks);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeAsyncRunNow(Consumer<Object> callback) {
        try {
            return (Object) asyncRunNow.invokeExact((Plugin) plugin, callback);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeAsyncRunDelayed(Consumer<Object> callback, long delay, TimeUnit unit) {
        try {
            return (Object) asyncRunDelayed.invokeExact((Plugin) plugin, callback, delay, unit);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object invokeAsyncRunAtFixedRate(Consumer<Object> callback, long initialDelay, long period, TimeUnit unit) {
        try {
            return (Object) asyncRunAtFixedRate.invokeExact((Plugin) plugin, callback, initialDelay, period, unit);
        } catch (Throwable e) {
            return schedulerFailure(e);
        }
    }

    private Object schedulerFailure(Throwable throwable) {
        logFoliaBindingFailure(throwable.getClass().getSimpleName() + ": " + safeMessage(throwable));
        return null;
    }

    private void track(TaskHandle handle) {
        if (handle == null || handle.isCancelled()) return;


        activeTasks.removeIf(TaskHandle::isCancelled);
        activeTasks.add(handle);
    }


    public void cancelAll() {
        for (TaskHandle handle : activeTasks) {
            try {
                handle.cancel();
            } catch (Throwable ignored) {

            }
        }
        activeTasks.clear();
    }

    public interface TaskHandle {
        TaskHandle EMPTY = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        void cancel();

        boolean isCancelled();
    }

    private record BukkitTaskHandle(BukkitTask task) implements TaskHandle {
        @Override
        public void cancel() {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }

        @Override
        public boolean isCancelled() {
            return task == null || task.isCancelled();
        }
    }


    private static final class Callback implements Consumer<Object> {
        private final Runnable runnable;

        private Callback(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void accept(Object ignored) {
            runnable.run();
        }
    }

    private static final class FoliaTaskHandle implements TaskHandle {
        private final Object scheduledTask;
        private final MethodHandle cancelHandle;
        private final MethodHandle isCancelledHandle;
        private volatile boolean cancelled;

        private FoliaTaskHandle(Object scheduledTask,
                                MethodHandle cancelHandle,
                                MethodHandle isCancelledHandle) {
            this.scheduledTask = scheduledTask;
            this.cancelHandle = cancelHandle;
            this.isCancelledHandle = isCancelledHandle;
        }

        @Override
        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (scheduledTask == null || cancelHandle == null) return;
            try {
                cancelHandle.invoke(scheduledTask);
            } catch (Throwable ignored) {

            }
        }

        @Override
        public boolean isCancelled() {
            if (cancelled || scheduledTask == null) return true;
            if (isCancelledHandle == null) return false;
            try {
                return (boolean) isCancelledHandle.invoke(scheduledTask);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    private record FoliaBindings(
            boolean valid,
            Object globalScheduler,
            Object regionScheduler,
            Object asyncScheduler,
            MethodHandle globalRun,
            MethodHandle globalRunDelayed,
            MethodHandle globalRunAtFixedRate,
            MethodHandle regionRun,
            MethodHandle regionRunDelayed,
            MethodHandle regionRunAtFixedRate,
            MethodHandle asyncRunNow,
            MethodHandle asyncRunDelayed,
            MethodHandle asyncRunAtFixedRate,
            MethodHandle entityGetScheduler,
            MethodHandle entityRun,
            MethodHandle entityRunDelayed,
            MethodHandle entityRunAtFixedRate,
            MethodHandle scheduledCancel,
            MethodHandle scheduledIsCancelled
    ) {
        static FoliaBindings invalid() {
            return new FoliaBindings(
                    false, null, null, null,
                    null, null, null,
                    null, null, null,
                    null, null, null,
                    null, null, null, null,
                    null, null
            );
        }
    }
}
