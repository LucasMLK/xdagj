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
import io.xdag.core.BlockWrapper;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagStats;
import io.xdag.net.message.MessageCode;
import io.xdag.net.message.consensus.*;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        this.messageTypes = new HashSet<>();
        this.messageTypes.add(MessageCode.NEW_BLOCK.toByte());
        this.messageTypes.add(MessageCode.SYNC_BLOCK.toByte());
        this.messageTypes.add(MessageCode.BLOCKS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.BLOCKS_REPLY.toByte());
        this.messageTypes.add(MessageCode.SUMS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SUMS_REPLY.toByte());
        this.messageTypes.add(MessageCode.BLOCK_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNCBLOCK_REQUEST.toByte());
        this.messageTypes.add(MessageCode.BLOCKEXT_REQUEST.toByte());
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
                case BLOCKS_REQUEST:
                    handleBlocksRequest(channel, data);
                    break;
                case BLOCKS_REPLY:
                    handleBlocksReply(channel, data);
                    break;
                case SUMS_REQUEST:
                    handleSumsRequest(channel, data);
                    break;
                case SUMS_REPLY:
                    handleSumsReply(channel, data);
                    break;
                case BLOCK_REQUEST:
                    handleBlockRequest(channel, data);
                    break;
                case SYNCBLOCK_REQUEST:
                    handleSyncBlockRequest(channel, data);
                    break;
                case BLOCKEXT_REQUEST:
                    handleBlockExtRequest(channel, data);
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
     * Handle NEW_BLOCK message - a new block propagated through the network
     */
    private void handleNewBlock(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            NewBlockMessage msg = new NewBlockMessage(data.toArray());
            Block block = msg.getBlock();

            if (syncManager.isSyncOld()) {
                return;
            }

            log.debug("Received NEW_BLOCK: {} from {}",
                    block.getHashLow(), channel.getRemoteAddress());

            // Create peer adapter - get network info from kernel
            XdagPeerAdapter peer = new XdagPeerAdapter(
                channel,
                kernel.getConfig().getNodeSpec().getNetwork(),
                kernel.getConfig().getNodeSpec().getNetworkVersion()
            );
            BlockWrapper bw = new BlockWrapper(block, msg.getTtl() - 1, peer, false);
            syncManager.validateAndAddNewBlock(bw);
        } catch (Exception e) {
            log.error("Error handling NEW_BLOCK from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_BLOCK message - a historical block during sync
     */
    private void handleSyncBlock(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlockMessage msg = new SyncBlockMessage(data.toArray());
            Block block = msg.getBlock();

            log.debug("Received SYNC_BLOCK: {} from {}",
                    block.getHashLow(), channel.getRemoteAddress());

            XdagPeerAdapter peer = new XdagPeerAdapter(
                channel,
                kernel.getConfig().getNodeSpec().getNetwork(),
                kernel.getConfig().getNodeSpec().getNetworkVersion()
            );
            BlockWrapper bw = new BlockWrapper(block, msg.getTtl() - 1, peer, true);
            syncManager.validateAndAddNewBlock(bw);
        } catch (Exception e) {
            log.error("Error handling SYNC_BLOCK from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle BLOCKS_REQUEST - request for blocks in a time range
     */
    private void handleBlocksRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlocksRequestMessage msg = new BlocksRequestMessage(data.toArray());
            updateXdagStats(msg);

            long startTime = msg.getStarttime();
            long endTime = msg.getEndtime();
            long random = msg.getRandom();

            log.debug("Received BLOCKS_REQUEST [{} - {}] from {}",
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                            .format(XdagTime.xdagTimestampToMs(startTime)),
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                            .format(XdagTime.xdagTimestampToMs(endTime)),
                    channel.getRemoteAddress());

            // Fetch and send blocks
            List<Block> blocks = blockchain.getBlocksByTime(startTime, endTime);
            for (Block block : blocks) {
                SyncBlockMessage blockMsg = new SyncBlockMessage(block, 1);
                channel.send(Bytes.wrap(blockMsg.getBody()));
            }

            // Send reply
            BlocksReplyMessage reply = new BlocksReplyMessage(
                    startTime, endTime, random, blockchain.getXdagStats());
            channel.send(Bytes.wrap(reply.getBody()));
        } catch (Exception e) {
            log.error("Error handling BLOCKS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle BLOCKS_REPLY - response to blocks request
     */
    private void handleBlocksReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlocksReplyMessage msg = new BlocksReplyMessage(data.toArray());
            updateXdagStats(msg);

            long randomSeq = msg.getRandom();
            var future = kernel.getSync().getBlocksRequestMap().get(randomSeq);
            if (future != null) {
                future.set(Bytes.wrap(new byte[]{0}));
            }
        } catch (Exception e) {
            log.error("Error handling BLOCKS_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SUMS_REQUEST - request for block sums
     */
    private void handleSumsRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SumRequestMessage msg = new SumRequestMessage(data.toArray());
            updateXdagStats(msg);

            org.apache.tuweni.bytes.MutableBytes sums = org.apache.tuweni.bytes.MutableBytes.create(256);
            kernel.getBlockStore().loadSum(msg.getStarttime(), msg.getEndtime(), sums);

            SumReplyMessage reply = new SumReplyMessage(
                    msg.getEndtime(), msg.getRandom(), blockchain.getXdagStats(), sums);
            channel.send(Bytes.wrap(reply.getBody()));
        } catch (Exception e) {
            log.error("Error handling SUMS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SUMS_REPLY - response to sums request
     */
    private void handleSumsReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SumReplyMessage msg = new SumReplyMessage(data.toArray());
            updateXdagStats(msg);

            long randomSeq = msg.getRandom();
            var future = kernel.getSync().getSumsRequestMap().get(randomSeq);
            if (future != null) {
                future.set(msg.getSum());
            }
        } catch (Exception e) {
            log.error("Error handling SUMS_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle BLOCK_REQUEST - request for a specific block by hash
     */
    private void handleBlockRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlockRequestMessage msg = new BlockRequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            Block block = blockchain.getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("Responding to BLOCK_REQUEST for {}", Bytes32.wrap(hash).toHexString());
                NewBlockMessage response = new NewBlockMessage(block,
                        kernel.getConfig().getNodeSpec().getTTL());
                channel.send(Bytes.wrap(response.getBody()));
            }
        } catch (Exception e) {
            log.error("Error handling BLOCK_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNCBLOCK_REQUEST - request for a specific historical block
     */
    private void handleSyncBlockRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlockRequestMessage msg = new SyncBlockRequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            Block block = blockchain.getBlockByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("Responding to SYNCBLOCK_REQUEST for {}",
                        Bytes32.wrap(hash).toHexString());
                SyncBlockMessage response = new SyncBlockMessage(block, 1);
                channel.send(Bytes.wrap(response.getBody()));
            }
        } catch (Exception e) {
            log.error("Error handling SYNCBLOCK_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle BLOCKEXT_REQUEST - extended block request (currently not implemented)
     */
    private void handleBlockExtRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        // Not implemented yet
        log.debug("Received BLOCKEXT_REQUEST from {} (not implemented)",
                channel.getRemoteAddress());
    }

    /**
     * Update global network statistics from remote peer
     */
    private void updateXdagStats(XdagMessage message) {
        syncManager.getIsUpdateXdagStats().compareAndSet(false, true);
        XdagStats remoteXdagStats = message.getXdagStats();
        blockchain.getXdagStats().update(remoteXdagStats);
    }

    /**
     * Send a new block to a specific channel (used by broadcasting logic)
     */
    public void sendNewBlock(io.xdag.p2p.channel.Channel channel, Block block, int ttl) {
        try {
            log.debug("Sending NEW_BLOCK: {} to {}", block.getHashLow(), channel.getRemoteAddress());
            NewBlockMessage msg = new NewBlockMessage(block, ttl);
            channel.send(Bytes.wrap(msg.getBody()));
        } catch (Exception e) {
            log.error("Error sending NEW_BLOCK to {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Send BLOCKS_REQUEST to request blocks in a time range
     * @return random sequence number for tracking the response
     */
    public long sendGetBlocks(io.xdag.p2p.channel.Channel channel, long startTime, long endTime) {
        try {
            BlocksRequestMessage msg = new BlocksRequestMessage(startTime, endTime, blockchain.getXdagStats());
            log.debug("Sending BLOCKS_REQUEST [{} - {}] to {}",
                    startTime, endTime, channel.getRemoteAddress());
            channel.send(Bytes.wrap(msg.getBody()));
            return msg.getRandom();
        } catch (Exception e) {
            log.error("Error sending BLOCKS_REQUEST to {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Send SUMS_REQUEST to request block sums in a time range
     * @return random sequence number for tracking the response
     */
    public long sendGetSums(io.xdag.p2p.channel.Channel channel, long startTime, long endTime) {
        try {
            SumRequestMessage msg = new SumRequestMessage(startTime, endTime, blockchain.getXdagStats());
            log.debug("Sending SUMS_REQUEST [{} - {}] to {}",
                    startTime, endTime, channel.getRemoteAddress());
            channel.send(Bytes.wrap(msg.getBody()));
            return msg.getRandom();
        } catch (Exception e) {
            log.error("Error sending SUMS_REQUEST to {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
            return -1;
        }
    }
}
