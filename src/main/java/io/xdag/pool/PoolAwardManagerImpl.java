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
package io.xdag.pool;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.cli.Commands;
import io.xdag.config.Config;
import io.xdag.core.*;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BasicUtils;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static io.xdag.config.Constants.MIN_GAS;
// TODO v5.1: Restore after migrating to BlockV5 Transaction system
// import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_IN;
// import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUTPUT;
import static io.xdag.pool.PoolAwardManagerImpl.BlockRewardHistorySender.awardMessageHistoryQueue;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.BytesUtils.compareTo;
import static io.xdag.utils.WalletUtils.checkAddress;

@Slf4j
public class PoolAwardManagerImpl extends AbstractXdagLifecycle implements PoolAwardManager, Runnable {
    private static final String TX_REMARK = "";
    private final Kernel kernel;
    protected Config config;
    private final Blockchain blockchain;
    private final Wallet wallet;
    private final String fundAddress;
    private final double fundRation;
    private final double nodeRation;
    private final Commands commands;
    /**
     * The hash of the past sixteen blocks
     */
    protected List<Bytes32> blockPreHashs = new CopyOnWriteArrayList<>(new ArrayList<>(16));
    protected List<Bytes32> blockHashs = new CopyOnWriteArrayList<>(new ArrayList<>(16));
    protected List<Bytes32> minShares = new CopyOnWriteArrayList<>(new ArrayList<>(16));
    // Phase 9: Node reward accumulation for batch processing
    // Stores deferred node rewards (5%) to be sent in batches of 10
    private final Map<Bytes32, NodeReward> paymentsToNodesMap = new HashMap<>(10);
    private static final BlockingQueue<AwardBlock> awardBlockBlockingQueue = new LinkedBlockingQueue<>();

    /**
     * Helper class to store node reward information (Phase 9)
     */
    private static class NodeReward {
        final XAmount amount;      // Node reward amount (5% of block reward)
        final ECKeyPair keyPair;   // Wallet key for signing the reward transaction

        NodeReward(XAmount amount, ECKeyPair keyPair) {
            this.amount = amount;
            this.keyPair = keyPair;
        }
    }

    // Phase 8.5: Nonce tracking per reward source address
    // Prevents transaction replay attacks in pool reward distribution
    private final Map<Bytes32, AtomicLong> rewardAccountNonces = new ConcurrentHashMap<>();

    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor(BasicThreadFactory.builder()
            .namingPattern("PoolAwardManager-work-thread")
            .daemon(true)
            .build());

    public PoolAwardManagerImpl(Kernel kernel) {
        this.kernel = kernel;
        this.config = kernel.getConfig();
        this.wallet = kernel.getWallet();
        this.fundAddress = config.getFundSpec().getFundAddress();
        this.fundRation = Math.max(0, Math.min(config.getFundSpec().getFundRation(), 100));
        this.nodeRation = Math.max(0, Math.min(config.getNodeSpec().getNodeRation(), 100));
        this.blockchain = kernel.getBlockchain();
        this.commands = new Commands(kernel);
        init();
    }

    public void addAwardBlock(Bytes32 share, Bytes32 preHash, Bytes32 hash, long generateTime) {
        AwardBlock awardBlock = new AwardBlock();
        awardBlock.share = share;
        awardBlock.preHash = preHash;
        awardBlock.hash = hash;
        awardBlock.generateTime = generateTime;
        if (!awardBlockBlockingQueue.offer(awardBlock)) {
            log.error("Failed to add a awardBlock to the block queue!");
        }
    }

    @Override
    protected void doStart() {
        workExecutor.execute(this);
        log.debug("PoolAwardManager started.");
    }

    @Override
    protected void doStop() {
        workExecutor.shutdown();
    }

