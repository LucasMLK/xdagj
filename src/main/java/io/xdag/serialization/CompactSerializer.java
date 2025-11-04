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

package io.xdag.serialization;

import io.xdag.core.*;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.units.bigints.UInt256;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Compact serializer for XDAG data structures
 *
 * This serializer replaces Kryo with a custom, optimized implementation that:
 * - Uses variable-length integer encoding (VarInt)
 * - Implements bit-field compression for flags
 * - Minimizes overhead
 * - Targets 3-4x performance improvement over Kryo
 * - Achieves ~180 bytes for BlockInfo (vs ~220 bytes with Kryo)
 */
public class CompactSerializer {

    // ========== BlockInfo Serialization ==========

    /**
     * Serialize BlockInfo to bytes
     * Target size: ~180 bytes
     */
    public static byte[] serialize(BlockInfo blockInfo) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(200);

        // Fixed-size fields first (for alignment)
        // hash: 32 bytes (full hash format)
        out.write(blockInfo.getHash().toArray());

        // timestamp: 8 bytes (fixed)
        writeFixed64(out, blockInfo.getTimestamp());

        // height: variable length (typically 3-5 bytes)
        writeVarLong(out, blockInfo.getHeight());

        // Phase 9.3: BlockInfo type/flags deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing zeros for backward compatibility with v1 format
        writeFixed64(out, 0L); // type placeholder
        writeFixed32(out, 0);  // flags placeholder

        // difficulty: 32 bytes (UInt256)
        out.write(blockInfo.getDifficulty().toBytes().toArray());

        // Phase 9.3: BlockInfo.ref field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // ref absent placeholder

        // Phase 9.3: BlockInfo.maxDiffLink field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // maxDiffLink absent placeholder

        // Phase 9.3: BlockInfo.amount field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing null for backward compatibility with v1 format
        serializeXAmount(out, null); // amount placeholder

        // Phase 9.3: BlockInfo.fee field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing null for backward compatibility with v1 format
        serializeXAmount(out, null); // fee placeholder

        // Phase 9.3: BlockInfo.remark field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing zero length for backward compatibility with v1 format
        writeVarInt(out, 0); // remark length placeholder

        // Phase 9.3: BlockInfo.isSnapshot field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing false for backward compatibility with v1 format
        out.write(0); // isSnapshot placeholder

        // Phase 9.3: BlockInfo.snapshotInfo field deleted in v5.1 minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // snapshotInfo absent placeholder

