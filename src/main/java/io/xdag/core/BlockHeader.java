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
import lombok.Getter;
import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.Serializable;

/**
 * BlockHeader for XDAG v5.1
 *
 * Design principles (from CORE_DATA_STRUCTURES.md):
 * 1. Participates in hash calculation: timestamp, difficulty, nonce, coinbase
 * 2. Hash is a cached field: lazy computation, not serialized
 * 3. All blocks are candidate blocks: all have nonce and coinbase
 * 4. Immutable design: thread-safe and cacheable
 *
 * Size: 104 bytes (fixed)
 * - timestamp: 8 bytes
 * - difficulty: 32 bytes
 * - nonce: 32 bytes
 * - coinbase: 32 bytes
 * - hash: 32 bytes (cached, not serialized)
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">v5.1 Design</a>
 */
@Value
@Builder(toBuilder = true)
public class BlockHeader implements Serializable {

    /**
     * Block timestamp (seconds since Unix epoch)
     * Used to calculate epoch: epoch = timestamp / 64
     */
    long timestamp;

    /**
     * PoW difficulty target value (32 bytes)
     * All candidate blocks in the same epoch use the same difficulty
     * Block's hash must satisfy: hash <= difficulty
     */
    UInt256 difficulty;

    /**
     * PoW nonce (32 bytes)
     * Found through mining to satisfy difficulty requirement
     * All blocks have nonce (all are candidate blocks)
     */
    Bytes32 nonce;

    /**
     * Miner address / coinbase (32 bytes)
     * Address that receives block reward and transaction fees
     * All blocks have coinbase (all compete for rewards)
     */
    Bytes32 coinbase;

    /**
     * Block hash cache (32 bytes)
     * Lazy computation: calculated on first use, then cached
     * NOT serialized, NOT participates in hash calculation
     * Managed by Block.getHash() method
     */
    Bytes32 hash;  // null initially, set by Block.getHash()

    /**
     * Calculate epoch from timestamp
     * epoch = timestamp / 64
     *
     * @return epoch number
     */
    public long getEpoch() {
        return timestamp / 64;
    }

    /**
     * Check if this block satisfies the PoW difficulty requirement
     * Valid if: hash <= difficulty
     *
     * Note: hash must be set (not null) before calling this method
     *
     * @return true if hash <= difficulty
     */
    public boolean satisfiesDifficulty() {
        if (hash == null) {
            throw new IllegalStateException("Hash not calculated yet, call Block.getHash() first");
        }
        return UInt256.fromBytes(hash).compareTo(difficulty) <= 0;
    }

    /**
     * Get the size of this header in bytes (for serialization)
     *
     * @return 104 bytes (fixed)
     */
    public static int getSerializedSize() {
        return 8 +   // timestamp
               32 +  // difficulty
               32 +  // nonce
               32;   // coinbase
        // hash is NOT serialized (cached field)
    }

    @Override
    public String toString() {
        return String.format(
            "BlockHeader[epoch=%d, timestamp=%d, difficulty=%s, nonce=%s, coinbase=%s]",
            getEpoch(),
            timestamp,
            difficulty.toHexString().substring(0, 16) + "...",
            nonce.toHexString().substring(0, 16) + "...",
            coinbase.toHexString().substring(0, 16) + "..."
        );
    }
}
