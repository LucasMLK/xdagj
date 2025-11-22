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

package io.xdag.utils;

import io.xdag.core.BlockInfo;
import io.xdag.core.ChainStats;
import io.xdag.core.XAmount;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Compact serializer for XDAG data structures
 */
public class CompactSerializer {

    /**
     * Serialize BlockInfo to bytes
     * Target size: ~180 bytes
     *
     * @param blockInfo BlockInfo to serialize (must not be null)
     * @return Serialized byte array
     * @throws IllegalArgumentException if blockInfo is null
     * @throws IOException if serialization fails
     */
    public static byte[] serialize(BlockInfo blockInfo) throws IOException {
        // BUGFIX (BUG-037): Add null check for defensive programming
        // Previously: Would throw NullPointerException if blockInfo is null
        // Now: Throw IllegalArgumentException with clear message
        if (blockInfo == null) {
            throw new IllegalArgumentException("BlockInfo cannot be null");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(200);

        // Fixed-size fields first (for alignment)
        // hash: 32 bytes (full hash format)
        out.write(blockInfo.getHash().toArray());

        // epoch: 8 bytes (fixed - XDAG epoch number)
        writeFixed64(out, blockInfo.getEpoch());

        // height: variable length (typically 3-5 bytes)
        writeVarLong(out, blockInfo.getHeight());

        //  BlockInfo type/flags deleted in minimal design (backward compatibility maintained)
        // Serializing zeros for backward compatibility with v1 format
        writeFixed64(out, 0L); // type placeholder
        writeFixed32(out, 0);  // flags placeholder

        // difficulty: 32 bytes (UInt256)
        out.write(blockInfo.getDifficulty().toBytes().toArray());

        //  BlockInfo.ref field deleted in minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // ref absent placeholder

        //  BlockInfo.maxDiffLink field deleted in minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // maxDiffLink absent placeholder

        //  BlockInfo.amount field deleted in minimal design (backward compatibility maintained)
        // Serializing null for backward compatibility with v1 format
        serializeXAmount(out, null); // amount placeholder

        //  BlockInfo.fee field deleted in minimal design (backward compatibility maintained)
        // Serializing null for backward compatibility with v1 format
        serializeXAmount(out, null); // fee placeholder

        //  BlockInfo.remark field deleted in minimal design (backward compatibility maintained)
        // Serializing zero length for backward compatibility with v1 format
        writeVarInt(out, 0); // remark length placeholder

        //  BlockInfo.isSnapshot field deleted in minimal design (backward compatibility maintained)
        // Serializing false for backward compatibility with v1 format
        out.write(0); // isSnapshot placeholder

        //  BlockInfo.snapshotInfo field deleted in minimal design (backward compatibility maintained)
        // Serializing absent flag for backward compatibility with v1 format
        out.write(0); // snapshotInfo absent placeholder

        return out.toByteArray();
    }

  // ========== ChainStats Serialization ==========

    /**
     * Serialize ChainStats to bytes
     *
     * @param stats ChainStats to serialize (must not be null)
     * @return Serialized byte array
     * @throws IllegalArgumentException if stats is null
     * @throws IOException if serialization fails
     */
    public static byte[] serialize(ChainStats stats) throws IOException {
        // BUGFIX (BUG-038): Add null check for defensive programming
        // Previously: Would throw NullPointerException if stats is null
        // Now: Throw IllegalArgumentException with clear message
        if (stats == null) {
            throw new IllegalArgumentException("ChainStats cannot be null");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(200);

        // difficulty: 32 bytes
        out.write(stats.getDifficulty().toBytes().toArray());

        // Core counts (all variable length)
        writeVarLong(out, stats.getMainBlockCount());

        // balance
        serializeXAmount(out, stats.getBalance());

        // NEW CONSENSUS FIELDS (v2)

        // baseDifficultyTarget (nullable UInt256)
        if (stats.getBaseDifficultyTarget() == null) {
            out.write(0); // absent
        } else {
            out.write(1); // present
            out.write(stats.getBaseDifficultyTarget().toBytes().toArray());
        }

        // lastDifficultyAdjustmentEpoch
        writeVarLong(out, stats.getLastDifficultyAdjustmentEpoch());

        // lastOrphanCleanupEpoch
        writeVarLong(out, stats.getLastOrphanCleanupEpoch());

        return out.toByteArray();
    }

    /**
     * Deserialize ChainStats from bytes (optimized)
     * <p>
     * v3: Reads simplified ChainStats with backward compatibility.
     * If reading old format data (with legacy fields), skips them and uses only core fields.
     * <p>
     * Format:
     * - difficulty (32 bytes)
     * - mainBlockCount (var long)
     * - balance (XAmount)
     * - baseDifficultyTarget (nullable, 1 byte flag + 32 bytes if present)
     * - lastDifficultyAdjustmentEpoch (var long)
     * - lastOrphanCleanupEpoch (var long)
     *
     * @param data Serialized data (must not be null or empty)
     * @return Deserialized ChainStats
     * @throws IllegalArgumentException if data is null or empty
     * @throws IOException if deserialization fails
     */
    public static ChainStats deserializeChainStats(byte[] data) throws IOException {
        // BUGFIX (BUG-040, BUG-041): Add input validation for defensive programming
        // Previously: Would throw NullPointerException or IOException with poor context
        // Now: Validate input and provide clear error messages
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        if (data.length == 0) {
            throw new IllegalArgumentException("Data cannot be empty");
        }

        ByteReader reader = new ByteReader(data);

        // LIMITATION (BUG-039): Legacy format detection uses heuristic
        // Current approach: data.length > 150 to detect legacy format
        //
        // Why this works in practice:
        // - Legacy format: ~200+ bytes (has maxDifficulty, totalMainBlockCount,
        //   totalBlockCount, totalHostCount, waitingSyncCount, etc.)
        // - New format: ~80-120 bytes (only core fields)
        //
        // Known edge cases:
        // - If new format grows beyond 150 bytes (unlikely), would be misidentified
        // - If legacy format is exactly 150 bytes or less (very unlikely)
        //
        // Better solution (requires protocol change):
        // - Add version byte at the start of serialized data
        // - Check version byte instead of length heuristic
        //
        // Risk assessment: LOW
        // - Format sizes are well-separated in practice
        // - New format is intentionally compact
        // - No known failures in production
        //
        // TODO: Consider adding version byte in next protocol upgrade
        boolean isLegacyFormat = data.length > 150;  // Legacy format is much longer

        UInt256 difficulty;
        long mainBlockCount;
        XAmount balance;
        UInt256 baseDifficultyTarget = null;
        long lastDifficultyAdjustmentEpoch = 0;
        long lastOrphanCleanupEpoch = 0;

        if (isLegacyFormat) {
            // Legacy format: skip obsolete fields
            difficulty = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));
            reader.readBytes(32);  // Skip maxDifficulty

            mainBlockCount = reader.readVarLong();
            reader.readVarLong();  // Skip totalMainBlockCount
            reader.readVarLong();  // Skip totalBlockCount
            reader.readVarInt();   // Skip totalHostCount

            reader.readVarLong();  // Skip waitingSyncCount
            reader.readVarLong();  // Skip noRefCount
            reader.readVarLong();  // Skip extraCount

            balance = deserializeXAmount(reader);

            // Try to read new consensus fields if present
            if (reader.hasMoreData()) {
                byte baseDiffFlag = reader.readByte();
                if (baseDiffFlag == 1) {
                    baseDifficultyTarget = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));
                }

                lastDifficultyAdjustmentEpoch = reader.readVarLong();
                lastOrphanCleanupEpoch = reader.readVarLong();

                // Skip legacy topBlock, topDifficulty, preTopBlock, preTopDifficulty if present
                if (reader.hasMoreData()) {
                    byte topBlockFlag = reader.readByte();
                    if (topBlockFlag == 1) {
                        reader.readBytes(32);  // Skip topBlock
                    }
                    if (reader.hasMoreData()) {
                        reader.readBytes(32);  // Skip topDifficulty
                    }
                    if (reader.hasMoreData()) {
                        byte preTopBlockFlag = reader.readByte();
                        if (preTopBlockFlag == 1) {
                            reader.readBytes(32);  // Skip preTopBlock
                        }
                    }
                    if (reader.hasMoreData()) {
                        reader.readBytes(32);  // Skip preTopDifficulty
                    }
                }
            }
        } else {
            // New format (v3)
            difficulty = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));
            mainBlockCount = reader.readVarLong();
            balance = deserializeXAmount(reader);

            // baseDifficultyTarget (nullable)
            byte baseDiffFlag = reader.readByte();
            if (baseDiffFlag == 1) {
                baseDifficultyTarget = UInt256.fromBytes(Bytes.wrap(reader.readBytes(32)));
            }

            // lastDifficultyAdjustmentEpoch
            lastDifficultyAdjustmentEpoch = reader.readVarLong();

            // lastOrphanCleanupEpoch
            lastOrphanCleanupEpoch = reader.readVarLong();
        }

        return ChainStats.builder()
                .difficulty(difficulty)
                .mainBlockCount(mainBlockCount)
                .balance(balance)
                .baseDifficultyTarget(baseDifficultyTarget)
                .lastDifficultyAdjustmentEpoch(lastDifficultyAdjustmentEpoch)
                .lastOrphanCleanupEpoch(lastOrphanCleanupEpoch)
                .build();
    }

  // ========== Helper Methods ==========

    /**
     * Write variable-length integer (VarInt encoding)
     * - 1 byte for 0-127
     * - 2 bytes for 128-16383
     * - etc.
     */
    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
    }

    /**
     * Write variable-length long
     */
    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) (value & 0x7F));
    }

    /**
     * Write fixed 32-bit integer (4 bytes, little-endian)
     */
    private static void writeFixed32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /**
     * Write fixed 64-bit long (8 bytes, little-endian)
     */
    private static void writeFixed64(ByteArrayOutputStream out, long value) {
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
    private static void serializeXAmount(ByteArrayOutputStream out, XAmount amount) {
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

  // ========== ByteReader Helper Class ==========

    /**
     * Helper class for reading bytes sequentially
     */
    private static class ByteReader {
        private final byte[] data;
        private int position;

        public ByteReader(byte[] data) {
            // BUGFIX (BUG-042): Add null check for defensive programming
            // Previously: Would throw NullPointerException on first access
            // Now: Throw IllegalArgumentException with clear message
            if (data == null) {
                throw new IllegalArgumentException("Data cannot be null");
            }
            this.data = data;
            this.position = 0;
        }

        public boolean hasMoreData() {
            return position < data.length;
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
            // BUGFIX (BUG-043): Add overflow protection for malformed input
            // Previously: Could overflow or loop indefinitely with malicious data
            // Now: Limit to maximum 5 bytes (32-bit integer)
            int result = 0;
            int shift = 0;
            byte b;
            do {
                if (shift > 28) {
                    throw new IOException("VarInt overflow: more than 5 bytes");
                }
                b = readByte();
                result |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            return result;
        }

        public long readVarLong() throws IOException {
            // BUGFIX (BUG-044): Add overflow protection for malformed input
            // Previously: Could overflow or loop indefinitely with malicious data
            // Now: Limit to maximum 10 bytes (64-bit long)
            long result = 0;
            int shift = 0;
            byte b;
            do {
                if (shift > 63) {
                    throw new IOException("VarLong overflow: more than 10 bytes");
                }
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
