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

package io.xdag.core;

/**
 * DAG Chain Event Listener
 *
 * <p>Interface for components that need to react to DAG chain state changes.
 * Listeners are notified when blocks are connected to or disconnected from
 * the main chain.
 *
 * <h2>Event Order</h2>
 * <p>Events are delivered in the order they occur:
 * <pre>
 * Block Import:
 *   1. Block validation
 *   2. Block added to chain
 *   3. onBlockConnected() → All listeners notified
 *
 * Block Rollback:
 *   1. Chain reorganization detected
 *   2. Block removed from chain
 *   3. onBlockDisconnected() → All listeners notified
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <p>Listener methods may be called from different threads. Implementations
 * must be thread-safe or synchronize access to shared state.
 *
 * <h2>Performance</h2>
 * <p>Listener methods should execute quickly to avoid blocking blockchain
 * operations. Heavy processing should be delegated to background threads.
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>RandomX seed updates on epoch boundaries</li>
 *   <li>Transaction indexing and notification</li>
 *   <li>Statistics and metrics collection</li>
 *   <li>External system synchronization</li>
 * </ul>
 *
 * @since XDAGJ v0.8.1
 */
public interface DagchainListener {

    /**
     * Called when a block is connected to the main chain.
     *
     * <p>This event is triggered after the block has been validated and
     * added to the blockchain. The block is now part of the main chain.
     *
     * <p><strong>Important:</strong> This method should execute quickly.
     * Perform heavy processing asynchronously.
     *
     * @param block The block that was connected
     */
    void onBlockConnected(Block block);

    /**
     * Called when a block is disconnected from the main chain.
     *
     * <p>This event is triggered during chain reorganizations when a block
     * is removed from the main chain. Listeners should revert any state
     * changes made in {@link #onBlockConnected}.
     *
     * <p><strong>Important:</strong> This method should execute quickly.
     * Perform heavy processing asynchronously.
     *
     * @param block The block that was disconnected
     */
    void onBlockDisconnected(Block block);
}
