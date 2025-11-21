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

package io.xdag.api.http.response;

import lombok.Builder;
import lombok.Data;

/**
 * Response for xdag_getTransactionByHash - follows RPC API v2 design
 */
@Data
@Builder
public class TransactionDetailResponse {

    /**
     * Transaction hash (hex with 0x prefix)
     */
    private String hash;

    /**
     * From address (Base58)
     */
    private String from;

    /**
     * To address (Base58)
     */
    private String to;

    /**
     * Amount in XDAG units (decimal string)
     */
    private String amount;

    /**
     * Transaction fee in XDAG units (decimal string)
     */
    private String fee;

    /**
     * Nonce (hex with 0x prefix)
     */
    private String nonce;

    /**
     * Transaction data (hex with 0x prefix)
     */
    private String data;

    /**
     * Signature information
     */
    private SignatureInfo signature;

    /**
     * Block number (hex with 0x prefix)
     */
    private String blockNumber;

    /**
     * Block hash (hex with 0x prefix)
     */
    private String blockHash;

    /**
     * Timestamp (hex with 0x prefix)
     */
    private String timestamp;

    /**
     * Transaction status: "confirmed", "pending", or "failed"
     */
    private String status;

    /**
     * Whether transaction is valid
     */
    private boolean valid;

    /**
     * Whether signature is valid
     */
    private boolean signatureValid;

    @Data
    @Builder
    public static class SignatureInfo {

        /**
         * V value (hex with 0x prefix)
         */
        private String v;

        /**
         * R value (hex with 0x prefix)
         */
        private String r;

        /**
         * S value (hex with 0x prefix)
         */
        private String s;
    }
}
