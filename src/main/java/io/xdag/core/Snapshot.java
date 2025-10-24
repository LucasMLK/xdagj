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
import lombok.With;
import org.apache.tuweni.bytes.Bytes;

import java.io.Serializable;

/**
 * Immutable snapshot information for block state
 *
 * This class replaces the mutable SnapshotInfo with a compact, type-safe implementation.
 *
 * Target size: ~34 bytes (compared to ~50 bytes in SnapshotInfo)
 * - 1 byte: type flag
 * - 1 byte: data length
 * - 32 bytes: data (max, typically public key or block reference)
 */
@Value
@Builder(toBuilder = true)
@With
public class Snapshot implements Serializable {

    /**
     * Type of snapshot data
     */
    SnapshotType type;

    /**
     * The snapshot data (public key or block data)
     */
    Bytes data;

    /**
     * Create a public key snapshot
     */
    public static Snapshot publicKey(Bytes publicKeyData) {
        return new Snapshot(SnapshotType.PUBLIC_KEY, publicKeyData);
    }

    /**
     * Create a block data snapshot
     */
    public static Snapshot blockData(Bytes blockData) {
        return new Snapshot(SnapshotType.BLOCK_DATA, blockData);
    }

    /**
     * Create from legacy SnapshotInfo
     */
    public static Snapshot fromLegacy(SnapshotInfo legacy) {
        if (legacy == null) {
            return null;
        }
        SnapshotType type = legacy.getType() ? SnapshotType.PUBLIC_KEY : SnapshotType.BLOCK_DATA;
        Bytes data = legacy.getData() != null ? Bytes.wrap(legacy.getData()) : Bytes.EMPTY;
        return new Snapshot(type, data);
    }

    /**
     * Convert to legacy SnapshotInfo (for backward compatibility)
     */
    public SnapshotInfo toLegacy() {
        boolean legacyType = (type == SnapshotType.PUBLIC_KEY);
        byte[] legacyData = data != null ? data.toArray() : new byte[0];
        return new SnapshotInfo(legacyType, legacyData);
    }

    /**
     * Check if this is a public key snapshot
     */
    public boolean isPublicKey() {
        return type == SnapshotType.PUBLIC_KEY;
    }

    /**
     * Check if this is a block data snapshot
     */
    public boolean isBlockData() {
        return type == SnapshotType.BLOCK_DATA;
    }

    /**
     * Check if snapshot has data
     */
    public boolean hasData() {
        return data != null && !data.isEmpty();
    }

    /**
     * Get the size of the snapshot data in bytes
     */
    public int getDataSize() {
        return data != null ? data.size() : 0;
    }

    /**
     * Get compact size (actual serialized size)
     */
    public int getCompactSize() {
        // 1 byte type + 1 byte length + actual data length
        return 1 + 1 + getDataSize();
    }

    @Override
    public String toString() {
        return String.format("Snapshot[type=%s, dataSize=%d bytes]",
                type,
                getDataSize());
    }

    /**
     * Snapshot type enumeration
     */
    public enum SnapshotType {
        /**
         * Public key snapshot
         */
        PUBLIC_KEY,

        /**
         * Block data snapshot
         */
        BLOCK_DATA;

        /**
         * Convert to byte representation (for serialization)
         */
        public byte toByte() {
            return (byte) ordinal();
        }

        /**
         * Create from byte representation (for deserialization)
         */
        public static SnapshotType fromByte(byte b) {
            return switch (b) {
                case 0 -> PUBLIC_KEY;
                case 1 -> BLOCK_DATA;
                default -> throw new IllegalArgumentException("Invalid SnapshotType byte: " + b);
            };
        }

        /**
         * Convert to legacy boolean representation
         */
        public boolean toLegacyBoolean() {
            return this == PUBLIC_KEY;
        }

        /**
         * Create from legacy boolean representation
         */
        public static SnapshotType fromLegacyBoolean(boolean legacyType) {
            return legacyType ? PUBLIC_KEY : BLOCK_DATA;
        }
    }
}
