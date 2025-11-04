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

import com.google.common.collect.Lists;
import com.google.common.primitives.UnsignedLong;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.consensus.RandomX;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.*;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.db.rocksdb.SnapshotStoreImpl;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Listener;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

import static io.xdag.config.Constants.*;
import static io.xdag.config.Constants.MessageType.NEW_LINK;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.BytesUtils.*;

@Slf4j
@Getter
public class BlockchainImpl implements Blockchain {

    // Static gas fee accumulator
    private static XAmount sumGas = XAmount.ZERO;
    
    // Thread factory for main chain checking
    private static final ThreadFactory factory = BasicThreadFactory.builder()
            .namingPattern("check-main-%d")
            .daemon(true)
            .build();

    // Wallet instance
    private final Wallet wallet;

    // Storage components
    private final AddressStore addressStore;
    private final BlockStore blockStore;
    private final TransactionHistoryStore txHistoryStore;
    private final TransactionStore transactionStore;  // Phase 4 - v5.1 Transaction storage

    // Store for non-Extra orphan blocks
    private final OrphanBlockStore orphanBlockStore;

    // In-memory pools and maps
    private final LinkedHashMap<Bytes, BlockV5> memOrphanPool = new LinkedHashMap<>();
    private final Map<Bytes, Integer> memOurBlocks = new ConcurrentHashMap<>();

    // Stats and status tracking (v5.1 immutable design)
    private volatile ChainStats chainStats;  // Use volatile for thread-safe publication
    private final Kernel kernel;
    private final XdagTopStatus xdagTopStatus;

    // Main chain checking components
    private final ScheduledExecutorService checkLoop;
    private final RandomX randomx;
    private final List<Listener> listeners = Lists.newArrayList();
    private ScheduledFuture<?> checkLoopFuture;
    
    // Snapshot related fields
    private final long snapshotHeight;
    private SnapshotStore snapshotStore;
    private SnapshotStore snapshotAddressStore;
    private final XdagExtStats xdagExtStats;
    
    @Getter
    private byte[] preSeed;

