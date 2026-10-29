package me.nolag.modules.netty;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NettyChannelInjectorTest {

    @Test
    void lateRemovalOfOldGuardDoesNotRemoveNewGuard() {
        NettyChannelInjector injector = new NettyChannelInjector(Logger.getAnonymousLogger());
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            ChannelInboundHandlerAdapter oldGuard = new ChannelInboundHandlerAdapter();
            ChannelInboundHandlerAdapter newGuard = new ChannelInboundHandlerAdapter();

            var first = injector.inject(channel, oldGuard);
            channel.runPendingTasks();
            assertEquals(Boolean.TRUE, first.getNow());
            var second = injector.inject(channel, newGuard);
            channel.runPendingTasks();
            assertEquals(Boolean.TRUE, second.getNow());
            injector.uninjectChannel(channel, oldGuard);
            channel.runPendingTasks();

            assertSame(newGuard, channel.pipeline().get(NettyChannelInjector.HANDLER_NAME));
            injector.uninjectChannel(channel, newGuard);
            channel.runPendingTasks();
            assertNull(channel.pipeline().get(NettyChannelInjector.HANDLER_NAME));
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
