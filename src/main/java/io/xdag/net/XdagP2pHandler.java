/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.spec.NodeSpec;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageQueue;
import io.xdag.net.message.ReasonCode;
import io.xdag.net.message.consensus.BlockRequestMessage;
import io.xdag.net.message.consensus.NewBlockMessage;
import io.xdag.net.message.consensus.SyncBlockMessage;
import io.xdag.net.message.consensus.SyncBlockRequestMessage;
import io.xdag.net.message.p2p.DisconnectMessage;
import io.xdag.net.message.p2p.HelloMessage;
import io.xdag.net.message.p2p.InitMessage;
import io.xdag.net.message.p2p.PingMessage;
import io.xdag.net.message.p2p.PongMessage;
import io.xdag.net.message.p2p.WorldMessage;
import io.xdag.net.node.NodeManager;
import io.xdag.utils.exception.UnreachableException;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Xdag P2P message handler
 */
@Slf4j
public class XdagP2pHandler extends SimpleChannelInboundHandler<Message> {

    // Phase 8.2.2: Constants for block request rate limiting
    private static final long MAX_TIME_RANGE = 86400; // Max time range: 1 day (in XDAG time units, ~18 hours)
    private static final int MAX_BLOCKS_PER_REQUEST = 1000; // Max blocks per request
    private static final int BATCH_SIZE = 100; // Blocks per batch
    private static final long BATCH_DELAY_MS = 100; // Delay between batches (ms)

