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
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.DagStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.TransactionStore;
import io.xdag.db.store.DagEntityResolver;
import io.xdag.db.store.ResolvedLinks;
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
 * DagChain implementation for XDAG v5.1 epoch-based DAG consensus
 *
 * <p>Implements epoch-based consensus with cumulative difficulty calculation.
 * Uses DagKernel exclusively for all storage operations.
 *
 * @since v5.1
 */
@Slf4j
public class DagChainImpl implements DagChain {

    private final DagKernel dagKernel;
    private final DagStore dagStore;
    private final DagEntityResolver entityResolver;
    private final OrphanBlockStore orphanBlockStore;
    private final TransactionStore transactionStore;

    private volatile ChainStats chainStats;
    private final List<Listener> listeners = new ArrayList<>();
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
            this.chainStats = ChainStats.builder()
                    .mainBlockCount(0)
                    .maxDifficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                    .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
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

            // Process block transactions and update account state
            if (dagKernel != null) {
                DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
                if (blockProcessor != null) {
                    DagBlockProcessor.ProcessingResult processResult =
                            blockProcessor.processBlock(blockWithInfo);

                    if (!processResult.isSuccess()) {
                        log.warn("Block {} transaction processing failed: {}",
                                block.getHash().toHexString(), processResult.getError());
                    } else {
                        log.debug("Block {} transactions processed successfully",
                                block.getHash().toHexString());
                    }
                }
            }

            // Index transactions
            indexTransactions(block);

            // Update chain statistics
            if (isBestChain) {
                updateChainStatsForNewMainBlock(blockInfo);
            }

            // Notify listeners
            notifyListeners(blockWithInfo);

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
                    listener.onMessage(new io.xdag.listener.BlockMessage(
                            org.apache.tuweni.bytes.Bytes.wrap(blockBytes),
                            io.xdag.config.Constants.MessageType.NEW_LINK
                    ));
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

        UInt256 difficulty = chainStats.getDifficulty();
        if (difficulty == null || difficulty.isZero()) {
            difficulty = UInt256.ONE;
        }

        // DEVNET ONLY: Use maximum difficulty target for fast testing
        boolean isDevnet = dagKernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
        if (isDevnet) {
            difficulty = UInt256.MAX_VALUE;
            log.warn("⚠ DEVNET TEST MODE: Using maximum difficulty target (MAX) for easy PoW validation");
        }

        Bytes coinbase = miningCoinbase;

        List<Link> links = collectCandidateLinks();

        Block candidateBlock = Block.createCandidate(timestamp, difficulty, coinbase, links);

        log.info("Created mining candidate block: epoch={}, difficulty={}, links={}, hash={}",
                epoch,
                difficulty.toDecimalString(),
                links.size(),
                candidateBlock.getHash().toHexString().substring(0, 16) + "...");

        return candidateBlock;
    }

    /**
     * Collect links for candidate block creation
     *
     * <p>Collects block references for mining candidate:
     * <ol>
     *   <li>Previous main block (parent with highest cumulative difficulty)</li>
     *   <li>Recent orphan blocks (for network health and connectivity)</li>
     * </ol>
     *
     * @return list of links (1-16 block references)
     */
    private List<Link> collectCandidateLinks() {
        List<Link> links = new ArrayList<>();

        long currentMainHeight = chainStats.getMainBlockCount();
        log.debug("Collecting candidate links: mainBlockCount={}", currentMainHeight);

        if (currentMainHeight > 0) {
            Block prevMainBlock = dagStore.getMainBlockByHeight(currentMainHeight, false);
            if (prevMainBlock != null) {
                links.add(Link.toBlock(prevMainBlock.getHash()));
                log.debug("Added prevMainBlock reference: height={}, hash={}",
                        currentMainHeight, prevMainBlock.getHash().toHexString().substring(0, 16) + "...");
            }
        }

        int maxOrphans = Block.MAX_BLOCK_LINKS - links.size();
        if (maxOrphans > 0) {
            long timestamp = XdagTime.getCurrentTimestamp();
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

        log.debug("Collected {} candidate links", links.size());
        return links;
    }

    /**
     * Set mining coinbase address
     *
     * @param coinbase mining reward address (20 bytes)
     */
    public void setMiningCoinbase(Bytes coinbase) {
        this.miningCoinbase = coinbase;
        log.info("Mining coinbase address set: {}", coinbase.toHexString().substring(0, 16) + "...");
    }

    @Override
    public Block createGenesisBlock(Bytes coinbase, long timestamp) {
        log.info("Creating deterministic genesis block at timestamp {}", timestamp);
        log.info("  - Coinbase: {}", coinbase.toHexString());

        Block genesisBlock = Block.createWithNonce(
                timestamp,
                UInt256.ONE,
                Bytes32.ZERO,
                coinbase,
                List.of()
        );

        log.info("✓ Deterministic genesis block created: hash={}, epoch={}",
                genesisBlock.getHash().toHexString(), genesisBlock.getEpoch());

        return genesisBlock;
    }

    @Override
    public Block createRewardBlock(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee) {
        throw new UnsupportedOperationException("createRewardBlock() not yet implemented");
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
        // Find parent block with maximum cumulative difficulty
        UInt256 maxParentDifficulty = UInt256.ZERO;

        for (Link link : block.getLinks()) {
            if (link.isBlock()) {
                Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
                if (parent != null && parent.getInfo() != null) {
                    UInt256 parentDifficulty = parent.getInfo().getDifficulty();
                    if (parentDifficulty.compareTo(maxParentDifficulty) > 0) {
                        maxParentDifficulty = parentDifficulty;
                    }
                }
            }
        }

        // Calculate this block's work
        UInt256 blockWork = calculateBlockWork(block.getHash());

        // Cumulative difficulty = parent max difficulty + block work
        UInt256 cumulativeDifficulty = maxParentDifficulty.add(blockWork);

        log.debug("Calculated cumulative difficulty for block {}: parent={}, work={}, cumulative={}",
                 block.getHash().toHexString(),
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
     */
    private void checkChainReorganization() {
        // TODO: Implement chain reorganization logic
        // This is a complex operation that needs careful design
        log.debug("Chain reorganization check (not yet implemented)");
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
}