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
import io.xdag.config.MainnetConfig;
import io.xdag.core.XdagField.FieldType;
import io.xdag.consensus.RandomX;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PublicKey;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;
import io.xdag.db.*;
import io.xdag.db.rocksdb.RocksdbKVSource;
import io.xdag.db.rocksdb.SnapshotStoreImpl;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Listener;
import io.xdag.listener.PretopMessage;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static io.xdag.config.Constants.*;
import static io.xdag.config.Constants.MessageType.NEW_LINK;
import static io.xdag.config.Constants.MessageType.PRE_TOP;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.XdagField.FieldType.*;
import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.BytesUtils.*;
import static io.xdag.utils.WalletUtils.checkAddress;

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
    private final LinkedHashMap<Bytes, Block> memOrphanPool = new LinkedHashMap<>();
    private final Map<Bytes, Integer> memOurBlocks = new ConcurrentHashMap<>();
    
    // Stats and status tracking
    private final XdagStats xdagStats;
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
            
            this.xdagStats = new XdagStats();
            this.xdagTopStatus = new XdagTopStatus();

            if (kernel.getConfig().getSnapshotSpec().isSnapshotJ()) {
                initSnapshotJ();
            }

            // Save latest snapshot state
            blockStore.saveXdagTopStatus(xdagTopStatus);
            blockStore.saveXdagStatus(xdagStats);
            
        } else {
            // Load existing state
            XdagStats storedStats = blockStore.getXdagStatus();
            XdagTopStatus storedTopStatus = blockStore.getXdagTopStatus();
            
            if (storedStats != null) {
                storedStats.setNwaitsync(0);
                this.xdagStats = storedStats;
                this.xdagStats.nextra = 0;
            } else {
                this.xdagStats = new XdagStats();
            }
            
            this.xdagTopStatus = Objects.requireNonNullElseGet(storedTopStatus, XdagTopStatus::new);
            
            Block lastBlock = getBlockByHeight(xdagStats.nmain);
            if (lastBlock != null) {
                xdagStats.setMaxdifficulty(lastBlock.getInfo().getDifficulty().toBigInteger());
                xdagStats.setDifficulty(lastBlock.getInfo().getDifficulty().toBigInteger());
                xdagTopStatus.setTop(lastBlock.getHash().toArray());
                xdagTopStatus.setTopDiff(lastBlock.getInfo().getDifficulty().toBigInteger());
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
        Block lastBlock = blockStore.getBlockByHeight(snapshotHeight);

        // Initialize stats
        xdagStats.balance = snapshotStore.getOurBalance();
        xdagStats.setNwaitsync(0);
        xdagStats.setNnoref(0);
        xdagStats.setNextra(0);
        xdagStats.setTotalnblocks(0);
        xdagStats.setNblocks(0);
        xdagStats.setTotalnmain(snapshotHeight);
        xdagStats.setNmain(snapshotHeight);
        xdagStats.setMaxdifficulty(lastBlock.getInfo().getDifficulty().toBigInteger());
        xdagStats.setDifficulty(lastBlock.getInfo().getDifficulty().toBigInteger());

        // Initialize top status
        xdagTopStatus.setPreTop(lastBlock.getHash().toArray());
        xdagTopStatus.setTop(lastBlock.getHash().toArray());
        xdagTopStatus.setTopDiff(lastBlock.getInfo().getDifficulty().toBigInteger());
        xdagTopStatus.setPreTopDiff(lastBlock.getInfo().getDifficulty().toBigInteger());

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
                    Block refBlock = getBlockByHash(link.getTargetHash(), false);
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
                        // Record transaction history for sender (from)
                        onNewTxHistoryV2(tx.getFrom(), block.getHash(), tx.getAmount(),
                                        block.getTimestamp(), true /* isFrom */);

                        // Record transaction history for receiver (to)
                        onNewTxHistoryV2(tx.getTo(), block.getHash(), tx.getAmount(),
                                        block.getTimestamp(), false /* isFrom */);
                    }
                }
            }

            // ====================
            // Phase 5: Initialize BlockInfo (Phase 4 Step 2.3 Part 2)
            // ====================
            // For BlockV5, we need to initialize BlockInfo with basic values
            // This enables applyBlockV2() and other BlockInfo-dependent operations
            BlockInfo initialInfo = BlockInfo.builder()
                    .hash(block.getHash())
                    .timestamp(block.getTimestamp())
                    .type(0L)  // Default type
                    .flags(0)  // No flags initially
                    .height(0L)  // Will be set when block becomes main
                    .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)  // Will be calculated
                    .ref(null)  // Will be set during applyBlock
                    .maxDiffLink(null)  // Will be calculated
                    .amount(XAmount.ZERO)  // Initial amount
                    .fee(XAmount.ZERO)  // Initial fee
                    .remark(null)  // No remark
                    .isSnapshot(false)  // Not a snapshot block
                    .snapshotInfo(null)  // No snapshot info
                    .build();

            // Save initial BlockInfo to database
            blockStore.saveBlockInfoV2(initialInfo);

            // Phase 4.5: Save raw BlockV5 data to storage
            // Attach BlockInfo to BlockV5 before saving (BlockV5 is immutable)
            BlockV5 blockWithInfo = block.toBuilder().info(initialInfo).build();
            blockStore.saveBlockV5(blockWithInfo);

            log.info("BlockV5 connected and saved to storage: {}", block.getHash().toHexString());
            return result;

        } catch (Throwable e) {
            log.error("Error connecting BlockV5: " + e.getMessage(), e);
            return ImportResult.ERROR;
        }
    }


    public boolean isAccountTx(Block block) {
        List<Address> inputs = block.getInputs();
        if ( inputs != null ) {
            for (Address ref : inputs) {
                if (ref.getType() == XDAG_FIELD_INPUT) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    public boolean isTxBlock(Block block) {
        List<Address> inputs = block.getInputs();
        if ( inputs != null ) {
            for (Address ref : inputs) {
                if (ref.getType() == XDAG_FIELD_INPUT || ref.getType() == XDAG_FIELD_IN) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    // Record transaction history
    public void onNewTxHistory(Bytes32 addressHash, Bytes32 txHash, XdagField.FieldType type,
                               XAmount amount, long time, byte[] remark, boolean isAddress, int id) {
        if (txHistoryStore != null) {
            Address address = new Address(addressHash, type, amount, isAddress);
            TxHistory txHistory = new TxHistory();
            txHistory.setAddress(address);
            txHistory.setHash(BasicUtils.hash2Address(txHash));
            if (remark != null) {
                txHistory.setRemark(new String(remark, StandardCharsets.UTF_8));
            }
            txHistory.setTimestamp(time);
            try {
                if (kernel.getXdagState() == XdagState.CDST || kernel.getXdagState() == XdagState.CTST || kernel.getXdagState() == XdagState.CONN
                        || kernel.getXdagState() == XdagState.CDSTP || kernel.getXdagState() == XdagState.CTSTP || kernel.getXdagState() == XdagState.CONNP) {
                    txHistoryStore.batchSaveTxHistory(txHistory);
                } else {
                    if (!txHistoryStore.saveTxHistory(txHistory)) {
                        log.warn("tx history write to mysql fail:{}", txHistory);
                        // Mysql exception, transaction history transferred to Rocksdb
                        blockStore.saveTxHistoryToRocksdb(txHistory, id);
                    } else {
                        List<TxHistory> txHistoriesInRocksdb = blockStore.getAllTxHistoryFromRocksdb();
                        if (!txHistoriesInRocksdb.isEmpty()) {
                            for (TxHistory txHistoryInRocksdb : txHistoriesInRocksdb) {
                                txHistoryStore.batchSaveTxHistory(txHistoryInRocksdb, txHistoriesInRocksdb.size());
                            }
                            if (txHistoryStore.batchSaveTxHistory(null)) {
                                blockStore.deleteAllTxHistoryFromRocksdb();
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Record transaction history (v5.1 version for Transaction objects)
     *
     * Phase 4 Step 2.1: This version works with Transaction objects instead of Address objects.
     * Simplified design that records sender/receiver transaction history without block-level complexity.
     *
     * @param address Account address (from or to)
     * @param blockHash Block hash that includes this transaction
     * @param amount Transaction amount
     * @param timestamp Block timestamp
     * @param isFrom true if this is the sender (from), false if receiver (to)
     */
    private void onNewTxHistoryV2(Bytes32 address, Bytes32 blockHash, XAmount amount,
                                   long timestamp, boolean isFrom) {
        if (txHistoryStore == null) {
            return;
        }

        try {
            // Create transaction history record
            // For v5.1, we use a simplified model:
            // - isFrom=true → sender (similar to XDAG_FIELD_OUTPUT for V1 - money going out)
            // - isFrom=false → receiver (similar to XDAG_FIELD_INPUT for V1 - money coming in)
            XdagField.FieldType fieldType = isFrom ? XDAG_FIELD_OUTPUT : XDAG_FIELD_INPUT;

            // Create Address wrapper for TxHistory (temporary compatibility layer)
            // TODO Phase 4 Step 2.4: Update TxHistory to use Bytes32 addresses directly
            Address addressWrapper = new Address(address, fieldType, amount, true);

            TxHistory txHistory = new TxHistory();
            txHistory.setAddress(addressWrapper);
            txHistory.setHash(BasicUtils.hash2Address(blockHash));
            txHistory.setTimestamp(timestamp);
            // No remark for Transaction-based history (remark is block-level, not tx-level)

            // Save transaction history (same logic as V1)
            if (kernel.getXdagState() == XdagState.CDST || kernel.getXdagState() == XdagState.CTST ||
                kernel.getXdagState() == XdagState.CONN || kernel.getXdagState() == XdagState.CDSTP ||
                kernel.getXdagState() == XdagState.CTSTP || kernel.getXdagState() == XdagState.CONNP) {
                // Batch mode during sync
                txHistoryStore.batchSaveTxHistory(txHistory);
            } else {
                // Normal mode with fallback to RocksDB
                if (!txHistoryStore.saveTxHistory(txHistory)) {
                    log.warn("tx history write to mysql fail (V2): {}", txHistory);
                    // MySQL exception, fall back to RocksDB
                    // Note: For V2, we don't have an ID field, so we use 0 as placeholder
                    blockStore.saveTxHistoryToRocksdb(txHistory, 0);
                } else {
                    // Check if there are pending entries in RocksDB and flush them
                    List<TxHistory> txHistoriesInRocksdb = blockStore.getAllTxHistoryFromRocksdb();
                    if (!txHistoriesInRocksdb.isEmpty()) {
                        for (TxHistory txHistoryInRocksdb : txHistoriesInRocksdb) {
                            txHistoryStore.batchSaveTxHistory(txHistoryInRocksdb, txHistoriesInRocksdb.size());
                        }
                        if (txHistoryStore.batchSaveTxHistory(null)) {
                            blockStore.deleteAllTxHistoryFromRocksdb();
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error recording transaction history (V2): " + e.getMessage(), e);
        }
    }

    // Get transaction history by address
    public List<TxHistory> getBlockTxHistoryByAddress(Bytes32 addressHash, int page, Object... parameters) {
        List<TxHistory> txHistory = Lists.newArrayList();
        if (txHistoryStore != null) {
            try {
                txHistory.addAll(txHistoryStore.listTxHistoryByAddress(checkAddress(addressHash) ?
                        BasicUtils.hash2PubAddress(addressHash) : BasicUtils.hash2Address(addressHash), page, parameters));
            } catch (Exception e) {
                log.error(e.getMessage(), e);
            }
        }
        return txHistory;
    }

    // Check if should use sync fix fork
    public boolean isSyncFixFork(long currentHeight) {
        long syncFixHeight = SYNC_FIX_HEIGHT;
        return currentHeight >= syncFixHeight;
    }

    /**
     * Find common ancestor block during fork resolution (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
     *             refactor and system restart with BlockV5-only storage, all main chain management
     *             should work with BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After complete refactor, this method will process BlockV5 objects from storage.
     *             No code changes needed if Block/BlockV5 interface is compatible. May require
     *             BlockV5-specific version if chain reorganization logic differs significantly.
     *
     *             <p><b>Impact:</b>
     *             This method is critical for blockchain fork resolution. It finds the common
     *             ancestor between the current main chain and a new candidate chain. Used by
     *             {@link #tryToConnect(Block)} during fork handling.
     *
     * @param block The new block triggering fork resolution
     * @param isFork Whether this is a fork scenario (affects BI_MAIN_CHAIN flag updates)
     * @return The common ancestor block, or null if not found
     * @see #tryToConnect(Block)
     * @see #unWindMain(Block)
     * @see #updateNewChain(Block, boolean)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public Block findAncestor(Block block, boolean isFork) {
        Block blockRef;
        Block blockRef0 = null;

        // Find highest difficulty non-main chain block
        // Continue traversal until we hit a block with BI_MAIN flag (main block) or null
        for (blockRef = block;
             blockRef != null && ((blockRef.getInfo().getFlags() & BI_MAIN) == 0);
             blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);

            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().toBigInteger().compareTo(calculateBlockDiff(tmpRef, calculateCurrentBlockDiff(tmpRef))) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                if (!isFork) {
                    updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                }
                blockRef0 = blockRef;
            }
        }

        // Handle fork point
        if (blockRef != null
                && blockRef0 != null
                && !blockRef.equals(blockRef0)
                && XdagTime.getEpoch(blockRef.getTimestamp()) == XdagTime.getEpoch(blockRef0.getTimestamp())) {
            blockRef = getMaxDiffLink(blockRef, false);
        }
        return blockRef;
    }

    /**
     * Update new chain after fork (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
     *             refactor and system restart with BlockV5-only storage, all main chain management
     *             should work with BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After complete refactor, this method will process BlockV5 objects from storage.
     *             No code changes needed if Block/BlockV5 interface is compatible. May require
     *             BlockV5-specific version if chain reorganization logic differs significantly.
     *
     *             <p><b>Impact:</b>
     *             This method is critical for blockchain fork resolution. It updates BI_MAIN_CHAIN
     *             flags for the new candidate chain after finding the common ancestor. Used by
     *             {@link #tryToConnect(Block)} during fork handling.
     *
     * @param block The new block that triggered fork resolution
     * @param isFork Whether this is a fork scenario (if false, method returns immediately)
     * @see #tryToConnect(Block)
     * @see #findAncestor(Block, boolean)
     * @see #unWindMain(Block)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public void updateNewChain(Block block, boolean isFork) {
        if (!isFork) {
            return;
        }
        Block blockRef;
        Block blockRef0 = null;

        // Update main chain flags
        // Continue traversal until we hit a block with BI_MAIN flag (main block) or null
        for (blockRef = block;
             blockRef != null && ((blockRef.getInfo().getFlags() & BI_MAIN) == 0);
             blockRef = getMaxDiffLink(blockRef, false)) {
            Block tmpRef = getMaxDiffLink(blockRef, false);

            if (
                    (tmpRef == null
                            || blockRef.getInfo().getDifficulty().toBigInteger().compareTo(calculateBlockDiff(tmpRef, calculateCurrentBlockDiff(tmpRef))) > 0) &&
                            (blockRef0 == null || XdagTime.getEpoch(blockRef0.getTimestamp()) > XdagTime
                                    .getEpoch(blockRef.getTimestamp()))
            ) {
                // Only set BI_MAIN_CHAIN for EXTRA blocks (mining candidates)
                // Non-EXTRA blocks (like address blocks) should not be candidates for main chain
                if ((blockRef.getInfo().getFlags() & BI_EXTRA) != 0 || (blockRef.getInfo().getFlags() & BI_MAIN) != 0) {
                    updateBlockFlag(blockRef, BI_MAIN_CHAIN, true);
                    blockRef0 = blockRef;
                }
            }
        }
    }

    // Process extra blocks
    public void processExtraBlock() {
        if (memOrphanPool.size() > MAX_ALLOWED_EXTRA) {
            Block reuse = memOrphanPool.entrySet().iterator().next().getValue();
            log.debug("Remove when extra too big");
            removeOrphan(reuse.getHash(), OrphanRemoveActions.ORPHAN_REMOVE_REUSE);
            xdagStats.nblocks--;
            xdagStats.totalnblocks = Math.max(xdagStats.nblocks, xdagStats.totalnblocks);

            if ((reuse.getInfo().getFlags() & BI_OURS) != 0) {
                removeOurBlock(reuse);
            }
        }
    }

    // Notify listeners of new pretop
    protected void onNewPretop() {
        for (Listener listener : listeners) {
            listener.onMessage(new PretopMessage(Bytes.wrap(xdagTopStatus.getTop()), PRE_TOP));
        }
    }

    // Notify listeners of new block
    protected void onNewBlock(Block block) {
        for (Listener listener : listeners) {
            listener.onMessage(new BlockMessage(Bytes.wrap(block.getXdagBlock().getData()), NEW_LINK));
        }
    }

    // Check and update main chain
    @Override
    public synchronized void checkNewMain() {
        Block p = null;
        int i = 0;

        log.debug("checkNewMain: top={}, nmain={}",
                 xdagTopStatus.getTop() == null ? "null" : Bytes32.wrap(xdagTopStatus.getTop()).toHexString(),
                 xdagStats.nmain);

        // If it's a snapshot point main block, return directly since data before snapshot is already determined
        if (xdagTopStatus.getTop() != null) {
            Block topBlock = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);

            for (Block block = topBlock; block != null
                    && ((block.getInfo().getFlags() & BI_MAIN) == 0);
                 block = getMaxDiffLink(getBlockByHash(block.getHash(), true), true)) {

                long epoch = XdagTime.getEpoch(block.getTimestamp());
                log.debug("checkNewMain: checking block={}, epoch={}, flags={}, hasMainChain={}",
                         block.getHash().toHexString(),
                         epoch,
                         block.getInfo().getFlags(),
                         (block.getInfo().getFlags() & BI_MAIN_CHAIN) != 0);

                if ((block.getInfo().getFlags() & BI_MAIN_CHAIN) != 0) {
                    p = block;
                    ++i;
                }
            }
        }
        long ct = XdagTime.getCurrentTimestamp();

        log.debug("checkNewMain: found {} candidates, p={}, BI_REF={}, time check={}",
                 i,
                 p == null ? "null" : p.getHash().toHexString(),
                 p != null && ((p.getInfo().getFlags() & BI_REF) != 0),
                 p != null && (ct >= p.getTimestamp() + 2 * 1024));

        if (p != null
                && ((p.getInfo().getFlags() & BI_REF) != 0)
                && i > 1
                && ct >= p.getTimestamp() + 2 * 1024) {
            log.info("setMain success block:{}", p.getHash().toHexString());
            setMain(p);
        }
    }

    @Override
    public long getLatestMainBlockNumber() {
        return xdagStats.nmain;
    }

    /**
     * Rollback main chain to specified block (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
     *             refactor and system restart with BlockV5-only storage, all main chain management
     *             should work with BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After complete refactor, this method will process BlockV5 objects from storage.
     *             No code changes needed if Block/BlockV5 interface is compatible. May require
     *             BlockV5-specific version if chain reorganization logic differs significantly.
     *
     *             <p><b>Impact:</b>
     *             This method is critical for blockchain fork resolution. It unwinds the main chain
     *             back to the common ancestor by clearing BI_MAIN_CHAIN flags and calling
     *             {@link #unSetMain(Block)} for main blocks. Used by {@link #tryToConnect(Block)}
     *             during fork handling.
     *
     * @param block The common ancestor block to unwind to (or null to unwind all)
     * @see #tryToConnect(Block)
     * @see #findAncestor(Block, boolean)
     * @see #updateNewChain(Block, boolean)
     * @see #unSetMain(Block)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public void unWindMain(Block block) {
        log.debug("Unwind main to block,{}", block == null ? "null" : block.getHash().toHexString());

        // If block is null and there are no main blocks yet, don't clear BI_MAIN_CHAIN flags
        // This happens when importing the first few extra blocks before any become main
        if (block == null && xdagStats.nmain == 0) {
            return;
        }

        if (xdagTopStatus.getTop() != null) {
            log.debug("now pretop : {}", xdagTopStatus.getPreTop() == null ? "null" : Bytes32.wrap(xdagTopStatus.getPreTop()).toHexString());
            for (Block tmp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), true); tmp != null
                    && !blockEqual(block, tmp); tmp = getMaxDiffLink(tmp, true)) {
                updateBlockFlag(tmp, BI_MAIN_CHAIN, false);
                // Update corresponding flag information
                if ((tmp.getInfo().getFlags() & BI_MAIN) != 0) {
                    unSetMain(tmp);
                    // Fix: Need to update block info in database like height 210729
                    blockStore.saveBlockInfo(tmp.getInfo().toLegacy());
                }
            }
        }
    }

    private boolean blockEqual(Block block1, Block block2) {
        if (block1 == null) {
            return block2 == null;
        } else {
            return block2.equals(block1);
        }
    }

    // ========== Phase 4 Step 2.2/2.3: BlockV5 applyBlock() Support ==========

    /**
     * Execute BlockV5 and return gas fee (v5.1 implementation)
     *
     * Phase 4 Step 2.2: Transaction execution (Complete)
     * Phase 4 Step 2.3 Part 2: Block link recursive processing (Complete)
     *
     * Key differences from V1:
     * 1. Uses List<Link> instead of List<Address>
     * 2. Transaction amount/fee retrieved from TransactionStore
     * 3. Handles from/to balance updates for Transactions
     * 4. BlockV5 is immutable, so BlockInfo updates are done in database directly
     *
     * @param flag true if this is the main block, false for recursive calls
     * @param block BlockV5 to execute
     * @return collected gas fees (or -1 if already processed)
     */
    private XAmount applyBlockV2(boolean flag, BlockV5 block) {
        XAmount gas = XAmount.ZERO;

        // Phase 4 Step 2.3 Part 2: Check if already processed (BI_MAIN_REF flag)
        BlockInfo blockInfo = loadBlockInfo(block);
        if (blockInfo != null && (blockInfo.getFlags() & BI_MAIN_REF) != 0) {
            return XAmount.ZERO.subtract(XAmount.ONE);  // -1 indicates already processed
        }

        // Mark as BI_MAIN_REF (processing started)
        updateBlockV5Flag(block, BI_MAIN_REF, true);

        List<Link> links = block.getLinks();
        if (links == null || links.isEmpty()) {
            // No links to process, mark as applied
            updateBlockV5Flag(block, BI_APPLIED, true);
            return XAmount.ZERO;
        }

        // Phase 1: Process Block links recursively first
        for (Link link : links) {
            if (link.isBlock()) {
                // Block link: Recursive processing
                Block refBlock = getBlockByHash(link.getTargetHash(), false);
                if (refBlock == null) {
                    log.error("Block not found during apply: {}", link.getTargetHash().toHexString());
                    return XAmount.ZERO;
                }

                XAmount ret;
                BlockInfo refInfo = refBlock.getInfo();

                // Check if already processed
                if (refInfo != null && (refInfo.getFlags() & BI_MAIN_REF) != 0) {
                    ret = XAmount.ZERO.subtract(XAmount.ONE);  // -1 indicates already processed
                } else {
                    // Recursively process (need full data)
                    refBlock = getBlockByHash(link.getTargetHash(), true);

                    // For Phase 4 Step 2.3 Part 2: Referenced blocks are legacy Block objects
                    // Once all blocks are migrated to BlockV5, this can call applyBlockV2() recursively
                    ret = applyBlock(false, refBlock);
                }

                // Skip if already processed
                if (ret.equals(XAmount.ZERO.subtract(XAmount.ONE))) {
                    continue;
                }

                // Accumulate gas from recursively processed blocks
                sumGas = sumGas.add(ret);

                // Update ref field (only for top-level mainBlock)
                if (flag) {
                    updateBlockV5Ref(refBlock, block.getHash());
                }

                // Add collected gas to mainBlock's fee (only for top-level mainBlock)
                if (flag && sumGas.compareTo(XAmount.ZERO) != 0) {
                    // For BlockV5, we can't modify the block, but we update BlockInfo in database
                    BlockInfo mainInfo = loadBlockInfo(block);
                    if (mainInfo != null) {
                        BlockInfo updatedInfo = mainInfo.toBuilder()
                            .fee(mainInfo.getFee().add(sumGas))
                            .amount(mainInfo.getAmount().add(sumGas))
                            .build();
                        saveBlockInfo(updatedInfo);
                    }
                    sumGas = XAmount.ZERO;
                }
            }
        }

        // Phase 2: Process Transaction links
        for (Link link : links) {
            if (link.isTransaction()) {
                // Transaction execution
                Transaction tx = transactionStore.getTransaction(link.getTargetHash());
                if (tx == null) {
                    log.error("Transaction not found during apply: {}", link.getTargetHash().toHexString());
                    return XAmount.ZERO;
                }

                // Subtract from sender (from address)
                XAmount fromBalance = addressStore.getBalanceByAddress(tx.getFrom().toArray());
                XAmount totalDeduction = tx.getAmount().add(tx.getFee());

                if (fromBalance.compareTo(totalDeduction) < 0) {
                    log.debug("Insufficient balance for tx {}: balance={}, need={}",
                             tx.getHash().toHexString(), fromBalance, totalDeduction);
                    return XAmount.ZERO;
                }

                addressStore.updateBalance(tx.getFrom().toArray(), fromBalance.subtract(totalDeduction));
                log.debug("applyBlockV2: Subtract from={}, amount={}, fee={}",
                         tx.getFrom().toHexString(), tx.getAmount(), tx.getFee());

                // Add to receiver (to address)
                XAmount toBalance = addressStore.getBalanceByAddress(tx.getTo().toArray());
                addressStore.updateBalance(tx.getTo().toArray(), toBalance.add(tx.getAmount()));
                log.debug("applyBlockV2: Add to={}, amount={}",
                         tx.getTo().toHexString(), tx.getAmount());

                // Collect gas fee
                gas = gas.add(tx.getFee());
            }
        }

        // Mark block as BI_APPLIED (processing complete)
        updateBlockV5Flag(block, BI_APPLIED, true);

        log.debug("applyBlockV2: Completed with gas={}", gas);
        return gas;
    }

    // ========== Phase 4 Step 2.3: BlockV5 BlockInfo Helper Methods ==========

    /**
     * Load BlockInfo for BlockV5 (from block or database)
     *
     * Phase 4 Step 2.3 Part 2: Helper method to get BlockInfo for immutable BlockV5.
     * Since BlockV5 is immutable and info field might be null, we need to load from database.
     *
     * @param block BlockV5 instance
     * @return BlockInfo (may be null if not found)
     */
    private BlockInfo loadBlockInfo(BlockV5 block) {
        // First try to get from block itself
        if (block.getInfo() != null) {
            return block.getInfo();
        }

        // Load from database
        Block blockFromStore = blockStore.getBlockInfoByHash(block.getHash());
        if (blockFromStore != null) {
            return blockFromStore.getInfo();
        }

        return null;
    }

    /**
     * Update BlockV5 flag in database
     *
     * Phase 4 Step 2.3 Part 2: Update BlockInfo flags for immutable BlockV5.
     * Since we can't modify BlockV5 directly, we update BlockInfo in database.
     *
     * @param block BlockV5 instance
     * @param flag Flag to update (BI_MAIN_REF, BI_APPLIED, etc.)
     * @param direction true to set flag, false to clear flag
     */
    private void updateBlockV5Flag(BlockV5 block, byte flag, boolean direction) {
        BlockInfo info = loadBlockInfo(block);
        if (info == null) {
            log.warn("BlockInfo not found for BlockV5: {}", block.getHash().toHexString());
            return;
        }

        int newFlags;
        if (direction) {
            newFlags = info.getFlags() | flag;
        } else {
            newFlags = info.getFlags() & ~flag;
        }

        BlockInfo updatedInfo = info.toBuilder()
            .flags(newFlags)
            .build();

        saveBlockInfo(updatedInfo);
    }

    /**
     * Update BlockV5 ref field in database
     *
     * Phase 4 Step 2.3 Part 2: Update BlockInfo.ref for BlockV5.
     *
     * @param refBlock Referenced block (can be Block or BlockV5)
     * @param mainBlockHash Hash of the main block that references this block
     */
    private void updateBlockV5Ref(Block refBlock, Bytes32 mainBlockHash) {
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

    /**
     * Save BlockInfo to database (v5.1 version)
     *
     * Phase 4 Step 2.3 Part 2: Save BlockInfo using V2 serialization.
     *
     * @param info BlockInfo to save
     */
    private void saveBlockInfo(BlockInfo info) {
        blockStore.saveBlockInfoV2(info);
    }

    /**
     * Execute block and return gas fee
     */
    private XAmount applyBlock(boolean flag, Block block) {
        XAmount gas = XAmount.ZERO;
        XAmount sumIn = XAmount.ZERO;
        XAmount sumOut = XAmount.ZERO; // sumOut is used to pay gas fee for other blocks linking to this one, currently set to 0
        // Block already processed
        if ((block.getInfo().getFlags() & BI_MAIN_REF) != 0) {
            return XAmount.ZERO.subtract(XAmount.ONE);
        }
        // TX block created by wallet or pool will not set fee = minGas, set here
        if (!block.getInputs().isEmpty() && block.getFee().equals(XAmount.ZERO)) {
            block.setInfo(block.getInfo().withFee(MIN_GAS));
        }
        // Mark as processed
        Bytes32 blockHash = block.getHash();

        updateBlockFlag(block, BI_MAIN_REF, true);

        List<Address> links = block.getLinks();
        if (links == null || links.isEmpty()) {
            updateBlockFlag(block, BI_APPLIED, true);
            return XAmount.ZERO;
        }

        for (Address link : links) {
            if (!link.isAddress) {
                // No need to get full data during pre-processing
                Block ref = getBlockByHash(link.getAddress(), false);
                XAmount ret;
                // If already processed
                if ((ref.getInfo().getFlags() & BI_MAIN_REF) != 0) {
                    ret = XAmount.ZERO.subtract(XAmount.ONE);
                } else {
                    // Check if this is the first time this block is being processed by main chain
                    boolean wasUnreferenced = (ref.getInfo().getFlags() & BI_REF) != 0 && (ref.getInfo().getFlags() & BI_EXTRA) == 0;

                    ref = getBlockByHash(link.getAddress(), true);
                    ret = applyBlock(false, ref);

                    // If this non-EXTRA block was referenced by an EXTRA block (BI_REF=true)
                    // and is now being processed by main chain for the first time,
                    // decrement nnoref and remove from orphanBlockStore
                    if (wasUnreferenced && (ref.getInfo().getFlags() & BI_EXTRA) == 0) {
                        orphanBlockStore.deleteByHash(ref.getHash().toArray());
                        xdagStats.nnoref--;
                    }
                }
                if (ret.equals(XAmount.ZERO.subtract(XAmount.ONE))) {
                    continue;
                }
                sumGas = sumGas.add(ret);
                // CRITICAL FIX: Only set ref field for top-level mainBlock (flag==true)
                // During recursive calls (flag==false), the 'block' parameter is NOT the mainBlock
                // but rather the recursively processed block itself, which would incorrectly set ref to itself
                if (flag) {
                    updateBlockRef(ref, new Address(block));
                }
                if (flag && sumGas != XAmount.ZERO) {// Check if block is mainBlock, if true: add fee!
                    block.setInfo(block.getInfo().withFee(block.getFee().add(sumGas)));
                    addAndAccept(block, sumGas);
                    sumGas = XAmount.ZERO;
                }
            }
        }

        for (Address link : links) {
            MutableBytes32 linkAddress = link.getAddress();
            if (link.getType() == XDAG_FIELD_IN) {
                /*
                 * Compatible with two transfer modes.
                 * When input is a block, use original processing method.
                 * When input is an address, get balance from database for verification.
                 */
                if (!link.isAddress) {
                    Block ref = getBlockByHash(linkAddress, false);
                    if (compareAmountTo(ref.getInfo().getAmount(), link.getAmount()) < 0) {
                        log.debug("This input ref doesn't have enough amount,hash:{},amount:{},need:{}",
                                Hex.toHexString(ref.getInfo().getHash().toArray()), ref.getInfo().getAmount(),
                                link.getAmount());
                        return XAmount.ZERO;
                    }
                } else {
                    log.debug("Type error");
                    return XAmount.ZERO;
                }

                // Verify in advance that Address amount is not negative
                if (compareAmountTo(sumIn.add(link.getAmount()), sumIn) < 0) {
                    log.debug("This input ref's amount less than 0");
                    return XAmount.ZERO;
                }
                sumIn = sumIn.add(link.getAmount());
            } else if (link.getType() == XDAG_FIELD_INPUT) {
                XAmount balance = addressStore.getBalanceByAddress(hash2byte(link.getAddress()).toArray());
                UInt64 executedNonce = addressStore.getExecutedNonceNum(BytesUtils.byte32ToArray(link.getAddress()).toArray());
                UInt64 blockNonce = block.getTxNonceField().getTransactionNonce();

                if (blockNonce.compareTo(executedNonce.add(UInt64.ONE)) > 0) {
                    addressStore.updateTxQuantity(BytesUtils.byte32ToArray(link.getAddress()).toArray(), executedNonce);
                    log.debug("The current situation belongs to a nonce fault, and nonce is rolled back to the current number of executed nonce {}",executedNonce.toLong());
                    return XAmount.ZERO.subtract(XAmount.ONE);
                }

                if(blockNonce.compareTo(executedNonce) <= 0) {
                    if (blockNonce.compareTo(executedNonce) == 0) {
                        log.debug("The sending transaction speed is too fast, resulting in multiple transactions corresponding to the same nonce, " +
                                "and another faster transaction of the nonce has already been executed. Please avoid sending transactions continuously so quickly, " +
                                "which may cause transaction execution failure");
                    } else {
                        log.debug("The current network computing power fluctuates greatly, it is recommended to wait for a period of time before sending transactions");
                    }

                    return XAmount.ZERO.subtract(XAmount.ONE);
                }

                if (compareAmountTo(balance, link.amount) < 0) {
                    log.debug("This input ref doesn't have enough amount,hash:{},amount:{},need:{}",
                            hash2byte(link.getAddress()).toHexString(), balance,
                            link.getAmount());
                    processNonceAfterTransactionExecution(link);
                    return XAmount.ZERO;
                }
                // Verify in advance that Address amount is not negative
                if (compareAmountTo(sumIn.add(link.getAmount()), sumIn) < 0) {
                    log.debug("This input ref's:{} amount less than 0", linkAddress.toHexString());
                    processNonceAfterTransactionExecution(link);
                    return XAmount.ZERO;
                }
                sumIn = sumIn.add(link.getAmount());
            } else {
                // Verify in advance that Address amount is not negative
                if (compareAmountTo(sumOut.add(link.getAmount()), sumOut) < 0) {
                    log.debug("This output ref's:{} amount less than 0", linkAddress.toHexString());
                    for(Address checkINlink : links){
                        if (checkINlink.getType() == XDAG_FIELD_INPUT){
                            Bytes address = BytesUtils.byte32ToArray(checkINlink.getAddress());
                            UInt64 currentExeNonce = addressStore.getExecutedNonceNum(address.toArray());
                            UInt64 nonceInTx = block.getTxNonceField().getTransactionNonce();
                            if (nonceInTx.compareTo(currentExeNonce.add(UInt64.ONE)) == 0) {
                                log.debug("The amount given by account {} to the transferring party is negative, resulting in the failure of the {} - th transaction execution of this account",
                                        hash2PubAddress(checkINlink.getAddress()),nonceInTx.intValue()
                                );
                                processNonceAfterTransactionExecution(checkINlink);
                            }
                        }
                    }
                    return XAmount.ZERO;
                }
                sumOut = sumOut.add(link.getAmount());
            }
        }
        if (compareAmountTo(block.getInfo().getAmount().add(sumIn), sumOut) < 0 ||
                compareAmountTo(block.getInfo().getAmount().add(sumIn), sumIn) < 0
        ) {
            log.debug("block:{} exec fail!", blockHash.toHexString());
            if (block.getInputs() != null) processNonceAfterTransactionExecution(block.getInputs().getFirst());
            return XAmount.ZERO;
        }

        for (Address link : links) {
            MutableBytes32 linkAddress = link.addressHash;
            if (!link.isAddress) {
                Block ref = getBlockByHash(linkAddress, false);
                if (link.getType() == XDAG_FIELD_IN) {
                    subtractAndAccept(ref, link.getAmount());
                    XAmount allBalance = addressStore.getAllBalance();
                    allBalance = allBalance.add(link.getAmount().subtract(block.getFee()));
                    addressStore.updateAllBalance(allBalance);
                } else if (!flag) {// When recursively returning to first layer, ref is previous main block (output) type, deduction not allowed
                    addAndAccept(ref, link.getAmount().subtract(block.getFee()));
                    gas = gas.add(block.getFee()); // Mark the output for Fee
                }
            } else {
                if (link.getType() == XDAG_FIELD_INPUT) {
                    subtractAmount(BasicUtils.hash2byte(linkAddress), link.getAmount(), block);
                    processNonceAfterTransactionExecution(link);
                } else if (link.getType() == XDAG_FIELD_OUTPUT) {
                    addAmount(BasicUtils.hash2byte(linkAddress), link.getAmount().subtract(block.getFee()), block);
                    gas = gas.add(block.getFee()); // Mark the output for Fee
                }
            }
        }

        // Not necessarily greater than 0 since some amount may be deducted
        updateBlockFlag(block, BI_APPLIED, true);
        return gas;
    }

    // TODO: unapply block which in snapshot
    public XAmount unApplyBlock(Block block) {
        List<Address> links = block.getLinks();
        Collections.reverse(links); // must be reverse
        XAmount sum = XAmount.ZERO;  // Initialize sum at method level to track all reverted amounts

        if ((block.getInfo().getFlags() & BI_APPLIED) != 0) {
            // TX block created by wallet or pool will not set fee = minGas, set here
            if (!block.getInputs().isEmpty() && block.getFee().equals(XAmount.ZERO)) {
                block.setInfo(block.getInfo().withFee(MIN_GAS));
            }

            for (Address link : links) {
                if (!link.isAddress) {
                    Block ref = getBlockByHash(link.getAddress(), false);
                    if (link.getType() == XDAG_FIELD_IN) {
                        addAndAccept(ref, link.getAmount());
                        sum = sum.subtract(link.getAmount());
                        XAmount allBalance = addressStore.getAllBalance();
                        // allBalance = allBalance.subtract(link.getAmount()); //fix subtract twice.
                        try {
                            allBalance = allBalance.subtract(link.getAmount().subtract(block.getFee()));
                        } catch (Exception e) {
                            log.debug("allBalance rollback");
                        }
                        addressStore.updateAllBalance(allBalance);
                    } else if (link.getType() == XDAG_FIELD_OUT) {
                        // When add amount in 'Apply' subtract fee, so unApply also subtract fee
                        subtractAndAccept(ref, link.getAmount().subtract(block.getFee()));
                        sum = sum.add(link.getAmount());
                    }
                } else {
                    if (link.getType() == XDAG_FIELD_INPUT) {
                        addAmount(BasicUtils.hash2byte(link.getAddress()), link.getAmount(), block);
                        sum = sum.subtract(link.getAmount());
                        Bytes address = byte32ToArray(link.getAddress());
                        UInt64 exeNonce = addressStore.getExecutedNonceNum(address.toArray());
                        addressStore.updateExcutedNonceNum(address.toArray(), false);
                        addressStore.updateTxQuantity(address.toArray(), exeNonce.subtract(UInt64.ONE));
                    } else {
                        // When add amount in 'Apply' subtract fee, so unApply also subtract fee
                        subtractAmount(BasicUtils.hash2byte(link.getAddress()), link.getAmount().subtract(block.getFee()), block);
                        sum = sum.add(link.getAmount());
                    }
                }

            }
            updateBlockFlag(block, BI_APPLIED, false);
        } else {
            //When rolling back, the unaccepted transactions in the main block need to be processed, which is the number of confirmed transactions sent corresponding to their account addresses, nonce, needs to be reduced by one
            for(Address link : links) {
                if (link.isAddress && link.getType() == XDAG_FIELD_INPUT){
                    Bytes address = byte32ToArray(link.getAddress());
                    UInt64 blockNonce = block.getTxNonceField().getTransactionNonce();
                    UInt64 exeNonce = addressStore.getExecutedNonceNum(address.toArray());
                    if (blockNonce.compareTo(exeNonce) == 0) {
                        addressStore.updateExcutedNonceNum(address.toArray(), false);
                        addressStore.updateTxQuantity(address.toArray(), exeNonce.subtract(UInt64.ONE));
                        log.debug("The transaction processed quantity of account {} is reduced by one, and the number of transactions processed now is nonce = {}",
                            Base58.encodeCheck(BytesUtils.byte32ToArray(link.getAddress())), addressStore.getExecutedNonceNum(address.toArray()).intValue()
                        );
                    }

                }
            }
        }
        updateBlockFlag(block, BI_MAIN_REF, false);
        updateBlockRef(block, null);

        for (Address link : links) {
            if (!link.isAddress) {
                Block ref = getBlockByHash(link.getAddress(), false);
                // Even if mainBlock duplicate links the TX_block which other mainBlock handled, we can check if this TX ref is this mainBlock
                if (ref.getInfo().getRef() != null
                        && equalBytes(ref.getInfo().getRef().toArray(), block.getHash().toArray())
                        && ((ref.getInfo().getFlags() & BI_MAIN_REF) != 0)) {
                    XAmount recursiveAmount = unApplyBlock(getBlockByHash(ref.getHash(), true));
                    addAndAccept(block, recursiveAmount);
                    sum = sum.add(recursiveAmount);
                }
            }
        }
        return sum;  // Return the accumulated sum instead of ZERO
    }

    /**
     * Set the main chain with block as the main block - either fork or extend (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
     *             refactor and system restart with BlockV5-only storage, all main chain management
     *             should work with BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After complete refactor, this method will process BlockV5 objects from storage.
     *             No code changes needed if Block/BlockV5 interface is compatible. May require
     *             BlockV5-specific version if chain management logic differs significantly.
     *
     *             <p><b>Impact:</b>
     *             This method is critical for blockchain main chain management. It sets a block as
     *             the main block (either extending or forking the chain), accepts block reward,
     *             applies transactions recursively, and collects fees. Used by {@link #checkNewMain()}
     *             during main chain consensus.
     *
     * @param block The block to set as main block
     * @see #checkNewMain()
     * @see #unSetMain(Block)
     * @see #applyBlock(boolean, Block)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public void setMain(Block block) {

        synchronized (this) {
            // Remove from memOrphanPool if it's an EXTRA block
            if ((block.getInfo().getFlags() & BI_EXTRA) != 0) {
                memOrphanPool.remove(block.getHash());
                updateBlockFlag(block, BI_EXTRA, false);
                xdagStats.nextra--;
            }

            // Set reward
            long mainNumber = xdagStats.nmain + 1;
            log.debug("mainNumber = {},hash = {}", mainNumber, Hex.toHexString(block.getInfo().getHash().toArray()));
            XAmount reward = getReward(mainNumber);
            block.setInfo(block.getInfo().withHeight(mainNumber));
            updateBlockFlag(block, BI_MAIN, true);

            // Accept reward
            acceptAmount(block, reward);
            xdagStats.nmain++;

            // Recursively execute blocks referenced by main block and get fees
            XAmount mainBlockFee = applyBlock(true, block); //the mainBlock may have tx, return the fee to itself.
            if (!mainBlockFee.equals(XAmount.ZERO)) {// normal mainBlock will not go into this
                acceptAmount(block, mainBlockFee); //add the fee
                block.setInfo(block.getInfo().withFee(mainBlockFee));
            }
            // Main block REF points to itself
            // TODO: Add fee
            updateBlockRef(block, new Address(block));

            if (randomx != null) {
                randomx.randomXSetForkTime(block);
            }
        }

    }

    /**
     * Cancel Block main block status (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
     *             refactor and system restart with BlockV5-only storage, all main chain management
     *             should work with BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
     *                   making this Block-based method obsolete</li>
     *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After complete refactor, this method will process BlockV5 objects from storage.
     *             No code changes needed if Block/BlockV5 interface is compatible. May require
     *             BlockV5-specific version if chain management logic differs significantly.
     *
     *             <p><b>Impact:</b>
     *             This method is critical for blockchain fork resolution. It cancels a block's main
     *             block status during chain reorganization, removing rewards and unapplying all
     *             transactions. Used by {@link #unWindMain(Block)} during fork handling.
     *
     * @param block The block to unset as main block
     * @see #unWindMain(Block)
     * @see #setMain(Block)
     * @see #unApplyBlock(Block)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    // TODO: Change to new way to cancel main block reward
    public void unSetMain(Block block) {

        synchronized (this) {

            log.debug("UnSet main,{}, mainnumber = {}", block.getHash().toHexString(), xdagStats.nmain);

            XAmount amount = block.getInfo().getAmount();// mainBlock's balance will have fee, subtract all balance.
            block.setInfo(block.getInfo().withFee(XAmount.ZERO));// set the mainBlock's zero.
            updateBlockFlag(block, BI_MAIN, false);

            xdagStats.nmain--;

            // Remove reward and referenced block fees
            acceptAmount(block, XAmount.ZERO.subtract(amount));
            XAmount unAppliedAmount = unApplyBlock(block);
            acceptAmount(block, unAppliedAmount);

            if (randomx != null) {
                randomx.randomXUnsetForkTime(block);
            }
            block.setInfo(block.getInfo().withHeight(0));
        }
    }

    public void processNonceAfterTransactionExecution(Address link) {
        if (link.getType() != XDAG_FIELD_INPUT) {
            return;
        }
        Bytes address = BytesUtils.byte32ToArray(link.getAddress());
        addressStore.updateExcutedNonceNum(address.toArray(), true);
        UInt64 currentTxNonce = addressStore.getTxQuantity(address.toArray());
        UInt64 currentExeNonce = addressStore.getExecutedNonceNum(address.toArray());
        addressStore.updateTxQuantity(address.toArray(), currentTxNonce, currentExeNonce);
    }

    /**
     * Create a mining main block (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method creates legacy Block objects for POW mining
     *             with Address-based references. After complete refactor and system restart with
     *             BlockV5-only storage, all mining block creation should use BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.2 (Current): Mark as @Deprecated</li>
     *               <li>Phase 5.5 (Planned): Create BlockV5 mining block creation methods</li>
     *               <li>Post-Restart: Remove this method entirely</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             Mining blocks should be created as BlockV5 objects with Link-based references to
     *             pretop blocks, coinbase addresses, and orphan blocks. The POW miner should be
     *             updated to work with BlockV5 structures.
     *
     *             <p><b>Impact:</b>
     *             This method is used by the POW mining system to create main blocks (candidates for
     *             blockchain tip). After migration, the mining system should create BlockV5 main blocks
     *             that include links to previous blocks and transactions.
     *
     * @see io.xdag.core.BlockV5
     * @see io.xdag.core.Link
     * @return Legacy Block object for mining (will be replaced by BlockV5)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public Block createMainBlock() {
        // <header + remark + outsig + nonce>
        int res = 1 + 1 + 2 + 1;
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getMainTime();
        Address preTop = null;
        Bytes32 pretopHash = getPreTopMainBlockForLink(sendTime[0]);
        if (pretopHash != null) {
            preTop = new Address(Bytes32.wrap(pretopHash), XdagField.FieldType.XDAG_FIELD_OUT, false);
            res++;
        }
        // The coinbase address of the block defaults to the default address of the node wallet
        Address coinbase = new Address(keyPair2Hash(wallet.getDefKey()),
                FieldType.XDAG_FIELD_COINBASE,
                true);
        List<Address> refs = Lists.newArrayList();
        if (preTop != null) {
            refs.add(preTop);
        }

        if (coinbase == null) {
            throw new ArithmeticException("Invalidate main block!");
        }
        refs.add(coinbase);
        res++;
        List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime);
        if (CollectionUtils.isNotEmpty(orphans)) {
            refs.addAll(orphans);
        }
        return new Block(kernel.getConfig(), sendTime[0], null, refs, true, null,
                kernel.getConfig().getNodeSpec().getNodeTag(), -1, XAmount.ZERO, null);
    }

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
        BigInteger networkDiff = xdagStats.getDifficulty();
        org.apache.tuweni.units.bigints.UInt256 difficulty =
            org.apache.tuweni.units.bigints.UInt256.valueOf(networkDiff);

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
     * Create a BlockV5 mining main block (v5.1 implementation)
     *
     * Phase 5.5: This is the NEW implementation for BlockV5 mining blocks.
     * Replaces the deprecated createMainBlock() method.
     *
     * Key differences from legacy createMainBlock():
     * 1. Uses Link.toBlock() instead of Address objects for block references
     * 2. Coinbase stored in BlockHeader (not as a link)
     * 3. Returns BlockV5 with Link-based DAG structure
     * 4. Uses current network difficulty from xdagStats
     * 5. Creates candidate block (nonce = 0, ready for POW mining)
     *
     * Block structure:
     * - Header: timestamp, difficulty, nonce=0, coinbase
     * - Links: [pretop_block (if exists), orphan_blocks...]
     * - Max block links: 16 (from BlockV5.MAX_BLOCK_LINKS)
     *
     * @return BlockV5 candidate block for mining (nonce = 0, needs POW)
     * @see BlockV5#createCandidate(long, org.apache.tuweni.units.bigints.UInt256, Bytes32, List)
     * @see Link#toBlock(Bytes32)
     */
    public BlockV5 createMainBlockV5() {
        // Get mining timestamp (aligned to 64-second epoch)
        long timestamp = XdagTime.getMainTime();

        // Get current network difficulty target
        BigInteger networkDiff = xdagStats.getDifficulty();
        org.apache.tuweni.units.bigints.UInt256 difficulty =
            org.apache.tuweni.units.bigints.UInt256.valueOf(networkDiff);

        // Get coinbase address (miner reward address)
        // In BlockV5, coinbase is stored in BlockHeader, not as a Link
        Bytes32 coinbase = keyPair2Hash(wallet.getDefKey());

        // Build DAG links (only block references, no coinbase link)
        List<Link> links = new ArrayList<>();

        // 1. Add pretop block reference (if exists)
        Bytes32 pretopHash = getPreTopMainBlockForLink(timestamp);
        if (pretopHash != null) {
            links.add(Link.toBlock(pretopHash));
        }

        // 2. Add orphan block references
        // Calculate available space: MAX=16, used: pretop(1 if exists)
        int maxOrphans = BlockV5.MAX_BLOCK_LINKS - links.size();
        long[] sendTime = new long[2];
        sendTime[0] = timestamp;

        List<Bytes32> orphans = orphanBlockStore.getOrphan(maxOrphans, sendTime);
        for (Bytes32 orphan : orphans) {
            links.add(Link.toBlock(orphan));
        }

        // Create candidate block (nonce = ZERO, will be set by mining)
        BlockV5 candidateBlock = BlockV5.createCandidate(timestamp, difficulty, coinbase, links);

        log.debug("Created BlockV5 mining candidate: epoch={}, links={} (pretop={}, orphans={})",
                 XdagTime.getEpoch(timestamp),
                 links.size(),
                 pretopHash != null ? 1 : 0,
                 orphans.size());

        return candidateBlock;
    }

    /**
     * Create a link block for network health (legacy v1.0 implementation).
     *
     * @deprecated As of v5.1 refactor, this method creates legacy Block objects for link blocks
     *             with Address-based references. After complete refactor and system restart with
     *             BlockV5-only storage, all link block creation should use BlockV5 structures.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.2 (Current): Mark as @Deprecated</li>
     *               <li>Phase 5.5 (Planned): Create BlockV5 link block creation methods</li>
     *               <li>Post-Restart: Remove this method entirely</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             Link blocks should be created as BlockV5 objects with Link-based references to
     *             orphan blocks. These blocks help maintain network health by referencing unreferenced
     *             blocks (orphans), preventing them from being pruned.
     *
     *             <p><b>Impact:</b>
     *             This method is used by {@link #checkOrphan()} to periodically create link blocks
     *             that reference orphan blocks in the network. After migration, the orphan health
     *             system should create BlockV5 link blocks.
     *
     * @param remark Optional remark/tag for the link block
     * @see io.xdag.core.BlockV5
     * @see io.xdag.core.Link
     * @see #checkOrphan()
     * @return Legacy Block object for network linking (will be replaced by BlockV5)
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    public Block createLinkBlock(String remark) {
        // <header + remark + outsig + nonce>
        int hasRemark = remark == null ? 0 : 1;
        int res = 1 + hasRemark + 2;
        long[] sendTime = new long[2];
        sendTime[0] = XdagTime.getCurrentTimestamp();

        List<Address> refs = Lists.newArrayList();
        List<Address> orphans = getBlockFromOrphanPool(16 - res, sendTime);
        if (CollectionUtils.isNotEmpty(orphans)) {
            refs.addAll(orphans);
        }
        return new Block(kernel.getConfig(), sendTime[1], null, refs, false, null,
                remark, -1, XAmount.ZERO, null);
    }

    /**
     * Get a certain number of orphan blocks from orphan pool for linking
     */
    public List<Address> getBlockFromOrphanPool(int num, long[] sendtime) {
        // v5.1: Convert Bytes32 to Address for backward compatibility
        List<Bytes32> orphans = orphanBlockStore.getOrphan(num, sendtime);
        List<Address> addresses = new ArrayList<>();
        for (Bytes32 orphan : orphans) {
            addresses.add(new Address(orphan, false));
        }
        return addresses;
    }

    public Bytes32 getPreTopMainBlockForLink(long sendTime) {
        long mainTime = XdagTime.getEpoch(sendTime);
        Block topInfo;
        if (xdagTopStatus.getTop() == null) {
            return null;
        }

        topInfo = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (topInfo == null) {
            return null;
        }
        if (XdagTime.getEpoch(topInfo.getTimestamp()) == mainTime) {
            log.debug("use pretop:{}", Bytes32.wrap(xdagTopStatus.getPreTop()).toHexString());
            return Bytes32.wrap(xdagTopStatus.getPreTop());
        } else {
            log.debug("use top:{}", Bytes32.wrap(xdagTopStatus.getTop()).toHexString());
            return Bytes32.wrap(xdagTopStatus.getTop());
        }
    }

    /**
     * Update pretop
     *
     * @param target     target block
     * @param targetDiff difficulty of block
     */
    public void setPreTop(Block target, BigInteger targetDiff) {
        if (target == null) {
            return;
        }

        // Make sure the target's epoch is earlier than current top's epoch
        Block block = getBlockByHash(xdagTopStatus.getTop() == null ? null :
                Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (block != null) {
            if (XdagTime.getEpoch(target.getTimestamp()) >= XdagTime.getEpoch(block.getTimestamp())) {
                return;
            }
        }

        // If pretop is null, then update pretop to target
        if (xdagTopStatus.getPreTop() == null) {
            xdagTopStatus.setPreTop(target.getHash().toArray());
            xdagTopStatus.setPreTopDiff(targetDiff);
            target.setPretopCandidate(true);
            target.setPretopCandidateDiff(targetDiff);
            return;
        }

        // If targetDiff greater than pretop diff, then update pretop to target
        if (targetDiff.compareTo(xdagTopStatus.getPreTopDiff()) > 0) {
            log.debug("update pretop:{}", Bytes32.wrap(target.getHash()).toHexString());
            xdagTopStatus.setPreTop(target.getHash().toArray());
            xdagTopStatus.setPreTopDiff(targetDiff);
            target.setPretopCandidate(true);
            target.setPretopCandidateDiff(targetDiff);
        }
    }

    /**
     * Calculate current block difficulty
     */
    public BigInteger calculateCurrentBlockDiff(Block block) {
        if (block == null) {
            return BigInteger.ZERO;
        }
        // Only return existing difficulty if it's both non-null AND non-zero
        if (block.getInfo().getDifficulty() != null && !block.getInfo().getDifficulty().isZero()) {
            return block.getInfo().getDifficulty().toBigInteger();
        }
        //TX block would not set diff, fix a diff = 1;
        if (!block.getInputs().isEmpty()) {
            return BigInteger.ONE;
        }

        BigInteger blockDiff;
        // Set initial block difficulty
        if (randomx != null && randomx.isRandomxFork(XdagTime.getEpoch(block.getTimestamp()))
                && XdagTime.isEndOfEpoch(block.getTimestamp())) {
            blockDiff = getDiffByRandomXHash(block);
        } else {
            blockDiff = getDiffByRawHash(block.getHash());
        }

        return blockDiff;
    }

    /**
     * Set block difficulty and max difficulty connection and return block difficulty
     */
    public BigInteger calculateBlockDiff(Block block, BigInteger cuDiff) {
        if (block == null) {
            return BigInteger.ZERO;
        }
        // Only return existing difficulty if it's both non-null AND non-zero
        if (block.getInfo().getDifficulty() != null && !block.getInfo().getDifficulty().isZero()) {
            return block.getInfo().getDifficulty().toBigInteger();
        }

        block.setInfo(block.getInfo().withDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(cuDiff)));

        BigInteger maxDiff = cuDiff;
        Address maxDiffLink = null;

        // Temporary block
        Block tmpBlock;
        if (block.getLinks().isEmpty()) {
            return cuDiff;
        }

        // Traverse all links to find maxLink
        List<Address> links = block.getLinks();
        for (Address ref : links) {
            /*
             * Only Blocks have difficulty
             */
            if (!ref.isAddress) {
                Block refBlock = getBlockByHash(ref.getAddress(), false);
                if (refBlock == null) {
                    break;
                }

                // If the referenced block's epoch is less than current block's round
                if (XdagTime.getEpoch(refBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp())) {
                    // If difficulty is greater than current max difficulty
                    BigInteger refDifficulty = refBlock.getInfo().getDifficulty() != null ? refBlock.getInfo().getDifficulty().toBigInteger() : BigInteger.ZERO;
                    BigInteger curDiff = refDifficulty.add(cuDiff);
                    if (curDiff.compareTo(maxDiff) > 0) {
                        maxDiff = curDiff;
                        maxDiffLink = ref;
                    }
                } else {
                    // Calculated diff
                    // 1. maxDiff+diff0 for different epochs
                    // 2. maxDiff for same epoch
                    tmpBlock = refBlock; // tmpBlock is from link
                    BigInteger curDiff = refBlock.getInfo().getDifficulty() != null ? refBlock.getInfo().getDifficulty().toBigInteger() : BigInteger.ZERO;
                    while ((tmpBlock != null)
                            && XdagTime.getEpoch(tmpBlock.getTimestamp()) == XdagTime.getEpoch(block.getTimestamp())) {
                        tmpBlock = getMaxDiffLink(tmpBlock, false);
                    }
                    if (tmpBlock != null
                            && (XdagTime.getEpoch(tmpBlock.getTimestamp()) < XdagTime.getEpoch(block.getTimestamp()))
                            && tmpBlock.getInfo().getDifficulty().toBigInteger().add(cuDiff).compareTo(curDiff) > 0
                    ) {
                        curDiff = tmpBlock.getInfo().getDifficulty().toBigInteger().add(cuDiff);
                    }
                    if (curDiff == null) {
                        curDiff = BigInteger.ZERO;
                    }
                    if (curDiff.compareTo(maxDiff) > 0) {
                        maxDiff = curDiff;
                        maxDiffLink = ref;
                    }
                }
            }
        }

        block.setInfo(block.getInfo().withDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(maxDiff)));

        if (maxDiffLink != null) {
            block.setInfo(block.getInfo().withMaxDiffLink(Bytes32.wrap(maxDiffLink.getAddress().toArray())));
        }
        return maxDiff;
    }

    public BigInteger getDiffByRandomXHash(Block block) {
        long epoch = XdagTime.getEpoch(block.getTimestamp());
        MutableBytes data = MutableBytes.create(64);
        Bytes32 rxHash = HashUtils.sha256(block.getXdagBlock().getData().slice(0, 512 - 32));
        data.set(0, rxHash);
        data.set(32, block.getXdagBlock().getField(15).getData());
        byte[] blockHash = randomx.randomXBlockHash(data.toArray(), epoch);
        BigInteger diff;
        if (blockHash != null) {
            Bytes32 hash = Bytes32.wrap(Arrays.reverse(blockHash));
            diff = getDiffByRawHash(hash);
        } else {
            diff = getDiffByRawHash(block.getHash());
        }
        log.debug("block diff:{}, ", diff);
        return diff;
    }

    public BigInteger getDiffByRawHash(Bytes32 hash) {
        return getDiffByHash(hash);
    }

    // ADD: Get block by height using new version
    public Block getBlockByHeightNew(long height) {
        // TODO: if snapshot enabled, need height > snapshotHeight - 128
        if (kernel.getConfig().getSnapshotSpec().isSnapshotEnabled() && (height < snapshotHeight - 128)
                && !kernel.getConfig().getSnapshotSpec().isSnapshotJ()) {
            return null;
        }
        // Return null if height is less than 0
        if (height > xdagStats.nmain || height <= 0) {
            return null;
        }
        return blockStore.getBlockByHeight(height);
    }

    @Override
    public Block getBlockByHeight(long height) {
        return getBlockByHeightNew(height);
    }

    @Override
    public Block getBlockByHash(Bytes32 hash, boolean isRaw) {
        if (hash == null) {
            return null;
        }

        // Phase 2: Use full 32-byte hash directly (no more legacy 24-byte conversion)
        // 1. Check memory pool first
        Block b = memOrphanPool.get(hash);

        // 2. Check main block store
        if (b == null) {
            b = blockStore.getBlockByHash(hash, isRaw);
        }

        // 3. Check finalized block store (Phase 2 refactor)
        if (b == null && kernel.getFinalizedBlockStore() != null) {
            b = kernel.getFinalizedBlockStore().getBlockByHash(hash)
                    .orElse(null);
        }

        return b;
    }

    public Block getMaxDiffLink(Block block, boolean isRaw) {
        if (block.getInfo().getMaxDiffLink() != null) {
            Bytes32 maxDiffLinkHash = Bytes32.wrap(block.getInfo().getMaxDiffLink());
            return getBlockByHash(maxDiffLinkHash, isRaw);
        }
        return null;
    }

    public void removeOrphan(Bytes32 hash, OrphanRemoveActions action) {
        Block b = getBlockByHash(hash, false);

        // Skip if block is snapshot
        if (b != null && b.getInfo() != null && b.getInfo().isSnapshot()) {
            return;
        }

        // Skip if block already has BI_REF flag
        if (b == null || (b.getInfo().getFlags() & BI_REF) != 0) {
            return;
        }

        // Handle EXTRA blocks
        if ((b.getInfo().getFlags() & BI_EXTRA) != 0) {

            if (action == OrphanRemoveActions.ORPHAN_REMOVE_EXTRA) {
                // Extra block referenced by another extra block - just mark as referenced, don't remove from memOrphanPool
                updateBlockFlag(b, BI_REF, true);
                return;
            }

            // Extra block being removed (not just referenced)
            // Remove from MemOrphanPool
            Bytes key = b.getHash();
            Block removeBlockRaw = memOrphanPool.get(key);
            memOrphanPool.remove(key);
            if (action != OrphanRemoveActions.ORPHAN_REMOVE_REUSE) {
                // Save block to disk
                saveBlock(removeBlockRaw);
                // Remove all blocks linked by EXTRA block
                if (removeBlockRaw != null) {
                    List<Address> all = removeBlockRaw.getLinks();
                    for (Address addr : all) {
                        removeOrphan(addr.getAddress(), OrphanRemoveActions.ORPHAN_REMOVE_NORMAL);
                    }
                }
            }
            // Update removeBlockRaw flag and decrement nextra
            updateBlockFlag(removeBlockRaw, BI_EXTRA, false);
            xdagStats.nextra--;
        } else {
            // Non-EXTRA block being removed from orphanBlockStore

            // Only decrement nnoref if this is a NORMAL removal (referenced by non-EXTRA block)
            // When referenced by EXTRA block (ORPHAN_REMOVE_EXTRA), keep nnoref count until
            // the EXTRA block becomes main and processes this block via applyBlock()
            if (action == OrphanRemoveActions.ORPHAN_REMOVE_NORMAL) {
                orphanBlockStore.deleteByHash(b.getHash().toArray());
                xdagStats.nnoref--;
            } else {
                // Keep in orphanBlockStore, will be removed when the EXTRA block becomes main
            }
        }

        // Always set BI_REF flag for any block that's been referenced
        updateBlockFlag(b, BI_REF, true);
    }

    public void updateBlockFlag(Block block, byte flag, boolean direction) {
        if (block == null) {
            return;
        }
        if (direction) {
            block.setInfo(block.getInfo().withFlags(block.getInfo().getFlags() | flag));
        } else {
            block.setInfo(block.getInfo().withFlags(block.getInfo().getFlags() & ~flag));
        }
        // Always save BlockInfo to database using V2 method (Phase 2 architecture)
        // This ensures flags are persisted with the new CompactSerializer format
        blockStore.saveBlockInfoV2(block.getInfo());
    }

    public void updateBlockRef(Block block, Address ref) {
        if (ref == null) {
            block.setInfo(block.getInfo().withRef(null));
        } else {
            block.setInfo(block.getInfo().withRef(Bytes32.wrap(ref.getAddress().toArray())));
        }
        // CRITICAL FIX: Use saveBlockInfoV2 instead of legacy saveBlockInfo
        // The block was originally saved with CompactSerializer (V2), so we must use the same format
        // Otherwise the hash key won't match and we'll create a duplicate entry
        blockStore.saveBlockInfoV2(block.getInfo());
    }

    public void saveBlock(Block block) {
        if (block == null) {
            return;
        }
        block.isSaved = true;
        blockStore.saveBlock(block);
        // If it's our account
        if (memOurBlocks.containsKey(block.getHash())) {
//            log.info("new account:{}", Hex.toHexString(block.getHash()));
            if (xdagStats.getOurLastBlockHash() == null) {
                blockStore.saveXdagStatus(xdagStats);
            }
            addOurBlock(memOurBlocks.get(block.getHash()), block);
            memOurBlocks.remove(block.getHash());
        }

        if (block.isPretopCandidate()) {
            xdagTopStatus.setPreTop(block.getHash().toArray());
            xdagTopStatus.setPreTopDiff(block.getPretopCandidateDiff());
            blockStore.saveXdagTopStatus(xdagTopStatus);
        }

    }

    public boolean isExtraBlock(Block block) {
        return (block.getTimestamp() & 0xffff) == 0xffff && block.getNonce() != null && !block.isSaved();
    }

    @Override
    public XdagStats getXdagStats() {
        return this.xdagStats;
    }

    public boolean canUseInput(Block block) {
        List<PublicKey> keys = block.verifiedKeys();
        List<Address> inputs = block.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return true;
        }
        /*
         * While "in" isn't address, need to verify signature
         */
        // TODO: Verify signature for non-address inputs
        for (Address in : inputs) {
            if (!in.isAddress) {
                if (!verifySignature(in, keys)) {
                    return false;
                }
            } else {
                if (!verifyBlockSignature(in, keys)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean verifyBlockSignature(Address in, List<PublicKey> keys) {
        Bytes pubHash = in.getAddress().mutableCopy().slice(8, 20);
        for (PublicKey key : keys) {
            if (pubHash.equals(toBytesAddress(key))) return true;
        }
        return false;
    }

    private boolean verifySignature(Address in, List<PublicKey> publicKeys) {
        // TODO: Check if block is in snapshot, get blockinfo with isRaw=false
        Block block = getBlockByHash(in.getAddress(), false);
        boolean isSnapshotBlock = block.getInfo().isSnapshot();
        if (isSnapshotBlock) {
            return verifySignatureFromSnapshot(in, publicKeys);
        } else {
            Block inBlock = getBlockByHash(in.getAddress(), true);
            MutableBytes subdata = inBlock.getSubRawData(inBlock.getOutsigIndex() - 2);
//            log.debug("verify encoded:{}", Hex.toHexString(subdata));
            Signature sig = inBlock.getOutsig();
            return verifySignature(subdata, sig, publicKeys, block.getInfo().toLegacy());
        }
    }

    // TODO: When input is a block in snapshot, need to verify snapshot's public key or signature data
    private boolean verifySignatureFromSnapshot(Address in, List<PublicKey> publicKeys) {
        LegacyBlockInfo blockInfo = blockStore.getBlockInfoByHash(in.getAddress()).getInfo().toLegacy();
        SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
        if (snapshotInfo.getType()) {
            // snapshotInfo.getData() contains 33-byte compressed public key format
            try {
                PublicKey targetPublicKey = PublicKey.fromBytes(snapshotInfo.getData());
            for (PublicKey publicKey : publicKeys) {
                if (publicKey.equals(targetPublicKey)) {
                    return true;
                }
            }
            return false;
            } catch (Exception e) {
                // If public key parsing fails, verification fails
                return false;
            }
        } else {
            Block block = getBlockByHash(in.getAddress(), false);
            block.setXdagBlock(new XdagBlock(snapshotInfo.getData()));
            block.setParsed(false);
            block.parse();
            MutableBytes subdata = block.getSubRawData(block.getOutsigIndex() - 2);
            Signature sig = block.getOutsig();
            // Check if signature is canonical to prevent signature malleability attacks
            if (!sig.isCanonical()) {
                return false; // Reject non-canonical signatures
            }
            return verifySignature(subdata, sig, publicKeys, blockInfo);
        }


    }

    private boolean verifySignature(MutableBytes subdata, Signature sig, List<PublicKey> publicKeys, LegacyBlockInfo blockInfo) {
        for (PublicKey publicKey : publicKeys) {
            byte[] publicKeyBytes = publicKey.toBytes().toArray();
            Bytes digest = Bytes.wrap(subdata, Bytes.wrap(publicKeyBytes));
//            log.debug("verify encoded:{}", Hex.toHexString(digest));
            Bytes32 hash = HashUtils.doubleSha256(digest);
            if (Signer.verify(hash, sig, publicKey)) {
                SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
                byte[] pubkeyBytes = publicKey.toBytes().toArray();
                if (snapshotInfo != null) {
                    snapshotInfo.setData(pubkeyBytes);
                    snapshotInfo.setType(true);
                } else {
                    blockInfo.setSnapshotInfo(new SnapshotInfo(true, pubkeyBytes));
                }
                blockStore.saveBlockInfo(blockInfo);
                return true;
            }
        }
        return false;
    }

    public boolean checkMineAndAdd(Block block) {
        List<ECKeyPair> ourkeys = wallet.getAccounts();
        // Only one output signature
        Signature signature = block.getOutsig();
        // Iterate through all keys
        for (int i = 0; i < ourkeys.size(); i++) {
            ECKeyPair ecKey = ourkeys.get(i);
            // TODO: Optimize
            byte[] publicKeyBytes = ecKey.getPublicKey().toBytes().toArray();
            Bytes digest = Bytes.wrap(block.getSubRawData(block.getOutsigIndex() - 2), Bytes.wrap(publicKeyBytes));
            Bytes32 hash = HashUtils.doubleSha256(Bytes.wrap(digest));
            // Use hyperledger besu crypto native secp256k1
            if (Signer.verify(hash, signature, ecKey.getPublicKey())) {
                log.debug("verify block success hash={}.", hash.toHexString());
                addOurBlock(i, block);
                return true;
            }
        }
        return false;
    }

    public void addOurBlock(int keyIndex, Block block) {
        xdagStats.setOurLastBlockHash(block.getHash().toArray());
        if (!block.isSaved()) {
            memOurBlocks.put(block.getHash(), keyIndex);
        } else {
            blockStore.saveOurBlock(keyIndex, block.getInfo().getHash().toArray());
        }
    }

    public void removeOurBlock(Block block) {
        if (!block.isSaved) {
            memOurBlocks.remove(block.getHash());
        } else {
            blockStore.removeOurBlock(block.getHash().toArray());
        }
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

    @Override
    public List<Block> getBlocksByTime(long starttime, long endtime) {
        return blockStore.getBlocksUsedTime(starttime, endtime);
    }

    /**
     * Get BlockV5 objects within specified time range (Phase 7.3.0)
     *
     * This is the BlockV5 version of getBlocksByTime(). It returns BlockV5 objects
     * instead of legacy Block objects. Used by network layer to send BlockV5 messages.
     *
     * @param starttime Start time in XDAG timestamp format
     * @param endtime End time in XDAG timestamp format
     * @return List of BlockV5 objects in the time range
     */
    @Override
    public List<BlockV5> getBlockV5sByTime(long starttime, long endtime) {
        // For now, get Block objects and convert to BlockV5
        // TODO Phase 7.3: After Block.java deletion, query BlockV5 directly from storage
        List<Block> blocks = blockStore.getBlocksUsedTime(starttime, endtime);
        List<BlockV5> blockV5List = new ArrayList<>();

        for (Block block : blocks) {
            try {
                // Try to get BlockV5 version from storage
                BlockV5 blockV5 = blockStore.getBlockV5ByHash(block.getHash(), true);
                if (blockV5 != null) {
                    blockV5List.add(blockV5);
                }
                // If BlockV5 not found, skip this block (legacy Block only)
            } catch (Exception e) {
                log.debug("Failed to get BlockV5 for hash {}: {}",
                         block.getHash().toHexString(), e.getMessage());
            }
        }

        return blockV5List;
    }

    /**
     * Get BlockV5 by its hash (Phase 7.3.0)
     *
     * This is the BlockV5 version of getBlockByHash().
     * Delegates to blockStore.getBlockV5ByHash().
     *
     * @param hash Block hash
     * @param isRaw Whether to include raw block data
     * @return BlockV5 or null if not found
     */
    @Override
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
        long nblk = xdagStats.nnoref / 11;
        if (nblk > 0) {
            boolean b = (nblk % 61) > CryptoProvider.nextLong(0, 61);
            nblk = nblk / 61 + (b ? 1 : 0);
        }
        while (nblk-- > 0) {
            // Phase 7.1: Use createLinkBlock() instead of deprecated createNewBlock()
            // Link blocks help maintain network health by referencing orphan blocks
            Block linkBlock = createLinkBlock(kernel.getConfig().getNodeSpec().getNodeTag());
            linkBlock.signOut(kernel.getWallet().getDefKey());

            // Phase 7.1: Temporary workaround - use legacy tryToConnect(Block)
            // TODO: After sync migration to BlockV5, this will use tryToConnect(BlockV5)
            // For now, use the internal implementation that still exists for legacy Block objects
            ImportResult result = tryToConnectLegacy(linkBlock);
            if (result == IMPORTED_NOT_BEST || result == IMPORTED_BEST) {
                onNewBlock(linkBlock);
            }
        }
    }

    /**
     * TEMPORARY: Legacy tryToConnect for Block objects (Phase 7.1 cleanup workaround)
     *
     * This is a temporary internal method to support legacy Block objects during the transition.
     * After the deleted tryToConnect(Block) method removal, some code paths still need legacy support.
     *
     * @deprecated This method exists only for transition support. Will be removed after:
     *             1. Sync system migrates to BlockV5
     *             2. All Block creation converted to BlockV5 creation
     *             3. Network layer fully supports BlockV5 messages
     *
     * @param block Legacy Block object
     * @return ImportResult
     */
    @Deprecated(since = "0.8.1", forRemoval = true)
    private synchronized ImportResult tryToConnectLegacy(Block block) {
        // For now, return INVALID_BLOCK to indicate this path is not supported
        // The proper solution is to migrate to BlockV5
        log.warn("tryToConnectLegacy called - this is a temporary workaround. Block: {}",
                block.getHash().toHexString());
        return ImportResult.INVALID_BLOCK;
    }

    public void checkMain() {
        try {
            checkNewMain();
            // xdagStats state will change after checkNewMain
            blockStore.saveXdagStatus(xdagStats);
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
     * Add amount to block
     */
    // TODO: Accept amount to block which in snapshot
    private void addAndAccept(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        try {
            block.setInfo(block.getInfo().withAmount(block.getInfo().getAmount().add(amount)));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  block {}", oldAmount, amount, block.getHash().toHexString());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo().toLegacy());
        }
        if ((block.getInfo().getFlags() & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
        XAmount finalAmount = blockStore.getBlockInfoByHash(block.getHash()).getInfo().getAmount();
        log.debug("Balance checker —— block:{} [old:{} add:{} fin:{}]",
                block.getHash().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    private void subtractAndAccept(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        try {
            block.setInfo(block.getInfo().withAmount(block.getInfo().getAmount().subtract(amount)));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  block {}", oldAmount, amount, block.getHash().toHexString());
        }
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo().toLegacy());
        }
        if ((block.getInfo().getFlags() & BI_OURS) != 0) {
            xdagStats.setBalance(xdagStats.getBalance().subtract(amount));
        }
        XAmount finalAmount = blockStore.getBlockInfoByHash(block.getHash()).getInfo().getAmount();
        log.debug("Balance checker —— block:{} [old:{} sub:{} fin:{}]",
                block.getHash().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    private void subtractAmount(Bytes addressHash, XAmount amount, Block block) {
        XAmount balance = addressStore.getBalanceByAddress(addressHash.toArray());
        try {
            addressStore.updateBalance(addressHash.toArray(), balance.subtract(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  addressHsh {}  block {}", balance, amount, Base58.encodeCheck(addressHash), block.getHash());
        }
        XAmount finalAmount = addressStore.getBalanceByAddress(addressHash.toArray());
        log.debug("Balance checker —— Address:{} [old:{} sub:{} fin:{}]",
                Base58.encodeCheck(addressHash),
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().getFlags() & BI_OURS) != 0) {
            xdagStats.setBalance(xdagStats.getBalance().subtract(amount));
        }
    }

    private void addAmount(Bytes addressHash, XAmount amount, Block block) {
        XAmount balance = addressStore.getBalanceByAddress(addressHash.toArray());
        try {
            addressStore.updateBalance(addressHash.toArray(), balance.add(amount));
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            log.debug("balance {}  amount {}  addressHsh {}  block {}", balance, amount, Base58.encodeCheck(addressHash), block.getHash());
        }
        XAmount finalAmount = addressStore.getBalanceByAddress(addressHash.toArray());
        log.warn("Balance checker —— Address:{} [old:{} add:{} fin:{}]",
                Base58.encodeCheck(addressHash),
                balance.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().getFlags() & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
    }

    // TODO: Accept amount to block which in snapshot
    private void acceptAmount(Block block, XAmount amount) {
        XAmount oldAmount = block.getInfo().getAmount();
        block.setInfo(block.getInfo().withAmount(block.getInfo().getAmount().add(amount)));
        if (block.isSaved) {
            blockStore.saveBlockInfo(block.getInfo().toLegacy());
        }
        XAmount finalAmount = blockStore.getBlockByHash(block.getHash(), false).getInfo().getAmount();
        log.warn("Balance checker —— Block:{} [old:{} acc:{} fin:{}]",
                block.getHash().toHexString(),
                oldAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                finalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        if ((block.getInfo().getFlags() & BI_OURS) != 0) {
            xdagStats.setBalance(amount.add(xdagStats.getBalance()));
        }
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


    // ADD: Get main blocks using new version method
    public List<Block> listMainBlocksByHeight(int count) {
        List<Block> res = new ArrayList<>();
        long currentHeight = xdagStats.nmain;
        for (int i = 0; i < count; i++) {
            Block block = getBlockByHeightNew(currentHeight - i);
            if (block != null) {
                res.add(block);
            }
        }
        return res;
    }

    @Override
    public List<Block> listMainBlocks(int count) {
        return listMainBlocksByHeight(count);
    }

    // TODO: List main blocks generated by this pool. If pool only generated blocks early or never generated blocks, 
    // need to traverse all block data which needs optimization
    @Override
    public List<Block> listMinedBlocks(int count) {
        Block temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getTop()), false);
        if (temp == null) {
            temp = getBlockByHash(Bytes32.wrap(xdagTopStatus.getPreTop()), false);
        }
        List<Block> res = Lists.newArrayList();
        while (count > 0) {
            if (temp == null) {
                break;
            }
            if ((temp.getInfo().getFlags() & BI_MAIN) != 0 && (temp.getInfo().getFlags() & BI_OURS) != 0) {
                count--;
                res.add((Block) temp.clone());
            }
            if (temp.getInfo().getMaxDiffLink() == null) {
                break;
            }
            temp = getBlockByHash(Bytes32.wrap(temp.getInfo().getMaxDiffLink()), false);
        }
        return res;
    }

    enum OrphanRemoveActions {
        ORPHAN_REMOVE_NORMAL, ORPHAN_REMOVE_REUSE, ORPHAN_REMOVE_EXTRA
    }
}
