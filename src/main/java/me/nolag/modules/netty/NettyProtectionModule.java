package me.nolag.modules.netty;

import io.netty.channel.Channel;
import io.netty.util.concurrent.Future;
import me.nolag.NoLag;
import me.nolag.platform.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

/**
 * Enterprise-grade Netty Network & Packet Protection Module for NoLag-Ultimate.
 * Protects against network floods, packet crash exploits, bot storms, and malformed payloads
 * with asynchronous Netty pipeline processing.
 */
public final class NettyProtectionModule implements Listener {

    private final NoLag plugin;
    private final NettyChannelInjector injector;
    private final NettyEarlyStageInjector earlyStageInjector;

    // Configurable Settings
    private volatile boolean enabled = true;
    private volatile int maxPacketRatePerSecond = 500;
    private volatile int packetRateBurst = 700;
    private volatile boolean disconnectOnExtremeFlood = true;
    private volatile int extremeFloodThreshold = 1200;

    private volatile boolean protectTabCompleteFlood = true;
    private volatile int maxTabCompletePerSecond = 20;

    private volatile boolean protectCustomPayloadFlood = true;
    private volatile int maxCustomPayloadPerSecond = 30;

    private volatile boolean protectWindowClickFlood = true;
    private volatile int maxWindowClickPerSecond = 60;

    private volatile boolean filterInvalidMovement = true;
    private volatile boolean filterCrashPackets = true;

    private volatile int connectionThrottlePerIpPerSecond = 25;
    private volatile int connectionBurstLimit = 40;
    // Y12 fix: loopback (this-machine-only) and site-local (private LAN) exemptions are now
    // separate switches. Loopback is safe to exempt by default; a private-IP range can be
    // shared by many real clients behind NAT/a proxy, so it is opt-in.
    private volatile boolean whitelistLoopback = true;
    private volatile boolean whitelistSiteLocal = false;
    private volatile boolean logBlockedPackets = false;

    // Y11 fix: hard cap on how many distinct IPs are tracked at once, so a wide-IP attack
    // cannot grow this table without bound between cleanup sweeps.
    private volatile int maxTrackedIps = 50_000;

    // K03: best-effort protection during the handshake/login phase, before a Player object
    // (and therefore this module's normal per-player handler) exists at all.
    private volatile boolean earlyStageProtectionEnabled = true;
    private volatile int earlyStageMaxPacketsPerSecond = 40;

    // P01: raw TCP-connection flood guard. AsyncPlayerPreLoginEvent (and therefore
    // connectionThrottlePerIpPerSecond above) only fires for connections that actually
    // attempt to log in — a status/ping connection, or a connection that is opened and then
    // simply left idle, never reaches it. Without a cap on how many sockets from a single IP
    // may be open at the same time, an attacker can still exhaust file descriptors/threads
    // purely at the TCP-accept level. This is enforced the moment a raw child channel is
    // accepted (see NettyEarlyStageInjector), independently of earlyStageProtectionEnabled.
    private volatile boolean protectConcurrentConnectionFlood = true;
    private volatile int maxConcurrentConnectionsPerIp = 10;

    // Active Channels
    private final Map<UUID, GuardRegistration> activeChannels = new ConcurrentHashMap<>();
    private final Map<UUID, PendingInjection> pendingInjections = new ConcurrentHashMap<>();
    private final AtomicLong injectionGeneration = new AtomicLong();
    private final Set<Channel> quittingChannels = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelistedIps = ConcurrentHashMap.newKeySet();

