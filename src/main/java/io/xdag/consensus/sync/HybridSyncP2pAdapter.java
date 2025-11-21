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

package io.xdag.consensus.sync;

import io.xdag.p2p.P2pService;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
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
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * P2P Adapter for Hybrid Sync Protocol
 *
 * <p><strong>Purpose</strong>:
 * Bridges HybridSyncManager with the xdagj-p2p layer, providing:
 * <ul>
 *   <li>Request-response pattern for sync messages</li>
 *   <li>Timeout handling</li>
 *   <li>Error recovery</li>
 *   <li>Concurrent request tracking</li>
 * </ul>
 *
 * <p><strong>Architecture</strong>:
 * <pre>
 * HybridSyncManager
 *        ↓
 * HybridSyncP2pAdapter ← (You are here)
 *        ↓
 * XdagP2pEventHandler
 *        ↓
 * P2P Channel
 * </pre>
 *
 * <p><strong>Request-Response Flow</strong>:
 * <pre>
 * 1. HybridSyncManager calls requestXXX()
 * 2. Adapter creates CompletableFuture and stores in pendingRequests
 * 3. Adapter sends request via P2P channel
 * 4. XdagP2pEventHandler receives response
 * 5. XdagP2pEventHandler calls onXXXReply()
 * 6. Adapter completes the Future
 * 7. HybridSyncManager receives result
 * </pre>
 *
 * @since XDAGJ
 */
@Slf4j
public class HybridSyncP2pAdapter {

  // ========== Configuration ==========

  /**
   * Default timeout for sync requests (30 seconds)
   */
  private static final long DEFAULT_TIMEOUT_MS = 30000;

  /**
   * Maximum number of pending requests
   */
  private static final int MAX_PENDING_REQUESTS = 100;

  // ========== Dependencies ==========

  /**
   * P2P service for channel management (5)
   */
  private P2pService p2pService;

  // ========== State ==========

  /**
   * Pending height query requests Key: request ID, Value: Future to complete when reply arrives
   */
  private final Map<String, CompletableFuture<SyncHeightReplyMessage>> pendingHeightRequests =
      new ConcurrentHashMap<>();

  /**
   * Pending main blocks requests Key: request ID, Value: Future to complete when reply arrives
   */
  private final Map<String, CompletableFuture<SyncMainBlocksReplyMessage>> pendingMainBlocksRequests =
      new ConcurrentHashMap<>();

  /**
   * Pending epoch blocks requests Key: request ID, Value: Future to complete when reply arrives
   */
  private final Map<String, CompletableFuture<SyncEpochBlocksReplyMessage>> pendingEpochBlocksRequests =
      new ConcurrentHashMap<>();

  /**
   * Pending blocks requests Key: request ID, Value: Future to complete when reply arrives
   */
  private final Map<String, CompletableFuture<SyncBlocksReplyMessage>> pendingBlocksRequests =
      new ConcurrentHashMap<>();

  /**
   * Pending transactions requests Key: request ID, Value: Future to complete when reply arrives
   */
  private final Map<String, CompletableFuture<SyncTransactionsReplyMessage>> pendingTransactionsRequests =
      new ConcurrentHashMap<>();

