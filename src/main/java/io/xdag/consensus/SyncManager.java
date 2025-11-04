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

package io.xdag.consensus;

import com.google.common.collect.Queues;
import io.xdag.Kernel;
import io.xdag.config.*;
import io.xdag.core.*;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.net.Channel;
import io.xdag.net.Peer;
import io.xdag.net.node.Node;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static io.xdag.core.ImportResult.*;
import static io.xdag.core.XdagState.*;
import static io.xdag.utils.XdagTime.msToXdagtimestamp;

/**
 * SyncManager for v5.1
 *
 * Manages block synchronization and validation.
 * Uses SyncBlock (simple wrapper) instead of BlockWrapper.
 */
@Slf4j
@Getter
@Setter
public class SyncManager extends AbstractXdagLifecycle {
    // Maximum size of syncMap
    public static final int MAX_SIZE = 500000;
    // Number of keys to remove when syncMap exceeds MAX_SIZE
    public static final int DELETE_NUM = 5000;

    /**
     * SyncBlock - Simple block wrapper for sync process (legacy v1.0)
     *
     * @deprecated As of v5.1 refactor (Phase 5.5 Part 3), this class wraps legacy Block objects.
     *             After complete BlockV5 migration, sync will work directly with BlockV5 objects
     *             without needing a wrapper class.
     *
     * TODO v5.1: DELETED - Block class no longer exists (Phase 7.1)
     * This wrapper is no longer usable. Use SyncBlockV5 instead.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.5 Part 3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: After fresh start with BlockV5-only storage, network layer will
     *                   use BlockV5Message directly</li>
     *               <li>Future: Remove this class and work directly with BlockV5</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             Network layer already has BlockV5 message support (NewBlockV5Message, SyncBlockV5Message).
     *             After migration, sync process will:
     *             <pre>{@code
     * // 1. Receive BlockV5 from network
     * BlockV5 block = blockV5Message.getBlock();
     *
     * // 2. Validate and import directly
     * ImportResult result = blockchain.tryToConnect(block);
     *
     * // 3. Broadcast if needed (no wrapper required)
     * if (shouldBroadcast(result)) {
     *     kernel.broadcastBlockV5(block, ttl);
     * }
     *             }</pre>
     *
     *             <p><b>Impact:</b>
     *             This wrapper is used throughout sync process. After BlockV5 migration, the sync
     *             flow becomes simpler: receive BlockV5 → validate → import → broadcast (no wrapping needed).
     *
     * @see io.xdag.core.BlockV5
     * @see io.xdag.net.message.consensus.NewBlockV5Message
     * @see io.xdag.net.message.consensus.SyncBlockV5Message
     * @see io.xdag.core.Blockchain#tryToConnect(BlockV5)
     */
    /*
    @Deprecated(since = "0.8.1", forRemoval = true)
    @Getter
    @Setter
    public static class SyncBlock {
        private Block block;
        private int ttl;
        private long time;
        private Peer remotePeer;
        private boolean old;

        public SyncBlock(Block block, int ttl) {
            this.block = block;
            this.ttl = ttl;
            this.time = System.currentTimeMillis();
        }

        public SyncBlock(Block block, int ttl, Peer remotePeer, boolean old) {
            this.block = block;
            this.ttl = ttl;
            this.remotePeer = remotePeer;
            this.old = old;
            this.time = System.currentTimeMillis();
        }
    }
    */

    /**
     * SyncBlockV5 - BlockV5 wrapper for sync process (v5.1)
     *
     * Phase 7.2: This is the NEW wrapper class for BlockV5 objects in the sync system.
     * Unlike the legacy SyncBlock (which wraps Block objects), this class works directly
     * with immutable BlockV5 objects.
     *
     * <p><b>Design Rationale:</b>
     * <ul>
     *   <li>BlockV5 is immutable - no parsing needed</li>
     *   <li>Uses Link-based references instead of Address</li>
     *   <li>Cleaner sync flow: receive → validate → import → broadcast</li>
     *   <li>Integrates with blockchain.tryToConnect(BlockV5)</li>
     * </ul>
     *
     * <p><b>Usage:</b>
     * <pre>{@code
     * // 1. Receive BlockV5 from network
     * BlockV5 block = blockV5Message.getBlock();
     *
     * // 2. Wrap in SyncBlockV5
     * SyncBlockV5 syncBlock = new SyncBlockV5(block, ttl, remotePeer, isOld);
     *
     * // 3. Validate and import
     * ImportResult result = syncManager.validateAndAddNewBlockV5(syncBlock);
     *
     * // 4. Handle result
     * if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
     *     log.info("BlockV5 successfully imported: {}", block.getHash().toHexString());
     * }
     * }</pre>
     *
     * @see io.xdag.core.BlockV5
     * @see io.xdag.core.Blockchain#tryToConnect(BlockV5)
     * @since 0.8.1 (Phase 7.2)
     */
    @Getter
    @Setter
    public static class SyncBlockV5 {
        /** The BlockV5 object to be synced */
        private BlockV5 block;

