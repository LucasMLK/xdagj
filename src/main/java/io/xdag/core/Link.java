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

import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;

import java.io.Serializable;
import java.nio.ByteBuffer;

/**
 * Lightweight DAG edge (Link) for XDAG v5.1
 *
 * Design principles:
 * 1. Extreme simplicity: only 33 bytes (32 bytes hash + 1 byte type)
 * 2. Type identification: distinguish Transaction (type=0) vs Block (type=1)
 * 3. No amount field: Block only stores references, not transfer amounts
 * 4. Immutable: thread-safe and cacheable
 * 5. Performance: compact structure enables 1,485,000 links in 48MB block
 *
 * Key difference from BlockLink:
 * - Link is ultra-simple (33 bytes): just hash + type
 * - BlockLink is feature-rich: includes LinkType enum, amount, remark
 * - Link is used in v5.1 Block structure for maximum performance
 * - BlockLink is used for internal tracking and analysis
 *
 * Size calculation:
 * - 32 bytes: targetHash (full 32-byte hash)
 * - 1 byte: type (0=Transaction, 1=Block)
 * - Total: 33 bytes per link
 *
 * TPS calculation (48MB Block):
 * - 48MB / 33 bytes = 1,485,000 links
 * - 1,485,000 txs / 64 seconds = 23,200 TPS (96.7% Visa level)
 *
 * @see <a href="docs/refactor-design/CORE_DATA_STRUCTURES.md">v5.1 Design</a>
 */
@Value
public class Link implements Serializable {

    /**
     * Hash of the target (Transaction or Block)
     * Full 32-byte hash for uniqueness
     */
    Bytes32 targetHash;

    /**
     * Type of the target
     * - TRANSACTION (0): link points to a Transaction
     * - BLOCK (1): link points to another Block
     *
     * This field avoids database queries to determine target type
     */
    LinkType type;

    /**
     * Size of this link in bytes
     */
    public static final int LINK_SIZE = 33;  // 32 bytes hash + 1 byte type

    /**
     * Link type enumeration
     */
    public enum LinkType {
        /**
         * Link points to a Transaction (type = 0)
         * Used when Block references a Transaction for inclusion
         */
        TRANSACTION(0),

        /**
         * Link points to another Block (type = 1)
         * Used for DAG structure (prevMainBlock, orphan references)
         */
        BLOCK(1);

        private final byte value;

        LinkType(int value) {
            this.value = (byte) value;
        }

        public byte getValue() {
            return value;
        }

        /**
         * Convert from byte value to LinkType
         *
         * @param value byte value (0 or 1)
         * @return corresponding LinkType
         * @throws IllegalArgumentException if value is not 0 or 1
         */
        public static LinkType fromByte(byte value) {
            return switch (value) {
                case 0 -> TRANSACTION;
                case 1 -> BLOCK;
                default -> throw new IllegalArgumentException("Invalid LinkType value: " + value);
            };
        }
    }

    // ========== Factory Methods ==========

    /**
     * Create a link to a Transaction
     *
     * @param transactionHash hash of the transaction
     * @return link pointing to transaction
     */
    public static Link toTransaction(Bytes32 transactionHash) {
        return new Link(transactionHash, LinkType.TRANSACTION);
    }

    /**
     * Create a link to a Block
     *
     * @param blockHash hash of the block
     * @return link pointing to block
     */
    public static Link toBlock(Bytes32 blockHash) {
        return new Link(blockHash, LinkType.BLOCK);
    }

    // ========== Helper Methods ==========

    /**
     * Check if this link points to a Transaction
     *
     * @return true if type is TRANSACTION
     */
    public boolean isTransaction() {
        return type == LinkType.TRANSACTION;
    }

    /**
     * Check if this link points to a Block
     *
     * @return true if type is BLOCK
     */
    public boolean isBlock() {
        return type == LinkType.BLOCK;
    }

    /**
     * Serialize this link to bytes
     *
     * Format:
     * - [0-31]: targetHash (32 bytes)
     * - [32]: type (1 byte, 0=TX, 1=Block)
     *
     * @return 33-byte array
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(LINK_SIZE);
        buffer.put(targetHash.toArray());
        buffer.put(type.getValue());
        return buffer.array();
    }

    /**
     * Deserialize a link from bytes
     *
     * @param bytes 33-byte array
     * @return Link instance
     * @throws IllegalArgumentException if bytes length is not 33
     */
    public static Link fromBytes(byte[] bytes) {
        if (bytes.length != LINK_SIZE) {
            throw new IllegalArgumentException(
                "Invalid link size: " + bytes.length + " (expected " + LINK_SIZE + ")");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        // Read targetHash (32 bytes)
        byte[] hashBytes = new byte[32];
        buffer.get(hashBytes);
        Bytes32 targetHash = Bytes32.wrap(hashBytes);

        // Read type (1 byte)
        byte typeValue = buffer.get();
        LinkType type = LinkType.fromByte(typeValue);

        return new Link(targetHash, type);
    }

    /**
     * Get the size of this link
     *
     * @return always returns 33
     */
    public int size() {
        return LINK_SIZE;
    }

    @Override
    public String toString() {
        return String.format(
            "Link[type=%s, target=%s]",
            type,
            targetHash.toHexString().substring(0, 16) + "..."
        );
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Link)) return false;
        Link link = (Link) o;
        return targetHash.equals(link.targetHash) && type == link.type;
    }

    @Override
    public int hashCode() {
        return targetHash.hashCode() * 31 + type.hashCode();
    }
}
