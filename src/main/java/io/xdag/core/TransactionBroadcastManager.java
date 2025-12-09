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
package io.xdag.core;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.xdag.p2p.P2pService;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.NewTransactionMessage;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * TransactionBroadcastManager - Manages transaction broadcasting with anti-loop protection (Phase
 * 3)
 *
 * <p>This manager prevents transaction broadcast loops in the P2P network by tracking:
 * <ul>
 *   <li><b>Recently Seen Transactions</b>: Transactions received from peers (avoid re-processing)</li>
 *   <li><b>Recently Broadcasted Transactions</b>: Transactions sent to peers (avoid re-broadcasting)</li>
 * </ul>
 *
 * <h2>Problem: Broadcast Loops</h2>
 * <pre>
 * Without protection:
 * Node A --broadcast--> Node B --broadcast--> Node C
 *  ↑                                            |
 *  |                                            |
 *  +----------broadcast<---------- Node D <----+
 *
 * Result: Transaction loops forever in the network!
 * </pre>
 *
 * <h2>Solution: "Recently Seen" Cache</h2>
 * <pre>
 * With protection:
 * 1. Node A receives tx from RPC
 * 2. Node A marks tx as "seen" and broadcasts to B, C, D
 * 3. Node B receives tx, marks as "seen", broadcasts to A, C, D
 * 4. Node A receives tx from B → checks "recently seen" → SKIP! ✓
 * 5. Loop prevented!
 * </pre>
 *
 * <h2>Memory Usage</h2>
 * <ul>
 *   <li>Recently Seen: 100,000 tx × 40 bytes = ~4 MB (1 hour retention)</li>
 *   <li>Recently Broadcasted: 10,000 tx × 40 bytes = ~0.4 MB (10 min retention)</li>
 *   <li>Total: ~4.4 MB</li>
 * </ul>
 *
 * <h2>Design Rationale</h2>
 * <ul>
 *   <li><b>Why LRU Cache?</b> Caffeine provides fast O(1) lookups and automatic expiration</li>
 *   <li><b>Why 1 hour retention?</b> Transaction pool expires after 1 hour, cache should match</li>
 *   <li><b>Why 10 min broadcast retention?</b> Sufficient to prevent short-term re-broadcasts</li>
 *   <li><b>Why separate caches?</b> Different retention policies for seen vs broadcasted</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Node receives transaction from peer
 * public void handleNewTransaction(Channel fromChannel, Transaction tx) {
 *     Bytes32 txHash = tx.getHash();
 *
 *     // Check if we've seen this transaction recently
 *     if (!broadcastManager.shouldProcess(txHash)) {
 *         log.debug("Transaction {} already seen, skipping", txHash);
 *         return;  // Prevents loop! ✓
 *     }
 *
 *     // Add to pool
 *     if (transactionPool.addTransaction(tx)) {
 *         // Broadcast to other peers (exclude sender)
 *         broadcastManager.broadcastTransaction(tx, fromChannel);
 *     }
 * }
 * }</pre>
 *
 * @see NewTransactionMessage for broadcast message format
 * @see TransactionPool for transaction pool integration
 * @since Phase 3 - Network Propagation
 */
@Slf4j
public class TransactionBroadcastManager {

  /**
   * Cache of recently seen transaction hashes
   * <p>
   * Purpose: Prevent re-processing transactions that loop back to us
   * <p>
   * Size: 100,000 transactions (~4 MB)
   * <p>
   * Retention: 1 hour (matches TransactionPool expiration)
   */
  private final Cache<Bytes32, Long> recentlySeenTxs;

  /**
   * Cache of recently broadcasted transaction hashes
   * <p>
   * Purpose: Prevent re-broadcasting the same transaction multiple times
   * <p>
   * Size: 10,000 transactions (~0.4 MB)
   * <p>
   * Retention: 10 minutes (shorter than seen cache)
   */
  private final Cache<Bytes32, Long> recentlyBroadcastedTxs;

  /**
   * P2P service for broadcasting messages to peers
   */
  private P2pService p2pService;

  /**
   * Create a new TransactionBroadcastManager
   */
  public TransactionBroadcastManager() {
    // Recently seen cache: 100,000 transactions, 1 hour expiration
    this.recentlySeenTxs = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build();

    // Recently broadcasted cache: 10,000 transactions, 10 minutes expiration
    this.recentlyBroadcastedTxs = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build();

    log.info("TransactionBroadcastManager initialized");
    log.info("  - Recently seen cache: 100,000 max, 1 hour retention (~4 MB)");
    log.info("  - Recently broadcast cache: 10,000 max, 10 min retention (~0.4 MB)");
  }

  /**
   * Set the P2P service for broadcasting
   *
   * @param p2pService P2P service instance
   */
  public void setP2pService(P2pService p2pService) {
    this.p2pService = p2pService;
    log.info("P2P service connected to TransactionBroadcastManager");
  }

  /**
   * Check if a transaction should be processed
   *
   * <p>This method checks if we've seen this transaction recently.
   * If yes, we skip processing to prevent broadcast loops.
   *
   * <p>This is the <b>first line of defense</b> against loops:
   * <ul>
   *   <li>Fast O(1) cache lookup</li>
   *   <li>Prevents unnecessary deserialization</li>
   *   <li>Saves CPU and memory</li>
   * </ul>
   *
   * <p><b>Thread Safety:</b> Uses putIfAbsent() to guarantee atomicity of check-then-act pattern.
   *
   * @param txHash transaction hash
   * @return true if should process, false if already seen
   */
  public boolean shouldProcess(Bytes32 txHash) {
    // BUGFIX : Use putIfAbsent() for atomic check-then-act
    // Previously: getIfPresent() + put() had race condition between check and insert
    // Now: putIfAbsent() guarantees atomicity - returns null only if newly inserted
    Long existing = recentlySeenTxs.asMap().putIfAbsent(txHash, System.currentTimeMillis());

    if (existing != null) {
      // Transaction already seen (existing value returned)
      log.trace("Transaction {} already seen, skipping",
          txHash.toHexString().substring(0, 16) + "...");
      return false;
    }

    // Transaction is new (null returned, value inserted atomically)
    return true;
  }

