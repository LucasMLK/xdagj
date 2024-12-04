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

import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.simple.RandomSource;

/**
 * Utility class for generating random numbers and bytes using XO-RO-SHI-RO-128-PP algorithm
 */
public class XdagRandomUtils {

    /**
     * Random number generator instance using XO-RO-SHI-RO-128-PP algorithm
     */
    private final static UniformRandomProvider rng = RandomSource.XO_RO_SHI_RO_128_PP.create();

    /**
     * Generate a random integer
     * @return Random integer value
     */
    public static int nextInt() {
        return rng.nextInt();
    }

    /**
     * Generate a random integer between 0 (inclusive) and n (exclusive)
     * @param n Upper bound (exclusive)
     * @return Random integer value in [0, n)
     */
    public static int nextInt(int n) {
        return rng.nextInt(n);
    }

    /**
     * Generate a random long value
     * @return Random long value
     */
    public static long nextLong() {
        return rng.nextLong();
    }

    /**
     * Generate a random long between 0 (inclusive) and n (exclusive)
     * @param n Upper bound (exclusive)
     * @return Random long value in [0, n)
     */
    public static long nextLong(long n) {
        return rng.nextLong(n);
    }

    /**
     * Fill the given byte array with random bytes
     * @param bytes Byte array to fill with random values
     */
    public static void nextBytes(byte[] bytes) {
        rng.nextBytes(bytes);
    }

    /**
     * Fill a portion of the given byte array with random bytes
     * @param bytes Byte array to fill
     * @param start Starting position
     * @param len Number of bytes to fill
     */
    public static void nextBytes(byte[] bytes, int start, int len) {
        rng.nextBytes(bytes, start, len);
    }

    /**
     * Create and return a new byte array filled with random bytes
     * @param count Size of the byte array to create
     * @return New byte array filled with random values
     */
    public static byte[] nextNewBytes(int count) {
        final byte[] result = new byte[count];
        rng.nextBytes(result);
        return result;
    }
}
