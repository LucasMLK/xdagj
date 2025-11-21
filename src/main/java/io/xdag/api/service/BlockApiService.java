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

package io.xdag.api.service;

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.BasicUtils.address2PubAddress;

import io.xdag.DagKernel;
import io.xdag.api.dto.BlockDetail;
import io.xdag.api.dto.BlockSummary;
import io.xdag.api.dto.PagedResult;
import io.xdag.api.dto.TransactionInfo;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.Link;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;

/**
 * Block API Service
 * Provides block-related data access for both CLI and RPC
 */
@Slf4j
public class BlockApiService {

    private final DagKernel dagKernel;
    private final TransactionApiService transactionApiService;
    private static final String MAIN_STATE = "Main";

    public BlockApiService(DagKernel dagKernel, TransactionApiService transactionApiService) {
        this.dagKernel = dagKernel;
        this.transactionApiService = transactionApiService;
    }

    /**
     * Get block detail by hash
     *
     * @param blockHash Block hash
     * @return Block detail information or null if not found
     */
    public BlockDetail getBlockDetail(Bytes32 blockHash) {
        try {
            Block block = dagKernel.getDagChain().getBlockByHash(blockHash, true);
            if (block == null) {
                return null;
            }

            return buildBlockDetail(block);

        } catch (Exception e) {
            log.error("Failed to get block detail: {}", blockHash.toHexString(), e);
            return null;
        }
    }

