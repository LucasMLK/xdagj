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
package io.xdag.p2p;

import io.xdag.Kernel;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.ChainStats;
import io.xdag.net.message.MessageCode;
import io.xdag.net.message.consensus.BlockRequestMessage;
import io.xdag.net.message.consensus.NewBlockMessage;
import io.xdag.net.message.consensus.SyncBlockMessage;
import io.xdag.net.message.consensus.XdagMessage;
import java.util.HashSet;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * XDAG-specific P2P event handler that processes blockchain messages
 * Replaces the functionality of the old XdagP2pHandler
 */
@Slf4j
public class XdagP2pEventHandler extends io.xdag.p2p.P2pEventHandler {

    private final Kernel kernel;
    private final Blockchain blockchain;
    private final SyncManager syncManager;

    public XdagP2pEventHandler(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.syncManager = kernel.getSyncMgr();

        // Register XDAG-specific message types
        // Phase 7.3: Register Block message types
        this.messageTypes = new HashSet<>();

        this.messageTypes.add(MessageCode.NEW_BLOCK.toByte());
        this.messageTypes.add(MessageCode.SYNC_BLOCK.toByte());
        this.messageTypes.add(MessageCode.BLOCK_REQUEST.toByte());
    }

    @Override
    public void onConnect(io.xdag.p2p.channel.Channel channel) {
        log.info("Peer connected: {} (Node ID: {})",
                channel.getRemoteAddress(), channel.getNodeId());
    }

    @Override
    public void onDisconnect(io.xdag.p2p.channel.Channel channel) {
        log.info("Peer disconnected: {} (Node ID: {})",
                channel.getRemoteAddress(), channel.getNodeId());
    }