  /**
   * Executor for timeout handling
   */
  private final ScheduledExecutorService timeoutExecutor =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HybridSync-Timeout");
        t.setDaemon(true);
        return t;
      });

  // ========== Request Methods (called by HybridSyncManager) ==========

  /**
   * Request remote peer's height information
   *
   * @param channel P2P channel
   * @return CompletableFuture with height reply, or null on timeout/error
   */
  public CompletableFuture<SyncHeightReplyMessage> requestHeight(
      Channel channel) {

    String requestId = UUID.randomUUID().toString();
    CompletableFuture<SyncHeightReplyMessage> future = new CompletableFuture<>();

    // Check capacity
    if (pendingHeightRequests.size() >= MAX_PENDING_REQUESTS) {
      log.warn("Too many pending height requests, rejecting new request");
      future.completeExceptionally(new IllegalStateException("Too many pending requests"));
      return future;
    }

    // Store pending request
    pendingHeightRequests.put(requestId, future);

    try {
      // Create and send request
      SyncHeightRequestMessage request = new SyncHeightRequestMessage();

      log.debug("Sending SyncHeightRequest to {} (requestId={})",
          channel.getRemoteAddress(), requestId);

      // Send Message object directly - Channel will handle encoding
      channel.send(request);

      // Setup timeout
      scheduleTimeout(requestId, future, DEFAULT_TIMEOUT_MS,
          () -> pendingHeightRequests.remove(requestId));

    } catch (Exception e) {
      log.error("Failed to send SyncHeightRequest", e);
      pendingHeightRequests.remove(requestId);
      future.completeExceptionally(e);
    }

    return future;
  }

  /**
   * Request main chain blocks by height range
   *
   * @param channel    P2P channel
   * @param fromHeight start height
   * @param toHeight   end height
   * @param maxBlocks  maximum blocks to return
   * @param isRaw      whether to return full block data
   * @return CompletableFuture with blocks reply
   */
  public CompletableFuture<SyncMainBlocksReplyMessage> requestMainBlocks(
      Channel channel,
      long fromHeight,
      long toHeight,
      int maxBlocks,
      boolean isRaw) {

    String requestId = UUID.randomUUID().toString();
    CompletableFuture<SyncMainBlocksReplyMessage> future = new CompletableFuture<>();

    if (pendingMainBlocksRequests.size() >= MAX_PENDING_REQUESTS) {
      log.warn("Too many pending main blocks requests");
      future.completeExceptionally(new IllegalStateException("Too many pending requests"));
      return future;
    }

    pendingMainBlocksRequests.put(requestId, future);

    try {
      SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(
          fromHeight, toHeight, maxBlocks, isRaw);

      log.debug("Sending SyncMainBlocksRequest [{}, {}] to {} (requestId={})",
          fromHeight, toHeight, channel.getRemoteAddress(), requestId);

      // Send Message object directly - Channel will handle encoding
      channel.send(request);

      scheduleTimeout(requestId, future, DEFAULT_TIMEOUT_MS,
          () -> pendingMainBlocksRequests.remove(requestId));

    } catch (Exception e) {
      log.error("Failed to send SyncMainBlocksRequest", e);
      pendingMainBlocksRequests.remove(requestId);
      future.completeExceptionally(e);
    }

    return future;
  }

  /**
   * Request all block hashes in an epoch range
   *
   * @param channel    P2P channel
   * @param startEpoch start epoch number (inclusive)
   * @param endEpoch   end epoch number (inclusive)
   * @return CompletableFuture with epoch blocks reply
   */
  public CompletableFuture<SyncEpochBlocksReplyMessage> requestEpochBlocks(
      Channel channel,
      long startEpoch,
      long endEpoch) {

    String requestId = UUID.randomUUID().toString();
    CompletableFuture<SyncEpochBlocksReplyMessage> future = new CompletableFuture<>();

    if (pendingEpochBlocksRequests.size() >= MAX_PENDING_REQUESTS) {
      log.warn("Too many pending epoch blocks requests");
      future.completeExceptionally(new IllegalStateException("Too many pending requests"));
      return future;
    }

    pendingEpochBlocksRequests.put(requestId, future);

    try {
      SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(startEpoch,
          endEpoch);

      log.debug("Sending SyncEpochBlocksRequest [{}, {}] (range={}) to {} (requestId={})",
          startEpoch, endEpoch, endEpoch - startEpoch + 1,
          channel.getRemoteAddress(), requestId);

      // Send Message object directly - Channel will handle encoding
      channel.send(request);

      scheduleTimeout(requestId, future, DEFAULT_TIMEOUT_MS,
          () -> pendingEpochBlocksRequests.remove(requestId));

    } catch (Exception e) {
      log.error("Failed to send SyncEpochBlocksRequest", e);
      pendingEpochBlocksRequests.remove(requestId);
      future.completeExceptionally(e);
    }

    return future;
  }

  /**
   * Request blocks by their hashes
   *
   * @param channel P2P channel
   * @param hashes  list of block hashes
   * @param isRaw   whether to return full block data
   * @return CompletableFuture with blocks reply
   */
  public CompletableFuture<SyncBlocksReplyMessage> requestBlocks(
      Channel channel,
      List<Bytes32> hashes,
      boolean isRaw) {

    String requestId = UUID.randomUUID().toString();
    CompletableFuture<SyncBlocksReplyMessage> future = new CompletableFuture<>();

    if (pendingBlocksRequests.size() >= MAX_PENDING_REQUESTS) {
      log.warn("Too many pending blocks requests");
      future.completeExceptionally(new IllegalStateException("Too many pending requests"));
      return future;
    }

    pendingBlocksRequests.put(requestId, future);

    try {
      SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(hashes, isRaw);

      log.debug("Sending SyncBlocksRequest count={} to {} (requestId={})",
          hashes.size(), channel.getRemoteAddress(), requestId);

      // Send Message object directly - Channel will handle encoding
      channel.send(request);

      scheduleTimeout(requestId, future, DEFAULT_TIMEOUT_MS,
          () -> pendingBlocksRequests.remove(requestId));

    } catch (Exception e) {
      log.error("Failed to send SyncBlocksRequest", e);
      pendingBlocksRequests.remove(requestId);
      future.completeExceptionally(e);
    }

    return future;
  }

  /**
   * Request transactions by their hashes
   *
   * @param channel P2P channel
   * @param hashes  list of transaction hashes
   * @return CompletableFuture with transactions reply
   */
  public CompletableFuture<SyncTransactionsReplyMessage> requestTransactions(
      Channel channel,
      List<Bytes32> hashes) {

    String requestId = UUID.randomUUID().toString();
    CompletableFuture<SyncTransactionsReplyMessage> future = new CompletableFuture<>();

    if (pendingTransactionsRequests.size() >= MAX_PENDING_REQUESTS) {
      log.warn("Too many pending transactions requests");
      future.completeExceptionally(new IllegalStateException("Too many pending requests"));
      return future;
    }

    pendingTransactionsRequests.put(requestId, future);

    try {
      SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(hashes);

      log.debug("Sending SyncTransactionsRequest count={} to {} (requestId={})",
          hashes.size(), channel.getRemoteAddress(), requestId);

      // Send Message object directly - Channel will handle encoding
      channel.send(request);

      scheduleTimeout(requestId, future, DEFAULT_TIMEOUT_MS,
          () -> pendingTransactionsRequests.remove(requestId));

    } catch (Exception e) {
      log.error("Failed to send SyncTransactionsRequest", e);
      pendingTransactionsRequests.remove(requestId);
      future.completeExceptionally(e);
    }

    return future;
  }

  // ========== Response Methods (called by XdagP2pEventHandler) ==========

  /**
   * Handle SyncHeightReply message Called by XdagP2pEventHandler when reply is received
   *
   * @param reply height reply message
   */
  public void onHeightReply(SyncHeightReplyMessage reply) {
    // For now, complete the first pending request
    // TODO: Implement proper request tracking by channel/peer
    if (!pendingHeightRequests.isEmpty()) {
      String requestId = pendingHeightRequests.keySet().iterator().next();
      CompletableFuture<SyncHeightReplyMessage> future = pendingHeightRequests.remove(requestId);

      if (future != null) {
        log.debug("Completing height request {} with reply: mainHeight={}",
            requestId, reply.getMainHeight());
        future.complete(reply);
      }
    }
  }

  /**
   * Handle SyncMainBlocksReply message
   *
   * @param reply main blocks reply message
   */
  public void onMainBlocksReply(SyncMainBlocksReplyMessage reply) {
    if (!pendingMainBlocksRequests.isEmpty()) {
      String requestId = pendingMainBlocksRequests.keySet().iterator().next();
      CompletableFuture<SyncMainBlocksReplyMessage> future =
          pendingMainBlocksRequests.remove(requestId);

      if (future != null) {
        log.debug("Completing main blocks request {} with {} blocks",
            requestId, reply.getBlocks().size());
        future.complete(reply);
      }
    }
  }

  /**
   * Handle SyncEpochBlocksReply message
   *
   * @param reply epoch blocks reply message
   */
  public void onEpochBlocksReply(SyncEpochBlocksReplyMessage reply) {
    if (!pendingEpochBlocksRequests.isEmpty()) {
      String requestId = pendingEpochBlocksRequests.keySet().iterator().next();
      CompletableFuture<SyncEpochBlocksReplyMessage> future =
          pendingEpochBlocksRequests.remove(requestId);

      if (future != null) {
        int totalBlocks = reply.getEpochBlocksMap().values().stream()
            .mapToInt(List::size)
            .sum();
        log.debug("Completing epoch blocks request {} with {} epochs, {} total blocks",
            requestId, reply.getEpochBlocksMap().size(), totalBlocks);
        future.complete(reply);
      }
    }
  }

  /**
   * Handle SyncBlocksReply message
   *
   * @param reply blocks reply message
   */
  public void onBlocksReply(SyncBlocksReplyMessage reply) {
    if (!pendingBlocksRequests.isEmpty()) {
      String requestId = pendingBlocksRequests.keySet().iterator().next();
      CompletableFuture<SyncBlocksReplyMessage> future =
          pendingBlocksRequests.remove(requestId);

      if (future != null) {
        log.debug("Completing blocks request {} with {} blocks",
            requestId, reply.getBlocks().size());
        future.complete(reply);
      }
    }
  }

  /**
   * Handle SyncTransactionsReply message
   *
   * @param reply transactions reply message
   */
  public void onTransactionsReply(SyncTransactionsReplyMessage reply) {
    if (!pendingTransactionsRequests.isEmpty()) {
      String requestId = pendingTransactionsRequests.keySet().iterator().next();
      CompletableFuture<SyncTransactionsReplyMessage> future =
          pendingTransactionsRequests.remove(requestId);

      if (future != null) {
        log.debug("Completing transactions request {} with {} transactions",
            requestId, reply.getTransactions().size());
        future.complete(reply);
      }
    }
  }

  // ========== Timeout Handling ==========

  /**
   * Schedule timeout for a request
   *
   * @param requestId request ID
   * @param future    future to complete exceptionally on timeout
   * @param timeoutMs timeout in milliseconds
   * @param cleanup   cleanup action to remove from pending map
   */
  private <T> void scheduleTimeout(
      String requestId,
      CompletableFuture<T> future,
      long timeoutMs,
      Runnable cleanup) {

    timeoutExecutor.schedule(() -> {
      if (!future.isDone()) {
        log.warn("Request {} timed out after {}ms", requestId, timeoutMs);
        cleanup.run();
        future.completeExceptionally(
            new TimeoutException("Request timed out after " + timeoutMs + "ms"));
      }
    }, timeoutMs, TimeUnit.MILLISECONDS);
  }

  // ========== P2P Service Management (5) ==========

  /**
   * Set P2P service for channel management
   *
   * <p>This method should be called after P2P service is started to enable
   * the adapter to query available channels for synchronization.
   *
   * @param p2pService P2P service instance
   */
  public void setP2pService(P2pService p2pService) {
    this.p2pService = p2pService;
    log.info("P2P service connected to HybridSyncP2pAdapter");
  }

  /**
   * Get list of available channels for synchronization
   *
   * <p>Returns all active P2P channels from ChannelManager.
   * These channels can be used to request sync data from remote peers.
   *
   * @return list of active channels, or empty list if P2P service not connected
   */
  public List<Channel> getAvailableChannels() {
    if (p2pService == null) {
      log.debug("P2P service not connected, no channels available");
      return java.util.Collections.emptyList();
    }

    try {
      ChannelManager channelManager = p2pService.getChannelManager();
      if (channelManager == null) {
        log.warn("ChannelManager not available");
        return java.util.Collections.emptyList();
      }

      // Get all active channels from ChannelManager
      Map<InetSocketAddress, Channel> channels =
          channelManager.getChannels();

      if (channels == null || channels.isEmpty()) {
        log.debug("No active channels available");
        return java.util.Collections.emptyList();
      }

      // Convert map values to list
      java.util.List<Channel> channelList =
          new java.util.ArrayList<>(channels.values());

      log.debug("Found {} active channels", channelList.size());
      return channelList;

    } catch (Exception e) {
      log.error("Error getting available channels", e);
      return java.util.Collections.emptyList();
    }
  }

  // ========== Lifecycle ==========

  /**
   * Shutdown the adapter and cancel all pending requests
   */
  public void shutdown() {
    log.info("Shutting down HybridSyncP2pAdapter...");

    // Cancel all pending requests
    cancelAllPending();

    // Shutdown timeout executor
    timeoutExecutor.shutdown();
    try {
      if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        timeoutExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      timeoutExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }

    log.info("HybridSyncP2pAdapter shutdown complete");
  }

  /**
   * Cancel all pending requests
   */
  private void cancelAllPending() {
    int totalCancelled = 0;

    for (CompletableFuture<?> future : pendingHeightRequests.values()) {
      future.cancel(true);
      totalCancelled++;
    }
    pendingHeightRequests.clear();

    for (CompletableFuture<?> future : pendingMainBlocksRequests.values()) {
      future.cancel(true);
      totalCancelled++;
    }
    pendingMainBlocksRequests.clear();

    for (CompletableFuture<?> future : pendingEpochBlocksRequests.values()) {
      future.cancel(true);
      totalCancelled++;
    }
    pendingEpochBlocksRequests.clear();

    for (CompletableFuture<?> future : pendingBlocksRequests.values()) {
      future.cancel(true);
      totalCancelled++;
    }
    pendingBlocksRequests.clear();

    for (CompletableFuture<?> future : pendingTransactionsRequests.values()) {
      future.cancel(true);
      totalCancelled++;
    }
    pendingTransactionsRequests.clear();

    if (totalCancelled > 0) {
      log.info("Cancelled {} pending requests", totalCancelled);
    }
  }

  /**
   * Get statistics about pending requests
   *
   * @return statistics string
   */
  public String getStats() {
    return String.format(
        "Pending requests: height=%d, mainBlocks=%d, epochBlocks=%d, blocks=%d, transactions=%d",
        pendingHeightRequests.size(),
        pendingMainBlocksRequests.size(),
        pendingEpochBlocksRequests.size(),
        pendingBlocksRequests.size(),
        pendingTransactionsRequests.size()
    );
  }
}
