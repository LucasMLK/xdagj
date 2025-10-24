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

import org.apache.tuweni.bytes.Bytes;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test class for Snapshot
 */
public class SnapshotTest {

    @Test
    public void testBuilderPattern() {
        Bytes data = Bytes.random(32);
        Snapshot snapshot = Snapshot.builder()
                .type(Snapshot.SnapshotType.PUBLIC_KEY)
                .data(data)
                .build();

        assertNotNull(snapshot);
        assertEquals(Snapshot.SnapshotType.PUBLIC_KEY, snapshot.getType());
        assertEquals(data, snapshot.getData());
    }

    @Test
    public void testPublicKeyFactory() {
        Bytes publicKey = Bytes.random(32);
        Snapshot snapshot = Snapshot.publicKey(publicKey);

        assertNotNull(snapshot);
        assertTrue(snapshot.isPublicKey());
        assertFalse(snapshot.isBlockData());
        assertEquals(publicKey, snapshot.getData());
        assertTrue(snapshot.hasData());
    }

    @Test
    public void testBlockDataFactory() {
        Bytes blockData = Bytes.random(32);
        Snapshot snapshot = Snapshot.blockData(blockData);

        assertNotNull(snapshot);
        assertTrue(snapshot.isBlockData());
        assertFalse(snapshot.isPublicKey());
        assertEquals(blockData, snapshot.getData());
        assertTrue(snapshot.hasData());
    }

    @Test
    public void testFromLegacyPublicKey() {
        byte[] publicKeyData = new byte[32];
        for (int i = 0; i < 32; i++) {
            publicKeyData[i] = (byte) i;
        }

        SnapshotInfo legacy = new SnapshotInfo(true, publicKeyData);
        Snapshot snapshot = Snapshot.fromLegacy(legacy);

        assertNotNull(snapshot);
        assertTrue(snapshot.isPublicKey());
        assertArrayEquals(publicKeyData, snapshot.getData().toArray());
    }

    @Test
    public void testFromLegacyBlockData() {
        byte[] blockData = new byte[32];
        for (int i = 0; i < 32; i++) {
            blockData[i] = (byte) (i * 2);
        }

        SnapshotInfo legacy = new SnapshotInfo(false, blockData);
        Snapshot snapshot = Snapshot.fromLegacy(legacy);

        assertNotNull(snapshot);
        assertTrue(snapshot.isBlockData());
        assertArrayEquals(blockData, snapshot.getData().toArray());
    }

    @Test
    public void testFromLegacyNull() {
        Snapshot snapshot = Snapshot.fromLegacy(null);
        assertNull(snapshot);
    }

    @Test
    public void testToLegacyPublicKey() {
        Bytes publicKey = Bytes.wrap(new byte[32]);
        Snapshot snapshot = Snapshot.publicKey(publicKey);

        SnapshotInfo legacy = snapshot.toLegacy();

        assertNotNull(legacy);
        assertTrue(legacy.getType());  // true for public key
        assertArrayEquals(publicKey.toArray(), legacy.getData());
    }

    @Test
    public void testToLegacyBlockData() {
        Bytes blockData = Bytes.wrap(new byte[32]);
        Snapshot snapshot = Snapshot.blockData(blockData);

        SnapshotInfo legacy = snapshot.toLegacy();

        assertNotNull(legacy);
        assertFalse(legacy.getType());  // false for block data
        assertArrayEquals(blockData.toArray(), legacy.getData());
    }

    @Test
    public void testRoundTripConversion() {
        // Create legacy
        byte[] originalData = new byte[32];
        for (int i = 0; i < 32; i++) {
            originalData[i] = (byte) (i * 3);
        }
        SnapshotInfo originalLegacy = new SnapshotInfo(true, originalData);

        // Legacy -> Snapshot -> Legacy
        Snapshot snapshot = Snapshot.fromLegacy(originalLegacy);
        SnapshotInfo convertedLegacy = snapshot.toLegacy();

        // Verify consistency
        assertEquals(originalLegacy.getType(), convertedLegacy.getType());
        assertArrayEquals(originalLegacy.getData(), convertedLegacy.getData());
    }

    @Test
    public void testHasData() {
        // With data
        Snapshot withData = Snapshot.publicKey(Bytes.random(32));
        assertTrue(withData.hasData());

        // Empty data
        Snapshot emptyData = Snapshot.publicKey(Bytes.EMPTY);
        assertFalse(emptyData.hasData());
    }

    @Test
    public void testGetDataSize() {
        // 32 bytes
        Snapshot snapshot32 = Snapshot.publicKey(Bytes.random(32));
        assertEquals(32, snapshot32.getDataSize());

        // 16 bytes
        Snapshot snapshot16 = Snapshot.blockData(Bytes.random(16));
        assertEquals(16, snapshot16.getDataSize());

        // Empty
        Snapshot snapshotEmpty = Snapshot.publicKey(Bytes.EMPTY);
        assertEquals(0, snapshotEmpty.getDataSize());
    }

