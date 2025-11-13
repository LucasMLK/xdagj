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
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Result of importing a block into DagChain
 *
 * <p>This class provides comprehensive information about the block import operation,
 * including:
 * <ul>
 *   <li><strong>Import status</strong>: Success, failure, or error</li>
 *   <li><strong>Block state</strong>: Main block, orphan, or rejected</li>
 *   <li><strong>Epoch competition</strong>: Whether block won its epoch</li>
 *   <li><strong>Cumulative difficulty</strong>: Block's cumulative difficulty</li>
 *   <li><strong>Error details</strong>: Detailed error information if import failed</li>
 * </ul>
 *
 * @since XDAGJ
 * @see DagChain#tryToConnect(Block)
 */
@Data
@Builder
public class DagImportResult {

    /** Import status */
    private final ImportStatus status;

    /** Block state after import */
    private final BlockState blockState;

    /** Whether block won its epoch competition (smallest hash in epoch) */
    private final boolean epochWinner;

    /** Block's epoch number */
    private final long epoch;

    /** Block's height in main chain (0 if orphan) */
    private final long height;

    /** Block's cumulative difficulty */
    private final UInt256 cumulativeDifficulty;

    /** Error details (if status != SUCCESS) */
    private final ErrorDetails errorDetails;

    /**
     * Import status enumeration
     */
    public enum ImportStatus {
        /** Block imported successfully */
        SUCCESS,

        /** Block already exists */
        DUPLICATE,

        /** Parent block or transaction not found */
        MISSING_DEPENDENCY,

        /** Block validation failed */
        INVALID,

        /** Exception occurred during import */
        ERROR
    }

    /**
     * Block state after import
     */
    public enum BlockState {
        /** Block became a main block (on main chain) */
        MAIN_BLOCK,

        /** Block imported as orphan (not on main chain) */
        ORPHAN,

        /** Block imported but still in memory pool (waiting for dependencies) */
        PENDING,

        /** Block rejected (validation failed) */
        REJECTED
    }

    /**
     * Error details for failed imports
     */
    @Data
    @Builder
    public static class ErrorDetails {
        /** Error type */
        private final ErrorType errorType;

        /** Human-readable error message */
        private final String message;

        /** Missing dependency hash (for MISSING_DEPENDENCY errors) */
        private final Bytes32 missingDependency;

        /** DAG validation result (for DAG_VALIDATION errors) */
        private final DAGValidationResult dagValidationResult;

        /** Exception (for EXCEPTION errors) */
        private final Exception exception;
    }

    /**
     * Error type enumeration
     */
    public enum ErrorType {
        /** No error */
        NONE,

        /** Basic validation failed (timestamp, structure, PoW) */
        BASIC_VALIDATION,

        /** Link validation failed (transaction or block reference invalid) */
        LINK_VALIDATION,

        /** DAG rules validation failed */
        DAG_VALIDATION,

        /** Missing parent block or transaction */
        MISSING_DEPENDENCY,

        /** Block already exists */
        DUPLICATE_BLOCK,

        /** Exception occurred */
        EXCEPTION
    }

    // ==================== Factory Methods ====================

