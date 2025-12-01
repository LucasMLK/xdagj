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

package io.xdag.config;

/**
 * Constants related to RandomX mining algorithm implementation
 */
public class RandomXConstants {

  /**
   * Number of blocks in one epoch for mainnet
   */
  public static final long SEEDHASH_EPOCH_BLOCKS = 4096;

  /**
   * Lag in blocks for seed hash calculation in mainnet
   */
  public static final long SEEDHASH_EPOCH_LAG = 128;

  /**
   * Block height at which RandomX activates on mainnet Set to 0 because xdagj only supports RandomX
   * from genesis (no fork needed)
   */
  public static final long RANDOMX_FORK_HEIGHT = 0;

  /**
   * Number of blocks in one epoch for testnet/devnet Set to 2 for fast devnet testing (seed updates
   * every 2 blocks)
   */
  public static long SEEDHASH_EPOCH_TESTNET_BLOCKS = 2;

  /**
   * Lag in blocks for seed hash calculation in testnet/devnet Set to 1 for devnet (minimal lag)
   */
  public static long SEEDHASH_EPOCH_TESTNET_LAG = 1;

  /**
   * Block height at which RandomX activates on testnet/devnet Set to 0 because xdagj only supports
   * RandomX from genesis (no fork needed)
   */
  public static long RANDOMX_TESTNET_FORK_HEIGHT = 0;

}