        /** Time-to-live for broadcast propagation (decrements with each hop) */
        private int ttl;

        /** Timestamp when this sync block was created/received (milliseconds) */
        private long time;

        /** Remote peer that sent this block (null if locally created) */
        private Peer remotePeer;

        /** Whether this is an old block (used for sync vs real-time distinction) */
        private boolean old;

        /**
         * Create SyncBlockV5 with minimal info (for local blocks)
         *
         * @param block BlockV5 object
         * @param ttl Time-to-live for broadcast
         */
        public SyncBlockV5(BlockV5 block, int ttl) {
            this.block = block;
            this.ttl = ttl;
            this.time = System.currentTimeMillis();
        }

        /**
         * Create SyncBlockV5 with full metadata (for network-received blocks)
         *
         * @param block BlockV5 object
         * @param ttl Time-to-live for broadcast
         * @param remotePeer Peer that sent this block
         * @param old Whether this is an old block (sync mode)
         */
        public SyncBlockV5(BlockV5 block, int ttl, Peer remotePeer, boolean old) {
            this.block = block;
            this.ttl = ttl;
            this.remotePeer = remotePeer;
            this.old = old;
            this.time = System.currentTimeMillis();
        }
    }

    private static final ThreadFactory factory = BasicThreadFactory.builder()
            .namingPattern("SyncManager-thread-%d")
            .daemon(true)
            .build();
    private Kernel kernel;
    private Blockchain blockchain;
    private long importStart;
    private AtomicLong importIdleTime = new AtomicLong();
    private AtomicBoolean syncDone = new AtomicBoolean(false);
    private AtomicBoolean isUpdateXdagStats = new AtomicBoolean(false);

    // Monitor whether to start itself
    private StateListener stateListener;

    /**
     * Queue with validated blocks to be added to the blockchain (legacy Block)
     * @deprecated Use blockQueueV5 for BlockV5 objects
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     */
    // @Deprecated(since = "0.8.1", forRemoval = true)
    // private Queue<SyncBlock> blockQueue = new ConcurrentLinkedQueue<>();

    /**
     * Queue for blocks with missing links (legacy Block)
     * @deprecated Use syncMapV5 for BlockV5 objects
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     */
    // @Deprecated(since = "0.8.1", forRemoval = true)
    // private ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMap = new ConcurrentHashMap<>();

    /**
     * Queue for polling oldest blocks (legacy)
     * @deprecated Use syncQueueV5 for BlockV5 objects
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     */
    // @Deprecated(since = "0.8.1", forRemoval = true)
    // private ConcurrentLinkedQueue<Bytes32> syncQueue = new ConcurrentLinkedQueue<>();

    // ========== BlockV5 Data Structures (Phase 7.2) ==========

    /**
     * Queue with validated BlockV5 objects to be added to the blockchain
     */
    private Queue<SyncBlockV5> blockQueueV5 = new ConcurrentLinkedQueue<>();

    /**
     * Queue for BlockV5 objects with missing parent blocks
     * Key: Hash of missing parent block
     * Value: Queue of child blocks waiting for this parent
     */
    private ConcurrentHashMap<Bytes32, Queue<SyncBlockV5>> syncMapV5 = new ConcurrentHashMap<>();

    /**
     * Queue for polling oldest BlockV5 objects
     */
    private ConcurrentLinkedQueue<Bytes32> syncQueueV5 = new ConcurrentLinkedQueue<>();

    private ScheduledExecutorService checkStateTask;

    private ScheduledFuture<?> checkStateFuture;
    private final TransactionHistoryStore txHistoryStore;

    public SyncManager(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.stateListener = new StateListener();
        checkStateTask = new ScheduledThreadPoolExecutor(1, factory);
        this.txHistoryStore = kernel.getTxHistoryStore();
    }

