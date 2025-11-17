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

import static io.xdag.config.Constants.MAIN_CHAIN_PERIOD;
import static io.xdag.config.Constants.MIN_GAS;

import io.xdag.DagKernel;
import io.xdag.config.Constants.MessageType;
import io.xdag.db.DagStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionStore;
import io.xdag.db.cache.DagEntityResolver;
import io.xdag.db.cache.ResolvedLinks;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Listener;
import io.xdag.utils.XdagTime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * DagChain implementation for XDAG epoch-based DAG consensus
 *
 * <p>Implements epoch-based consensus with cumulative difficulty calculation.
 * Uses DagKernel exclusively for all storage operations.
 *
 * @since XDAGJ
 */
@Slf4j
public class DagChainImpl implements DagChain {

    // ==================== Consensus Parameters ====================

    /**
     * Initial base difficulty target for PoW validation
     * <p>
     * Requires hash to have approximately 8 zero bytes (hash < 2^192)
     * Average mining time with 1 GH/s: ~5 hours per block
     * Network effect (1000 miners @ 10 GH/s): ~346 blocks/epoch
     */
    private static final UInt256 INITIAL_BASE_DIFFICULTY_TARGET =
            UInt256.valueOf(BigInteger.valueOf(2).pow(192));

    /**
     * DEVNET difficulty target - accepts any block (no PoW required)
     * <p>
     * Used in development/testing environments where PoW validation
     * would prevent tests from running (random blocks won't pass real PoW).
     */
    private static final UInt256 DEVNET_DIFFICULTY_TARGET = UInt256.MAX_VALUE;

    /**
     * Maximum blocks accepted per epoch (64 seconds)
     * <p>
     * Controls orphan block growth and storage consumption
     * Effect: 10,000 nodes → 100 accepted blocks → 25 GB/year storage
     */
    private static final int MAX_BLOCKS_PER_EPOCH = 100;

    /**
     * Target blocks per epoch for difficulty adjustment
     * <p>
     * Set higher than MAX_BLOCKS_PER_EPOCH to maintain competition
     * Adjustment keeps ~150 qualifying blocks, accepting top 100
     */
    private static final int TARGET_BLOCKS_PER_EPOCH = 150;

    /**
     * Difficulty adjustment interval (in epochs)
     * <p>
     * Adjust every 1000 epochs (~17.7 hours)
     * Balances stability with adaptiveness to hashrate changes
     */
    private static final int DIFFICULTY_ADJUSTMENT_INTERVAL = 1000;

    /**
     * Orphan block retention window (in epochs)
     * <p>
     * XDAG rule: blocks can only reference blocks within 12 days (16384 epochs)
     * After this window, orphan blocks cannot become main blocks anymore
     */
    private static final long ORPHAN_RETENTION_WINDOW = 16384;

    /**
     * Orphan cleanup interval (in epochs)
     * <p>
     * Run cleanup every 100 epochs (~1.78 hours)
     */
    private static final long ORPHAN_CLEANUP_INTERVAL = 100;

    /**
     * Maximum and minimum difficulty adjustment factors
     * <p>
     * Prevents drastic difficulty swings
     */
    private static final double MAX_ADJUSTMENT_FACTOR = 2.0;   // Max 2x increase
    private static final double MIN_ADJUSTMENT_FACTOR = 0.5;   // Max 50% decrease

    // ==================== Instance Fields ====================

    private final DagKernel dagKernel;
    private final DagStore dagStore;
    private final DagEntityResolver entityResolver;
    private final OrphanBlockStore orphanBlockStore;
    private final TransactionStore transactionStore;

    private volatile ChainStats chainStats;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<DagchainListener> dagchainListeners = new ArrayList<>();
    private final ThreadLocal<Boolean> isRetryingOrphans = ThreadLocal.withInitial(() -> false);
    private volatile Bytes miningCoinbase = Bytes.wrap(new byte[20]);

    /**
     * Creates DagChain with DagKernel dependencies
     *
     * @param dagKernel kernel providing storage and entity resolution
     */
    public DagChainImpl(DagKernel dagKernel) {
        this.dagKernel = dagKernel;
        this.dagStore = dagKernel.getDagStore();
        this.entityResolver = dagKernel.getEntityResolver();
        this.orphanBlockStore = dagKernel.getOrphanBlockStore();
        this.transactionStore = dagKernel.getTransactionStore();

        this.chainStats = dagStore.getChainStats();
        if (this.chainStats == null) {
            long currentEpoch = XdagTime.getCurrentTimestamp() / 64;

            // DEVNET: Use relaxed difficulty target (no PoW required)
            // MAINNET/TESTNET: Use real difficulty target (requires actual mining)
            boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
            UInt256 initialTarget = isDevnet ? DEVNET_DIFFICULTY_TARGET : INITIAL_BASE_DIFFICULTY_TARGET;

            if (isDevnet) {
                log.info("DEVNET mode detected - using relaxed difficulty target (no PoW required)");
            }

            this.chainStats = ChainStats.builder()
                    .mainBlockCount(0)
                    .maxDifficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                    .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                    .baseDifficultyTarget(initialTarget)
                    .lastDifficultyAdjustmentEpoch(currentEpoch)  // Start from current epoch to prevent immediate adjustment
                    .lastOrphanCleanupEpoch(currentEpoch)          // Start from current epoch
                    .topBlock(null)
                    .topDifficulty(UInt256.ZERO)
                    .preTopBlock(null)
                    .preTopDifficulty(UInt256.ZERO)
                    .build();
            dagStore.saveChainStats(this.chainStats);
        }

        // Initialize new consensus fields for existing chains
        if (this.chainStats.getBaseDifficultyTarget() == null) {
            long currentEpoch = XdagTime.getCurrentTimestamp() / 64;

            // DEVNET: Use relaxed difficulty target (no PoW required)
            // MAINNET/TESTNET: Use real difficulty target (requires actual mining)
            boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
            UInt256 initialTarget = isDevnet ? DEVNET_DIFFICULTY_TARGET : INITIAL_BASE_DIFFICULTY_TARGET;

            log.info("Initializing baseDifficultyTarget for existing chain (devnet={}, target={})",
                    isDevnet, initialTarget.toHexString().substring(0, 16) + "...");

            this.chainStats = this.chainStats.toBuilder()
                    .baseDifficultyTarget(initialTarget)
                    .lastDifficultyAdjustmentEpoch(currentEpoch)
                    .lastOrphanCleanupEpoch(currentEpoch)
                    .topBlock(this.chainStats.getTopBlock())
                    .topDifficulty(this.chainStats.getTopDifficulty() != null ?
                            this.chainStats.getTopDifficulty() : UInt256.ZERO)
                    .preTopBlock(this.chainStats.getPreTopBlock())
                    .preTopDifficulty(this.chainStats.getPreTopDifficulty() != null ?
                            this.chainStats.getPreTopDifficulty() : UInt256.ZERO)
                    .build();
            dagStore.saveChainStats(this.chainStats);
        }

        log.info("DagChainImpl initialized with DagKernel");
        log.info("  - DagStore: {}", dagStore.getClass().getSimpleName());
        log.info("  - DagEntityResolver: {}", entityResolver.getClass().getSimpleName());
        log.info("  - OrphanBlockStore: {}", orphanBlockStore.getClass().getSimpleName());
        log.info("  - TransactionStore: {}", transactionStore.getClass().getSimpleName());
    }

    // ==================== Block Import Operations ====================

