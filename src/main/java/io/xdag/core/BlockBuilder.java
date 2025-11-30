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

import io.xdag.DagKernel;
import io.xdag.config.Config;
import io.xdag.config.GenesisConfig;
import io.xdag.store.DagStore;
import io.xdag.utils.TimeUtils;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Block Builder - candidate block factory.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Create candidate blocks used for mining</li>
 *   <li>Create the genesis block</li>
 *   <li>Collect candidate links (block references + transaction references)</li>
 *   <li>Manage the mining coinbase address</li>
 * </ul>
 *
 * <p>Extracted from {@link DagChainImpl} in Phase 1 of the refactor.</p>
 */
@Slf4j
public class BlockBuilder {

  /**
   * Maximum reference depth for normal mining (in epochs)
   *
   * <p>When node is up-to-date (not behind), new blocks can only reference blocks within the last 16
   * epochs (≈17 minutes).
   * <p>This prevents "ancient reference" attacks where malicious nodes reference very old blocks to
   * create fake chains.
   */
  private static final long MINING_MAX_REFERENCE_DEPTH = 16;

  private static final UInt256 INITIAL_BASE_DIFFICULTY_TARGET = UInt256.fromBytes(
      Bytes32.fromHexString("0x00ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));

  private final DagKernel dagKernel;
  private final DagStore dagStore;
  private final Config config;

  private volatile Bytes miningCoinbase = Bytes.wrap(new byte[20]); // Default: zero address

  /**
   * Create a new builder.
   *
   * @param dagKernel DAG kernel that exposes shared services
   */
  public BlockBuilder(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
    this.dagStore = dagKernel.getDagStore();
    this.config = dagKernel.getConfig();
  }

  /**
   * Create a candidate block for mining.
   *
   * @param chainStats current chain statistics
   * @return freshly built candidate block
   */
  public Block createCandidateBlock(ChainStats chainStats) {
    log.info("Creating candidate block for mining");

    // BUGFIX: Use epoch number directly; block timestamp is derived at epoch end for display
    long epoch = TimeUtils.getCurrentEpochNumber();

    // Use baseDifficultyTarget from chain stats (NEW CONSENSUS)
    UInt256 difficultyTarget = chainStats.getBaseDifficultyTarget();
    if (difficultyTarget == null) {
      // Fallback for uninitialized chains
      difficultyTarget = INITIAL_BASE_DIFFICULTY_TARGET;
      log.warn("Base difficulty target not set, using initial value: {}",
          difficultyTarget.toHexString().substring(0, 16) + "...");
    }

    Bytes coinbase = miningCoinbase;
    List<Link> links = collectCandidateLinks(chainStats);

    Block candidateBlock = Block.createCandidate(epoch, difficultyTarget, coinbase, links);

    log.info("Created mining candidate block: epoch={}, target={}, links={}, hash={}",
        epoch,
        difficultyTarget.toHexString().substring(0, 16) + "...",
        links.size(),
        candidateBlock.getHash().toHexString().substring(0, 16) + "...");

    return candidateBlock;
  }

  /**
   * Collect links for candidate block creation (REFACTORED)
   *
   * <p>NEW STRATEGY: Reference "top 16 candidates from previous height's epoch" + up to 1024
   * transactions from pool
   *
   * <p>Algorithm:
   * <ol>
   *   <li>Get previous main block (height N-1)</li>
   *   <li>Check if node is up-to-date (strict mining reference depth limit)</li>
   *   <li>Get all candidates in that block's epoch</li>
   *   <li>Sort by work (descending), take top 16 (MAX_BLOCK_LINKS)</li>
   *   <li>Add references to these 16 blocks</li>
   *   <li>Add up to 1024 transaction references from transaction pool</li>
   * </ol>
   *
   * <p>RATIONALE:
   * <ul>
   *   <li>Height N-1 is already confirmed (epoch competition finished)</li>
   *   <li>All candidates are valid and can be referenced</li>
   *   <li>Top 16 includes the winner (highest work) and top candidates</li>
   *   <li>Gives epoch losers a chance to be referenced</li>
   *   <li>Strict reference limit prevents outdated nodes from mining</li>
   *   <li>MAX_LINKS_PER_BLOCK (1,485,000) allows massive transaction throughput</li>
   *   <li>Initial limit of 1024 txs is conservative for stability</li>
   * </ul>
   *
   * <p>See: docs/DESIGN-BLOCK-LINK-ORPHAN-STORE-REFACTOR.md
   *
   * @param chainStats current chain statistics
   * @return list of links (16 block links + up to 1024 tx links), empty list if node is too far
   *     behind
   */
  private List<Link> collectCandidateLinks(ChainStats chainStats) {
    List<Link> links = new ArrayList<>();

    // Step 1: Get previous main block (height N-1)
    // IMPORTANT: mainBlockCount is 0-indexed relative to next block height
    // When Genesis (height=1) exists, mainBlockCount=0
    // So we need to get block at height (mainBlockCount), NOT (mainBlockCount-1)
    long currentMainHeight = chainStats.getMainBlockCount();

    // Try to get the last main block
    // If mainBlockCount==0, this will try to get Genesis at height 1
    // If mainBlockCount>0, this gets the actual last main block
    long lastBlockHeight = Math.max(1, currentMainHeight);

    log.debug("Collecting candidate links: currentMainHeight={}, lastBlockHeight={}",
        currentMainHeight, lastBlockHeight);

    Block prevMainBlock = dagStore.getMainBlockByHeight(lastBlockHeight, false);

    if (prevMainBlock == null) {
      // No blocks exist yet (not even Genesis) - this should only happen during initialization
      log.error(
          "ERROR: Cannot find block at height {}! currentMainHeight={}, Genesis might not be imported!",
          lastBlockHeight, currentMainHeight);
      return links;
    }

    log.debug("Found previous main block at height {}, epoch={}, hash={}",
        lastBlockHeight, prevMainBlock.getEpoch(),
        prevMainBlock.getHash().toHexString().substring(0, 16));

    // Step 2: STRICT mining reference depth check
    // Prevent outdated nodes from creating blocks with stale references
    long currentEpoch = TimeUtils.getCurrentEpochNumber();
    long prevEpoch = prevMainBlock.getEpoch();
    long referenceDepth = currentEpoch - prevEpoch;

    // DEVNET: Skip reference depth check to allow development/testing with arbitrary epoch gaps
    // In development, nodes may be stopped for extended periods, and strict epoch checks
    // would prevent mining even after successful sync
    boolean isDevnet = config.getNodeSpec().getNetwork()
        .toString().toLowerCase().contains("devnet");

    boolean allowBootstrap = currentMainHeight <= 1;

    if (!isDevnet) {
      // Production networks: enforce strict reference depth limit
      if (referenceDepth > MINING_MAX_REFERENCE_DEPTH && !allowBootstrap) {
        log.error(
            "MINING BLOCKED: Previous main block (epoch {}) is {} epochs behind current epoch {}",
            prevEpoch, referenceDepth, currentEpoch);
        log.error("Maximum allowed reference depth for mining: {} epochs (~{} minutes)",
            MINING_MAX_REFERENCE_DEPTH, MINING_MAX_REFERENCE_DEPTH * 64 / 60);
        log.error("Node must sync to latest epoch before mining can resume");
        log.error("Current lag: {} epochs (~{} minutes)",
            referenceDepth, referenceDepth * 64 / 60);
        return links;  // Return empty links to prevent mining
      } else if (referenceDepth > MINING_MAX_REFERENCE_DEPTH) {
        log.warn(
            "Reference depth {} exceeds limit {} but allowing bootstrap because currentMainHeight={}",
            referenceDepth, MINING_MAX_REFERENCE_DEPTH, currentMainHeight);
      }
    } else {
      // DEVNET: Log reference depth but don't block mining
      if (referenceDepth > MINING_MAX_REFERENCE_DEPTH) {
        log.info(
            "DEVNET: Reference depth {} epochs (~{} minutes) exceeds normal limit {}, but allowing for development",
            referenceDepth, referenceDepth * 64 / 60, MINING_MAX_REFERENCE_DEPTH);
      }
    }

    // Step 3: Get all candidates in prev block's epoch
    log.debug("Collecting links from height {} (epoch {}), reference depth: {} epochs",
        currentMainHeight, prevEpoch, referenceDepth);

    List<Block> candidates = getCandidateBlocksInEpoch(prevEpoch);

    if (candidates.isEmpty()) {
      // Fallback: if no candidates found (shouldn't happen), at least reference prev main block
      log.warn("No candidates found in epoch {}, only referencing prev main block", prevEpoch);
      links.add(Link.toBlock(prevMainBlock.getHash()));
      return links;
    }

    // Step 3: Sort by work (descending), take top 16 (MAX_BLOCK_LINKS)
    // Work = MAX_UINT256 / hash → smaller hash = more work
    List<Block> top16 = candidates.stream()
        .sorted((b1, b2) -> {
          UInt256 work1 = calculateBlockWork(b1.getHash());
          UInt256 work2 = calculateBlockWork(b2.getHash());
          return work2.compareTo(work1);  // Descending: largest work first
        })
        .limit(Block.MAX_BLOCK_LINKS)
        .toList();

    // Step 4: Add block references
    for (Block block : top16) {
      links.add(Link.toBlock(block.getHash()));
    }

    log.info("Collected {} block links from height {} epoch {} (top {} of {} candidates)",
        links.size(), currentMainHeight, prevEpoch,
        Math.min(Block.MAX_BLOCK_LINKS, candidates.size()), candidates.size());

    // Step 5: Add transaction links from transaction pool
    // MAX_LINKS_PER_BLOCK (1,485,000) - MAX_BLOCK_LINKS (16) = 1,484,984 available for transactions
    // Initial conservative limit: 1024 transactions per block for stability
    final int MAX_TX_LINKS_PER_BLOCK = 1024;

    TransactionPool txPool = dagKernel.getTransactionPool();
    if (txPool != null && txPool.size() > 0) {
      // Select transactions from pool (ordered by fee, highest first)
      List<Transaction> selectedTxs = txPool.selectTransactions(MAX_TX_LINKS_PER_BLOCK);

      for (Transaction tx : selectedTxs) {
        links.add(Link.toTransaction(tx.getHash()));
      }

      log.info("Added {} transaction links from pool ({} total pending, limit {})",
          selectedTxs.size(), txPool.size(), MAX_TX_LINKS_PER_BLOCK);
    } else {
      log.debug("Transaction pool is empty or not available, no transaction links added");
    }

    return links;
  }

  /**
   * Set mining coinbase address
   *
   * @param coinbase mining reward address (20 bytes)
   */
  public void setMiningCoinbase(Bytes coinbase) {
    Bytes normalized;
    if (coinbase == null) {
      log.warn("Null coinbase provided, using zero address (20 bytes)");
      normalized = Bytes.wrap(new byte[20]);
    } else if (coinbase.size() > 20) {
      log.warn("Coinbase address too long ({} bytes), truncating to 20 bytes: {} -> {}",
          coinbase.size(),
          coinbase.toHexString(),
          coinbase.slice(0, 20).toHexString());
      normalized = coinbase.slice(0, 20);
    } else if (coinbase.size() < 20) {
      byte[] padded = new byte[20];
      System.arraycopy(coinbase.toArray(), 0, padded, 0, coinbase.size());
      log.warn("Coinbase address too short ({} bytes), padding to 20 bytes: {} -> {}",
          coinbase.size(),
          coinbase.toHexString(),
          Bytes.wrap(padded).toHexString());
      normalized = Bytes.wrap(padded);
    } else {
      normalized = coinbase;
    }

    if (normalized.equals(this.miningCoinbase)) {
      return;
    }

    this.miningCoinbase = normalized;
    log.info("Mining coinbase address set: {}", normalized.toHexString().substring(0, 16) + "...");
  }

  /**
   * Create the genesis block for the provided epoch.
   *
   * @param epoch epoch assigned to the genesis block
   * @return genesis block
   */
  public Block createGenesisBlock(long epoch) {
    log.info("Creating genesis block at epoch {}", epoch);

    // IMPORTANT: Block.createWithNonce() expects epoch number, not timestamp
    // Block.getTimestamp() derives display time via TimeUtils.epochNumberToMainTime(...)
    // So we pass the epoch number directly
    log.info("  - Genesis epoch: {} (timestamp will be main time: {})", epoch,
        TimeUtils.epochNumberToMainTime(epoch));

    // Use zero address (20 bytes) for genesis coinbase
    Bytes coinbase = Bytes.wrap(new byte[20]);
    log.info("  - Using zero coinbase (genesis block)");

    // Get difficulty from genesis config (not hardcoded)
    GenesisConfig genesisConfig = dagKernel.getGenesisConfig();
    UInt256 difficulty = genesisConfig.getDifficultyUInt256();
    log.info("  - Genesis difficulty: {}", difficulty.toHexString());

    // Create genesis block with epoch number (NOT timestamp)
    // Block.getTimestamp() uses TimeUtils helper to derive display timestamp
    Block genesisBlock = Block.createWithNonce(
        epoch,  // Pass epoch number, Block will convert to timestamp
        difficulty,  // Use configured difficulty from genesis.json
        Bytes32.ZERO,
        coinbase,
        List.of()
    );

    log.info("✓ Genesis block created: hash={}, epoch={}",
        genesisBlock.getHash().toHexString(),
        genesisBlock.getEpoch());

    return genesisBlock;
  }

  // ==================== Helper Methods ====================

  /**
   * Get candidate blocks in an epoch
   */
  private List<Block> getCandidateBlocksInEpoch(long epoch) {
    return dagStore.getCandidateBlocksInEpoch(epoch);
  }

  /**
   * Calculate block work
   *
   * XDAG rule: blockWork = MAX_UINT256 / hash
   */
  private UInt256 calculateBlockWork(Bytes32 hash) {
    if (hash.isZero()) {
      return UInt256.ZERO;
    }

    UInt256 hashValue = UInt256.fromBytes(hash);

    // Special handling for very small hashes (avoid overflow)
    if (hashValue.compareTo(UInt256.ONE) <= 0) {
      return UInt256.MAX_VALUE;
    }

    // Calculate: MAX_UINT256 / hash
    BigInteger max = UInt256.MAX_VALUE.toBigInteger();
    BigInteger hashBI = hashValue.toBigInteger();

    try {
      BigInteger work = max.divide(hashBI);
      return UInt256.valueOf(work);
    } catch (ArithmeticException e) {
      log.error("Error calculating block work for hash {}: {}", hash.toHexString(), e.getMessage());
      return UInt256.ZERO;
    }
  }
}