    @Override
    protected void doStart() {
        log.debug("Download receiveBlock run...");
        new Thread(this.stateListener, "xdag-stateListener").start();
        checkStateFuture = checkStateTask.scheduleAtFixedRate(this::checkState, 64, 5, TimeUnit.SECONDS);
    }

    @Override
    protected void doStop() {
        log.debug("sync manager stop");
        if (this.stateListener.isRunning) {
            this.stateListener.isRunning = false;
        }
        stopStateTask();
    }

    private void checkState() {
        if (!isUpdateXdagStats.get()) {
            return;
        }
        if (syncDone.get()) {
            stopStateTask();
            return;
        }

        // Phase 7.3: Use getChainStats().toLegacy()
        XdagStats xdagStats = kernel.getBlockchain().getChainStats().toLegacy();
        XdagTopStatus xdagTopStatus = kernel.getBlockchain().getXdagTopStatus();
        long lastTime = kernel.getSync().getLastTime();
        long curTime = msToXdagtimestamp(System.currentTimeMillis());
        long curHeight = xdagStats.getNmain();
        long maxHeight = xdagStats.getTotalnmain();
        // Exit the syncOld state based on time and height.
        if (!isSync() && (curHeight >= maxHeight - 512 || lastTime >= curTime - 32 * REQUEST_BLOCKS_MAX_TIME)) {
            log.debug("our node height:{} the max height:{}, set sync state", curHeight, maxHeight);
            setSyncState();
        }
        // Confirm whether the synchronization is complete based on time and height.
        if (curHeight >= maxHeight || xdagTopStatus.getTopDiff().compareTo(xdagStats.maxdifficulty) >= 0) {
            log.debug("our node height:{} the max height:{}, our diff:{} max diff:{}, make sync done",
                    curHeight, maxHeight, xdagTopStatus.getTopDiff(), xdagStats.maxdifficulty);
            makeSyncDone();
        }

    }

    /**
     * Monitor kernel state to determine if it's time to start
     */
    public boolean isTimeToStart() {
        boolean res = false;
        Config config = kernel.getConfig();
        int waitEpoch = config.getNodeSpec().getWaitEpoch();
        if (!isSync() && !isSyncOld() && (XdagTime.getCurrentEpoch() > kernel.getStartEpoch() + waitEpoch)) {
            res = true;
        }
        if (res) {
            log.debug("Waiting time exceeded,starting pow");
        }
        return res;
    }