  /**
   * Check if a transaction should be broadcasted
   *
   * <p>This method checks if we've already broadcasted this transaction recently.
   * If yes, we skip to prevent spamming the network.
   *
   * <p><b>Thread Safety:</b> Uses putIfAbsent() to guarantee atomicity of check-then-act pattern.
   *
   * @param txHash transaction hash
   * @return true if should broadcast, false if already broadcasted
   */
  public boolean shouldBroadcast(Bytes32 txHash) {
    // BUGFIX : Use putIfAbsent() for atomic check-then-act
    // Previously: getIfPresent() + put() had race condition between check and insert
    // Now: putIfAbsent() guarantees atomicity - returns null only if newly inserted
    Long existing = recentlyBroadcastedTxs.asMap().putIfAbsent(txHash, System.currentTimeMillis());

    if (existing != null) {
      // Transaction already broadcasted (existing value returned)
      log.trace("Transaction {} already broadcasted, skipping",
          txHash.toHexString().substring(0, 16) + "...");
      return false;
    }

    // Transaction is new (null returned, value inserted atomically)
    return true;
  }

  /**
   * Broadcast a transaction to all peers (excluding sender)
   *
   * <p>This is the main broadcast method used by the transaction pool.
   * It implements the <b>sender exclusion</b> logic to prevent immediate loops.
   *
   * <h3>Broadcast Flow</h3>
   * <pre>
   * 1. Check if already broadcasted recently → skip if yes
   * 2. Create NEW_TRANSACTION message (with default TTL=5)
   * 3. Send to all active peers (except sender)
   * 4. Log statistics
   * </pre>
   *
   * @param tx             transaction to broadcast
   * @param excludeChannel channel to exclude (sender), or null to broadcast to all
   */
  public void broadcastTransaction(Transaction tx, Channel excludeChannel) {
    Bytes32 txHash = tx.getHash();

    // Check if we should broadcast (prevents re-broadcast)
    if (!shouldBroadcast(txHash)) {
      return;
    }

    // Create broadcast message with default TTL
    NewTransactionMessage message = new NewTransactionMessage(tx);

    // Broadcast using the message object
    broadcastTransactionMessage(message, excludeChannel);
  }

  /**
   * Broadcast a transaction message to all peers (excluding sender)
   *
   * <p>This method is used for forwarding received transactions with custom TTL.
   * It bypasses the broadcast cache check since the message may have been received from another
   * peer and needs to be forwarded.
   *
   * @param message        transaction message with custom TTL
   * @param excludeChannel channel to exclude (sender), or null to broadcast to all
   */
  public void broadcastTransactionMessage(NewTransactionMessage message, Channel excludeChannel) {
    if (p2pService == null) {
      log.trace("P2P service not available, cannot broadcast transaction");
      return;
    }

    Transaction tx = message.getTransaction();
    Bytes32 txHash = tx.getHash();
    int ttl = message.getTtl();

    // Get all active channels
    List<Channel> channels = getActiveChannels();
    if (channels.isEmpty()) {
      log.trace("No active peers to broadcast transaction {} (TTL={})",
          txHash.toHexString().substring(0, 16) + "...",
          ttl);
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
          log.warn("Failed to broadcast transaction {} to peer {}: {}",
              txHash.toHexString().substring(0, 16) + "...",
              channel.getRemoteAddress(),
              e.getMessage());
        }
      }
    }

    if (broadcastCount > 0) {
      log.debug("Broadcasted transaction {} to {} peers (TTL={})",
          txHash.toHexString().substring(0, 16) + "...",
          broadcastCount,
          ttl);
    } else {
      log.trace("No active peers to broadcast transaction {} (TTL={})",
          txHash.toHexString().substring(0, 16) + "...",
          ttl);
    }
  }

  /**
   * Get list of active channels from P2P service
   *
   * @return list of active channels
   */
  private List<Channel> getActiveChannels() {
    if (p2pService == null) {
      return new ArrayList<>();
    }

    try {
      ChannelManager channelManager = p2pService.getChannelManager();
      if (channelManager == null) {
        return new ArrayList<>();
      }

      Map<InetSocketAddress, Channel> channels = channelManager.getChannels();

      if (channels == null || channels.isEmpty()) {
        return new ArrayList<>();
      }

      return new ArrayList<>(channels.values());

    } catch (Exception e) {
      log.error("Error getting active channels", e);
      return new ArrayList<>();
    }
  }

  /**
   * Get cache statistics
   *
   * @return human-readable statistics string
   */
  public String getStatistics() {
    long seenSize = recentlySeenTxs.estimatedSize();
    long broadcastSize = recentlyBroadcastedTxs.estimatedSize();

    return String.format(
        "TransactionBroadcastManager Stats: seen=%d, broadcasted=%d",
        seenSize, broadcastSize
    );
  }

  /**
   * Clear all caches (for testing)
   */
  public void clear() {
    recentlySeenTxs.invalidateAll();
    recentlyBroadcastedTxs.invalidateAll();
    log.info("TransactionBroadcastManager caches cleared");
  }
}
