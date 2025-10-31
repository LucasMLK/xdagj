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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.core.XdagLifecycle;
import io.xdag.core.*;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.hash.XdagSha256Digest;
import io.xdag.listener.BlockMessage;
import io.xdag.listener.Listener;
import io.xdag.listener.PretopMessage;
import io.xdag.pool.ChannelSupervise;
import io.xdag.pool.PoolAwardManager;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.XdagTime;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.xdag.utils.BasicUtils.hash2byte;
import static io.xdag.utils.BasicUtils.keyPair2Hash;
import static io.xdag.utils.BytesUtils.*;


@Slf4j
public class XdagPow implements PoW, Listener, Runnable, XdagLifecycle {


    private final Kernel kernel;
    protected BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    protected Timer timer;
    protected Broadcaster broadcaster;
    @Getter
    protected GetShares sharesFromPools;
    // Current block (Phase 5.5: migrated to BlockV5)
    protected AtomicReference<BlockV5> generateBlockV5 = new AtomicReference<>();
    protected AtomicReference<Bytes32> minShare = new AtomicReference<>();
    protected final AtomicReference<Bytes32> minHash = new AtomicReference<>();
    protected final Wallet wallet;

    protected Blockchain blockchain;
    protected volatile Bytes32 globalPretop;
    protected PoolAwardManager poolAwardManager;
    protected AtomicReference<Task> currentTask = new AtomicReference<>();
    protected AtomicLong taskIndex = new AtomicLong(0L);
    private boolean isWorking = false;

    private final ExecutorService timerExecutor = Executors.newSingleThreadExecutor(BasicThreadFactory.builder()
            .namingPattern("XdagPow-timer-thread")
            .build());

    private final ExecutorService mainExecutor = Executors.newSingleThreadExecutor(BasicThreadFactory.builder()
            .namingPattern("XdagPow-main-thread")
            .build());

    private final ExecutorService broadcasterExecutor = Executors.newSingleThreadExecutor(BasicThreadFactory.builder()
            .namingPattern("XdagPow-broadcaster-thread")
            .build());
    private final ExecutorService getSharesExecutor = Executors.newSingleThreadExecutor(BasicThreadFactory.builder()
            .namingPattern("XdagPow-getShares-thread")
            .build());

    protected RandomX randomXUtils;
    private final AtomicBoolean running = new AtomicBoolean(false);


