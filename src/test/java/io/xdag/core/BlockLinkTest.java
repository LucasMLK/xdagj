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

import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Test class for BlockLink
 */
public class BlockLinkTest {

    @Test
    public void testReferenceLink() {
        Bytes32 targetHash = Bytes32.random();
        BlockLink link = BlockLink.reference(targetHash);

        assertNotNull(link);
        assertEquals(targetHash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.REFERENCE, link.getLinkType());
        assertNull(link.getAmount());
        assertNull(link.getRemark());
        assertTrue(link.isReference());
        assertFalse(link.isInput());
        assertFalse(link.isOutput());
        assertFalse(link.hasAmount());
        assertFalse(link.hasRemark());
        assertEquals(0, link.getDirection());
    }

    @Test
    public void testInputLink() {
        Bytes32 targetHash = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);
        BlockLink link = BlockLink.input(targetHash, amount);

        assertNotNull(link);
        assertEquals(targetHash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.INPUT, link.getLinkType());
        assertEquals(amount, link.getAmount());
        assertNull(link.getRemark());
        assertTrue(link.isInput());
        assertFalse(link.isOutput());
        assertFalse(link.isReference());
        assertTrue(link.hasAmount());
        assertFalse(link.hasRemark());
        assertEquals(-1, link.getDirection());
    }

    @Test
    public void testInputLinkWithRemark() {
        Bytes32 targetHash = Bytes32.random();
        XAmount amount = XAmount.of(50, XUnit.XDAG);
        String remark = "Test payment";
        BlockLink link = BlockLink.input(targetHash, amount, remark);

        assertNotNull(link);
        assertEquals(targetHash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.INPUT, link.getLinkType());
        assertEquals(amount, link.getAmount());
        assertEquals(remark, link.getRemark());
        assertTrue(link.isInput());
        assertTrue(link.hasAmount());
        assertTrue(link.hasRemark());
        assertEquals(-1, link.getDirection());
    }

    @Test
    public void testOutputLink() {
        Bytes32 targetHash = Bytes32.random();
        XAmount amount = XAmount.of(200, XUnit.XDAG);
        BlockLink link = BlockLink.output(targetHash, amount);

        assertNotNull(link);
        assertEquals(targetHash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.OUTPUT, link.getLinkType());
        assertEquals(amount, link.getAmount());
        assertNull(link.getRemark());
        assertTrue(link.isOutput());
        assertFalse(link.isInput());
        assertFalse(link.isReference());
        assertTrue(link.hasAmount());
        assertFalse(link.hasRemark());
        assertEquals(1, link.getDirection());
    }

    @Test
    public void testOutputLinkWithRemark() {
        Bytes32 targetHash = Bytes32.random();
        XAmount amount = XAmount.of(75, XUnit.XDAG);
        String remark = "Refund";
        BlockLink link = BlockLink.output(targetHash, amount, remark);

        assertNotNull(link);
        assertEquals(targetHash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.OUTPUT, link.getLinkType());
        assertEquals(amount, link.getAmount());
        assertEquals(remark, link.getRemark());
        assertTrue(link.isOutput());
        assertTrue(link.hasAmount());
        assertTrue(link.hasRemark());
        assertEquals(1, link.getDirection());
    }

    @Test
    public void testHasAmount() {
        // Link with amount
        BlockLink withAmount = BlockLink.input(Bytes32.random(), XAmount.of(10, XUnit.XDAG));
        assertTrue(withAmount.hasAmount());

        // Link with zero amount
        BlockLink zeroAmount = BlockLink.input(Bytes32.random(), XAmount.ZERO);
        assertFalse(zeroAmount.hasAmount());

        // Reference link (null amount)
        BlockLink noAmount = BlockLink.reference(Bytes32.random());
        assertFalse(noAmount.hasAmount());
    }

    @Test
    public void testHasRemark() {
        // Link with remark
        BlockLink withRemark = BlockLink.input(Bytes32.random(), XAmount.of(10, XUnit.XDAG), "Test");
        assertTrue(withRemark.hasRemark());

        // Link with empty remark
        BlockLink emptyRemark = BlockLink.input(Bytes32.random(), XAmount.of(10, XUnit.XDAG), "");
        assertFalse(emptyRemark.hasRemark());

        // Link without remark
        BlockLink noRemark = BlockLink.input(Bytes32.random(), XAmount.of(10, XUnit.XDAG));
        assertFalse(noRemark.hasRemark());
    }

    @Test
    public void testLinkTypeConversion() {
        // Test INPUT
        assertEquals(0, BlockLink.LinkType.INPUT.toByte());
        assertEquals(BlockLink.LinkType.INPUT, BlockLink.LinkType.fromByte((byte) 0));

        // Test OUTPUT
        assertEquals(1, BlockLink.LinkType.OUTPUT.toByte());
        assertEquals(BlockLink.LinkType.OUTPUT, BlockLink.LinkType.fromByte((byte) 1));

        // Test REFERENCE
        assertEquals(2, BlockLink.LinkType.REFERENCE.toByte());
        assertEquals(BlockLink.LinkType.REFERENCE, BlockLink.LinkType.fromByte((byte) 2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidLinkType() {
        BlockLink.LinkType.fromByte((byte) 99);
    }

    @Test
    public void testToString() {
        Bytes32 hash = Bytes32.fromHexString("0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");
        XAmount amount = XAmount.of(100, XUnit.XDAG);

        BlockLink referenceLink = BlockLink.reference(hash);
        String refStr = referenceLink.toString();
        assertTrue(refStr.contains("BlockLink"));
        assertTrue(refStr.contains("type=REFERENCE"));
        assertTrue(refStr.contains("target="));
        assertFalse(refStr.contains("amount="));

        BlockLink inputLink = BlockLink.input(hash, amount, "Payment");
        String inputStr = inputLink.toString();
        assertTrue(inputStr.contains("type=INPUT"));
        assertTrue(inputStr.contains("amount="));
        assertTrue(inputStr.contains("remark='Payment'"));

        BlockLink outputLink = BlockLink.output(hash, amount);
        String outputStr = outputLink.toString();
        assertTrue(outputStr.contains("type=OUTPUT"));
        assertTrue(outputStr.contains("amount="));
        assertFalse(outputStr.contains("remark="));
    }

    @Test
    public void testEquality() {
        Bytes32 hash = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);

        BlockLink link1 = BlockLink.input(hash, amount, "Test");
        BlockLink link2 = BlockLink.input(hash, amount, "Test");

        assertEquals(link1, link2);
        assertEquals(link1.hashCode(), link2.hashCode());
    }

    @Test
    public void testInequality() {
        Bytes32 hash1 = Bytes32.random();
        Bytes32 hash2 = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);

        BlockLink link1 = BlockLink.input(hash1, amount);
        BlockLink link2 = BlockLink.input(hash2, amount);

        assertNotEquals(link1, link2);
    }

    @Test
    public void testDifferentTypesNotEqual() {
        Bytes32 hash = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);

        BlockLink inputLink = BlockLink.input(hash, amount);
        BlockLink outputLink = BlockLink.output(hash, amount);
        BlockLink refLink = BlockLink.reference(hash);

        assertNotEquals(inputLink, outputLink);
        assertNotEquals(inputLink, refLink);
        assertNotEquals(outputLink, refLink);
    }

    @Test
    public void testImmutability() {
        Bytes32 hash = Bytes32.random();
        XAmount amount = XAmount.of(100, XUnit.XDAG);

        BlockLink link = BlockLink.input(hash, amount, "Original");

        // Verify all getters return the same values
        assertEquals(hash, link.getTargetHash());
        assertEquals(BlockLink.LinkType.INPUT, link.getLinkType());
        assertEquals(amount, link.getAmount());
        assertEquals("Original", link.getRemark());

        // BlockLink is immutable (@Value), so no setters exist
        // This test verifies the object maintains its state
    }
}
