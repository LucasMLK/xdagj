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

import lombok.Builder;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;

/**
 * BlockSubmitResult - Result of submitting a mined block to the node
 *
 * <p>This class encapsulates the response from the mining API's submitMinedBlock method.
 * It indicates whether the block was accepted and provides details about the result.
 *
 * <h2>Result Types</h2>
 * <ul>
 *   <li><strong>ACCEPTED</strong>: Block validated and imported to blockchain</li>
 *   <li><strong>REJECTED</strong>: Block validation failed</li>
 * </ul>
 *
 * <h2>Common Rejection Reasons</h2>
 * <ul>
 *   <li>"Unknown candidate block" - Block not based on known candidate</li>
 *   <li>"Invalid PoW" - Nonce doesn't produce valid hash</li>
 *   <li>"Timestamp out of bounds" - Block timestamp invalid for epoch</li>
 *   <li>"Invalid links" - Block references unknown or invalid blocks</li>
 *   <li>"Duplicate block" - Block already imported</li>
 * </ul>
 *
 * @since XDAGJ v5.1
 */
@Data
@Builder
public class BlockSubmitResult {

    /**
     * Whether the block was accepted
     */
    private final boolean accepted;

    /**
     * Hash of the submitted block (for reference)
     */
    private final Bytes32 blockHash;

    /**
     * Human-readable message describing the result
     */
    private final String message;

    /**
     * Error code (null if accepted)
     */
    private final String errorCode;

    /**
     * Create an accepted result
     *
     * @param blockHash Hash of the accepted block
     * @return BlockSubmitResult indicating acceptance
     */
    public static BlockSubmitResult accepted(Bytes32 blockHash) {
        return BlockSubmitResult.builder()
                .accepted(true)
                .blockHash(blockHash)
                .message("Block accepted and imported successfully")
                .errorCode(null)
                .build();
    }

    /**
     * Create a rejected result
     *
     * @param reason Human-readable rejection reason
     * @return BlockSubmitResult indicating rejection
     */
    public static BlockSubmitResult rejected(String reason) {
        return BlockSubmitResult.builder()
                .accepted(false)
                .blockHash(null)
                .message(reason)
                .errorCode("REJECTED")
                .build();
    }

    /**
     * Create a rejected result with error code
     *
     * @param reason Human-readable rejection reason
     * @param errorCode Machine-readable error code
     * @return BlockSubmitResult indicating rejection
     */
    public static BlockSubmitResult rejected(String reason, String errorCode) {
        return BlockSubmitResult.builder()
                .accepted(false)
                .blockHash(null)
                .message(reason)
                .errorCode(errorCode)
                .build();
    }
}
