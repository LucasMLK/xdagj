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

package io.xdag.consensus.miner;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.p2p.P2pService;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.NewBlockMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockBroadcaster - Pool-specific block broadcasting (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class implements <strong>pool-specific functionality</strong> that is deprecated and will be
 * removed in version <strong>0.9.0</strong>. Block broadcasting logic should be handled by the
 * node's RPC layer when pools submit mined blocks.
 *
 * <h2>Why Deprecated?</h2>
 * <p>Block broadcasting is already handled by the node, pool doesn't need separate broadcaster:
 * <ul>
 *   <li>❌ Duplicates functionality already in DagChain.tryToConnect()</li>
 *   <li>❌ Only used by internal pool (MiningManager)</li>
 *   <li>❌ External pools use RPC interface which handles broadcasting internally</li>
 *   <li>❌ Couples pool-specific logic with node functionality</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p><strong>OLD (Deprecated)</strong>:
 * <pre>
 * // Internal pool broadcasts blocks via BlockBroadcaster
 * BlockBroadcaster broadcaster = new BlockBroadcaster(dagKernel, 8);
 * boolean success = broadcaster.broadcast(minedBlock);
 * </pre>
 *
 * <p><strong>NEW (Recommended)</strong>:
 * <pre>
 * // External pool submits via RPC (node handles broadcasting internally)
 * // In xdagj-pool project:
 * NodeRpcClient nodeClient = new NodeRpcClient("http://localhost:10001");
 * BlockSubmitResult result = nodeClient.submitMinedBlock(minedBlock, "pool-1");
 *
 * // Node's MiningRpcServiceImpl internally does:
 * // 1. Validates block against cached candidate
 * // 2. Calls dagChain.tryToConnect(block) - which broadcasts automatically
 * // 3. Returns result to pool
 * </pre>
 *
 * <h2>Temporary Usage (Testing Only)</h2>
 * <p>For development and testing, you can still use BlockBroadcaster, but be aware:
 * <ul>
 *   <li>⚠️ Not recommended for production</li>
 *   <li>⚠️ Will be removed in v0.9.0</li>
 *   <li>⚠️ No new features will be added</li>
 *   <li>⚠️ Bugs may not be fixed</li>
 * </ul>
 *
 * <h2>What This Class Does</h2>
 * <ul>
 *   <li>Imports mined blocks to local DagChain</li>
 *   <li>Checks if block became main block or orphan</li>
 *   <li>Broadcasts main blocks to P2P network</li>
 *   <li>Tracks broadcast statistics</li>
 * </ul>
 *
 * <h2>Timeline</h2>
 * <ul>
 *   <li><strong>v0.8.2</strong>: Marked as @Deprecated (current)</li>
 *   <li><strong>v0.8.3</strong>: xdagj-pool uses RPC interface instead</li>
 *   <li><strong>v0.9.0</strong>: BlockBroadcaster removed (breaking change)</li>
 * </ul>
 *
 * @since XDAGJ v5.1
 * @deprecated Since v0.8.2, scheduled for removal in v0.9.0.
 *             Pools should use {@link io.xdag.rpc.service.MiningRpcServiceImpl#submitMinedBlock}
 *             which handles import and broadcast internally.
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 * @see io.xdag.core.DagChain#tryToConnect(Block)
 */
@Deprecated(since = "0.8.2", forRemoval = true)
@Slf4j
public class BlockBroadcaster {

    private final DagKernel dagKernel;
    private final DagChain dagChain;
    private final int ttl;  // Time-to-live for broadcast messages

    /**
     * Total blocks broadcast successfully
     */
    private final AtomicLong totalBlocksBroadcast = new AtomicLong(0);

    /**
     * Total blocks failed to import
     */
    private final AtomicLong totalBlocksFailed = new AtomicLong(0);

    /**
     * Create a new BlockBroadcaster
     *
     * @param dagKernel DagKernel for block import
     * @param ttl Time-to-live for P2P broadcast messages
     */
    public BlockBroadcaster(DagKernel dagKernel, int ttl) {
        if (dagKernel == null) {
            throw new IllegalArgumentException("DagKernel cannot be null");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        this.dagKernel = dagKernel;
        this.dagChain = dagKernel.getDagChain();
        this.ttl = ttl;
    }

    /**
     * Broadcast a mined block
     *
     * <p>This method:
     * <ol>
     *   <li>Imports the block to local DagChain</li>
     *   <li>Checks if block became a main block</li>
     *   <li>Broadcasts to P2P network if successful</li>
     * </ol>
     *
     * @param block Block to broadcast
     * @return true if block was imported successfully, false otherwise
     * @throws IllegalArgumentException if block is null
     */
    public boolean broadcast(Block block) {
        if (block == null) {
            throw new IllegalArgumentException("Block cannot be null");
        }

        log.info("Broadcasting mined block: hash={}, timestamp={}",
                block.getHash().toHexString(),
                block.getTimestamp());

        try {
            // Step 1: Import block to local DagChain
            DagImportResult result = dagChain.tryToConnect(block);

            if (result == null) {
                log.error("Block import returned null result: {}",
                        block.getHash().toHexString());
                totalBlocksFailed.incrementAndGet();
                return false;
            }

            // Step 2: Check import status
            DagImportResult.ImportStatus importStatus = result.getStatus();

            if (importStatus == DagImportResult.ImportStatus.SUCCESS) {
                if (result.isMainBlock()) {
                    // Block became a main block!
                    log.info("Mined block {} imported as main block at height {}",
                            block.getHash().toHexString(),
                            result.getHeight());

                    // Step 3: Broadcast to P2P network
                    broadcastToNetwork(block);

                    totalBlocksBroadcast.incrementAndGet();
                    return true;

                } else {
                    // Block imported but as orphan
                    log.info("Mined block {} imported as orphan (not best chain)",
                            block.getHash().toHexString());

                    // Don't broadcast orphan blocks
                    return true;
                }

            } else if (importStatus == DagImportResult.ImportStatus.DUPLICATE) {
                log.warn("Block {} is duplicate, already exists in chain",
                        block.getHash().toHexString());
                return false;

            } else if (importStatus == DagImportResult.ImportStatus.INVALID) {
                log.error("Block {} import failed: {}",
                        block.getHash().toHexString(),
                        result.getErrorMessage() != null ? result.getErrorMessage() : "validation failed");
                totalBlocksFailed.incrementAndGet();
                return false;

            } else if (importStatus == DagImportResult.ImportStatus.MISSING_DEPENDENCY) {
                log.warn("Block {} import failed: missing dependency",
                        block.getHash().toHexString());
                totalBlocksFailed.incrementAndGet();
                return false;

            } else if (importStatus == DagImportResult.ImportStatus.ERROR) {
                log.error("Block {} import error: {}",
                        block.getHash().toHexString(),
                        result.getErrorMessage());
                totalBlocksFailed.incrementAndGet();
                return false;

            } else {
                log.error("Unknown import status: {}", importStatus);
                totalBlocksFailed.incrementAndGet();
                return false;
            }

        } catch (Exception e) {
            log.error("Exception while broadcasting block {}: {}",
                    block.getHash().toHexString(), e.getMessage(), e);
            totalBlocksFailed.incrementAndGet();
            return false;
        }
    }

    /**
     * Broadcast block to P2P network
     *
     * <p> Integrated with P2P layer for actual broadcasting.
     * Sends NEW_BLOCK message to all connected peers with TTL.
     *
     * @param block Block to broadcast
     */
    private void broadcastToNetwork(Block block) {
        // Check if P2P service is available
        P2pService p2pService = dagKernel.getP2pService();
        if (p2pService == null) {
            log.warn("P2P service not available, cannot broadcast block {}",
                    block.getHash().toHexString());
            return;
        }

        try {
            // Create NewBlockMessage
            NewBlockMessage message = new NewBlockMessage(block, ttl);

            // Broadcast to all connected peers
            // 5 FIX: Send Message object directly, not raw bytes
            // Channel.send(Message) will handle proper encoding with message code prefix
            int sentCount = 0;
            for (Channel channel : p2pService.getChannelManager().getChannels().values()) {
                if (channel.isFinishHandshake()) {
                    try {
                        channel.send(message);  // Send Message object, not bytes
                        sentCount++;
                    } catch (Exception e) {
                        log.error("Error broadcasting block to {}: {}",
                                channel.getRemoteAddress(), e.getMessage());
                    }
                }
            }

            log.info("Block {} broadcast to {} peers (ttl={})",
                    block.getHash().toHexString().substring(0, 18) + "...",
                    sentCount, ttl);

        } catch (Exception e) {
            log.error("Error broadcasting block to P2P network: {}", e.getMessage(), e);
        }
    }

    /**
     * Get broadcast statistics
     *
     * @return Human-readable statistics
     */
    public String getStatistics() {
        long broadcast = totalBlocksBroadcast.get();
        long failed = totalBlocksFailed.get();
        long total = broadcast + failed;
        double successRate = total > 0 ? (broadcast * 100.0 / total) : 0.0;

        return String.format(
                "BlockBroadcaster Stats: broadcast=%d, failed=%d, success_rate=%.1f%%",
                broadcast,
                failed,
                successRate
        );
    }

    /**
     * Get total blocks successfully broadcast
     *
     * @return count of successfully broadcast blocks
     */
    public long getTotalBlocksBroadcast() {
        return totalBlocksBroadcast.get();
    }

    /**
     * Get total blocks failed to import
     *
     * @return count of failed blocks
     */
    public long getTotalBlocksFailed() {
        return totalBlocksFailed.get();
    }

    /**
     * Reset statistics counters
     */
    public void resetStatistics() {
        totalBlocksBroadcast.set(0);
        totalBlocksFailed.set(0);
        log.debug("BlockBroadcaster statistics reset");
    }
}