    public XdagPow(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.timer = new Timer();
        this.broadcaster = new Broadcaster();
        this.randomXUtils = kernel.getRandomx();
        this.sharesFromPools = new GetShares();
        this.poolAwardManager = kernel.getPoolAwardManager();
        this.wallet = kernel.getWallet();

    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            getSharesExecutor.execute(this.sharesFromPools);
            mainExecutor.execute(this);
            kernel.getPoolAwardManager().start();
            timerExecutor.execute(timer);
            broadcasterExecutor.execute(this.broadcaster);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            timer.isRunning = false;
            broadcaster.isRunning = false;
            sharesFromPools.isRunning = false;
        }
    }

    public void newBlock() {
        log.debug("Start new block generate....");
        long sendTime = XdagTime.getMainTime();
        resetTimeout(sendTime);
        if (randomXUtils != null && randomXUtils.isRandomxFork(XdagTime.getEpoch(sendTime))) {
            if (randomXUtils.getRandomXPoolMemIndex() == 0) {
                randomXUtils.setRandomXPoolMemIndex((randomXUtils.getRandomXHashEpochIndex() - 1) & 1);
            }

            if (randomXUtils.getRandomXPoolMemIndex() == -1) {

                long switchTime0 = randomXUtils.getGlobalMemory()[0] == null ? 0 : randomXUtils.getGlobalMemory()[0].getSwitchTime();
                long switchTime1 = randomXUtils.getGlobalMemory()[1] == null ? 0 : randomXUtils.getGlobalMemory()[1].getSwitchTime();

                if (switchTime0 > switchTime1) {
                    if (XdagTime.getEpoch(sendTime) > switchTime0) {
                        randomXUtils.setRandomXPoolMemIndex(2);
                    } else {
                        randomXUtils.setRandomXPoolMemIndex(1);
                    }
                } else {
                    if (XdagTime.getEpoch(sendTime) > switchTime1) {
                        randomXUtils.setRandomXPoolMemIndex(1);
                    } else {
                        randomXUtils.setRandomXPoolMemIndex(2);
                    }
                }
            }

            long randomXMemIndex = randomXUtils.getRandomXPoolMemIndex() + 1;
            RandomXMemory memory = randomXUtils.getGlobalMemory()[(int) (randomXMemIndex) & 1];

            if ((XdagTime.getEpoch(XdagTime.getMainTime()) >= memory.getSwitchTime()) && (memory.getIsSwitched() == 0)) {
                randomXUtils.setRandomXPoolMemIndex(randomXUtils.getRandomXPoolMemIndex() + 1);
                memory.setIsSwitched(1);
            }
            generateBlockV5.set(generateRandomXBlock(sendTime));
        } else {
            generateBlockV5.set(generateBlock(sendTime));
        }
    }


    /**
     * Generate RandomX mining block (Phase 5.5: BlockV5 version)
     *
     * Key changes from legacy Block version:
     * 1. Uses blockchain.createMainBlockV5() instead of createNewBlock()
     * 2. No signOut() call (coinbase already in BlockHeader)
     * 3. Uses block.withNonce() to set initial nonce (immutable pattern)
     * 4. Returns BlockV5 instead of Block
     *
     * @param sendTime mining timestamp
     * @return BlockV5 candidate block for mining
     */
    public BlockV5 generateRandomXBlock(long sendTime) {
        taskIndex.incrementAndGet();

        // Create BlockV5 candidate (nonce = 0, coinbase in header)
        BlockV5 block = blockchain.createMainBlockV5();

        // Set initial nonce (last 20 bytes are node wallet address)
        Bytes32 initialNonce = Bytes32.wrap(BytesUtils.merge(
            CryptoProvider.nextBytes(12),
            hash2byte(keyPair2Hash(wallet.getDefKey()))
        ));
        minShare.set(initialNonce);

        // Create new block with initial nonce (BlockV5 is immutable)
        block = block.withNonce(minShare.get());

        // Reset minHash
        minHash.set(Bytes32.fromHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));

        // Create task and broadcast to pools
        currentTask.set(createTaskByRandomXBlock(block, sendTime));
        ChannelSupervise.send2Pools(currentTask.get().toJsonString());

        return block;
    }


    /**
     * Generate non-RandomX mining block (Phase 5.5: BlockV5 version)
     *
     * Key changes from legacy Block version:
     * 1. Uses blockchain.createMainBlockV5() instead of createNewBlock()
     * 2. No signOut() call (coinbase already in BlockHeader)
     * 3. Uses block.withNonce() to set initial nonce (immutable pattern)
     * 4. Uses block.getHash() instead of recalcHash()
     * 5. Returns BlockV5 instead of Block
     *
     * @param sendTime mining timestamp
     * @return BlockV5 candidate block for mining
     */
    public BlockV5 generateBlock(long sendTime) {
        taskIndex.incrementAndGet();

        // Create BlockV5 candidate (nonce = 0, coinbase in header)
        BlockV5 block = blockchain.createMainBlockV5();

        // Set initial nonce (last 20 bytes are node wallet address)
        Bytes32 initialNonce = Bytes32.wrap(BytesUtils.merge(
            CryptoProvider.nextBytes(12),
            hash2byte(keyPair2Hash(wallet.getDefKey()))
        ));
        minShare.set(initialNonce);

        // Create new block with initial nonce (BlockV5 is immutable)
        block = block.withNonce(minShare.get());

        // Calculate initial hash
        minHash.set(block.getHash());

        // Create task and broadcast to pools
        currentTask.set(createTaskByNewBlock(block, sendTime));
        ChannelSupervise.send2Pools(currentTask.get().toJsonString());

        return block;
    }

    protected void resetTimeout(long timeout) {
        timer.timeout(timeout);
        events.removeIf(e -> e.type == Event.Type.TIMEOUT);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Every time a share sent from a pool is received, it will be recorded here.
     */
    @Override
    public void receiveNewShare(String share, String hash, long taskIndex) {

        if (!running.get()) {
            return;
        }
        if (currentTask.get() == null) {
            log.info("Current task is empty");
        } else if (currentTask.get().getTaskIndex() == taskIndex && Objects.equals(hash,
                currentTask.get().getTask()[0].getData().toUnprefixedHexString())) {
            onNewShare(Bytes32.wrap(Bytes.fromHexString(share)));
        } else {
            log.debug("Task index error or preHash error. Current task is {} ,but pool sends task index is {}",
                    currentTask.get().getTaskIndex(), taskIndex);
        }
    }

    public void receiveNewPretop(Bytes pretop) {
        // make sure the PoW is running and the main block is generating
        if (!running.get() || !isWorking) {
            return;
        }

        // prevent duplicate event
        if (globalPretop == null || !equalBytes(pretop.toArray(), globalPretop.toArray())) {
            log.debug("update global pretop:{}", Bytes32.wrap(pretop).toHexString());
            globalPretop = Bytes32.wrap(pretop);
            events.add(new Event(Event.Type.NEW_PRETOP, pretop));
        }
    }

    protected void onNewShare(Bytes32 share) {
        try {
            Task task = currentTask.get();
            Bytes32 hash;
            // if randomx fork
            if (kernel.getRandomx().isRandomxFork(task.getTaskTime())) {
                MutableBytes taskData = MutableBytes.create(64);

                taskData.set(0, task.getTask()[0].getData());// preHash
                taskData.set(32, share);// share
                // Calculate hash
                hash = Bytes32.wrap(kernel.getRandomx().randomXPoolCalcHash(taskData, task.getTaskTime()).reverse());
            } else {
                XdagSha256Digest digest = new XdagSha256Digest(task.getDigest());
                hash = Bytes32.wrap(digest.sha256Final(share.reverse()));
            }
            synchronized (minHash) {
                Bytes32 mh = minHash.get();
                if (compareTo(hash.toArray(), 0, 32, mh.toArray(), 0, 32) < 0) {
                    log.debug("Receive a hash from pool,hash {} is valid.", hash.toHexString());
                    minHash.set(hash);
                    minShare.set(share);

                    // Phase 5.5: Update BlockV5 with new nonce (immutable pattern)
                    // BlockV5 is immutable, so we create a new instance with updated nonce
                    BlockV5 currentBlock = generateBlockV5.get();
                    BlockV5 updatedBlock = currentBlock.withNonce(minShare.get());
                    generateBlockV5.set(updatedBlock);

                    log.debug("New MinShare :{}", share.toHexString());
                    log.debug("New MinHash :{}", hash.toHexString());
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }


    protected void onTimeout() {
        BlockV5 blockV5 = generateBlockV5.get();
        // stop generate main block
        isWorking = false;
        if (blockV5 != null) {
            log.debug("Broadcast locally generated blockchain, waiting to be verified. block hash = [{}]",
                     blockV5.getHash().toHexString());

            // Phase 5.5: Connect BlockV5 to blockchain
            kernel.getBlockchain().tryToConnect(blockV5);

            Bytes32 currentPreHash = Bytes32.wrap(currentTask.get().getTask()[0].getData());
            poolAwardManager.addAwardBlock(minShare.get(), currentPreHash, blockV5.getHash(), blockV5.getTimestamp());

            // Phase 7.7: Broadcast BlockV5 directly (no conversion needed)
            broadcaster.broadcast(blockV5, kernel.getConfig().getNodeSpec().getTTL());
        }
        isWorking = true;
        // start generate main block
        newBlock();
    }

    protected void onNewPreTop() {
        log.debug("Receive New PreTop");
        newBlock();
    }

    /**
     * Create a RandomX task (Phase 5.5: BlockV5 version)
     *
     * Key changes from legacy Block version:
     * 1. Accepts BlockV5 instead of Block
     * 2. Uses block.getRandomXPreHash() instead of SHA256(block.getXdagBlock().getData().slice(0, 480))
     * 3. Task structure unchanged (still uses XdagField for pool compatibility)
     *
     * @param block BlockV5 mining candidate
     * @param sendTime mining timestamp
     * @return Task for RandomX mining
     */
    private Task createTaskByRandomXBlock(BlockV5 block, long sendTime) {
        Task newTask = new Task();
        XdagField[] task = new XdagField[2];

        RandomXMemory memory = randomXUtils.getGlobalMemory()[(int) randomXUtils.getRandomXPoolMemIndex() & 1];

        // Phase 5.5: Use BlockV5.getRandomXPreHash() instead of legacy XdagBlock slicing
        Bytes32 preHash = block.getRandomXPreHash();

        // task[0]=preHash
        task[0] = new XdagField(preHash.mutableCopy());
        // task[1]=taskSeed
        task[1] = new XdagField(MutableBytes.wrap(memory.getSeed()));

        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex.get());

        return newTask;
    }

    /**
     * Create original task (Phase 5.5: BlockV5 version)
     *
     * Key changes from legacy Block version:
     * 1. Accepts BlockV5 instead of Block
     * 2. Uses block.toBytes() to get serialized data for SHA256 digest
     * 3. Task structure unchanged (still uses XdagField for pool compatibility)
     *
     * @param block BlockV5 mining candidate
     * @param sendTime mining timestamp
     * @return Task for non-RandomX mining
     */
    private Task createTaskByNewBlock(BlockV5 block, long sendTime) {
        Task newTask = new Task();

        XdagField[] task = new XdagField[2];

        // Phase 5.5: Use BlockV5 serialization
        // Get the nonce field (last 32 bytes of header, equivalent to field 14 in legacy format)
        byte[] blockBytes = block.toBytes();
        int headerSize = BlockHeader.getSerializedSize();  // 104 bytes
        byte[] nonceBytes = new byte[32];
        // Nonce is at offset 72-104 in header (timestamp 8 + difficulty 32 + nonce 32)
        System.arraycopy(blockBytes, 72, nonceBytes, 0, 32);
        task[1] = new XdagField(MutableBytes.wrap(nonceBytes));

        // Calculate SHA256 digest of first 448 bytes (equivalent to legacy format)
        MutableBytes data = MutableBytes.wrap(blockBytes, 0, Math.min(448, blockBytes.length));

        XdagSha256Digest currentTaskDigest = new XdagSha256Digest();
        try {
            currentTaskDigest.sha256Update(data);
            byte[] state = currentTaskDigest.getState();
            task[0] = new XdagField(MutableBytes.wrap(state));
            currentTaskDigest.sha256Update(MutableBytes.wrap(nonceBytes));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        newTask.setTask(task);
        newTask.setTaskTime(XdagTime.getEpoch(sendTime));
        newTask.setTaskIndex(taskIndex.get());
        newTask.setDigest(currentTaskDigest);

        return newTask;
    }


    @Override
    public void run() {
        log.info("Main PoW start ....");
        timer.timeout(XdagTime.getEndOfEpoch(XdagTime.getCurrentTimestamp() + 64));
        // init pretop
        globalPretop = null;
        while (running.get()) {
            try {
                Event ev = events.poll(10, TimeUnit.MILLISECONDS);
                if (ev == null) {
                    continue;
                }
                switch (ev.getType()) {
                    case TIMEOUT -> {
                        if (kernel.getXdagState() == XdagState.SDST || kernel.getXdagState() == XdagState.STST
                                || kernel.getXdagState() == XdagState.SYNC) {
                            onTimeout();
                        }
                    }
                    case NEW_PRETOP -> {
                        if (kernel.getXdagState() == XdagState.SDST || kernel.getXdagState() == XdagState.STST
                                || kernel.getXdagState() == XdagState.SYNC) {
                            onNewPreTop();
                        }
                    }
                    default -> {
                    }
                }
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void onMessage(io.xdag.listener.Message msg) {
        if (msg instanceof BlockMessage message) {
            // Phase 7.3.0: Legacy Block broadcasting no longer supported
            // Listener system receives legacy Block format, but NEW_BLOCK messages were deleted
            // TODO: Migrate listener system to use BlockV5 objects
            log.warn("Received legacy Block from listener system, but NEW_BLOCK broadcast is no longer supported. " +
                    "Block hash: {}", Bytes32.wrap(message.getData().slice(0, 32)).toHexString());
            log.warn("Listener system needs migration to BlockV5 format");
        }
        if (msg instanceof PretopMessage message) {
            receiveNewPretop(message.getData());
        }
    }

    public static class Event {

        @Getter
        private final Type type;
        private final Object data;
        private Object channel;

        public Event(Type type) {
            this(type, null);
        }

        public Event(Type type, Object data) {
            this.type = type;
            this.data = data;
        }

        public Event(Type type, Object data, Object channel) {
            this.type = type;
            this.data = data;
            this.channel = channel;
        }

        @SuppressWarnings("unchecked")
        public <T> T getData() {
            return (T) data;
        }

        @SuppressWarnings("unchecked")
        public <T> T getChannel() {
            return (T) channel;
        }

        @Override
        public String toString() {
            return "Event [type=" + type + ", data=" + data + "]";
        }

        public enum Type {
            /**
             * Received a timeout signal.
             */
            TIMEOUT,
            /**
             * Received a new pretop message.
             */
            NEW_PRETOP,
            /**
             * Received a new largest diff message.
             */
            NEW_DIFF,
        }
    }

    public class Timer implements Runnable {

        private long timeout;
        private boolean isRunning = false;

        @Override
        public void run() {
            this.isRunning = true;
            while (this.isRunning) {
                if (timeout != -1 && XdagTime.getCurrentTimestamp() > timeout) {
                    log.debug("CurrentTimestamp:{},sendTime:{} Timeout!", XdagTime.getCurrentTimestamp(), timeout);
                    timeout = -1;
                    events.add(new Event(Event.Type.TIMEOUT));
                    continue;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }

        public void timeout(long sendtime) {
            if (sendtime < 0) {
                throw new IllegalArgumentException("Timeout can not be negative");
            }
            this.timeout = sendtime;
        }
    }

    /**
     * Broadcaster for v5.1 - BlockV5 broadcasting (Phase 7.7)
     *
     * Phase 7.7: Updated to broadcast BlockV5 directly instead of legacy Block.
     * Uses kernel.broadcastBlockV5() for network propagation.
     */
    public class Broadcaster implements Runnable {
        // Simple tuple for BlockV5 + TTL
        private static class BroadcastTask {
            final BlockV5 block;
            final int ttl;
            BroadcastTask(BlockV5 block, int ttl) {
                this.block = block;
                this.ttl = ttl;
            }
        }

        private final LinkedBlockingQueue<BroadcastTask> queue = new LinkedBlockingQueue<>();
        private volatile boolean isRunning = false;

        @Override
        public void run() {
            isRunning = true;
            while (isRunning) {
                BroadcastTask task = null;
                try {
                    task = queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
                if (task != null) {
                    kernel.broadcastBlockV5(task.block, task.ttl);
                }
            }
        }

        public void broadcast(BlockV5 block, int ttl) {
            if (!queue.offer(new BroadcastTask(block, ttl))) {
                log.error("Failed to add a message to the broadcast queue: block = {}",
                        block.getHash().toHexString());
            }
        }
    }

    public class GetShares implements Runnable {
        private final LinkedBlockingQueue<String> shareQueue = new LinkedBlockingQueue<>();
        private volatile boolean isRunning = false;
        private static final int SHARE_FLAG = 2;

        @Override
        public void run() {
            isRunning = true;
            while (isRunning) {
                String shareInfo = null;
                try {
                    shareInfo = shareQueue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }

                if (shareInfo != null) {
                    try {
                        JsonObject shareJson = JsonParser.parseString(shareInfo).getAsJsonObject();
                        if (shareJson.get("msgType").getAsInt() == SHARE_FLAG) {
                            JsonObject msgContent = shareJson.getAsJsonObject("msgContent");
                            receiveNewShare(msgContent.get("share").getAsString(),
                                    msgContent.get("hash").getAsString(),
                                    msgContent.get("taskIndex").getAsLong());
                        } else {
                            log.error("Share format error! Current share: {}", shareInfo);
                        }
                    } catch (Exception e) {
                        log.error("Share format error, current share: {}", shareInfo);
                    }
                }
            }
        }

        public void getShareInfo(String share) {
            // TODO: Limit the number of shares submitted by each pool within each block production cycle
            if (!shareQueue.offer(share)) {
                log.error("Failed to get ShareInfo from pools");
            }
        }
    }
}
