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

import io.xdag.config.Config;
import io.xdag.store.DagStore;
import io.xdag.store.cache.DagEntityResolver;
import io.xdag.store.cache.ResolvedLinks;
import io.xdag.utils.TimeUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Block Validator - Centralized block validation logic
 *
 * <p>Responsible for all block validation rules:
 * <ul>
 *   <li>Basic rules validation (timestamp, structure, coinbase)</li>
 *   <li>PoW validation (minimum difficulty requirement)</li>
 *   <li>Epoch limit validation (max blocks per epoch)</li>
 *   <li>Link validation (transaction and block references)</li>
 *   <li>DAG rules validation (cycles, time window, link count)</li>
 * </ul>
 *
 * <p>This class consolidates all validation logic that was previously scattered
 * throughout DagChainImpl, making it easier to test and maintain validation rules.
 *
 * @since XDAGJ v1.0.0
 */
@Slf4j
public class BlockValidator {

  private static final long ORPHAN_RETENTION_WINDOW = 16384; // 12 days in epochs
  private static final long SYNC_MAX_REFERENCE_DEPTH = 1000; // Max reference depth for sync mode
  private static final int MAX_BLOCKS_PER_EPOCH = 16; // Limit orphan growth
  private static final int MAX_TRAVERSAL_DEPTH = 1000; // Max DAG traversal depth

  private final DagStore dagStore;
  private final DagEntityResolver entityResolver;
  private final Config config;

  /**
   * Creates a BlockValidator with required dependencies
   *
   * @param dagStore        DAG storage layer
   * @param entityResolver  entity resolution service
   * @param config          node configuration
   */
  public BlockValidator(
      DagStore dagStore,
      DagEntityResolver entityResolver,
      Config config) {
    this.dagStore = dagStore;
    this.entityResolver = entityResolver;
    this.config = config;
  }

  /**
   * Perform complete block validation
   *
   * <p>Validates block through all validation stages:
   * <ol>
   *   <li>Basic rules (timestamp, structure, coinbase)</li>
   *   <li>Minimum PoW (hash meets difficulty target)</li>
   *   <li>Epoch limit (max blocks per epoch not exceeded)</li>
   *   <li>Link validation (all references exist and are valid)</li>
   *   <li>DAG rules (no cycles, time window, link count)</li>
   * </ol>
   *
   * @param block block to validate
   * @param chainStats current chain statistics (for difficulty target)
   * @return validation result (success or specific error)
   */
  public DagImportResult validate(Block block, ChainStats chainStats) {
    // Stage 1: Basic rules
    DagImportResult basicResult = validateBasicRules(block, chainStats);
    if (basicResult != null) {
      return basicResult;
    }

    // Stage 2: PoW validation
    DagImportResult powResult = validateMinimumPoW(block, chainStats);
    if (powResult != null) {
      return powResult;
    }

    // Stage 3: Epoch limit
    DagImportResult epochLimitResult = validateEpochLimit(block, chainStats);
    if (epochLimitResult != null) {
      return epochLimitResult;
    }

    // Stage 4: Link validation
    DagImportResult linkResult = validateLinks(block, chainStats);
    if (linkResult != null) {
      return linkResult;
    }

    // Stage 5: DAG rules
    DAGValidationResult dagResult = validateDAGRules(block, chainStats);
    if (!dagResult.isValid()) {
      log.debug("Block {} failed DAG validation: {}",
          formatHash(block.getHash()), dagResult.getErrorMessage());
      return DagImportResult.invalidDAG(dagResult);
    }

    // All validations passed
    return null;
  }