    @Test
    public void testGetCompactSize() {
        // 32 bytes data = 1 (type) + 1 (length) + 32 (data) = 34 bytes
        Snapshot snapshot32 = Snapshot.publicKey(Bytes.random(32));
        assertEquals(34, snapshot32.getCompactSize());

        // 16 bytes data = 1 + 1 + 16 = 18 bytes
        Snapshot snapshot16 = Snapshot.blockData(Bytes.random(16));
        assertEquals(18, snapshot16.getCompactSize());

        // Empty data = 1 + 1 + 0 = 2 bytes
        Snapshot snapshotEmpty = Snapshot.publicKey(Bytes.EMPTY);
        assertEquals(2, snapshotEmpty.getCompactSize());
    }

    @Test
    public void testImmutability() {
        Bytes originalData = Bytes.random(32);
        Snapshot original = Snapshot.publicKey(originalData);

        // Use @With to create modified copy
        Bytes newData = Bytes.random(32);
        Snapshot modified = original.withData(newData);

        // Verify original unchanged
        assertEquals(originalData, original.getData());
        assertEquals(Snapshot.SnapshotType.PUBLIC_KEY, original.getType());

        // Verify new instance modified
        assertEquals(newData, modified.getData());
        assertEquals(Snapshot.SnapshotType.PUBLIC_KEY, modified.getType());
    }

    @Test
    public void testToBuilder() {
        Snapshot original = Snapshot.publicKey(Bytes.random(32));

        Snapshot modified = original.toBuilder()
                .type(Snapshot.SnapshotType.BLOCK_DATA)
                .data(Bytes.random(16))
                .build();

        // Verify original unchanged
        assertTrue(original.isPublicKey());
        assertEquals(32, original.getDataSize());

        // Verify new instance modified
        assertTrue(modified.isBlockData());
        assertEquals(16, modified.getDataSize());
    }

    @Test
    public void testSnapshotTypeConversion() {
        // Test PUBLIC_KEY
        assertEquals(0, Snapshot.SnapshotType.PUBLIC_KEY.toByte());
        assertEquals(Snapshot.SnapshotType.PUBLIC_KEY, Snapshot.SnapshotType.fromByte((byte) 0));
        assertTrue(Snapshot.SnapshotType.PUBLIC_KEY.toLegacyBoolean());
        assertEquals(Snapshot.SnapshotType.PUBLIC_KEY, Snapshot.SnapshotType.fromLegacyBoolean(true));

        // Test BLOCK_DATA
        assertEquals(1, Snapshot.SnapshotType.BLOCK_DATA.toByte());
        assertEquals(Snapshot.SnapshotType.BLOCK_DATA, Snapshot.SnapshotType.fromByte((byte) 1));
        assertFalse(Snapshot.SnapshotType.BLOCK_DATA.toLegacyBoolean());
        assertEquals(Snapshot.SnapshotType.BLOCK_DATA, Snapshot.SnapshotType.fromLegacyBoolean(false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidSnapshotType() {
        Snapshot.SnapshotType.fromByte((byte) 99);
    }

    @Test
    public void testToString() {
        Snapshot snapshot = Snapshot.publicKey(Bytes.random(32));
        String str = snapshot.toString();

        assertNotNull(str);
        assertTrue(str.contains("Snapshot"));
        assertTrue(str.contains("type=PUBLIC_KEY"));
        assertTrue(str.contains("dataSize=32"));
    }

    @Test
    public void testEquality() {
        Bytes data = Bytes.random(32);

        Snapshot snapshot1 = Snapshot.publicKey(data);
        Snapshot snapshot2 = Snapshot.publicKey(data);

        assertEquals(snapshot1, snapshot2);
        assertEquals(snapshot1.hashCode(), snapshot2.hashCode());
    }

    @Test
    public void testInequality() {
        Snapshot snapshot1 = Snapshot.publicKey(Bytes.random(32));
        Snapshot snapshot2 = Snapshot.publicKey(Bytes.random(32));

        assertNotEquals(snapshot1, snapshot2);
    }

    @Test
    public void testDifferentTypesNotEqual() {
        Bytes data = Bytes.random(32);

        Snapshot publicKeySnapshot = Snapshot.publicKey(data);
        Snapshot blockDataSnapshot = Snapshot.blockData(data);

        assertNotEquals(publicKeySnapshot, blockDataSnapshot);
    }

    @Test
    public void testEmptySnapshot() {
        Snapshot empty = Snapshot.publicKey(Bytes.EMPTY);

        assertTrue(empty.isPublicKey());
        assertFalse(empty.hasData());
        assertEquals(0, empty.getDataSize());
        assertEquals(2, empty.getCompactSize());  // 1 + 1 + 0
    }

    @Test
    public void testLargeSnapshot() {
        // Test with larger data (e.g., 64 bytes)
        Bytes largeData = Bytes.random(64);
        Snapshot large = Snapshot.blockData(largeData);

        assertTrue(large.isBlockData());
        assertTrue(large.hasData());
        assertEquals(64, large.getDataSize());
        assertEquals(66, large.getCompactSize());  // 1 + 1 + 64
    }
}
