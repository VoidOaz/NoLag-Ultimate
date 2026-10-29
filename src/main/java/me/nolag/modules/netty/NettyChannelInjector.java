package me.nolag.modules.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.Promise;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance, reflection-cached Netty channel injector.
 * Resolves player Netty channels across Paper, Purpur, Spigot, and Folia (1.21.x)
 * without runtime overhead or allocation on the hot path.
 */
public final class NettyChannelInjector {

    public static final String HANDLER_NAME = "nolag_netty_guard";

    private static final long REINIT_COOLDOWN_MILLIS = 30_000L;

    private final Logger logger;
    private MethodHandle getHandleHandle;
    private MethodHandle getConnectionHandle;
    private MethodHandle getNetworkManagerHandle;
    private MethodHandle getChannelHandle;
    private volatile boolean initialized = false;
    private volatile boolean initializationFailed = false;
    private volatile long lastInitAttemptMillis = 0L;

    public NettyChannelInjector(Logger logger) {
        this.logger = logger;
        initReflection();
    }

    /**
     * Y06 fix: a transient failure (e.g. the server booted with a relocated/odd CraftBukkit
     * build still initializing) no longer permanently disables the injector. Retries are
     * cooled down so a steady stream of inject() calls cannot turn into a reflection-scan
     * storm.
     */
    private void ensureInitialized() {
        if (initialized) return;
        long now = System.currentTimeMillis();
        if (now - lastInitAttemptMillis < REINIT_COOLDOWN_MILLIS) return;

        synchronized (this) {
            if (initialized) return;
            if (System.currentTimeMillis() - lastInitAttemptMillis < REINIT_COOLDOWN_MILLIS) return;
            lastInitAttemptMillis = System.currentTimeMillis();
            initializationFailed = false;
            initReflection();
        }
    }

