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

package io.xdag.api.service.dto;

import io.xdag.core.XAmount;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Block detail DTO (for detailed view)
 * Used by both CLI and RPC to return detailed block information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockDetail {

    /**
     * Block hash (hex string)
     */
    private String hash;

    /**
     * Block height (null if orphan)
     */
    private Long height;

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
     * Block state (Main, Orphan)
     */
    private String state;

    /**
     * Coinbase address (Base58 format)
     */
    private String coinbase;

    /**
     * Block links (references to other blocks)
     */
    private List<LinkInfo> blockLinks;

    /**
     * Transaction links
     */
    private List<String> transactionHashes;

    /**
     * Transactions in this block
     */
    private List<TransactionInfo> transactions;

    /**
     * Total amount transferred in all transactions
     */
    private XAmount totalAmount;

    /**
     * Total fees paid in all transactions
     */
    private XAmount totalFees;

    /**
     * Link information DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LinkInfo {
        /**
         * Linked block hash (full hex string)
         */
        private String hash;

        /**
         * Linked block height (null if not available)
         */
        private Long height;

        /**
         * Linked block epoch (null if not available)
         */
        private Long epoch;
    }
}
