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
import io.xdag.consensus.HybridSyncP2pAdapter;
import io.xdag.consensus.SyncManager;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.ChainStats;
import io.xdag.core.Transaction;
import io.xdag.net.message.MessageCode;
import io.xdag.net.message.consensus.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.Setter;
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

    /**
     * Hybrid sync P2P adapter for handling new sync protocol messages (Phase 1.6)
     */
    @Setter
    private HybridSyncP2pAdapter hybridSyncAdapter;

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

        // Phase 1.6: Register hybrid sync protocol messages (10 new message types)
        this.messageTypes.add(MessageCode.SYNC_HEIGHT_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNC_HEIGHT_REPLY.toByte());
        this.messageTypes.add(MessageCode.SYNC_MAIN_BLOCKS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNC_MAIN_BLOCKS_REPLY.toByte());
        this.messageTypes.add(MessageCode.SYNC_EPOCH_BLOCKS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNC_EPOCH_BLOCKS_REPLY.toByte());
        this.messageTypes.add(MessageCode.SYNC_BLOCKS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNC_BLOCKS_REPLY.toByte());
        this.messageTypes.add(MessageCode.SYNC_TRANSACTIONS_REQUEST.toByte());
        this.messageTypes.add(MessageCode.SYNC_TRANSACTIONS_REPLY.toByte());
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
                // Phase 1.6: Handle hybrid sync protocol messages
                case SYNC_HEIGHT_REQUEST:
                    handleSyncHeightRequest(channel, data);
                    break;
                case SYNC_HEIGHT_REPLY:
                    handleSyncHeightReply(channel, data);
                    break;
                case SYNC_MAIN_BLOCKS_REQUEST:
                    handleSyncMainBlocksRequest(channel, data);
                    break;
                case SYNC_MAIN_BLOCKS_REPLY:
                    handleSyncMainBlocksReply(channel, data);
                    break;
                case SYNC_EPOCH_BLOCKS_REQUEST:
                    handleSyncEpochBlocksRequest(channel, data);
                    break;
                case SYNC_EPOCH_BLOCKS_REPLY:
                    handleSyncEpochBlocksReply(channel, data);
                    break;
                case SYNC_BLOCKS_REQUEST:
                    handleSyncBlocksRequest(channel, data);
                    break;
                case SYNC_BLOCKS_REPLY:
                    handleSyncBlocksReply(channel, data);
                    break;
                case SYNC_TRANSACTIONS_REQUEST:
                    handleSyncTransactionsRequest(channel, data);
                    break;
                case SYNC_TRANSACTIONS_REPLY:
                    handleSyncTransactionsReply(channel, data);
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

    // ========== Phase 1.6: Hybrid Sync Protocol Handlers ==========

    /**
     * Handle SYNC_HEIGHT_REQUEST - peer asking for our chain height (Phase 1.6)
     */
    private void handleSyncHeightRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            // Deserialize request (empty body)
            SyncHeightRequestMessage request = new SyncHeightRequestMessage(data.toArray());

            // Get current chain stats
            ChainStats stats = blockchain.getChainStats();
            long mainHeight = stats.getMainBlockCount();
            long finalizedHeight = Math.max(0, mainHeight - 16384); // FINALITY_EPOCHS = 16384

            // Get tip hash
            Bytes32 tipHash = Bytes32.ZERO;
            if (mainHeight > 0) {
                Block tipBlock = blockchain.getBlockByHeight(mainHeight);
                if (tipBlock != null) {
                    tipHash = tipBlock.getHash();
                }
            }

            // Create and send reply
            SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
                    mainHeight, finalizedHeight, tipHash);

            log.debug("Sending SyncHeightReply to {}: mainHeight={}, finalizedHeight={}",
                    channel.getRemoteAddress(), mainHeight, finalizedHeight);

            channel.send(Bytes.wrap(reply.getBody()));

        } catch (Exception e) {
            log.error("Error handling SYNC_HEIGHT_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_HEIGHT_REPLY - received remote chain height (Phase 1.6)
     */
    private void handleSyncHeightReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncHeightReplyMessage reply = new SyncHeightReplyMessage(data.toArray());

            log.debug("Received SyncHeightReply from {}: mainHeight={}, finalizedHeight={}",
                    channel.getRemoteAddress(), reply.getMainHeight(), reply.getFinalizedHeight());

            // Notify adapter to complete the Future
            if (hybridSyncAdapter != null) {
                hybridSyncAdapter.onHeightReply(reply);
            } else {
                log.warn("HybridSyncAdapter not set, cannot process SyncHeightReply");
            }

        } catch (Exception e) {
            log.error("Error handling SYNC_HEIGHT_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_MAIN_BLOCKS_REQUEST - peer requesting main chain blocks by height range (Phase 1.6)
     */
    private void handleSyncMainBlocksRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(data.toArray());

            long fromHeight = request.getFromHeight();
            long toHeight = request.getToHeight();
            int maxBlocks = request.getMaxBlocks();
            boolean isRaw = request.isRaw();

            log.debug("Received SyncMainBlocksRequest from {}: [{}, {}], maxBlocks={}",
                    channel.getRemoteAddress(), fromHeight, toHeight, maxBlocks);

            // Collect blocks in requested range
            List<Block> blocks = new ArrayList<>();
            long actualToHeight = Math.min(toHeight, fromHeight + maxBlocks - 1);

            for (long height = fromHeight; height <= actualToHeight; height++) {
                Block block = blockchain.getBlockByHeight(height);
                blocks.add(block); // May be null if block not found
            }

            // Create and send reply
            SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(blocks);

            log.debug("Sending SyncMainBlocksReply to {}: {} blocks",
                    channel.getRemoteAddress(), blocks.size());

            channel.send(Bytes.wrap(reply.getBody()));

        } catch (Exception e) {
            log.error("Error handling SYNC_MAIN_BLOCKS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_MAIN_BLOCKS_REPLY - received main chain blocks (Phase 1.6)
     */
    private void handleSyncMainBlocksReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(data.toArray());

            log.debug("Received SyncMainBlocksReply from {}: {} blocks",
                    channel.getRemoteAddress(), reply.getBlocks().size());

            // Notify adapter to complete the Future
            if (hybridSyncAdapter != null) {
                hybridSyncAdapter.onMainBlocksReply(reply);
            } else {
                log.warn("HybridSyncAdapter not set, cannot process SyncMainBlocksReply");
            }

        } catch (Exception e) {
            log.error("Error handling SYNC_MAIN_BLOCKS_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_EPOCH_BLOCKS_REQUEST - peer requesting all block hashes in an epoch (Phase 1.6)
     */
    private void handleSyncEpochBlocksRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(data.toArray());

            long epoch = request.getEpoch();

            log.debug("Received SyncEpochBlocksRequest from {}: epoch={}",
                    channel.getRemoteAddress(), epoch);

            // Get all block hashes in this epoch
            // TODO: Implement efficient epoch block hash retrieval from DagStore
            // For now, return empty list
            List<Bytes32> hashes = new ArrayList<>();

            // Create and send reply
            SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(epoch, hashes);

            log.debug("Sending SyncEpochBlocksReply to {}: {} hashes",
                    channel.getRemoteAddress(), hashes.size());

            channel.send(Bytes.wrap(reply.getBody()));

        } catch (Exception e) {
            log.error("Error handling SYNC_EPOCH_BLOCKS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_EPOCH_BLOCKS_REPLY - received epoch block hashes (Phase 1.6)
     */
    private void handleSyncEpochBlocksReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(data.toArray());

            log.debug("Received SyncEpochBlocksReply from {}: epoch={}, {} hashes",
                    channel.getRemoteAddress(), reply.getEpoch(), reply.getHashes().size());

            // Notify adapter to complete the Future
            if (hybridSyncAdapter != null) {
                hybridSyncAdapter.onEpochBlocksReply(reply);
            } else {
                log.warn("HybridSyncAdapter not set, cannot process SyncEpochBlocksReply");
            }

        } catch (Exception e) {
            log.error("Error handling SYNC_EPOCH_BLOCKS_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_BLOCKS_REQUEST - peer requesting blocks by hash list (Phase 1.6)
     */
    private void handleSyncBlocksRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(data.toArray());

            List<Bytes32> hashes = request.getHashes();
            boolean isRaw = request.isRaw();

            log.debug("Received SyncBlocksRequest from {}: {} hashes",
                    channel.getRemoteAddress(), hashes.size());

            // Collect blocks by hash
            List<Block> blocks = new ArrayList<>();
            for (Bytes32 hash : hashes) {
                Block block = blockchain.getBlockByHash(hash, true);
                blocks.add(block); // May be null if block not found
            }

            // Create and send reply
            SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(blocks);

            log.debug("Sending SyncBlocksReply to {}: {} blocks",
                    channel.getRemoteAddress(), blocks.size());

            channel.send(Bytes.wrap(reply.getBody()));

        } catch (Exception e) {
            log.error("Error handling SYNC_BLOCKS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_BLOCKS_REPLY - received blocks by hash (Phase 1.6)
     */
    private void handleSyncBlocksReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(data.toArray());

            log.debug("Received SyncBlocksReply from {}: {} blocks",
                    channel.getRemoteAddress(), reply.getBlocks().size());

            // Notify adapter to complete the Future
            if (hybridSyncAdapter != null) {
                hybridSyncAdapter.onBlocksReply(reply);
            } else {
                log.warn("HybridSyncAdapter not set, cannot process SyncBlocksReply");
            }

        } catch (Exception e) {
            log.error("Error handling SYNC_BLOCKS_REPLY from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_TRANSACTIONS_REQUEST - peer requesting transactions by hash list (Phase 1.6)
     */
    private void handleSyncTransactionsRequest(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(data.toArray());

            List<Bytes32> hashes = request.getHashes();

            log.debug("Received SyncTransactionsRequest from {}: {} hashes",
                    channel.getRemoteAddress(), hashes.size());

            // Collect transactions by hash
            // TODO: Implement transaction retrieval from blockchain/mempool
            // For now, return empty list
            List<Transaction> transactions = new ArrayList<>();

            // Create and send reply
            SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(transactions);

            log.debug("Sending SyncTransactionsReply to {}: {} transactions",
                    channel.getRemoteAddress(), transactions.size());

            channel.send(Bytes.wrap(reply.getBody()));

        } catch (Exception e) {
            log.error("Error handling SYNC_TRANSACTIONS_REQUEST from {}: {}",
                    channel.getRemoteAddress(), e.getMessage(), e);
        }
    }

    /**
     * Handle SYNC_TRANSACTIONS_REPLY - received transactions (Phase 1.6)
     */
    private void handleSyncTransactionsReply(io.xdag.p2p.channel.Channel channel, Bytes data) {
        try {
            SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(data.toArray());

            log.debug("Received SyncTransactionsReply from {}: {} transactions",
                    channel.getRemoteAddress(), reply.getTransactions().size());

            // Notify adapter to complete the Future
            if (hybridSyncAdapter != null) {
                hybridSyncAdapter.onTransactionsReply(reply);
            } else {
                log.warn("HybridSyncAdapter not set, cannot process SyncTransactionsReply");
            }

        } catch (Exception e) {
            log.error("Error handling SYNC_TRANSACTIONS_REPLY from {}: {}",
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
