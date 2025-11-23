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

import io.xdag.DagKernel;
import io.xdag.consensus.sync.HybridSyncManager;
import io.xdag.consensus.sync.HybridSyncP2pAdapter;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.core.Transaction;
import io.xdag.core.TransactionBroadcastManager;
import io.xdag.core.TransactionPool;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.BlockRequestMessage;
import io.xdag.p2p.message.NewBlockMessage;
import io.xdag.p2p.message.NewTransactionMessage;
import io.xdag.p2p.message.SyncBlockMessage;
import io.xdag.p2p.message.SyncBlocksReplyMessage;
import io.xdag.p2p.message.SyncBlocksRequestMessage;
import io.xdag.p2p.message.SyncEpochBlocksReplyMessage;
import io.xdag.p2p.message.SyncEpochBlocksRequestMessage;
import io.xdag.p2p.message.SyncHeightReplyMessage;
import io.xdag.p2p.message.SyncHeightRequestMessage;
import io.xdag.p2p.message.SyncMainBlocksReplyMessage;
import io.xdag.p2p.message.SyncMainBlocksRequestMessage;
import io.xdag.p2p.message.SyncTransactionsReplyMessage;
import io.xdag.p2p.message.SyncTransactionsRequestMessage;
import io.xdag.p2p.message.XdagMessageCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * XDAG-specific P2P event handler that processes blockchain messages Replaces the functionality of
 * the old XdagP2pHandler
 */
@Slf4j
public class XdagP2pEventHandler extends io.xdag.p2p.P2pEventHandler {

  private final DagKernel dagKernel;
  private final DagChain dagChain;
  private final HybridSyncManager hybridSyncManager;

  /**
   * Hybrid sync P2P adapter for handling new sync protocol messages (6)
   */
  @Setter
  private HybridSyncP2pAdapter hybridSyncAdapter;

  public XdagP2pEventHandler(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagChain = dagKernel.getDagChain();
    this.hybridSyncManager = dagKernel.getHybridSyncManager();

    // Register XDAG-specific message types
    //  Register Block message types
    this.messageTypes = new HashSet<>();

    this.messageTypes.add(XdagMessageCode.NEW_BLOCK.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_BLOCK.toByte());
    this.messageTypes.add(XdagMessageCode.BLOCK_REQUEST.toByte());

    //  Register hybrid sync protocol messages (10 new message types)
    this.messageTypes.add(XdagMessageCode.SYNC_HEIGHT_REQUEST.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_HEIGHT_REPLY.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_MAIN_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_MAIN_BLOCKS_REPLY.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_EPOCH_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_EPOCH_BLOCKS_REPLY.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_BLOCKS_REQUEST.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_BLOCKS_REPLY.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_TRANSACTIONS_REQUEST.toByte());
    this.messageTypes.add(XdagMessageCode.SYNC_TRANSACTIONS_REPLY.toByte());

    //  Register transaction broadcast message (Phase 3)
    this.messageTypes.add(XdagMessageCode.NEW_TRANSACTION.toByte());
  }

  @Override
  public void onConnect(Channel channel) {
    log.info("Peer connected: {} (Node ID: {})",
        channel.getRemoteAddress(), channel.getNodeId());
  }

  @Override
  public void onDisconnect(Channel channel) {
    log.info("Peer disconnected: {} (Node ID: {})",
        channel.getRemoteAddress(), channel.getNodeId());
  }

