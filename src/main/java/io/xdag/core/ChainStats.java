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

import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import java.io.Serializable;
import lombok.Builder;
import lombok.Value;
import lombok.With;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Immutable chain statistics for XDAG 1.0 epoch-based consensus
 *
 * <p>Optimized for XDAG 1.0 design - removed legacy fields that are never updated:
 * <ul>
 *   <li>Removed: totalMainBlockCount, totalBlockCount, totalHostCount (network-wide stats, never maintained)</li>
 *   <li>Removed: waitingSyncCount, noRefCount, extraCount (should use OrphanBlockStore instead)</li>
 *   <li>Removed: maxDifficulty (redundant with difficulty - we always follow heaviest chain)</li>
 *   <li>Removed: topBlock, topDifficulty, preTopBlock, preTopDifficulty (legacy, redundant with main chain tip)</li>
 * </ul>
 *
 * <p><strong>Core Fields (6 fields, ~80 bytes):</strong>
 * <ul>
 *   <li>difficulty (32 bytes) - Current chain cumulative difficulty for chain selection</li>
 *   <li>mainBlockCount (8 bytes) - Local main chain length (epoch winners)</li>
 *   <li>balance (8 bytes) - Current wallet balance</li>
 *   <li>baseDifficultyTarget (32 bytes) - PoW validation target</li>
 *   <li>lastDifficultyAdjustmentEpoch (8 bytes) - Last difficulty adjustment</li>
 *   <li>lastOrphanCleanupEpoch (8 bytes) - Last orphan cleanup</li>
 * </ul>
 *
 * @since XDAGJ 1.0
 */
@Value
@Builder(toBuilder = true)
@With
public class ChainStats implements Serializable {

  // ========== Core Chain State ==========

  /**
   * Current chain cumulative difficulty (sum of all block difficulties from genesis)
   * <p>
   * Used for chain selection (heaviest chain wins), NOT for PoW validation. For PoW validation, see
   * baseDifficultyTarget.
   */
  UInt256 difficulty;

  /**
   * Current number of main blocks in local chain (epoch winners)
   * <p>
   * This is the height of the local main chain. Each epoch winner gets a sequential height.
   */
  long mainBlockCount;

  /**
   * Current wallet balance across all accounts
   */
  XAmount balance;

  // ========== Difficulty Management ==========

  /**
   * Base difficulty target for PoW validation
   * <p>
   * All blocks must satisfy: hash <= baseDifficultyTarget This prevents spam blocks and ensures
   * minimum proof of work.
   * <p>
   * Adjusted periodically based on network hashrate to maintain target blocks per epoch.
   */
  UInt256 baseDifficultyTarget;

  /**
   * Last epoch when difficulty was adjusted
   * <p>
   * Used to determine when next difficulty adjustment should occur. Adjustment happens every
   * DIFFICULTY_ADJUSTMENT_INTERVAL epochs.
   */
  long lastDifficultyAdjustmentEpoch;

  /**
   * Last epoch when orphan blocks were cleaned up
   * <p>
   * Used to trigger periodic orphan block cleanup. Cleanup removes orphan blocks older than
   * ORPHAN_RETENTION_WINDOW.
   */
  long lastOrphanCleanupEpoch;

  // ========== Factory Methods ==========

  /**
   * Create initial chain stats with zero values
   */
  public static ChainStats zero() {
    return ChainStats.builder()
        .difficulty(UInt256.ZERO)
        .mainBlockCount(0)
        .balance(XAmount.ZERO)
        .baseDifficultyTarget(null)  // Will be set during initialization
        .lastDifficultyAdjustmentEpoch(0)
        .lastOrphanCleanupEpoch(0)
        .build();
  }

  // ========== Display Methods ==========

  @Override
  public String toString() {
    return String.format("ChainStats[mainBlocks=%d, difficulty=%s, balance=%s, target=%s]",
        mainBlockCount,
        difficulty.toDecimalString(),
        balance.toDecimal(9, XUnit.XDAG).toPlainString(),
        baseDifficultyTarget != null ? baseDifficultyTarget.toHexString().substring(0, 10) + "..."
            : "null");
  }

  // ========== Serialization Methods ==========

  /**
   * Serialize ChainStats to bytes for storage
   * <p>
   * Format (80 bytes total):
   * <ul>
   *   <li>[32 bytes] difficulty</li>
   *   <li>[8 bytes] mainBlockCount</li>
   *   <li>[8 bytes] balance (nano)</li>
   *   <li>[1 byte] baseDifficultyTarget null flag</li>
   *   <li>[32 bytes] baseDifficultyTarget (if not null)</li>
   *   <li>[8 bytes] lastDifficultyAdjustmentEpoch</li>
   *   <li>[8 bytes] lastOrphanCleanupEpoch</li>
   * </ul>
   */
  public byte[] toBytes() {
    SimpleEncoder enc = new SimpleEncoder();

    // Write difficulty (32 bytes)
    enc.write(difficulty.toBytes().toArray());

    // Write long fields
    enc.writeLong(mainBlockCount);
    enc.writeLong(balance.toXAmount().toLong());

    // Write baseDifficultyTarget (nullable)
    if (baseDifficultyTarget == null) {
      enc.writeBoolean(false);
    } else {
      enc.writeBoolean(true);
      enc.write(baseDifficultyTarget.toBytes().toArray());
    }

    // Write epoch tracking
    enc.writeLong(lastDifficultyAdjustmentEpoch);
    enc.writeLong(lastOrphanCleanupEpoch);

    return enc.toBytes();
  }

  /**
   * Deserialize ChainStats from bytes
   */
  public static ChainStats fromBytes(byte[] data) {
    SimpleDecoder dec = new SimpleDecoder(data);

    // Read difficulty (32 bytes)
    byte[] difficultyBytes = new byte[32];
    dec.readBytes(difficultyBytes);
    UInt256 difficulty = UInt256.fromBytes(org.apache.tuweni.bytes.Bytes.wrap(difficultyBytes));

    // Read long fields
    long mainBlockCount = dec.readLong();
    long balanceNano = dec.readLong();
    XAmount balance = XAmount.of(balanceNano);

    // Read baseDifficultyTarget (nullable)
    UInt256 baseDifficultyTarget = null;
    boolean hasBaseDifficultyTarget = dec.readBoolean();
    if (hasBaseDifficultyTarget) {
      byte[] baseDifficultyBytes = new byte[32];
      dec.readBytes(baseDifficultyBytes);
      baseDifficultyTarget = UInt256.fromBytes(
          org.apache.tuweni.bytes.Bytes.wrap(baseDifficultyBytes));
    }

    // Read epoch tracking
    long lastDifficultyAdjustmentEpoch = dec.readLong();
    long lastOrphanCleanupEpoch = dec.readLong();

    // Build ChainStats
    return ChainStats.builder()
        .difficulty(difficulty)
        .mainBlockCount(mainBlockCount)
        .balance(balance)
        .baseDifficultyTarget(baseDifficultyTarget)
        .lastDifficultyAdjustmentEpoch(lastDifficultyAdjustmentEpoch)
        .lastOrphanCleanupEpoch(lastOrphanCleanupEpoch)
        .build();
  }
}
