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
 * Wrapper for a pending transaction in the transaction pool.
 *
 * <p>This class wraps a Transaction with additional metadata:
 * <ul>
 *   <li>Entry timestamp - when it was added to the pool</li>
 *   <li>Future: Priority, retry count, etc.</li>
 * </ul>
 *
 * @since Phase 1 - TransactionPool Implementation
 */
@Getter
public class PendingTransaction {

    /**
     * The wrapped transaction
     */
    private final Transaction transaction;

    /**
     * Timestamp when this transaction was added to the pool (milliseconds)
     */
    private final long entryTime;

    /**
     * Create a PendingTransaction with current timestamp.
     *
     * @param transaction the transaction to wrap
     */
    public PendingTransaction(Transaction transaction) {
        this.transaction = transaction;
        this.entryTime = System.currentTimeMillis();
    }

    /**
     * Get the age of this transaction in milliseconds.
     *
     * @return age in milliseconds
     */
    public long getAge() {
        return System.currentTimeMillis() - entryTime;
    }

    /**
     * Check if this transaction has expired.
     *
     * @param maxAgeMillis maximum allowed age
     * @return true if expired
     */
    public boolean isExpired(long maxAgeMillis) {
        return getAge() > maxAgeMillis;
    }

    @Override
    public String toString() {
        return String.format("PendingTransaction{hash=%s, from=%s, nonce=%d, age=%dms}",
                transaction.getHash().toHexString().substring(0, 16),
                transaction.getFrom().toHexString().substring(0, 8),
                transaction.getNonce(),
                getAge());
    }
}