    /**
     * Process blocks in queue and add them to the chain (legacy v1.0)
     *
     * @deprecated As of v5.1 refactor (Phase 5.5 Part 3), this method processes legacy SyncBlock
     *             wrappers containing Block objects. Uses deprecated blockchain.tryToConnect(Block).
     *             After BlockV5 migration, sync will import BlockV5 objects directly.
     *
     * TODO v5.1: DELETED - SyncBlock class and Block class no longer exist
     * This method cannot be used. Use importBlockV5() for BlockV5 objects instead.
     *
     *             <p><b>Migration Path:</b>
     *             <ul>
     *               <li>Phase 5.5 Part 3 (Current): Mark as @Deprecated</li>
     *               <li>Post-Restart: Use blockchain.tryToConnect(BlockV5) directly</li>
     *               <li>Future: Remove this method</li>
     *             </ul>
     *
     *             <p><b>Replacement Strategy:</b>
     *             After migration, import BlockV5 directly without wrapper:
     *             <pre>{@code
     * // Receive BlockV5 from network
     * BlockV5 block = blockV5Message.getBlock();
     *
     * // Import directly
     * ImportResult result = blockchain.tryToConnect(block);
     *
     * // Handle result
     * switch (result) {
     *     case IMPORTED_BEST, IMPORTED_NOT_BEST -> handleSuccess(block);
     *     case NO_PARENT -> requestParent(result.getHash());
     *     case INVALID_BLOCK -> handleInvalid(block);
     * }
     *             }</pre>
     *
     *             <p><b>Impact:</b>
     *             Core sync import method. Uses deprecated tryToConnect(Block) at line 208.
     *             After migration, sync becomes simpler without SyncBlock wrapper.
     *
     * @param syncBlock Legacy SyncBlock wrapper containing Block
     * @return ImportResult from blockchain connection attempt
     * @see io.xdag.core.Blockchain#tryToConnect(BlockV5)
     * @see SyncBlock
     */
    /*
    @Deprecated(since = "0.8.1", forRemoval = true)
    // TODO: Modify consensus
    public ImportResult importBlock(SyncBlock syncBlock) {
        log.debug("importBlock:{}", syncBlock.getBlock().getHash());

        // Phase 7.1: TEMPORARY STUB - tryToConnect(Block) was deleted
        // The sync system needs full migration to BlockV5. For now, return INVALID_BLOCK
        // to allow compilation. Sync functionality is temporarily impaired.
        //
        // TODO Phase 7.1: Complete sync migration to BlockV5:
        // 1. Update network layer to send/receive BlockV5 instead of Block
        // 2. Change SyncBlock wrapper to use BlockV5
        // 3. Call blockchain.tryToConnect(BlockV5) instead
        // 4. Update all sync logic to work with BlockV5
        log.error("importBlock() called but tryToConnect(Block) was deleted in Phase 7.1");
        log.error("Sync system needs BlockV5 migration. Block {} cannot be imported.",
                syncBlock.getBlock().getHash().toHexString());

        ImportResult importResult = ImportResult.INVALID_BLOCK;
        importResult.setErrorInfo("Legacy Block import not supported after Phase 7.1 cleanup");

        // ORIGINAL CODE - DISABLED after tryToConnect(Block) deletion:
        // ImportResult importResult = blockchain
        //         .tryToConnect(new Block(new XdagBlock(syncBlock.getBlock().getXdagBlock().getData().toArray())));

        if (importResult == EXIST) {
            log.debug("Block have exist:{}", syncBlock.getBlock().getHash());
        }

        if (!syncBlock.isOld() && (importResult == IMPORTED_BEST || importResult == IMPORTED_NOT_BEST)) {
            Peer blockPeer = syncBlock.getRemotePeer();
            Node node = kernel.getClient().getNode();
            if (blockPeer == null || !Strings.CS.equals(blockPeer.getIp(), node.getIp()) || blockPeer.getPort() != node.getPort()) {
                if (syncBlock.getTtl() > 0) {
                    distributeBlock(syncBlock);
                }
            }
        }
        return importResult;
    }
    */

    /**
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     * Use validateAndAddNewBlockV5() for BlockV5 objects instead.
     */
    /*
    public synchronized ImportResult validateAndAddNewBlock(SyncBlock syncBlock) {
        syncBlock.getBlock().parse();
        ImportResult result = importBlock(syncBlock);
        log.debug("validateAndAddNewBlock:{}, {}", syncBlock.getBlock().getHash(), result);
        switch (result) {
            case EXIST, IMPORTED_BEST, IMPORTED_NOT_BEST, IN_MEM -> syncPopBlock(syncBlock);
            case NO_PARENT -> doNoParent(syncBlock, result);
            case INVALID_BLOCK -> {
//                log.error("invalid block:{}", Hex.toHexString(syncBlock.getBlock().getHash()));
            }
            default -> {
            }
        }
        return result;
    }

  private void doNoParent(SyncBlock syncBlock, ImportResult result) {
    if (syncPushBlock(syncBlock, result.getHash())) {
        logParent(syncBlock, result);

        // Use P2P service to request missing parent block
        if (kernel.getP2pService() != null) {
            // TODO: Send block request via P2P - need to add method to XdagP2pEventHandler
            log.debug("Request missing parent block: {}", result.getHash().toHexString());
        }
    }
  }
    */