    // Per-IP Connection Rate Limiter (Token bucket)
    private final Map<String, IpConnectionBucket> ipBuckets = new ConcurrentHashMap<>();
    // P01: live count of currently-open raw connections per IP, used only to enforce
    // maxConcurrentConnectionsPerIp. Self-pruning: an entry is removed the moment its count
    // drops back to zero (on channel close), so this never needs a cleanup sweep or a
    // maxTrackedIps-style hard cap — its size is bounded by the number of sockets actually
    // open right now, which the connection cap itself keeps in check.
    private final Map<String, AtomicInteger> concurrentConnectionsPerIp = new ConcurrentHashMap<>();
    private final AtomicInteger unprotectedChannelsCounter = new AtomicInteger();
    private final Set<Channel> degradedChannels = ConcurrentHashMap.newKeySet();
    // Y10 fix: was a plain volatile long read-then-written non-atomically, letting several
    // concurrent async login threads all pass the "is a cleanup due" check at once and run
    // redundant sweeps. An AtomicLong + compareAndSet makes only one winner perform it.
    private final AtomicLong lastBucketCleanupTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastWarningNanos = new AtomicLong();

    // High-performance Statistics (LongAdder: lock-free, zero contention)
    private final LongAdder totalPacketsInspected = new LongAdder();
    private final LongAdder totalFloodPacketsBlocked = new LongAdder();
    private final LongAdder totalExploitsBlocked = new LongAdder();
    private final LongAdder totalBotBurstsBlocked = new LongAdder();
    private final LongAdder totalConnectionFloodsBlocked = new LongAdder();

    public NettyProtectionModule(NoLag plugin) {
        this.plugin = plugin;
        this.injector = new NettyChannelInjector(plugin.getLogger());
        this.earlyStageInjector = new NettyEarlyStageInjector(plugin, this);
        reload();
        earlyStageInjector.scheduleHook();
    }

