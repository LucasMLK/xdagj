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

package io.xdag.api.dto;

import lombok.Builder;
import lombok.Data;

/**
 * RandomXInfo - Information about RandomX proof-of-work fork status
 *
 * <p>This class provides information about the RandomX mining algorithm fork.
 * External miners and pool servers use this to determine which hashing algorithm
 * to use (SHA256 pre-fork, RandomX post-fork).
 *
 * <h2>RandomX Fork</h2>
 * <p>XDAG transitioned from SHA256 to RandomX proof-of-work at a specific epoch.
 * This class tells miners:
 * <ul>
 *   <li>Whether the fork has activated</li>
 *   <li>What epoch we're currently at</li>
 *   <li>When the fork activated</li>
 *   <li>Whether RandomX VM is ready</li>
 * </ul>
 *
 * <h2>Algorithm Selection</h2>
 * <pre>
 * if (randomXInfo.isEnabled() && currentEpoch >= randomXInfo.getForkEpoch()) {
 *     // Use RandomX hashing
 *     hash = randomx.calculateHash(blockData, nonce);
 * } else {
 *     // Use SHA256 hashing
 *     hash = sha256(sha256(blockData || nonce));
 * }
 * </pre>
 *
 * @since XDAGJ 1.0
 */
@Data
@Builder
public class RandomXInfo {

    /**
     * Whether RandomX is enabled in node configuration
     */
    private final boolean enabled;

    /**
     * Current epoch number
     */
    private final long currentEpoch;

    /**
     * Epoch at which RandomX fork activates
     * (blocks before this epoch use SHA256, after use RandomX)
     */
    private final long forkEpoch;

    /**
     * Whether RandomX VM is initialized and ready
     * (false during initialization, true when ready to hash)
     */
    private final boolean vmReady;

    /**
     * RandomX seed epoch interval (how often seed changes)
     * Typically 64 or 128 epochs
     */
    private final long seedEpochInterval;

    /**
     * Check if RandomX fork is currently active
     *
     * @return true if current epoch >= fork epoch and RandomX is enabled
     */
    public boolean isForkActive() {
        return enabled && currentEpoch >= forkEpoch;
    }

    /**
     * Get algorithm name based on current state
     *
     * @return "RandomX" if fork active, "SHA256" otherwise
     */
    public String getAlgorithmName() {
        return isForkActive() ? "RandomX" : "SHA256";
    }

    /**
     * Create RandomXInfo for pre-fork state (SHA256 mining)
     *
     * @param currentEpoch Current epoch number
     * @param forkEpoch Epoch when RandomX will activate
     * @return RandomXInfo indicating SHA256 mining
     */
    public static RandomXInfo preFork(long currentEpoch, long forkEpoch) {
        return RandomXInfo.builder()
                .enabled(true)
                .currentEpoch(currentEpoch)
                .forkEpoch(forkEpoch)
                .vmReady(false)
                .seedEpochInterval(64)
                .build();
    }

    /**
     * Create RandomXInfo for post-fork state (RandomX mining)
     *
     * @param currentEpoch Current epoch number
     * @param forkEpoch Epoch when RandomX activated
     * @param vmReady Whether RandomX VM is initialized
     * @return RandomXInfo indicating RandomX mining
     */
    public static RandomXInfo postFork(long currentEpoch, long forkEpoch, boolean vmReady) {
        return RandomXInfo.builder()
                .enabled(true)
                .currentEpoch(currentEpoch)
                .forkEpoch(forkEpoch)
                .vmReady(vmReady)
                .seedEpochInterval(64)
                .build();
    }

    /**
     * Create RandomXInfo when RandomX is disabled (always SHA256)
     *
     * @param currentEpoch Current epoch number
     * @return RandomXInfo indicating SHA256 mining
     */
    public static RandomXInfo disabled(long currentEpoch) {
        return RandomXInfo.builder()
                .enabled(false)
                .currentEpoch(currentEpoch)
                .forkEpoch(Long.MAX_VALUE)
                .vmReady(false)
                .seedEpochInterval(64)
                .build();
    }
}