  /**
     * Synchronize missing blocks
     *
     * @param syncBlock New block
     * @param hash Hash of missing parent block
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     * Use syncPushBlockV5() for BlockV5 objects instead.
     */
    /*
    public boolean syncPushBlock(SyncBlock syncBlock, Bytes32 hash) {
        if (syncMap.size() >= MAX_SIZE) {
            for (int j = 0; j < DELETE_NUM; j++) {
                List<Bytes32> keyList = new ArrayList<>(syncMap.keySet());

                Bytes32 key = keyList.get(CryptoProvider.nextInt(0, keyList.size()));
                assert key != null;
                // Phase 7.3: Use getChainStats().toLegacy()
                if (syncMap.remove(key) != null) blockchain.getChainStats().toLegacy().nwaitsync--;
            }
        }
        AtomicBoolean r = new AtomicBoolean(true);
        long now = System.currentTimeMillis();

        Queue<SyncBlock> newQueue = Queues.newConcurrentLinkedQueue();
        syncBlock.setTime(now);
        newQueue.add(syncBlock);
        // Phase 7.3: Use getChainStats().toLegacy()
        blockchain.getChainStats().toLegacy().nwaitsync++;

        syncMap.merge(hash, newQueue,
                (oldQ, newQ) -> {
                    // Phase 7.3: Use getChainStats().toLegacy()
                    blockchain.getChainStats().toLegacy().nwaitsync--;
                    for (SyncBlock b : oldQ) {
                        if (b.getBlock().getHash().equals(syncBlock.getBlock().getHash())) {
                            // after 64 sec must resend block request
                            if (now - b.getTime() > 64 * 1000) {
                                b.setTime(now);
                                r.set(true);
                            } else {
                                // TODO: Consider timeout for unreceived request block
                                r.set(false);
                            }
                            return oldQ;
                        }
                    }
                    oldQ.add(syncBlock);
                    r.set(true);
                    return oldQ;
                });
        return r.get();
    }
    */

    /**
     * Release child blocks based on received block
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     * Use syncPopBlockV5() for BlockV5 objects instead.
     */
    /*
    public void syncPopBlock(SyncBlock syncBlock) {
        Block block = syncBlock.getBlock();

        Queue<SyncBlock> queue = syncMap.getOrDefault(block.getHash(), null);
        if (queue != null) {
            syncMap.remove(block.getHash());
            blockchain.getXdagStats().nwaitsync--;
            queue.forEach(bw -> {
                ImportResult importResult = importBlock(bw);
                switch (importResult) {
                    case EXIST, IN_MEM, IMPORTED_BEST, IMPORTED_NOT_BEST -> {
                        // TODO: Need to remove after successful import
                        syncPopBlock(bw);
                        queue.remove(bw);
                    }
                    case NO_PARENT -> doNoParent(bw, importResult);
                    default -> {
                    }
                }
            });
        }
    }

  private void logParent(SyncBlock bw, ImportResult importResult) {
    log.debug("push block:{}, NO_PARENT {}", bw.getBlock().getHash(),
            importResult.getHash().toHexString());
  }
    */

  // TODO: Currently stays in sync by default, not responsible for block generation
    public void makeSyncDone() {
        if (syncDone.compareAndSet(false, true)) {
            // Stop state check process
            this.stateListener.isRunning = false;
            Config config = kernel.getConfig();
            if (config instanceof MainnetConfig) {
                if (kernel.getXdagState() != XdagState.SYNC) {
                    kernel.setXdagState(XdagState.SYNC);
                }
            } else if (config instanceof TestnetConfig) {
                if (kernel.getXdagState() != XdagState.STST) {
                    kernel.setXdagState(XdagState.STST);
                }
            } else if (config instanceof DevnetConfig) {
                if (kernel.getXdagState() != XdagState.SDST) {
                    kernel.setXdagState(XdagState.SDST);
                }
            }

            // Phase 7.3: Use getChainStats().toLegacy()
            log.info("sync done, the last main block number = {}", blockchain.getChainStats().toLegacy().nmain);
            kernel.getSync().setStatus(XdagSync.Status.SYNC_DONE);
            if (config.getEnableTxHistory() && txHistoryStore != null) {
                // TODO v5.1: DELETED - TransactionHistoryStore.batchSaveTxHistory() no longer exists
                // Sync done, batch write remaining history
                // txHistoryStore.batchSaveTxHistory(null);
            }

            if (config.getEnableGenerateBlock()) {
                log.info("start pow at:{}",
                        FastDateFormat.getInstance("yyyy-MM-dd 'at' HH:mm:ss z").format(new Date()));
                // Check main chain
//                kernel.getMinerServer().start();
                kernel.getPow().start();
            } else {
                log.info("A non-mining node, will not generate blocks.");
            }
        }
    }

    public void setSyncState() {
        Config config = kernel.getConfig();
        if (config instanceof MainnetConfig) {
            kernel.setXdagState(CONN);
        } else if (config instanceof TestnetConfig) {
            kernel.setXdagState(CTST);
        } else if (config instanceof DevnetConfig) {
            kernel.setXdagState(CDST);
        }
    }

    public boolean isSync() {
        return kernel.getXdagState() == CONN || kernel.getXdagState() == CTST
                || kernel.getXdagState() == CDST;
    }

