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

import io.xdag.core.Block;
import io.xdag.utils.XdagTime;
import lombok.Getter;

/**
 * Hash Calculation Context
 *
 * <p>Encapsulates all parameters needed for PoW hash calculation.
 * Provides a clean, type-safe way to pass calculation parameters to PoW algorithms.
 *
 * <h2>Why HashContext?</h2>
 * <ul>
 *   <li><strong>Type Safety</strong>: Prevents parameter ordering errors</li>
 *   <li><strong>Extensibility</strong>: Easy to add new parameters without breaking API</li>
 *   <li><strong>Clarity</strong>: Self-documenting code with named fields</li>
 *   <li><strong>Immutability</strong>: Thread-safe value object</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // For block validation
 * HashContext context = HashContext.forBlock(block);
 * byte[] hash = powAlgorithm.calculateBlockHash(data, context);
 *
 * // For mining
 * HashContext context = HashContext.forMining(timestamp);
 * Bytes32 hash = powAlgorithm.calculatePoolHash(data, context);
 * }</pre>
 *
 * @since XDAGJ v0.8.1
 */
@Getter
public final class HashContext {

  /**
   * Timestamp for hash calculation (XDAG epoch time)
   */
  private final long timestamp;

  /**
   * Block height (optional, -1 if not available)
   */
  private final long blockHeight;

  /**
   * Epoch number derived from timestamp
   */
  private final long epoch;

  // ========== Constructor ==========

  /**
   * Creates a new hash context.
   *
   * @param timestamp   Block or task timestamp
   * @param blockHeight Block height, or -1 if not applicable
   * @param epoch       Epoch number
   */
  private HashContext(long timestamp, long blockHeight, long epoch) {
    this.timestamp = timestamp;
    this.blockHeight = blockHeight;
    this.epoch = epoch;
  }

  // ========== Factory Methods ==========

  /**
   * Creates context for block hash calculation.
   *
   * <p>Used when validating or creating blocks. Includes complete
   * block information for seed selection and validation.
   *
   * @param block Block to create context from
   * @return Hash context for block validation
   * @throws IllegalArgumentException if block or info is null
   */
  public static HashContext forBlock(Block block) {
    if (block == null || block.getInfo() == null) {
      throw new IllegalArgumentException("Block and block info cannot be null");
    }

    long timestamp = XdagTime.epochNumberToMainTime(block.getEpoch());
    long height = block.getInfo().getHeight();
    long epoch = block.getEpoch();

    return new HashContext(timestamp, height, epoch);
  }

  /**
   * Creates context for mining pool hash calculation.
   *
   * <p>Used when calculating hashes for mining tasks. Block height
   * is not available during mining, so it's set to -1.
   *
   * @param timestamp Mining task timestamp
   * @return Hash context for pool mining
   */
  public static HashContext forMining(long timestamp) {
    long epoch = XdagTime.getEpochNumber(timestamp);
    return new HashContext(timestamp, -1, epoch);
  }

  /**
   * Creates context from timestamp and height.
   *
   * <p>General-purpose factory method for custom use cases.
   *
   * @param timestamp   Timestamp
   * @param blockHeight Block height (or -1)
   * @return Hash context
   */
  public static HashContext of(long timestamp, long blockHeight) {
    long epoch = XdagTime.getEpochNumber(timestamp);
    return new HashContext(timestamp, blockHeight, epoch);
  }

  // ========== Utility Methods ==========

  @Override
  public String toString() {
    return String.format("HashContext[timestamp=%d, height=%d, epoch=%d]",
        timestamp, blockHeight, epoch);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    HashContext that = (HashContext) o;
    return timestamp == that.timestamp
        && blockHeight == that.blockHeight
        && epoch == that.epoch;
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(timestamp);
    result = 31 * result + Long.hashCode(blockHeight);
    result = 31 * result + Long.hashCode(epoch);
    return result;
  }
}
