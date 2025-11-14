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
 * MiningTask - Pool-specific mining task data (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class is a <strong>pool-specific data structure</strong> that is deprecated and will be
 * removed in version <strong>0.9.0</strong>. External pools receive candidate blocks directly
 * from the RPC interface and don't need this wrapper.
 *
 * <h2>Why Deprecated?</h2>
 * <p>This data structure is only used by the internal pool (MiningManager):
 * <ul>
 *   <li>❌ Only used by deprecated MiningManager and LocalMiner</li>
 *   <li>❌ External pools receive Block directly from RPC</li>
 *   <li>❌ Wraps information already available in Block</li>
 *   <li>❌ Pool-specific tracking (taskIndex) not relevant to nodes</li>
 * </ul>
 *
 * <h2>Migration Path</h2>
 * <p><strong>OLD (Deprecated)</strong>:
 * <pre>
 * // Internal pool creates MiningTask wrapper
 * MiningTask task = new MiningTask(candidate, preHash, timestamp, taskIdx, seed);
 * shareValidator.validateShare(nonce, task);
 * </pre>
 *
 * <p><strong>NEW (Recommended)</strong>:
 * <pre>
 * // External pool works directly with Block
 * // In xdagj-pool project:
 * Block candidate = nodeClient.getCandidateBlock("pool-1");
 *
 * // Distribute to miners via Stratum protocol
 * stratumServer.sendMiningJob(candidate);
 *
 * // When miner finds nonce:
 * Block minedBlock = candidate.withNonce(foundNonce);
 * nodeClient.submitMinedBlock(minedBlock, "pool-1");
 * </pre>
 *
 * <h2>Temporary Usage (Testing Only)</h2>
 * <p>For development and testing, you can still use MiningTask, but be aware:
 * <ul>
 *   <li>⚠️ Not recommended for production</li>
 *   <li>⚠️ Will be removed in v0.9.0</li>
 *   <li>⚠️ No new features will be added</li>
 *   <li>⚠️ Bugs may not be fixed</li>
 * </ul>
 *
 * <h2>What This Class Does</h2>
 * <ul>
 *   <li>Wraps candidate block with mining metadata</li>
 *   <li>Stores pre-hash for PoW calculation</li>
 *   <li>Tracks task index and timestamp</li>
 *   <li>Distinguishes RandomX vs SHA256 tasks</li>
 * </ul>
 *
 * <h2>Timeline</h2>
 * <ul>
 *   <li><strong>v0.8.2</strong>: Marked as @Deprecated (current)</li>
 *   <li><strong>v0.8.3</strong>: xdagj-pool uses Block directly</li>
 *   <li><strong>v0.9.0</strong>: MiningTask removed (breaking change)</li>
 * </ul>
 *
 * @since XDAGJ v5.1
 * @deprecated Since v0.8.2, scheduled for removal in v0.9.0.
 *             External pools should work directly with {@link Block} from
 *             {@link io.xdag.rpc.service.MiningRpcServiceImpl#getCandidateBlock}.
 * @see io.xdag.core.Block
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 */
@Deprecated(since = "0.8.2", forRemoval = true)
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
