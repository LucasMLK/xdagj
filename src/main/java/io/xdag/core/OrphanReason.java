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

import lombok.Getter;

/**
 * OrphanReason - classifies different orphan categories.
 *
 * <p>We distinguish two entirely different orphan block types:
 * <ul>
 *   <li><strong>MISSING_DEPENDENCY</strong>: the parent block is missing, so the orphan must be
 *   retried after dependencies arrive.</li>
 *   <li><strong>LOST_COMPETITION</strong>: the block lost the epoch competition; it will never
 *   become a main block.</li>
 * </ul>
 *
 * <h2>Why draw the boundary?</h2>
 * <p>In XDAG 1.0b each epoch (64 seconds) stores at most 16 candidate blocks, and only one can
 * become the main block. The remaining 15 are orphans because they lost the competition, not because
 * they miss parents, so re-importing them is pointless.</p>
 *
 * <p><strong>BUG-ORPHAN-001</strong> fix: PendingBlockManager must only retry
 * {@link OrphanReason#MISSING_DEPENDENCY} blocks and should never retry
 * {@link OrphanReason#LOST_COMPETITION} blocks.</p>
 *
 * @since XDAGJ 5.2.0
 * @see PendingBlockManager
 */
@Getter
public enum OrphanReason {
  /**
   * Parent block is missing so we must retry later.
   *
   * <p>Scenario: blocks arrive out of order and the child reaches the node before the parent.</p>
   * <p>Example: Block C references Block B, but Block B has not been received from the network.</p>
   * <p>Expected handling: enqueue the block and retry after the parent is imported.</p>
   */
  MISSING_DEPENDENCY((byte) 0),

  /**
   * Lost the epoch competition even though all parents exist.
   *
   * <p>Scenario: multiple candidates are mined in the same epoch and only the smallest hash wins.</p>
   * <p>Example: epoch 27,569,886 contains two blocks:</p>
   * <ul>
   *   <li>Block A (difficulty = 20) - loses and becomes an orphan.</li>
   *   <li>Block B (difficulty = 36) - wins and becomes the main block.</li>
   * </ul>
   * <p>Expected handling: no retry unless a chain reorganization later promotes it.</p>
   */
  LOST_COMPETITION((byte) 1);

  /**
   * -- GETTER --
   * Byte code used when persisting the reason.
   */
  private final byte code;

  OrphanReason(byte code) {
    this.code = code;
  }

  /**
   * Parse the persisted reason code.
   *
   * @param code the serialized byte
   * @return reason enum
   * @throws IllegalArgumentException when the code is invalid
   */
  public static OrphanReason fromCode(byte code) {
    return switch (code) {
      case 0 -> MISSING_DEPENDENCY;
      case 1 -> LOST_COMPETITION;
      default -> throw new IllegalArgumentException("Unknown OrphanReason code: " + code);
    };
  }

}
