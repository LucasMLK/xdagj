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
 * ValidationResult - Validation result wrapper
 *
 * <p>Unified validation result for transaction and block validation in Dag layer.
 *
 * <h2>Usage Example</h2>
 * <pre>
 * // Success case
 * ValidationResult result = ValidationResult.success();
 * if (result.isSuccess()) {
 *     // proceed
 * }
 *
 * // Error case
 * ValidationResult result = ValidationResult.error("Insufficient balance");
 * if (result.isError()) {
 *     log.error("Validation failed: {}", result.getError());
 * }
 * </pre>
 *
 * @since v5.1 Phase 10
 */
@Value
public class ValidationResult {
    /**
     * Whether validation succeeded
     */
    boolean success;

    /**
     * Error message (null if success)
     */
    String error;

    /**
     * Create a success result
     *
     * @return success result with no error
     */
    public static ValidationResult success() {
        return new ValidationResult(true, null);
    }

    /**
     * Create an error result
     *
     * @param error error message
     * @return error result with message
     */
    public static ValidationResult error(String error) {
        return new ValidationResult(false, error);
    }

    /**
     * Check if validation succeeded
     *
     * @return true if success
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Check if validation failed
     *
     * @return true if error
     */
    public boolean isError() {
        return !success;
    }

    @Override
    public String toString() {
        return success ? "ValidationResult{success}" : "ValidationResult{error='" + error + "'}";
    }
}
