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
import io.xdag.Wallet;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MiningManager - Internal pool server (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class implements an <strong>internal pool server</strong> that is deprecated and will be
 * removed in version <strong>0.9.0</strong>. Mining pool functionality should be moved to the
 * standalone <strong>xdagj-pool</strong> project.
 *
 * <h2>Why Deprecated?</h2>
 * <p>Mixing pool server functionality with blockchain node violates separation of concerns:
 * <ul>
 *   <li>❌ Node becomes too complex (blockchain + pool + mining)</li>
 *   <li>❌ Cannot scale pool and node independently</li>
 *   <li>❌ Cannot run multiple pools against single node</li>
 *   <li>❌ Harder to maintain and test</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p><strong>OLD (Deprecated)</strong>:
 * <pre>
 * // Node with internal pool (deprecated)
 * MiningManager manager = new MiningManager(dagKernel, wallet, powAlgorithm, 8);
 * manager.start();
 * // Miners connect directly to node
 * </pre>
 *
 * <p><strong>NEW (Recommended)</strong>:
 * <pre>
 * // 1. Node exposes RPC interface
 * MiningRpcServiceImpl rpcService = kernel.getMiningRpcService();
 *
 * // 2. Deploy separate pool server (xdagj-pool)
 * PoolServer pool = new PoolServer(config);
 * pool.connectToNode("http://localhost:10001");
 * pool.start();
 *
 * // 3. Miners connect to pool (not node)
 * // Node → Pool → Miners (proper architecture)
 * </pre>
 *
 * <h2>Temporary Usage (Testing Only)</h2>
 * <p>For development and testing, you can still use MiningManager, but be aware:
 * <ul>
 *   <li>⚠️ Not recommended for production</li>
 *   <li>⚠️ Will be removed in v0.9.0</li>
 *   <li>⚠️ No new features will be added</li>
 *   <li>⚠️ Bugs may not be fixed</li>
 * </ul>
 *
 * <h2>What This Class Does</h2>
 * <ul>
 *   <li>Generates candidate blocks every 64 seconds</li>
 *   <li>Receives mining shares from external miners via {@link #receiveShare(Bytes32, long)}</li>
 *   <li>Validates shares and tracks the best solution</li>
 *   <li>Broadcasts the best block when epoch ends</li>
 * </ul>
 *
 * <h2>Timeline</h2>
 * <ul>
 *   <li><strong>v0.8.2</strong>: Marked as @Deprecated (current)</li>
 *   <li><strong>v0.8.3</strong>: xdagj-pool project available</li>
 *   <li><strong>v0.9.0</strong>: MiningManager removed (breaking change)</li>
 * </ul>
 *
 * @since XDAGJ v5.1
 * @deprecated Since v0.8.2, scheduled for removal in v0.9.0.
 *             Use external pool server (xdagj-pool) connecting via
 *             {@link io.xdag.rpc.service.MiningRpcServiceImpl} instead.
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 * @see io.xdag.rpc.service.NodeMiningRpcService
 */
@Deprecated(since = "0.8.2", forRemoval = true)
@Slf4j
public class MiningManager {

    // ========== Configuration ==========

    /**
     * Block interval in seconds (1 epoch = 64 seconds)
     */
    public static final long BLOCK_INTERVAL = 64;

    /**
     * Timeout for mining (end of epoch)
     */
    public static final long MINING_TIMEOUT_OFFSET = 64;

    // ========== Dependencies ==========

    private final DagKernel dagKernel;
    private final DagChain dagChain;
    private final Wallet wallet;
    private final PowAlgorithm powAlgorithm;
    private final int ttl;  // Network broadcast TTL

    // ========== Components ==========

    private final BlockGenerator blockGenerator;
    private final ShareValidator shareValidator;
    private final BlockBroadcaster blockBroadcaster;

    // ========== State ==========

    /**
     * Current mining task
     */
    private final AtomicReference<MiningTask> currentTask = new AtomicReference<>();

    /**
     * Task index counter
     */
    private final AtomicLong taskIndex = new AtomicLong(0);

    /**
     * Running state
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Mining scheduler
     */
    private ScheduledExecutorService miningScheduler;

    /**
     * Statistics: total blocks mined
     */
    private final AtomicLong totalBlocksMined = new AtomicLong(0);

    /**
     * Statistics: total shares received
     */
    private final AtomicLong totalSharesReceived = new AtomicLong(0);

    // ========== Constructor ==========

    /**
     * Create a new MiningManager
     *
     * @param dagKernel DagKernel for mining operations
     * @param wallet Wallet for coinbase rewards
     * @param powAlgorithm PoW algorithm instance (can be null if not using RandomX)
     * @param ttl Network broadcast time-to-live
     */
    public MiningManager(DagKernel dagKernel, Wallet wallet, PowAlgorithm powAlgorithm, int ttl) {
        if (dagKernel == null) {
            throw new IllegalArgumentException("DagKernel cannot be null");
        }
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet cannot be null");
        }
        if (ttl <= 0) {
            throw new IllegalArgumentException("TTL must be positive");
        }

        this.dagKernel = dagKernel;
        this.dagChain = dagKernel.getDagChain();
        this.wallet = wallet;
        this.powAlgorithm = powAlgorithm;
        this.ttl = ttl;

        // Create components
        this.blockGenerator = new BlockGenerator(dagChain, wallet, powAlgorithm);
        this.shareValidator = new ShareValidator(powAlgorithm);
        this.blockBroadcaster = new BlockBroadcaster(dagKernel, ttl);

        log.info("MiningManager initialized with TTL={}", ttl);
    }

    // ========== Lifecycle Management ==========

    /**
     * Start the mining manager
     *
     * <p>This method:
     * <ul>
     *   <li>Starts the mining scheduler</li>
     *   <li>Begins mining at next epoch boundary</li>
     *   <li>Runs mining cycle every 64 seconds</li>
     * </ul>
     */
    public synchronized void start() {
        if (running.getAndSet(true)) {
            log.warn("MiningManager already running");
            return;
        }

        log.info("========================================");
        log.info("Starting MiningManager");
        log.info("========================================");

        // Create mining scheduler
        miningScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "MiningManager-Scheduler");
            thread.setDaemon(true);
            return thread;
        });

        // Calculate initial delay to next epoch boundary
        long currentTime = XdagTime.getCurrentTimestamp();
        long nextEpoch = XdagTime.getEndOfEpoch(currentTime);
        long initialDelay = Math.max(1, nextEpoch - currentTime);

        // DEVNET ONLY: Use short delay for testing (10 seconds)
        // In production, this will use the full epoch delay
        boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
        if (isDevnet && initialDelay > 30) {
            log.warn("⚠ DEVNET TEST MODE: Reducing mining delay from {} to 10 seconds", initialDelay);
            initialDelay = 10;
        }

        log.info("Mining will start in {} seconds ({})",
                initialDelay,
                isDevnet && initialDelay <= 30 ? "devnet fast mode" : "at next epoch boundary");

        // Schedule periodic mining (every 64 seconds)
        miningScheduler.scheduleAtFixedRate(
                this::mineBlock,
                initialDelay,
                BLOCK_INTERVAL,
                TimeUnit.SECONDS
        );

        log.info("✓ MiningManager started");
        log.info("  - Block interval: {} seconds", BLOCK_INTERVAL);
        log.info("  - Initial delay: {} seconds", initialDelay);
        log.info("========================================");
    }

    /**
     * Stop the mining manager
     *
     * <p>This method:
     * <ul>
     *   <li>Stops the mining scheduler</li>
     *   <li>Waits for current mining cycle to complete</li>
     *   <li>Cleans up resources</li>
     * </ul>
     */
    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            log.warn("MiningManager not running");
            return;
        }

        log.info("========================================");
        log.info("Stopping MiningManager");
        log.info("========================================");

        // Shutdown scheduler
        if (miningScheduler != null) {
            miningScheduler.shutdown();
            try {
                // Wait up to 30 seconds for graceful shutdown
                if (!miningScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    miningScheduler.shutdownNow();
                    log.warn("Mining scheduler forcefully terminated");
                }
            } catch (InterruptedException e) {
                miningScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            miningScheduler = null;
        }

        log.info("✓ MiningManager stopped");
        log.info("  - Total blocks mined: {}", totalBlocksMined.get());
        log.info("  - Total shares received: {}", totalSharesReceived.get());
        log.info("========================================");
    }

    /**
     * Check if mining manager is running
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    // ========== Mining Operations ==========

    /**
     * Mine a new block
     *
     * <p>This method is called every 64 seconds by the scheduler.
     * It performs a complete mining cycle:
     * <ol>
     *   <li>Generate candidate block</li>
     *   <li>Create mining task</li>
     *   <li>Reset share validator</li>
     *   <li>Send task to pools (if pool mode)</li>
     *   <li>Wait for shares/timeout</li>
     *   <li>Broadcast best block</li>
     * </ol>
     */
    private void mineBlock() {
        try {
            log.info("========================================");
            log.info("Starting mining cycle {}", taskIndex.get() + 1);
            log.info("========================================");

            // Step 1: Generate candidate block
            Block candidateBlock = blockGenerator.generateCandidate();
            if (candidateBlock == null) {
                log.error("Failed to generate candidate block, skipping cycle");
                return;
            }

            log.info("Generated candidate block: hash={}, timestamp={}",
                    candidateBlock.getHash().toHexString().substring(0, 18) + "...",
                    candidateBlock.getTimestamp());

            // Step 2: Create mining task
            MiningTask task = createMiningTask(candidateBlock);
            currentTask.set(task);
            taskIndex.incrementAndGet();

            log.info("Created mining task #{}: isRandomX={}",
                    task.getTaskIndex(),
                    task.isRandomX());

            // Step 3: Reset share validator for new task
            shareValidator.reset();

            // Step 4: External miners will fetch this task and submit shares
            // Task is available via getCurrentTask() for HTTP/RPC API
            log.info("Mining task ready for external miners to fetch and process");

            // Step 5: Schedule timeout for end of epoch
            long timeout = XdagTime.getEndOfEpoch(candidateBlock.getTimestamp());
            long timeToTimeout = timeout - XdagTime.getCurrentTimestamp();

            log.info("Mining cycle will timeout in {} seconds", timeToTimeout);

            // Schedule timeout handler
            miningScheduler.schedule(
                    () -> onMiningTimeout(task),
                    timeToTimeout,
                    TimeUnit.SECONDS
            );

        } catch (Exception e) {
            log.error("Error in mining cycle", e);
        }
    }

    /**
     * Create a mining task from candidate block
     *
     * <p><strong>NOTE ON RANDOMX SEED</strong>:
     * For pool server architecture, external miners don't need the RandomX seed.
     * They receive the complete block data and initialize their own RandomX VM.
     * The seed management is handled by this node's {@link RandomXPow} component.
     *
     * @param candidateBlock Candidate block to mine
     * @return MiningTask ready for miners
     */
    private MiningTask createMiningTask(Block candidateBlock) {
        long timestamp = candidateBlock.getTimestamp();
        long taskIdx = taskIndex.get() + 1;

        // Check if RandomX fork is active
        boolean isRandomXFork = blockGenerator.isRandomXFork(timestamp);

        if (isRandomXFork) {
            // Create RandomX task
            Bytes32 preHash = candidateBlock.getRandomXPreHash();
            byte[] randomXSeed = getRandomXSeedIdentifier(timestamp);

            return new MiningTask(candidateBlock, preHash, timestamp, taskIdx, randomXSeed);

        } else {
            // Create SHA256 task
            // For SHA256, the pre-hash is the block's partial hash
            Bytes32 preHash = candidateBlock.getHash();

            return new MiningTask(candidateBlock, preHash, timestamp, taskIdx, (io.xdag.crypto.hash.XdagSha256Digest) null);
        }
    }

    /**
     * Get RandomX seed identifier for current timestamp
     *
     * <p><strong>IMPORTANT</strong>: This is NOT the actual RandomX seed bytes!
     * External miners don't need the seed - they initialize RandomX from block data.
     * This returns a seed epoch identifier for task tracking purposes only.
     *
     * <p>The actual RandomX seed management is handled by {@link RandomXPow} and
     * {@link RandomXSeedManager} components within this node.
     *
     * @param timestamp Current block timestamp
     * @return Seed epoch identifier (for logging/tracking), not actual seed
     */
    private byte[] getRandomXSeedIdentifier(long timestamp) {
        if (powAlgorithm == null) {
            return new byte[32];  // Empty identifier if PoW algorithm not available
        }

        // For pool architecture, we don't need to pass actual seed to external miners
        // Return epoch identifier for task tracking
        long epoch = timestamp / 64;
        byte[] identifier = new byte[32];
        // Encode epoch in identifier (for debugging/logging)
        for (int i = 0; i < 8 && i < 32; i++) {
            identifier[i] = (byte) ((epoch >> (i * 8)) & 0xFF);
        }
        return identifier;
    }

    /**
     * Handle mining timeout (end of epoch)
     *
     * <p>Called when mining cycle times out. This method:
     * <ol>
     *   <li>Creates mined block with best share</li>
     *   <li>Broadcasts block to network</li>
     *   <li>Logs statistics</li>
     * </ol>
     *
     * @param task The mining task that timed out
     */
    private void onMiningTimeout(MiningTask task) {
        try {
            log.info("Mining cycle #{} timed out", task.getTaskIndex());

            // Check if we have a valid share
            if (!shareValidator.hasValidShare()) {
                log.warn("No valid share found for task #{}, cannot create block", task.getTaskIndex());
                return;
            }

            // Create mined block with best share
            Block minedBlock = shareValidator.createMinedBlock(task);
            if (minedBlock == null) {
                log.error("Failed to create mined block for task #{}", task.getTaskIndex());
                return;
            }

            log.info("Best share found: hash={}, nonce={}",
                    minedBlock.getHash().toHexString().substring(0, 18) + "...",
                    minedBlock.getHeader().getNonce().toHexString().substring(0, 18) + "...");

            // Broadcast the mined block
            boolean success = blockBroadcaster.broadcast(minedBlock);

            if (success) {
                totalBlocksMined.incrementAndGet();
                log.info("✓ Block mined and broadcast successfully!");
                log.info("  - Task: #{}", task.getTaskIndex());
                log.info("  - Hash: {}", minedBlock.getHash().toHexString());
                log.info("  - Shares received: {}", shareValidator.getTotalSharesValidated());
                log.info("  - Improved shares: {}", shareValidator.getImprovedSharesCount());
            } else {
                log.error("Failed to broadcast mined block");
            }

        } catch (Exception e) {
            log.error("Error handling mining timeout for task #{}", task.getTaskIndex(), e);
        }
    }

    /**
     * Receive a mining share from a pool or local miner
     *
     * <p>This method validates the share and updates the best share if this one is better.
     *
     * @param nonce Share nonce to validate
     * @param taskIdx Task index this share belongs to
     * @return true if share was accepted, false otherwise
     */
    public boolean receiveShare(Bytes32 nonce, long taskIdx) {
        if (!running.get()) {
            log.debug("Mining manager not running, ignoring share");
            return false;
        }

        MiningTask task = currentTask.get();
        if (task == null) {
            log.debug("No current task, ignoring share");
            return false;
        }

        if (task.getTaskIndex() != taskIdx) {
            log.debug("Share task index mismatch: expected {}, got {}",
                    task.getTaskIndex(), taskIdx);
            return false;
        }

        // Validate share
        totalSharesReceived.incrementAndGet();
        boolean isBest = shareValidator.validateShare(nonce, task);

        if (isBest) {
            log.info("New best share received for task #{}", taskIdx);
        }

        return true;
    }

    // ========== Statistics and Status ==========

    /**
     * Get mining statistics summary
     *
     * @return Human-readable statistics
     */
    public String getStatistics() {
        return String.format(
                "MiningManager Stats:\n" +
                "  - Running: %s\n" +
                "  - Current task: #%d\n" +
                "  - Total blocks mined: %d\n" +
                "  - Total shares received: %d\n" +
                "  - %s\n" +
                "  - %s\n" +
                "  - %s",
                running.get() ? "YES" : "NO",
                taskIndex.get(),
                totalBlocksMined.get(),
                totalSharesReceived.get(),
                shareValidator.getStatistics(),
                blockBroadcaster.getStatistics(),
                blockGenerator.getClass().getSimpleName() + " ready"
        );
    }

    /**
     * Get current mining task
     *
     * @return Current MiningTask or null if none
     */
    public MiningTask getCurrentTask() {
        return currentTask.get();
    }

    /**
     * Get total blocks mined
     *
     * @return count of successfully mined blocks
     */
    public long getTotalBlocksMined() {
        return totalBlocksMined.get();
    }

    /**
     * Get total shares received
     *
     * @return count of shares received from pools
     */
    public long getTotalSharesReceived() {
        return totalSharesReceived.get();
    }
}