    // Constructor initializes all components and starts main chain checking
    public BlockchainImpl(Kernel kernel) {
        // Initialize core components
        this.kernel = kernel;
        this.wallet = kernel.getWallet();
        this.xdagExtStats = new XdagExtStats();
        
        // Initialize storage components
        this.addressStore = kernel.getAddressStore();
        this.blockStore = kernel.getBlockStore();
        this.orphanBlockStore = kernel.getOrphanBlockStore();
        this.txHistoryStore = kernel.getTxHistoryStore();
        this.transactionStore = kernel.getTransactionStore();
        snapshotHeight = kernel.getConfig().getSnapshotSpec().getSnapshotHeight();

        // Initialize snapshot if enabled
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()
                && kernel.getConfig().getSnapshotSpec().getSnapshotHeight() > 0
                && !blockStore.isSnapshotBoot()) {

            this.chainStats = ChainStats.zero();
            this.xdagTopStatus = new XdagTopStatus();

            if (kernel.getConfig().getSnapshotSpec().isSnapshotJ()) {
                initSnapshotJ();
            }

            // Save latest snapshot state
            blockStore.saveXdagTopStatus(xdagTopStatus);
            blockStore.saveChainStats(chainStats);

        } else {
            // Load existing state
            ChainStats storedStats = blockStore.getChainStats();
            XdagTopStatus storedTopStatus = blockStore.getXdagTopStatus();

            if (storedStats != null) {
                // Reset waiting sync count to 0 on startup
                this.chainStats = storedStats.withWaitingSyncCount(0).withExtraCount(0);
            } else {
                this.chainStats = ChainStats.zero();
            }

            this.xdagTopStatus = Objects.requireNonNullElseGet(storedTopStatus, XdagTopStatus::new);

            // Phase 7.3 continuation: Restore lastBlock initialization using BlockV5
            // Load last main block to initialize stats and top status
            BlockV5 lastBlock = getBlockByHeight(chainStats.getMainBlockCount());
            if (lastBlock != null && lastBlock.getInfo() != null) {
                BigInteger lastDifficulty = lastBlock.getInfo().getDifficulty().toBigInteger();
                chainStats = chainStats
                    .withMaxDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(lastDifficulty))
                    .withDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(lastDifficulty));
                xdagTopStatus.setTop(lastBlock.getHash().toArray());
                xdagTopStatus.setTopDiff(lastDifficulty);
                log.debug("Initialized blockchain state from last main block at height {}: diff={}, hash={}",
                         chainStats.getMainBlockCount(), lastDifficulty, lastBlock.getHash().toHexString());
            } else if (chainStats.getMainBlockCount() > 0) {
                log.warn("Last main block not found at height {}, blockchain state may be incomplete",
                        chainStats.getMainBlockCount());
            }
            preSeed = blockStore.getPreSeed();
        }

        // Initialize RandomX
        randomx = kernel.getRandomx();
        if (randomx != null) {
            randomx.setBlockchain(this);
        }

        // Start main chain checking
        checkLoop = new ScheduledThreadPoolExecutor(1, factory);
        this.startCheckMain(1024);
    }

    // Initialize snapshot data
    public void initSnapshotJ() {
        long start = System.currentTimeMillis();
        System.out.println("init snapshot...");

        // Initialize address snapshot store
        RocksdbKVSource snapshotAddressSource = new RocksdbKVSource("SNAPSHOT/ADDRESS");
        snapshotAddressStore = new SnapshotStoreImpl(snapshotAddressSource);
        snapshotAddressSource.setConfig(kernel.getConfig());
        snapshotAddressSource.init();
        snapshotAddressStore.saveAddress(this.blockStore, this.addressStore, this.txHistoryStore, kernel.getWallet().getAccounts(), kernel.getConfig().getSnapshotSpec().getSnapshotTime());

        // Initialize block snapshot store
        RocksdbKVSource snapshotSource = new RocksdbKVSource("SNAPSHOT/BLOCKS");
        snapshotStore = new SnapshotStoreImpl(snapshotSource);
        snapshotSource.setConfig(kernel.getConfig());
        snapshotStore.init();
        snapshotStore.saveSnapshotToIndex(this.blockStore, this.txHistoryStore, kernel.getWallet().getAccounts(), kernel.getConfig().getSnapshotSpec().getSnapshotTime());

        // Phase 7.3 continuation: Restore lastBlock initialization using BlockV5
        // Load snapshot block to initialize stats and top status
        BlockV5 lastBlock = getBlockByHeight(snapshotHeight);
        if (lastBlock != null && lastBlock.getInfo() != null) {
            BigInteger lastDifficulty = lastBlock.getInfo().getDifficulty().toBigInteger();
            chainStats = chainStats
                .withMaxDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(lastDifficulty))
                .withDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(lastDifficulty));
            xdagTopStatus.setPreTop(lastBlock.getHash().toArray());
            xdagTopStatus.setTop(lastBlock.getHash().toArray());
            xdagTopStatus.setTopDiff(lastDifficulty);
            xdagTopStatus.setPreTopDiff(lastDifficulty);
            log.debug("Initialized snapshot state from block at height {}: diff={}, hash={}",
                     snapshotHeight, lastDifficulty, lastBlock.getHash().toHexString());
        } else {
            log.warn("Snapshot block not found at height {}, using default stats", snapshotHeight);
        }

        // Initialize stats
        XAmount ourBalance = snapshotStore.getOurBalance();
        chainStats = chainStats
            .withBalance(ourBalance)
            .withWaitingSyncCount(0)
            .withNoRefCount(0)
            .withExtraCount(0)
            .withTotalBlockCount(0)
            .withBlockCount(0)
            .withTotalMainBlockCount(snapshotHeight)
            .withMainBlockCount(snapshotHeight);

        // Calculate total balance
        XAmount allBalance = snapshotStore.getAllBalance().add(snapshotAddressStore.getAllBalance());

        long end = System.currentTimeMillis();
        System.out.println("init snapshotJ done");
        System.out.println("time：" + (end - start) + "ms");
        System.out.println("Our balance: " + snapshotStore.getOurBalance().toDecimal(9, XUnit.XDAG).toPlainString());
        System.out.printf("All amount: %s%n", allBalance.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    // Register event listener
    @Override
    public void registerListener(Listener listener) {
        this.listeners.add(listener);
    }

    // ========== Phase 4 Step 2.1: BlockV5 Support ==========

    /**
     * Try to connect a BlockV5 to the blockchain (v5.1 implementation)
     *
     * Phase 4 Step 2.1: This is the NEW implementation for BlockV5 that uses
     * Link-based references instead of Address objects.
     *
     * @param block BlockV5 to connect (uses List<Link>)
     * @return ImportResult indicating success or failure
     */
    public synchronized ImportResult tryToConnect(BlockV5 block) {
        return tryToConnectV2(block);
    }

    /**
     * Internal implementation for BlockV5 (v5.1 Link-based design)
     *
     * Key differences from V1 (old Block + Address):
     * 1. Uses Link instead of Address
     * 2. Transaction details retrieved from TransactionStore
     * 3. Block references contain no amount (only hash + type)
     *
     * Phase 4 Step 2.3 Part 2: Added BlockInfo initialization for new blocks
     *
     * @param block BlockV5 instance
     * @return ImportResult
     */
    private synchronized ImportResult tryToConnectV2(BlockV5 block) {
        try {
            ImportResult result = ImportResult.IMPORTED_NOT_BEST;

            // Phase 1: Basic validation
            if (block.getTimestamp() > (XdagTime.getCurrentTimestamp() + MAIN_CHAIN_PERIOD / 4)
                    || block.getTimestamp() < kernel.getConfig().getXdagEra()) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("Block's time is illegal");
                log.debug("BlockV5 time is illegal: {}", block.getTimestamp());
                return result;
            }

            // Check if block already exists
            if (isExist(block.getHash())) {
                return ImportResult.EXIST;
            }

            if (isExistInMem(block.getHash())) {
                return ImportResult.IN_MEM;
            }

            // Validate block structure (from BlockV5.isValid())
            if (!block.isValid()) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("BlockV5 structure validation failed");
                log.debug("BlockV5 validation failed: {}", block.getHash().toHexString());
                return result;
            }

            // Phase 2: Validate links (Transaction and Block references)
            List<Link> links = block.getLinks();

            for (Link link : links) {
                if (link.isTransaction()) {
                    // Transaction link validation
                    Transaction tx = transactionStore.getTransaction(link.getTargetHash());
                    if (tx == null) {
                        result = ImportResult.NO_PARENT;
                        result.setHash(link.getTargetHash());
                        result.setErrorInfo("Transaction not found: " + link.getTargetHash().toHexString());
                        log.debug("Transaction {} not found", link.getTargetHash().toHexString());
                        return result;
                    }

                    // Validate transaction structure
                    if (!tx.isValid()) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setHash(link.getTargetHash());
                        result.setErrorInfo("Invalid transaction structure");
                        log.debug("Transaction {} invalid", tx.getHash().toHexString());
                        return result;
                    }

                    // Validate transaction signature
                    if (!tx.verifySignature()) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setHash(link.getTargetHash());
                        result.setErrorInfo("Invalid transaction signature");
                        log.debug("Transaction {} signature invalid", tx.getHash().toHexString());
                        return result;
                    }

                    // Validate transaction amount (amount + fee >= MIN_GAS)
                    if (tx.getAmount().add(tx.getFee()).subtract(MIN_GAS).isNegative()) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setHash(link.getTargetHash());
                        result.setErrorInfo("Transaction amount + fee < minGas");
                        log.debug("Transaction {} amount insufficient", tx.getHash().toHexString());
                        return result;
                    }

                } else {
                    // Block link validation
                  BlockV5 refBlock = getBlockByHash(link.getTargetHash(), false);
                    if (refBlock == null) {
                        result = ImportResult.NO_PARENT;
                        result.setHash(link.getTargetHash());
                        result.setErrorInfo("Block not found: " + link.getTargetHash().toHexString());
                        log.debug("Referenced block {} not found", link.getTargetHash().toHexString());
                        return result;
                    }

                    // Validate block timestamp order
                    if (refBlock.getTimestamp() >= block.getTimestamp()) {
                        result = ImportResult.INVALID_BLOCK;
                        result.setHash(refBlock.getHash());
                        result.setErrorInfo("Ref block's time >= block's time");
                        log.debug("Block {} timestamp order invalid", refBlock.getHash().toHexString());
                        return result;
                    }
                }
            }

            // ====================
            // Phase 3: Remove orphan block links
            // ====================
            for (Link link : links) {
                if (link.isBlock()) {
                    // Remove block links from orphan pool
                    removeOrphan(link.getTargetHash(), OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                }
            }

            // ====================
            // Phase 4: Record Transaction history
            // ====================
            // Note: Currently records based on Transaction links
            // TODO: Redesign onNewTxHistory() to work directly with Transaction objects
            for (Link link : links) {
                if (link.isTransaction()) {
                    Transaction tx = transactionStore.getTransaction(link.getTargetHash());
                    if (tx != null) {
                        // Phase 8.2: Index transaction to block for efficient block transaction queries
                        // This enables transactionStore.getTransactionsByBlock() to work
                        transactionStore.indexTransactionToBlock(block.getHash(), tx.getHash());
                    }
                }
            }

            // ====================
            // Phase 5: Initialize BlockInfo (v5.1 minimal design)
            // ====================
            // v5.1: BlockInfo only has 4 fields (hash, height, difficulty, timestamp)
            // Removed: type, flags, ref, maxDiffLink, amount, fee, remark, isSnapshot, snapshotInfo
            BlockInfo initialInfo = BlockInfo.builder()
                    .hash(block.getHash())
                    .timestamp(block.getTimestamp())
                    .height(0L)  // 0 = orphan block initially
                    .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)  // Will be calculated
                    .build();

            // Save initial BlockInfo to database
            blockStore.saveBlockInfoV2(initialInfo);

            // Phase 4.5: Save raw BlockV5 data to storage
            // Attach BlockInfo to BlockV5 before saving (BlockV5 is immutable)
            BlockV5 blockWithInfo = block.toBuilder().info(initialInfo).build();
            blockStore.saveBlockV5(blockWithInfo);

            log.info("BlockV5 connected and saved to storage: {}", block.getHash().toHexString());

            // Phase 7.3: Notify listeners (e.g., pool listener) of new BlockV5
            onNewBlockV5(blockWithInfo);

            return result;

        } catch (Throwable e) {
            log.error("Error connecting BlockV5: " + e.getMessage(), e);
            return ImportResult.ERROR;
        }
    }

    /**
     * Notify listeners of new BlockV5 (v5.1 implementation)
     *
     * Phase 7.3: Pool listener migration to BlockV5.
     * Sends BlockV5 serialized data to listener system (e.g., XdagPow).
     *
     * @param block BlockV5 to broadcast
     */
    protected void onNewBlockV5(BlockV5 block) {
        for (Listener listener : listeners) {
            // Serialize BlockV5 to bytes for listener
            byte[] blockBytes = block.toBytes();
            listener.onMessage(new BlockMessage(Bytes.wrap(blockBytes), NEW_LINK));
        }
    }

    @Override
    public long getLatestMainBlockNumber() {
        return chainStats.getMainBlockCount();
    }

    // TODO v5.1: DELETED - BlockInfo.ref field no longer exists in v5.1 minimal design
    // Temporarily disabled - waiting for migration to v5.1 architecture
    /*
    /**
     * Update BlockV5 ref field in database
     *
     * Phase 4 Step 2.3 Part 2: Update BlockInfo.ref for BlockV5.
     *
     * @param refBlock Referenced block (can be Block or BlockV5)
     * @param mainBlockHash Hash of the main block that references this block
     */
    /*
    private void updateBlockV5Ref(BlockV5 refBlock, Bytes32 mainBlockHash) {
        BlockInfo refInfo = refBlock.getInfo();
        if (refInfo == null) {
            log.warn("BlockInfo not found for ref block: {}", refBlock.getHash().toHexString());
            return;
        }

        BlockInfo updatedInfo = refInfo.toBuilder()
            .ref(mainBlockHash)
            .build();

        saveBlockInfo(updatedInfo);
    }
    */

    /**
     * Create a reward BlockV5 for pool distribution (v5.1 implementation - Phase 7.6)
     *
     * Phase 7.6: Pool reward distribution using BlockV5 architecture.
     * This method creates a BlockV5 containing Transaction references for reward distribution.
     *
     * Flow:
     * 1. Create Transaction objects for each recipient (foundation, pool)
     * 2. Sign each Transaction with the source key
     * 3. Save Transactions to TransactionStore
     * 4. Create BlockV5 with Link.toTransaction() references
     * 5. Return BlockV5 (caller will import via tryToConnect)
     *
     * @param sourceBlockHash Hash of source block (where funds come from)
     * @param recipients List of recipient addresses
     * @param amounts List of amounts for each recipient
     * @param sourceKey ECKeyPair for signing transactions (source of funds)
     * @param nonce Account nonce for transaction
     * @param totalFee Total transaction fee
     * @return BlockV5 containing reward transactions
     * @see Transaction#createTransfer(Bytes32, Bytes32, XAmount, long, XAmount)
     * @see Link#toTransaction(Bytes32)
     */
    public BlockV5 createRewardBlockV5(
            Bytes32 sourceBlockHash,
            List<Bytes32> recipients,
            List<XAmount> amounts,
            ECKeyPair sourceKey,
            long nonce,
            XAmount totalFee) {

        if (recipients.size() != amounts.size()) {
            throw new IllegalArgumentException("Recipients and amounts list sizes must match");
        }

        // Get source address (from address for transactions)
        Bytes32 sourceAddress = keyPair2Hash(sourceKey);

        // Create and save transactions
        List<Link> transactionLinks = new ArrayList<>();

        // Calculate fee per transaction (XAmount doesn't have divide method, use BigDecimal)
        BigDecimal totalFeeBD = new BigDecimal(totalFee.toString());
        BigDecimal recipientCount = BigDecimal.valueOf(recipients.size());
        XAmount feePerTx = XAmount.of(
            totalFeeBD.divide(recipientCount, java.math.RoundingMode.FLOOR).longValue()
        );

        for (int i = 0; i < recipients.size(); i++) {
            // Create transaction
            Transaction tx = Transaction.createTransfer(
                sourceAddress,      // from
                recipients.get(i),  // to
                amounts.get(i),     // amount
                nonce + i,          // nonce (increment for each tx)
                feePerTx            // fee
            );

            // Sign transaction
            Transaction signedTx = tx.sign(sourceKey);

            // Save transaction to storage
            transactionStore.saveTransaction(signedTx);

            // Create link to transaction
            transactionLinks.add(Link.toTransaction(signedTx.getHash()));

            log.debug("Created reward transaction: {} -> {} amount={} fee={}",
                     sourceAddress.toHexString().substring(0, 16) + "...",
                     recipients.get(i).toHexString().substring(0, 16) + "...",
                     amounts.get(i).toDecimal(9, XUnit.XDAG).toPlainString(),
                     feePerTx.toDecimal(9, XUnit.XDAG).toPlainString());
        }

        // Add source block as a link (input reference)
        List<Link> allLinks = new ArrayList<>();
        allLinks.add(Link.toBlock(sourceBlockHash));  // Source block (where funds come from)
        allLinks.addAll(transactionLinks);            // Transaction references

        // Create BlockV5 with current difficulty
        long timestamp = XdagTime.getCurrentTimestamp();
        org.apache.tuweni.units.bigints.UInt256 difficulty = chainStats.getDifficulty();

        // Coinbase = wallet default key (reward block creator)
        Bytes32 coinbase = keyPair2Hash(wallet.getDefKey());

        // Create reward block (candidate block with nonce = 0, no mining needed for reward distribution)
        BlockV5 rewardBlock = BlockV5.createCandidate(timestamp, difficulty, coinbase, allLinks);

        log.info("Created reward BlockV5: {} transactions, source={}, total_fee={}",
                 recipients.size(),
                 sourceBlockHash.toHexString().substring(0, 16) + "...",
                 totalFee.toDecimal(9, XUnit.XDAG).toPlainString());

        return rewardBlock;
    }

    /**
     * Create a genesis BlockV5 (v5.1 implementation - Phase 7.5)
     *
     * Phase 7.5: Genesis block creation for fresh node startup.
     * This is called when xdagStats.getOurLastBlockHash() == null.
     *
     * Genesis block characteristics:
     * 1. Empty links list (no previous blocks)
     * 2. Minimal difficulty (1)
     * 3. Zero nonce (no mining required for genesis)
     * 4. Coinbase set to wallet's default key
     * 5. Timestamp = current time or config genesis time
     *
     * @param key ECKeyPair for coinbase address
     * @param timestamp Genesis block timestamp
     * @return BlockV5 genesis block
     * @see BlockV5#createWithNonce(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, Bytes32, List)
     */
    public BlockV5 createGenesisBlockV5(ECKeyPair key, long timestamp) {
        // Genesis block uses minimal difficulty
        org.apache.tuweni.units.bigints.UInt256 genesisDifficulty =
            org.apache.tuweni.units.bigints.UInt256.ONE;

        // Get coinbase address from key
        Bytes32 coinbase = keyPair2Hash(key);

        // Genesis block has no links (no previous blocks)
        List<Link> emptyLinks = new ArrayList<>();

        // Create genesis block with zero nonce (no mining needed)
        BlockV5 genesisBlock = BlockV5.createWithNonce(
            timestamp,
            genesisDifficulty,
            Bytes32.ZERO,  // nonce = 0
            coinbase,
            emptyLinks
        );

        log.info("Created genesis BlockV5: epoch={}, hash={}, coinbase={}",
                 XdagTime.getEpoch(timestamp),
                 genesisBlock.getHash().toHexString(),
                 coinbase.toHexString().substring(0, 16) + "...");

        return genesisBlock;
    }

    /**
     * Create a link BlockV5 for network health (v5.1 implementation - Phase 8.3.1)
     *
     * Phase 8.3.1: Orphan health system migration to BlockV5.
     * Creates a BlockV5 that references orphan blocks to maintain network health.
     *
     * Link blocks help prevent orphan blocks from being forgotten by periodically
     * referencing them. This maintains network connectivity and reduces orphan count.
     *
     * Key differences from createMainBlockV5():
     * 1. No pretop block reference (link blocks only reference orphans)
     * 2. No mining required (nonce = 0)
     * 3. Uses all available space for orphan references
     *
     * @return BlockV5 link block ready for import
     * @see #checkOrphan()
     * @see BlockV5#createCandidate(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, List)
     * @since Phase 8.3.1 v5.1
     */
    public BlockV5 createLinkBlockV5() {
        // Get current timestamp (link blocks don't use main time alignment)
        long timestamp = XdagTime.getCurrentTimestamp();

        // Get current network difficulty
        org.apache.tuweni.units.bigints.UInt256 difficulty = chainStats.getDifficulty();

        // Get coinbase address (link block creator)
        Bytes32 coinbase = keyPair2Hash(wallet.getDefKey());

        // Get orphan blocks to reference (use ALL available link slots)
        long[] sendTime = new long[2];
        sendTime[0] = timestamp;
        List<Bytes32> orphans = orphanBlockStore.getOrphan(BlockV5.MAX_BLOCK_LINKS, sendTime);

        List<Link> links = new ArrayList<>();
        for (Bytes32 orphan : orphans) {
            links.add(Link.toBlock(orphan));
        }

        // Create link block (nonce = 0, no mining needed)
        BlockV5 linkBlock = BlockV5.createCandidate(timestamp, difficulty, coinbase, links);

        log.debug("Created BlockV5 link block: epoch={}, orphans={}",
                 XdagTime.getEpoch(timestamp), links.size());

        return linkBlock;
    }

    @Override
    public BlockV5 getBlockByHash(Bytes32 hash, boolean isRaw) {
        if (hash == null) {
            return null;
        }

        // Phase 8.3.2: Use BlockV5 version from blockStore
        try {
            return blockStore.getBlockV5ByHash(hash, isRaw);
        } catch (Exception e) {
            log.debug("Failed to get BlockV5 for hash {}: {}", hash.toHexString(), e.getMessage());
            return null;
        }
    }

    // TODO v5.1: DELETED - BlockInfo.maxDiffLink field no longer exists in v5.1 minimal design
    // Temporarily disabled - waiting for migration to v5.1 architecture
    /*
    /**
     * Get max difficulty link for BlockV5 (Phase 8.3.3)
     *
     * Returns the block with maximum difficulty link from BlockInfo.
     * For Phase 8.3.3, this internally uses Block for compatibility.
     *
     * @param block BlockV5 to get maxDiffLink from
     * @param isRaw Whether to load raw block data
     * @return Block with max difficulty (legacy, for internal use)
     */
    /*
    private BlockV5 getMaxDiffLinkV5(BlockV5 block, boolean isRaw) {
        BlockInfo info = loadBlockInfo(block);
        if (info != null && info.getMaxDiffLink() != null) {
            Bytes32 maxDiffLinkHash = info.getMaxDiffLink();
            return getBlockByHash(maxDiffLinkHash, isRaw);
        }
        return null;
    }
    */

    /**
     * Get blockchain statistics as immutable ChainStats (Phase 7.3)
     *
     * Phase 7.3: Public API uses ChainStats (immutable) directly.
     * No conversion needed - chainStats is already ChainStats.
     *
     * @return ChainStats containing current blockchain statistics
     */
    @Override
    public ChainStats getChainStats() {
        return this.chainStats;
    }

    /**
     * Increment waiting sync count (Phase 7.3 ChainStats support)
     *
     * Atomically increments the count of blocks waiting for parent blocks.
     * Used by SyncManager when adding blocks to the waiting queue.
     *
     * @since Phase 7.3 v5.1
     */
    @Override
    public synchronized void incrementWaitingSyncCount() {
        chainStats = chainStats.withWaitingSyncCount(chainStats.getWaitingSyncCount() + 1);
    }

    /**
     * Decrement waiting sync count (Phase 7.3 ChainStats support)
     *
     * Atomically decrements the count of blocks waiting for parent blocks.
     * Used by SyncManager when removing blocks from the waiting queue.
     *
     * @since Phase 7.3 v5.1
     */
    @Override
    public synchronized void decrementWaitingSyncCount() {
        chainStats = chainStats.withWaitingSyncCount(chainStats.getWaitingSyncCount() - 1);
    }

    /**
     * Update blockchain stats from remote peer statistics (Phase 7.3 ChainStats support)
     *
     * Updates global network statistics based on data received from remote peers.
     * Takes the maximum of local and remote values for network-wide metrics.
     *
     * @param remoteStats Statistics from remote peer
     * @since Phase 7.3 v5.1
     */
    @Override
    public synchronized void updateStatsFromRemote(XdagStats remoteStats) {
        // Update total hosts (take maximum)
        int maxHosts = (int) Math.max(chainStats.getTotalHostCount(), remoteStats.totalnhosts);

        // Update total blocks (take maximum)
        long maxBlocks = Math.max(chainStats.getTotalBlockCount(), remoteStats.totalnblocks);

        // Update total main blocks (take maximum)
        long maxMain = Math.max(chainStats.getTotalMainBlockCount(), remoteStats.totalnmain);

        // Update max difficulty (take maximum)
        org.apache.tuweni.units.bigints.UInt256 localMaxDiff = chainStats.getMaxDifficulty();
        org.apache.tuweni.units.bigints.UInt256 remoteMaxDiff =
            org.apache.tuweni.units.bigints.UInt256.valueOf(remoteStats.maxdifficulty);
        org.apache.tuweni.units.bigints.UInt256 newMaxDiff =
            localMaxDiff.compareTo(remoteMaxDiff) > 0 ? localMaxDiff : remoteMaxDiff;

        // Apply updates
        chainStats = chainStats
            .withTotalHostCount(maxHosts)
            .withTotalBlockCount(maxBlocks)
            .withTotalMainBlockCount(maxMain)
            .withMaxDifficulty(newMaxDiff);

        log.debug("Updated stats from remote: hosts={}, blocks={}, main={}, maxDiff={}",
                 maxHosts, maxBlocks, maxMain, newMaxDiff.toDecimalString());
    }

    /**
     * Get extended blockchain statistics (Phase 7.3)
     *
     * Phase 7.3: XdagExtStats provides detailed hash rate tracking.
     * This includes historical hash rate data for network and local node.
     *
     * @return XdagExtStats containing hash rate history
     */
    @Override
    public XdagExtStats getXdagExtStats() {
        return this.xdagExtStats;
    }

    public XAmount getReward(long nmain) {
        XAmount start = getStartAmount(nmain);
        long nanoAmount = start.toXAmount().toLong();
        return XAmount.ofXAmount(nanoAmount >> (nmain >> MAIN_BIG_PERIOD_LOG));
    }

    @Override
    public XAmount getSupply(long nmain) {
        UnsignedLong res = UnsignedLong.ZERO;
        XAmount amount = getStartAmount(nmain);
        long nanoAmount = amount.toXAmount().toLong();
        long current_nmain = nmain;
        while ((current_nmain >> MAIN_BIG_PERIOD_LOG) > 0) {
            res = res.plus(UnsignedLong.fromLongBits(1L << MAIN_BIG_PERIOD_LOG).times(long2UnsignedLong(nanoAmount)));
            current_nmain -= 1L << MAIN_BIG_PERIOD_LOG;
            nanoAmount >>= 1;
        }
        res = res.plus(long2UnsignedLong(current_nmain).times(long2UnsignedLong(nanoAmount)));
        long fork_height = kernel.getConfig().getApolloForkHeight();
        if (nmain >= fork_height) {
            // Add before apollo amount
            XAmount diff = kernel.getConfig().getMainStartAmount().subtract(kernel.getConfig().getApolloForkAmount());
            long nanoDiffAmount = diff.toXAmount().toLong();
            res = res.plus(long2UnsignedLong(fork_height - 1).times(long2UnsignedLong(nanoDiffAmount)));
        }
        return XAmount.ofXAmount(res.longValue());
    }

    /**
     * Get BlockV5 by its hash (Phase 7.3.0 - Legacy method, kept for compatibility)
     *
     * This is a legacy helper method that duplicates getBlockByHash().
     * Kept for internal callers that explicitly want BlockV5.
     *
     * @param hash Block hash
     * @param isRaw Whether to include raw block data
     * @return BlockV5 or null if not found
     */
    public BlockV5 getBlockV5ByHash(Bytes32 hash, boolean isRaw) {
        if (hash == null) {
            return null;
        }

        try {
            return blockStore.getBlockV5ByHash(hash, isRaw);
        } catch (Exception e) {
            log.debug("Failed to get BlockV5 for hash {}: {}",
                     hash.toHexString(), e.getMessage());
            return null;
        }
    }

    @Override
    public void startCheckMain(long period) {
        if (checkLoop == null) {
            return;
        }
        checkLoopFuture = checkLoop.scheduleAtFixedRate(this::checkState, 0, period, TimeUnit.MILLISECONDS);
    }

    public void checkState() {
        // Prohibit Non-mining nodes generate link blocks
        if (kernel.getConfig().getEnableGenerateBlock() &&
                (kernel.getXdagState() == XdagState.SDST || XdagState.STST == kernel.getXdagState() || XdagState.SYNC == kernel.getXdagState())) {
            checkOrphan();
        }
        checkMain();
    }

    public void checkOrphan() {
        long nblk = chainStats.getNoRefCount() / 11;
        if (nblk > 0) {
            boolean b = (nblk % 61) > CryptoProvider.nextLong(0, 61);
            nblk = nblk / 61 + (b ? 1 : 0);
        }
        while (nblk-- > 0) {
            // Phase 8.3.1: Use BlockV5 for link block creation
            // Link blocks help maintain network health by referencing orphan blocks
            BlockV5 linkBlock = createLinkBlockV5();

            // Import using working BlockV5 method
            ImportResult result = tryToConnect(linkBlock);
            if (result == IMPORTED_NOT_BEST || result == IMPORTED_BEST) {
                onNewBlockV5(linkBlock);
            }
        }
    }

    public void checkMain() {
        try {
            checkNewMain();
            // Save updated chain stats
            blockStore.saveChainStats(chainStats);
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void stopCheckMain() {
        try {

            if (checkLoopFuture != null) {
                checkLoopFuture.cancel(true);
            }
            // Shutdown thread pool
            checkLoop.shutdownNow();
            checkLoop.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error(e.getMessage(), e);
        }
    }

    public XAmount getStartAmount(long nmain) {
        XAmount startAmount;
        long forkHeight = kernel.getConfig().getApolloForkHeight();
        if (nmain >= forkHeight) {
            startAmount = kernel.getConfig().getApolloForkAmount();
        } else {
            startAmount = kernel.getConfig().getMainStartAmount();
        }

        return startAmount;
    }

    /**
     * Check if block already exists
     */
    public boolean isExist(Bytes32 hash) {
        // 1. Check main block store
        if (blockStore.hasBlock(hash)) {
            return true;
        }

        // 2. Check snapshot
        if (isExitInSnapshot(hash)) {
            return true;
        }

        // 3. Check finalized block store (Phase 2 refactor)
        if (kernel.getFinalizedBlockStore() != null &&
            kernel.getFinalizedBlockStore().hasBlock(hash)) {
            return true;
        }

        return false;
    }

    public boolean isExistInMem(Bytes32 hash) {
        return memOrphanPool.containsKey(hash);
    }

    /**
     * Check if exists in snapshot
     */
    public boolean isExitInSnapshot(Bytes32 hash) {
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled()) {
            // Query block from public key snapshot and signature snapshot
            return blockStore.hasBlockInfo(hash);
        } else {
            return false;
        }
    }

    // ========== Phase 7.3 Fix: Missing Interface Methods ==========

    /**
     * Remove orphan block from pool (Phase 7.3 continuation - BlockV5 implementation)
     *
     * v5.1 Design: Simple orphan removal. When a block is referenced by another block,
     * it's no longer an orphan and should be removed from the orphan pool.
     *
     * @param hash Block hash to remove from orphan pool
     * @param action Type of orphan removal action
     */
    public void removeOrphan(Bytes32 hash, OrphanRemoveActions action) {
        try {
            // Remove from orphan store
            orphanBlockStore.deleteByHash(hash.toArray());

            // Update stats based on action type (immutable update)
            switch (action) {
                case ORPHAN_REMOVE_NORMAL:
                    // Normal removal - block got referenced, no longer orphan
                    chainStats = chainStats.withNoRefCount(chainStats.getNoRefCount() - 1);
                    log.debug("Removed orphan block (normal): {}", hash.toHexString());
                    break;

                case ORPHAN_REMOVE_REUSE:
                    // Block reused in chain - decrease orphan count
                    chainStats = chainStats.withNoRefCount(chainStats.getNoRefCount() - 1);
                    log.debug("Removed orphan block (reuse): {}", hash.toHexString());
                    break;

                case ORPHAN_REMOVE_EXTRA:
                    // Extra block removal - update extra count
                    chainStats = chainStats.withExtraCount(chainStats.getExtraCount() - 1);
                    log.debug("Removed orphan block (extra): {}", hash.toHexString());
                    break;

                default:
                    log.warn("Unknown orphan removal action: {}", action);
                    chainStats = chainStats.withNoRefCount(chainStats.getNoRefCount() - 1);
            }

        } catch (Exception e) {
            log.error("Failed to remove orphan block: {}", hash.toHexString(), e);
        }
    }

    /**
     * Check for new main chain (Phase 7.3 continuation - v5.1 minimal implementation)
     *
     * v5.1 Design: Minimal consensus checking. In v5.1, main blocks are determined by:
     * 1. BlockInfo.height > 0 = main block
     * 2. Blocks are promoted to main chain during tryToConnect() based on difficulty
     * 3. This periodic check ensures xdagStats remains consistent
     *
     * Note: Full consensus logic (fork resolution, chain reorganization) is handled
     * during block import in tryToConnect(). This method just maintains stats.
     *
     * @since Phase 7.3 continuation
     */
    @Override
    public void checkNewMain() {
        // Phase 7.3 continuation: Minimal implementation for v5.1
        // In v5.1, main chain updates happen during tryToConnect()
        // This periodic check just ensures stats are consistent

        log.debug("checkNewMain() running - v5.1 minimal implementation (stats maintenance only)");

        // Future enhancement: Add periodic chain health checks here if needed
        // For now, tryToConnect() handles all main chain logic
    }

    /**
     * Load BlockInfo from database (Phase 7.3 continuation)
     *
     * Loads BlockInfo for a given BlockV5 from storage.
     * If BlockV5 already has info attached, returns it directly.
     *
     * @param block BlockV5 to load info for
     * @return BlockInfo or null if not found
     */
    public BlockInfo loadBlockInfo(BlockV5 block) {
        // Check if BlockV5 already has BlockInfo attached
        if (block.getInfo() != null) {
            return block.getInfo();
        }

        // Load from BlockStore using hash
        BlockV5 blockWithInfo = blockStore.getBlockV5InfoByHash(block.getHash());
        if (blockWithInfo != null && blockWithInfo.getInfo() != null) {
            return blockWithInfo.getInfo();
        }

        log.debug("BlockInfo not found for BlockV5: {}", block.getHash().toHexString());
        return null;
    }

    /**
     * Get BlockV5 by height (Phase 7.3 continuation)
     *
     * Uses BlockStore.getBlockV5ByHeight() to retrieve main block at given height.
     *
     * @param height Block height (main block number)
     * @return BlockV5 or null if not found
     */
    @Override
    public BlockV5 getBlockByHeight(long height) {
        return blockStore.getBlockV5ByHeight(height, false);
    }

    /**
     * Get blocks by time range (Phase 7.3 continuation)
     *
     * Uses BlockStore.getBlockV5sByTime() to retrieve blocks in time range.
     *
     * @param starttime Start timestamp
     * @param endtime End timestamp
     * @return List of BlockV5 in time range
     */
    @Override
    public List<BlockV5> getBlocksByTime(long starttime, long endtime) {
        return blockStore.getBlockV5sByTime(starttime, endtime);
    }

    /**
     * List main blocks (Phase 7.3 continuation)
     *
     * Returns a list of main blocks starting from the latest height.
     * Uses getBlockByHeight() to retrieve each block.
     *
     * @param count Number of blocks to list
     * @return List of main BlockV5s
     */
    @Override
    public List<BlockV5> listMainBlocks(int count) {
        List<BlockV5> result = Lists.newArrayList();

        long currentHeight = chainStats.getMainBlockCount();
        long startHeight = Math.max(1, currentHeight - count + 1);

        // Retrieve blocks from latest to oldest (or oldest to latest, depending on requirement)
        for (long height = currentHeight; height >= startHeight && height > 0; height--) {
            BlockV5 block = getBlockByHeight(height);
            if (block != null) {
                result.add(block);
            } else {
                log.warn("Main block not found at height: {}", height);
            }
        }

        log.debug("Listed {} main blocks (requested: {})", result.size(), count);
        return result;
    }

    /**
     * List mined blocks (Phase 7.3 continuation)
     *
     * Returns a list of blocks mined by this node.
     * Uses memOurBlocks to identify our blocks, then retrieves them.
     *
     * @param count Number of blocks to list
     * @return List of mined BlockV5s
     */
    @Override
    public List<BlockV5> listMinedBlocks(int count) {
        List<BlockV5> result = Lists.newArrayList();

        // memOurBlocks contains: hash -> keyIndex mapping for blocks we mined
        int collected = 0;
        for (Bytes hash : memOurBlocks.keySet()) {
            if (collected >= count) {
                break;
            }

            // Convert Bytes to Bytes32
            Bytes32 blockHash = Bytes32.wrap(hash);
            BlockV5 block = getBlockByHash(blockHash, false);

            if (block != null) {
                result.add(block);
                collected++;
            } else {
                log.warn("Mined block not found in storage: {}", blockHash.toHexString());
            }
        }

        log.debug("Listed {} mined blocks (requested: {})", result.size(), count);
        return result;
    }

    /**
     * Create main BlockV5 for mining (Phase 7.3 continuation - v5.1 design)
     *
     * v5.1 Key Changes:
     * - NO LinkBlock concept - all blocks are candidate blocks competing for main chain
     * - References prevMainBlock (last main block) + orphan blocks
     * - All blocks have nonce, coinbase, and compete for main block selection
     * - Main block = block with smallest hash in each epoch
     *
     * @return BlockV5 candidate for mining (nonce = 0, needs POW)
     */
    @Override
    public BlockV5 createMainBlockV5() {
        // 1. Get previous main block (last confirmed main block)
        long currentMainHeight = chainStats.getMainBlockCount();
        BlockV5 prevMainBlock = null;

        if (currentMainHeight > 0) {
            prevMainBlock = getBlockByHeight(currentMainHeight);
            if (prevMainBlock == null) {
                log.warn("Cannot create main BlockV5: previous main block not found at height {}", currentMainHeight);
                return null;
            }
        }

        // 2. Get current timestamp (aligned to epoch if needed)
        long timestamp = XdagTime.getCurrentTimestamp();

        // 3. Get current network difficulty
        org.apache.tuweni.units.bigints.UInt256 difficulty = chainStats.getDifficulty();

        // 4. Get coinbase address (our mining address)
        Bytes32 coinbase = keyPair2Hash(wallet.getDefKey());

        // 5. Build links: prevMainBlock + orphan blocks
        List<Link> links = new ArrayList<>();

        // Add prevMainBlock reference (if exists)
        if (prevMainBlock != null) {
            links.add(Link.toBlock(prevMainBlock.getHash()));
        }

        // Add orphan block references (for network health)
        // v5.1: No LinkBlock - just reference orphans directly
        int maxOrphans = BlockV5.MAX_BLOCK_LINKS - (prevMainBlock != null ? 1 : 0);
        long[] sendTime = new long[2];
        sendTime[0] = timestamp;
        List<Bytes32> orphans = orphanBlockStore.getOrphan(maxOrphans, sendTime);

        for (Bytes32 orphanHash : orphans) {
            links.add(Link.toBlock(orphanHash));
        }

        // 6. Create candidate block (nonce = 0, ready for mining)
        BlockV5 candidateBlock = BlockV5.createCandidate(timestamp, difficulty, coinbase, links);

        log.info("Created mining candidate BlockV5: epoch={}, prevMainHeight={}, orphans={}, hash={}",
                XdagTime.getEpoch(timestamp),
                currentMainHeight,
                orphans.size(),
                candidateBlock.getHash().toHexString().substring(0, 16) + "...");

        return candidateBlock;
    }

    /**
     * Get memory our blocks (Phase 7.3 stub)
     *
     * @return Map of our blocks
     */
    @Override
    public Map<Bytes, Integer> getMemOurBlocks() {
        return memOurBlocks;
    }

    /**
     * Get XDAG top status (Phase 7.3 stub)
     *
     * @return XdagTopStatus
     */
    @Override
    public XdagTopStatus getXdagTopStatus() {
        return xdagTopStatus;
    }

    enum OrphanRemoveActions {
        ORPHAN_REMOVE_NORMAL, ORPHAN_REMOVE_REUSE, ORPHAN_REMOVE_EXTRA
    }
}
