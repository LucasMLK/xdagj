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

import org.apache.tuweni.bytes.Bytes32;

/**
 * Proof of Work Algorithm Interface
 *
 * <p>Defines the contract for all PoW algorithms used in XDAG blockchain.
 * Implementations must be thread-safe and support concurrent hash calculations.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 * 1. Constructor injection of dependencies
 * 2. start() - Initialize algorithm and register event listeners
 * 3. [Mining operations - calculateHash calls]
 * 4. stop() - Cleanup resources and unregister listeners
 * </pre>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link RandomXPow} - RandomX algorithm (XDAG standard)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>All methods must be thread-safe. Multiple threads may call
 * {@link #calculateBlockHash} and {@link #calculatePoolHash} concurrently.
 *
 * @since XDAGJ v0.8.1
 */
public interface PowAlgorithm {

    /**
     * Calculate proof-of-work hash for block validation.
     *
     * <p>Used by the blockchain to validate blocks and verify PoW.
     * This method must be deterministic and thread-safe.
     *
     * @param data Block data to hash
     * @param context Hash calculation context (timestamp, height, etc.)
     * @return 32-byte hash result, or null if algorithm not ready
     * @throws IllegalArgumentException if data or context is null
     */
    byte[] calculateBlockHash(byte[] data, HashContext context);

    /**
     * Calculate hash for mining pool share validation.
     *
     * <p>Used by mining pools to validate shares submitted by miners.
     * Pool mining may use different parameters than block validation.
     *
     * @param data Mining task data
     * @param context Hash calculation context
     * @return 32-byte hash result, or null if algorithm not ready
     * @throws IllegalArgumentException if data or context is null
     */
    Bytes32 calculatePoolHash(byte[] data, HashContext context);

    /**
     * Check if this PoW algorithm is active for the given epoch.
     *
     * <p>For RandomX-only implementations, this typically returns true always.
     *
     * @param epoch Epoch number to check
     * @return true if this algorithm should be used for the epoch
     */
    boolean isActive(long epoch);

    /**
     * Start the PoW algorithm.
     *
     * <p>Initializes resources, prepares internal state, and registers
     * event listeners if needed. Must be called before hash calculations.
     *
     * @throws IllegalStateException if already started or dependencies not set
     */
    void start();

    /**
     * Stop the PoW algorithm.
     *
     * <p>Cleans up resources and unregisters event listeners.
     * After stop(), the algorithm cannot be restarted.
     */
    void stop();

    /**
     * Check if the PoW algorithm is ready for hash calculations.
     *
     * <p>An algorithm may not be ready if:
     * <ul>
     *   <li>Not yet started</li>
     *   <li>Waiting for seed initialization (RandomX)</li>
     *   <li>In transition between epochs</li>
     * </ul>
     *
     * @return true if ready to calculate hashes, false otherwise
     */
    boolean isReady();

    /**
     * Get the algorithm name for logging and debugging.
     *
     * @return Algorithm name (e.g., "RandomX")
     */
    String getName();
}
