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
import io.xdag.consensus.epoch.EpochConsensusManager;
import io.xdag.consensus.epoch.SubmitResult;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.Transaction;
import io.xdag.core.TransactionBroadcastManager;
import io.xdag.core.TransactionPool;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.BlocksReplyMessage;
import io.xdag.p2p.message.EpochHashesReplyMessage;
import io.xdag.p2p.message.GetBlocksMessage;
import io.xdag.p2p.message.GetEpochHashesMessage;
import io.xdag.p2p.message.GetStatusMessage;
import io.xdag.p2p.message.Message;
import io.xdag.p2p.message.NewBlockHashMessage;
import io.xdag.p2p.message.NewTransactionMessage;
import io.xdag.p2p.message.StatusReplyMessage;
import io.xdag.p2p.message.XdagMessageCode;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * XDAG-specific P2P event handler that processes blockchain messages Replaces the functionality of
 * the old XdagP2pHandler
 */
@Slf4j
public class XdagP2pEventHandler extends io.xdag.p2p.P2pEventHandler implements io.xdag.core.listener.NewBlockListener {

  private final DagKernel dagKernel;
  private final DagChain dagChain;

  public XdagP2pEventHandler(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagChain = dagKernel.getDagChain();

    // Register as NewBlockListener for real-time broadcasting
    this.dagChain.registerNewBlockListener(this);

    // Register XDAG-specific message types
    this.messageTypes = new HashSet<>();

    //  Register transaction broadcast message (Phase 3)
    this.messageTypes.add(XdagMessageCode.NEW_TRANSACTION.toByte());

    // Register FastDAG Sync messages (v3.0)
    this.messageTypes.add(XdagMessageCode.NEW_BLOCK_HASH.toByte());
    this.messageTypes.add(XdagMessageCode.GET_BLOCKS.toByte());
    this.messageTypes.add(XdagMessageCode.BLOCKS_REPLY.toByte());
    this.messageTypes.add(XdagMessageCode.GET_EPOCH_HASHES.toByte());
    this.messageTypes.add(XdagMessageCode.EPOCH_HASHES_REPLY.toByte());

    // Register status exchange messages (BUG-SYNC-001 fix)
    this.messageTypes.add(XdagMessageCode.GET_STATUS.toByte());
    this.messageTypes.add(XdagMessageCode.STATUS_REPLY.toByte());
  }

  @Override
  public void onNewBlock(Block block) {
    // Real-time broadcasting of new blocks (Inv)
    // This is triggered when a block is mined or received and validated
    
    try {
      P2pService p2pService = dagKernel.getP2pService();
      if (p2pService == null) {
        return;
      }

      // Create Inv message with default TTL=3
      // Using hash + epoch is enough for peers to check if they need it
      NewBlockHashMessage msg =
          new NewBlockHashMessage(block.getHash(), block.getEpoch(), 3);

      // Broadcast to all connected peers
      // Use a custom broadcast method that doesn't exclude anyone (since this is a new event)
      List<Channel> channels = getActiveChannels(p2pService);
      log.info("📤 Broadcasting NEW_BLOCK_HASH {} to {} peers",
          block.getHash().toHexString().substring(0, 16), channels.size());
      for (Channel channel : channels) {
        try {
          log.info("  → Sending NEW_BLOCK_HASH to {}", channel.getRemoteAddress());
          channel.send(msg);
        } catch (Exception e) {
          log.warn("Failed to send NewBlockHash to {}: {}", channel.getRemoteAddress(), e.getMessage());
        }
      }

      log.debug("Broadcasted NewBlockHash {} to {} peers",
          block.getHash().toHexString().substring(0, 16), channels.size());
          
    } catch (Exception e) {
      log.error("Error broadcasting new block hash: {}", e.getMessage(), e);
    }
  }