    public boolean isSyncOld() {
        return kernel.getXdagState() == CONNP || kernel.getXdagState() == CTSTP
                || kernel.getXdagState() == CDSTP;
    }

    private void stopStateTask() {
        if (checkStateFuture != null) {
            checkStateFuture.cancel(true);
        }
        // Shutdown thread pool
        checkStateTask.shutdownNow();
    }

    /**
     * Distribute legacy Block (deprecated)
     *
     * Phase 7.3.0: This method is only called from the deprecated importBlock() method,
     * which was stubbed out in Phase 7.1. Legacy Block broadcasting is no longer supported.
     *
     * @deprecated This method is non-functional after Phase 7.3.0 cleanup
     *
     * TODO v5.1: DELETED - SyncBlock class no longer exists
     * Use distributeBlockV5() for BlockV5 objects instead.
     */
    /*
    @Deprecated(since = "0.8.1", forRemoval = true)
    public void distributeBlock(SyncBlock syncBlock) {
        // Phase 7.3.0: kernel.broadcastBlock() deleted - NEW_BLOCK messages no longer supported
        // This method is only called from deprecated importBlock() (which is stubbed), so no impact
        log.warn("distributeBlock() called but legacy Block broadcasting no longer supported. " +
                "Block hash: {}", syncBlock.getBlock().getHash().toHexString());
    }
    */

    // ========== BlockV5 Sync Methods (Phase 7.2) ==========

    /**
     * Import BlockV5 to blockchain (v5.1 implementation)
     *
     * Phase 7.2: This is the NEW import method for BlockV5 objects. Unlike the legacy
     * importBlock() which uses deprecated tryToConnect(Block), this method uses the
     * functional tryToConnect(BlockV5) from BlockchainImpl.
     *
     * <p><b>Key Differences from Legacy:</b>
     * <ul>
     *   <li>No parse() needed - BlockV5 is immutable and pre-validated</li>
     *   <li>Uses blockchain.tryToConnect(BlockV5) directly</li>
     *   <li>Cleaner error handling with ImportResult</li>
     *   <li>Broadcasts via kernel.broadcastBlockV5()</li>
     * </ul>
     *
     * @param syncBlock SyncBlockV5 wrapper containing BlockV5 and metadata
     * @return ImportResult indicating success or failure reason
     */
    public ImportResult importBlockV5(SyncBlockV5 syncBlock) {
        BlockV5 block = syncBlock.getBlock();
        log.debug("importBlockV5: {}", block.getHash().toHexString());

        // Import BlockV5 directly to blockchain
        ImportResult importResult = blockchain.tryToConnect(block);

        // Log result
        if (importResult == EXIST) {
            log.debug("BlockV5 already exists: {}", block.getHash().toHexString());
        } else if (importResult == IMPORTED_BEST) {
            log.info("BlockV5 imported as BEST: {}", block.getHash().toHexString());
        } else if (importResult == IMPORTED_NOT_BEST) {
            log.debug("BlockV5 imported as NOT_BEST: {}", block.getHash().toHexString());
        } else if (importResult == NO_PARENT) {
            log.debug("BlockV5 missing parent: {} (parent: {})",
                     block.getHash().toHexString(),
                     importResult.getHash() != null ? importResult.getHash().toHexString() : "unknown");
        } else if (importResult == INVALID_BLOCK) {
            log.warn("BlockV5 validation failed: {} (reason: {})",
                    block.getHash().toHexString(),
                    importResult.getErrorInfo());
        }

        // Handle broadcast for successfully imported blocks
        if (!syncBlock.isOld() &&
            (importResult == IMPORTED_BEST || importResult == IMPORTED_NOT_BEST)) {

            Peer blockPeer = syncBlock.getRemotePeer();
            Node node = kernel.getClient().getNode();

            // Only broadcast if block didn't come from ourselves
            if (blockPeer == null ||
                !Strings.CS.equals(blockPeer.getIp(), node.getIp()) ||
                blockPeer.getPort() != node.getPort()) {

                if (syncBlock.getTtl() > 0) {
                    distributeBlockV5(syncBlock);
                }
            }
        }

        return importResult;
    }

