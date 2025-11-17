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

import lombok.Builder;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Transaction execution information.
 *
 * <p>Records details about when and where a transaction was executed:
 * <ul>
 *   <li>Executing block hash and height</li>
 *   <li>Execution timestamp</li>
 *   <li>Reversal status (for chain reorganizations)</li>
 * </ul>
 *
 * <p>This information is used to:
 * <ul>
 *   <li>Prevent duplicate execution (same tx in multiple blocks)</li>
 *   <li>Track transaction history</li>
 *   <li>Support chain reorganization rollback</li>
 * </ul>
 *
 * @since Phase 1 - Task 1.2
 */
@Value
@Builder
public class TransactionExecutionInfo {

    /**
     * Hash of the block that executed this transaction
     */
    Bytes32 executingBlockHash;

    /**
     * Height of the executing block
     */
    long executingBlockHeight;

    /**
     * Timestamp when the transaction was executed (milliseconds)
     */
    long executionTimestamp;

    /**
     * Whether this execution has been reversed (due to chain reorg)
     */
    @Builder.Default
    boolean isReversed = false;

    /**
     * Create execution info for a newly executed transaction.
     *
     * @param blockHash executing block hash
     * @param blockHeight executing block height
     * @return execution info
     */
    public static TransactionExecutionInfo create(Bytes32 blockHash, long blockHeight) {
        return TransactionExecutionInfo.builder()
                .executingBlockHash(blockHash)
                .executingBlockHeight(blockHeight)
                .executionTimestamp(System.currentTimeMillis())
                .isReversed(false)
                .build();
    }

    @Override
    public String toString() {
        return String.format(
                "TransactionExecutionInfo{block=%s, height=%d, timestamp=%d, reversed=%b}",
                executingBlockHash.toHexString().substring(0, 16),
                executingBlockHeight,
                executionTimestamp,
                isReversed);
    }
}
