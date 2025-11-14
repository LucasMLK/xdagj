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

import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.core.Block;
import io.xdag.crypto.hash.XdagSha256Digest;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LocalMiner - CPU miner implementation (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class implements a <strong>CPU miner for testing</strong> that is deprecated and will be
 * removed in version <strong>0.9.0</strong>. Mining functionality should be moved to the
 * standalone <strong>xdagj-miner</strong> project.
 *
 * <h2>Why Deprecated?</h2>
 * <p>Miner should be a separate standalone program, not part of the node:
 * <ul>
 *   <li>❌ Mining is compute-intensive and should run separately from node</li>
 *   <li>❌ Users should be able to run multiple miners against one pool</li>
 *   <li>❌ GPU miners, optimized CPU miners should be separate projects</li>
 *   <li>❌ Couples mining logic with blockchain node</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p><strong>OLD (Deprecated)</strong>:
 * <pre>
 * // Internal miner tied to node
 * LocalMiner miner = new LocalMiner(miningManager, powAlgorithm, 4);
 * miner.start();
 * // Miner directly calls MiningManager.receiveShare()
 * </pre>
 *
 * <p><strong>NEW (Recommended)</strong>:
 * <pre>
 * // Separate miner program (xdagj-miner)
 * // Connects to pool via Stratum protocol
 *
 * // In xdagj-miner project:
 * StratumClient client = new StratumClient("pool.example.com", 3333);
 * client.connect();
 *
 * // Miner receives jobs via Stratum
 * MiningJob job = client.getJob();
 *
 * // Mine and submit via Stratum
 * while (mining) {
 *     Bytes32 nonce = tryNextNonce();
 *     Bytes32 hash = calculateHash(job, nonce);
 *     if (hash < target) {
 *         client.submitShare(job.id, nonce);
 *     }
 * }
 * </pre>
 *
 * <h2>Architecture Evolution</h2>
 * <pre>
 * OLD (Deprecated):
 * Node → MiningManager (internal pool) → LocalMiner
 *                                         ↓
 *                                     receiveShare()
 *
 * NEW (Three-layer):
 * Node (xdagj) ← RPC ← Pool (xdagj-pool) ← Stratum ← Miner (xdagj-miner)
 * </pre>
 *
 * <h2>Temporary Usage (Testing Only)</h2>
 * <p>For development and testing, you can still use LocalMiner, but be aware:
 * <ul>
 *   <li>⚠️ CPU mining is VERY SLOW (use for testing only)</li>
 *   <li>⚠️ Not recommended for production mining</li>
 *   <li>⚠️ Will be removed in v0.9.0</li>
 *   <li>⚠️ No GPU acceleration</li>
 *   <li>⚠️ No optimization</li>
 * </ul>
 *
 * <h2>What This Class Does</h2>
 * <ul>
 *   <li>✅ Implements the nonce iteration loop (THIS is where mining happens!)</li>
 *   <li>✅ Multi-threaded CPU mining</li>
 *   <li>✅ Supports RandomX and SHA256 algorithms</li>
 *   <li>✅ Submits shares to internal MiningManager</li>
 *   <li>✅ Good for understanding mining flow</li>
 * </ul>
 *
 * <h2>Timeline</h2>
 * <ul>
 *   <li><strong>v0.8.2</strong>: Marked as @Deprecated (current)</li>
 *   <li><strong>v0.8.4</strong>: xdagj-miner project available with GPU support</li>
 *   <li><strong>v0.9.0</strong>: LocalMiner removed (breaking change)</li>
 * </ul>
 *
 * <h2>For Users Who Need Mining</h2>
 * <p>After v0.9.0, use the three-layer architecture:
 * <ol>
 *   <li>Run xdagj node with RPC enabled</li>
 *   <li>Run xdagj-pool connecting to node via RPC</li>
 *   <li>Run xdagj-miner (or other miners) connecting to pool via Stratum</li>
 * </ol>
 *
 * @since XDAGJ v5.1
 * @deprecated Since v0.8.2, scheduled for removal in v0.9.0.
 *             Use standalone xdagj-miner connecting to xdagj-pool via Stratum protocol.
 * @see io.xdag.consensus.miner.MiningManager
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 */
@Deprecated(since = "0.8.2", forRemoval = true)
@Slf4j
public class LocalMiner {

    // ========== Configuration ==========

    /**
     * Number of nonces to try before checking for task updates
     */
    private static final long NONCE_CHECK_INTERVAL = 10000;

    /**
     * Delay between task polls in milliseconds
     */
    private static final long TASK_POLL_DELAY_MS = 1000;

    // ========== Dependencies ==========

    private final MiningManager miningManager;
    private final PowAlgorithm powAlgorithm;
    private final int threadCount;

    // ========== State ==========

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService minerExecutor;

    /**
     * Current mining task being worked on
     */
    private volatile MiningTask currentTask;

    /**
     * Nonce counter for distributing work across threads
     */
    private final AtomicLong nonceCounter = new AtomicLong(0);

    /**
     * Statistics: total hashes computed
     */
    private final AtomicLong totalHashesComputed = new AtomicLong(0);

    /**
     * Statistics: total shares submitted
     */
    private final AtomicLong totalSharesSubmitted = new AtomicLong(0);

    // ========== Constructor ==========

    /**
     * Create a new LocalMiner
     *
     * @param miningManager MiningManager to receive shares
     * @param powAlgorithm PoW algorithm for hash calculation (can be null for SHA256-only)
     * @param threadCount Number of mining threads (1-16 recommended for CPU)
     */
    public LocalMiner(MiningManager miningManager, PowAlgorithm powAlgorithm, int threadCount) {
        if (miningManager == null) {
            throw new IllegalArgumentException("MiningManager cannot be null");
        }
        if (threadCount <= 0 || threadCount > 64) {
            throw new IllegalArgumentException("Thread count must be between 1 and 64");
        }

        this.miningManager = miningManager;
        this.powAlgorithm = powAlgorithm;
        this.threadCount = threadCount;

        log.info("LocalMiner created with {} threads", threadCount);
    }

    // ========== Lifecycle Management ==========

    /**
     * Start local mining
     *
     * <p>Creates mining threads and begins monitoring for tasks from MiningManager.
     */
    public synchronized void start() {
        if (running.getAndSet(true)) {
            log.warn("LocalMiner already running");
            return;
        }

        log.info("========================================");
        log.info("Starting LocalMiner");
        log.info("========================================");
        log.info("⚠️  WARNING: CPU mining is slow! This is for testing only.");
        log.info("  - Threads: {}", threadCount);
        log.info("  - Algorithm: {}", powAlgorithm != null ? powAlgorithm.getName() : "SHA256");
        log.info("========================================");

        // Create thread pool for mining
        minerExecutor = Executors.newFixedThreadPool(threadCount + 1, r -> {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
        });

        // Start task monitor thread
        minerExecutor.submit(this::taskMonitor);

        // Start mining threads
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            minerExecutor.submit(() -> miningWorker(threadId));
        }

        log.info("✓ LocalMiner started with {} threads", threadCount);
    }

    /**
     * Stop local mining
     *
     * <p>Stops all mining threads and waits for graceful shutdown.
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            log.warn("LocalMiner not running");
            return;
        }

        log.info("========================================");
        log.info("Stopping LocalMiner");
        log.info("========================================");

        if (minerExecutor != null) {
            minerExecutor.shutdown();
            try {
                if (!minerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    minerExecutor.shutdownNow();
                    log.warn("Mining threads forcefully terminated");
                }
            } catch (InterruptedException e) {
                minerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            minerExecutor = null;
        }

        log.info("✓ LocalMiner stopped");
        log.info("  - Total hashes computed: {}", totalHashesComputed.get());
        log.info("  - Total shares submitted: {}", totalSharesSubmitted.get());
        log.info("========================================");
    }

    /**
     * Check if miner is running
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    // ========== Mining Operations ==========

    /**
     * Task monitor - polls MiningManager for new tasks
     */
    private void taskMonitor() {
        log.info("Task monitor started");

        while (running.get()) {
            try {
                // Poll MiningManager for current task
                MiningTask newTask = miningManager.getCurrentTask();

                if (newTask != null && !newTask.equals(currentTask)) {
                    // New task available
                    currentTask = newTask;
                    nonceCounter.set(0);  // Reset nonce counter

                    log.info("New mining task received: #{}, isRandomX={}",
                            newTask.getTaskIndex(), newTask.isRandomX());
                }

                // Sleep before next poll
                Thread.sleep(TASK_POLL_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in task monitor", e);
            }
        }

        log.info("Task monitor stopped");
    }

    /**
     * Mining worker - the actual mining loop
     *
     * <p><strong>THIS IS WHERE THE NONCE ITERATION HAPPENS!</strong>
     *
     * @param threadId Worker thread ID
     */
    private void miningWorker(int threadId) {
        log.info("Mining worker {} started", threadId);

        while (running.get()) {
            try {
                MiningTask task = currentTask;

                if (task == null) {
                    // No task available, wait
                    Thread.sleep(TASK_POLL_DELAY_MS);
                    continue;
                }

                // Mining loop for current task
                mineTask(task, threadId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in mining worker {}", threadId, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Mining worker {} stopped", threadId);
    }

    /**
     * Mine a specific task
     *
     * @param task Mining task to work on
     * @param threadId Worker thread ID
     */
    private void mineTask(MiningTask task, int threadId) throws InterruptedException {
        Block candidate = task.getCandidateBlock();
        long taskIdx = task.getTaskIndex();

        // Try NONCE_CHECK_INTERVAL nonces before checking for task updates
        for (int i = 0; i < NONCE_CHECK_INTERVAL && running.get(); i++) {
            // Get next nonce (distributed across threads)
            long nonceValue = nonceCounter.getAndAdd(threadCount) + threadId;

            // Create nonce as Bytes32
            Bytes32 nonce = createNonce(nonceValue);

            // Calculate hash
            Bytes32 hash = calculateHash(candidate, nonce, task);

            totalHashesComputed.incrementAndGet();

            // Submit share to MiningManager
            // MiningManager's ShareValidator will determine if this is better
            boolean accepted = miningManager.receiveShare(nonce, taskIdx);

            if (accepted) {
                totalSharesSubmitted.incrementAndGet();
                log.debug("Worker {} submitted share: nonce={}, hash={}",
                        threadId,
                        Long.toHexString(nonceValue),
                        hash.toHexString().substring(0, 16) + "...");
            }

            // Periodically log progress
            if (i % 1000 == 0 && i > 0) {
                long totalHashes = totalHashesComputed.get();
                if (totalHashes % 100000 == 0) {
                    log.info("Mining progress: {} hashes computed, {} shares submitted",
                            totalHashes, totalSharesSubmitted.get());
                }
            }
        }

        // Check if task changed
        if (!task.equals(currentTask)) {
            log.debug("Worker {} detected task change, restarting", threadId);
        }
    }

    /**
     * Calculate hash for block with given nonce
     *
     * @param candidate Candidate block
     * @param nonce Nonce to try
     * @param task Mining task
     * @return Calculated hash
     */
    private Bytes32 calculateHash(Block candidate, Bytes32 nonce, MiningTask task) {
        // Create block with new nonce
        Block blockWithNonce = candidate.withNonce(nonce);

        if (task.isRandomX()) {
            // RandomX hash calculation
            if (powAlgorithm == null) {
                log.warn("RandomX task but no PoW algorithm available, using SHA256 fallback");
                return blockWithNonce.getHash();
            }

            try {
                byte[] blockData = blockWithNonce.toBytes();
                byte[] hashBytes = powAlgorithm.calculateBlockHash(
                        blockData,
                        io.xdag.consensus.pow.HashContext.forMining(task.getTimestamp())
                );

                return hashBytes != null ? Bytes32.wrap(hashBytes) : blockWithNonce.getHash();
            } catch (Exception e) {
                log.warn("RandomX hash calculation failed, using SHA256 fallback: {}", e.getMessage());
                return blockWithNonce.getHash();
            }
        } else {
            // SHA256 hash calculation (Block.getHash() does double SHA256)
            return blockWithNonce.getHash();
        }
    }

    /**
     * Create nonce from long value
     *
     * @param nonceValue Nonce value
     * @return Bytes32 nonce
     */
    private Bytes32 createNonce(long nonceValue) {
        byte[] nonceBytes = new byte[32];
        // Encode nonce value in little-endian
        for (int i = 0; i < 8; i++) {
            nonceBytes[i] = (byte) ((nonceValue >> (i * 8)) & 0xFF);
        }
        return Bytes32.wrap(nonceBytes);
    }

    // ========== Statistics ==========

    /**
     * Get mining statistics
     *
     * @return Human-readable statistics
     */
    public String getStatistics() {
        long hashes = totalHashesComputed.get();
        long shares = totalSharesSubmitted.get();
        double acceptRate = hashes > 0 ? (shares * 100.0 / hashes) : 0.0;

        return String.format(
                "LocalMiner Stats:\n" +
                "  - Running: %s\n" +
                "  - Threads: %d\n" +
                "  - Current task: %s\n" +
                "  - Total hashes: %d\n" +
                "  - Total shares: %d\n" +
                "  - Accept rate: %.4f%%",
                running.get() ? "YES" : "NO",
                threadCount,
                currentTask != null ? "#" + currentTask.getTaskIndex() : "none",
                hashes,
                shares,
                acceptRate
        );
    }

    /**
     * Get total hashes computed
     *
     * @return hash count
     */
    public long getTotalHashesComputed() {
        return totalHashesComputed.get();
    }

    /**
     * Get total shares submitted
     *
     * @return share count
     */
    public long getTotalSharesSubmitted() {
        return totalSharesSubmitted.get();
    }
}