    /**
     * Validate and add new BlockV5 to blockchain (v5.1 entry point)
     *
     * Phase 7.2: This is the main entry point for importing BlockV5 objects from the network.
     * Network message handlers should call this method when receiving new BlockV5 objects.
     *
     * <p><b>Process Flow:</b>
     * <ol>
     *   <li>Import BlockV5 via importBlockV5()</li>
     *   <li>Handle ImportResult:
     *     <ul>
     *       <li>IMPORTED_BEST/NOT_BEST/EXIST → Process child blocks waiting for this block</li>
     *       <li>NO_PARENT → Add to waiting queue and request parent</li>
     *       <li>INVALID_BLOCK → Log and discard</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param syncBlock SyncBlockV5 wrapper containing BlockV5 and metadata
     * @return ImportResult from the import attempt
     */
    public synchronized ImportResult validateAndAddNewBlockV5(SyncBlockV5 syncBlock) {
        // No parse() needed for BlockV5 (immutable, pre-validated)
        ImportResult result = importBlockV5(syncBlock);

        log.debug("validateAndAddNewBlockV5: {} → {}",
                 syncBlock.getBlock().getHash().toHexString(), result);

        // Handle result
        switch (result) {
            case EXIST, IMPORTED_BEST, IMPORTED_NOT_BEST, IN_MEM -> {
                // Block successfully added - process any child blocks waiting for it
                syncPopBlockV5(syncBlock);
            }
            case NO_PARENT -> {
                // Block's parent is missing - add to waiting queue
                doNoParentV5(syncBlock, result);
            }
            case INVALID_BLOCK -> {
                log.debug("Invalid BlockV5: {} (reason: {})",
                         syncBlock.getBlock().getHash().toHexString(),
                         result.getErrorInfo());
            }
            default -> {
                log.warn("Unexpected ImportResult for BlockV5: {} → {}",
                        syncBlock.getBlock().getHash().toHexString(), result);
            }
        }

        return result;
    }

    /**
     * Handle BlockV5 with missing parent block
     *
     * Phase 7.2: When a BlockV5 references a parent block that doesn't exist yet,
     * this method adds the child block to a waiting queue (syncMapV5) and requests
     * the missing parent from the network.
     *
     * @param syncBlock SyncBlockV5 that has a missing parent
     * @param result ImportResult with NO_PARENT status (contains parent hash)
     */
    private void doNoParentV5(SyncBlockV5 syncBlock, ImportResult result) {
        // Add child block to waiting queue
        if (syncPushBlockV5(syncBlock, result.getHash())) {
            logParentV5(syncBlock, result);

            // Request missing parent block from network (Phase 7.3)
            java.util.List<io.xdag.p2p.channel.Channel> channels = kernel.getActiveP2pChannels();
            if (!channels.isEmpty()) {
                io.xdag.p2p.XdagP2pEventHandler eventHandler =
                    (io.xdag.p2p.XdagP2pEventHandler) kernel.getP2pEventHandler();

                // Request from all active peers
                channels.forEach(channel -> {
                    eventHandler.requestBlockV5ByHash(channel, result.getHash());
                });

                log.debug("Requested missing parent BlockV5: {} from {} peers (for child: {})",
                         result.getHash().toHexString(),
                         channels.size(),
                         syncBlock.getBlock().getHash().toHexString());
            }
        }
    }

