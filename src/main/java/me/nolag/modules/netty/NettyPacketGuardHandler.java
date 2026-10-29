package me.nolag.modules.netty;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-performance, non-blocking Netty packet filter.
 * Runs directly on Netty worker threads (off the main tick thread),
 * inspecting, rate-limiting, and neutralizing malicious packets and floods
 * with minimal allocations.
 *
 * <p>Every mutable field below is only ever touched from this channel's own
 * single-threaded Netty EventLoop, so no synchronization is required or used
 * anywhere in this class.</p>
 */
public final class NettyPacketGuardHandler extends ChannelDuplexHandler {

    private static final int MAX_CRASH_STRING_LENGTH = 32_767;
    private static final int MAX_CRASH_BYTE_ARRAY_LENGTH = 2 * 1024 * 1024;

    private static final Set<Class<?>> NUMERIC_RETURN_TYPES = Set.of(
            double.class, float.class, int.class, long.class,
            Double.class, Float.class, Integer.class, Long.class
    );

    private static final Field[] NO_FIELDS = new Field[0];

    // Packet-class metadata is resolved once per distinct class (not per packet, not per
    // player) and shared process-wide — fixes Y02 (reflection repeated every movement
    // packet) and reduces the per-packet cost of the type classification used for O03/O04.
    private static final Map<Class<?>, PacketProfile> PROFILE_CACHE = new ConcurrentHashMap<>();

    private final NettyProtectionModule module;
    private final UUID playerUuid;
    private final String playerName;

    // Per-second window tracking (extreme-flood detection only — see K01 fix below).
    private long currentSecond = 0L;
    private int packetCount = 0;
    private int tabCompleteCount = 0;
    private int customPayloadCount = 0;
    private int windowClickCount = 0;
    private boolean extremeExceededThisSecond = false;
    private int consecutiveFloodSeconds = 0;

    // Token bucket for normal packet-rate limiting (O01/O02 fix: a real, continuously
    // refilling bucket instead of a fixed one-second window plus a coin-flip drop).
    private double packetTokens = -1.0; // -1 sentinel: lazily initialized to the burst cap
    private long lastRefillNanos = 0L;

    public NettyPacketGuardHandler(NettyProtectionModule module, UUID playerUuid, String playerName) {
        this.module = module;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!module.isEnabled()) {
            super.channelRead(ctx, msg);
            return;
        }

        long nowSecond = System.nanoTime() / 1_000_000_000L;
        if (nowSecond != currentSecond) {
            // Flush this second's inspected-packet count in one batched call instead of
            // incrementing a LongAdder once per packet (O04 fix).
            if (packetCount > 0) {
                module.addPacketsInspected(packetCount);
            }
            // K01 fix: only now — once a full one-second window has actually elapsed — do
            // we decide whether THAT window counted as "flooded", and advance the
            // consecutive-seconds counter. Previously this counter was incremented once per
            // over-threshold packet within the *same* second, so two extra packets arriving
            // in one second could trigger a disconnect meant to require two full seconds of
            // sustained flooding.
            // H-03 fix: Only advance consecutive flood count if exactly 1 second elapsed.
            // If multiple idle/low-traffic seconds passed, the flood was not sustained.
            if (currentSecond != 0L) {
                if (nowSecond - currentSecond == 1L) {
                    consecutiveFloodSeconds = extremeExceededThisSecond ? consecutiveFloodSeconds + 1 : 0;
                } else {
                    consecutiveFloodSeconds = 0;
                }
            }
            currentSecond = nowSecond;
            packetCount = 0;
            tabCompleteCount = 0;
            customPayloadCount = 0;
            windowClickCount = 0;
            extremeExceededThisSecond = false;
        }

        if (packetCount < Integer.MAX_VALUE) packetCount++;