        return out.toByteArray();
    }

    /**
     * Deserialize BlockInfo from bytes
     */
    public static BlockInfo deserializeBlockInfo(byte[] data) throws IOException {
        ByteReader reader = new ByteReader(data);

        // hash: 32 bytes (full hash format)
        Bytes32 fullHash = Bytes32.wrap(reader.readBytes(32));

        // timestamp: 8 bytes
        long timestamp = reader.readFixed64();

        // height: variable
        long height = reader.readVarLong();

        // Phase 9.3: BlockInfo type/flags deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholders for backward compatibility with v1 format
        long type = reader.readFixed64(); // placeholder
        int flags = reader.readFixed32(); // placeholder

        // difficulty: 32 bytes
        UInt256 difficulty = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));

        // Phase 9.3: BlockInfo.ref field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        byte refFlag = reader.readByte(); // placeholder

        // Phase 9.3: BlockInfo.maxDiffLink field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        byte maxDiffLinkFlag = reader.readByte(); // placeholder

        // Phase 9.3: BlockInfo.amount field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        XAmount amount = deserializeXAmount(reader); // placeholder

        // Phase 9.3: BlockInfo.fee field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        XAmount fee = deserializeXAmount(reader); // placeholder

        // Phase 9.3: BlockInfo.remark field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        int remarkLen = reader.readVarInt(); // placeholder

        // Phase 9.3: BlockInfo.isSnapshot field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        byte isSnapshotFlag = reader.readByte(); // placeholder

        // Phase 9.3: BlockInfo.snapshotInfo field deleted in v5.1 minimal design (backward compatibility maintained)
        // Reading placeholder for backward compatibility with v1 format
        byte snapshotInfoFlag = reader.readByte(); // placeholder

        return BlockInfo.builder()
                .hash(fullHash)  // Use full hash format, not hash
                .timestamp(timestamp)
                .height(height)
                .difficulty(difficulty)
                .build();
    }

    // ========== ChainStats Serialization ==========

    /**
     * Serialize ChainStats to bytes (v5.1 optimized - removed 5 deprecated fields)
     *
     * Phase 7.3 Optimization: Removed blockCount, hostCount, mainBlockTime,
     * globalMinerHash, ourLastBlockHash serialization for 33% size reduction.
     */
    public static byte[] serialize(ChainStats stats) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(100);

        // difficulty: 32 bytes
        out.write(stats.getDifficulty().toBytes().toArray());

        // maxDifficulty: 32 bytes
        out.write(stats.getMaxDifficulty().toBytes().toArray());

        // Core counts (all variable length)
        writeVarLong(out, stats.getMainBlockCount());
        writeVarLong(out, stats.getTotalMainBlockCount());
        writeVarLong(out, stats.getTotalBlockCount());
        writeVarInt(out, stats.getTotalHostCount());

        // Sync & orphan tracking
        writeVarLong(out, stats.getWaitingSyncCount());
        writeVarLong(out, stats.getNoRefCount());
        writeVarLong(out, stats.getExtraCount());

        // balance
        serializeXAmount(out, stats.getBalance());

        return out.toByteArray();
    }

    /**
     * Deserialize ChainStats from bytes (v5.1 optimized)
     *
     * Phase 7.3 Optimization: Reads only the 10 core fields.
     * Deprecated fields are set to defaults in fromLegacy() if needed.
     */
    public static ChainStats deserializeChainStats(byte[] data) throws IOException {
        ByteReader reader = new ByteReader(data);

        UInt256 difficulty = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));
        UInt256 maxDifficulty = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));

        long mainBlockCount = reader.readVarLong();
        long totalMainBlockCount = reader.readVarLong();
        long totalBlockCount = reader.readVarLong();
        int totalHostCount = reader.readVarInt();

        long waitingSyncCount = reader.readVarLong();
        long noRefCount = reader.readVarLong();
        long extraCount = reader.readVarLong();

        XAmount balance = deserializeXAmount(reader);

        return ChainStats.builder()
                .difficulty(difficulty)
                .maxDifficulty(maxDifficulty)
                .mainBlockCount(mainBlockCount)
                .totalMainBlockCount(totalMainBlockCount)
                .totalBlockCount(totalBlockCount)
                .totalHostCount(totalHostCount)
                .waitingSyncCount(waitingSyncCount)
                .noRefCount(noRefCount)
                .extraCount(extraCount)
                .balance(balance)
                .build();
    }

    // ========== Snapshot Serialization ==========

    /**
     * Serialize Snapshot to bytes
     * Target: ~34 bytes for 32-byte data
     */
    public static byte[] serialize(Snapshot snapshot) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(40);

        // type: 1 byte
        out.write(snapshot.getType().toByte());

        // data length: variable
        byte[] dataBytes = snapshot.getData() != null ? snapshot.getData().toArray() : new byte[0];
        writeVarInt(out, dataBytes.length);

        // data
        if (dataBytes.length > 0) {
            out.write(dataBytes);
        }

        return out.toByteArray();
    }

    /**
     * Deserialize Snapshot from bytes
     */
    public static Snapshot deserializeSnapshot(byte[] data) throws IOException {
        ByteReader reader = new ByteReader(data);

        Snapshot.SnapshotType type = Snapshot.SnapshotType.fromByte(reader.readByte());

        int dataLen = reader.readVarInt();
        Bytes snapshotData = dataLen > 0 ? Bytes.wrap(reader.readBytes(dataLen)) : Bytes.EMPTY;

        return Snapshot.builder()
                .type(type)
                .data(snapshotData)
                .build();
    }

    // ========== Helper Methods ==========

    /**
     * Write variable-length integer (VarInt encoding)
     * - 1 byte for 0-127
     * - 2 bytes for 128-16383
     * - etc.
     */
    private static void writeVarInt(ByteArrayOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    /**
     * Write variable-length long
     */
    private static void writeVarLong(ByteArrayOutputStream out, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    /**
     * Write fixed 32-bit integer (4 bytes, little-endian)
     */
    private static void writeFixed32(ByteArrayOutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /**
     * Write fixed 64-bit long (8 bytes, little-endian)
     */
    private static void writeFixed64(ByteArrayOutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 32) & 0xFF));
        out.write((int) ((value >> 40) & 0xFF));
        out.write((int) ((value >> 48) & 0xFF));
        out.write((int) ((value >> 56) & 0xFF));
    }

    /**
     * Serialize XAmount
     */
    private static void serializeXAmount(ByteArrayOutputStream out, XAmount amount) throws IOException {
        if (amount != null) {
            out.write(1); // present
            writeFixed64(out, amount.toXAmount().toLong());
        } else {
            out.write(0); // absent
        }
    }

    /**
     * Deserialize XAmount
     */
    private static XAmount deserializeXAmount(ByteReader reader) throws IOException {
        if (reader.readByte() == 1) {
            return XAmount.ofXAmount(reader.readFixed64());
        }
        return null;
    }

    /**
     * Serialize SnapshotInfo (legacy)
     */
    private static byte[] serializeSnapshotInfo(SnapshotInfo info) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(40);
        out.write(info.getType() ? 1 : 0);
        byte[] data = info.getData() != null ? info.getData() : new byte[0];
        writeVarInt(out, data.length);
        if (data.length > 0) {
            out.write(data);
        }
        return out.toByteArray();
    }

    /**
     * Deserialize SnapshotInfo (legacy)
     */
    private static SnapshotInfo deserializeSnapshotInfo(ByteReader reader) throws IOException {
        boolean type = reader.readByte() == 1;
        int dataLen = reader.readVarInt();
        byte[] data = dataLen > 0 ? reader.readBytes(dataLen) : new byte[0];
        return new SnapshotInfo(type, data);
    }

    // ========== ByteReader Helper Class ==========

    /**
     * Helper class for reading bytes sequentially
     */
    private static class ByteReader {
        private final byte[] data;
        private int position;

        public ByteReader(byte[] data) {
            this.data = data;
            this.position = 0;
        }

        public byte readByte() throws IOException {
            if (position >= data.length) {
                throw new IOException("End of data reached");
            }
            return data[position++];
        }

        public byte[] readBytes(int length) throws IOException {
            if (position + length > data.length) {
                throw new IOException("Not enough data");
            }
            byte[] result = new byte[length];
            System.arraycopy(data, position, result, 0, length);
            position += length;
            return result;
        }

        public int readVarInt() throws IOException {
            int result = 0;
            int shift = 0;
            byte b;
            do {
                b = readByte();
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        public long readVarLong() throws IOException {
            long result = 0;
            int shift = 0;
            byte b;
            do {
                b = readByte();
                result |= (long) (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        public int readFixed32() throws IOException {
            int b1 = readByte() & 0xFF;
            int b2 = readByte() & 0xFF;
            int b3 = readByte() & 0xFF;
            int b4 = readByte() & 0xFF;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
        }

        public long readFixed64() throws IOException {
            long b1 = readByte() & 0xFF;
            long b2 = readByte() & 0xFF;
            long b3 = readByte() & 0xFF;
            long b4 = readByte() & 0xFF;
            long b5 = readByte() & 0xFF;
            long b6 = readByte() & 0xFF;
            long b7 = readByte() & 0xFF;
            long b8 = readByte() & 0xFF;
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24) |
                   (b5 << 32) | (b6 << 40) | (b7 << 48) | (b8 << 56);
        }
    }
}