    @Override
    public void run() {
        while (isRunning()) {
            try {
                AwardBlock awardBlock = awardBlockBlockingQueue.poll(1, TimeUnit.SECONDS);
                if (awardBlock != null) {
                    log.debug("Start award this block:{}", awardBlock.hash.toHexString());
                    payAndAddNewAwardBlock(awardBlock);
                }
            } catch (InterruptedException e) {
                log.error(" Can not take the awardBlock from awardBlockQueue{}", e.getMessage(), e);
            }
        }
    }

    public void init() {
        log.debug("Pool award manager init.");
        // Container initialization
        for (int i = 0; i < 16; i++) {
            blockHashs.add(null);
            minShares.add(null);
            blockPreHashs.add(null);
        }
    }

    public void payAndAddNewAwardBlock(AwardBlock awardBlock) {
        int awardBlockIndex = (int) ((awardBlock.generateTime >> 16) & config.getNodeSpec().getAwardEpoch());
        log.debug("Add reward block to index: {}", awardBlockIndex);
        if (payPools(awardBlock.generateTime) == 0) {
            log.debug("Start distributing block rewards...");
        }
        blockPreHashs.set(awardBlockIndex, awardBlock.preHash);
        blockHashs.set(awardBlockIndex, awardBlock.hash);
        minShares.set(awardBlockIndex, awardBlock.share);
    }

    /**
     * Pool payment distribution (Phase 8.5 - Re-enabled with v5.1 Transaction support)
     *
     * Phase 8.5: Pool reward distribution re-enabled after implementing nonce tracking
     * and migrating doPayments() to use List<Bytes32> + List<XAmount> instead of Address.
     *
     * This method finds a block to distribute rewards from (16 rounds delayed),
     * extracts pool wallet info from the block's nonce field, and calls doPayments().
     *
     * @param time Current time for calculating which block to pay
     * @return 0 on success, negative error code on failure
     */
    public int payPools(long time) {
        // Obtain the corresponding +1 position of the current task and delay it for 16 rounds
        int paidBlockIndex = (int) (((time >> 16) + 1) & config.getNodeSpec().getAwardEpoch());
        log.info("Index of the block paid to the pool:{} ", paidBlockIndex);
        int keyPos;

        // Obtain the block hash and corresponding share to be paid
        Bytes32 preHash = blockPreHashs.get(paidBlockIndex) == null ? null : blockPreHashs.get(paidBlockIndex);
        Bytes32 blockHash = blockHashs.get(paidBlockIndex) == null ? null : blockHashs.get(paidBlockIndex);
        Bytes32 share = minShares.get(paidBlockIndex) == null ? null : minShares.get(paidBlockIndex);
        if (blockHash == null || share == null || preHash == null) {
            log.debug("Can not find the hash or nonce or preHash ,hash is null ?[{}],nonce is null ?[{}],preHash is " +
                            "null ?[{}]",
                    blockHash == null,
                    share == null, preHash == null);
            return -1;
        }
        // Obtain the hash (legacy format) of this block for query
        MutableBytes32 hash = MutableBytes32.create();
        hash.set(8, Bytes.wrap(blockHash).slice(8, 24));
        // Phase 8.5: Blockchain interface returns BlockV5, use it directly for nonce/coinbase access
        BlockV5 blockV5 = blockchain.getBlockByHash(hash, true);
        if (blockV5 == null) {
            log.debug("Can't find the block");
            return -2;
        }
        log.debug("Hash (legacy format) [{}]", hash.toHexString());

        // Phase 8.5: Access nonce and coinbase directly from BlockV5.header
        Bytes32 blockNonce = blockV5.getHeader().getNonce();
        Bytes32 blockCoinbase = blockV5.getHeader().getCoinbase();

        // nonce = share(12 bytes) + pool wallet address(20 bytes)
        // Check if this block is produced by pool mining (nonce contains pool address different from coinbase)
        if (compareTo(blockNonce.slice(12, 20).toArray(), 0,
                20, blockCoinbase.slice(8, 20).toArray(), 0, 20) == 0) {
            log.debug("This block is not produced by mining and belongs to the node, block hash:{}",
                    hash.toHexString());
            return -3;
        }
        if (kernel.getBlockchain().getMemOurBlocks().get(hash) == null) {
            keyPos = kernel.getBlockStore().getKeyIndexByHash(hash);
        } else {
            keyPos = kernel.getBlockchain().getMemOurBlocks().get(hash);
        }
        if (keyPos < 0) {
            log.debug("keyPos < 0,keyPos = {}", keyPos);
            return -4;
        }

        // Phase 9: Calculate block reward from block height
        XAmount allAmount;
        if (blockV5.getInfo() == null) {
            log.warn("Block info not loaded, cannot calculate reward from height");
            allAmount = XAmount.of(1024, XUnit.XDAG);  // Fallback
        } else {
            long blockHeight = blockV5.getInfo().getHeight();
            if (blockHeight > 0) {
                // Use blockchain.getReward() to calculate correct block reward based on height
                allAmount = blockchain.getReward(blockHeight);
                log.debug("Calculated block reward for height {}: {} XDAG",
                        blockHeight, allAmount.toDecimal(9, XUnit.XDAG).toPlainString());
            } else {
                // Orphan block (height = 0), should not pay rewards
                log.debug("Block is orphan (height=0), cannot pay rewards");
                return -5;
            }
        }

        // Phase 8.5: Extract pool wallet address from nonce (bytes 12-31)
        Bytes32 poolWalletAddress = BasicUtils.hexPubAddress2Hash(String.valueOf(blockNonce.slice(12, 20)));
        if (!checkAddress(Base58.encodeCheck(blockNonce.slice(12, 20)))) {
            log.error("mining pool wallet address format error");
            return -6;
        }
        if (allAmount.multiply(div(fundRation, 100, 6)).lessThanOrEqual(MIN_GAS)) {
            log.error("The community reward ratio is set too small, and the rewards are refused to be distributed.");
            return -7;
        }
        log.debug("=========== At this time {} starts to distribute rewards to pools===========", time);
        TransactionInfoSender transactionInfoSender = new TransactionInfoSender();
        transactionInfoSender.setPreHash(preHash);
        transactionInfoSender.setShare(share);
        try {
            // Phase 8.5: Call newly rewritten doPayments() method
            doPayments(hash, allAmount, poolWalletAddress, keyPos, transactionInfoSender);
        } catch (AddressFormatException e) {
            throw new RuntimeException(e);
        }
        return 0;
    }