    @Override
    public synchronized DagImportResult tryToConnect(Block block) {
        try {
            log.debug("Attempting to connect block: {}", block.getHash().toHexString());

            // Basic validation
            DagImportResult basicValidation = validateBasicRules(block);
            if (basicValidation != null) {
                return basicValidation;
            }

            // NEW CONSENSUS: Minimum PoW validation
            DagImportResult powValidation = validateMinimumPoW(block);
            if (powValidation != null) {
                return powValidation;
            }

            // NEW CONSENSUS: Epoch limit validation
            DagImportResult epochLimitValidation = validateEpochLimit(block);
            if (epochLimitValidation != null) {
                return epochLimitValidation;
            }

            // Link validation (Transaction and Block references)
            DagImportResult linkValidation = validateLinks(block);
            if (linkValidation != null) {
                return linkValidation;
            }

            // DAG rules validation
            DAGValidationResult dagValidation = validateDAGRules(block);
            if (!dagValidation.isValid()) {
                log.debug("Block {} failed DAG validation: {}",
                         block.getHash().toHexString(), dagValidation.getErrorMessage());
                return DagImportResult.invalidDAG(dagValidation);
            }

            // Calculate cumulative difficulty
            UInt256 cumulativeDifficulty;
            try {
                cumulativeDifficulty = calculateCumulativeDifficulty(block);
                log.debug("Block {} cumulative difficulty: {}",
                         block.getHash().toHexString(), cumulativeDifficulty.toDecimalString());
            } catch (Exception e) {
                log.error("Failed to calculate cumulative difficulty for block {}",
                         block.getHash().toHexString(), e);
                return DagImportResult.error(e, "Failed to calculate cumulative difficulty: " + e.getMessage());
            }

            // Main chain determination
            boolean isBestChain = cumulativeDifficulty.compareTo(chainStats.getMaxDifficulty()) > 0;
            long height;

            if (isBestChain) {
                // This block extends the main chain
                height = chainStats.getMainBlockCount() + 1;
                log.info("Block {} becomes main block at height {} with cumulative difficulty {}",
                        block.getHash().toHexString(), height, cumulativeDifficulty.toDecimalString());
            } else {
                // This block is an orphan or side chain block
                height = 0;
                log.debug("Block {} imported as orphan with cumulative difficulty {}",
                         block.getHash().toHexString(), cumulativeDifficulty.toDecimalString());
            }

            // Epoch competition check and reorganization
            long blockEpoch = block.getEpoch();
            Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

            // DEBUG: Log epoch competition details
            if (currentWinner == null) {
                log.debug("No winner found in epoch {} when importing block {}",
                        blockEpoch, block.getHash().toHexString().substring(0, 16));
                // Check if there are ANY blocks in this epoch
                List<Block> candidates = getCandidateBlocksInEpoch(blockEpoch);
                log.debug("  Total candidates in epoch {}: {}", blockEpoch, candidates.size());
                for (Block candidate : candidates) {
                    log.debug("    Candidate: hash={}, height={}",
                            candidate.getHash().toHexString().substring(0, 16),
                            candidate.getInfo() != null ? candidate.getInfo().getHeight() : "null");
                }
            } else {
                log.debug("Current winner in epoch {} is {} (height={})",
                        blockEpoch,
                        currentWinner.getHash().toHexString().substring(0, 16),
                        currentWinner.getInfo().getHeight());
            }

            boolean epochWinner = currentWinner == null || block.getHash().compareTo(currentWinner.getHash()) < 0;

            if (epochWinner && currentWinner != null && !currentWinner.getHash().equals(block.getHash())) {
                // This block wins the epoch competition, demote previous winner
                log.debug("Block {} wins epoch {} competition (smaller hash than {})",
                         block.getHash().toHexString(), blockEpoch,
                         currentWinner.getHash().toHexString());

                // IMPORTANT: Save the replacement height BEFORE demotion
                // The new winner will REPLACE the old winner at the SAME height
                long replacementHeight = currentWinner.getInfo().getHeight();

                demoteBlockToOrphan(currentWinner);

                // The new winner takes the demoted block's height (REPLACEMENT, not addition)
                height = replacementHeight;  // NOT mainBlockCount + 1
                isBestChain = true;
                log.info("Block {} promoted to main chain at height {} after winning epoch competition",
                        block.getHash().toHexString(), height);
            } else if (!epochWinner && isBestChain) {
                // This block has higher cumulative difficulty but loses epoch competition
                // Mark as orphan instead of main block
                height = 0;
                isBestChain = false;
                log.debug("Block {} loses epoch {} competition (larger hash than {}), demoting to orphan",
                         block.getHash().toHexString(), blockEpoch,
                         currentWinner.getHash().toHexString());
            }

            // Save block and metadata
            BlockInfo blockInfo = BlockInfo.builder()
                    .hash(block.getHash())
                    .timestamp(block.getTimestamp())
                    .height(height)
                    .difficulty(cumulativeDifficulty)
                    .build();

            dagStore.saveBlockInfo(blockInfo);
            Block blockWithInfo = block.toBuilder().info(blockInfo).build();
            dagStore.saveBlock(blockWithInfo);

            // DEBUG: Log saved block details for troubleshooting
            log.debug("Saved block: hash={}, epoch={}, timestamp={}, height={}",
                    blockWithInfo.getHash().toHexString().substring(0, 16),
                    blockWithInfo.getEpoch(),
                    blockWithInfo.getTimestamp(),
                    blockWithInfo.getInfo().getHeight());

            // Index transactions (for all blocks)
            indexTransactions(block);

            // Update chain statistics and process transactions (ONLY for main blocks)
            if (isBestChain) {
                // Process block transactions and update account state
                // IMPORTANT: Only execute transactions for main blocks to prevent orphan block transactions from affecting state
                if (dagKernel != null) {
                    DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
                    if (blockProcessor != null) {
                        DagBlockProcessor.ProcessingResult processResult =
                                blockProcessor.processBlock(blockWithInfo);

                        if (!processResult.isSuccess()) {
                            log.error("Block {} transaction processing failed: {}",
                                    block.getHash().toHexString(), processResult.getError());
                            // Transaction execution failed but block is valid, continue
                        } else {
                            log.info("Block {} transactions executed successfully",
                                    block.getHash().toHexString());
                        }
                    }
                }

                // Update chain statistics
                updateChainStatsForNewMainBlock(blockInfo);

                // NEW CONSENSUS: Check and adjust difficulty
                checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());

                // NEW CONSENSUS: Cleanup old orphans
                cleanupOldOrphans(block.getEpoch());
            }

            // Notify listeners
            notifyListeners(blockWithInfo);

            // Notify DAG chain listeners (only for main blocks)
            if (isBestChain) {
                notifyDagchainListeners(blockWithInfo);
            }

            // Retry orphan blocks (dependency resolution)
            retryOrphanBlocks();

            log.info("Successfully imported block {}: height={}, difficulty={}",
                    block.getHash().toHexString(), height, cumulativeDifficulty.toDecimalString());

            // Return detailed result
            if (isBestChain) {
                return DagImportResult.mainBlock(blockEpoch, height, cumulativeDifficulty, epochWinner);
            } else {
                return DagImportResult.orphan(blockEpoch, cumulativeDifficulty, epochWinner);
            }

        } catch (Exception e) {
            log.error("Error importing block {}: {}", block.getHash().toHexString(), e.getMessage(), e);
            return DagImportResult.error(e, "Exception during import: " + e.getMessage());
        }
    }

    /**
     * Validate basic block rules
     */
    private DagImportResult validateBasicRules(Block block) {
        // Check timestamp
        long currentTime = XdagTime.getCurrentTimestamp();
        if (block.getTimestamp() > (currentTime + MAIN_CHAIN_PERIOD / 4)) {
            log.debug("Block {} has invalid timestamp: {} (current: {})",
                     block.getHash().toHexString(), block.getTimestamp(), currentTime);
            return DagImportResult.invalidBasic("Block timestamp is too far in the future");
        }

        if (block.getTimestamp() < dagKernel.getConfig().getXdagEra()) {
            log.debug("Block {} timestamp {} is before XDAG era {}",
                     block.getHash().toHexString(), block.getTimestamp(), dagKernel.getConfig().getXdagEra());
            return DagImportResult.invalidBasic("Block timestamp is before XDAG era");
        }

        // Check if block already exists
        if (dagStore.hasBlock(block.getHash())) {
            Block existingBlock = dagStore.getBlockByHash(block.getHash(), false);
            if (existingBlock != null && existingBlock.getInfo() != null && existingBlock.getInfo().getHeight() == 0) {
                // Orphan block exists - allow re-processing
                // When dependencies arrive, orphan blocks should be re-evaluated for main chain inclusion
                log.debug("Block {} exists as orphan, allowing re-processing",
                        block.getHash().toHexString());
                // Continue with validation
            } else {
                log.debug("Block {} already exists as non-orphan", block.getHash().toHexString());
                return DagImportResult.duplicate();
            }
        }

        // SECURITY: Validate genesis block (防止伪造创世区块攻击)
        // Genesis blocks can only be accepted if the chain is empty
        if (isGenesisBlock(block)) {
            if (chainStats.getMainBlockCount() > 0) {
                log.warn("SECURITY: Rejecting genesis block {} - chain already initialized with {} blocks",
                        block.getHash().toHexString(), chainStats.getMainBlockCount());
                return DagImportResult.invalidBasic("Genesis block rejected: chain already has main blocks");
            }

            // Genesis block must be created at or very close to XDAG era time
            long xdagEra = dagKernel.getConfig().getXdagEra();
            long timeDiff = Math.abs(block.getTimestamp() - xdagEra);
            if (timeDiff > 64) {  // Allow 1 epoch (64 seconds) tolerance
                log.warn("SECURITY: Rejecting genesis block {} - invalid timestamp: {} (era: {}, diff: {}s)",
                        block.getHash().toHexString(), block.getTimestamp(), xdagEra, timeDiff);
                return DagImportResult.invalidBasic("Genesis block has invalid timestamp (not at XDAG era)");
            }

            log.info("Accepting genesis block {} at timestamp {} (era: {})",
                    block.getHash().toHexString(), block.getTimestamp(), xdagEra);
        }

        // Validate block structure
        if (!block.isValid()) {
            log.debug("Block {} failed structure validation", block.getHash().toHexString());
            return DagImportResult.invalidBasic("Block structure validation failed");
        }

        // SECURITY: Validate coinbase field length (must be exactly 20 bytes)
        // This prevents BufferOverflowException during hash calculation
        // and ensures all blocks follow the Ethereum-style 20-byte address format
        Bytes coinbase = block.getHeader().getCoinbase();
        if (coinbase == null) {
            log.warn("SECURITY: Rejecting block {} - null coinbase", block.getHash().toHexString());
            return DagImportResult.invalidBasic("Block coinbase is null");
        }
        if (coinbase.size() != 20) {
            log.warn("SECURITY: Rejecting block {} - invalid coinbase length: {} bytes (expected 20)",
                    block.getHash().toHexString(), coinbase.size());
            return DagImportResult.invalidBasic(String.format(
                    "Block coinbase must be exactly 20 bytes, got %d bytes", coinbase.size()));
        }

        return null;  // Validation passed
    }

    /**
     * Check if a block is a genesis block
     * <p>
     * SECURITY: Genesis block identification
     * - Empty links (no parent blocks)
     * - Difficulty exactly equals 1 (minimal difficulty)
     *
     * @param block block to check
     * @return true if genesis block
     */
    private boolean isGenesisBlock(Block block) {
        return block.getLinks().isEmpty() &&
               block.getHeader().getDifficulty() != null &&
               block.getHeader().getDifficulty().equals(UInt256.ONE);
    }

    /**
     * Validate minimum PoW requirement (NEW CONSENSUS)
     * <p>
     * Ensures block hash satisfies the base difficulty target.
     * This prevents spam blocks and ensures basic work was done.
     * <p>
     * Rule: hash <= baseDifficultyTarget
     * <p>
     * Genesis blocks are exempt from this check.
     *
     * @param block block to validate
     * @return null if valid, error result if invalid
     */
    private DagImportResult validateMinimumPoW(Block block) {
        // Skip genesis blocks
        if (isGenesisBlock(block)) {
            return null;
        }

        UInt256 baseDifficultyTarget = chainStats.getBaseDifficultyTarget();

        // Skip validation if target not initialized (backward compatibility)
        if (baseDifficultyTarget == null) {
            log.warn("Base difficulty target not initialized, skipping PoW validation");
            return null;
        }

        // Calculate block hash as UInt256
        UInt256 blockHash = UInt256.fromBytes(block.getHash());

        // Check: hash <= baseDifficultyTarget
        if (blockHash.compareTo(baseDifficultyTarget) > 0) {
            log.debug("Block {} rejected: insufficient PoW (hash {} > target {})",
                    block.getHash().toHexString().substring(0, 16),
                    blockHash.toHexString().substring(0, 16),
                    baseDifficultyTarget.toHexString().substring(0, 16));

            return DagImportResult.invalidBasic(String.format(
                    "Insufficient proof of work: hash exceeds difficulty target (hash=%s, target=%s)",
                    blockHash.toHexString().substring(0, 16) + "...",
                    baseDifficultyTarget.toHexString().substring(0, 16) + "..."
            ));
        }

        log.debug("Block {} passed minimum PoW check (hash {} <= target {})",
                block.getHash().toHexString().substring(0, 16),
                blockHash.toHexString().substring(0, 16),
                baseDifficultyTarget.toHexString().substring(0, 16));

        return null;  // Validation passed
    }

    /**
     * Validate epoch block limit (NEW CONSENSUS)
     * <p>
     * Limits the number of blocks accepted per epoch to control orphan block growth.
     * If epoch already has MAX_BLOCKS_PER_EPOCH blocks, only accept new blocks if they
     * have better difficulty (smaller hash) than the worst existing block.
     * <p>
     * This implements a competitive admission policy:
     * - First MAX_BLOCKS_PER_EPOCH blocks are always accepted
     * - Additional blocks must beat the weakest accepted block
     * - Maintains top N blocks per epoch
     *
     * @param block block to validate
     * @return null if valid, error result if should be rejected
     */
    private DagImportResult validateEpochLimit(Block block) {
        long epoch = block.getEpoch();
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);

        // Filter out only non-orphan blocks (height > 0) for counting
        List<Block> nonOrphanBlocks = candidates.stream()
                .filter(b -> b.getInfo() != null && b.getInfo().getHeight() > 0)
                .toList();

        // If under limit, accept
        if (nonOrphanBlocks.size() < MAX_BLOCKS_PER_EPOCH) {
            log.debug("Block {} accepted: epoch {} has {} < {} non-orphan blocks",
                    block.getHash().toHexString().substring(0, 16),
                    epoch, nonOrphanBlocks.size(), MAX_BLOCKS_PER_EPOCH);
            return null;  // Accept
        }

        // Epoch is full, check if this block is better than the worst one
        UInt256 thisBlockWork = calculateBlockWork(block.getHash());

        // Find the worst block (smallest work = largest hash)
        Block worstBlock = null;
        UInt256 worstWork = UInt256.MAX_VALUE;

        for (Block candidate : nonOrphanBlocks) {
            UInt256 candidateWork = calculateBlockWork(candidate.getHash());
            if (candidateWork.compareTo(worstWork) < 0) {
                worstWork = candidateWork;
                worstBlock = candidate;
            }
        }

        // Compare with worst block
        if (thisBlockWork.compareTo(worstWork) > 0) {
            // This block is better, will replace the worst one
            log.info("Block {} will replace worse block {} in epoch {} (work {} > {})",
                    block.getHash().toHexString().substring(0, 16),
                    worstBlock.getHash().toHexString().substring(0, 16),
                    epoch,
                    thisBlockWork.toHexString().substring(0, 16),
                    worstWork.toHexString().substring(0, 16));

            // Demote worst block to orphan
            demoteBlockToOrphan(worstBlock);

            return null;  // Accept this block
        } else {
            // This block is not better than worst, reject
            log.debug("Block {} rejected: epoch {} full and work {} <= worst {}",
                    block.getHash().toHexString().substring(0, 16),
                    epoch,
                    thisBlockWork.toHexString().substring(0, 16),
                    worstWork.toHexString().substring(0, 16));

            return DagImportResult.invalidBasic(String.format(
                    "Epoch %d full (%d blocks) and this block's work not in top %d",
                    epoch, MAX_BLOCKS_PER_EPOCH, MAX_BLOCKS_PER_EPOCH
            ));
        }
    }

    /**
     * Validate block links (Transaction and Block references)
     */
    private DagImportResult validateLinks(Block block) {
        ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

        // Check for missing references
        if (!resolved.hasAllReferences()) {
            Bytes32 missing = resolved.getMissingReferences().getFirst();
            log.debug("Block {} has missing dependency: {}",
                     block.getHash().toHexString(), missing.toHexString());

            // Save block to DagStore (for later retrieval)
            // Create temporary BlockInfo with height=0 (orphan)
            BlockInfo orphanInfo = BlockInfo.builder()
                    .hash(block.getHash())
                    .timestamp(block.getTimestamp())
                    .height(0)  // Orphan status
                    .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)  // Unknown until dependencies satisfied
                    .build();
            dagStore.saveBlockInfo(orphanInfo);
            Block blockWithInfo = block.toBuilder().info(orphanInfo).build();
            dagStore.saveBlock(blockWithInfo);

            // Add to orphan queue for retry
            orphanBlockStore.addOrphan(block.getHash(), block.getTimestamp());

            log.debug("Saved orphan block {} to queue (missing dependency: {})",
                    block.getHash().toHexString().substring(0, 16),
                    missing.toHexString().substring(0, 16));

            return DagImportResult.missingDependency(
                    missing,
                    "Link target not found: " + missing.toHexString()
            );
        }

        // Validate all referenced Blocks
        for (Block refBlock : resolved.getReferencedBlocks()) {
            // Validate timestamp order
            if (refBlock.getTimestamp() >= block.getTimestamp()) {
                log.debug("Block {} references block {} with invalid timestamp order",
                         block.getHash().toHexString(), refBlock.getHash().toHexString());
                return DagImportResult.invalidLink(
                        "Referenced block timestamp >= current block timestamp",
                        refBlock.getHash()
                );
            }
        }

        // Validate all referenced Transactions
        for (Transaction tx : resolved.getReferencedTransactions()) {
            // Validate transaction structure
            if (!tx.isValid()) {
                log.debug("Transaction {} has invalid structure", tx.getHash().toHexString());
                return DagImportResult.invalidLink("Invalid transaction structure", tx.getHash());
            }

            // Validate transaction signature
            if (!tx.verifySignature()) {
                log.debug("Transaction {} has invalid signature", tx.getHash().toHexString());
                return DagImportResult.invalidLink("Invalid transaction signature", tx.getHash());
            }

            // Validate transaction amount
            if (tx.getAmount().add(tx.getFee()).subtract(MIN_GAS).isNegative()) {
                log.debug("Transaction {} has insufficient amount", tx.getHash().toHexString());
                return DagImportResult.invalidLink("Transaction amount + fee < MIN_GAS", tx.getHash());
            }
        }

        log.debug("Block {} link validation passed: {} block links, {} transaction links",
                 block.getHash().toHexString(),
                 resolved.getReferencedBlocks().size(),
                 resolved.getReferencedTransactions().size());

        return null;  // Validation passed
    }

    /**
     * Index transactions to block mapping
     */
    private void indexTransactions(Block block) {
        for (Link link : block.getLinks()) {
            if (link.isTransaction()) {
                try {
                    transactionStore.indexTransactionToBlock(block.getHash(), link.getTargetHash());
                    log.debug("Indexed transaction {} to block {}",
                             link.getTargetHash().toHexString(), block.getHash().toHexString());
                } catch (Exception e) {
                    log.warn("Failed to index transaction {}: {}",
                            link.getTargetHash().toHexString(), e.getMessage());
                }
            }
        }
    }

    /**
     * Update chain statistics for new main block
     *
     * <p>IMPORTANT: When a block replaces another via epoch competition, its height may be
     * less than the current mainBlockCount. In this case, we should NOT decrease mainBlockCount.
     * Only increase it when the new block truly extends the chain.
     */
    private synchronized void updateChainStatsForNewMainBlock(BlockInfo blockInfo) {
        // Only update mainBlockCount if this block extends the chain
        // (for epoch competition replacements, height < mainBlockCount, so we keep the higher value)
        long newMainBlockCount = Math.max(chainStats.getMainBlockCount(), blockInfo.getHeight());

        chainStats = chainStats
                .withMainBlockCount(newMainBlockCount)
                .withMaxDifficulty(blockInfo.getDifficulty())
                .withDifficulty(blockInfo.getDifficulty())
                .withTopBlock(blockInfo.getHash())
                .withTopDifficulty(blockInfo.getDifficulty());

        // Save updated stats
        dagStore.saveChainStats(chainStats);

        log.debug("Updated chain stats: mainBlockCount={}, maxDifficulty={}",
                 chainStats.getMainBlockCount(), chainStats.getMaxDifficulty().toDecimalString());
    }

    /**
     * Clean up old orphan blocks beyond retention window (NEW CONSENSUS)
     * <p>
     * Periodically removes orphan blocks older than ORPHAN_RETENTION_WINDOW (16384 epochs = 12 days).
     * According to XDAG rules, blocks can only reference blocks within 12 days, so older orphan
     * blocks cannot become main blocks anymore and can be safely deleted.
     * <p>
     * Runs every ORPHAN_CLEANUP_INTERVAL (100) epochs to maintain manageable orphan pool size.
     *
     * @param currentEpoch current epoch number
     */
    private synchronized void cleanupOldOrphans(long currentEpoch) {
        long lastCleanupEpoch = chainStats.getLastOrphanCleanupEpoch();

        // Check if cleanup interval reached
        if (currentEpoch - lastCleanupEpoch < ORPHAN_CLEANUP_INTERVAL) {
            return;  // Not time yet
        }

        log.info("Orphan cleanup triggered at epoch {} (last cleanup: epoch {})",
                currentEpoch, lastCleanupEpoch);

        long cutoffEpoch = currentEpoch - ORPHAN_RETENTION_WINDOW;
        if (cutoffEpoch < 0) {
            cutoffEpoch = 0;  // Don't go negative
        }

        int removedCount = 0;
        long scanStartEpoch = Math.max(0, lastCleanupEpoch - ORPHAN_RETENTION_WINDOW);

        // Scan epochs from last cleanup to cutoff epoch
        for (long epoch = scanStartEpoch; epoch < cutoffEpoch; epoch++) {
            List<Block> candidates = getCandidateBlocksInEpoch(epoch);

            for (Block block : candidates) {
                // Remove orphan blocks (height = 0)
                if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
                    try {
                        dagStore.deleteBlock(block.getHash());
                        orphanBlockStore.deleteByHash(block.getHash().toArray());
                        removedCount++;

                        if (removedCount % 1000 == 0) {
                            log.debug("Orphan cleanup progress: removed {} blocks so far", removedCount);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to delete orphan block {}: {}",
                                block.getHash().toHexString().substring(0, 16), e.getMessage());
                    }
                }
            }
        }

        log.info("Orphan cleanup completed: removed {} blocks from epochs {} to {} (~{} days old)",
                removedCount, scanStartEpoch, cutoffEpoch,
                (currentEpoch - cutoffEpoch) * 64 / 86400);

        // Update last cleanup epoch
        chainStats = chainStats.toBuilder()
                .lastOrphanCleanupEpoch(currentEpoch)
                .build();
        dagStore.saveChainStats(chainStats);
    }

    /**
     * Check and adjust difficulty target if needed (NEW CONSENSUS)
     * <p>
     * Adjusts baseDifficultyTarget every DIFFICULTY_ADJUSTMENT_INTERVAL (1000) epochs
     * based on average blocks per epoch to maintain TARGET_BLOCKS_PER_EPOCH (150) blocks/epoch.
     * <p>
     * Algorithm:
     * - If avgBlocksPerEpoch > TARGET * 1.5 → increase difficulty (lower target)
     * - If avgBlocksPerEpoch < TARGET * 0.5 → decrease difficulty (raise target)
     * - Adjustment limited to MIN_ADJUSTMENT_FACTOR (0.5x) to MAX_ADJUSTMENT_FACTOR (2x)
     *
     * @param currentHeight current main block height
     * @param currentEpoch current epoch number
     */
    private synchronized void checkAndAdjustDifficulty(long currentHeight, long currentEpoch) {
        long lastAdjustmentEpoch = chainStats.getLastDifficultyAdjustmentEpoch();

        // Check if adjustment interval reached
        if (currentEpoch - lastAdjustmentEpoch < DIFFICULTY_ADJUSTMENT_INTERVAL) {
            return;  // Not time yet
        }

        log.info("Difficulty adjustment triggered at epoch {} (last adjustment: epoch {})",
                currentEpoch, lastAdjustmentEpoch);

        // Calculate average blocks per epoch in the adjustment period
        long totalBlocks = 0;
        long epochCount = 0;

        for (long epoch = lastAdjustmentEpoch; epoch < currentEpoch; epoch++) {
            List<Block> blocks = getCandidateBlocksInEpoch(epoch);
            // Count non-orphan blocks only
            long nonOrphanCount = blocks.stream()
                    .filter(b -> b.getInfo() != null && b.getInfo().getHeight() > 0)
                    .count();
            totalBlocks += nonOrphanCount;
            epochCount++;
        }

        double avgBlocksPerEpoch = epochCount > 0 ? (double) totalBlocks / epochCount : 0;

        log.info("Average blocks per epoch in last {} epochs: {} (target: {})",
                epochCount, String.format("%.2f", avgBlocksPerEpoch), TARGET_BLOCKS_PER_EPOCH);

        // Calculate adjustment factor
        double adjustmentFactor = 1.0;

        if (avgBlocksPerEpoch > TARGET_BLOCKS_PER_EPOCH * 1.5) {
            // Too many blocks → increase difficulty (lower target)
            adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
            log.info("Too many blocks, increasing difficulty (lowering target) by factor {}",
                    String.format("%.2f", adjustmentFactor));
        } else if (avgBlocksPerEpoch < TARGET_BLOCKS_PER_EPOCH * 0.5) {
            // Too few blocks → decrease difficulty (raise target)
            adjustmentFactor = TARGET_BLOCKS_PER_EPOCH / avgBlocksPerEpoch;
            log.info("Too few blocks, decreasing difficulty (raising target) by factor {}",
                    String.format("%.2f", adjustmentFactor));
        } else {
            log.info("Block count in acceptable range, no adjustment needed");
            // Update last adjustment epoch even if no change
            chainStats = chainStats.toBuilder()
                    .lastDifficultyAdjustmentEpoch(currentEpoch)
                    .build();
            dagStore.saveChainStats(chainStats);
            return;
        }

        // Limit adjustment factor
        adjustmentFactor = Math.max(MIN_ADJUSTMENT_FACTOR,
                                    Math.min(MAX_ADJUSTMENT_FACTOR, adjustmentFactor));

        log.info("Limited adjustment factor: {} (range: {} - {})",
                String.format("%.2f", adjustmentFactor),
                String.format("%.2f", MIN_ADJUSTMENT_FACTOR),
                String.format("%.2f", MAX_ADJUSTMENT_FACTOR));

        // Calculate new target
        UInt256 currentTarget = chainStats.getBaseDifficultyTarget();
        BigInteger newTargetBigInt = currentTarget.toBigInteger()
                .multiply(BigInteger.valueOf((long)(adjustmentFactor * 1000)))
                .divide(BigInteger.valueOf(1000));

        UInt256 newTarget = UInt256.valueOf(newTargetBigInt);

        log.info("Difficulty adjusted: old target={}, new target={}, factor={}",
                currentTarget.toHexString().substring(0, 16) + "...",
                newTarget.toHexString().substring(0, 16) + "...",
                String.format("%.2f", adjustmentFactor));

        // Update chain stats
        chainStats = chainStats.toBuilder()
                .baseDifficultyTarget(newTarget)
                .lastDifficultyAdjustmentEpoch(currentEpoch)
                .build();
        dagStore.saveChainStats(chainStats);
    }

    /**
     * Notify listeners of new block
     */
    private void notifyListeners(Block block) {
        synchronized (listeners) {
            if (listeners.isEmpty()) {
                return;
            }

            for (Listener listener : listeners) {
                try {
                    byte[] blockBytes = block.toBytes();
                    listener.onMessage(new BlockMessage(Bytes.wrap(blockBytes), MessageType.NEW_LINK));
                    log.debug("Notified listener {} of new block: {}",
                             listener.getClass().getSimpleName(),
                             block.getHash().toHexString().substring(0, 16) + "...");
                } catch (Exception e) {
                    log.error("Error notifying listener {}: {}",
                             listener.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }

    // ==================== Block Creation Operations ====================

    @Override
    public Block createCandidateBlock() {
        log.info("Creating candidate block for mining");

        long timestamp = XdagTime.getCurrentTimestamp();
        long epoch = timestamp / 64;

        // Use baseDifficultyTarget from chain stats (NEW CONSENSUS)
        UInt256 difficultyTarget = chainStats.getBaseDifficultyTarget();
        if (difficultyTarget == null) {
            // Fallback for uninitialized chains
            difficultyTarget = INITIAL_BASE_DIFFICULTY_TARGET;
            log.warn("Base difficulty target not set, using initial value: {}",
                    difficultyTarget.toHexString().substring(0, 16) + "...");
        }

        Bytes coinbase = miningCoinbase;
        List<Link> links = collectCandidateLinks();

        Block candidateBlock = Block.createCandidate(timestamp, difficultyTarget, coinbase, links);

        log.info("Created mining candidate block: epoch={}, target={}, links={}, hash={}",
                epoch,
                difficultyTarget.toHexString().substring(0, 16) + "...",
                links.size(),
                candidateBlock.getHash().toHexString().substring(0, 16) + "...");

        return candidateBlock;
    }

    /**
     * Collect links for candidate block creation
     *
     * <p>Collects block references for mining candidate:
     * <ol>
     *   <li>Previous main block with epoch boundary check (matching C code logic)</li>
     *   <li>Recent orphan blocks (for network health and connectivity)</li>
     * </ol>
     *
     * <p>IMPORTANT: This implements XDAG's epoch-based link reference rules
     * matching C code (block.c:1028-1040). If the new block and current main block
     * are in the SAME epoch, we must reference the previous epoch's main block instead
     * to avoid same-epoch difficulty accumulation.
     *
     * @return list of links (1-16 block references)
     */
    private List<Link> collectCandidateLinks() {
        List<Link> links = new ArrayList<>();
        long timestamp = XdagTime.getCurrentTimestamp();
        long sendEpoch = timestamp / 64;

        long currentMainHeight = chainStats.getMainBlockCount();
        log.debug("Collecting candidate links: mainBlockCount={}, sendEpoch={}", currentMainHeight, sendEpoch);

        // Add previous main block reference (with epoch boundary check)
        if (currentMainHeight > 0) {
            Block prevMainBlock = dagStore.getMainBlockByHeight(currentMainHeight, false);

            if (prevMainBlock != null) {
                long prevEpoch = prevMainBlock.getEpoch();

                if (prevEpoch < sendEpoch) {
                    // Different epoch → Reference directly (C code: pretop = top_main_chain)
                    links.add(Link.toBlock(prevMainBlock.getHash()));
                    log.debug("Added main block reference: {} (epoch {}, different from send epoch {})",
                            prevMainBlock.getHash().toHexString().substring(0, 16), prevEpoch, sendEpoch);
                } else {
                    // Same epoch → Find previous epoch's main block (C code: pretop = pretop_main_chain)
                    Block preEpochMainBlock = findPreviousEpochMainBlock(prevMainBlock, sendEpoch);
                    if (preEpochMainBlock != null) {
                        links.add(Link.toBlock(preEpochMainBlock.getHash()));
                        log.debug("Added pre-epoch main block reference: {} (epoch {}, avoiding same-epoch reference)",
                                preEpochMainBlock.getHash().toHexString().substring(0, 16),
                                preEpochMainBlock.getEpoch());
                    } else {
                        log.warn("Could not find previous epoch main block, skipping main block reference");
                    }
                }
            }
        }

        // Add recent orphan blocks (as many as possible, up to block field limit)
        int maxOrphans = Block.MAX_BLOCK_LINKS - links.size() - 2;  // Reserve space for nonce and signatures
        if (maxOrphans > 0) {
            long[] sendTime = new long[2];
            sendTime[0] = timestamp;

            List<Bytes32> orphanHashes = orphanBlockStore.getOrphan(maxOrphans, sendTime);
            if (orphanHashes != null && !orphanHashes.isEmpty()) {
                for (Bytes32 orphanHash : orphanHashes) {
                    links.add(Link.toBlock(orphanHash));
                }
                log.debug("Added {} orphan block references", orphanHashes.size());
            }
        }

        log.info("Collected {} links for candidate block (epoch {})", links.size(), sendEpoch);
        return links;
    }

    /**
     * Find the closest main block from a previous epoch
     *
     * <p>Traverses the main chain backwards until finding a block in a different epoch.
     * This matches C code's pretop_main_chain logic (block.c:952).
     *
     * <p>Purpose: Prevent same-epoch difficulty accumulation by ensuring cross-epoch
     * references when building candidate blocks.
     *
     * @param currentBlock starting block (current main block)
     * @param currentEpoch current epoch to avoid
     * @return main block from previous epoch, or null if not found
     */
    private Block findPreviousEpochMainBlock(Block currentBlock, long currentEpoch) {
        if (currentBlock == null || currentBlock.getInfo() == null) {
            return null;
        }

        long currentHeight = currentBlock.getInfo().getHeight();
        int maxIterations = 100;  // Safety limit to prevent infinite loops

        // Traverse back through main chain by height
        for (int i = 1; i <= maxIterations && (currentHeight - i) > 0; i++) {
            long checkHeight = currentHeight - i;
            Block block = dagStore.getMainBlockByHeight(checkHeight, false);

            if (block != null) {
                long blockEpoch = block.getEpoch();

                if (blockEpoch < currentEpoch) {
                    // Found a block in previous epoch
                    log.debug("Found previous epoch main block: {} (height {}, epoch {}) after {} iterations",
                            block.getHash().toHexString().substring(0, 16), checkHeight, blockEpoch, i);
                    return block;
                }
            }
        }

        log.warn("Could not find previous epoch main block after {} iterations (starting from height {})",
                maxIterations, currentHeight);
        return null;
    }

    /**
     * Set mining coinbase address
     *
     * @param coinbase mining reward address (20 bytes)
     */
    public void setMiningCoinbase(Bytes coinbase) {
        // BUGFIX: Ensure coinbase is exactly 20 bytes
        // This prevents BufferOverflowException in Block.calculateHash()
        if (coinbase == null) {
            log.warn("Null coinbase provided, using zero address (20 bytes)");
            this.miningCoinbase = Bytes.wrap(new byte[20]);
            return;
        }

        if (coinbase.size() > 20) {
            // Truncate to 20 bytes
            log.warn("Coinbase address too long ({} bytes), truncating to 20 bytes: {} -> {}",
                    coinbase.size(),
                    coinbase.toHexString(),
                    coinbase.slice(0, 20).toHexString());
            this.miningCoinbase = coinbase.slice(0, 20);
        } else if (coinbase.size() < 20) {
            // Pad with zeros to 20 bytes
            byte[] padded = new byte[20];
            System.arraycopy(coinbase.toArray(), 0, padded, 0, coinbase.size());
            log.warn("Coinbase address too short ({} bytes), padding to 20 bytes: {} -> {}",
                    coinbase.size(),
                    coinbase.toHexString(),
                    Bytes.wrap(padded).toHexString());
            this.miningCoinbase = Bytes.wrap(padded);
        } else {
            // Exactly 20 bytes, use as-is
            this.miningCoinbase = coinbase;
            log.info("Mining coinbase address set: {}", coinbase.toHexString().substring(0, 16) + "...");
        }
    }

    @Override
    public Block createGenesisBlock(Bytes coinbase, long timestamp) {
        log.info("Creating deterministic genesis block at timestamp {}", timestamp);
        log.info("  - Coinbase: {}", coinbase.toHexString());

        // BUGFIX: Ensure coinbase is exactly 20 bytes (same as setMiningCoinbase)
        Bytes normalizedCoinbase = coinbase;
        if (coinbase == null) {
            log.warn("Null coinbase for genesis, using zero address (20 bytes)");
            normalizedCoinbase = Bytes.wrap(new byte[20]);
        } else if (coinbase.size() > 20) {
            log.warn("Genesis coinbase too long ({} bytes), truncating to 20 bytes", coinbase.size());
            normalizedCoinbase = coinbase.slice(0, 20);
        } else if (coinbase.size() < 20) {
            byte[] padded = new byte[20];
            System.arraycopy(coinbase.toArray(), 0, padded, 0, coinbase.size());
            log.warn("Genesis coinbase too short ({} bytes), padding to 20 bytes", coinbase.size());
            normalizedCoinbase = Bytes.wrap(padded);
        }

        Block genesisBlock = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                normalizedCoinbase,
                List.of()
        );

        log.info("✓ Deterministic genesis block created: hash={}, epoch={}",
                genesisBlock.getHash().toHexString(), genesisBlock.getEpoch());

        return genesisBlock;
    }

    // ==================== Main Chain Queries (Height-Based) ====================

    @Override
    public Block getMainBlockByHeight(long height) {
        return dagStore.getMainBlockByHeight(height, false);
    }

    @Override
    public long getMainChainLength() {
        return chainStats.getMainBlockCount();
    }

    @Override
    public long getEpochOfMainBlock(long height) {
        Block block = getMainBlockByHeight(height);
        if (block == null) {
            return -1;
        }
        return block.getEpoch();
    }

    @Override
    public List<Block> listMainBlocks(int count) {
        return dagStore.listMainBlocks(count);
    }

    @Override
    public List<Block> getMainChainPath(Bytes32 hash) {
        List<Block> path = new ArrayList<>();
        Block current = dagStore.getBlockByHash(hash, false);

        if (current == null || current.getInfo() == null || current.getInfo().getHeight() == 0) {
            throw new IllegalArgumentException("Block is not on main chain: " + hash.toHexString());
        }

        // Trace back to genesis
        while (current != null && current.getInfo() != null && current.getInfo().getHeight() > 0) {
            path.add(current);

            // Find parent with maximum cumulative difficulty
            Block parent = findMaxDifficultyParent(current);
            current = parent;
        }

        return path;
    }

    /**
     * Find parent block with maximum cumulative difficulty
     */
    private Block findMaxDifficultyParent(Block block) {
        Block maxDiffParent = null;
        UInt256 maxDiff = UInt256.ZERO;

        for (Link link : block.getLinks()) {
            if (link.isBlock()) {
                Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
                if (parent != null && parent.getInfo() != null) {
                    UInt256 parentDiff = parent.getInfo().getDifficulty();
                    if (parentDiff.compareTo(maxDiff) > 0) {
                        maxDiff = parentDiff;
                        maxDiffParent = parent;
                    }
                }
            }
        }

        return maxDiffParent;
    }

    // ==================== Epoch Queries (Time-Based) ====================

    @Override
    public long getCurrentEpoch() {
        return XdagTime.getCurrentTimestamp() / 64;
    }

    @Override
    public long[] getEpochTimeRange(long epoch) {
        // IMPORTANT: Time range must be [startTime, endTime) with EXCLUSIVE end
        // Blocks with timestamp = endTime belong to NEXT epoch!
        // Example: epoch 23693854 → [1516406656, 1516406720)
        //   timestamp 1516406656 belongs to epoch 23693854 ✓
        //   timestamp 1516406719 belongs to epoch 23693854 ✓
        //   timestamp 1516406720 belongs to epoch 23693855 ✗ (next epoch!)
        return new long[] { epoch * 64, (epoch + 1) * 64 };
    }

    @Override
    public List<Block> getCandidateBlocksInEpoch(long epoch) {
        long[] timeRange = getEpochTimeRange(epoch);
        List<Block> allBlocks = dagStore.getBlocksByTimeRange(timeRange[0], timeRange[1]);

        // IMPORTANT: Filter out blocks with timestamp >= endTime (they belong to next epoch)
        // This ensures we only get blocks that truly belong to this epoch
        List<Block> filtered = new ArrayList<>();
        for (Block block : allBlocks) {
            if (block.getTimestamp() < timeRange[1]) {
                filtered.add(block);
            }
        }

        return filtered;
    }

    @Override
    public Block getWinnerBlockInEpoch(long epoch) {
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);

        // FALLBACK: If time-range query returns empty, scan recent main blocks manually
        // This works around potential DagStore indexing/caching issues
        if (candidates.isEmpty() && chainStats.getMainBlockCount() > 0) {
            log.debug("Time-range query returned 0 blocks for epoch {}, using fallback scan", epoch);
            candidates = new ArrayList<>();

            // Scan last 100 main blocks for blocks in this epoch
            long scanStart = Math.max(1, chainStats.getMainBlockCount() - 100);
            log.debug("  Fallback scan range: height {} to {}", scanStart, chainStats.getMainBlockCount());

            for (long height = chainStats.getMainBlockCount(); height >= scanStart; height--) {
                Block block = dagStore.getMainBlockByHeight(height, false);
                if (block != null) {
                    long blockEpoch = block.getEpoch();
                    log.debug("    Height {}: block={}, epoch={}, match={}",
                            height,
                            block.getHash().toHexString().substring(0, 16),
                            blockEpoch,
                            blockEpoch == epoch);

                    if (blockEpoch == epoch) {
                        candidates.add(block);
                        log.debug("  ✓ Found block at height {} in epoch {} via fallback scan", height, epoch);
                    }
                }
            }

            if (!candidates.isEmpty()) {
                log.info("Fallback scan found {} blocks in epoch {} (time-range query failed)",
                        candidates.size(), epoch);
            } else {
                log.warn("Fallback scan found NO blocks in epoch {} (scanned heights {} to {})",
                        epoch, scanStart, chainStats.getMainBlockCount());
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // Find block with smallest hash that is not an orphan
        Block winner = null;
        Bytes32 smallestHash = null;

        for (Block candidate : candidates) {
            // Skip orphan blocks (height = 0)
            if (candidate.getInfo() != null && candidate.getInfo().getHeight() > 0) {
                if (smallestHash == null || candidate.getHash().compareTo(smallestHash) < 0) {
                    smallestHash = candidate.getHash();
                    winner = candidate;
                }
            }
        }

        return winner;
    }

    @Override
    public long getWinnerBlockHeight(long epoch) {
        Block winner = getWinnerBlockInEpoch(epoch);
        if (winner == null || winner.getInfo() == null) {
            return -1;
        }
        return winner.getInfo().getHeight();
    }

    @Override
    public EpochStats getEpochStats(long epoch) {
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);
        Block winner = getWinnerBlockInEpoch(epoch);

        // Calculate average block time
        double avgBlockTime = 0.0;
        if (!candidates.isEmpty()) {
            long[] timeRange = getEpochTimeRange(epoch);
            long epochDuration = timeRange[1] - timeRange[0];
            avgBlockTime = (double) epochDuration / candidates.size();
        }

        // Calculate total difficulty added in this epoch
        UInt256 totalDifficulty = UInt256.ZERO;
        for (Block candidate : candidates) {
            if (candidate.getInfo() != null) {
                UInt256 blockWork = calculateBlockWork(candidate.getHash());
                totalDifficulty = totalDifficulty.add(blockWork);
            }
        }

        return EpochStats.builder()
                .epoch(epoch)
                .startTime(epoch * 64)
                .endTime((epoch + 1) * 64)
                .totalBlocks(candidates.size())
                .winningBlockHash(winner != null ? winner.getHash() : null)
                .averageBlockTime(avgBlockTime)
                .totalDifficulty(totalDifficulty)
                .hasMainBlock(winner != null)
                .build();
    }

    // ==================== General Block Queries ====================

    @Override
    public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
        return dagStore.getBlockByHash(hash, isRaw);
    }

    @Override
    public List<Block> getBlocksByTimeRange(long startTime, long endTime) {
        return dagStore.getBlocksByTimeRange(startTime, endTime);
    }

    @Override
    public List<Block> listMinedBlocks(int count) {
        throw new UnsupportedOperationException("listMinedBlocks() not yet implemented");
    }

    @Override
    public Map<Bytes, Integer> getMemOurBlocks() {
        throw new UnsupportedOperationException("getMemOurBlocks() not yet implemented");
    }

    // ==================== Cumulative Difficulty ====================

    @Override
    public UInt256 calculateCumulativeDifficulty(Block block) {
        // IMPORTANT: This implements XDAG's epoch-based difficulty calculation
        // matching C code logic in block.c:724-735
        //
        // Rule: Blocks in the SAME epoch do NOT accumulate difficulty
        // Only cross-epoch references accumulate difficulty
        // This ensures fair competition within each epoch

        long blockEpoch = block.getEpoch();
        UInt256 maxParentDifficulty = UInt256.ZERO;

        for (Link link : block.getLinks()) {
            if (link.isBlock()) {
                Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
                if (parent != null && parent.getInfo() != null) {
                    long parentEpoch = parent.getEpoch();

                    if (parentEpoch < blockEpoch) {
                        // Case 1: Parent is in PREVIOUS epoch
                        // → Accumulate difficulty (C code: diff = xdag_diff_add(diff0, blockRef->difficulty))
                        UInt256 parentDifficulty = parent.getInfo().getDifficulty();
                        if (parentDifficulty.compareTo(maxParentDifficulty) > 0) {
                            maxParentDifficulty = parentDifficulty;
                        }
                    } else {
                        // Case 2: Parent is in SAME epoch
                        // → Do NOT accumulate (C code: diff = blockRef->difficulty, skip same-epoch blocks)
                        // Skip this parent and continue searching for cross-epoch references
                        log.debug("Skipping same-epoch parent {} (epoch {})",
                                parent.getHash().toHexString().substring(0, 16), parentEpoch);
                    }
                }
            }
        }

        // Calculate this block's work
        UInt256 blockWork = calculateBlockWork(block.getHash());

        // Cumulative difficulty = parent max difficulty (from previous epochs) + block work
        UInt256 cumulativeDifficulty = maxParentDifficulty.add(blockWork);

        log.debug("Calculated cumulative difficulty for block {} (epoch {}): parent={}, work={}, cumulative={}",
                 block.getHash().toHexString().substring(0, 16),
                 blockEpoch,
                 maxParentDifficulty.toDecimalString(),
                 blockWork.toDecimalString(),
                 cumulativeDifficulty.toDecimalString());

        return cumulativeDifficulty;
    }

    @Override
    public UInt256 calculateBlockWork(Bytes32 hash) {
        // XDAG philosophy: work = MAX_UINT256 / hash
        // Smaller hash = more work = higher difficulty

        BigInteger hashValue = new BigInteger(1, hash.toArray());

        if (hashValue.equals(BigInteger.ZERO)) {
            // Theoretically impossible, but handle gracefully
            log.warn("Block hash is zero, returning MAX_VALUE work");
            return UInt256.MAX_VALUE;
        }

        BigInteger maxUint256 = UInt256.MAX_VALUE.toBigInteger();
        BigInteger work = maxUint256.divide(hashValue);

        return UInt256.valueOf(work);
    }

    // ==================== DAG Structure Validation ====================

    @Override
    public DAGValidationResult validateDAGRules(Block block) {
        // Genesis block doesn't need DAG validation (it's the first block)
        if (isGenesisBlock(block)) {
            return DAGValidationResult.valid();
        }

        // Rule 1: Check for cycles
        if (hasCycle(block)) {
            return DAGValidationResult.invalid(
                    DAGValidationResult.DAGErrorCode.CYCLE_DETECTED,
                    "Block creates a cycle in DAG"
            );
        }

        // Rule 2: Check time window (12 days / 16384 epochs)
        // DEVNET: Skip time window validation for testing with old genesis blocks
        boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
        if (!isDevnet) {
            long currentEpoch = getCurrentEpoch();
            for (Link link : block.getLinks()) {
                if (link.isBlock()) {
                    Block refBlock = dagStore.getBlockByHash(link.getTargetHash(), false);
                    if (refBlock != null) {
                        long refEpoch = refBlock.getEpoch();
                        if (currentEpoch - refEpoch > 16384) {
                            return DAGValidationResult.invalid(
                                    DAGValidationResult.DAGErrorCode.TIME_WINDOW_VIOLATION,
                                    "Referenced block is too old (>" + (currentEpoch - refEpoch) + " epochs)"
                            );
                        }
                    }
                }
            }
        }

        // Rule 3: Check link count (1-16 block links)
        // DEVNET: Allow 0 links for first block when chain is empty
        long blockLinkCount = block.getLinks().stream()
                .filter(link -> !link.isTransaction())
                .count();

        boolean isFirstBlock = (blockLinkCount == 0 && chainStats.getMainBlockCount() == 0);
        if (!isFirstBlock && (blockLinkCount < 1 || blockLinkCount > 16)) {
            return DAGValidationResult.invalid(
                    DAGValidationResult.DAGErrorCode.INVALID_LINK_COUNT,
                    "Block must have 1-16 block links (found " + blockLinkCount + ")"
            );
        }

        // Rule 4: Check timestamp order (already checked in validateLinks)

        // Rule 5: Check traversal depth
        int depth = calculateDepthFromGenesis(block);
        if (depth > 1000) {
            return DAGValidationResult.invalid(
                    DAGValidationResult.DAGErrorCode.TRAVERSAL_DEPTH_EXCEEDED,
                    "Block depth from genesis exceeds 1000 layers (depth=" + depth + ")"
            );
        }

        return DAGValidationResult.valid();
    }

    /**
     * Check if adding this block creates a cycle in the DAG
     */
    private boolean hasCycle(Block block) {
        Set<Bytes32> visited = new HashSet<>();
        Set<Bytes32> recursionStack = new HashSet<>();

        return hasCycleDFS(block.getHash(), visited, recursionStack);
    }

    /**
     * DFS-based cycle detection
     */
    private boolean hasCycleDFS(Bytes32 currentHash, Set<Bytes32> visited, Set<Bytes32> recursionStack) {
        visited.add(currentHash);
        recursionStack.add(currentHash);

        Block current = dagStore.getBlockByHash(currentHash, false);
        if (current != null) {
            for (Link link : current.getLinks()) {
                if (link.isBlock()) {
                    Bytes32 childHash = link.getTargetHash();

                    if (!visited.contains(childHash)) {
                        if (hasCycleDFS(childHash, visited, recursionStack)) {
                            return true;
                        }
                    } else if (recursionStack.contains(childHash)) {
                        // Found a back edge (cycle)
                        return true;
                    }
                }
            }
        }

        recursionStack.remove(currentHash);
        return false;
    }

    /**
     * Calculate depth from genesis
     */
    private int calculateDepthFromGenesis(Block block) {
        int maxDepth = 0;

        for (Link link : block.getLinks()) {
            if (link.isBlock()) {
                Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
                if (parent != null) {
                    int parentDepth = calculateDepthFromGenesis(parent);
                    maxDepth = Math.max(maxDepth, parentDepth + 1);
                }
            }
        }

        return maxDepth;
    }

    @Override
    public boolean isBlockInMainChain(Bytes32 hash) {
        Block block = dagStore.getBlockByHash(hash, false);

        if (block == null || block.getInfo() == null) {
            return false;
        }

        // Check if it's a main block (height > 0)
        if (block.getInfo().getHeight() > 0) {
            return true;
        }

        // Check if it's referenced by a main block
        List<Block> references = getBlockReferences(hash);
        for (Block ref : references) {
            if (isBlockInMainChain(ref.getHash())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<Block> getBlockReferences(Bytes32 hash) {
        // DagStore returns List<Bytes32> (hashes), need to convert to List<Block>
        List<Bytes32> hashes = dagStore.getBlockReferences(hash);
        return hashes.stream()
                .map(h -> dagStore.getBlockByHash(h, false))
                .filter(block -> block != null)
                .collect(Collectors.toList());
    }

    // ==================== Chain Management ====================

    @Override
    public synchronized void checkNewMain() {
        log.debug("Running checkNewMain() - scanning recent epochs for winners");

        long currentEpoch = getCurrentEpoch();
        long scanStartEpoch = Math.max(1, currentEpoch - 100);  // Scan last 100 epochs

        // Scan recent epochs and determine winners
        for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
            checkEpochWinner(epoch);
        }

        // Check for chain reorganization
        checkChainReorganization();

        // Save updated chain stats
        dagStore.saveChainStats(chainStats);

        log.debug("checkNewMain() completed: mainBlockCount={}, maxDifficulty={}",
                 chainStats.getMainBlockCount(), chainStats.getMaxDifficulty().toDecimalString());
    }

    /**
     * Check and update winner for a specific epoch
     */
    private void checkEpochWinner(long epoch) {
        List<Block> candidates = getCandidateBlocksInEpoch(epoch);

        if (candidates.isEmpty()) {
            return;  // Empty epoch
        }

        // Find block with smallest hash
        Block winner = null;
        Bytes32 smallestHash = null;

        for (Block candidate : candidates) {
            if (smallestHash == null || candidate.getHash().compareTo(smallestHash) < 0) {
                smallestHash = candidate.getHash();
                winner = candidate;
            }
        }

        if (winner != null && winner.getInfo() != null && winner.getInfo().getHeight() == 0) {
            // Winner is currently an orphan, need to promote it
            log.debug("Promoting block {} to main block for epoch {}",
                     winner.getHash().toHexString(), epoch);
            promoteToMainBlock(winner);
        }
    }

    /**
     * Promote a block to main block
     */
    private void promoteToMainBlock(Block block) {
        // Calculate new height
        long newHeight = chainStats.getMainBlockCount() + 1;

        // Update BlockInfo
        BlockInfo updatedInfo = block.getInfo().toBuilder()
                .height(newHeight)
                .build();

        dagStore.saveBlockInfo(updatedInfo);

        // Update chain stats
        chainStats = chainStats
                .withMainBlockCount(newHeight)
                .withMaxDifficulty(updatedInfo.getDifficulty())
                .withTopBlock(block.getHash())
                .withTopDifficulty(updatedInfo.getDifficulty());

        log.info("Promoted block {} to main block at height {}",
                block.getHash().toHexString(), newHeight);
    }

    /**
     * Check for chain reorganization
     * <p>
     * This method performs a comprehensive check for potential chain reorganization scenarios:
     * <ol>
     *   <li>Scan orphan blocks for higher cumulative difficulty forks</li>
     *   <li>Verify main chain consistency (no gaps in height sequence)</li>
     *   <li>Detect and resolve fork points if better chain exists</li>
     * </ol>
     * <p>
     * Note: Most reorganization is handled incrementally in tryToConnect().
     * This method handles edge cases and performs validation.
     */
    private synchronized void checkChainReorganization() {
        if (chainStats.getMainBlockCount() == 0) {
            return;  // Empty chain, nothing to reorganize
        }

        log.debug("Checking for chain reorganization (current main chain length: {})",
                chainStats.getMainBlockCount());

        // Step 1: Scan recent epochs for orphan blocks with high cumulative difficulty
        long currentEpoch = getCurrentEpoch();
        long scanStartEpoch = Math.max(1, currentEpoch - 100);  // Scan last 100 epochs

        List<Block> potentialForkHeads = new ArrayList<>();
        UInt256 currentMaxDifficulty = chainStats.getMaxDifficulty();

        for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
            List<Block> candidates = getCandidateBlocksInEpoch(epoch);

            for (Block block : candidates) {
                // Find orphan blocks (height=0) with high cumulative difficulty
                if (block.getInfo() != null && block.getInfo().getHeight() == 0) {
                    UInt256 blockDifficulty = block.getInfo().getDifficulty();

                    // If orphan block has higher cumulative difficulty than current main chain
                    if (blockDifficulty.compareTo(currentMaxDifficulty) > 0) {
                        potentialForkHeads.add(block);
                        log.warn("Found orphan block {} with higher difficulty ({}) than main chain ({})",
                                block.getHash().toHexString().substring(0, 16),
                                blockDifficulty.toDecimalString(),
                                currentMaxDifficulty.toDecimalString());
                    }
                }
            }
        }

        // Step 2: If better forks found, trigger reorganization
        if (!potentialForkHeads.isEmpty()) {
            log.warn("Found {} orphan blocks with higher cumulative difficulty, investigating...",
                    potentialForkHeads.size());

            // Sort by cumulative difficulty (descending)
            potentialForkHeads.sort((b1, b2) ->
                b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

            Block bestForkHead = potentialForkHeads.getFirst();

            log.info("Best fork head: {} (difficulty: {})",
                    bestForkHead.getHash().toHexString(),
                    bestForkHead.getInfo().getDifficulty().toDecimalString());

            // Find fork point and perform reorganization
            performChainReorganization(bestForkHead);
        }

        // Step 3: Verify main chain consistency
        verifyMainChainConsistency();

        log.debug("Chain reorganization check completed");
    }

    /**
     * Perform chain reorganization to switch to a better fork
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Find the fork point (common ancestor)</li>
     *   <li>Demote blocks on current main chain after fork point</li>
     *   <li>Promote blocks on new fork to main chain</li>
     *   <li>Update chain statistics</li>
     * </ol>
     *
     * @param newForkHead the head block of the new (better) fork
     */
    private synchronized void performChainReorganization(Block newForkHead) {
        log.warn("CHAIN REORGANIZATION: Switching to fork with head {}",
                newForkHead.getHash().toHexString());

        // Step 1: Find fork point
        Block forkPoint = findForkPoint(newForkHead);

        if (forkPoint == null) {
            log.error("Cannot find fork point for reorganization, aborting");
            return;
        }

        long forkHeight = forkPoint.getInfo().getHeight();
        log.info("Fork point found at height {} (block: {})",
                forkHeight, forkPoint.getHash().toHexString().substring(0, 16));

        // Step 2: Demote blocks on current main chain after fork point
        List<Block> demotedBlocks = demoteBlocksAfterHeight(forkHeight);
        log.info("Demoted {} blocks from old main chain", demotedBlocks.size());

        // Step 3: Build new main chain path from fork point to new head
        List<Block> newMainChainBlocks = buildChainPath(newForkHead, forkPoint);

        if (newMainChainBlocks.isEmpty()) {
            log.error("Failed to build new main chain path, aborting reorganization");
            // Restore demoted blocks
            for (Block block : demotedBlocks) {
                promoteToMainBlock(block);
            }
            return;
        }

        log.info("New main chain has {} blocks from fork point to new head",
                newMainChainBlocks.size());

        // Step 4: Promote blocks on new fork (from fork point + 1 to new head)
        long currentHeight = forkHeight;
        for (int i = newMainChainBlocks.size() - 1; i >= 0; i--) {
            Block block = newMainChainBlocks.get(i);

            // Skip fork point itself (already on main chain)
            if (block.getHash().equals(forkPoint.getHash())) {
                continue;
            }

            currentHeight++;
            promoteBlockToHeight(block, currentHeight);
        }

        // Step 5: Update chain statistics
        Block newTip = newMainChainBlocks.getFirst();  // First element is the new head
        chainStats = chainStats
                .withMainBlockCount(currentHeight)
                .withMaxDifficulty(newTip.getInfo().getDifficulty())
                .withDifficulty(newTip.getInfo().getDifficulty())
                .withTopBlock(newTip.getHash())
                .withTopDifficulty(newTip.getInfo().getDifficulty());

        dagStore.saveChainStats(chainStats);

        log.warn("CHAIN REORGANIZATION COMPLETE: New main chain length = {}, new tip = {}",
                currentHeight, newTip.getHash().toHexString().substring(0, 16));
    }

    /**
     * Find the fork point (common ancestor) between new fork and current main chain
     *
     * @param forkHead head block of the new fork
     * @return the fork point block, or null if not found
     */
    private Block findForkPoint(Block forkHead) {
        Set<Bytes32> visited = new HashSet<>();
        Block current = forkHead;

        // Traverse back from fork head until we find a main block
        while (current != null) {
            Bytes32 currentHash = current.getHash();

            // Prevent infinite loop
            if (visited.contains(currentHash)) {
                log.warn("Cycle detected while finding fork point");
                return null;
            }
            visited.add(currentHash);

            // Check if this block is on main chain (height > 0)
            if (current.getInfo() != null && current.getInfo().getHeight() > 0) {
                log.debug("Fork point found: {} at height {}",
                        currentHash.toHexString().substring(0, 16),
                        current.getInfo().getHeight());
                return current;
            }

            // Move to parent with highest cumulative difficulty
            current = findMaxDifficultyParent(current);
        }

        log.error("Could not find fork point (reached genesis)");
        return null;
    }

    /**
     * Build chain path from fork head back to fork point
     *
     * @param forkHead the head of the fork
     * @param forkPoint the common ancestor
     * @return list of blocks from forkHead to forkPoint (inclusive, ordered head-first)
     */
    private List<Block> buildChainPath(Block forkHead, Block forkPoint) {
        List<Block> path = new ArrayList<>();
        Set<Bytes32> visited = new HashSet<>();
        Block current = forkHead;

        while (current != null) {
            // Prevent infinite loop
            if (visited.contains(current.getHash())) {
                log.error("Cycle detected while building chain path");
                return new ArrayList<>();  // Return empty list on error
            }
            visited.add(current.getHash());

            path.add(current);

            // Stop when we reach fork point
            if (current.getHash().equals(forkPoint.getHash())) {
                break;
            }

            // Move to parent with highest cumulative difficulty
            current = findMaxDifficultyParent(current);
        }

        return path;
    }

    /**
     * Demote all blocks on main chain after the specified height
     *
     * @param afterHeight demote blocks with height > afterHeight
     * @return list of demoted blocks
     */
    private List<Block> demoteBlocksAfterHeight(long afterHeight) {
        List<Block> demoted = new ArrayList<>();
        long currentHeight = chainStats.getMainBlockCount();

        // Demote from highest to afterHeight+1
        for (long height = currentHeight; height > afterHeight; height--) {
            Block block = dagStore.getMainBlockByHeight(height, false);
            if (block != null) {
                demoteBlockToOrphan(block);
                demoted.add(block);
                log.debug("Demoted block {} from height {}",
                        block.getHash().toHexString().substring(0, 16), height);
            }
        }

        return demoted;
    }

    /**
     * Promote a block to main chain at specific height
     *
     * @param block the block to promote
     * @param height the height to assign
     */
    private void promoteBlockToHeight(Block block, long height) {
        BlockInfo updatedInfo = block.getInfo().toBuilder()
                .height(height)
                .build();

        dagStore.saveBlockInfo(updatedInfo);

        Block updatedBlock = block.toBuilder().info(updatedInfo).build();
        dagStore.saveBlock(updatedBlock);

        log.debug("Promoted block {} to height {}",
                block.getHash().toHexString().substring(0, 16), height);
    }

    /**
     * Verify main chain consistency
     * <p>
     * Checks that:
     * <ol>
     *   <li>Height sequence is continuous (no gaps)</li>
     *   <li>Each block's cumulative difficulty >= parent's difficulty</li>
     *   <li>Top block matches chain stats</li>
     * </ol>
     */
    private void verifyMainChainConsistency() {
        long mainBlockCount = chainStats.getMainBlockCount();

        if (mainBlockCount == 0) {
            return;  // Empty chain is trivially consistent
        }

        // Check height sequence
        for (long height = 1; height <= mainBlockCount; height++) {
            Block block = dagStore.getMainBlockByHeight(height, false);
            if (block == null) {
                log.error("INCONSISTENCY: Missing main block at height {}", height);
                return;
            }

            if (block.getInfo() == null || block.getInfo().getHeight() != height) {
                log.error("INCONSISTENCY: Block at height {} has wrong height info: {}",
                        height, block.getInfo() != null ? block.getInfo().getHeight() : "null");
                return;
            }
        }

        // Verify top block
        Block topBlock = dagStore.getMainBlockByHeight(mainBlockCount, false);
        if (topBlock == null) {
            log.error("INCONSISTENCY: Top block at height {} not found", mainBlockCount);
            return;
        }

        if (chainStats.getTopBlock() != null &&
            !chainStats.getTopBlock().equals(topBlock.getHash())) {
            log.warn("INCONSISTENCY: Chain stats top block mismatch (expected: {}, actual: {})",
                    chainStats.getTopBlock().toHexString().substring(0, 16),
                    topBlock.getHash().toHexString().substring(0, 16));
        }

        log.debug("Main chain consistency verified: {} blocks, no gaps",
                mainBlockCount);
    }

    @Override
    public void startCheckMain(long period) {
        throw new UnsupportedOperationException("startCheckMain() not yet implemented");
    }

    @Override
    public void stopCheckMain() {
        throw new UnsupportedOperationException("stopCheckMain() not yet implemented");
    }

    // ==================== Statistics and State ====================

    @Override
    public ChainStats getChainStats() {
        return this.chainStats;
    }

    @Override
    public void incrementWaitingSyncCount() {
        synchronized (this) {
            chainStats = chainStats.withWaitingSyncCount(chainStats.getWaitingSyncCount() + 1);
            log.debug("Incremented waiting sync count to: {}", chainStats.getWaitingSyncCount());
        }
    }

    @Override
    public void decrementWaitingSyncCount() {
        synchronized (this) {
            chainStats = chainStats.withWaitingSyncCount(chainStats.getWaitingSyncCount() - 1);
            log.debug("Decremented waiting sync count to: {}", chainStats.getWaitingSyncCount());
        }
    }

    @Override
    public void updateStatsFromRemote(ChainStats remoteStats) {
        synchronized (this) {
            // Update total hosts (take maximum)
            int maxHosts = Math.max(chainStats.getTotalHostCount(), remoteStats.getTotalHostCount());

            // Update total blocks (take maximum)
            long maxBlocks = Math.max(chainStats.getTotalBlockCount(), remoteStats.getTotalBlockCount());

            // Update total main blocks (take maximum)
            long maxMain = Math.max(chainStats.getTotalMainBlockCount(), remoteStats.getTotalMainBlockCount());

            // Update max difficulty (take maximum)
            UInt256 localMaxDiff = chainStats.getMaxDifficulty();
            UInt256 remoteMaxDiff = remoteStats.getMaxDifficulty();
            UInt256 newMaxDiff = localMaxDiff.compareTo(remoteMaxDiff) > 0 ? localMaxDiff : remoteMaxDiff;

            // Apply updates
            chainStats = chainStats
                    .withTotalHostCount(maxHosts)
                    .withTotalBlockCount(maxBlocks)
                    .withTotalMainBlockCount(maxMain)
                    .withMaxDifficulty(newMaxDiff);

            log.debug("Updated stats from remote: hosts={}, blocks={}, main={}, maxDiff={}",
                     maxHosts, maxBlocks, maxMain, newMaxDiff.toDecimalString());
        }
    }

    // ==================== Economic Model ====================

    @Override
    public XAmount getReward(long nmain) {
        throw new UnsupportedOperationException("getReward() not yet implemented");
    }

    @Override
    public XAmount getSupply(long nmain) {
        throw new UnsupportedOperationException("getSupply() not yet implemented");
    }

    // ==================== Lifecycle Management ====================

    @Override
    public void registerListener(Listener listener) {
        synchronized (listeners) {
            listeners.add(listener);
            log.debug("Registered listener: {}", listener.getClass().getSimpleName());
        }
    }

    @Override
    public byte[] getPreSeed() {
        throw new UnsupportedOperationException("getPreSeed() not yet implemented");
    }

    /**
     * Retry orphan blocks that may now have satisfied dependencies
     *
     * <p>After successfully importing a block, some orphan blocks may now have all
     * their dependencies satisfied. This method retrieves orphan blocks from the queue
     * and attempts to import them again.
     */
    private void retryOrphanBlocks() {
        // Prevent recursive retry (avoid infinite loop)
        if (isRetryingOrphans.get()) {
            return;
        }

        try {
            isRetryingOrphans.set(true);

            // Keep retrying until no more orphans can be imported (cascading retry for chain dependencies)
            int totalSuccessCount = 0;
            int pass = 1;
            boolean madeProgress;

            do {
                madeProgress = false;
                long[] sendTime = new long[2];
                sendTime[0] = Long.MAX_VALUE;  // No timestamp filtering - retry all orphans

                // Get up to 100 orphan blocks to retry
                List<Bytes32> orphanHashes = orphanBlockStore.getOrphan(100, sendTime);

                if (orphanHashes == null || orphanHashes.isEmpty()) {
                    break;  // No orphans to retry
                }

                log.debug("Orphan retry pass {}: {} orphans to process", pass, orphanHashes.size());

                int successCount = 0;
                int failCount = 0;

                for (Bytes32 orphanHash : orphanHashes) {
                    try {
                        // Retrieve orphan block from DagStore
                        Block orphanBlock = dagStore.getBlockByHash(orphanHash, false);
                        if (orphanBlock == null) {
                            log.warn("Orphan block {} not found in DagStore", orphanHash.toHexString().substring(0, 16));
                            orphanBlockStore.deleteByHash(orphanHash.toArray());
                            continue;
                        }

                        // Attempt to import
                        DagImportResult result = tryToConnect(orphanBlock);

                        if (result.isMainBlock() || result.isOrphan()) {
                            successCount++;
                            totalSuccessCount++;
                            madeProgress = true;
                            // Successfully imported - remove from orphan queue
                            orphanBlockStore.deleteByHash(orphanHash.toArray());
                            log.debug("Successfully imported orphan block {} (status: {})",
                                    orphanHash.toHexString().substring(0, 16), result.getStatus());
                        } else if (result.getStatus() == DagImportResult.ImportStatus.MISSING_DEPENDENCY) {
                            failCount++;
                            // Still missing dependencies, keep in queue
                        } else if (result.getStatus() == DagImportResult.ImportStatus.DUPLICATE) {
                            // Already imported, remove from queue
                            orphanBlockStore.deleteByHash(orphanHash.toArray());
                        } else {
                            failCount++;
                            log.warn("Orphan block {} import failed: {}",
                                    orphanHash.toHexString().substring(0, 16), result.getStatus());
                        }

                    } catch (Exception e) {
                        failCount++;
                        log.error("Error retrying orphan block {}: {}",
                                orphanHash.toHexString().substring(0, 16), e.getMessage());
                    }
                }

                if (successCount > 0) {
                    log.info("Orphan retry pass {} completed: {} succeeded, {} still pending",
                            pass, successCount, failCount);
                }

                pass++;
            } while (madeProgress && pass <= 10);  // Max 10 passes to prevent infinite loop

            if (totalSuccessCount > 0) {
                log.info("Orphan block retry completed: {} total blocks imported across {} passes",
                        totalSuccessCount, pass - 1);
            }

        } finally {
            isRetryingOrphans.set(false);
        }
    }

    /**
     * Demote a block from main chain to orphan status
     *
     * <p>This is called during epoch competition when a new winner emerges.
     * The previous epoch winner must be demoted to orphan (height=0) to maintain
     * consensus rule: only one main block per epoch.
     *
     * <p>When a block is demoted, we must also adjust the chain stats to ensure
     * the next main block gets the correct height. The mainBlockCount is decremented
     * so that the new winner can take the demoted block's height.
     *
     * @param block the block to demote
     */
    private synchronized void demoteBlockToOrphan(Block block) {
        if (block == null || block.getInfo() == null) {
            log.warn("Attempted to demote null block or block without info");
            return;
        }

        long previousHeight = block.getInfo().getHeight();
        if (previousHeight == 0) {
            log.debug("Block {} is already an orphan, skipping demotion",
                    block.getHash().toHexString());
            return;
        }

        // Rollback transaction executions (Phase 1 - Task 1.4)
        // When a block is demoted, unmark all its transactions as executed
        // so they can be re-executed if the block becomes main again
        rollbackBlockTransactions(block);

        // Update BlockInfo to mark as orphan (height = 0)
        BlockInfo updatedInfo = block.getInfo().toBuilder()
                .height(0)
                .build();

        dagStore.saveBlockInfo(updatedInfo);

        // IMPORTANT: Also save the Block with updated info to update cache and indices
        // This ensures that getBlockByHash() and time-range queries return the updated block
        Block updatedBlock = block.toBuilder().info(updatedInfo).build();
        dagStore.saveBlock(updatedBlock);

        // NOTE: We do NOT modify mainBlockCount here!
        // The mainBlockCount should only be updated by updateChainStatsForNewMainBlock()
        // using Math.max(currentCount, newBlockHeight) to ensure correctness during replacements.
        // Decrementing here would break the chain when demoting non-tail blocks.

        log.info("Demoted block {} from height {} to orphan (epoch competition loser)",
                block.getHash().toHexString(), previousHeight);
    }

    /**
     * Rollback transaction executions for a demoted block (Phase 1 - Task 1.4)
     *
     * <p>When a block is demoted from main chain to orphan status (e.g., during chain
     * reorganization or epoch competition), all transactions executed by that block
     * must be unmarked AND their state changes must be reverted.
     *
     * <p>This is critical for:
     * <ul>
     *   <li>Chain reorganization: When blocks are demoted, their state changes must be reverted</li>
     *   <li>Epoch competition: When a block loses to a new winner with smaller hash</li>
     *   <li>Transaction replay: Allows transactions to be re-executed in a different block</li>
     * </ul>
     *
     * <p>Rollback operations for each transaction:
     * <ol>
     *   <li>Sender: Restore balance (refund amount + fee), decrement nonce</li>
     *   <li>Receiver: Deduct balance (remove received amount)</li>
     *   <li>Unmark transaction as executed</li>
     * </ol>
     *
     * @param block the block being demoted
     */
    private void rollbackBlockTransactions(Block block) {
        try {
            // Get all transaction hashes in this block
            List<Bytes32> txHashes = transactionStore.getTransactionHashesByBlock(block.getHash());

            if (txHashes.isEmpty()) {
                log.debug("No transactions to rollback for block {}",
                        block.getHash().toHexString().substring(0, 16));
                return;
            }

            // Get account manager for state rollback
            DagAccountManager accountManager = dagKernel.getDagAccountManager();
            if (accountManager == null) {
                log.error("Cannot rollback transactions: DagAccountManager not available");
                return;
            }

            // Rollback each transaction
            int rolledBackCount = 0;
            for (Bytes32 txHash : txHashes) {
                try {
                    // Load transaction
                    Transaction tx = transactionStore.getTransaction(txHash);
                    if (tx == null) {
                        log.warn("Transaction {} not found in store, skipping rollback",
                                txHash.toHexString().substring(0, 16));
                        continue;
                    }

                    // Rollback account state changes
                    rollbackTransactionState(tx, accountManager);

                    // Unmark transaction as executed
                    transactionStore.unmarkTransactionExecuted(txHash);

                    rolledBackCount++;

                    if (log.isDebugEnabled()) {
                        log.debug("Rolled back transaction {} from block {}",
                                txHash.toHexString().substring(0, 16),
                                block.getHash().toHexString().substring(0, 16));
                    }

                } catch (Exception e) {
                    log.error("Failed to rollback transaction {}: {}",
                            txHash.toHexString().substring(0, 16), e.getMessage());
                }
            }

            log.info("Rolled back {} transactions (state + execution status) for demoted block {}",
                    rolledBackCount, block.getHash().toHexString().substring(0, 16));

        } catch (Exception e) {
            log.error("Failed to rollback transactions for block {}: {}",
                    block.getHash().toHexString().substring(0, 16), e.getMessage());
        }
    }

    /**
     * Rollback account state changes for a single transaction
     *
     * <p>Reverses the state changes applied during transaction execution:
     * <ul>
     *   <li>Sender: Restore balance (refund amount + fee), decrement nonce</li>
     *   <li>Receiver: Deduct balance (remove received amount)</li>
     * </ul>
     *
     * @param tx transaction to rollback
     * @param accountManager account manager for state operations
     */
    private void rollbackTransactionState(Transaction tx, DagAccountManager accountManager) {
        // Convert XAmount to UInt256 (nano units)
        UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, XUnit.NANO_XDAG).longValue());
        UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, XUnit.NANO_XDAG).longValue());

        // Rollback sender account: refund amount + fee, decrement nonce
        try {
            accountManager.addBalance(tx.getFrom(), txAmount.add(txFee));
            accountManager.decrementNonce(tx.getFrom());

            if (log.isDebugEnabled()) {
                log.debug("Rolled back sender {}: refunded {}, decremented nonce",
                        tx.getFrom().toHexString().substring(0, 8),
                        txAmount.add(txFee).toDecimalString());
            }
        } catch (Exception e) {
            log.error("Failed to rollback sender account {}: {}",
                    tx.getFrom().toHexString().substring(0, 8), e.getMessage());
        }

        // Rollback receiver account: deduct amount
        try {
            UInt256 receiverBalance = accountManager.getBalance(tx.getTo());
            if (receiverBalance.compareTo(txAmount) >= 0) {
                accountManager.subtractBalance(tx.getTo(), txAmount);

                if (log.isDebugEnabled()) {
                    log.debug("Rolled back receiver {}: deducted {}",
                            tx.getTo().toHexString().substring(0, 8),
                            txAmount.toDecimalString());
                }
            } else {
                // This shouldn't happen in normal operation
                log.warn("Cannot fully rollback receiver {}: insufficient balance ({} < {})",
                        tx.getTo().toHexString().substring(0, 8),
                        receiverBalance.toDecimalString(),
                        txAmount.toDecimalString());
            }
        } catch (Exception e) {
            log.error("Failed to rollback receiver account {}: {}",
                    tx.getTo().toHexString().substring(0, 8), e.getMessage());
        }
    }

    // ==================== DAG Chain Event Listeners ====================

    @Override
    public void addListener(DagchainListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("DagchainListener cannot be null");
        }

        synchronized (dagchainListeners) {
            if (!dagchainListeners.contains(listener)) {
                dagchainListeners.add(listener);
                log.debug("Added DAG chain listener: {}", listener.getClass().getSimpleName());
            } else {
                log.warn("Attempted to add duplicate listener: {}", listener.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void removeListener(DagchainListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (dagchainListeners) {
            boolean removed = dagchainListeners.remove(listener);
            if (removed) {
                log.debug("Removed DAG chain listener: {}", listener.getClass().getSimpleName());
            }
        }
    }

    /**
     * Notify all DAG chain listeners of block connection
     *
     * <p>This method is called after a block is successfully imported
     * and added to the main chain. It notifies all registered listeners
     * so they can react to the chain state change.
     *
     * <p>Listener exceptions are caught and logged to prevent one failing
     * listener from affecting others.
     *
     * @param block The block that was connected to the main chain
     */
    private void notifyDagchainListeners(Block block) {
        synchronized (dagchainListeners) {
            if (dagchainListeners.isEmpty()) {
                return;
            }

            log.debug("Notifying {} DAG chain listeners of block connection: {}",
                    dagchainListeners.size(),
                    block.getHash().toHexString().substring(0, 16));

            for (DagchainListener listener : dagchainListeners) {
                try {
                    listener.onBlockConnected(block);
                } catch (Exception e) {
                    log.error("Error notifying DAG chain listener {}: {}",
                            listener.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }
}