    private synchronized void initReflection() {
        lastInitAttemptMillis = System.currentTimeMillis();
        initialized = false;
        getHandleHandle = null;
        getConnectionHandle = null;
        getNetworkManagerHandle = null;
        getChannelHandle = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // 1. CraftPlayer.getHandle() -> EntityPlayer / ServerPlayer
            Class<?> craftPlayerClass = null;
            try {
                craftPlayerClass = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            } catch (ClassNotFoundException e) {
                // Try versioned NMS package if on legacy relocations
                for (Package pkg : Package.getPackages()) {
                    if (pkg.getName().startsWith("org.bukkit.craftbukkit.")) {
                        try {
                            craftPlayerClass = Class.forName(pkg.getName() + ".entity.CraftPlayer");
                            break;
                        } catch (ClassNotFoundException ignored) {}
                    }
                }
            }

            if (craftPlayerClass == null) {
                throw new IllegalStateException("Could not locate CraftPlayer class");
            }

            Method getHandleMethod = craftPlayerClass.getMethod("getHandle");
            getHandleHandle = lookup.unreflect(getHandleMethod);
            Class<?> serverPlayerClass = getHandleMethod.getReturnType();

            // 2. ServerPlayer.connection -> ServerGamePacketListenerImpl
            // Y05 fix: a by-name match is only accepted once its field TYPE also looks
            // plausible; otherwise we fall through to the broader type-based scan below
            // instead of trusting an obfuscation-coincidental name match blindly.
            Field connectionField = findFieldValidated(serverPlayerClass,
                    f -> f.getType().getName().contains("PacketListener") || f.getType().getName().contains("Connection"),
                    "connection", "b", "c");
            if (connectionField == null) {
                for (Field f : serverPlayerClass.getFields()) {
                    if (f.getType().getName().contains("PacketListener") || f.getType().getName().contains("Connection")) {
                        connectionField = f;
                        break;
                    }
                }
            }
            if (connectionField == null) {
                for (Field f : serverPlayerClass.getDeclaredFields()) {
                    if (f.getType().getName().contains("PacketListener") || f.getType().getName().contains("Connection")) {
                        connectionField = f;
                        break;
                    }
                }
            }

            if (connectionField == null) {
                throw new IllegalStateException("Could not locate player connection field");
            }
            connectionField.setAccessible(true);
            getConnectionHandle = lookup.unreflectGetter(connectionField);
            Class<?> packetListenerClass = connectionField.getType();

            // 3. ServerGamePacketListenerImpl.connection / networkManager -> Connection
            Field networkManagerField = findFieldValidated(packetListenerClass,
                    f -> f.getType().getName().contains("NetworkManager") || f.getType().getName().contains("Connection"),
                    "connection", "networkManager", "a", "c", "e", "h");
            if (networkManagerField == null) {
                for (Field f : packetListenerClass.getDeclaredFields()) {
                    if (f.getType().getName().contains("NetworkManager") || f.getType().getName().contains("Connection")) {
                        networkManagerField = f;
                        break;
                    }
                }
            }

            Class<?> connectionClass;
            if (networkManagerField != null) {
                networkManagerField.setAccessible(true);
                getNetworkManagerHandle = lookup.unreflectGetter(networkManagerField);
                connectionClass = networkManagerField.getType();
            } else {
                // In some versions, packetListener itself holds channel or implements Connection
                connectionClass = packetListenerClass;
            }

            // 4. Connection.channel -> io.netty.channel.Channel
            Field channelField = findFieldValidated(connectionClass,
                    f -> Channel.class.isAssignableFrom(f.getType()),
                    "channel", "m", "k", "n");
            if (channelField == null) {
                for (Field f : connectionClass.getDeclaredFields()) {
                    if (Channel.class.isAssignableFrom(f.getType())) {
                        channelField = f;
                        break;
                    }
                }
            }

            if (channelField == null) {
                throw new IllegalStateException("Could not locate Netty channel field in " + connectionClass.getName());
            }
            channelField.setAccessible(true);
            getChannelHandle = lookup.unreflectGetter(channelField);

            initialized = true;
            logger.fine("[NoLag-Netty] Channel injector reflection initialized successfully.");
        } catch (Throwable t) {
            initializationFailed = true;
            logger.log(Level.WARNING, "[NoLag-Netty] Could not bind Netty channel reflection: " + t.getMessage(), t);
        }
    }

    private Field findFieldValidated(Class<?> clazz, Predicate<Field> typeValidator, String... names) {
        Field byName = findField(clazz, names);
        if (byName != null && typeValidator.test(byName)) {
            return byName;
        }
        return null;
    }

    private Field findField(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
            try {
                Field f = clazz.getField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    /**
     * Extracts the Netty Channel for an online player.
     */
    public Channel getChannel(Player player) {
        ensureInitialized();
        if (!initialized || player == null) {
            return null;
        }

        try {
            Object serverPlayer = getHandleHandle.invoke(player);
            if (serverPlayer == null) return null;

            Object connectionObj = getConnectionHandle.invoke(serverPlayer);
            if (connectionObj == null) return null;

            Object targetForChannel;
            if (getNetworkManagerHandle != null) {
                targetForChannel = getNetworkManagerHandle.invoke(connectionObj);
            } else {
                targetForChannel = connectionObj;
            }

            if (targetForChannel == null) return null;

            return (Channel) getChannelHandle.invoke(targetForChannel);
        } catch (Throwable t) {
            logger.log(Level.FINE, "[NoLag-Netty] Failed to get channel for " + player.getName() + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * Injects the NettyPacketGuardHandler into the player's Netty pipeline.
     *
     * <p>K04 fix: this used to return {@code true} the instant the task was handed off to
     * the channel's EventLoop, regardless of whether the pipeline mutation actually
     * succeeded. It now returns a {@link Future} that only resolves once the mutation has
     * really run, carrying whether the handler ended up installed.</p>
     */
    public Future<Boolean> inject(Channel channel, ChannelHandler handler) {
        if (channel == null || !channel.isOpen()) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(Boolean.FALSE);
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline == null) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(Boolean.FALSE);
        }

        EventExecutor executor = channel.eventLoop();
        Promise<Boolean> promise = executor.newPromise();

        Runnable task = () -> {
            boolean success;
            try {
                success = performInjection(pipeline, handler);
            } catch (Throwable t) {
                logger.log(Level.FINE, "[NoLag-Netty] Failed to inject pipeline: " + t.getMessage());
                success = false;
            }
            promise.setSuccess(success);
        };

        if (executor.inEventLoop()) {
            task.run();
        } else {
            try {
                executor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                promise.setSuccess(false);
            }
        }
        return promise;
    }

    /**
     * Installs (or refreshes) the guard handler. Idempotent (K05 fix): if this exact
     * handler instance is already the one installed, this is a no-op that preserves its
     * accumulated rate-limit state; a different (stale) instance under the same name is
     * swapped out cleanly.
     *
     * <p>Because every pipeline mutation for a given channel is funneled through that
     * channel's single-threaded EventLoop (both here and in {@link #uninjectChannel}),
     * Netty itself serializes any concurrent inject()/uninject() calls targeting the same
     * channel — they can never execute concurrently with each other — so no additional
     * locking is required for correctness.</p>
     */
    private boolean performInjection(ChannelPipeline pipeline, ChannelHandler handler) {
        ChannelHandler existing = pipeline.get(HANDLER_NAME);
        if (existing == handler) {
            return true;
        }
        if (existing != null) {
            pipeline.remove(HANDLER_NAME);
        }

        if (pipeline.get("packet_handler") != null) {
            pipeline.addBefore("packet_handler", HANDLER_NAME, handler);
        } else if (pipeline.get("decoder") != null) {
            pipeline.addAfter("decoder", HANDLER_NAME, handler);
        } else {
            pipeline.addFirst(HANDLER_NAME, handler);
        }
        return pipeline.get(HANDLER_NAME) == handler;
    }

    /**
     * Removes the NettyPacketGuardHandler from the player's Netty pipeline.
     */
    public Future<?> uninject(Player player) {
        Channel channel = getChannel(player);
        if (channel == null) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }
        return uninjectChannel(channel);
    }

    /**
     * Removes the handler directly from a channel.
     *
     * <p>Y08 fix: returns a {@link Future} so callers (notably {@code
     * NettyProtectionModule#stop()}) can wait, with a bound, for the removal to actually
     * complete instead of firing-and-forgetting it.</p>
     */
    public Future<?> uninjectChannel(Channel channel) {
        return uninjectChannel(channel, null);
    }

    /** Removes only the specified handler when an older injection completes late. */
    Future<?> uninjectChannel(Channel channel, ChannelHandler expected) {
        if (channel == null) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline == null) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }

        EventExecutor executor = channel.eventLoop();
        if (executor.inEventLoop()) {
            removeHandler(pipeline, expected);
            return executor.newSucceededFuture(null);
        }
        try {
            return executor.submit(() -> removeHandler(pipeline, expected));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
        }
    }

    private void removeHandler(ChannelPipeline pipeline, ChannelHandler expected) {
        try {
            ChannelHandler installed = pipeline.get(HANDLER_NAME);
            if (installed != null && (expected == null || installed == expected)) {
                pipeline.remove(HANDLER_NAME);
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isInitialized() {
        return initialized;
    }
}
