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

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class XdagRandomUtilsTest {

    @Test
    public void testNextInt_withPositiveBound() {
        int bound = 100;
        for (int i = 0; i < 1000; i++) {
            int randomNumber = XdagRandomUtils.nextInt(bound);
            assertTrue("Random number should be non-negative", randomNumber >= 0);
            assertTrue("Random number should be less than bound", randomNumber < bound);
        }
    }

    @Test
    public void testNextInt_withBoundOne() {
        int bound = 1;
        for (int i = 0; i < 100; i++) {
            int randomNumber = XdagRandomUtils.nextInt(bound);
            assertEquals("Random number should be 0 when bound is 1", 0, randomNumber);
        }
    }

    @Test
    public void testNextInt_generatesDifferentValues() {
        int bound = 100;
        Set<Integer> values = new HashSet<>();
        // It's statistically very likely to get at least 10 different values out of 50 tries for a bound of 100.
        // This is not a perfect test for randomness but a basic check.
        for (int i = 0; i < 50 && values.size() < 10; i++) {
            values.add(XdagRandomUtils.nextInt(bound));
        }
        assertTrue("Expected to generate multiple different values for nextInt", values.size() > 1 || bound <= 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNextInt_throwsExceptionForZeroBound() {
        XdagRandomUtils.nextInt(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNextInt_throwsExceptionForNegativeBound() {
        XdagRandomUtils.nextInt(-5);
    }


    @Test
    public void testNextLong_withPositiveBound() {
        long bound = 200L;
        for (int i = 0; i < 1000; i++) {
            long randomNumber = XdagRandomUtils.nextLong(bound);
            assertTrue("Random long should be non-negative", randomNumber >= 0);
            assertTrue("Random long should be less than bound", randomNumber < bound);
        }
    }

    @Test
    public void testNextLong_withBoundOne() {
        long bound = 1L;
        for (int i = 0; i < 100; i++) {
            long randomNumber = XdagRandomUtils.nextLong(bound);
            assertEquals("Random long should be 0 when bound is 1", 0L, randomNumber);
        }
    }

    @Test
    public void testNextLong_generatesDifferentValues() {
        long bound = 200L;
        Set<Long> values = new HashSet<>();
        for (int i = 0; i < 50 && values.size() < 10; i++) {
            values.add(XdagRandomUtils.nextLong(bound));
        }
        assertTrue("Expected to generate multiple different values for nextLong", values.size() > 1 || bound <= 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNextLong_throwsExceptionForZeroBound() {
        XdagRandomUtils.nextLong(0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNextLong_throwsExceptionForNegativeBound() {
        XdagRandomUtils.nextLong(-10L);
    }


    @Test
    public void testNextNewBytes_returnsCorrectLength() {
        assertEquals(0, XdagRandomUtils.nextNewBytes(0).length);
        assertEquals(10, XdagRandomUtils.nextNewBytes(10).length);
        assertEquals(128, XdagRandomUtils.nextNewBytes(128).length);
    }

    @Test
    public void testNextNewBytes_returnsNonNull() {
        assertNotNull(XdagRandomUtils.nextNewBytes(0));
        assertNotNull(XdagRandomUtils.nextNewBytes(10));
    }
    
    @Test
    public void testNextNewBytes_withZeroCount() {
        byte[] bytes = XdagRandomUtils.nextNewBytes(0);
        assertNotNull(bytes);
        assertEquals(0, bytes.length);
    }

    @Test
    public void testNextNewBytes_generatesDifferentValues() {
        byte[] bytes1 = XdagRandomUtils.nextNewBytes(16);
        byte[] bytes2 = XdagRandomUtils.nextNewBytes(16);
        assertNotNull(bytes1);
        assertNotNull(bytes2);
        assertEquals(16, bytes1.length);
        assertEquals(16, bytes2.length);
        assertFalse("Expected different byte arrays from subsequent calls", Arrays.equals(bytes1, bytes2));
    }
    
    @Test(expected = NegativeArraySizeException.class)
    public void testNextNewBytes_throwsExceptionForNegativeCount() {
        XdagRandomUtils.nextNewBytes(-1);
    }


    @Test
    public void testGenerateRandomBytes_returnsCorrectLength() {
        assertEquals(0, XdagRandomUtils.generateRandomBytes(0).length);
        assertEquals(10, XdagRandomUtils.generateRandomBytes(10).length);
        assertEquals(256, XdagRandomUtils.generateRandomBytes(256).length);
    }

    @Test
    public void testGenerateRandomBytes_returnsNonNull() {
        assertNotNull(XdagRandomUtils.generateRandomBytes(0));
        assertNotNull(XdagRandomUtils.generateRandomBytes(10));
    }
    
    @Test
    public void testGenerateRandomBytes_withZeroCount() {
        byte[] bytes = XdagRandomUtils.generateRandomBytes(0);
        assertNotNull(bytes);
        assertEquals(0, bytes.length);
    }

    @Test
    public void testGenerateRandomBytes_generatesDifferentValues() {
        byte[] bytes1 = XdagRandomUtils.generateRandomBytes(16);
        byte[] bytes2 = XdagRandomUtils.generateRandomBytes(16);
        assertNotNull(bytes1);
        assertNotNull(bytes2);
        assertEquals(16, bytes1.length);
        assertEquals(16, bytes2.length);
        // This could theoretically fail with an extremely low probability if SecureRandom produces the same sequence.
        // For a unit test, this is generally an acceptable risk.
        assertFalse("Expected different byte arrays from subsequent calls to generateRandomBytes", Arrays.equals(bytes1, bytes2));
    }
    
     @Test(expected = NegativeArraySizeException.class)
    public void testGenerateRandomBytes_throwsExceptionForNegativeCount() {
        // SecureRandom.nextBytes(byte[]) will be called with new byte[count]
        // which throws NegativeArraySizeException if count is negative.
        XdagRandomUtils.generateRandomBytes(-1);
    }

} 