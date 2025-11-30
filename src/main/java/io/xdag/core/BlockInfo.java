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

import io.xdag.utils.TimeUtils;
import java.io.Serializable;
import lombok.Builder;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * BlockInfo - minimal block metadata DTO.
 * <p>
 * Design principles (see CORE_DATA_STRUCTURES.md):
 * <ol>
 *   <li>Single responsibility – only hold the metadata required for indexing, status checks, and
 *   PoW verification.</li>
 *   <li>Minimal fields – just the four values that cannot be derived anywhere else.</li>
 *   <li>DRY – never persist values that can be recalculated.</li>
 * </ol>
 *
 * <p>The four core fields are:
 * <ul>
 *   <li><strong>hash</strong>: unique block identifier (full 32 bytes)</li>
 *   <li><strong>height</strong>: main-chain height (0 indicates orphan/candidate)</li>
 *   <li><strong>difficulty</strong>: PoW difficulty value</li>
 *   <li><strong>epoch</strong>: XDAG epoch number (not Unix time)</li>
 * </ul>
 *
 * <p>DRY reminders – do NOT store:
 * <ul>
 *   <li>Previous main block → query via {@code getBlockByHeight(height - 1)}</li>
 *   <li>Amount/Fee → derived from the block transactions</li>
 *   <li>Snapshot metadata → handled by {@code SnapshotManager}</li>
 *   <li>Type/flags/ref/maxDiffLink → obsolete, removed in the refactor</li>
 * </ul>
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">Design</a>
 */
@Value
@Builder(toBuilder = true)
public class BlockInfo implements Serializable {

  /** Full block hash (32 bytes) used as unique identifier. */
  Bytes32 hash;

  /**
   * Main chain height.
   * <p>
   * {@code height > 0} → block is part of the main chain.
   * <br>{@code height = 0} → block is an orphan/candidate that never won an epoch.
   * <p>
   * This flag is the canonical way to determine whether a block is in the best chain.
   */
  long height;

  /** PoW difficulty value (32 bytes). */
  UInt256 difficulty;

  /**
   * XDAG epoch number (each epoch = 64 seconds)
   *
   * <p><strong>IMPORTANT</strong>: This is NOT a Unix timestamp!
   * <p>XDAG uses a special epoch-based time system:
   * <ul>
   *   <li>XDAG timestamp = (Unix milliseconds * 1024) / 1000</li>
   *   <li>XDAG epoch = XDAG timestamp >> 16 (NOT / 64!)</li>
   *   <li>Each epoch = 65536 XDAG timestamp units = 64 seconds</li>
   * </ul>
   *
   * <p>To convert epoch to Unix time for display:
   * <pre>
   *   long unixMillis = TimeUtils.epochToMs(epoch);
   *   Date date = new Date(unixMillis);
   * </pre>
   * <p>Getter simply returns the raw epoch value and is retained for backward compatibility.
   *
   * @see TimeUtils#getEpoch(long)
   * @see TimeUtils#epochToTimeMillis(long)
  */
  long epoch;

  // ========== helper methods ==========

  /** @return {@code true} when the block belongs to the main chain. */
  public boolean isMainBlock() {
    return height > 0;
  }

  @Override
  public String toString() {
    return String.format(
        "BlockInfo{height=%d, hash=%s, epoch=%d, isMain=%b}",
        height,
        hash != null ? hash.toHexString().substring(0, 16) + "..." : "null",
        epoch,
        isMainBlock()
    );
  }
}