    private static final ScheduledExecutorService exec = Executors
            .newSingleThreadScheduledExecutor(new ThreadFactory() {
                private final AtomicInteger cnt = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "p2p-" + cnt.getAndIncrement());
                }
            });

    // Phase 8.2.2: Executor for large block request processing (prevents DoS attacks)
    private static final java.util.concurrent.ExecutorService blockSendExecutor =
            Executors.newCachedThreadPool(new ThreadFactory() {
                private final AtomicInteger cnt = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "block-sender-" + cnt.getAndIncrement());
                    t.setDaemon(true);  // Daemon thread won't prevent JVM shutdown
                    return t;
                }
            });

    private final Channel channel;

    private final Kernel kernel;
    private final Config config;
    private final NodeSpec nodeSpec;
    private final Blockchain chain;
    private final ChannelManager channelMgr;
    private final NodeManager nodeMgr;
    private final PeerClient client;
    private final SyncManager syncMgr;

    private final NetDBManager netdbMgr;
    private final MessageQueue msgQueue;

    private final AtomicBoolean isHandshakeDone = new AtomicBoolean(false);

    private ScheduledFuture<?> getNodes = null;
    private ScheduledFuture<?> pingPong = null;

    private byte[] secret = CryptoProvider.nextBytes(InitMessage.SECRET_LENGTH);
    private long timestamp = System.currentTimeMillis();

    public XdagP2pHandler(Channel channel, Kernel kernel) {
        this.channel = channel;
        this.kernel = kernel;
        this.config = kernel.getConfig();
        this.nodeSpec = kernel.getConfig().getNodeSpec();

        this.chain = kernel.getBlockchain();
        this.channelMgr = kernel.getChannelMgr();
        this.nodeMgr = kernel.getNodeMgr();
        this.client = kernel.getClient();

        this.syncMgr = kernel.getSyncMgr();
        this.netdbMgr = kernel.getNetDBMgr();
        this.msgQueue = channel.getMessageQueue();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.debug("P2P handler active, remoteIp = {}, remotePort = {}", channel.getRemoteIp(), channel.getRemotePort());

        // activate message queue
        msgQueue.activate(ctx);

        // disconnect if too many connections
        if (channel.isInbound() && channelMgr.size() >= config.getNodeSpec().getNetMaxInboundConnections()) {
            msgQueue.disconnect(ReasonCode.TOO_MANY_PEERS);
            return;
        }

        if (channel.isInbound()) {
            msgQueue.sendMessage(new InitMessage(secret, timestamp));
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.debug("P2P handler inactive, remoteIp = {}", channel.getRemoteIp());

        // deactivate the message queue
        msgQueue.deactivate();

        // stop scheduled workers
        if (getNodes != null) {
            getNodes.cancel(false);
            getNodes = null;
        }

        if (pingPong != null) {
            pingPong.cancel(false);
            pingPong = null;
        }

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("Exception in P2P handler, remoteIp = {}, remotePort = {}", channel.getRemoteIp(), channel.getRemotePort(),cause);

        // close connection on exception
        ctx.close();
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, Message msg) {
        log.trace("Received message: {}", msg);

        switch (msg.getCode()) {
        /* p2p */
        case DISCONNECT -> onDisconnect(ctx, (DisconnectMessage) msg);
        case PING -> onPing();
        case PONG -> onPong();
        case HANDSHAKE_INIT -> onHandshakeInit((InitMessage) msg);
        case HANDSHAKE_HELLO -> onHandshakeHello((HelloMessage) msg);
        case HANDSHAKE_WORLD -> onHandshakeWorld((WorldMessage) msg);

        /* sync */
        // Phase 7.3.0: Removed NEW_BLOCK, SYNC_BLOCK from routing (use V5 messages)
        case BLOCK_REQUEST, SYNCBLOCK_REQUEST, NEW_BLOCK, SYNC_BLOCK ->  // Phase 3: Block message support
                onXdag(msg);
        default -> ctx.fireChannelRead(msg);
        }
    }

    protected void onDisconnect(ChannelHandlerContext ctx, DisconnectMessage msg) {
        ReasonCode reason = msg.getReason();
        log.info("Received a DISCONNECT message: reason = {}, remoteIP = {}",
                reason, channel.getRemoteIp());

        ctx.close();
    }

    protected void onHandshakeInit(InitMessage msg) {
        // unexpected
        if (channel.isInbound()) {
            return;
        }

        // check message
        if (!msg.validate()) {
            this.msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }

        // record the secret
        this.secret = msg.getSecret();
        this.timestamp = msg.getTimestamp();

        // send the HELLO message
        this.msgQueue.sendMessage(new HelloMessage(nodeSpec.getNetwork(), nodeSpec.getNetworkVersion(),
                client.getPeerId(), client.getPort(), config.getClientId(), config.getClientCapabilities().toArray(),
                chain.getLatestMainBlockNumber(), secret, client.getCoinbase(), config.getEnableGenerateBlock(),
                config.getNodeTag()));
    }

    protected void onHandshakeHello(HelloMessage msg) {
        // unexpected
        if (channel.isOutbound()) {
            return;
        }
        Peer peer = msg.getPeer(channel.getRemoteIp());

        // check peer
        ReasonCode code = checkPeer(peer, true);
        if (code != null) {
            msgQueue.disconnect(code);
            return;
        }

        // check message
        if (!Arrays.equals(secret, msg.getSecret()) || !msg.validate(config)) {
            msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }
        // send the WORLD message
        this.msgQueue.sendMessage(new WorldMessage(nodeSpec.getNetwork(), nodeSpec.getNetworkVersion(),
                client.getPeerId(), client.getPort(), config.getClientId(), config.getClientCapabilities().toArray(),
                chain.getLatestMainBlockNumber(), secret, client.getCoinbase(), config.getEnableGenerateBlock(),
                config.getNodeTag()));

        // handshake done
        onHandshakeDone(peer);
    }

    protected void onHandshakeWorld(WorldMessage msg) {
        // unexpected
        if (channel.isInbound()) {
            return;
        }
        Peer peer = msg.getPeer(channel.getRemoteIp());

        // check peer
        ReasonCode code = checkPeer(peer, true);
        if (code != null) {
            msgQueue.disconnect(code);
            return;
        }

        // check message
        if (!Arrays.equals(secret, msg.getSecret()) || !msg.validate(config)) {
            msgQueue.disconnect(ReasonCode.INVALID_HANDSHAKE);
            return;
        }

        // handshake done
        onHandshakeDone(peer);
    }

    private long lastPing;

    protected void onPing() {
        PongMessage pong = new PongMessage();
        msgQueue.sendMessage(pong);
        lastPing = System.currentTimeMillis();
    }

    protected void onPong() {
        if (lastPing > 0) {
            long latency = System.currentTimeMillis() - lastPing;
            channel.getRemotePeer().setLatency(latency);
        }
    }

    protected void onXdag(Message msg) {
        if (!isHandshakeDone.get()) {
            return;
        }

        switch (msg.getCode()) {
            // Phase 7.3.0: Removed NEW_BLOCK and SYNC_BLOCK cases (use V5 messages)
            case NEW_BLOCK -> processNewBlock((NewBlockMessage) msg);  // Phase 3
            case BLOCK_REQUEST -> processBlockRequest((BlockRequestMessage) msg);
            case SYNC_BLOCK -> processSyncBlock((SyncBlockMessage) msg);  // Phase 3
            case SYNCBLOCK_REQUEST -> processSyncBlockRequest((SyncBlockRequestMessage) msg);
            default -> throw new UnreachableException();
        }
    }

    /**
     * Check whether the peer is valid to connect.
     */
    private ReasonCode checkPeer(Peer peer, boolean newHandShake) {
        // has to be same network
        if (newHandShake && !nodeSpec.getNetwork().equals(peer.getNetwork())) {
            return ReasonCode.BAD_NETWORK;
        }

        // has to be compatible version
        if (nodeSpec.getNetworkVersion() != peer.getNetworkVersion()) {
            return ReasonCode.BAD_NETWORK_VERSION;
        }

        return null;
    }

    private void onHandshakeDone(Peer peer) {
        if (isHandshakeDone.compareAndSet(false, true)) {
            // register into channel manager
            channelMgr.onChannelActive(channel, peer);

            // start ping pong
            pingPong = exec.scheduleAtFixedRate(() -> msgQueue.sendMessage(new PingMessage()),
                    channel.isInbound() ? 1 : 0, 1, TimeUnit.MINUTES);
        } else {
            msgQueue.disconnect(ReasonCode.HANDSHAKE_EXISTS);
        }
    }

    protected void processNewBlock(NewBlockMessage msg) {
        Block block = msg.getBlock();
        if (syncMgr.isSyncOld()) {
            return;
        }

        log.debug("processNewBlock:{} from node {} (v5.1)",
            block.getHash(), channel.getRemoteAddress());

        try {
            chain.tryToConnect(block);
        } catch (Exception e) {
            log.error("Failed to process Block: {}", block.getHash(), e);
        }
    }

    protected void processSyncBlock(SyncBlockMessage msg) {
        Block block = msg.getBlock();

        log.debug("processSyncBlock:{} from node {} (v5.1)",
            block.getHash(), channel.getRemoteAddress());

        try {
            chain.tryToConnect(block);
        } catch (Exception e) {
            log.error("Failed to process Block during sync: {}", block.getHash(), e);
        }
    }

    protected void processBlockRequest(BlockRequestMessage msg) {
        Bytes hash = msg.getHash();
        int ttl = config.getNodeSpec().getTTL();

        // Phase 7.3.0: Send Block messages only
        try {
            Block block = kernel.getBlockStore().getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("processBlockRequest: findBlock {}", Bytes32.wrap(hash).toHexString());
                NewBlockMessage message = new NewBlockMessage(block, ttl);
                msgQueue.sendMessage(message);
                return;
            } else {
                log.debug("Block {} not available as Block, not sending", Bytes32.wrap(hash).toHexString());
            }
        } catch (Exception e) {
            log.debug("Failed to get Block for hash {}: {}",
                     Bytes32.wrap(hash).toHexString(), e.getMessage());
        }
    }

    private void processSyncBlockRequest(SyncBlockRequestMessage msg) {
        Bytes hash = msg.getHash();

        // Phase 7.3.0: Send Block messages only
        try {
            Block block = kernel.getBlockStore().getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("processSyncBlockRequest: findBlock {}, to node: {}",
                         Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
                SyncBlockMessage message = new SyncBlockMessage(block, 1);
                msgQueue.sendMessage(message);
                return;
            } else {
                log.debug("Block {} not available as Block, not sending to node: {}",
                         Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
            }
        } catch (Exception e) {
            log.debug("Failed to get Block for hash {}: {}",
                     Bytes32.wrap(hash).toHexString(), e.getMessage());
        }
    }

    public void sendNewBlock(Block newBlock, int TTL) {
        log.debug("send Block:{} to node:{} (v5.1)", newBlock.getHash(), channel.getRemoteAddress());
        NewBlockMessage msg = new NewBlockMessage(newBlock, TTL);
        sendMessage(msg);
    }

    public void sendMessage(Message message) {
        msgQueue.sendMessage(message);
    }

}