    @Override
    public void onMessage(io.xdag.p2p.channel.Channel channel, Bytes data) {
        if (data.size() == 0) {
            log.warn("Received empty message from {}", channel.getRemoteAddress());
            return;
        }

        byte messageType = data.get(0);

        try {
            switch (MessageCode.of(messageType)) {
                case NEW_BLOCK:
                    handleNewBlock(channel, data);
                    break;
                case SYNC_BLOCK:
                    handleSyncBlock(channel, data);
                    break;
                case BLOCK_REQUEST:
                    handleBlockRequest(channel, data);
                    break;
                default:
                    log.warn("Unknown message type {} from {}",
                            messageType, channel.getRemoteAddress());
            }
        } catch (Exception e) {
            log.error("Error processing message type {} from {}: {}",
                    messageType, channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle NEW_BLOCK_V5 message - a new Block propagated through the network (Phase 7.3)
     *
     * This is the NEW handler for Block objects received from network peers.
     * Unlike the legacy handleNewBlock(), this method:
     * - Uses NewBlockMessage to deserialize Block objects
     * - Wraps in SyncBlock instead of SyncBlock
     * - Calls syncManager.validateAndAddNewBlock() instead of validateAndAddNewBlock()
     * - Uses functional blockchain.tryToConnect(Block) for import
     */
    private void handleNewBlock(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            NewBlockMessage msg = new NewBlockMessage(data.toArray());
            Block block = msg.getBlock();

            if (syncManager.isSyncOld()) {
                return;
            }

            log.debug("Received NEW_BLOCK_V5: {} from {}",
                    block.getHash().toHexString(), channel.getRemoteAddress());

            // Create peer adapter - get network info from kernel
            XdagPeerAdapter peer = new XdagPeerAdapter(
                channel,
                kernel.getConfig().getNodeSpec().getNetwork(),
                kernel.getConfig().getNodeSpec().getNetworkVersion()
            );

            // Phase 7.3: Use SyncBlock instead of SyncBlock
            SyncManager.SyncBlock syncBlock = new SyncManager.SyncBlock(
                block, msg.getTtl() - 1, peer, false);
            syncManager.validateAndAddNewBlock(syncBlock);
        } catch (Exception e) {
            log.error("Error handling NEW_BLOCK_V5 from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_BLOCK_V5 message - a historical Block during sync (Phase 7.3)
     *
     * Similar to handleNewBlock() but marks blocks as "old" (old=true) to indicate
     * they are part of historical sync rather than real-time propagation.
     */
    private void handleSyncBlock(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlockMessage msg = new SyncBlockMessage(data.toArray());
            Block block = msg.getBlock();

            log.debug("Received SYNC_BLOCK_V5: {} from {}",
                    block.getHash().toHexString(), channel.getRemoteAddress());

            XdagPeerAdapter peer = new XdagPeerAdapter(
                channel,
                kernel.getConfig().getNodeSpec().getNetwork(),
                kernel.getConfig().getNodeSpec().getNetworkVersion()
            );

            // Phase 7.3: Use SyncBlock with old=true (historical sync)
            SyncManager.SyncBlock syncBlock = new SyncManager.SyncBlock(
                block, msg.getTtl() - 1, peer, true);
            syncManager.validateAndAddNewBlock(syncBlock);
        } catch (Exception e) {
            log.error("Error handling SYNC_BLOCK_V5 from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle Block_REQUEST - request for a specific Block by hash (Phase 7.3)
     *
     * When a peer requests a specific Block (usually a missing parent block),
     * this handler looks up the block and sends it back via SYNC_BLOCK_V5 message.
     */
    private void handleBlockRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlockRequestMessage msg = new BlockRequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            // Phase 8.3.2: Use unified getBlockByHash() method
            Block block = blockchain.getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("Responding to Block_REQUEST for {} from {}",
                        Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());

                // Send requested Block as SYNC_BLOCK_V5 (with ttl=1, not for broadcast)
                SyncBlockMessage response = new SyncBlockMessage(block, 1);
                channel.send(Bytes.wrap(response.getBody()));
            } else {
                log.debug("Block_REQUEST for {} from {} - block not found",
                        Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
            }
        } catch (Exception e) {
            log.error("Error handling Block_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Update global network statistics from remote peer (Phase 7.3 ChainStats support)
     */
    private void updateChainStats(XdagMessage message) {
        syncManager.getIsUpdateXdagStats().compareAndSet(false, true);
        ChainStats remoteChainStats = message.getChainStats();
        // Phase 7.3: Use new updateStatsFromRemote() method with ChainStats
        blockchain.updateStatsFromRemote(remoteChainStats);
    }

    /**
     * Request a specific Block by hash from a channel (Phase 7.3)
     *
     * This method is called by SyncManager when a Block references a missing parent block.
     * The receiving peer will respond with the requested Block via SYNC_BLOCK_V5 message.
     *
     * @param channel P2P channel to send request to
     * @param hash Hash of the requested Block
     */
    // Phase 7.3: Use getChainStats() directly (XdagStats deleted)
    public void requestBlockByHash(io.xdag.p2p.channel.Channel channel, Bytes32 hash) {
        try {
            BlockRequestMessage msg = new BlockRequestMessage(
                org.apache.tuweni.bytes.MutableBytes.wrap(hash.toArray()),
                blockchain.getChainStats()
            );
            log.debug("Sending Block_REQUEST for {} to {}",
                    hash.toHexString(), channel.getRemoteAddress());
            channel.send(Bytes.wrap(msg.getBody()));
        } catch (Exception e) {
            log.error("Error sending Block_REQUEST to {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Legacy method - SUMS protocol removed in v5.1
     * @deprecated Use Hybrid Sync Protocol instead
     */
    @Deprecated
    public long sendGetBlocks(io.xdag.p2p.channel.Channel channel, long startTime, long endTime) {
        log.warn("sendGetBlocks() called but SUMS protocol removed in v5.1 - use Hybrid Sync Protocol");
        return 0;
    }

    /**
     * Legacy method - SUMS protocol removed in v5.1
     * @deprecated Use Hybrid Sync Protocol instead
     */
    @Deprecated
    public long sendGetSums(io.xdag.p2p.channel.Channel channel, long startTime, long endTime) {
        log.warn("sendGetSums() called but SUMS protocol removed in v5.1 - use Hybrid Sync Protocol");
        return 0;
    }
}