    public void reload() {
        injectionGeneration.incrementAndGet();
        pendingInjections.clear();
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("features.netty-protection.enabled", true);
        this.maxPacketRatePerSecond = Math.max(50, cfg.getInt("features.netty-protection.max-packet-rate-per-second", 500));
        this.packetRateBurst = Math.max(maxPacketRatePerSecond, cfg.getInt("features.netty-protection.packet-rate-burst", 700));
        this.disconnectOnExtremeFlood = cfg.getBoolean("features.netty-protection.disconnect-on-extreme-flood", true);
        int minimumExtreme = (int) Math.min(Integer.MAX_VALUE, (long) packetRateBurst + 100L);
        this.extremeFloodThreshold = Math.max(minimumExtreme, cfg.getInt("features.netty-protection.extreme-flood-threshold", 1200));

        this.protectTabCompleteFlood = cfg.getBoolean("features.netty-protection.protect-tab-complete-flood", true);
        this.maxTabCompletePerSecond = Math.max(5, cfg.getInt("features.netty-protection.max-tab-complete-per-second", 20));

        this.protectCustomPayloadFlood = cfg.getBoolean("features.netty-protection.protect-custom-payload-flood", true);
        this.maxCustomPayloadPerSecond = Math.max(5, cfg.getInt("features.netty-protection.max-custom-payload-per-second", 30));

        this.protectWindowClickFlood = cfg.getBoolean("features.netty-protection.protect-window-click-flood", true);
        this.maxWindowClickPerSecond = Math.max(10, cfg.getInt("features.netty-protection.max-window-click-per-second", 60));

        this.filterInvalidMovement = cfg.getBoolean("features.netty-protection.filter-invalid-movement", true);
        this.filterCrashPackets = cfg.getBoolean("features.netty-protection.filter-crash-packets", true);

        this.connectionThrottlePerIpPerSecond = Math.max(5, cfg.getInt("features.netty-protection.connection-throttle-per-ip-per-second", 25));
        this.connectionBurstLimit = Math.max(connectionThrottlePerIpPerSecond, cfg.getInt("features.netty-protection.connection-burst-limit", 40));
        this.whitelistLoopback = cfg.getBoolean("features.netty-protection.whitelist-local-ips", true);
        this.whitelistSiteLocal = cfg.getBoolean("features.netty-protection.whitelist-site-local-ips", false);
        this.logBlockedPackets = cfg.getBoolean("features.netty-protection.log-blocked-packets", false);
        this.maxTrackedIps = Math.max(1_000, cfg.getInt("features.netty-protection.max-tracked-ips", 50_000));

        this.earlyStageProtectionEnabled = cfg.getBoolean("features.netty-protection.early-stage-protection-enabled", true);
        this.earlyStageMaxPacketsPerSecond = Math.max(10, cfg.getInt("features.netty-protection.early-stage-max-packets-per-second", 40));

        this.protectConcurrentConnectionFlood = cfg.getBoolean("features.netty-protection.protect-concurrent-connection-flood", false);
        this.maxConcurrentConnectionsPerIp = Math.max(1, cfg.getInt("features.netty-protection.max-concurrent-connections-per-ip", 10));
        if (enabled && (earlyStageProtectionEnabled || protectConcurrentConnectionFlood)) {
            earlyStageInjector.scheduleHook();
        }

        whitelistedIps.clear();
        if (whitelistLoopback) {
            whitelistedIps.add("127.0.0.1");
            whitelistedIps.add("0:0:0:0:0:0:0:1");
            whitelistedIps.add("localhost");
        }
        for (String ip : cfg.getStringList("features.netty-protection.whitelisted-ips")) {
            if (ip != null && !ip.isBlank()) {
                whitelistedIps.add(ip.trim());
            }
        }

        if (!enabled) {
            // Y09 fix: previously, toggling the module off via reload left any handlers
            // already sitting in player pipelines in place — they would simply be skipped
            // over (isEnabled() check) rather than actually removed, leaking a handler
            // instance (and a reference back to this plugin's classloader) in every active
            // connection's pipeline for as long as those connections stayed open.
            List<Future<?>> pending = new ArrayList<>();
            for (GuardRegistration registration : activeChannels.values()) {
                pending.add(injector.uninjectChannel(registration.channel(), registration.handler()));
            }
            activeChannels.clear();
            awaitPending(pending, 1);
        } else if (injector.isInitialized()) {
            Runnable reinjectTask = () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player == null) continue;
                    if (plugin.getSchedulerAdapter() != null && plugin.getSchedulerAdapter().isFolia()) {
                        plugin.getSchedulerAdapter().runEntity(player, () -> injectIfAllowed(player));
                    } else {
                        injectIfAllowed(player);
                    }
                }
            };
            SchedulerAdapter scheduler = plugin.getSchedulerAdapter();
            if (scheduler != null) {
                scheduler.runGlobal(reinjectTask);
            } else {
                reinjectTask.run();
            }
        }
    }

    public void stop() {
        enabled = false;
        injectionGeneration.incrementAndGet();
        pendingInjections.clear();
        earlyStageInjector.shutdown();
        degradedChannels.clear();
        List<Future<?>> pending = new ArrayList<>();
        for (GuardRegistration registration : activeChannels.values()) {
            pending.add(injector.uninjectChannel(registration.channel(), registration.handler()));
        }
        activeChannels.clear();
        ipBuckets.clear();
        concurrentConnectionsPerIp.clear();
        quittingChannels.clear();

        // Y08 fix: previously returned immediately after merely scheduling the handler
        // removals, without waiting for them. On plugin disable/reload that risks a stale
        // NettyPacketGuardHandler instance (holding a reference back to this module, and
        // transitively to this plugin's classloader) still sitting in a live pipeline after
        // the plugin has been considered "stopped".
        awaitPending(pending, 2);

    }

    private void awaitPending(List<Future<?>> futures, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        for (Future<?> future : futures) {
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                future.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled) return;

        InetAddress address = event.getAddress();
        if (address == null) return;

        if (isWhitelisted(address)) {
            return;
        }

        String hostAddress = address.getHostAddress();
        long now = System.currentTimeMillis();
        cleanupIpBucketsIfDue(now);

        IpConnectionBucket bucket;
        synchronized (ipBuckets) {
            bucket = ipBuckets.get(hostAddress);
            if (bucket == null && ipBuckets.size() < maxTrackedIps) {
                bucket = new IpConnectionBucket(connectionBurstLimit);
                ipBuckets.put(hostAddress, bucket);
            }
        }
        if (bucket == null) {
            // Y11 fix: fail closed on brand-new IPs once the tracking table is at capacity,
            // instead of letting it grow without bound under a wide-IP attack.
            totalBotBurstsBlocked.increment();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§c[NoLag] Server is under high connection load. Please try again shortly.");
            logWarning("IP tracking table at capacity (" + maxTrackedIps + "); rejecting new IP: " + hostAddress);
            return;
        }

        if (!bucket.tryConsume(connectionThrottlePerIpPerSecond, connectionBurstLimit, now)) {
            totalBotBurstsBlocked.increment();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    "§c[NoLag] Too many connection attempts from your network. Please wait a moment.");
            logWarning("Throttled connection flood attempt from IP: " + hostAddress);
        }
    }

    /**
     * Shared whitelist check (loopback / site-local / explicit IP list) used by both the
     * login-rate throttle ({@link #onPreLogin}) and the raw connection-count guard
     * ({@link #tryAcquireConnectionSlot}), so the two stay consistent by construction
     * instead of duplicating (and risking drifting) the same three conditions.
     */
    private boolean isWhitelisted(InetAddress address) {
        if (address == null) return false;
        if (whitelistLoopback && address.isLoopbackAddress()) return true;
        if (whitelistSiteLocal && address.isSiteLocalAddress()) return true;
        return whitelistedIps.contains(address.getHostAddress());
    }

    /**
     * P01: called once per raw child channel, the moment it is accepted and registered —
     * before any Minecraft protocol handling, before AsyncPlayerPreLoginEvent, before even
     * the early packet-rate guard is installed. Enforces a hard cap on how many connections
     * a single IP may hold open at once.
     *
     * <p>Returns {@code true} if the connection may proceed. Returns {@code false} if the
     * caller must close {@code channel} immediately because this IP is already at its
     * concurrent-connection limit. On success, a listener is registered so the slot is
     * released automatically whenever the channel eventually closes — callers never need to
     * (and must not) release it manually.</p>
     */
    boolean tryAcquireConnectionSlot(Channel channel) {
        if (!enabled || !protectConcurrentConnectionFlood || channel == null) {
            return true;
        }
        SocketAddress remote = channel.remoteAddress();
        if (!(remote instanceof InetSocketAddress inetRemote)) {
            // Not a regular TCP socket address (e.g. embedded/local transport in tests) —
            // nothing meaningful to rate-limit by IP, so let it through.
            return true;
        }
        InetAddress address = inetRemote.getAddress();
        if (address == null || isWhitelisted(address)) {
            return true;
        }

        String host = address.getHostAddress();
        AtomicInteger counter = concurrentConnectionsPerIp.computeIfAbsent(host, k -> new AtomicInteger());
        int current = counter.incrementAndGet();
        if (current > maxConcurrentConnectionsPerIp) {
            counter.decrementAndGet();
            totalConnectionFloodsBlocked.increment();
            logWarning("Rejected connection from " + host + ": concurrent-connection limit ("
                    + maxConcurrentConnectionsPerIp + ") reached.");
            return false;
        }
        channel.closeFuture().addListener(f -> releaseConnectionSlot(host));
        return true;
    }

    private void releaseConnectionSlot(String host) {
        concurrentConnectionsPerIp.computeIfPresent(host, (key, counter) ->
                counter.decrementAndGet() <= 0 ? null : counter);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled) return;

        Player player = event.getPlayer();
        if (player.hasPermission("nolag.netty.bypass")) {
            Channel bypassed = injector.getChannel(player);
            earlyStageInjector.removeChildGuard(bypassed);
            GuardRegistration registration = activeChannels.get(player.getUniqueId());
            if (registration != null && registration.channel() == bypassed
                    && activeChannels.remove(player.getUniqueId(), registration)) {
                injector.uninjectChannel(bypassed, registration.handler());
            }
            return;
        }

        injectPlayer(player);
    }

    private void injectIfAllowed(Player player) {
        if (!enabled || !player.isOnline()) return;
        if (!player.hasPermission("nolag.netty.bypass")) { injectPlayer(player); return; }
        Channel bypassed = injector.getChannel(player);
        earlyStageInjector.removeChildGuard(bypassed);
        GuardRegistration registration = activeChannels.get(player.getUniqueId());
        if (registration != null && registration.channel() == bypassed
                && activeChannels.remove(player.getUniqueId(), registration)) {
            injector.uninjectChannel(bypassed, registration.handler());
        }
    }

    private void injectPlayer(Player player) {
        if (!enabled || player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Channel channel = injector.getChannel(player);
        if (channel == null) {
            unprotectedChannelsCounter.incrementAndGet();
            plugin.getLogger().warning("[NettyProtection] Could not locate channel for " + name
                    + "; disconnecting to avoid falsely claiming packet protection.");
            player.kickPlayer("Network protection unavailable. Please contact the administrator.");
            return;
        }
        GuardRegistration current = activeChannels.get(uuid);
        if (current != null && current.channel() == channel
                && channel.pipeline().get(NettyChannelInjector.HANDLER_NAME) == current.handler()) return;
        PendingInjection previousPending = pendingInjections.get(uuid);
        if (previousPending != null && previousPending.channel() == channel) return;
        NettyPacketGuardHandler handler = new NettyPacketGuardHandler(this, uuid, name);
        PendingInjection pending = new PendingInjection(channel, handler);
        long generation = injectionGeneration.get();
        pendingInjections.put(uuid, pending);

        // K04 fix: only mark/track the channel as protected once the injection has actually
        // been confirmed to succeed by the injector, instead of assuming success the moment
        // the pipeline task was merely scheduled.
        Future<Boolean> injectFuture = injector.inject(channel, handler);
        injectFuture.addListener(f -> {
            boolean success = injectFuture.isSuccess() && Boolean.TRUE.equals(injectFuture.getNow());
            if (!pendingInjections.remove(uuid, pending) || generation != injectionGeneration.get()) {
                injector.uninjectChannel(channel, handler);
                return;
            }
            if (success) {
                if (enabled && channel.isOpen() && !quittingChannels.contains(channel)) {
                    GuardRegistration registration = new GuardRegistration(channel, handler);
                    GuardRegistration old = activeChannels.put(uuid, registration);
                    // Reload/quit may race with publication from the Netty event loop.
                    if (generation != injectionGeneration.get() || !enabled
                            || quittingChannels.contains(channel)) {
                        activeChannels.remove(uuid, registration);
                        injector.uninjectChannel(channel, handler);
                        if (old != null && old.channel() != channel) {
                            injector.uninjectChannel(old.channel(), old.handler());
                        }
                        return;
                    }
                    if (old != null && old.channel() != channel) {
                        injector.uninjectChannel(old.channel(), old.handler());
                    }
                    degradedChannels.remove(channel);
                    earlyStageInjector.removeChildGuard(channel);
                } else {
                    injector.uninjectChannel(channel, handler);
                }
            } else {
                // Late injection callbacks from reloads, disconnects or previous
                // attempts must never disconnect a now fully guarded player.
                if (!enabled || !channel.isOpen() || quittingChannels.contains(channel)
                        || isGuarded(uuid, channel)) {
                    return;
                }
                // No confirmed joined-player handler. Early guard is a rate limiter,
                // NOT equivalent to the full player guard. Fail closed when absent.
                unprotectedChannelsCounter.incrementAndGet();
                boolean hasEarlyGuard = earlyStageProtectionEnabled
                        && earlyStageInjector.hasChildGuard(channel);
                if (hasEarlyGuard) {
                    degradedChannels.add(channel);
                    channel.closeFuture().addListener(closed -> degradedChannels.remove(channel));
                } else {
                    channel.close();
                }
                plugin.getLogger().warning("[NettyProtection] Full packet guard failed for " + name
                        + (hasEarlyGuard ? "; early limiter retained (degraded)." : "; connection closed (fail-closed).")
                        + " Failures: " + unprotectedChannelsCounter.get()
                        + ", active degraded: " + degradedChannels.size());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Channel channel = injector.getChannel(player);
        if (channel != null) {
            PendingInjection pending = pendingInjections.get(uuid);
            if (pending != null && pending.channel() == channel) pendingInjections.remove(uuid, pending);
            GuardRegistration registration = activeChannels.get(uuid);
            boolean active = registration != null && registration.channel() == channel
                    && activeChannels.remove(uuid, registration);
            degradedChannels.remove(channel);
            quittingChannels.add(channel);
            channel.closeFuture().addListener(f -> quittingChannels.remove(channel));
            if (active) {
                injector.uninjectChannel(channel, registration.handler());
            } else if (pending != null && pending.channel() == channel) {
                injector.uninjectChannel(channel, pending.handler());
            }
        }
    }

    private boolean isGuarded(UUID uuid, Channel channel) {
        GuardRegistration registration = activeChannels.get(uuid);
        return registration != null && registration.channel() == channel
                && channel.pipeline().get(NettyChannelInjector.HANDLER_NAME) == registration.handler();
    }

    /**
     * Conditional removal (O07 fix): only clears the registration if it is still pointing
     * at the given channel. {@code NettyPacketGuardHandler#channelInactive} for a stale/old
     * connection (e.g. a very fast reconnect under the same UUID) must not be able to wipe
     * out a newer connection's registration.
     */
    public void unregisterChannel(UUID uuid, Channel channel, NettyPacketGuardHandler handler) {
        if (uuid == null || channel == null) return;
        activeChannels.remove(uuid, new GuardRegistration(channel, handler));
    }

    private record GuardRegistration(Channel channel, NettyPacketGuardHandler handler) { }
    private record PendingInjection(Channel channel, NettyPacketGuardHandler handler) { }

    private void cleanupIpBucketsIfDue(long now) {
        long last = lastBucketCleanupTime.get();
        if (now - last < 60_000L) {
            return;
        }
        if (lastBucketCleanupTime.compareAndSet(last, now)) {
            ipBuckets.entrySet().removeIf(entry -> now - entry.getValue().lastUpdate > 30_000L);
        }
    }

    // Diagnostics & Statistics API
    public void incrementPacketsInspected() {
        totalPacketsInspected.increment();
    }

    /**
     * O04 fix companion: lets callers batch per-second packet counts into a single
     * LongAdder#add() call instead of calling increment() once per individual packet.
     */
    public void addPacketsInspected(long count) {
        if (count > 0) {
            totalPacketsInspected.add(count);
        }
    }

    public void incrementFloodPacketsBlocked() {
        totalFloodPacketsBlocked.increment();
    }

    public void incrementExploitsBlocked() {
        totalExploitsBlocked.increment();
    }

    public void incrementBotBurstsBlocked() {
        totalBotBurstsBlocked.increment();
    }

    public void incrementConnectionFloodsBlocked() {
        totalConnectionFloodsBlocked.increment();
    }

    public void logWarning(String message) {
        long now = System.nanoTime();
        long last = lastWarningNanos.get();
        if (logBlockedPackets && (last == 0 || now - last >= TimeUnit.SECONDS.toNanos(5))
                && lastWarningNanos.compareAndSet(last, now)) {
            plugin.getLogger().warning("[NoLag-Netty] " + message);
        }
    }

    public void resetStatistics() {
        totalPacketsInspected.reset();
        totalFloodPacketsBlocked.reset();
        totalExploitsBlocked.reset();
        totalBotBurstsBlocked.reset();
        totalConnectionFloodsBlocked.reset();
    }

    // Getters
    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxPacketRatePerSecond() {
        return maxPacketRatePerSecond;
    }

    public int getPacketRateBurst() {
        return packetRateBurst;
    }

    public boolean isDisconnectOnExtremeFlood() {
        return disconnectOnExtremeFlood;
    }

    public int getExtremeFloodThreshold() {
        return extremeFloodThreshold;
    }

    public boolean isProtectTabCompleteFlood() {
        return protectTabCompleteFlood;
    }

    public int getMaxTabCompletePerSecond() {
        return maxTabCompletePerSecond;
    }

    public boolean isProtectCustomPayloadFlood() {
        return protectCustomPayloadFlood;
    }

    public int getMaxCustomPayloadPerSecond() {
        return maxCustomPayloadPerSecond;
    }

    public boolean isProtectWindowClickFlood() {
        return protectWindowClickFlood;
    }

    public int getMaxWindowClickPerSecond() {
        return maxWindowClickPerSecond;
    }

    public boolean isFilterInvalidMovement() {
        return filterInvalidMovement;
    }

    public boolean isFilterCrashPackets() {
        return filterCrashPackets;
    }

    public boolean isEarlyStageProtectionEnabled() {
        return earlyStageProtectionEnabled;
    }

    public int getEarlyStageMaxPacketsPerSecond() {
        return earlyStageMaxPacketsPerSecond;
    }

    public boolean isProtectConcurrentConnectionFlood() {
        return protectConcurrentConnectionFlood;
    }

    public int getMaxConcurrentConnectionsPerIp() {
        return maxConcurrentConnectionsPerIp;
    }

    public long getTotalConnectionFloodsBlocked() {
        return totalConnectionFloodsBlocked.sum();
    }

    /** Number of distinct IPs currently holding at least one open connection. */
    public int getTrackedConcurrentConnectionIpCount() {
        return concurrentConnectionsPerIp.size();
    }

    public int getActiveGuardedChannelCount() {
        return activeChannels.size();
    }

    public int getActiveDegradedChannelCount() { return degradedChannels.size(); }

    public int getUnprotectedChannelsCount() {
        return unprotectedChannelsCounter.get();
    }

    public long getTotalPacketsInspected() {
        return totalPacketsInspected.sum();
    }

    public long getTotalFloodPacketsBlocked() {
        return totalFloodPacketsBlocked.sum();
    }

    public long getTotalExploitsBlocked() {
        return totalExploitsBlocked.sum();
    }

    public long getTotalBotBurstsBlocked() {
        return totalBotBurstsBlocked.sum();
    }

    public Logger getLogger() {
        return plugin.getLogger();
    }

    public NettyChannelInjector getInjector() {
        return injector;
    }

    /**
     * Token bucket for IP-based handshake rate limiting, guarded by a short {@code
     * synchronized} block (a handful of arithmetic operations).
     *
     * <p>O05 fix: this class previously claimed to be "lock-free" in its javadoc while
     * actually using {@code synchronized}, which is misleading. It is intentionally NOT
     * lock-free: implementing a genuinely lock-free fractional-token bucket would require
     * CAS loops over boxed/bit-packed doubles for little practical benefit here, since the
     * critical section is a few arithmetic operations and contention per IP is bounded by
     * how many simultaneous login attempts a single address can realistically produce.</p>
     */
    private static final class IpConnectionBucket {
        private double tokens;
        private volatile long lastUpdate;

        IpConnectionBucket(int burstCapacity) {
            this.tokens = burstCapacity;
            this.lastUpdate = System.currentTimeMillis();
        }

        synchronized boolean tryConsume(int ratePerSecond, int burstCapacity, long now) {
            long deltaMs = Math.max(0L, now - lastUpdate);
            lastUpdate = now;

            double refill = (deltaMs / 1000.0) * ratePerSecond;
            tokens = Math.min(burstCapacity, tokens + refill);

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
