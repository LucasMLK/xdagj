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

package io.xdag.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Block summary DTO (for list display)
 * Used by both CLI and RPC to return block list data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockSummary {

    /**
     * Block hash (hex string)
     */
    private String hash;

    /**
     * Block height
     */
    private long height;

    /**
     * Block timestamp in milliseconds
     */
    private long timestamp;

    /**
     * Epoch number
     */
    private long epoch;

    /**
     * Block difficulty
     */
    private UInt256 difficulty;

    /**
     * Transaction count in this block
     */
    private int transactionCount;

    /**
     * Block state (Main, Orphan)
     */
    private String state;

    /**
     * Coinbase address (Base58 format)
     */
    private String coinbase;
}
