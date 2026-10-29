package me.nolag.modules.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.Future;
import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Adds a child initializer ahead of Netty's acceptor without changing its private fields. */
final class NettyEarlyStageInjector {
    private static final String ACCEPT_GUARD = "nolag_accept_guard";
    private static final int MAX_ATTEMPTS = 30;

    private final NoLag plugin;
    private final NettyProtectionModule module;
    private final Logger logger;
    private final Set<Channel> listeners = ConcurrentHashMap.newKeySet();
    private final Set<Channel> children = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    private volatile SchedulerAdapter.TaskHandle retryTask;

    NettyEarlyStageInjector(NoLag plugin, NettyProtectionModule module) {
        this.plugin = plugin;
        this.module = module;
        this.logger = plugin.getLogger();
    }

    void scheduleHook() {
        if (stopped || !listeners.isEmpty() || (retryTask != null && !retryTask.isCancelled())) return;
        SchedulerAdapter scheduler = plugin.getSchedulerAdapter();
        if (scheduler == null) {
            attemptHook();
            return;
        }
        final int[] attempts = {0};
        retryTask = scheduler.runGlobalTimer(() -> {
            if (stopped) return;
            attempts[0]++;
            attemptHook();
            if (attempts[0] >= MAX_ATTEMPTS) {
                SchedulerAdapter.TaskHandle task = retryTask;
                if (task != null) task.cancel();
                retryTask = null;
                if (listeners.isEmpty() && module.isEnabled()
                        && (module.isEarlyStageProtectionEnabled() || module.isProtectConcurrentConnectionFlood())) {
                    logger.warning("[NoLag-Netty] Early connection guard unavailable on this server build; "
                            + "the early packet-rate limiter and per-IP concurrent-connection cap are "
                            + "inactive. The async pre-login throttle and joined-player guard remain active.");
                }
            }
        }, 20L, 20L);
    }

    private void attemptHook() {
        try {
            for (ChannelFuture future : findListenerChannels()) {
                Channel listener = future.channel();
                if (listener == null || !listener.isOpen()) continue;
                // Pipeline mutations always occur on the listener's event loop.
                listener.eventLoop().execute(() -> {
                    if (stopped || !listener.isOpen()) return;
                    ChannelPipeline pipeline = listener.pipeline();
                    if (pipeline.get(ACCEPT_GUARD) != null) {
                        listeners.add(listener);
                        return;
                    }
                    String acceptor = null;
                    for (String name : pipeline.names()) {
                        ChannelHandler handler = pipeline.get(name);
                        if (handler != null && handler.getClass().getSimpleName().equals("ServerBootstrapAcceptor")) {
                            acceptor = name;
                            break;
                        }
                    }
                    if (acceptor == null) return;
                    try {
                        pipeline.addBefore(acceptor, ACCEPT_GUARD, new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                // Note: gated on module.isEnabled() only, NOT on
                                // isEarlyStageProtectionEnabled() — the concurrent-connection
                                // cap (P01) below must keep working even when the separate
                                // early packet-rate guard is turned off. The packet-rate guard
                                // itself is still independently gated inside initChannel().
                                if (msg instanceof Channel child && !stopped && module.isEnabled()) {
                                    try {
                                        children.add(child);
                                        child.closeFuture().addListener(f -> children.remove(child));
                                        // The server's original child initializer runs first on registration.
                                        // This initializer then enforces the per-IP concurrent-connection cap
                                        // (P01) and, if the connection survives that, inserts our packet-rate
                                        // guard after the protocol decoder.
                                        child.pipeline().addLast(new ChannelInitializer<Channel>() {
                                            @Override
                                            protected void initChannel(Channel ch) {
                                                if (stopped || !module.isEnabled()) return;
                                                if (!module.tryAcquireConnectionSlot(ch)) {
                                                    ch.close();
                                                    return;
                                                }
                                                if (!module.isEarlyStageProtectionEnabled()) return;
                                                ChannelPipeline cp = ch.pipeline();
                                                String name = NettyPreLoginGuardHandler.handlerName();
                                                if (cp.get(name) != null) return;
                                                if (cp.get("packet_handler") != null) {
                                                    cp.addBefore("packet_handler", name, new NettyPreLoginGuardHandler(module));
                                                } else if (cp.get("decoder") != null) {
                                                    cp.addAfter("decoder", name, new NettyPreLoginGuardHandler(module));
                                                } else {
                                                    logger.fine("[NoLag-Netty] Early decoder unavailable for a child channel.");
                                                }
                                            }
                                        });
                                    } catch (Throwable t) {
                                        children.remove(child);
                                        logger.log(Level.FINE, "[NoLag-Netty] Child guard setup failed", t);
                                    }
                                }
                                ctx.fireChannelRead(msg);
                            }
                        });
                        listeners.add(listener);
                    } catch (Throwable t) {
                        logger.log(Level.FINE, "[NoLag-Netty] Could not attach listener guard", t);
                    }
                });
            }
        } catch (Throwable t) {
            logger.log(Level.FINE, "[NoLag-Netty] Listener discovery failed", t);
        }
    }

    private List<ChannelFuture> findListenerChannels() throws ReflectiveOperationException {
        Object server = plugin.getServer();
        Method getServer = server.getClass().getMethod("getServer");
        Object minecraftServer = getServer.invoke(server);
        Method getConnection = minecraftServer.getClass().getMethod("getConnection");
        Object connection = getConnection.invoke(minecraftServer);
        List<ChannelFuture> result = new ArrayList<>();
        for (Class<?> type = connection.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(connection);
                    if (!(value instanceof List<?> list)) continue;
                    // Do not confuse an unrelated list of channels or connections for listeners.
                    for (Object candidate : list.toArray()) {
                        if (candidate instanceof ChannelFuture future && future.channel().parent() == null) {
                            result.add(future);
                        }
                    }
                } catch (RuntimeException inaccessible) {
                    logger.log(Level.FINEST, "[NoLag-Netty] Skipping inaccessible listener field", inaccessible);
                }
            }
        }
        return result;
    }

    boolean hasChildGuard(Channel child) {
        return child != null && child.pipeline().get(NettyPreLoginGuardHandler.handlerName()) != null;
    }

    void removeChildGuard(Channel child) {
        if (child == null) return;
        try {
            child.eventLoop().execute(() -> {
                ChannelPipeline pipeline = child.pipeline();
                if (pipeline.get(NettyPreLoginGuardHandler.handlerName()) != null) {
                    pipeline.remove(NettyPreLoginGuardHandler.handlerName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The connection is closing and its event loop has already stopped.
        }
    }

    void shutdown() {
        stopped = true;
        SchedulerAdapter.TaskHandle task = retryTask;
        if (task != null) task.cancel();
        List<Future<?>> pending = new ArrayList<>();
        for (Channel listener : listeners) {
            try {
                pending.add(listener.eventLoop().submit(() -> {
                    if (listener.pipeline().get(ACCEPT_GUARD) != null) listener.pipeline().remove(ACCEPT_GUARD);
                }));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Listener's event loop is already gone.
            }
        }
        for (Channel child : children) {
            try {
                pending.add(child.eventLoop().submit(() -> {
                    if (child.pipeline().get(NettyPreLoginGuardHandler.handlerName()) != null) {
                        child.pipeline().remove(NettyPreLoginGuardHandler.handlerName());
                    }
                }));
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // Child's event loop is already gone.
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Future<?> future : pending) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                future.await(remaining, TimeUnit.NANOSECONDS);
            }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        listeners.clear();
        children.clear();
    }
}
