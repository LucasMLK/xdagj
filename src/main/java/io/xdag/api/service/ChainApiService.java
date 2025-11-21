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
import io.xdag.api.service.dto.ChainStatsInfo;
import io.xdag.api.service.dto.EpochInfo;
import io.xdag.api.service.dto.NodeStatusInfo;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.DagChainImpl;
import io.xdag.core.XAmount;
import io.xdag.utils.XdagTime;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
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
                topBlockHash = chainStats.getTopBlock().toHexString();
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
                long currentTime = XdagTime.getCurrentEpoch();
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
                builder.winnerHash(Hex.toHexString(winner.getHash().toArray()));
                builder.winnerHeight(winner.getInfo().getHeight());
            }

            return builder.build();

        } catch (Exception e) {
            log.error("Failed to get epoch info for epoch: {}", epochNumber, e);
            return null;
        }
    }

    /**
     * Get node synchronization status
     * <p>
     * Returns detailed information about node sync state, epoch gaps, and mining restrictions.
     * Used for monitoring node health and detecting network partition scenarios.
     *
     * @return Node status information or null if failed
     * @since XDAGJ 1.0
     */
    public NodeStatusInfo getNodeStatus() {
        try {
            // Check if DagChain supports partition handling
            if (!(dagKernel.getDagChain() instanceof DagChainImpl)) {
                log.warn("DagChain does not support partition handling (not DagChainImpl instance)");
                return NodeStatusInfo.builder()
                        .syncState("unknown")
                        .isBehind(false)
                        .miningStatus("unknown")
                        .canMine(false)
                        .message("Node status not available (DagChain implementation does not support isNodeBehind())")
                        .warningLevel("info")
                        .build();
            }

            DagChainImpl dagChain = (DagChainImpl) dagKernel.getDagChain();
            ChainStats chainStats = dagChain.getChainStats();

            // Get current epoch
            long currentEpoch = dagChain.getCurrentEpoch();

            // Get local latest main block
            long mainChainLength = chainStats.getMainBlockCount();
            Long localLatestEpoch = null;
            String latestBlockHash = null;
            Long latestBlockHeight = null;

            if (mainChainLength > 0) {
                Block latestBlock = dagChain.getMainBlockByHeight(mainChainLength);
                if (latestBlock != null) {
                    localLatestEpoch = latestBlock.getEpoch();
                    latestBlockHash = latestBlock.getHash().toHexString();
                    latestBlockHeight = latestBlock.getInfo() != null ?
                            latestBlock.getInfo().getHeight() : null;
                }
            }

            // Calculate epoch gap
            long epochGap = localLatestEpoch != null ? (currentEpoch - localLatestEpoch) : currentEpoch;
            long timeLagMinutes = epochGap * 64 / 60;  // 1 epoch = 64 seconds

            // Determine sync state
            boolean isBehind = dagChain.isNodeBehind();
            String syncState = isBehind ? "behind" : "up-to-date";

            // Determine mining status
            long miningMaxReferenceDepth = 16;  // From DagChainImpl.MINING_MAX_REFERENCE_DEPTH
            boolean canMine = epochGap <= miningMaxReferenceDepth;
            String miningStatus = canMine ? "allowed" : "blocked";

            // Determine warning level
            String warningLevel;
            String message;

            if (epochGap == 0) {
                warningLevel = "none";
                message = "Node is fully synced and can mine";
            } else if (epochGap <= 100) {
                warningLevel = "none";
                message = String.format("Node is up-to-date (lag: %d epochs, ~%d minutes)",
                        epochGap, timeLagMinutes);
            } else if (epochGap <= 1000) {
                warningLevel = "info";
                message = String.format("Node is behind and must sync before mining (lag: %d epochs, ~%.1f hours)",
                        epochGap, epochGap * 64 / 3600.0);
            } else if (epochGap <= 16384) {
                warningLevel = "warning";
                message = String.format("WARNING: Large epoch gap detected (lag: %d epochs, ~%.1f hours). " +
                        "This may indicate a network partition or extended offline period. " +
                        "Sync required before mining.",
                        epochGap, epochGap * 64 / 3600.0);
            } else {
                warningLevel = "critical";
                message = String.format("CRITICAL: Epoch gap exceeds XDAG protocol limit (lag: %d epochs, ~%.1f days). " +
                        "Node is outside 12-day time window. Manual intervention may be required.",
                        epochGap, epochGap * 64 / 86400.0);
            }

            return NodeStatusInfo.builder()
                    .syncState(syncState)
                    .isBehind(isBehind)
                    .currentEpoch(currentEpoch)
                    .localLatestEpoch(localLatestEpoch != null ? localLatestEpoch : 0)
                    .epochGap(epochGap)
                    .syncLagThreshold(100)  // From DagChainImpl.SYNC_LAG_THRESHOLD
                    .timeLagMinutes(timeLagMinutes)
                    .miningStatus(miningStatus)
                    .canMine(canMine)
                    .miningMaxReferenceDepth(miningMaxReferenceDepth)
                    .mainChainLength(mainChainLength)
                    .latestBlockHash(latestBlockHash)
                    .latestBlockHeight(latestBlockHeight)
                    .message(message)
                    .warningLevel(warningLevel)
                    .build();

        } catch (Exception e) {
            log.error("Failed to get node status", e);
            return null;
        }
    }
}
