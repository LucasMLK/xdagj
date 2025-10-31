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
     * Queue with validated blocks to be added to the blockchain
     */
    private Queue<SyncBlock> blockQueue = new ConcurrentLinkedQueue<>();
    /**
     * Queue for blocks with missing links
     */
    private ConcurrentHashMap<Bytes32, Queue<SyncBlock>> syncMap = new ConcurrentHashMap<>();
    /**
     * Queue for polling oldest blocks
     */
    private ConcurrentLinkedQueue<Bytes32> syncQueue = new ConcurrentLinkedQueue<>();

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

        XdagStats xdagStats = kernel.getBlockchain().getXdagStats();
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
    @Deprecated(since = "0.8.1", forRemoval = true)
    // TODO: Modify consensus
    public ImportResult importBlock(SyncBlock syncBlock) {
        log.debug("importBlock:{}", syncBlock.getBlock().getHash());
        ImportResult importResult = blockchain
                .tryToConnect(new Block(new XdagBlock(syncBlock.getBlock().getXdagBlock().getData().toArray())));

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

  /**
     * Synchronize missing blocks
     *
     * @param syncBlock New block
     * @param hash Hash of missing parent block
     */
    public boolean syncPushBlock(SyncBlock syncBlock, Bytes32 hash) {
        if (syncMap.size() >= MAX_SIZE) {
            for (int j = 0; j < DELETE_NUM; j++) {
                List<Bytes32> keyList = new ArrayList<>(syncMap.keySet());

                Bytes32 key = keyList.get(CryptoProvider.nextInt(0, keyList.size()));
                assert key != null;
                if (syncMap.remove(key) != null) blockchain.getXdagStats().nwaitsync--;
            }
        }
        AtomicBoolean r = new AtomicBoolean(true);
        long now = System.currentTimeMillis();

        Queue<SyncBlock> newQueue = Queues.newConcurrentLinkedQueue();
        syncBlock.setTime(now);
        newQueue.add(syncBlock);
        blockchain.getXdagStats().nwaitsync++;

        syncMap.merge(hash, newQueue,
                (oldQ, newQ) -> {
                    blockchain.getXdagStats().nwaitsync--;
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

    /**
     * Release child blocks based on received block
     */
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

            log.info("sync done, the last main block number = {}", blockchain.getXdagStats().nmain);
            kernel.getSync().setStatus(XdagSync.Status.SYNC_DONE);
            if (config.getEnableTxHistory() && txHistoryStore != null) {
                // Sync done, batch write remaining history
                txHistoryStore.batchSaveTxHistory(null);
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

    public void distributeBlock(SyncBlock syncBlock) {
        // Use Kernel's broadcast method with P2P service
        kernel.broadcastBlock(syncBlock.getBlock(), syncBlock.getTtl());
    }

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