    /**
     * Add BlockV5 to waiting queue for missing parent
     *
     * Phase 7.2: Manages the queue of child blocks waiting for their parent blocks.
     * Similar to syncPushBlock() but for BlockV5 objects.
     *
     * @param syncBlock SyncBlockV5 to add to waiting queue
     * @param parentHash Hash of the missing parent block
     * @return true if block was added or request should be sent, false if duplicate
     */
    public boolean syncPushBlockV5(SyncBlockV5 syncBlock, Bytes32 parentHash) {
        // Check if syncMapV5 is getting too large
        if (syncMapV5.size() >= MAX_SIZE) {
            // Remove oldest entries
            for (int j = 0; j < DELETE_NUM; j++) {
                List<Bytes32> keyList = new ArrayList<>(syncMapV5.keySet());
                if (keyList.isEmpty()) break;

                Bytes32 key = keyList.get(CryptoProvider.nextInt(0, keyList.size()));
                if (syncMapV5.remove(key) != null) {
                    // Phase 7.3: Use new ChainStats decrement method
                    blockchain.decrementWaitingSyncCount();
                }
            }
        }

        AtomicBoolean shouldRequest = new AtomicBoolean(true);
        long now = System.currentTimeMillis();

        // Create new queue for this parent hash
        Queue<SyncBlockV5> newQueue = Queues.newConcurrentLinkedQueue();
        syncBlock.setTime(now);
        newQueue.add(syncBlock);
        // Phase 7.3: Use new ChainStats increment method
        blockchain.incrementWaitingSyncCount();

        // Merge with existing queue (if any)
        syncMapV5.merge(parentHash, newQueue, (oldQueue, newQ) -> {
            // Phase 7.3: Undo increment since merging
            blockchain.decrementWaitingSyncCount();

            // Check if this block is already in the queue
            for (SyncBlockV5 existing : oldQueue) {
                if (existing.getBlock().getHash().equals(syncBlock.getBlock().getHash())) {
                    // Block already waiting - check if we should resend request
                    if (now - existing.getTime() > 64 * 1000) {
                        // More than 64 seconds - update time and allow request
                        existing.setTime(now);
                        shouldRequest.set(true);
                    } else {
                        // Too soon - don't spam requests
                        shouldRequest.set(false);
                    }
                    return oldQueue;  // Keep existing queue
                }
            }

            // New block - add to existing queue
            oldQueue.add(syncBlock);
            shouldRequest.set(true);
            return oldQueue;
        });

        return shouldRequest.get();
    }

    /**
     * Process child BlockV5 objects when their parent arrives
     *
     * Phase 7.2: When a parent BlockV5 is successfully imported, this method
     * retrieves all child blocks that were waiting for it and attempts to import them.
     *
     * @param syncBlock SyncBlockV5 that was just imported (the parent)
     */
    public void syncPopBlockV5(SyncBlockV5 syncBlock) {
        BlockV5 block = syncBlock.getBlock();

        // Get queue of child blocks waiting for this parent
        Queue<SyncBlockV5> queue = syncMapV5.getOrDefault(block.getHash(), null);
        if (queue != null) {
            syncMapV5.remove(block.getHash());
            // Phase 7.3: Use new ChainStats decrement method
            blockchain.decrementWaitingSyncCount();

            log.debug("Processing {} child BlockV5 objects waiting for parent: {}",
                     queue.size(), block.getHash().toHexString());

            // Try to import each child block
            queue.forEach(childSync -> {
                ImportResult childResult = importBlockV5(childSync);

                switch (childResult) {
                    case EXIST, IN_MEM, IMPORTED_BEST, IMPORTED_NOT_BEST -> {
                        // Child successfully imported - process its children recursively
                        syncPopBlockV5(childSync);
                        queue.remove(childSync);
                    }
                    case NO_PARENT -> {
                        // Child still has missing parent (different from this one)
                        doNoParentV5(childSync, childResult);
                    }
                    case INVALID_BLOCK -> {
                        log.warn("Child BlockV5 invalid after parent arrived: {} (reason: {})",
                                childSync.getBlock().getHash().toHexString(),
                                childResult.getErrorInfo());
                    }
                    default -> {
                        log.debug("Unexpected result for child BlockV5: {} → {}",
                                childSync.getBlock().getHash().toHexString(), childResult);
                    }
                }
            });
        }
    }

    /**
     * Broadcast BlockV5 to network peers
     *
     * Phase 7.2: Distributes a BlockV5 object to all connected peers using the P2P service.
     *
     * @param syncBlock SyncBlockV5 to broadcast
     */
    public void distributeBlockV5(SyncBlockV5 syncBlock) {
        // Use Kernel's BlockV5 broadcast method
        kernel.broadcastBlockV5(syncBlock.getBlock(), syncBlock.getTtl());

        log.debug("Distributed BlockV5: {} (ttl={})",
                 syncBlock.getBlock().getHash().toHexString(),
                 syncBlock.getTtl());
    }

    /**
     * Log missing parent information for BlockV5
     *
     * @param syncBlock SyncBlockV5 with missing parent
     * @param importResult ImportResult containing parent hash
     */
    private void logParentV5(SyncBlockV5 syncBlock, ImportResult importResult) {
        log.debug("BlockV5 {} waiting for parent: {}",
                 syncBlock.getBlock().getHash().toHexString(),
                 importResult.getHash().toHexString());
    }

    // ========== End of BlockV5 Sync Methods ==========

    private class StateListener implements Runnable {

        boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                if (isTimeToStart()) {
                    makeSyncDone();
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

}
