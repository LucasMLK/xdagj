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

package io.xdag.api.service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Node synchronization status information DTO
 * <p>
 * Provides detailed information about node sync state, epoch gaps, and mining status.
 * Used for monitoring node health and partition detection.
 *
 * @since XDAGJ 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeStatusInfo {

    /**
     * Node synchronization state
     * <p>
     * Possible values:
     * - "up-to-date": Node is synced and can mine (epoch gap <= 100)
     * - "behind": Node needs to sync before mining (epoch gap > 100)
     * - "unknown": Node status cannot be determined
     */
    private String syncState;

    /**
     * Whether node is behind and needs sync
     */
    private boolean isBehind;

    /**
     * Current epoch number (network time)
     */
    private long currentEpoch;

    /**
     * Local latest main block epoch
     */
    private long localLatestEpoch;

    /**
     * Epoch gap between current time and local chain
     * <p>
     * Positive value: node is behind
     * Zero/Negative: node is up-to-date or ahead (should not happen normally)
     */
    private long epochGap;

    /**
     * Epoch gap threshold for "behind" detection (default: 100 epochs)
     */
    private long syncLagThreshold;

    /**
     * Time lag in minutes (epoch gap * 64 seconds / 60)
     */
    private long timeLagMinutes;

    /**
     * Mining status
     * <p>
     * Possible values:
     * - "allowed": Node can mine (epoch gap <= 16)
     * - "blocked": Mining blocked until sync (epoch gap > 16)
     * - "unknown": Mining status cannot be determined
     */
    private String miningStatus;

    /**
     * Whether mining is currently allowed
     */
    private boolean canMine;

    /**
     * Mining reference depth limit (default: 16 epochs)
     */
    private long miningMaxReferenceDepth;

    /**
     * Main chain length (total main blocks)
     */
    private long mainChainLength;

    /**
     * Latest main block hash (full hex string)
     */
    private String latestBlockHash;

    /**
     * Latest main block height
     */
    private Long latestBlockHeight;

    /**
     * Additional information/warnings
     * <p>
     * Examples:
     * - "Node is behind, sync required before mining"
     * - "Node is up-to-date and can mine"
     * - "WARNING: Large epoch gap may indicate network partition"
     */
    private String message;

    /**
     * Warning level
     * <p>
     * Possible values:
     * - "none": No warnings
     * - "info": Informational (behind but within normal range)
     * - "warning": Warning (epoch gap > 1000, possible partition)
     * - "critical": Critical (epoch gap > 16384, outside XDAG window)
     */
    private String warningLevel;
}
