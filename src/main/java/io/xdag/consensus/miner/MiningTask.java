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

package io.xdag.consensus.miner;

import io.xdag.core.Block;
import io.xdag.crypto.hash.XdagSha256Digest;
import lombok.Getter;
import org.apache.tuweni.bytes.Bytes32;

/**
 * MiningTask - Represents a mining task
 *
 * <p>Immutable data structure containing all information needed for mining:
 * <ul>
 *   <li>Candidate block to mine</li>
 *   <li>Pre-hash for PoW calculation</li>
 *   <li>Task timestamp and index</li>
 *   <li>RandomX seed (if applicable)</li>
 *   <li>SHA256 digest state (for non-RandomX)</li>
 * </ul>
 *
 * @since v5.1 Phase 12.4
 */
@Getter
public class MiningTask {

    /**
     * Candidate block to mine
     */
    private final Block candidateBlock;

    /**
     * Pre-hash for PoW calculation
     * - RandomX: block.getRandomXPreHash()
     * - SHA256: SHA256 digest state
     */
    private final Bytes32 preHash;

    /**
     * Task timestamp (epoch)
     */
    private final long timestamp;

    /**
     * Task index (unique identifier)
     */
    private final long taskIndex;

    /**
     * RandomX seed (null for non-RandomX)
     */
    private final byte[] randomXSeed;

    /**
     * SHA256 digest state (null for RandomX)
     */
    private final XdagSha256Digest sha256Digest;

    /**
     * Is this a RandomX task
     */
    private final boolean isRandomX;

    /**
     * Create a RandomX mining task
     *
     * @param candidateBlock Candidate block
     * @param preHash RandomX pre-hash
     * @param timestamp Task timestamp (epoch)
     * @param taskIndex Task index
     * @param randomXSeed RandomX seed
     */
    public MiningTask(Block candidateBlock, Bytes32 preHash, long timestamp, long taskIndex, byte[] randomXSeed) {
        this.candidateBlock = candidateBlock;
        this.preHash = preHash;
        this.timestamp = timestamp;
        this.taskIndex = taskIndex;
        this.randomXSeed = randomXSeed;
        this.sha256Digest = null;
        this.isRandomX = true;
    }

    /**
     * Create a SHA256 mining task
     *
     * @param candidateBlock Candidate block
     * @param preHash SHA256 digest state
     * @param timestamp Task timestamp (epoch)
     * @param taskIndex Task index
     * @param sha256Digest SHA256 digest
     */
    public MiningTask(Block candidateBlock, Bytes32 preHash, long timestamp, long taskIndex, XdagSha256Digest sha256Digest) {
        this.candidateBlock = candidateBlock;
        this.preHash = preHash;
        this.timestamp = timestamp;
        this.taskIndex = taskIndex;
        this.randomXSeed = null;
        this.sha256Digest = sha256Digest;
        this.isRandomX = false;
    }

    @Override
    public String toString() {
        return "MiningTask{" +
                "taskIndex=" + taskIndex +
                ", timestamp=" + timestamp +
                ", isRandomX=" + isRandomX +
                ", blockHash=" + candidateBlock.getHash().toHexString() +
                ", preHash=" + preHash.toHexString() +
                '}';
    }
}