  /**
   * Validate basic block rules
   *
   * <p>Checks:
   * <ul>
   *   <li>Genesis block special handling</li>
   *   <li>Timestamp validity (not too far in future, after XDAG era)</li>
   *   <li>Block structure validation</li>
   *   <li>Coinbase field length (exactly 20 bytes)</li>
   *   <li>Duplicate block check (allow orphan re-processing)</li>
   * </ul>
   *
   * @param block block to validate
   * @param chainStats current chain statistics
   * @return null if valid, error result if invalid
   */
  private DagImportResult validateBasicRules(Block block, ChainStats chainStats) {
    // Genesis block special handling
    if (isGenesisBlock(block)) {
      // Security: Genesis blocks can only be accepted if chain is empty
      if (chainStats.getMainBlockCount() > 0) {
        log.warn("SECURITY: Rejecting genesis block {} - chain already initialized with {} blocks",
            formatHash(block.getHash()), chainStats.getMainBlockCount());
        return DagImportResult.invalidBasic("Genesis block rejected: chain already has main blocks");
      }

      log.info("Accepting genesis block {} at epoch {} - deterministic from genesis.json",
          formatHash(block.getHash()), block.getEpoch());
      // Skip timestamp validation for genesis
    } else {
      // Regular blocks: timestamp validation
      long currentTimestamp = TimeUtils.getCurrentEpoch();
      long blockTimestamp = TimeUtils.epochNumberToMainTime(block.getEpoch());

      if (blockTimestamp > (currentTimestamp + MAIN_CHAIN_PERIOD)) {
        log.debug("Block {} has invalid timestamp: {} (current: {})",
            formatHash(block.getHash()), blockTimestamp, currentTimestamp);
        return DagImportResult.invalidBasic("Block timestamp is too far in the future");
      }

      // Check XDAG era (convert from Unix seconds to XDAG timestamp)
      long xdagEra = config.getXdagEra();
      long xdagEraTimestamp = xdagEra * 1024;

      if (blockTimestamp < xdagEraTimestamp) {
        log.debug("Block {} timestamp {} is before XDAG era {} (Unix: {})",
            formatHash(block.getHash()), blockTimestamp, xdagEraTimestamp, xdagEra);
        return DagImportResult.invalidBasic("Block timestamp is before XDAG era");
      }
    }

    // Check if block already exists
    if (dagStore.hasBlock(block.getHash())) {
      Block existingBlock = dagStore.getBlockByHash(block.getHash());
      if (existingBlock != null && existingBlock.getInfo() != null
          && existingBlock.getInfo().getHeight() == 0) {
        // Orphan block exists - allow re-processing
        log.debug("Block {} exists as orphan, allowing re-processing", formatHash(block.getHash()));
      } else {
        log.debug("Block {} already exists as non-orphan", formatHash(block.getHash()));
        return DagImportResult.duplicate();
      }
    }

    // Validate block structure
    if (!block.isValid()) {
      log.debug("Block {} failed structure validation", formatHash(block.getHash()));
      return DagImportResult.invalidBasic("Block structure validation failed");
    }

    // Security: Validate coinbase field length (must be exactly 20 bytes)
    Bytes coinbase = block.getHeader().getCoinbase();
    if (coinbase == null) {
      log.warn("SECURITY: Rejecting block {} - null coinbase", formatHash(block.getHash()));
      return DagImportResult.invalidBasic("Block coinbase is null");
    }
    if (coinbase.size() != 20) {
      log.warn("SECURITY: Rejecting block {} - invalid coinbase length: {} bytes (expected 20)",
          formatHash(block.getHash()), coinbase.size());
      return DagImportResult.invalidBasic(
          String.format("Block coinbase must be exactly 20 bytes, got %d bytes", coinbase.size()));
    }

    return null; // Validation passed
  }

  /**
   * Validate minimum PoW requirement
   *
   * <p>Ensures block hash satisfies the base difficulty target.
   * This prevents spam blocks and ensures basic work was done.
   * <p>
   * Rule: hash <= baseDifficultyTarget
   * <p>
   * Genesis blocks are exempt from this check.
   *
   * @param block block to validate
   * @param chainStats current chain statistics (for difficulty target)
   * @return null if valid, error result if invalid
   */
  private DagImportResult validateMinimumPoW(Block block, ChainStats chainStats) {
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
          formatHash(block.getHash()),
          blockHash.toHexString().substring(0, 16),
          baseDifficultyTarget.toHexString().substring(0, 16));

