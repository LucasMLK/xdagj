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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BytesUtilsTest {

    @Test
    public void testIntToBytesAndBack() {
        int value = 123456789;
        
        // Big Endian
        byte[] bytesBig = BytesUtils.intToBytes(value, false);
        assertEquals(4, bytesBig.length);
        assertEquals(value, BytesUtils.bytesToInt(bytesBig, 0, false));

        // Little Endian
        byte[] bytesLittle = BytesUtils.intToBytes(value, true);
        assertEquals(4, bytesLittle.length);
        assertEquals(value, BytesUtils.bytesToInt(bytesLittle, 0, true));
    }

    @Test
    public void testLongToBytesAndBack() {
        long value = 1234567890123456789L;

        // Big Endian
        byte[] bytesBig = BytesUtils.longToBytes(value, false);
        assertEquals(8, bytesBig.length);
        assertEquals(value, BytesUtils.bytesToLong(bytesBig, 0, false));

        // Little Endian
        byte[] bytesLittle = BytesUtils.longToBytes(value, true);
        assertEquals(8, bytesLittle.length);
        assertEquals(value, BytesUtils.bytesToLong(bytesLittle, 0, true));
    }

    @Test
    public void testMergeVarargs() {
        byte[] a = new byte[]{1, 2};
        byte[] b = new byte[]{3, 4};
        byte[] c = new byte[]{5};

        byte[] result = BytesUtils.merge(a, b, c);
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
        
        // Test empty
        assertArrayEquals(new byte[]{}, BytesUtils.merge());
    }

    @Test
    public void testMergeTwoArrays() {
        byte b1 = 1;
        byte[] b2 = new byte[]{2, 3};

        byte[] result = BytesUtils.merge(b1, b2);
        assertArrayEquals(new byte[]{1, 2, 3}, result);
    }

    @Test
    public void testSubArray() {
        byte[] src = new byte[]{1, 2, 3, 4, 5};

        assertArrayEquals(new byte[]{2, 3}, BytesUtils.subArray(src, 1, 2));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, BytesUtils.subArray(src, 0, 5));
        assertArrayEquals(new byte[]{}, BytesUtils.subArray(src, 0, 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubArrayNull() {
        BytesUtils.subArray(null, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubArrayNegativeIndex() {
        BytesUtils.subArray(new byte[]{1}, -1, 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSubArrayNegativeLength() {
        BytesUtils.subArray(new byte[]{1}, 0, -1);
    }

    @Test(expected = ArrayIndexOutOfBoundsException.class)
    public void testSubArrayOutOfBounds() {
        BytesUtils.subArray(new byte[]{1}, 0, 2);
    }

    @Test
    public void testHexStringToBytes() {
        String hex = "010203";
        byte[] expected = new byte[]{1, 2, 3};
        assertArrayEquals(expected, BytesUtils.hexStringToBytes(hex));
        
        // Case insensitive
        hex = "01020A";
        expected = new byte[]{1, 2, 10};
        assertArrayEquals(expected, BytesUtils.hexStringToBytes(hex));
        
        // With prefix (Tuweni supports this?) 
        // Based on my refactoring using Bytes.fromHexString, it handles optional 0x prefix.
        hex = "0x0102";
        expected = new byte[]{1, 2};
        assertArrayEquals(expected, BytesUtils.hexStringToBytes(hex));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHexStringToBytesOddLength() {
        BytesUtils.hexStringToBytes("123");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHexStringToBytesInvalidChar() {
        BytesUtils.hexStringToBytes("01020G");
    }
}
