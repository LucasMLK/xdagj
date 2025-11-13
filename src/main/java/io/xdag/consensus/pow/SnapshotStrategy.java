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

package io.xdag.consensus.pow;

/**
 * RandomX Snapshot Loading Strategy
 *
 * <p>Defines different strategies for initializing RandomX state from snapshots.
 * Choosing the right strategy depends on the snapshot age and chain state.
 *
 * <h2>Strategy Selection</h2>
 * <pre>
 * Snapshot Age vs Current Chain:
 *
 * Case 1: Snapshot recent (same epoch)
 *   → Use WITH_PRESEED for fast startup
 *
 * Case 2: Snapshot old (past epoch)
 *   → Use FROM_CURRENT_STATE to reconstruct
 *
 * Case 3: No snapshot
 *   → Use FROM_FORK_HEIGHT to build from scratch
 *
 * Case 4: Uncertain
 *   → Use AUTO to let system decide
 * </pre>
 *
 * <h2>Performance Comparison</h2>
 * <table border="1">
 *   <tr>
 *     <th>Strategy</th>
 *     <th>Startup Time</th>
 *     <th>Accuracy</th>
 *     <th>Use Case</th>
 *   </tr>
 *   <tr>
 *     <td>WITH_PRESEED</td>
 *     <td>Fast (seconds)</td>
 *     <td>High</td>
 *     <td>Recent snapshot</td>
 *   </tr>
 *   <tr>
 *     <td>FROM_CURRENT_STATE</td>
 *     <td>Medium (minutes)</td>
 *     <td>Highest</td>
 *     <td>Old snapshot</td>
 *   </tr>
 *   <tr>
 *     <td>FROM_FORK_HEIGHT</td>
 *     <td>Slow (hours)</td>
 *     <td>Highest</td>
 *     <td>No snapshot</td>
 *   </tr>
 *   <tr>
 *     <td>AUTO</td>
 *     <td>Varies</td>
 *     <td>High</td>
 *     <td>Default choice</td>
 *   </tr>
 * </table>
 *
 * @since XDAGJ v0.8.1
 */
public enum SnapshotStrategy {

    /**
     * Load using pre-computed seed from snapshot file.
     *
     * <p><strong>When to use:</strong> Snapshot is recent (within current epoch)
     *
     * <p><strong>Pros:</strong>
     * <ul>
     *   <li>Fastest startup time</li>
     *   <li>Minimal computation</li>
     * </ul>
     *
     * <p><strong>Cons:</strong>
     * <ul>
     *   <li>Requires valid preseed</li>
     *   <li>May be incorrect if chain progressed past epoch</li>
     * </ul>
     *
     * <p><strong>Process:</strong>
     * <pre>
     * 1. Load preseed from snapshot
     * 2. Initialize seed manager with preseed
     * 3. Replay blocks from snapshot to current
     * </pre>
     */
    WITH_PRESEED,

    /**
     * Reconstruct state from current blockchain height.
     *
     * <p><strong>When to use:</strong> Snapshot is old (past epoch boundaries)
     *
     * <p><strong>Pros:</strong>
     * <ul>
     *   <li>Accurate for current chain state</li>
     *   <li>No preseed required</li>
     * </ul>
     *
     * <p><strong>Cons:</strong>
     * <ul>
     *   <li>Slower than preseed (needs epoch calculation)</li>
     *   <li>Requires chain state available</li>
     * </ul>
     *
     * <p><strong>Process:</strong>
     * <pre>
     * 1. Find current and previous epoch boundaries
     * 2. Derive seeds from historical blocks
     * 3. Initialize current epoch seed
     * </pre>
     */
    FROM_CURRENT_STATE,

    /**
     * Initialize from RandomX fork activation block.
     *
     * <p><strong>When to use:</strong> No snapshot available (full sync)
     *
     * <p><strong>Pros:</strong>
     * <ul>
     *   <li>Most accurate (replays all history)</li>
     *   <li>No preseed required</li>
     * </ul>
     *
     * <p><strong>Cons:</strong>
     * <ul>
     *   <li>Slowest startup (replays all blocks since fork)</li>
     *   <li>High computation cost</li>
     * </ul>
     *
     * <p><strong>Process:</strong>
     * <pre>
     * 1. Start from fork activation height
     * 2. Replay all blocks from fork to current
     * 3. Track all seed epoch transitions
     * </pre>
     */
    FROM_FORK_HEIGHT,

    /**
     * Automatically choose best strategy based on chain state.
     *
     * <p><strong>When to use:</strong> Default choice when uncertain
     *
     * <p><strong>Decision Logic:</strong>
     * <pre>
     * IF (current height &lt; next epoch after snapshot)
     *     → Use WITH_PRESEED
     * ELSE
     *     → Use FROM_CURRENT_STATE
     * </pre>
     *
     * <p><strong>Pros:</strong>
     * <ul>
     *   <li>Balances speed and accuracy</li>
     *   <li>Safe default choice</li>
     * </ul>
     *
     * <p><strong>Cons:</strong>
     * <ul>
     *   <li>Requires chain state inspection</li>
     * </ul>
     */
    AUTO;

    /**
     * Check if this strategy requires a preseed.
     *
     * @return true if preseed is required
     */
    public boolean requiresPreseed() {
        return this == WITH_PRESEED;
    }

    /**
     * Check if this strategy requires blockchain access.
     *
     * @return true if blockchain is needed
     */
    public boolean requiresBlockchain() {
        return this != WITH_PRESEED;
    }
}