        // 1. Extreme Abusive Packet Flood Check (e.g., 1200+ packets/sec), evaluated across
        //    consecutive one-second windows (K01).
        int extremeThreshold = module.getExtremeFloodThreshold();
        if (packetCount > extremeThreshold) {
            extremeExceededThisSecond = true;
        }
        if (module.isDisconnectOnExtremeFlood() && consecutiveFloodSeconds >= 2) {
            module.incrementFloodPacketsBlocked();
            module.logWarning("Terminating connection of " + playerName + " due to extreme packet flood sustained over "
                    + consecutiveFloodSeconds + " consecutive seconds (" + packetCount + " packets in current second)");
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        PacketProfile profile = profileFor(msg);

        // Apply the same budget to every decoded packet. Unknown packets and stateful
        // protocol messages cannot safely be discarded in the middle of a session.
        if (!consumePacketToken()) {
            module.incrementFloodPacketsBlocked();
            module.logWarning("Packet rate exceeded for " + playerName);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        // 3. Packet Type / Exploit Inspection
        if (module.isProtectTabCompleteFlood() && profile.tabComplete()) {
            tabCompleteCount++;
            if (tabCompleteCount > module.getMaxTabCompletePerSecond()) {
                module.incrementFloodPacketsBlocked();
                ReferenceCountUtil.release(msg);
                // No protocol-safe suggestion response is available at this layer.
                // Close the abusive channel explicitly instead of leaving a request hanging.
                ctx.close();
                return;
            }
        } else if (module.isProtectCustomPayloadFlood() && profile.customPayload()) {
            customPayloadCount++;
            if (customPayloadCount > module.getMaxCustomPayloadPerSecond()) {
                module.incrementFloodPacketsBlocked();
                ReferenceCountUtil.release(msg);
                ctx.close();
                return;
            }
        } else if (module.isProtectWindowClickFlood() && profile.windowClick()) {
            windowClickCount++;
            if (windowClickCount > module.getMaxWindowClickPerSecond()) {
                module.incrementFloodPacketsBlocked();
                ReferenceCountUtil.release(msg);
                // Inventory clicks have ordering and state IDs. Dropping just one can
                // permanently desynchronize the client; terminate the abusive session.
                ctx.close();
                return;
            }
        }

        // 4. Crash / malformed-payload inspection (Y01 fix: filter-crash-packets is now
        //    actually backed by a check instead of being an inert configuration option).
        //    This is a best-effort heuristic (oversized strings/byte arrays are a common
        //    crash-exploit shape); it cannot be exhaustive without per-version packet
        //    schemas, which this plugin does not have access to.
        if (module.isFilterCrashPackets() && profile.crashProne() && isLikelyCrashPacket(msg, profile)) {
            module.incrementExploitsBlocked();
            module.logWarning("Blocked potential crash-exploit packet (" + msg.getClass().getSimpleName() + ") from " + playerName);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        // 5. Malformed / invalid movement coordinate inspection
        if (module.isFilterInvalidMovement() && profile.movement() && hasInvalidCoordinates(msg, profile)) {
            module.incrementExploitsBlocked();
            module.logWarning("Blocked invalid coordinate exploit packet from " + playerName);
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }

        super.channelRead(ctx, msg);
    }

    /**
     * Consumes one token from the per-connection rate-limiting bucket, refilling it
     * continuously based on elapsed wall-clock time (a true token bucket — O01/O02 fix).
     */
    private boolean consumePacketToken() {
        int rate = Math.max(1, module.getMaxPacketRatePerSecond());
        int burst = Math.max(rate, module.getPacketRateBurst());
        long now = System.nanoTime();

        if (packetTokens < 0.0) {
            packetTokens = burst;
            lastRefillNanos = now;
        } else {
            long deltaNanos = Math.max(0L, now - lastRefillNanos);
            lastRefillNanos = now;
            double refill = (deltaNanos / 1_000_000_000.0) * rate;
            packetTokens = Math.min(burst, packetTokens + refill);
        }

        if (packetTokens >= 1.0) {
            packetTokens -= 1.0;
            return true;
        }
        return false;
    }

    private PacketProfile profileFor(Object packet) {
        return PROFILE_CACHE.computeIfAbsent(packet.getClass(), clazz -> buildProfile(clazz, module.getLogger()));
    }

    private static PacketProfile buildProfile(Class<?> clazz, Logger logger) {
        String simpleName = clazz.getSimpleName();

        boolean tabComplete = simpleName.contains("CommandSuggestion") || simpleName.contains("TabComplete");
        boolean customPayload = simpleName.contains("CustomPayload") || simpleName.contains("PluginMessage");
        boolean windowClick = simpleName.contains("ContainerClick") || simpleName.contains("WindowClick");
        boolean movement = simpleName.contains("MovePlayer") || simpleName.contains("PacketPlayInFlying")
                || simpleName.contains("PlayerPosition") || simpleName.contains("PlayerLook");
        boolean crashProne = simpleName.contains("Book") || simpleName.contains("Sign")
                || simpleName.contains("CustomPayload") || simpleName.contains("PluginMessage")
                || simpleName.contains("ResourcePack") || simpleName.contains("ClientInformation")
                || simpleName.contains("Settings") || simpleName.contains("Chat");

        CoordinateAccessor coordinateAccessor = CoordinateAccessor.UNAVAILABLE;
        if (movement) {
            coordinateAccessor = resolveCoordinateAccessor(clazz);
            if (!coordinateAccessor.isAvailable()) {
                // Y03 fix: previously a failed lookup failed silently on every single packet.
                // Logged exactly once per distinct packet class (this method only runs once
                // per class, thanks to PROFILE_CACHE), so admins can notice that invalid
                // movement filtering is not actually effective for this packet type/version.
                logger.log(Level.WARNING, "[NoLag-Netty] Could not resolve coordinate accessors for movement "
                        + "packet " + clazz.getName() + "; invalid-movement filtering will not apply to this packet type.");
            }
        }

        Field[] crashCheckFields = crashProne ? resolveCrashCheckFields(clazz) : NO_FIELDS;

        return new PacketProfile(tabComplete, customPayload, windowClick, movement, crashProne,
                coordinateAccessor, crashCheckFields);
    }

    private static CoordinateAccessor resolveCoordinateAccessor(Class<?> clazz) {
        Method x = findNumericMethod(clazz, "getX");
        Method y = findNumericMethod(clazz, "getY");
        Method z = findNumericMethod(clazz, "getZ");

        // Require a consistent numeric type; do not guess short obfuscated method names.
        if (x != null && y != null && z != null && x.getReturnType() == y.getReturnType() && y.getReturnType() == z.getReturnType()) {
            return new CoordinateAccessor(x, y, z, null, null, null);
        }
        // Mojang's MovePlayer packets often expose getX(defaultX), while the actual
        // coordinates live in inherited double fields x/y/z. Never guess obfuscated methods.
        Field fx = findCoordinateField(clazz, "x");
        Field fy = findCoordinateField(clazz, "y");
        Field fz = findCoordinateField(clazz, "z");
        if (fx != null && fy != null && fz != null) {
            return new CoordinateAccessor(null, null, null, fx, fy, fz);
        }
        return CoordinateAccessor.UNAVAILABLE;
    }

    private static Field findCoordinateField(Class<?> clazz, String name) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                if (field.getType() != double.class) return null;
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException denied) {
                return null;
            }
        }
        return null;
    }

    private static Method findNumericMethod(Class<?> clazz, String... names) {
        for (String name : names) {
            try {
                Method m = clazz.getMethod(name);
                if (m.getParameterCount() == 0 && NUMERIC_RETURN_TYPES.contains(m.getReturnType())) {
                    m.setAccessible(true);
                    return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // B-03 fix: Extended crash inspection supporting CharSequence, byte[], and Netty ByteBuf
    private static Field[] resolveCrashCheckFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = clazz; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                Class<?> type = field.getType();
                if (!CharSequence.class.isAssignableFrom(type)
                        && type != byte[].class
                        && !io.netty.buffer.ByteBuf.class.isAssignableFrom(type)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    result.add(field);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return result.isEmpty() ? NO_FIELDS : result.toArray(new Field[0]);
    }

    private boolean isLikelyCrashPacket(Object packet, PacketProfile profile) {
        Field[] fields = profile.crashCheckFields();
        if (fields.length == 0) {
            return false;
        }
        try {
            for (Field field : fields) {
                Object value = field.get(packet);
                if (value instanceof CharSequence cs && cs.length() > MAX_CRASH_STRING_LENGTH) {
                    return true;
                }
                if (value instanceof byte[] arr && arr.length > MAX_CRASH_BYTE_ARRAY_LENGTH) {
                    return true;
                }
                if (value instanceof io.netty.buffer.ByteBuf buf && buf.readableBytes() > MAX_CRASH_BYTE_ARRAY_LENGTH) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // An inaccessible field must never crash the pipeline.
        }
        return false;
    }

    private boolean hasInvalidCoordinates(Object packet, PacketProfile profile) {
        CoordinateAccessor accessor = profile.coordinateAccessor();
        if (!accessor.isAvailable()) {
            return false;
        }
        try {
            Object xObj = accessor.xField() == null ? accessor.getX().invoke(packet) : accessor.xField().get(packet);
            Object yObj = accessor.yField() == null ? accessor.getY().invoke(packet) : accessor.yField().get(packet);
            Object zObj = accessor.zField() == null ? accessor.getZ().invoke(packet) : accessor.zField().get(packet);

            if (xObj instanceof Number xNum && yObj instanceof Number yNum && zObj instanceof Number zNum) {
                double x = xNum.doubleValue();
                double y = yNum.doubleValue();
                double z = zNum.doubleValue();

                if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)
                        || Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
                    return true;
                }

                if (Math.abs(x) > 3.0E7 || Math.abs(y) > 3.0E7 || Math.abs(z) > 3.0E7) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        flushInspected();
        // O07 fix: only clear the module's channel registration if it still points at THIS
        // channel. A delayed channelInactive() for a stale/old connection (e.g. a very fast
        // reconnect under the same UUID) must not wipe out a newer connection's registration.
        module.unregisterChannel(playerUuid, ctx.channel(), this);
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        flushInspected();
        module.unregisterChannel(playerUuid, ctx.channel(), this);
        super.handlerRemoved(ctx);
    }

    private void flushInspected() {
        if (packetCount > 0) {
            module.addPacketsInspected(packetCount);
            packetCount = 0;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (isBenignNetworkException(cause)) {
            // Expected connection resets / forcibly closed pipes: close quietly without
            // spamming the console.
            ctx.close();
            return;
        }
        // O06 fix: this guard previously closed the connection for *any* exception anywhere
        // in the pipeline, including failures unrelated to packet-flood/security concerns
        // (e.g. a bug in a completely different handler). Log it and defer to the rest of
        // the pipeline / Minecraft's own handling instead of unilaterally disconnecting the
        // player on this guard's behalf.
        module.getLogger().log(Level.FINE, "[NoLag-Netty] Non-network channel exception for " + playerName + ": " + cause, cause);
        super.exceptionCaught(ctx, cause);
    }

    private boolean isBenignNetworkException(Throwable cause) {
        if (cause instanceof java.io.IOException) {
            return true;
        }
        String msg = cause.getMessage();
        return msg != null && (msg.contains("Connection reset")
                || msg.contains("An existing connection was forcibly closed")
                || msg.contains("Broken pipe"));
    }

    private record CoordinateAccessor(Method getX, Method getY, Method getZ,
                                      Field xField, Field yField, Field zField) {
        static final CoordinateAccessor UNAVAILABLE = new CoordinateAccessor(null, null, null, null, null, null);

        boolean isAvailable() {
            return getX != null || xField != null;
        }
    }

    private record PacketProfile(
            boolean tabComplete,
            boolean customPayload,
            boolean windowClick,
            boolean movement,
            boolean crashProne,
            CoordinateAccessor coordinateAccessor,
            Field[] crashCheckFields
    ) {
    }
}
