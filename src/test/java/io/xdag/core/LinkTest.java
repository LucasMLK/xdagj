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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

/**
 * Unit tests for Link (v5.1)
 */
public class LinkTest {

    @Test
    public void testLinkSize() {
        assertEquals(33, Link.LINK_SIZE);
    }

    @Test
    public void testTransactionLink() {
        Bytes32 txHash = Bytes32.random();
        Link link = Link.toTransaction(txHash);

        assertEquals(txHash, link.getTargetHash());
        assertEquals(Link.LinkType.TRANSACTION, link.getType());
        assertTrue(link.isTransaction());
        assertFalse(link.isBlock());
    }

    @Test
    public void testBlockLink() {
        Bytes32 blockHash = Bytes32.random();
        Link link = Link.toBlock(blockHash);

        assertEquals(blockHash, link.getTargetHash());
        assertEquals(Link.LinkType.BLOCK, link.getType());
        assertFalse(link.isTransaction());
        assertTrue(link.isBlock());
    }

    @Test
    public void testLinkSerialization() {
        Bytes32 hash = Bytes32.random();
        Link original = Link.toTransaction(hash);

        byte[] bytes = original.toBytes();
        assertEquals(33, bytes.length);

        Link deserialized = Link.fromBytes(bytes);
        assertEquals(original, deserialized);
        assertEquals(original.getTargetHash(), deserialized.getTargetHash());
        assertEquals(original.getType(), deserialized.getType());
    }

    @Test
    public void testLinkTypeFromByte() {
        assertEquals(Link.LinkType.TRANSACTION, Link.LinkType.fromByte((byte) 0));
        assertEquals(Link.LinkType.BLOCK, Link.LinkType.fromByte((byte) 1));

        assertThrows(IllegalArgumentException.class, () -> Link.LinkType.fromByte((byte) 2));
        assertThrows(IllegalArgumentException.class, () -> Link.LinkType.fromByte((byte) -1));
    }

    @Test
    public void testInvalidLinkBytes() {
        byte[] tooShort = new byte[32];
        byte[] tooLong = new byte[34];

        assertThrows(IllegalArgumentException.class, () -> Link.fromBytes(tooShort));
        assertThrows(IllegalArgumentException.class, () -> Link.fromBytes(tooLong));
    }

    @Test
    public void testLinkEquality() {
        Bytes32 hash1 = Bytes32.random();
        Bytes32 hash2 = Bytes32.random();

        Link link1a = Link.toTransaction(hash1);
        Link link1b = Link.toTransaction(hash1);
        Link link2 = Link.toTransaction(hash2);
        Link link3 = Link.toBlock(hash1);

        assertEquals(link1a, link1b);
        assertNotEquals(link1a, link2);
        assertNotEquals(link1a, link3);  // Same hash but different type
    }

    @Test
    public void testLinkToString() {
        Bytes32 hash = Bytes32.random();
        Link txLink = Link.toTransaction(hash);
        Link blockLink = Link.toBlock(hash);

        String txStr = txLink.toString();
        String blockStr = blockLink.toString();

        assertTrue(txStr.contains("TRANSACTION"));
        assertTrue(blockStr.contains("BLOCK"));
        assertTrue(txStr.contains(hash.toHexString().substring(0, 16)));
    }
}