      return DagImportResult.invalidBasic(String.format(
          "Insufficient proof of work: hash exceeds difficulty target (hash=%s, target=%s)",
          blockHash.toHexString().substring(0, 16) + "...",
          baseDifficultyTarget.toHexString().substring(0, 16) + "..."));
    }

    log.debug("Block {} passed minimum PoW check (hash {} <= target {})",
        formatHash(block.getHash()),
        blockHash.toHexString().substring(0, 16),
        baseDifficultyTarget.toHexString().substring(0, 16));

    return null; // Validation passed
  }

  /**
   * Validate epoch block limit
   *
   * <p>Limits the number of blocks accepted per epoch to control orphan block growth.
   * If epoch already has MAX_BLOCKS_PER_EPOCH blocks, only accept new blocks if they
   * have better difficulty (smaller hash) than the worst existing block.
   * <p>
   * This implements a competitive admission policy:
   * <ul>
   *   <li>First MAX_BLOCKS_PER_EPOCH blocks are always accepted</li>
   *   <li>Additional blocks must beat the weakest accepted block</li>
   *   <li>Maintains top N blocks per epoch</li>
   * </ul>
   *
   * @param block block to validate
   * @param chainStats current chain statistics
   * @return null if valid, error result if should be rejected
   */
  private DagImportResult validateEpochLimit(Block block, ChainStats chainStats) {
    long epoch = block.getEpoch();
    List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);

    // Count ALL candidate blocks in this epoch
    int candidateCount = candidates.size();

    // If under limit, accept
    if (candidateCount < MAX_BLOCKS_PER_EPOCH) {
      log.debug("Block {} accepted: epoch {} has {} < {} candidate blocks",
          formatHash(block.getHash()), epoch, candidateCount, MAX_BLOCKS_PER_EPOCH);
      return null; // Accept
    }

    // Epoch is full, check if this block is better than the worst one
    UInt256 thisBlockWork = calculateBlockWork(block.getHash());

    // Find the worst block (smallest work = largest hash)
    Block worstBlock = null;
    UInt256 worstWork = UInt256.MAX_VALUE;

    for (Block candidate : candidates) {
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
          formatHash(block.getHash()),
          formatHash(worstBlock.getHash()),
          epoch,
          thisBlockWork.toHexString().substring(0, 16),
          worstWork.toHexString().substring(0, 16));

      return null; // Accept this block (caller will handle demotion)
    } else {
      // This block is not better than worst, reject
      log.debug("Block {} rejected: epoch {} full and work {} <= worst {}",
          formatHash(block.getHash()),
          epoch,
          thisBlockWork.toHexString().substring(0, 16),
          worstWork.toHexString().substring(0, 16));

      return DagImportResult.invalidBasic(String.format(
          "Epoch %d full (%d blocks) and this block's work not in top %d",
          epoch, MAX_BLOCKS_PER_EPOCH, MAX_BLOCKS_PER_EPOCH));
    }
  }

  /**
   * Validate block links (Transaction and Block references)
   *
   * <p>Validates:
   * <ul>
   *   <li>All referenced blocks and transactions exist</li>
   *   <li>Referenced blocks are from PREVIOUS epochs (no same-epoch or future references)</li>
   *   <li>Reference depth is reasonable (warns if > SYNC_MAX_REFERENCE_DEPTH)</li>
   *   <li>Transaction structure and signatures are valid</li>
   *   <li>Transaction amounts are sufficient</li>
   * </ul>
   *
   * @param block block to validate
   * @param chainStats current chain statistics
   * @return null if valid, error result if invalid
   */
  private DagImportResult validateLinks(Block block, ChainStats chainStats) {
    ResolvedLinks resolved = entityResolver.resolveAllLinks(block);

    // Check for missing references
    if (!resolved.hasAllReferences()) {
      boolean allowBootstrap = chainStats.getMainBlockCount() <= 1;
      if (!allowBootstrap) {
        Bytes32 missing = resolved.getMissingReferences().getFirst();
        log.debug("Block {} has missing dependency: {}",
            formatHash(block.getHash()), formatHash(missing));

        return DagImportResult.missingDependency(missing,
            "Link target not found: " + missing.toHexString());
      } else {
        log.warn("Bootstrap mode: accepting block {} despite {} missing references (chain height={})",
            formatHash(block.getHash()),
            resolved.getMissingReferences().size(),
            chainStats.getMainBlockCount());
        // Allow bootstrap with missing references
        resolved = ResolvedLinks.builder()
            .referencedBlocks(new ArrayList<>(resolved.getReferencedBlocks()))
            .referencedTransactions(new ArrayList<>(resolved.getReferencedTransactions()))
            .missingReferences(List.of())
            .build();
      }
    }

    // Validate all referenced Blocks
    for (Block refBlock : resolved.getReferencedBlocks()) {
      // Validate epoch order: blocks can ONLY reference blocks from PREVIOUS epochs
      if (refBlock.getEpoch() >= block.getEpoch()) {
        log.debug(
            "Block {} (epoch {}) references block {} (epoch {}) - invalid: must reference EARLIER epochs only",
            formatHash(block.getHash()), block.getEpoch(),
            formatHash(refBlock.getHash()), refBlock.getEpoch());
        return DagImportResult.invalidLink(
            String.format(
                "Referenced block epoch (%d) >= current block epoch (%d) - must reference earlier epochs only",
                refBlock.getEpoch(), block.getEpoch()),
            refBlock.getHash());
      }

      // Check reference depth (soft check - warn but don't reject)
      long referenceDepth = block.getEpoch() - refBlock.getEpoch();

      if (referenceDepth > SYNC_MAX_REFERENCE_DEPTH) {
        log.warn("Block {} (epoch {}) references very old block {} (epoch {}) - depth: {} epochs",
            formatHash(block.getHash()), block.getEpoch(),
            formatHash(refBlock.getHash()), refBlock.getEpoch(),
            referenceDepth);
        log.warn("This may indicate a network partition merge scenario (partition duration: ~{} hours)",
            referenceDepth * 64 / 3600.0);
        log.warn("Block will be accepted, but node operators should verify chain consistency");
      }
    }

    // Validate all referenced Transactions
    for (Transaction tx : resolved.getReferencedTransactions()) {
      // Validate transaction structure
      if (!tx.isValid()) {
        log.debug("Transaction {} has invalid structure", formatHash(tx.getHash()));
        return DagImportResult.invalidLink("Invalid transaction structure", tx.getHash());
      }

      // Validate transaction signature
      if (!tx.verifySignature()) {
        log.debug("Transaction {} has invalid signature", formatHash(tx.getHash()));
        return DagImportResult.invalidLink("Invalid transaction signature", tx.getHash());
      }

      // Validate transaction amount
      if (tx.getAmount().add(tx.getFee()).subtract(MIN_GAS).isNegative()) {
        log.debug("Transaction {} has insufficient amount", formatHash(tx.getHash()));
        return DagImportResult.invalidLink("Transaction amount + fee < MIN_GAS", tx.getHash());
      }
    }

    log.debug("Block {} link validation passed: {} block links, {} transaction links",
        formatHash(block.getHash()),
        resolved.getReferencedBlocks().size(),
        resolved.getReferencedTransactions().size());

    return null; // Validation passed
  }

  /**
   * Validate DAG structure rules
   *
   * <p>Validates:
   * <ul>
   *   <li>No cycles in DAG</li>
   *   <li>Time window constraints (12 days / 16384 epochs)</li>
   *   <li>Link count limits (1-16 block links)</li>
   *   <li>Traversal depth limits (max 1000 layers)</li>
   * </ul>
   *
   * @param block block to validate
   * @param chainStats current chain statistics
   * @return validation result with detailed error info if invalid
   */
  public DAGValidationResult validateDAGRules(Block block, ChainStats chainStats) {
    // Genesis block doesn't need DAG validation
    if (isGenesisBlock(block)) {
      return DAGValidationResult.valid();
    }

    // Rule 1: Check for cycles
    if (hasCycle(block)) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.CYCLE_DETECTED,
          "Block creates a cycle in DAG");
    }

    // Rule 2: Check time window (12 days / 16384 epochs)
    // DEVNET: Skip time window validation for testing with old genesis blocks
    boolean isDevnet = config.getNodeSpec().getNetwork().toString().toLowerCase().contains("devnet");
    if (!isDevnet) {
      long currentEpoch = TimeUtils.getCurrentEpochNumber();
      for (Link link : block.getLinks()) {
        if (link.isBlock()) {
          Block refBlock = dagStore.getBlockByHash(link.getTargetHash());
          if (refBlock != null) {
            long refEpoch = refBlock.getEpoch();
            if (currentEpoch - refEpoch > ORPHAN_RETENTION_WINDOW) {
              return DAGValidationResult.invalid(
                  DAGValidationResult.DAGErrorCode.TIME_WINDOW_VIOLATION,
                  "Referenced block is too old (>" + (currentEpoch - refEpoch) + " epochs)");
            }
          }
        }
      }
    }

    // Rule 3: Check link count (1-16 block links)
    long blockLinkCount = block.getLinks().stream()
        .filter(link -> !link.isTransaction())
        .count();

    boolean isFirstBlock = (blockLinkCount == 0 && chainStats.getMainBlockCount() == 0);
    if (!isFirstBlock && (blockLinkCount < 1 || blockLinkCount > 16)) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.INVALID_LINK_COUNT,
          "Block must have 1-16 block links (found " + blockLinkCount + ")");
    }

    // Rule 4: Check traversal depth
    int depth = calculateDepthFromGenesis(block);
    if (depth > MAX_TRAVERSAL_DEPTH) {
      return DAGValidationResult.invalid(
          DAGValidationResult.DAGErrorCode.TRAVERSAL_DEPTH_EXCEEDED,
          "Block depth from genesis exceeds 1000 layers (depth=" + depth + ")");
    }

    return DAGValidationResult.valid();
  }

  // ==================== Helper Methods ====================

  /**
   * Check if a block is a genesis block
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

    Block current = dagStore.getBlockByHash(currentHash);
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
        Block parent = dagStore.getBlockByHash(link.getTargetHash());
        if (parent != null) {
          int parentDepth = calculateDepthFromGenesis(parent);
          maxDepth = Math.max(maxDepth, parentDepth + 1);
        }
      }
    }

    return maxDepth;
  }

  /**
   * Calculate work for a single block (XDAG: work = MAX_UINT256 / hash)
   */
  private UInt256 calculateBlockWork(Bytes32 hash) {
    if (hash.isZero()) {
      throw new IllegalArgumentException("Hash cannot be zero");
    }
    return UInt256.MAX_VALUE.divide(UInt256.fromBytes(hash));
  }

  /**
   * Format block hash for logging (first 16 hex chars)
   */
  private String formatHash(Bytes32 hash) {
    return hash.toHexString().substring(0, 16);
  }
}
