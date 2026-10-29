package me.nolag.modules.netty;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Counts decoded inbound handshake, status and login packets on the channel event loop.
 * The joined-player guard replaces this guard after it has been installed successfully.
 */
final class NettyPreLoginGuardHandler extends ChannelInboundHandlerAdapter {

    private static final String NAME = "nolag_netty_early_guard";

    private final NettyProtectionModule module;

    private double tokens = -1.0;
    private long lastRefillNanos;

    NettyPreLoginGuardHandler(NettyProtectionModule module) {
        this.module = module;
    }

    static String handlerName() {
        return NAME;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!module.isEnabled() || !module.isEarlyStageProtectionEnabled()) {
            ctx.fireChannelRead(msg);
            return;
        }

        int rate = Math.max(1, module.getEarlyStageMaxPacketsPerSecond());
        long now = System.nanoTime();
        if (tokens < 0) {
            tokens = rate;
        } else {
            tokens = Math.min(rate, tokens + Math.max(0L, now - lastRefillNanos) / 1_000_000_000.0 * rate);
        }
        lastRefillNanos = now;
        if (tokens < 1.0) {
            module.incrementFloodPacketsBlocked();
            module.logWarning("Pre-login packet rate exceeded for " + remoteAddress(ctx));
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }
        tokens -= 1.0;
        ctx.fireChannelRead(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Not this guard's job to decide connection fate on arbitrary pipeline exceptions
        // (same reasoning as NettyPacketGuardHandler's O06 fix) — just pass it along.
        ctx.fireExceptionCaught(cause);
    }

    private String remoteAddress(ChannelHandlerContext ctx) {
        try {
            return String.valueOf(ctx.channel().remoteAddress());
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
