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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Transaction information DTO
 * Used by both CLI and RPC to return transaction data
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionInfo {

    /**
     * Transaction hash (hex string)
     */
    private String hash;

    /**
     * From address (Base58 format)
     */
    private String from;

    /**
     * To address (Base58 format)
     */
    private String to;

    /**
     * Transaction amount
     */
    private XAmount amount;

    /**
     * Transaction fee
     */
    private XAmount fee;

    /**
     * Transaction nonce
     */
    private long nonce;

    /**
     * Transaction data/remark (UTF-8 decoded if present)
     */
    private String remark;

    /**
     * Transaction signature (full hex string: v, r, s combined)
     */
    private String signature;

    /**
     * Block hash containing this transaction (null if not confirmed)
     */
    private String blockHash;

    /**
     * Block height (null if not confirmed or orphan)
     */
    private Long blockHeight;

    /**
     * Block timestamp in milliseconds (null if not confirmed)
     */
    private Long timestamp;

    /**
     * Epoch number (null if not confirmed)
     */
    private Long epoch;

    /**
     * Confirmation status
     */
    private String status; // "Confirmed (Main)", "Unconfirmed (Orphan)", "Pending"

    /**
     * Transaction validation status
     */
    private boolean isValid;

    /**
     * Signature verification status
     */
    private boolean signatureValid;
}