    /**
     * Distribute rewards to pool, foundation, and node (Phase 8.5 - v5.1 implementation)
     *
     * Phase 8.5: Migrated from Address-based system to direct Transaction creation.
     * Splits block rewards into three parts:
     * - Foundation (fundRation, default 5%)
     * - Pool (remainder after deducting foundation and node)
     * - Node (nodeRation, default 5%)
     *
     * Foundation + Pool rewards sent immediately via transaction() method.
     * Node rewards accumulated in paymentsToNodesMap for batch processing.
     *
     * @param hash Source block hash
     * @param allAmount Total reward amount
     * @param poolWalletAddress Pool's wallet address
     * @param keyPos Wallet key position for signing
     * @param transactionInfoSender Transaction info for pool notification
     */
    public void doPayments(Bytes32 hash, XAmount allAmount, Bytes32 poolWalletAddress, int keyPos,
                           TransactionInfoSender transactionInfoSender)
        throws AddressFormatException {

        // Phase 9: Check if node rewards map is full, send batch if needed
        if (paymentsToNodesMap.size() >= 10) {
            log.info("Node reward map reached size limit ({}), sending batch transaction",
                    paymentsToNodesMap.size());
            sendBatchNodeRewards();
        }

        // Calculate reward distribution
        // Foundation rewards, default reward ratio is 5%
        XAmount fundAmount = allAmount.multiply(div(fundRation, 100, 6));
        // Node rewards, default reward ratio is 5%
        XAmount nodeAmount = allAmount.multiply(div(nodeRation, 100, 6));
        // Pool rewards (what's left after foundation and node)
        XAmount poolAmount = allAmount.subtract(fundAmount).subtract(nodeAmount);
        // sendAmount = Foundation rewards + Pool rewards (node rewards handled separately)
        XAmount sendAmount = allAmount.subtract(nodeAmount);

        // Validate reward ratios
        if (fundRation + nodeRation >= 100 || fundAmount.lessThan(MIN_GAS) || poolAmount.lessThan(MIN_GAS)) {
            log.error("Block reward distribution failed. The fundRation and nodeRation parameter settings are " +
                    "unreasonable. Your fundRation:{}, nodeRation:{}", fundRation, nodeRation);
            return;
        }

        // Phase 8.5: Use List<Bytes32> recipients and List<XAmount> amounts
        if (sendAmount.compareTo(MIN_GAS.multiply(2)) >= 0) {
            List<Bytes32> recipients = new ArrayList<>(2);
            List<XAmount> amounts = new ArrayList<>(2);

            // Add foundation recipient
            Bytes32 fundAddressHash = pubAddress2Hash(fundAddress);
            recipients.add(fundAddressHash);
            amounts.add(fundAmount);

            // Add pool recipient
            recipients.add(poolWalletAddress);
            amounts.add(poolAmount);

            // Set transaction info for pool notification
            transactionInfoSender.setAmount(poolAmount.subtract(MIN_GAS).toDecimal(9,
                    XUnit.XDAG).toPlainString());
            transactionInfoSender.setFee(MIN_GAS.toDecimal(9, XUnit.XDAG).toPlainString());
            transactionInfoSender.setDonate(fundAmount.toDecimal(9, XUnit.XDAG).toPlainString());

            log.debug("Start payment...");
            log.debug("Foundation: {} XDAG to {}", fundAmount.toDecimal(9, XUnit.XDAG), fundAddress);
            log.debug("Pool: {} XDAG to {}", poolAmount.toDecimal(9, XUnit.XDAG), poolWalletAddress.toHexString());
            log.debug("Node: {} XDAG (deferred)", nodeAmount.toDecimal(9, XUnit.XDAG));

            // Phase 8.5: Call new transaction() signature with List<Bytes32> and List<XAmount>
            transaction(hash, recipients, amounts, keyPos, transactionInfoSender);

            // Phase 9: Store node reward for batch processing
            paymentsToNodesMap.put(hash, new NodeReward(nodeAmount, wallet.getAccount(keyPos)));
            log.info("Node reward deferred for block {}, amount: {} XDAG, Map size: {}",
                    hash.toHexString(), nodeAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                    paymentsToNodesMap.size());
        } else {
            log.debug("The balance of block {} is insufficient and rewards will not be distributed. " +
                            "Maybe this block has been rollback. send balance: {}",
                    hash.toHexString(), sendAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        }
    }

    /**
     * Get next nonce for reward distribution from specified address (Phase 8.5)
     *
     * Phase 8.5: Implements proper nonce tracking for pool reward distribution.
     * Each source address maintains an independent nonce counter to prevent
     * transaction replay attacks.
     *
     * Nonce Strategy:
     * - Starts from 0 for each source address
     * - Increments atomically for each reward distribution
     * - Thread-safe using AtomicLong
     * - Resets to 0 on node restart (safe because old transactions are on-chain)
     *
     * @param sourceAddress Reward source address (pool wallet)
     * @return Next available nonce for this address
     */
    private long getNextNonce(Bytes32 sourceAddress) {
        return rewardAccountNonces
            .computeIfAbsent(sourceAddress, k -> new AtomicLong(0))
            .getAndIncrement();
    }

    /**
     * Send batch node reward distribution (Phase 9)
     *
     * Phase 9: Implements batch distribution of accumulated node rewards (5% portions).
     * Sends all accumulated node rewards to the node's coinbase address.
     *
     * Strategy:
     * - Iterate through all accumulated node rewards in paymentsToNodesMap
     * - For each source block, send its node reward (5%) to node address
     * - Use source block's keyPair for signing each reward transaction
     * - Clear map after successful distribution
     *
     * This approach sends multiple reward blocks (one per source block) but processes
     * them together to ensure all accumulated rewards are distributed atomically.
     */
    private void sendBatchNodeRewards() {
        if (paymentsToNodesMap.isEmpty()) {
            log.warn("sendBatchNodeRewards called but paymentsToNodesMap is empty");
            return;
        }

        // Get node's coinbase address (where node rewards go)
        Bytes32 nodeAddress = keyPair2Hash(wallet.getDefKey());

        // Calculate total amount for logging
        XAmount totalAmount = paymentsToNodesMap.values().stream()
            .map(nr -> nr.amount)
            .reduce(XAmount.ZERO, XAmount::add);

        log.info("Starting batch node reward distribution: {} source blocks, total {} XDAG to node {}",
                paymentsToNodesMap.size(),
                totalAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                nodeAddress.toHexString().substring(0, 16) + "...");

        int successCount = 0;
        int failCount = 0;

        // Send individual reward blocks for each source block
        for (Map.Entry<Bytes32, NodeReward> entry : paymentsToNodesMap.entrySet()) {
            Bytes32 sourceBlockHash = entry.getKey();
            NodeReward nodeReward = entry.getValue();

            try {
                // Create single-recipient list (node address)
                List<Bytes32> recipients = new ArrayList<>(1);
                recipients.add(nodeAddress);

                List<XAmount> amounts = new ArrayList<>(1);
                amounts.add(nodeReward.amount);

                // Get source address for nonce tracking
                Bytes32 sourceAddress = keyPair2Hash(nodeReward.keyPair);
                long baseNonce = getNextNonce(sourceAddress);

                // Create reward BlockV5 for this source block's node reward
                BlockV5 rewardBlock = blockchain.createRewardBlockV5(
                    sourceBlockHash,    // source block hash
                    recipients,         // node address
                    amounts,            // node reward amount
                    nodeReward.keyPair, // source key for signing
                    baseNonce,          // proper nonce tracking
                    MIN_GAS             // fee
                );

                // Import reward block to blockchain
                ImportResult result = kernel.getSyncMgr().validateAndAddNewBlockV5(
                    new io.xdag.consensus.SyncManager.SyncBlockV5(rewardBlock, 5)
                );

                if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                    log.debug("Node reward sent: {} XDAG from block {} (result: {})",
                            nodeReward.amount.toDecimal(9, XUnit.XDAG).toPlainString(),
                            sourceBlockHash.toHexString().substring(0, 16) + "...",
                            result);
                    successCount++;
                } else {
                    log.warn("Node reward import failed for block {}: result={}, error={}",
                            sourceBlockHash.toHexString().substring(0, 16) + "...",
                            result,
                            result.getErrorInfo() != null ? result.getErrorInfo() : "none");
                    failCount++;
                }

            } catch (Exception e) {
                log.error("Failed to send node reward for block {}: {}",
                        sourceBlockHash.toHexString(), e.getMessage(), e);
                failCount++;
            }
        }

        // Clear the map after processing all rewards
        paymentsToNodesMap.clear();

        log.info("Batch node reward distribution complete: {} succeeded, {} failed, total {} XDAG sent",
                successCount, failCount,
                totalAmount.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    /**
     * Create and distribute reward block to recipients (Phase 8.5 - v5.1 implementation)
     *
     * Phase 8.5: Pool reward distribution using BlockV5 + Transaction architecture.
     * This method creates a reward BlockV5 containing Transaction objects for each recipient.
     *
     * Key Changes from Legacy:
     * - Uses List<Bytes32> recipients instead of ArrayList<Address>
     * - Proper nonce tracking via getNextNonce() instead of nonce = 0
     * - Direct Transaction creation instead of Address-based fields
     *
     * @param hash Source block hash (where funds come from)
     * @param recipients List of recipient addresses
     * @param amounts List of amounts for each recipient
     * @param keyPos Wallet key position for signing
     * @param transactionInfoSender Transaction info for pool notification
     */
    public void transaction(Bytes32 hash, List<Bytes32> recipients, List<XAmount> amounts, int keyPos,
                            TransactionInfoSender transactionInfoSender) {
        XAmount sendAmount = amounts.stream().reduce(XAmount.ZERO, XAmount::add);
        log.debug("Total balance pending transfer: {}", sendAmount.toDecimal(9, XUnit.XDAG).toPlainString());
        log.debug("unlock keypos =[{}]", keyPos);

        // Phase 8.5: Get source key for signing
        ECKeyPair sourceKey = wallet.getAccount(keyPos);
        Bytes32 sourceAddress = keyPair2Hash(sourceKey);

        // Phase 8.5: Get next nonce for this source address
        long baseNonce = getNextNonce(sourceAddress);

        // Create reward BlockV5
        BlockV5 rewardBlock = blockchain.createRewardBlockV5(
            hash,           // source block hash
            recipients,     // recipient addresses
            amounts,        // amounts for each recipient
            sourceKey,      // source key for signing
            baseNonce,      // Phase 8.5: Proper nonce tracking
            MIN_GAS.multiply(recipients.size())  // total fee
        );

        // Import reward block to blockchain
        ImportResult result = kernel.getSyncMgr().validateAndAddNewBlockV5(
            new io.xdag.consensus.SyncManager.SyncBlockV5(rewardBlock, 5)
        );

        log.debug("Reward BlockV5 import result: {}", result);
        log.debug("Reward block hash: {}", rewardBlock.getHash().toHexString());
        log.debug("Nonce used: {} (for {} transactions)", baseNonce, recipients.size());

        // Update transaction info for pool
        transactionInfoSender.setTxBlock(rewardBlock.getHash());
        transactionInfoSender.setDonateBlock(rewardBlock.getHash());

        // Send reward distribution info to pools
        if (awardMessageHistoryQueue.remainingCapacity() == 0) {
            awardMessageHistoryQueue.poll();
        }

        // Send the last 16 reward distribution transaction history to the pool
        if (awardMessageHistoryQueue.offer(transactionInfoSender.toJsonString())) {
            ChannelSupervise.send2Pools(BlockRewardHistorySender.toJsonString());
        } else {
            log.error("Failed to add transaction history");
        }

        log.debug("The reward for block {} has been distributed to {} recipients",
                 hash.toHexString(), recipients.size());
    }


    /**
     * Used to record information about the reward main block
     */
    public static class AwardBlock {
        Bytes32 share;
        Bytes32 preHash;
        Bytes32 hash;
        long generateTime;
    }

    @Setter
    public static class TransactionInfoSender {
        // Single transaction history
        Bytes32 txBlock;
        Bytes32 donateBlock;
        Bytes32 preHash;
        Bytes32 share;
        String amount;
        String fee;
        String donate;

        public String toJsonString() {
            return "{\n" +
                    "  \"txBlock\":\"" + txBlock.toUnprefixedHexString() + "\",\n" +
                    "  \"preHash\":\"" + preHash.toUnprefixedHexString() + "\",\n" +
                    "  \"share\":\"" + share.toUnprefixedHexString() + "\",\n" +
                    "  \"amount\":" + amount + ",\n" +
                    "  \"fee\":" + fee + ",\n" +
                    "  \"donateBlock\":\"" + txBlock.toUnprefixedHexString() + "\",\n" +
                    "  \"donate\":" + donate +
                    "\n}";
        }
    }

    public static class BlockRewardHistorySender {
        // Cache the last 16 blocks reward transaction history
        public static final BlockingQueue<String> awardMessageHistoryQueue = new LinkedBlockingQueue<>(16);
        private static final int REWARD_HISTORIES_FLAG = 3;

        public static String toJsonString() {
            return "{\n" +
                    "  \"msgType\": " + REWARD_HISTORIES_FLAG + ",\n" +
                    "  \"msgContent\": \n" + awardMessageHistoryQueue + "\n" +
                    "}";
        }

    }

}