    /**
     * Create a successful main block import result
     */
    public static DagImportResult mainBlock(
            long epoch,
            long height,
            UInt256 cumulativeDifficulty,
            boolean epochWinner) {
        return DagImportResult.builder()
                .status(ImportStatus.SUCCESS)
                .blockState(BlockState.MAIN_BLOCK)
                .epochWinner(epochWinner)
                .epoch(epoch)
                .height(height)
                .cumulativeDifficulty(cumulativeDifficulty)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.NONE)
                        .build())
                .build();
    }

    /**
     * Create a successful orphan block import result
     */
    public static DagImportResult orphan(
            long epoch,
            UInt256 cumulativeDifficulty,
            boolean epochWinner) {
        return DagImportResult.builder()
                .status(ImportStatus.SUCCESS)
                .blockState(BlockState.ORPHAN)
                .epochWinner(epochWinner)
                .epoch(epoch)
                .height(0)  // Orphans have no height
                .cumulativeDifficulty(cumulativeDifficulty)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.NONE)
                        .build())
                .build();
    }

    /**
     * Create a duplicate block result
     */
    public static DagImportResult duplicate() {
        return DagImportResult.builder()
                .status(ImportStatus.DUPLICATE)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.DUPLICATE_BLOCK)
                        .message("Block already exists")
                        .build())
                .build();
    }

    /**
     * Create a missing dependency result
     */
    public static DagImportResult missingDependency(Bytes32 missingHash, String message) {
        return DagImportResult.builder()
                .status(ImportStatus.MISSING_DEPENDENCY)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.MISSING_DEPENDENCY)
                        .message(message)
                        .missingDependency(missingHash)
                        .build())
                .build();
    }

    /**
     * Create a basic validation failure result
     */
    public static DagImportResult invalidBasic(String message) {
        return DagImportResult.builder()
                .status(ImportStatus.INVALID)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.BASIC_VALIDATION)
                        .message(message)
                        .build())
                .build();
    }

    /**
     * Create a link validation failure result
     */
    public static DagImportResult invalidLink(String message, Bytes32 invalidLink) {
        return DagImportResult.builder()
                .status(ImportStatus.INVALID)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.LINK_VALIDATION)
                        .message(message)
                        .missingDependency(invalidLink)
                        .build())
                .build();
    }

    /**
     * Create a DAG validation failure result
     */
    public static DagImportResult invalidDAG(DAGValidationResult dagResult) {
        return DagImportResult.builder()
                .status(ImportStatus.INVALID)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.DAG_VALIDATION)
                        .message("DAG validation failed: " + dagResult.getErrorMessage())
                        .dagValidationResult(dagResult)
                        .build())
                .build();
    }

    /**
     * Create an exception result
     */
    public static DagImportResult error(Exception exception, String message) {
        return DagImportResult.builder()
                .status(ImportStatus.ERROR)
                .blockState(BlockState.REJECTED)
                .errorDetails(ErrorDetails.builder()
                        .errorType(ErrorType.EXCEPTION)
                        .message(message)
                        .exception(exception)
                        .build())
                .build();
    }

    // ==================== Convenience Methods ====================

    /**
     * Check if import was successful
     */
    public boolean isSuccess() {
        return status == ImportStatus.SUCCESS;
    }

    /**
     * Check if block became a main block
     */
    public boolean isMainBlock() {
        return blockState == BlockState.MAIN_BLOCK;
    }

    /**
     * Check if block is an orphan
     */
    public boolean isOrphan() {
        return blockState == BlockState.ORPHAN;
    }

    /**
     * Check if block was rejected
     */
    public boolean isRejected() {
        return blockState == BlockState.REJECTED;
    }

    /**
     * Get error message (if any)
     */
    public String getErrorMessage() {
        if (errorDetails == null) {
            return null;
        }
        return errorDetails.getMessage();
    }

    /**
     * Get detailed status string for logging
     */
    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("DagImportResult{");
        sb.append("status=").append(status);
        sb.append(", blockState=").append(blockState);

        if (status == ImportStatus.SUCCESS) {
            sb.append(", epoch=").append(epoch);
            sb.append(", height=").append(height);
            sb.append(", epochWinner=").append(epochWinner);
            sb.append(", cumulativeDifficulty=").append(
                    cumulativeDifficulty != null ? cumulativeDifficulty.toDecimalString() : "null"
            );
        } else if (errorDetails != null) {
            sb.append(", errorType=").append(errorDetails.getErrorType());
            sb.append(", message='").append(errorDetails.getMessage()).append("'");
            if (errorDetails.getMissingDependency() != null) {
                sb.append(", missingDependency=").append(errorDetails.getMissingDependency().toHexString());
            }
        }

        sb.append("}");
        return sb.toString();
    }
}
