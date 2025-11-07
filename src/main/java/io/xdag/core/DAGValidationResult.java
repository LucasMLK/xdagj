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

import lombok.Builder;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;

/**
 * DAG validation result for XDAG v5.1
 *
 * <p>Contains the result of DAG structure validation with detailed error information.
 *
 * <p>DAG validation rules:
 * <ul>
 *   <li><strong>No cycles</strong>: Block cannot create a cycle in DAG</li>
 *   <li><strong>Time window</strong>: Can only reference blocks within 12 days (16384 epochs)</li>
 *   <li><strong>Link limits</strong>: Must have 1-16 block links</li>
 *   <li><strong>Timestamp order</strong>: All referenced blocks must have earlier timestamps</li>
 *   <li><strong>Traversal depth</strong>: Path depth from genesis must not exceed 1000 layers</li>
 * </ul>
 *
 * @since v5.1
 */
@Data
@Builder
public class DAGValidationResult {

    /** Validation passed */
    private final boolean valid;

    /** Error code if validation failed */
    private final DAGErrorCode errorCode;

    /** Human-readable error message */
    private final String errorMessage;

    /** Block hash that caused the error (if applicable) */
    private final Bytes32 conflictingBlockHash;

    /**
     * DAG validation error codes
     */
    public enum DAGErrorCode {
        /** Validation passed */
        VALID,

        /** Cycle detected in DAG */
        CYCLE_DETECTED,

        /** Referenced block is too old (>16384 epochs) */
        TIME_WINDOW_VIOLATION,

        /** Invalid number of block links (not in 1-16 range) */
        INVALID_LINK_COUNT,

        /** Referenced block has later or equal timestamp */
        TIMESTAMP_ORDER_VIOLATION,

        /** Path depth from genesis exceeds 1000 layers */
        TRAVERSAL_DEPTH_EXCEEDED
    }

    /**
     * Create a valid result
     */
    public static DAGValidationResult valid() {
        return DAGValidationResult.builder()
                .valid(true)
                .errorCode(DAGErrorCode.VALID)
                .build();
    }

    /**
     * Create an invalid result with error code and message
     */
    public static DAGValidationResult invalid(DAGErrorCode code, String message) {
        return DAGValidationResult.builder()
                .valid(false)
                .errorCode(code)
                .errorMessage(message)
                .build();
    }

    /**
     * Create an invalid result with error code, message, and conflicting block
     */
    public static DAGValidationResult invalid(DAGErrorCode code, String message, Bytes32 conflictingBlock) {
        return DAGValidationResult.builder()
                .valid(false)
                .errorCode(code)
                .errorMessage(message)
                .conflictingBlockHash(conflictingBlock)
                .build();
    }
}
