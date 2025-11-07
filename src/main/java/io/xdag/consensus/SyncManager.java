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

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static io.xdag.core.ImportResult.EXIST;
import static io.xdag.core.ImportResult.IMPORTED_BEST;
import static io.xdag.core.ImportResult.IMPORTED_NOT_BEST;
import static io.xdag.core.ImportResult.INVALID_BLOCK;
import static io.xdag.core.ImportResult.NO_PARENT;
import static io.xdag.core.XdagState.CDST;
import static io.xdag.core.XdagState.CDSTP;
import static io.xdag.core.XdagState.CONN;
import static io.xdag.core.XdagState.CONNP;
import static io.xdag.core.XdagState.CTST;
import static io.xdag.core.XdagState.CTSTP;
import static io.xdag.utils.XdagTime.msToXdagtimestamp;

import com.google.common.collect.Queues;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.core.AbstractXdagLifecycle;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.ChainStats;
import io.xdag.core.ImportResult;
import io.xdag.core.XdagState;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.net.Peer;
import io.xdag.net.message.consensus.NewBlockMessage;
import io.xdag.net.message.consensus.SyncBlockMessage;
import io.xdag.net.node.Node;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes32;

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

    @Getter
    @Setter
    public static class SyncBlock {
        /** The Block object to be synced */
        private Block block;

        /** Time-to-live for broadcast propagation (decrements with each hop) */
        private int ttl;

        /** Timestamp when this sync block was created/received (milliseconds) */
        private long time;

        /** Remote peer that sent this block (null if locally created) */
        private Peer remotePeer;

        /** Whether this is an old block (used for sync vs real-time distinction) */
        private boolean old;

        /**
         * Create SyncBlock with minimal info (for local blocks)
         *
         * @param block Block object
         * @param ttl Time-to-live for broadcast
         */
        public SyncBlock(Block block, int ttl) {
            this.block = block;
            this.ttl = ttl;
            this.time = System.currentTimeMillis();
        }

        /**
         * Create SyncBlock with full metadata (for network-received blocks)
         *
         * @param block Block object
         * @param ttl Time-to-live for broadcast
         * @param remotePeer Peer that sent this block
         * @param old Whether this is an old block (sync mode)
         */
        public SyncBlock(Block block, int ttl, Peer remotePeer, boolean old) {
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
     * Queue with validated Block objects to be added to the blockchain
     */
    private Queue<SyncBlock> blockQueueV5 = new ConcurrentLinkedQueue<>();

    /**
     * Queue for Block objects with missing parent blocks
     * Key: Hash of missing parent block
     * Value: Queue of child blocks waiting for this parent
     */
    private ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMapV5 = new ConcurrentHashMap<>();

    /**
     * Queue for polling oldest Block objects
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

        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        // Phase 7.3.1: XdagTopStatus merged into ChainStats (deleted)
        ChainStats chainStats = kernel.getBlockchain().getChainStats();
        long lastTime = kernel.getSync().getLastTime();
        long curTime = msToXdagtimestamp(System.currentTimeMillis());
        long curHeight = chainStats.getMainBlockCount();
        long maxHeight = chainStats.getTotalMainBlockCount();
        // Exit the syncOld state based on time and height.
        if (!isSync() && (curHeight >= maxHeight - 512 || lastTime >= curTime - 32 * REQUEST_BLOCKS_MAX_TIME)) {
            log.debug("our node height:{} the max height:{}, set sync state", curHeight, maxHeight);
            setSyncState();
        }
        // Confirm whether the synchronization is complete based on time and height.
        // Phase 7.3.1: Use chainStats.getTopDifficulty() instead of xdagTopStatus.getTopDiff()
        if (curHeight >= maxHeight || chainStats.getTopDifficulty().toBigInteger().compareTo(chainStats.getMaxDifficulty().toBigInteger()) >= 0) {
            log.debug("our node height:{} the max height:{}, our diff:{} max diff:{}, make sync done",
                    curHeight, maxHeight, chainStats.getTopDifficulty().toBigInteger(), chainStats.getMaxDifficulty().toBigInteger());
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
     * Mark synchronization as complete and transition to final state
     *
     * <p>Phase 8.4: Currently stays in sync by default, not responsible for block generation.
     * This behavior may change in future versions when POW is fully integrated.
     */
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

            // Phase 7.3: Use ChainStats directly (XdagStats deleted)
            log.info("sync done, the last main block number = {}", blockchain.getChainStats().getMainBlockCount());
            kernel.getSync().setStatus(XdagSync.Status.SYNC_DONE);

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


    public ImportResult importBlock(SyncBlock syncBlock) {
        Block block = syncBlock.getBlock();
        log.debug("importBlock: {}", block.getHash().toHexString());

        // Import Block directly to blockchain
        ImportResult importResult = blockchain.tryToConnect(block);

        // Log result
        if (importResult == EXIST) {
            log.debug("Block already exists: {}", block.getHash().toHexString());
        } else if (importResult == IMPORTED_BEST) {
            log.info("Block imported as BEST: {}", block.getHash().toHexString());
        } else if (importResult == IMPORTED_NOT_BEST) {
            log.debug("Block imported as NOT_BEST: {}", block.getHash().toHexString());
        } else if (importResult == NO_PARENT) {
            log.debug("Block missing parent: {} (parent: {})",
                     block.getHash().toHexString(),
                     importResult.getHash() != null ? importResult.getHash().toHexString() : "unknown");
        } else if (importResult == INVALID_BLOCK) {
            log.warn("Block validation failed: {} (reason: {})",
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
                    distributeBlock(syncBlock);
                }
            }
        }

        return importResult;
    }

    /**
     * Validate and add new Block to blockchain (v5.1 entry point)
     *
     * Phase 7.2: This is the main entry point for importing Block objects from the network.
     * Network message handlers should call this method when receiving new Block objects.
     *
     * <p><b>Process Flow:</b>
     * <ol>
     *   <li>Import Block via importBlock()</li>
     *   <li>Handle ImportResult:
     *     <ul>
     *       <li>IMPORTED_BEST/NOT_BEST/EXIST → Process child blocks waiting for this block</li>
     *       <li>NO_PARENT → Add to waiting queue and request parent</li>
     *       <li>INVALID_BLOCK → Log and discard</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param syncBlock SyncBlock wrapper containing Block and metadata
     * @return ImportResult from the import attempt
     */
    public synchronized ImportResult validateAndAddNewBlock(SyncBlock syncBlock) {
        // No parse() needed for Block (immutable, pre-validated)
        ImportResult result = importBlock(syncBlock);

        log.debug("validateAndAddNewBlock: {} → {}",
                 syncBlock.getBlock().getHash().toHexString(), result);

        // Handle result
        switch (result) {
            case EXIST, IMPORTED_BEST, IMPORTED_NOT_BEST, IN_MEM -> {
                // Block successfully added - process any child blocks waiting for it
                syncPopBlock(syncBlock);
            }
            case NO_PARENT -> {
                // Block's parent is missing - add to waiting queue
                doNoParentV5(syncBlock, result);
            }
            case INVALID_BLOCK -> {
                log.debug("Invalid Block: {} (reason: {})",
                         syncBlock.getBlock().getHash().toHexString(),
                         result.getErrorInfo());
            }
            default -> {
                log.warn("Unexpected ImportResult for Block: {} → {}",
                        syncBlock.getBlock().getHash().toHexString(), result);
            }
        }

        return result;
    }

    /**
     * Handle Block with missing parent block
     *
     * Phase 7.2: When a Block references a parent block that doesn't exist yet,
     * this method adds the child block to a waiting queue (syncMapV5) and requests
     * the missing parent from the network.
     *
     * @param syncBlock SyncBlock that has a missing parent
     * @param result ImportResult with NO_PARENT status (contains parent hash)
     */
    private void doNoParentV5(SyncBlock syncBlock, ImportResult result) {
        // Add child block to waiting queue
        if (syncPushBlock(syncBlock, result.getHash())) {
            logParentV5(syncBlock, result);

            // Request missing parent block from network (Phase 7.3)
            java.util.List<io.xdag.p2p.channel.Channel> channels = kernel.getActiveP2pChannels();
            if (!channels.isEmpty()) {
                io.xdag.p2p.XdagP2pEventHandler eventHandler =
                    (io.xdag.p2p.XdagP2pEventHandler) kernel.getP2pEventHandler();

                // Request from all active peers
                channels.forEach(channel -> {
                    eventHandler.requestBlockByHash(channel, result.getHash());
                });

                log.debug("Requested missing parent Block: {} from {} peers (for child: {})",
                         result.getHash().toHexString(),
                         channels.size(),
                         syncBlock.getBlock().getHash().toHexString());
            }
        }
    }

    /**
     * Add Block to waiting queue for missing parent
     *
     * Phase 7.2: Manages the queue of child blocks waiting for their parent blocks.
     * Similar to syncPushBlock() but for Block objects.
     *
     * @param syncBlock SyncBlock to add to waiting queue
     * @param parentHash Hash of the missing parent block
     * @return true if block was added or request should be sent, false if duplicate
     */
    public boolean syncPushBlock(SyncBlock syncBlock, Bytes32 parentHash) {
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
        Queue<SyncBlock> newQueue = Queues.newConcurrentLinkedQueue();
        syncBlock.setTime(now);
        newQueue.add(syncBlock);
        // Phase 7.3: Use new ChainStats increment method
        blockchain.incrementWaitingSyncCount();

        // Merge with existing queue (if any)
        syncMapV5.merge(parentHash, newQueue, (oldQueue, newQ) -> {
            // Phase 7.3: Undo increment since merging
            blockchain.decrementWaitingSyncCount();

            // Check if this block is already in the queue
            for (SyncBlock existing : oldQueue) {
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
     * Process child Block objects when their parent arrives
     *
     * Phase 7.2: When a parent Block is successfully imported, this method
     * retrieves all child blocks that were waiting for it and attempts to import them.
     *
     * @param syncBlock SyncBlock that was just imported (the parent)
     */
    public void syncPopBlock(SyncBlock syncBlock) {
        Block block = syncBlock.getBlock();

        // Get queue of child blocks waiting for this parent
        Queue<SyncBlock> queue = syncMapV5.getOrDefault(block.getHash(), null);
        if (queue != null) {
            syncMapV5.remove(block.getHash());
            // Phase 7.3: Use new ChainStats decrement method
            blockchain.decrementWaitingSyncCount();

            log.debug("Processing {} child Block objects waiting for parent: {}",
                     queue.size(), block.getHash().toHexString());

            // Try to import each child block
            queue.forEach(childSync -> {
                ImportResult childResult = importBlock(childSync);

                switch (childResult) {
                    case EXIST, IN_MEM, IMPORTED_BEST, IMPORTED_NOT_BEST -> {
                        // Child successfully imported - process its children recursively
                        syncPopBlock(childSync);
                        queue.remove(childSync);
                    }
                    case NO_PARENT -> {
                        // Child still has missing parent (different from this one)
                        doNoParentV5(childSync, childResult);
                    }
                    case INVALID_BLOCK -> {
                        log.warn("Child Block invalid after parent arrived: {} (reason: {})",
                                childSync.getBlock().getHash().toHexString(),
                                childResult.getErrorInfo());
                    }
                    default -> {
                        log.debug("Unexpected result for child Block: {} → {}",
                                childSync.getBlock().getHash().toHexString(), childResult);
                    }
                }
            });
        }
    }

    /**
     * Broadcast Block to network peers
     *
     * Phase 7.2: Distributes a Block object to all connected peers using the P2P service.
     *
     * @param syncBlock SyncBlock to broadcast
     */
    public void distributeBlock(SyncBlock syncBlock) {
        // Use Kernel's Block broadcast method
        kernel.broadcastBlock(syncBlock.getBlock(), syncBlock.getTtl());

        log.debug("Distributed Block: {} (ttl={})",
                 syncBlock.getBlock().getHash().toHexString(),
                 syncBlock.getTtl());
    }

    /**
     * Log missing parent information for Block
     *
     * @param syncBlock SyncBlock with missing parent
     * @param importResult ImportResult containing parent hash
     */
    private void logParentV5(SyncBlock syncBlock, ImportResult importResult) {
        log.debug("Block {} waiting for parent: {}",
                 syncBlock.getBlock().getHash().toHexString(),
                 importResult.getHash().toHexString());
    }

    // ========== End of Block Sync Methods ==========

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