  @Override
  public void onConnect(Channel channel) {
    log.info("Peer connected: {} (Node ID: {})",
        channel.getRemoteAddress(), channel.getNodeId());

    // BUG-SYNC-001 fix: Request peer's chain status on connection
    // This allows us to know the remote tip epoch for sync completion detection
    try {
      channel.send(new GetStatusMessage());
      log.debug("Sent GET_STATUS to {}", channel.getRemoteAddress());
    } catch (Exception e) {
      log.warn("Failed to send GET_STATUS to {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  @Override
  public void onDisconnect(Channel channel) {
    log.info("Peer disconnected: {} (Node ID: {})",
        channel.getRemoteAddress(), channel.getNodeId());
  }

  @Override
  public void onMessage(Channel channel, Bytes data) {
    // Temporary: INFO level for P2P message debugging
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
        case NEW_TRANSACTION:
          handleNewTransaction(channel, body);
          break;
        // FastDAG Sync Handlers
        case NEW_BLOCK_HASH:
          handleNewBlockHash(channel, body);
          break;
        case GET_BLOCKS:
          handleGetBlocks(channel, body);
          break;
        case BLOCKS_REPLY:
          handleBlocksReply(channel, body);
          break;
        case GET_EPOCH_HASHES:
          handleGetEpochHashes(channel, body);
          break;
        case EPOCH_HASHES_REPLY:
          handleEpochHashesReply(channel, body);
          break;
        // Status exchange handlers (BUG-SYNC-001 fix)
        case GET_STATUS:
          handleGetStatus(channel);
          break;
        case STATUS_REPLY:
          handleStatusReply(channel, body);
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
   * Handle NEW_BLOCK_HASH (Inv) - Received notification of a new block
   *
   * <p>BUG-SYNC-004 fix: During initial sync (when node is not synchronized),
   * ignore NEW_BLOCK_HASH messages to prevent height inconsistency. These blocks
   * will be fetched through ordered epoch sync instead.
   */
  private void handleNewBlockHash(Channel channel, Bytes body) {
    try {
      NewBlockHashMessage msg = new NewBlockHashMessage(body.toArray());
      Bytes32 hash = msg.getHash();

      // BUG-SYNC-004: Check if node is synchronized before processing real-time broadcasts
      // During initial sync, blocks should come through ordered epoch sync to ensure
      // correct height assignment. Ignore NEW_BLOCK_HASH to prevent height inconsistency.
      SyncManager syncManager = dagKernel.getSyncManager();
      if (syncManager != null && !syncManager.isSynchronized()) {
        log.debug("🔄 BUG-SYNC-004: Ignoring NEW_BLOCK_HASH {} during initial sync (will fetch via epoch sync)",
            hash.toHexString().substring(0, 16));
        return;
      }

      // 1. Check if we already have this block
      if (dagChain.getBlockByHash(hash, false) != null) { // Use getBlockByHash(..., false) instead of hasBlock
        log.trace("Ignoring NEW_BLOCK_HASH {} from {}: already have",
            hash.toHexString().substring(0, 16), channel.getRemoteAddress());
        return;
      }

      // 2. Request the block data
      log.debug("Received NEW_BLOCK_HASH {} from {}, requesting data...",
          hash.toHexString().substring(0, 16), channel.getRemoteAddress());

      GetBlocksMessage request = new GetBlocksMessage(hash);
      channel.send(request);

      // 3. Forward Inv to other peers (Gossip) if TTL allows
      // We assume we will eventually get the block, so we help propagate the hash early
      if (msg.shouldForward()) {
        NewBlockHashMessage forwardMsg = msg.decrementTTL();
        broadcastMessage(forwardMsg, channel);
      }

    } catch (Exception e) {
      log.error("Error handling NEW_BLOCK_HASH from {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  /**
   * Handle GET_BLOCKS - Peer requesting block data
   */
  private void handleGetBlocks(Channel channel, Bytes body) {
    try {
      GetBlocksMessage request = new GetBlocksMessage(body.toArray());
      log.info("📥 Handling GET_BLOCKS from {}: requesting {} block(s)",
          channel.getRemoteAddress(), request.getHashes().size());

      List<Block> foundBlocks = new ArrayList<>();

      for (Bytes32 hash : request.getHashes()) {
        // BUG-SYNC-002 debug: Log each hash being looked up
        log.info("  → Looking up hash: {}", hash.toHexString().substring(0, 18) + "...");

        // Use isRaw=true to get full block data
        Block block = dagChain.getBlockByHash(hash, true);
        if (block != null) {
          foundBlocks.add(block);
          log.info("  ✓ Found block: hash={}, epoch={}",
              hash.toHexString().substring(0, 18) + "...", block.getEpoch());
        } else {
          log.warn("  ✗ Block NOT FOUND: hash={}", hash.toHexString().substring(0, 18) + "...");
        }
      }

      if (!foundBlocks.isEmpty()) {
        log.info("📤 Sending BLOCKS_REPLY to {} with {} blocks",
            channel.getRemoteAddress(), foundBlocks.size());

        BlocksReplyMessage reply = new BlocksReplyMessage(foundBlocks);
        channel.send(reply);
      } else {
        log.info("📤 GET_BLOCKS from {} yielded 0 blocks (not sending reply)",
            channel.getRemoteAddress());
      }

    } catch (Exception e) {
      log.error("Error handling GET_BLOCKS from {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  /**
   * Handle BLOCKS_REPLY - Received block data
   *
   * <p>P2P blocks are submitted to epoch consensus if they belong to the current epoch,
   * allowing them to compete with locally mined blocks for best solution selection.
   *
   * <p>Historical or delayed blocks are imported directly without epoch competition.
   *
   * <p>BUG-SYNC-004: Reverse dependency filling is disabled to prevent memory explosion
   * on long-running nodes. Missing dependencies are resolved through ordered forward sync.
   */
  private void handleBlocksReply(Channel channel, Bytes body) {
    try {
      BlocksReplyMessage reply = new BlocksReplyMessage(body.toArray());
      List<Block> blocks = reply.getBlocks();

      log.info("📦 Received BLOCKS_REPLY from {} with {} blocks",
          channel.getRemoteAddress(), blocks.size());

      for (Block block : blocks) {
        long blockEpoch = block.getEpoch();

        // Check if we have epoch consensus manager
        EpochConsensusManager epochConsensusManager = dagKernel.getEpochConsensusManager();
        if (epochConsensusManager != null && epochConsensusManager.isRunning()) {
          long currentEpoch = epochConsensusManager.getCurrentEpoch();

          log.info("🔍 Block epoch={}, current epoch={}, match={}",
                   blockEpoch, currentEpoch, (blockEpoch == currentEpoch));

          // Only submit current epoch blocks to solution pool
          if (blockEpoch == currentEpoch) {
            // Real-time block: participate in current epoch competition
            String peerId = "P2P_" + channel.getRemoteAddress();
            SubmitResult result = epochConsensusManager.submitSolution(block, peerId);

            if (result.isAccepted()) {
              log.info("✅ P2P block from {} submitted to epoch {} solution pool",
                       peerId, blockEpoch);
            } else {
              // Submission failed (e.g., epoch ended), fallback to direct import
              log.info("❌ submitSolution rejected: {}, falling back to direct import",
                       result.getMessage());
              dagChain.tryToConnect(block);
            }
          } else {
            // Historical or delayed block: direct import
            log.info("📥 Block epoch {} != current epoch {}, importing directly",
                     blockEpoch, currentEpoch);
            io.xdag.core.DagImportResult importResult = dagChain.tryToConnect(block);
            log.info("📥 Import result for {}: {}",
                     block.getHash().toHexString().substring(0, 18),
                     importResult.toDetailedString());
          }
        } else {
          // No epoch consensus manager: direct import (legacy behavior)
          log.info("⚠ No EpochConsensusManager (running={}), direct import",
                   epochConsensusManager != null && epochConsensusManager.isRunning());
          dagChain.tryToConnect(block);
        }
      }

    } catch (Exception e) {
      log.error("Error handling BLOCKS_REPLY from {}: {}", channel.getRemoteAddress(), e.getMessage(), e);
    }
  }

  /**
   * Handle GET_EPOCH_HASHES - Peer requesting block hashes in epoch range
   */
  private void handleGetEpochHashes(Channel channel, Bytes body) {
    try {
      GetEpochHashesMessage request = new GetEpochHashesMessage(body.toArray());
      long start = request.getStartEpoch();
      long end = request.getEndEpoch();

      // Limit range to prevent DOS (max 1000 epochs per request)
      if (end - start > 1000) {
        end = start + 1000;
      }

      log.info("📥 Handling GET_EPOCH_HASHES from {}: range [{}, {}]",
          channel.getRemoteAddress(), start, end);

      Map<Long, List<Bytes32>> epochMap = new LinkedHashMap<>();
      int totalHashes = 0;

      for (long epoch = start; epoch <= end; epoch++) {
        List<Bytes32> hashes = dagChain.getBlockHashesByEpoch(epoch);
        if (!hashes.isEmpty()) {
          epochMap.put(epoch, hashes);
          totalHashes += hashes.size();
        }
      }

      log.info("📤 Sending EPOCH_HASHES_REPLY to {} with {} epochs ({} hashes)",
          channel.getRemoteAddress(), epochMap.size(), totalHashes);

      channel.send(new EpochHashesReplyMessage(epochMap));

    } catch (Exception e) {
      log.error("Error handling GET_EPOCH_HASHES from {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  /**
   * Handle EPOCH_HASHES_REPLY - Received hashes, need to fetch missing blocks
   */
  private void handleEpochHashesReply(Channel channel, Bytes body) {
    try {
      EpochHashesReplyMessage reply = new EpochHashesReplyMessage(body.toArray());
      Map<Long, List<Bytes32>> data = reply.getEpochHashes();

      log.info("📦 Received EPOCH_HASHES_REPLY from {}: {} epochs",
          channel.getRemoteAddress(), data.size());

      // BUG-SYNC-004: Check if SyncManager is in binary search mode
      SyncManager syncManager = dagKernel.getSyncManager();
      if (syncManager != null && syncManager.isInBinarySearch()) {
        // Binary search mode: report results to SyncManager
        boolean hasBlocks = !data.isEmpty();
        long minEpoch = -1;
        if (hasBlocks) {
          // Find minimum epoch in response
          minEpoch = data.keySet().stream().min(Long::compareTo).orElse(-1L);
        }
        log.info("🔍 BUG-SYNC-004: Binary search response - hasBlocks={}, minEpoch={}",
            hasBlocks, minEpoch);
        syncManager.onBinarySearchResponse(hasBlocks, minEpoch);
        return; // Don't fetch blocks during binary search, just probe
      }

      // Normal sync mode: fetch missing blocks
      List<Bytes32> missingHashes = new ArrayList<>();

      for (List<Bytes32> hashes : data.values()) {
        for (Bytes32 hash : hashes) {
          if (dagChain.getBlockByHash(hash, false) == null) { // Use getBlockByHash(..., false)
            missingHashes.add(hash);
          }
        }
      }

      if (!missingHashes.isEmpty()) {
        log.info("📤 Found {} missing blocks from epoch sync, sending GET_BLOCKS request(s)...",
            missingHashes.size());

        // BUG-SYNC-002 debug: Log first few missing hashes
        int logLimit = Math.min(5, missingHashes.size());
        for (int i = 0; i < logLimit; i++) {
          log.info("  → Missing hash {}: {}", i, missingHashes.get(i).toHexString().substring(0, 18) + "...");
        }

        // Batch requests (max 500 per message)
        int batchSize = 500;
        for (int i = 0; i < missingHashes.size(); i += batchSize) {
            int end = Math.min(i + batchSize, missingHashes.size());
            List<Bytes32> batch = missingHashes.subList(i, end);
            log.info("  → Sending GET_BLOCKS batch {} hashes to {}",
                batch.size(), channel.getRemoteAddress());
            channel.send(new GetBlocksMessage(batch));
        }
      } else {
        log.info("📦 Epoch sync complete: no missing blocks in this range");
      }

    } catch (Exception e) {
      log.error("Error handling EPOCH_HASHES_REPLY from {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  // ==================== Status Exchange Handlers (BUG-SYNC-001 fix) ====================

  /**
   * Handle GET_STATUS - peer requesting our chain status.
   * Reply with local tip epoch, height, and cumulative difficulty.
   */
  private void handleGetStatus(Channel channel) {
    try {
      SyncManager syncManager = dagKernel.getSyncManager();
      long tipEpoch = syncManager != null ? syncManager.getLocalTipEpoch() : 0;
      long height = dagChain.getMainChainLength();

      // Get cumulative difficulty from tip block (optional, primarily tipEpoch is needed)
      java.math.BigInteger difficulty = java.math.BigInteger.ZERO;
      if (height > 0) {
        Block tipBlock = dagChain.getMainBlockByHeight(height);
        if (tipBlock != null) {
          org.apache.tuweni.units.bigints.UInt256 cumDiff = dagChain.calculateCumulativeDifficulty(tipBlock);
          difficulty = cumDiff.toBigInteger();
        }
      }

      StatusReplyMessage reply = new StatusReplyMessage(tipEpoch, height, difficulty);
      channel.send(reply);

      log.debug("Sent STATUS_REPLY to {}: tipEpoch={}, height={}, difficulty={}",
          channel.getRemoteAddress(), tipEpoch, height, difficulty);

    } catch (Exception e) {
      log.error("Error handling GET_STATUS from {}: {}", channel.getRemoteAddress(), e.getMessage());
    }
  }

  /**
   * Handle STATUS_REPLY - received peer's chain status.
   * Update SyncManager with the remote tip epoch AND height for sync completion detection.
   * BUG-SYNC-001 fix (enhanced): Track both epoch and height to prevent mining
   * before all historical blocks are synced.
   */
  private void handleStatusReply(Channel channel, Bytes body) {
    try {
      StatusReplyMessage reply = new StatusReplyMessage(body.toArray());

      log.info("Received STATUS_REPLY from {}: tipEpoch={}, height={}, difficulty={}",
          channel.getRemoteAddress(),
          reply.getTipEpoch(),
          reply.getMainChainHeight(),
          reply.getDifficulty());

      // Update SyncManager with peer's tip epoch AND height
      SyncManager syncManager = dagKernel.getSyncManager();
      if (syncManager != null) {
        syncManager.updateRemoteTipEpoch(reply.getTipEpoch());
        syncManager.updateRemoteTipHeight(reply.getMainChainHeight());
      }

    } catch (Exception e) {
      log.error("Error handling STATUS_REPLY from {}: {}", channel.getRemoteAddress(), e.getMessage());
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

  private void broadcastMessage(Message msg, Channel excludeChannel) {
    try {
      io.xdag.p2p.P2pService p2pService = dagKernel.getP2pService();
      if (p2pService == null) return;

      List<Channel> channels = getActiveChannels(p2pService);
      log.info("📤 Broadcasting message type 0x{} to {} peer(s) (excluding: {})",
          String.format("%02X", msg.getCode().toByte()),
          excludeChannel != null ? channels.size() - 1 : channels.size(),
          excludeChannel != null ? excludeChannel.getRemoteAddress() : "none");
      for (Channel channel : channels) {
        if (channel != excludeChannel) {
          log.info("  → Sending to {}", channel.getRemoteAddress());
          channel.send(msg);
        }
      }
    } catch (Exception e) {
      log.warn("Error broadcasting message: {}", e.getMessage());
    }
  }

  /**
   * Get list of unique connected channels from P2P service, deduplicated by Node ID.
   * This ensures we only send one message per unique peer, not per connection.
   *
   * @param p2pService P2P service instance
   * @return list of unique connected channels
   */
  private List<Channel> getActiveChannels(P2pService p2pService) {
    if (p2pService == null) {
      return new ArrayList<>();
    }

    try {
      ChannelManager channelManager = p2pService.getChannelManager();
      if (channelManager == null) {
        return new ArrayList<>();
      }

      // Use getUniqueConnectedChannels() to get deduplicated channels by NodeId
      // This prevents sending duplicate messages to the same peer via different ephemeral ports
      return channelManager.getUniqueConnectedChannels();

    } catch (Exception e) {
      log.error("Error getting active channels", e);
      return new ArrayList<>();
    }
  }

}