  @Override
  public void onMessage(Channel channel, Bytes data) {
    // 5 DEBUG: Log all message receptions at INFO level
    log.info("⚡ onMessage() called from {} (data size: {} bytes)",
        channel.getRemoteAddress(), data.size());

    if (data.isEmpty()) {
      log.warn("Received empty message from {}", channel.getRemoteAddress());
      return;
    }

    byte messageType = data.get(0);
    log.info("📨 Received message type 0x{} from {} (registered: {})",
        String.format("%02X", messageType),
        channel.getRemoteAddress(),
        messageTypes.contains(messageType) ? "YES" : "NO");

    try {
      XdagMessageCode code = XdagMessageCode.of(messageType);
      if (code == null) {
        log.warn("Unknown message code 0x{} from {}",
            String.format("%02X", messageType), channel.getRemoteAddress());
        return;
      }

      // Extract message body (skip first byte which is message code)
      // All handlers expect pure body without message code prefix
      Bytes body = data.slice(1);

      switch (code) {
        case NEW_BLOCK:
          handleNewBlock(channel, body);
          break;
        case SYNC_BLOCK:
          handleSyncBlock(channel, body);
          break;
        case BLOCK_REQUEST:
          handleBlockRequest(channel, body);
          break;
        //  Handle hybrid sync protocol messages
        case SYNC_HEIGHT_REQUEST:
          handleSyncHeightRequest(channel, body);
          break;
        case SYNC_HEIGHT_REPLY:
          handleSyncHeightReply(channel, body);
          break;
        case SYNC_MAIN_BLOCKS_REQUEST:
          handleSyncMainBlocksRequest(channel, body);
          break;
        case SYNC_MAIN_BLOCKS_REPLY:
          handleSyncMainBlocksReply(channel, body);
          break;
        case SYNC_EPOCH_BLOCKS_REQUEST:
          handleSyncEpochBlocksRequest(channel, body);
          break;
        case SYNC_EPOCH_BLOCKS_REPLY:
          handleSyncEpochBlocksReply(channel, body);
          break;
        case SYNC_BLOCKS_REQUEST:
          handleSyncBlocksRequest(channel, body);
          break;
        case SYNC_BLOCKS_REPLY:
          handleSyncBlocksReply(channel, body);
          break;
        case SYNC_TRANSACTIONS_REQUEST:
          handleSyncTransactionsRequest(channel, body);
          break;
        case SYNC_TRANSACTIONS_REPLY:
          handleSyncTransactionsReply(channel, body);
          break;
        case NEW_TRANSACTION:
          handleNewTransaction(channel, body);
          break;
        default:
          log.warn("Unknown message type {} from {}",
              messageType, channel.getRemoteAddress());
      }
    } catch (Exception e) {
      // If parsing failed at top level (e.g. unknown message code or corrupted wrapper), penalize
      penalizePeer(channel, "Malformed top-level message (type 0x" +
          String.format("%02X", messageType) + "): " + e.getMessage());
      log.error("Error processing message type {} from {}: {}",
          messageType, channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle NEW_BLOCK message - a new Block propagated through the network with TTL
   *
   * <p>This handler implements TTL-based hop limiting for block broadcasting:
   * <ol>
   *   <li>Check TTL > 0, drop if TTL expired</li>
   *   <li>Import block via DagChain</li>
   *   <li>Forward to other peers with TTL - 1 (excluding sender) if imported successfully</li>
   * </ol>
   *
   * @param channel the channel that sent this block
   * @param body    message body (without message code prefix)
   */
  private void handleNewBlock(Channel channel, Bytes body) {
    try {
      // 1. Deserialize block message
      NewBlockMessage msg = new NewBlockMessage(body.toArray());
      Block block = msg.getBlock();
      Bytes32 blockHash = block.getHash();
      int ttl = msg.getTtl();

      log.info("Received NEW_BLOCK: {} from {} (height={}, epoch={}, TTL={})",
          blockHash.toHexString().substring(0, 18) + "...",
          channel.getRemoteAddress(),
          block.getInfo() != null ? block.getInfo().getHeight() : "unknown",
          block.getEpoch(),
          ttl);

      // 2. Check TTL (hop limit)
      if (ttl <= 0) {
        log.trace("Block {} dropped: TTL expired (TTL={})",
            blockHash.toHexString().substring(0, 16) + "...", ttl);
        return;  // TTL expired, do not process or forward
      }

      // 3. Import block directly via DagChain
      DagImportResult result = dagChain.tryToConnect(block);

      if (result != null && result.isMainBlock()) {
        log.info("✓ Received block imported as main block at height {}",
            result.getHeight());

        // 4. Forward to other peers with decremented TTL (exclude sender)
        if (msg.shouldForward()) {
          NewBlockMessage forwardMsg = msg.decrementTTL();
          if (forwardMsg.shouldForward()) {
            broadcastBlockMessage(forwardMsg, channel);
            log.debug("Forwarded block {} to other peers (TTL: {} -> {})",
                blockHash.toHexString().substring(0, 16) + "...",
                ttl, forwardMsg.getTtl());
          } else {
            log.trace("Block {} not forwarded: TTL would expire (TTL={})",
                blockHash.toHexString().substring(0, 16) + "...", ttl);
          }
        }
      } else if (result != null && result.isOrphan()) {
        log.info("Received block imported as orphan");
      } else if (result != null) {
        // Check for invalid block status (potential malicious behavior)
        if (result.getStatus() == DagImportResult.ImportStatus.INVALID) {
          penalizePeer(channel, "Sent INVALID_BLOCK: " + result.getErrorMessage());
        } else {
          log.warn("Received block import failed: {} (status={})",
              result.getErrorMessage(), result.getStatus());
        }
      }

    } catch (Exception e) {
      // Message deserialization or processing error - likely malformed data
      penalizePeer(channel, "Malformed NEW_BLOCK message: " + e.getMessage());
      log.error("Error handling NEW_BLOCK from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_BLOCK message - a historical Block during sync (5)
   * <p>
   * Simplified implementation using DagChain.tryToConnect() directly
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncBlock(Channel channel, Bytes body) {
    try {
      SyncBlockMessage msg = new SyncBlockMessage(body.toArray());
      Block block = msg.getBlock();

      log.debug("Received SYNC_BLOCK: {} from {}",
          block.getHash().toHexString(), channel.getRemoteAddress());

      //  Import block directly via DagChain
      dagChain.tryToConnect(block);

    } catch (Exception e) {
      log.error("Error handling SYNC_BLOCK from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle Block_REQUEST - request for a specific Block by hash (3)
   * <p>
   * When a peer requests a specific Block (usually a missing parent block), this handler looks up
   * the block and sends it back via SYNC_BLOCK_V5 message.
   *
   * @param body message body (without message code prefix)
   */
  private void handleBlockRequest(Channel channel, Bytes body) {
    try {
      BlockRequestMessage msg = new BlockRequestMessage(body.toArray());
      Bytes hash = msg.getHash();

      //  Use unified getBlockByHash() method
      Block block = dagChain.getBlockByHash(Bytes32.wrap(hash), true);
      if (block != null) {
        log.debug("Responding to Block_REQUEST for {} from {}",
            Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());

        // Send requested Block as SYNC_BLOCK_V5 (with ttl=1, not for broadcast)
        SyncBlockMessage response = new SyncBlockMessage(block, 1);
        // Send Message object directly - Channel will handle encoding
        channel.send(response);
      } else {
        log.debug("Block_REQUEST for {} from {} - block not found",
            Bytes32.wrap(hash).toHexString(), channel.getRemoteAddress());
      }
    } catch (Exception e) {
      log.error("Error handling Block_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  // ==========  Hybrid Sync Protocol Handlers ==========

  /**
   * Handle SYNC_HEIGHT_REQUEST - peer asking for our chain height (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncHeightRequest(Channel channel, Bytes body) {
    try {
      SyncHeightRequestMessage request = new SyncHeightRequestMessage(body.toArray());

      // Get current chain stats
      ChainStats stats = dagChain.getChainStats();
      long mainHeight = stats.getMainBlockCount();
      long finalizedHeight = Math.max(0, mainHeight - 16384); // FINALITY_EPOCHS = 16384

      // Get tip hash
      Bytes32 tipHash = Bytes32.ZERO;
      if (mainHeight > 0) {
        Block tipBlock = dagChain.getMainBlockByHeight(mainHeight);
        if (tipBlock != null) {
          tipHash = tipBlock.getHash();
        }
      }

      // Create and send reply (BUGFIX BUG-022: Pass requestId from request to reply)
      SyncHeightReplyMessage reply = new SyncHeightReplyMessage(
          request.getRequestId(), mainHeight, finalizedHeight, tipHash);

      log.debug("Sending SyncHeightReply to {}: mainHeight={}, finalizedHeight={}, requestId={}",
          channel.getRemoteAddress(), mainHeight, finalizedHeight, request.getRequestId());

      // Send Message object directly - Channel will handle encoding
      channel.send(reply);

    } catch (Exception e) {
      log.error("Error handling SYNC_HEIGHT_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_HEIGHT_REPLY - received remote chain height (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncHeightReply(Channel channel, Bytes body) {
    try {
      SyncHeightReplyMessage reply = new SyncHeightReplyMessage(body.toArray());

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
   * Handle SYNC_MAIN_BLOCKS_REQUEST - peer requesting main chain blocks by height range (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncMainBlocksRequest(Channel channel, Bytes body) {
    try {
      SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(body.toArray());

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
        Block block = dagChain.getMainBlockByHeight(height);
        blocks.add(block); // May be null if block not found
      }

      // Create and send reply (BUGFIX BUG-022: Pass requestId from request to reply)
      SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(request.getRequestId(), blocks);

      log.debug("Sending SyncMainBlocksReply to {}: {} blocks, requestId={}",
          channel.getRemoteAddress(), blocks.size(), request.getRequestId());

      // Send Message object directly - Channel will handle encoding
      channel.send(reply);

    } catch (Exception e) {
      log.error("Error handling SYNC_MAIN_BLOCKS_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_MAIN_BLOCKS_REPLY - received main chain blocks (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncMainBlocksReply(Channel channel, Bytes body) {
    try {
      SyncMainBlocksReplyMessage reply = new SyncMainBlocksReplyMessage(body.toArray());

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
   * Handle SYNC_EPOCH_BLOCKS_REQUEST - peer requesting all block hashes in an epoch range (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncEpochBlocksRequest(Channel channel, Bytes body) {
    try {
      SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(body.toArray());

      long startEpoch = request.getStartEpoch();
      long endEpoch = request.getEndEpoch();
      long rangeSize = endEpoch - startEpoch + 1;

      log.debug("Received SyncEpochBlocksRequest from {}: epoch range [{}, {}] (size={})",
          channel.getRemoteAddress(), startEpoch, endEpoch, rangeSize);

      // Build map of epoch -> block hashes
      // Use LinkedHashMap to preserve insertion order (though client will iterate sequentially)
      java.util.Map<Long, List<Bytes32>> epochBlocksMap = new java.util.LinkedHashMap<>();
      int totalBlocks = 0;

      // Process each epoch in the range
      for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
        try {
          // Use DagChain's method to get all blocks in this epoch
          List<Block> blocks = dagChain.getCandidateBlocksInEpoch(epoch);

          if (blocks != null && !blocks.isEmpty()) {
            // Extract hashes from blocks
            List<Bytes32> hashes = new ArrayList<>();
            for (Block block : blocks) {
              if (block != null && block.getHash() != null) {
                hashes.add(block.getHash());
              }
            }

            if (!hashes.isEmpty()) {
              // Only include epochs that have blocks
              epochBlocksMap.put(epoch, hashes);
              totalBlocks += hashes.size();
              log.trace("Epoch {} has {} blocks", epoch, hashes.size());
            }
          }
        } catch (Exception e) {
          log.warn("Error retrieving blocks for epoch {}: {}", epoch, e.getMessage());
          // Continue with next epoch - better to reply with partial data than to fail
        }
      }

      log.debug("Found {} epochs with blocks ({} total blocks) in range [{}, {}]",
          epochBlocksMap.size(), totalBlocks, startEpoch, endEpoch);

      // Create and send reply with batch data (BUGFIX BUG-022: Pass requestId from request to reply)
      SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(request.getRequestId(), epochBlocksMap);

      log.debug("Sending SyncEpochBlocksReply to {}: {} epochs, {} total hashes, requestId={}",
          channel.getRemoteAddress(), epochBlocksMap.size(), totalBlocks, request.getRequestId());

      // Send Message object directly - Channel will handle encoding
      channel.send(reply);

    } catch (Exception e) {
      log.error("Error handling SYNC_EPOCH_BLOCKS_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_EPOCH_BLOCKS_REPLY - received epoch block hashes (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncEpochBlocksReply(Channel channel, Bytes body) {
    try {
      SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(body.toArray());

      int totalHashes = reply.getEpochBlocksMap().values().stream()
          .mapToInt(List::size)
          .sum();

      log.debug("Received SyncEpochBlocksReply from {}: {} epochs, {} total hashes",
          channel.getRemoteAddress(), reply.getEpochBlocksMap().size(), totalHashes);

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
   * Handle SYNC_BLOCKS_REQUEST - peer requesting blocks by hash list (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncBlocksRequest(Channel channel, Bytes body) {
    try {
      SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(body.toArray());

      List<Bytes32> hashes = request.getHashes();
      boolean isRaw = request.isRaw();

      log.debug("Received SyncBlocksRequest from {}: {} hashes",
          channel.getRemoteAddress(), hashes.size());

      // Collect blocks by hash
      List<Block> blocks = new ArrayList<>();
      for (Bytes32 hash : hashes) {
        Block block = dagChain.getBlockByHash(hash, true);
        blocks.add(block); // May be null if block not found
      }

      // Create and send reply (BUGFIX BUG-022: Pass requestId from request to reply)
      SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(request.getRequestId(), blocks);

      log.debug("Sending SyncBlocksReply to {}: {} blocks, requestId={}",
          channel.getRemoteAddress(), blocks.size(), request.getRequestId());

      // Send Message object directly - Channel will handle encoding
      channel.send(reply);

    } catch (Exception e) {
      log.error("Error handling SYNC_BLOCKS_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_BLOCKS_REPLY - received blocks by hash (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncBlocksReply(Channel channel, Bytes body) {
    try {
      SyncBlocksReplyMessage reply = new SyncBlocksReplyMessage(body.toArray());

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
   * Handle SYNC_TRANSACTIONS_REQUEST - peer requesting transactions by hash list (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncTransactionsRequest(Channel channel, Bytes body) {
    try {
      SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(body.toArray());

      List<Bytes32> hashes = request.getHashes();

      log.debug("Received SyncTransactionsRequest from {}: {} hashes",
          channel.getRemoteAddress(), hashes.size());

      // Collect transactions by hash
      // TODO: Implement transaction retrieval from blockchain/mempool
      // For now, return empty list
      List<Transaction> transactions = new ArrayList<>();

      // Create and send reply (BUGFIX BUG-022: Pass requestId from request to reply)
      SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(request.getRequestId(), transactions);

      log.debug("Sending SyncTransactionsReply to {}: {} transactions, requestId={}",
          channel.getRemoteAddress(), transactions.size(), request.getRequestId());

      // Send Message object directly - Channel will handle encoding
      channel.send(reply);

    } catch (Exception e) {
      log.error("Error handling SYNC_TRANSACTIONS_REQUEST from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle SYNC_TRANSACTIONS_REPLY - received transactions (6)
   *
   * @param body message body (without message code prefix)
   */
  private void handleSyncTransactionsReply(Channel channel, Bytes body) {
    try {
      SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(body.toArray());

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

  // ==========  Transaction Broadcast Handler (Phase 3) ==========

  /**
   * Handle NEW_TRANSACTION - received new transaction from peer (Phase 3)
   *
   * <p>This handler implements the anti-loop logic for transaction broadcasting:
   * <ol>
   *   <li>Check TTL > 0, drop if TTL expired</li>
   *   <li>Check if we've seen this transaction recently → skip if yes</li>
   *   <li>Add to transaction pool if valid</li>
   *   <li>Forward to other peers with TTL - 1 (excluding sender)</li>
   * </ol>
   *
   * @param channel the channel that sent this transaction
   * @param body    message body (without message code prefix)
   */
  private void handleNewTransaction(Channel channel, Bytes body) {
    try {
      // 1. Deserialize transaction message
      NewTransactionMessage msg = new NewTransactionMessage(body.toArray());
      Transaction tx = msg.getTransaction();
      Bytes32 txHash = tx.getHash();
      int ttl = msg.getTtl();

      log.debug("Received NEW_TRANSACTION {} from {} (TTL={})",
          txHash.toHexString().substring(0, 16) + "...",
          channel.getRemoteAddress(),
          ttl);

      // 2. Check TTL (hop limit)
      if (ttl <= 0) {
        log.trace("Transaction {} dropped: TTL expired (TTL={})",
            txHash.toHexString().substring(0, 16) + "...", ttl);
        return;  // TTL expired, do not process or forward
      }

      // 3. Check if we've seen this transaction recently (anti-loop protection)
      TransactionBroadcastManager broadcastManager =
          dagKernel.getTransactionBroadcastManager();
      if (broadcastManager != null) {
        if (!broadcastManager.shouldProcess(txHash)) {
          log.trace("Transaction {} already seen, skipping",
              txHash.toHexString().substring(0, 16) + "...");
          return;  // Prevents broadcast loop! ✓
        }
      }

      // 4. Add to transaction pool
      TransactionPool txPool = dagKernel.getTransactionPool();
      if (txPool == null) {
        log.warn("Transaction pool not available, cannot accept transaction {}",
            txHash.toHexString().substring(0, 16) + "...");
        return;
      }

      boolean added = txPool.addTransaction(tx);
      if (added) {
        log.info("Received transaction {} from peer {} added to pool (TTL={})",
            txHash.toHexString().substring(0, 16) + "...",
            channel.getRemoteAddress(),
            ttl);

        // 5. Forward to other peers with decremented TTL (exclude sender)
        if (msg.shouldForward() && broadcastManager != null) {
          // Create new message with TTL - 1
          NewTransactionMessage forwardMsg = msg.decrementTTL();

          if (forwardMsg.shouldForward()) {
            broadcastManager.broadcastTransactionMessage(forwardMsg, channel);
            log.debug("Forwarded transaction {} to other peers (TTL: {} -> {})",
                txHash.toHexString().substring(0, 16) + "...",
                ttl, forwardMsg.getTtl());
          } else {
            log.trace("Transaction {} not forwarded: TTL would expire (TTL={})",
                txHash.toHexString().substring(0, 16) + "...", ttl);
          }
        }
      } else {
        log.debug("Transaction {} from peer {} rejected by pool (duplicate/invalid) (TTL={})",
            txHash.toHexString().substring(0, 16) + "...",
            channel.getRemoteAddress(),
            ttl);
      }

    } catch (Exception e) {
      // Message deserialization error - likely malformed data
      penalizePeer(channel, "Malformed NEW_TRANSACTION message: " + e.getMessage());
      log.error("Error handling NEW_TRANSACTION from {}: {}",
          channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Penalize a peer for sending invalid or malicious data.
   *
   * <p>This method implements a strict "fail-fast" policy:
   * <ul>
   *   <li>Log the violation</li>
   *   <li>Close the connection immediately</li>
   * </ul>
   *
   * @param channel the peer channel
   * @param reason reason for penalty
   */
  private void penalizePeer(Channel channel, String reason) {
    log.warn("⛔ Penalizing peer {} due to: {}", channel.getRemoteAddress(), reason);
    try {
      // Disconnect immediately to prevent further processing
      channel.close();
    } catch (Exception e) {
      log.warn("Error closing channel for penalized peer {}: {}",
          channel.getRemoteAddress(), e.getMessage());
    }
  }

  /**
   * Broadcast a block message to all peers (excluding sender)
   *
   * <p>This method forwards received blocks with decremented TTL to prevent loops.
   *
   * @param message        block message with custom TTL
   * @param excludeChannel channel to exclude (sender), or null to broadcast to all
   */
  private void broadcastBlockMessage(NewBlockMessage message, Channel excludeChannel) {
    try {
      Block block = message.getBlock();
      Bytes32 blockHash = block.getHash();
      int ttl = message.getTtl();

      // Get P2P service
      io.xdag.p2p.P2pService p2pService = dagKernel.getP2pService();
      if (p2pService == null) {
        log.trace("P2P service not available, cannot broadcast block {}",
            blockHash.toHexString().substring(0, 16) + "...");
        return;
      }

      // Get all active channels
      List<Channel> channels = getActiveChannels(p2pService);
      if (channels.isEmpty()) {
        log.trace("No active peers to broadcast block {} (TTL={})",
            blockHash.toHexString().substring(0, 16) + "...", ttl);
        return;
      }

      // Broadcast to all peers (excluding sender)
      int broadcastCount = 0;
      for (Channel channel : channels) {
        if (channel != excludeChannel) {
          try {
            channel.send(message);
            broadcastCount++;
          } catch (Exception e) {
            log.warn("Failed to broadcast block {} to peer {}: {}",
                blockHash.toHexString().substring(0, 16) + "...",
                channel.getRemoteAddress(),
                e.getMessage());
          }
        }
      }

      if (broadcastCount > 0) {
        log.debug("Broadcasted block {} to {} peers (TTL={})",
            blockHash.toHexString().substring(0, 16) + "...",
            broadcastCount,
            ttl);
      }

    } catch (Exception e) {
      log.error("Error broadcasting block message: {}", e.getMessage(), e);
    }
  }

  /**
   * Get list of active channels from P2P service
   *
   * @param p2pService P2P service instance
   * @return list of active channels
   */
  private List<Channel> getActiveChannels(io.xdag.p2p.P2pService p2pService) {
    if (p2pService == null) {
      return new ArrayList<>();
    }

    try {
      io.xdag.p2p.channel.ChannelManager channelManager = p2pService.getChannelManager();
      if (channelManager == null) {
        return new ArrayList<>();
      }

      java.util.Map<java.net.InetSocketAddress, Channel> channels =
          channelManager.getChannels();

      if (channels == null || channels.isEmpty()) {
        return new ArrayList<>();
      }

      return new ArrayList<>(channels.values());

    } catch (Exception e) {
      log.error("Error getting active channels", e);
      return new ArrayList<>();
    }
  }

}
