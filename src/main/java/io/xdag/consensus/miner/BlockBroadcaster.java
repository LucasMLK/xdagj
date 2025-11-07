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
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BlockBroadcaster - Broadcasts mined blocks to the network
 *
 * <p>This component is responsible for importing successfully mined blocks
 * to the local DAG chain and broadcasting them to the P2P network.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Single Responsibility: Only handles block importing and broadcasting</li>
 *   <li>v5.1 Alignment: Uses DagChain API instead of legacy Blockchain</li>
 *   <li>Separation: Broadcasting is separate from mining and validation</li>
 *   <li>Future-ready: P2P integration points clearly marked</li>
 * </ul>
 *
 * <h2>Block Import Process</h2>
 * <ol>
 *   <li>Import block to local DagChain</li>
 *   <li>Check if block became a main block</li>
 *   <li>If main block: broadcast to P2P network</li>
 *   <li>If orphan: log but don't broadcast</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * BlockBroadcaster broadcaster = new BlockBroadcaster(dagKernel, 8);
 *
 * // Broadcast a mined block
 * boolean success = broadcaster.broadcast(minedBlock);
 *
 * if (success) {
 *     System.out.println("Block imported and broadcast!");
 * }
 * </pre>
 *
 * @since v5.1 Phase 12.4
 */
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
                    log.info("Mined block {} imported as main block at position {}",
                            block.getHash().toHexString(),
                            result.getPosition());

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
     * <p>Phase 12.4: Framework method for P2P broadcasting.
     * Once P2P layer is integrated, this will:
     * <ul>
     *   <li>Serialize block to bytes</li>
     *   <li>Send to all connected peers</li>
     *   <li>Track broadcast success/failure</li>
     * </ul>
     *
     * @param block Block to broadcast
     */
    private void broadcastToNetwork(Block block) {
        // TODO Phase 12.5: Integrate with P2P layer
        // Once P2P layer is ready, this will:
        // 1. Get list of connected peers from DagKernel
        // 2. Serialize block to bytes
        // 3. Send NEW_BLOCK message to all peers with TTL
        // 4. Track broadcast metrics

        log.info("Block {} ready for P2P broadcast (P2P integration pending)",
                block.getHash().toHexString());

        // Example of what will be implemented:
        // List<Channel> peers = dagKernel.getP2pManager().getConnectedPeers();
        // byte[] blockBytes = block.toBytes();
        // for (Channel peer : peers) {
        //     peer.send(new NewBlockMessage(blockBytes, ttl));
        // }
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
