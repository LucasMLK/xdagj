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

package io.xdag.db.rocksdb.transaction;

/**
 * Exception thrown when a database transaction operation fails.
 *
 * <p>This exception indicates that a transaction could not be completed successfully.
 * Common causes include:
 * <ul>
 *   <li>Transaction not found (invalid transaction ID)</li>
 *   <li>Database write failure during commit</li>
 *   <li>Operation failure (e.g., invalid key/value)</li>
 *   <li>RocksDB internal error</li>
 * </ul>
 *
 * @since Phase 0.5 - Transaction Support Infrastructure
 */
public class TransactionException extends Exception {

    /**
     * Constructs a new transaction exception with the specified detail message.
     *
     * @param message the detail message
     */
    public TransactionException(String message) {
        super(message);
    }

    /**
     * Constructs a new transaction exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause (which is saved for later retrieval)
     */
    public TransactionException(String message, Throwable cause) {
        super(message, cause);
    }
}
