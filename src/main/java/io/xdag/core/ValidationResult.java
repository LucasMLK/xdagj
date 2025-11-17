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

import lombok.Value;

/**
 * Result of transaction validation.
 *
 * <p>This class encapsulates the result of validating a transaction:
 * <ul>
 *   <li>Success/failure status</li>
 *   <li>Error message (if validation failed)</li>
 *   <li>Validation level that failed (syntax/state/economic)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ValidationResult result = validator.validate(tx);
 * if (result.isValid()) {
 *     // Process transaction
 * } else {
 *     log.warn("Validation failed: {}", result.getErrorMessage());
 * }
 * }</pre>
 *
 * @since Phase 1 - Task 1.3
 */
@Value
public class ValidationResult {

    /**
     * Validation status
     */
    boolean valid;

    /**
     * Error message (null if valid)
     */
    String errorMessage;

    /**
     * Validation level that failed (null if valid)
     */
    ValidationLevel failedLevel;

    /**
     * Validation levels in order of execution
     */
    public enum ValidationLevel {
        /**
         * Syntax validation: signature, format, field checks
         */
        SYNTAX,

        /**
         * State validation: nonce, replay protection
         */
        STATE,

        /**
         * Economic validation: balance, fee checks
         */
        ECONOMIC
    }

    /**
     * Create a successful validation result.
     *
     * @return success result
     */
    public static ValidationResult success() {
        return new ValidationResult(true, null, null);
    }

    /**
     * Create a failed validation result.
     *
     * @param level validation level that failed
     * @param message error message
     * @return failure result
     */
    public static ValidationResult failure(ValidationLevel level, String message) {
        return new ValidationResult(false, message, level);
    }

    /**
     * Create a failed validation result (for backward compatibility).
     *
     * @param message error message
     * @return failure result
     */
    public static ValidationResult error(String message) {
        return new ValidationResult(false, message, null);
    }

    // ========== Compatibility Methods ==========

    /**
     * Check if validation succeeded (alias for isValid()).
     *
     * @return true if validation succeeded
     */
    public boolean isSuccess() {
        return valid;
    }

    /**
     * Check if validation failed.
     *
     * @return true if validation failed
     */
    public boolean isError() {
        return !valid;
    }

    /**
     * Get error message (alias for getErrorMessage()).
     *
     * @return error message, or null if valid
     */
    public String getError() {
        return errorMessage;
    }

    @Override
    public String toString() {
        if (valid) {
            return "ValidationResult{valid=true}";
        } else {
            return String.format("ValidationResult{valid=false, level=%s, message='%s'}",
                    failedLevel, errorMessage);
        }
    }
}