    /**
     * Get main chain blocks
     *
     * @param count Number of blocks to retrieve
     * @return List of block summaries
     */
    public List<BlockSummary> getMainBlocks(int count) {
        try {
            List<Block> blocks = dagKernel.getDagChain().listMainBlocks(count);
            if (CollectionUtils.isEmpty(blocks)) {
                return new ArrayList<>();
            }

            List<BlockSummary> result = new ArrayList<>();
            for (Block block : blocks) {
                result.add(buildBlockSummary(block));
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to get main blocks", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get paginated main blocks (newest first).
     *
     * @param page page number (1-based)
     * @param size page size
     * @return paged block summaries with total count
     */
    public PagedResult<BlockSummary> getMainBlocksPage(int page, int size) {
        long totalBlocks = dagKernel.getDagChain().getMainChainLength();
        if (totalBlocks <= 0) {
            return PagedResult.empty();
        }

        long safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        long offset = (safePage - 1L) * safeSize;

        if (offset >= totalBlocks) {
            return PagedResult.of(Collections.emptyList(), totalBlocks);
        }

        long startHeight = totalBlocks - offset;
        long endHeight = Math.max(1, startHeight - safeSize + 1);

        List<BlockSummary> summaries = new ArrayList<>();
        for (long height = startHeight; height >= endHeight; height--) {
            Block block = dagKernel.getDagChain().getMainBlockByHeight(height);
            if (block == null) {
                continue;
            }
            BlockSummary summary = buildBlockSummary(block);
            if (summary != null) {
                summaries.add(summary);
            }
        }

        return PagedResult.of(summaries, totalBlocks);
    }

    /**
     * Get mined blocks (using coinbase filtering)
     *
     * @param count Number of blocks to retrieve
     * @param walletAddresses List of wallet addresses to filter by
     * @return List of block summaries
     */
    public List<BlockSummary> getMinedBlocks(int count, List<Bytes> walletAddresses) {
        try {
            if (walletAddresses == null || walletAddresses.isEmpty()) {
                return new ArrayList<>();
            }

            // Get more blocks to ensure we find enough mined blocks
            List<Block> allMainBlocks = dagKernel.getDagChain().listMainBlocks(Math.max(count * 10, 1000));
            List<BlockSummary> minedBlocks = new ArrayList<>();

            for (Block block : allMainBlocks) {
                if (block.getHeader() != null && block.getHeader().getCoinbase() != null) {
                    Bytes coinbase = block.getHeader().getCoinbase();

                    // Check if coinbase matches any wallet address
                    for (Bytes walletAddr : walletAddresses) {
                        if (coinbase.equals(walletAddr)) {
                            minedBlocks.add(buildBlockSummary(block));
                            if (minedBlocks.size() >= count) {
                                return minedBlocks;
                            }
                            break;
                        }
                    }
                }
            }

            return minedBlocks;

        } catch (Exception e) {
            log.error("Failed to get mined blocks", e);
            return new ArrayList<>();
        }
    }

    /**
     * Get wallet addresses for mining filter
     *
     * @return List of wallet addresses
     */
    public List<Bytes> getWalletAddresses() {
        List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();
        List<Bytes> addresses = new ArrayList<>();
        for (ECKeyPair account : accounts) {
            addresses.add(Bytes.wrap(toBytesAddress(account)));
        }
        return addresses;
    }

    /**
     * Get all blocks in a specific epoch (including main block and orphan blocks).
     * This is useful for consensus verification and debugging epoch competition.
     *
     * @param epoch Epoch number to query
     * @return List of all blocks in the epoch, sorted by hash (winner first)
     */
    public List<BlockSummary> getBlocksByEpoch(long epoch) {
        try {
            List<Block> blocks = dagKernel.getDagStore().getCandidateBlocksInEpoch(epoch);
            if (CollectionUtils.isEmpty(blocks)) {
                return new ArrayList<>();
            }

            // Sort by hash to show winner first (smallest hash wins)
            blocks.sort((b1, b2) -> b1.getHash().compareTo(b2.getHash()));

            List<BlockSummary> result = new ArrayList<>();
            for (Block block : blocks) {
                BlockSummary summary = buildBlockSummary(block);
                if (summary != null) {
                    result.add(summary);
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to get blocks by epoch: {}", epoch, e);
            return new ArrayList<>();
        }
    }

    /**
     * Get all blocks in an epoch range (including main blocks and orphan blocks).
     * This is useful for analyzing consensus behavior across multiple epochs.
     *
     * @param fromEpoch Start epoch (inclusive)
     * @param toEpoch End epoch (inclusive)
     * @return List of all blocks grouped by epoch
     */
    public List<EpochBlocks> getBlocksByEpochRange(long fromEpoch, long toEpoch) {
        try {
            // Validate range
            if (fromEpoch > toEpoch) {
                log.warn("Invalid epoch range: fromEpoch={} > toEpoch={}", fromEpoch, toEpoch);
                return new ArrayList<>();
            }

            // Limit range size to prevent memory issues
            long rangeSize = toEpoch - fromEpoch + 1;
            if (rangeSize > 1000) {
                log.warn("Epoch range too large: {}, limiting to 1000", rangeSize);
                toEpoch = fromEpoch + 999;
            }

            List<EpochBlocks> result = new ArrayList<>();

            for (long epoch = fromEpoch; epoch <= toEpoch; epoch++) {
                List<BlockSummary> epochBlocks = getBlocksByEpoch(epoch);

                if (!epochBlocks.isEmpty()) {
                    result.add(EpochBlocks.builder()
                            .epoch(epoch)
                            .blockCount(epochBlocks.size())
                            .blocks(epochBlocks)
                            .build());
                }
            }

            return result;

        } catch (Exception e) {
            log.error("Failed to get blocks by epoch range: [{}, {}]", fromEpoch, toEpoch, e);
            return new ArrayList<>();
        }
    }

    /**
     * DTO for grouping blocks by epoch
     */
    @lombok.Builder
    @lombok.Data
    public static class EpochBlocks {
        private long epoch;
        private int blockCount;
        private List<BlockSummary> blocks;
    }

    /**
     * Build BlockSummary from Block object
     */
    private BlockSummary buildBlockSummary(Block block) {
        BlockInfo info = block.getInfo();

        if (info == null) {
            log.warn("Block info not available for block: {}", block.getHash().toHexString());
            return null;
        }

        long timestamp = XdagTime.epochNumberToTimeMillis(block.getEpoch());
        String state = info.isMainBlock() ? MAIN_STATE : "Orphan";

        // Get transaction count
        int txCount = dagKernel.getTransactionStore().getTransactionsByBlock(block.getHash()).size();

        // Get coinbase address
        String coinbase = block.getHeader() != null && block.getHeader().getCoinbase() != null
                ? address2PubAddress(block.getHeader().getCoinbase())
                : "N/A";

        return BlockSummary.builder()
                .hash(block.getHash().toHexString())
                .height(info.getHeight())
                .timestamp(timestamp)
                .epoch(info.getEpoch())
                .difficulty(info.getDifficulty())
                .transactionCount(txCount)
                .state(state)
                .coinbase(coinbase)
                .build();
    }

    /**
     * Build BlockDetail from Block object
     */
    private BlockDetail buildBlockDetail(Block block) {
        BlockInfo info = block.getInfo();

        if (info == null) {
            log.warn("Block info not available for block: {}", block.getHash().toHexString());
            return null;
        }

        long timestamp = XdagTime.epochNumberToTimeMillis(block.getEpoch());
        String state = info.isMainBlock() ? MAIN_STATE : "Orphan";

        // Get coinbase address
        String coinbase = block.getHeader() != null && block.getHeader().getCoinbase() != null
                ? address2PubAddress(block.getHeader().getCoinbase())
                : "N/A";

        // Process block links
        List<BlockDetail.LinkInfo> blockLinks = new ArrayList<>();
        List<String> transactionHashes = new ArrayList<>();

        List<Link> links = block.getLinks();
        for (Link link : links) {
            if (link.isBlock()) {
                Block linkedBlock = dagKernel.getDagChain().getBlockByHash(link.getTargetHash(), false);
                if (linkedBlock != null && linkedBlock.getInfo() != null) {
                    blockLinks.add(BlockDetail.LinkInfo.builder()
                            .hash(Hex.toHexString(link.getTargetHash().toArray()))
                            .height(linkedBlock.getInfo().getHeight())
                            .epoch(linkedBlock.getInfo().getEpoch())
                            .build());
                } else {
                    blockLinks.add(BlockDetail.LinkInfo.builder()
                            .hash(Hex.toHexString(link.getTargetHash().toArray()))
                            .build());
                }
            } else if (link.isTransaction()) {
                transactionHashes.add(link.getTargetHash().toHexString());
            }
        }

        // Get transactions
        List<TransactionInfo> transactions = transactionApiService.getTransactionsByBlock(block.getHash());

        // Calculate totals
        XAmount totalAmount = XAmount.ZERO;
        XAmount totalFees = XAmount.ZERO;
        for (TransactionInfo tx : transactions) {
            totalAmount = totalAmount.add(tx.getAmount());
            totalFees = totalFees.add(tx.getFee());
        }

        return BlockDetail.builder()
                .hash(block.getHash().toHexString())
                .height(info.isMainBlock() ? info.getHeight() : null)
                .timestamp(timestamp)
                .epoch(info.getEpoch())
                .difficulty(info.getDifficulty())
                .state(state)
                .coinbase(coinbase)
                .blockLinks(blockLinks)
                .transactionHashes(transactionHashes)
                .transactions(transactions)
                .totalAmount(totalAmount)
                .totalFees(totalFees)
                .build();
    }
}
