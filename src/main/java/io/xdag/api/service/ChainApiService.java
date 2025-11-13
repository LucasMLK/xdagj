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

import io.xdag.DagKernel;
import io.xdag.api.dto.ChainStatsInfo;
import io.xdag.api.dto.EpochInfo;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.bouncycastle.util.encoders.Hex;

/**
 * Chain API Service
 * Provides blockchain and consensus-related data access for both CLI and RPC
 */
@Slf4j
public class ChainApiService {

    private final DagKernel dagKernel;
    private final AccountApiService accountApiService;

    public ChainApiService(DagKernel dagKernel, AccountApiService accountApiService) {
        this.dagKernel = dagKernel;
        this.accountApiService = accountApiService;
    }

    /**
     * Get chain statistics
     *
     * @return Chain statistics information
     */
    public ChainStatsInfo getChainStats() {
        try {
            ChainStats chainStats = dagKernel.getDagChain().getChainStats();

            // Get top block height
            Long topBlockHeight = null;
            String topBlockHash = null;
            if (chainStats.getTopBlock() != null) {
                Block topBlock = dagKernel.getDagChain().getBlockByHash(chainStats.getTopBlock(), false);
                if (topBlock != null && topBlock.getInfo() != null) {
                    topBlockHeight = topBlock.getInfo().getHeight();
                }
                topBlockHash = chainStats.getTopBlock().toHexString().substring(0, 16) + "...";
            }

            // Get wallet statistics
            XAmount totalWalletBalance = accountApiService.getTotalBalance();
            int accountCount = dagKernel.getWallet().getAccounts().size();

            // Get P2P statistics (placeholder)
            int connectedPeers = 0;
            if (dagKernel.getP2pService() != null) {
                // TODO: Implement P2pService.getActiveChannelCount()
                connectedPeers = 0;
            }

            return ChainStatsInfo.builder()
                    .mainBlockCount(chainStats.getMainBlockCount())
                    .totalBlockCount(chainStats.getTotalBlockCount())
                    .topBlockHeight(topBlockHeight)
                    .topBlockHash(topBlockHash)
                    .currentEpoch(dagKernel.getDagChain().getCurrentEpoch())
                    .currentDifficulty(chainStats.getTopDifficulty() != null ?
                            chainStats.getTopDifficulty() : UInt256.ZERO)
                    .maxDifficulty(chainStats.getMaxDifficulty())
                    .orphanCount(chainStats.getNoRefCount())
                    .waitingSyncCount(chainStats.getWaitingSyncCount())
                    .syncProgress(chainStats.getSyncProgress())
                    .connectedPeers(connectedPeers)
                    .totalHosts(chainStats.getTotalHostCount())
                    .totalWalletBalance(totalWalletBalance)
                    .accountCount(accountCount)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get chain stats", e);
            return null;
        }
    }

    /**
     * Get current epoch number
     *
     * @return Current epoch number
     */
    public long getCurrentEpoch() {
        return dagKernel.getDagChain().getCurrentEpoch();
    }

    /**
     * Get epoch information
     *
     * @param epochNumber Epoch number (null for current epoch)
     * @return Epoch information or null if failed
     */
    public EpochInfo getEpochInfo(Long epochNumber) {
        try {
            // Get epoch number
            long epoch = epochNumber != null ? epochNumber : dagKernel.getDagChain().getCurrentEpoch();
            long currentEpoch = dagKernel.getDagChain().getCurrentEpoch();
            boolean isCurrent = (epoch == currentEpoch);

            // Get epoch time range
            long[] timeRange = dagKernel.getDagChain().getEpochTimeRange(epoch);
            long startTime = timeRange[0];
            long endTime = timeRange[1];
            long duration = endTime - startTime;

            // Calculate progress for current epoch
            Long elapsed = null;
            Double progress = null;
            Long timeToNext = null;
            if (isCurrent) {
                long currentTime = XdagTime.getCurrentTimestamp();
                elapsed = currentTime - startTime;
                progress = (double) elapsed / duration * 100.0;
                timeToNext = duration - elapsed;
            }

            // Get candidate blocks
            List<Block> candidates = dagKernel.getDagChain().getCandidateBlocksInEpoch(epoch);

            // Find winner and orphans
            Block winner = null;
            List<Block> orphans = new ArrayList<>();
            UInt256 minHash = UInt256.MAX_VALUE;

            for (Block block : candidates) {
                UInt256 blockHash = UInt256.fromBytes(block.getHash());
                if (blockHash.compareTo(minHash) < 0) {
                    if (winner != null) {
                        orphans.add(winner);
                    }
                    winner = block;
                    minHash = blockHash;
                } else {
                    orphans.add(block);
                }
            }

            // Calculate average and total difficulty
            UInt256 totalDifficulty = UInt256.ZERO;
            for (Block block : candidates) {
                totalDifficulty = totalDifficulty.add(block.getInfo().getDifficulty());
            }
            UInt256 avgDifficulty = candidates.isEmpty() ? UInt256.ZERO :
                    totalDifficulty.divide(candidates.size());

            // Build result
            EpochInfo.EpochInfoBuilder builder = EpochInfo.builder()
                    .epochNumber(epoch)
                    .isCurrent(isCurrent)
                    .startTime(startTime)
                    .endTime(endTime)
                    .duration(duration)
                    .elapsed(elapsed)
                    .progress(progress)
                    .timeToNext(timeToNext)
                    .candidateCount(candidates.size())
                    .orphanCount(orphans.size())
                    .averageDifficulty(avgDifficulty)
                    .totalDifficulty(totalDifficulty);

            if (winner != null) {
                builder.winnerHash(Hex.toHexString(winner.getHash().toArray()).substring(0, 16) + "...");
                builder.winnerHeight(winner.getInfo().getHeight());
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Failed to get epoch info for epoch: {}", epochNumber, e);
            return null;
        }
    }
}
