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
        // Phase 7.3.0: Removed NEW_BLOCK and SYNC_BLOCK (use V5 messages)
        this.messageTypes = new HashSet<>();
        this.messageTypes.add(MessageCode.BLOCKS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.BLOCKS_REPLY.toByte());
        this.messageTypes.add(MessageCode.SUMS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SUMS_REPLY.toByte());
        this.messageTypes.add(MessageCode.BLOCK_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNCBLOCK_REQUEST.toByte());
        this.messageTypes.add(MessageCode.BLOCKEXT_REQUEST.toByte());

        // Phase 7.3: Register BlockV5 message types
        this.messageTypes.add(MessageCode.NEW_BLOCK_V5.toByte());
        this.messageTypes.add(MessageCode.SYNC_BLOCK_V5.toByte());
        this.messageTypes.add(MessageCode.BLOCKV5_REQUEST.toByte());
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
                // Phase 7.3.0: Removed NEW_BLOCK and SYNC_BLOCK cases (use V5 messages)
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
                case NEW_BLOCK_V5:
                    handleNewBlockV5(channel, data);
                    break;
                case SYNC_BLOCK_V5:
                    handleSyncBlockV5(channel, data);
                    break;
                case BLOCKV5_REQUEST:
                    handleBlockV5Request(channel, data);
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
     * Phase 7.3.0: Removed handleNewBlock() and handleSyncBlock() methods
     *
     * Legacy NEW_BLOCK and SYNC_BLOCK message support was removed in Phase 7.3.0.
     * All block receiving now uses NEW_BLOCK_V5 and SYNC_BLOCK_V5 messages.
     * See handleNewBlockV5() and handleSyncBlockV5() for BlockV5 support.
     */

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

            // Phase 7.3.0: Send BlockV5 messages only (no legacy fallback)
            List<Block> blocks = blockchain.getBlocksByTime(startTime, endTime);
            for (Block block : blocks) {
                try {
                    io.xdag.core.BlockV5 blockV5 = kernel.getBlockStore().getBlockV5ByHash(block.getHash(), true);
                    if (blockV5 != null) {
                        SyncBlockV5Message blockMsg = new SyncBlockV5Message(blockV5, 1);
                        channel.send(Bytes.wrap(blockMsg.getBody()));
                    } else {
                        log.debug("Block {} not available as BlockV5, skipping", block.getHash().toHexString());
                    }
                } catch (Exception e) {
                    log.debug("Failed to get BlockV5 for hash {}", block.getHash().toHexString());
                }
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
     * Handle BLOCK_REQUEST - request for a specific block by hash (Phase 7.3.0)
     *
     * Phase 7.3.0: Updated to send BlockV5 messages only.
     * Blocks not available as BlockV5 will be skipped (not sent).
     */
    private void handleBlockRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlockRequestMessage msg = new BlockRequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            // Phase 7.3.0: Try to get BlockV5
            try {
                io.xdag.core.BlockV5 blockV5 = kernel.getBlockStore().getBlockV5ByHash(Bytes32.wrap(hash), true);
                if (blockV5 != null) {
                    log.debug("Responding to BLOCK_REQUEST for {} with BlockV5", Bytes32.wrap(hash).toHexString());
                    NewBlockV5Message response = new NewBlockV5Message(blockV5,
                            kernel.getConfig().getNodeSpec().getTTL());
                    channel.send(Bytes.wrap(response.getBody()));
                } else {
                    log.debug("Block {} not available as BlockV5, not sending", Bytes32.wrap(hash).toHexString());
                }
            } catch (Exception e) {
                log.debug("Failed to get BlockV5 for hash {}", Bytes32.wrap(hash).toHexString());
            }
        } catch (Exception e) {
            log.error("Error handling BLOCK_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNCBLOCK_REQUEST - request for a specific historical block (Phase 7.3.0)
     *
     * Phase 7.3.0: Updated to send BlockV5 messages only.
     * Blocks not available as BlockV5 will be skipped (not sent).
     */
    private void handleSyncBlockRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlockRequestMessage msg = new SyncBlockRequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            // Phase 7.3.0: Try to get BlockV5
            try {
                io.xdag.core.BlockV5 blockV5 = kernel.getBlockStore().getBlockV5ByHash(Bytes32.wrap(hash), true);
                if (blockV5 != null) {
                    log.debug("Responding to SYNCBLOCK_REQUEST for {} with BlockV5",
                            Bytes32.wrap(hash).toHexString());
                    SyncBlockV5Message response = new SyncBlockV5Message(blockV5, 1);
                    channel.send(Bytes.wrap(response.getBody()));
                } else {
                    log.debug("Block {} not available as BlockV5, not sending", Bytes32.wrap(hash).toHexString());
                }
            } catch (Exception e) {
                log.debug("Failed to get BlockV5 for hash {}", Bytes32.wrap(hash).toHexString());
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
     * Handle NEW_BLOCK_V5 message - a new BlockV5 propagated through the network (Phase 7.3)
     *
     * This is the NEW handler for BlockV5 objects received from network peers.
     * Unlike the legacy handleNewBlock(), this method:
     * - Uses NewBlockV5Message to deserialize BlockV5 objects
     * - Wraps in SyncBlockV5 instead of SyncBlock
     * - Calls syncManager.validateAndAddNewBlockV5() instead of validateAndAddNewBlock()
     * - Uses functional blockchain.tryToConnect(BlockV5) for import
     */
    private void handleNewBlockV5(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            NewBlockV5Message msg = new NewBlockV5Message(data.toArray());
            io.xdag.core.BlockV5 block = msg.getBlock();

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

            // Phase 7.3: Use SyncBlockV5 instead of SyncBlock
            SyncManager.SyncBlockV5 syncBlock = new SyncManager.SyncBlockV5(
                block, msg.getTtl() - 1, peer, false);
            syncManager.validateAndAddNewBlockV5(syncBlock);
        } catch (Exception e) {
            log.error("Error handling NEW_BLOCK_V5 from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_BLOCK_V5 message - a historical BlockV5 during sync (Phase 7.3)
     *
     * Similar to handleNewBlockV5() but marks blocks as "old" (old=true) to indicate
     * they are part of historical sync rather than real-time propagation.
     */
    private void handleSyncBlockV5(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlockV5Message msg = new SyncBlockV5Message(data.toArray());
            io.xdag.core.BlockV5 block = msg.getBlock();

            log.debug("Received SYNC_BLOCK_V5: {} from {}",
                    block.getHash().toHexString(), channel.getRemoteAddress());

            XdagPeerAdapter peer = new XdagPeerAdapter(
                channel,
                kernel.getConfig().getNodeSpec().getNetwork(),
                kernel.getConfig().getNodeSpec().getNetworkVersion()
            );

            // Phase 7.3: Use SyncBlockV5 with old=true (historical sync)
            SyncManager.SyncBlockV5 syncBlock = new SyncManager.SyncBlockV5(
                block, msg.getTtl() - 1, peer, true);
            syncManager.validateAndAddNewBlockV5(syncBlock);
        } catch (Exception e) {
            log.error("Error handling SYNC_BLOCK_V5 from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle BLOCKV5_REQUEST - request for a specific BlockV5 by hash (Phase 7.3)
     *
     * When a peer requests a specific BlockV5 (usually a missing parent block),
     * this handler looks up the block and sends it back via SYNC_BLOCK_V5 message.
     */
    private void handleBlockV5Request(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            BlockV5RequestMessage msg = new BlockV5RequestMessage(data.toArray());
            Bytes hash = msg.getHash();

            // Look up BlockV5 by hash
            io.xdag.core.BlockV5 block = blockchain.getBlockV5ByHash(Bytes32.wrap(hash), true);
            if (block != null) {
                log.debug("Responding to BLOCKV5_REQUEST for {} from {}",
                        Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());

                // Send requested BlockV5 as SYNC_BLOCK_V5 (with ttl=1, not for broadcast)
                SyncBlockV5Message response = new SyncBlockV5Message(block, 1);
                channel.send(Bytes.wrap(response.getBody()));
            } else {
                log.debug("BLOCKV5_REQUEST for {} from {} - block not found",
                        Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
            }
        } catch (Exception e) {
            log.error("Error handling BLOCKV5_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
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
     * Phase 7.3.0: Deleted sendNewBlock() method
     *
     * Legacy sendNewBlock(Block, int) method was removed when NewBlockMessage was deleted.
     * Use kernel.broadcastBlockV5() for broadcasting BlockV5 blocks instead.
     */

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

    /**
     * Request a specific BlockV5 by hash from a channel (Phase 7.3)
     *
     * This method is called by SyncManager when a BlockV5 references a missing parent block.
     * The receiving peer will respond with the requested BlockV5 via SYNC_BLOCK_V5 message.
     *
     * @param channel P2P channel to send request to
     * @param hash Hash of the requested BlockV5
     */
    public void requestBlockV5ByHash(io.xdag.p2p.channel.Channel channel, Bytes32 hash) {
        try {
            BlockV5RequestMessage msg = new BlockV5RequestMessage(
                org.apache.tuweni.bytes.MutableBytes.wrap(hash.toArray()),
                blockchain.getXdagStats()
            );
            log.debug("Sending BLOCKV5_REQUEST for {} to {}",
                    hash.toHexString(), channel.getRemoteAddress());
            channel.send(Bytes.wrap(msg.getBody()));
        } catch (Exception e) {
            log.error("Error sending BLOCKV5_REQUEST to {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }
}
