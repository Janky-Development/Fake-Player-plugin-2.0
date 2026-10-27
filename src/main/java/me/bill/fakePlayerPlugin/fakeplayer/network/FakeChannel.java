package me.bill.fakePlayerPlugin.fakeplayer.network;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.Consumer;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

public final class FakeChannel extends AbstractChannel {

    private static final EventLoop EVENT_LOOP = new DefaultEventLoop();

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final ChannelPipeline pipeline = new FakeChannelPipeline(this);
    private final InetAddress address;

    private volatile boolean open = true;
    private volatile boolean active = true;

    private volatile Consumer<Object> packetListener;

    public FakeChannel(InetAddress address) {
        super(null);
        this.address = address;
    }

    public FakeChannel(Channel parent, InetAddress address) {
        super(parent);
        this.address = address;
    }

    public Consumer<Object> getPacketListener() {
        return packetListener;
    }

    @Override
    public ChannelConfig config() {
        config.setAutoRead(true);
        return config;
    }

    @Override
    public ChannelPipeline pipeline() {
        return pipeline;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ChannelMetadata metadata() {
        return new ChannelMetadata(true);
    }

    @Override
    public EventLoop eventLoop() {
        return EVENT_LOOP;
    }

    @Override
    protected SocketAddress localAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return new InetSocketAddress(address, 25565);
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new AbstractUnsafe() {
            @Override
            public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
                safeSetSuccess(promise);
            }
        };
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected void doBeginRead() {}

    @Override
    protected void doBind(SocketAddress localAddress) {}

    @Override
    protected void doDisconnect() {

        active = false;
    }

    @Override
    protected void doClose() {

        active = false;
        open = false;
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) {

        for (; ; ) {
            Object msg = in.current();
            if (msg == null) break;
            in.remove();
        }
    }
}
