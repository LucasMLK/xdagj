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

import io.xdag.core.ValidationResult.ValidationLevel;

/**
 * Transaction validation interface for XDAG.
 *
 * <p>Provides multi-level validation for transactions:
 * <ol>
 *   <li><b>Syntax Validation</b>: Signature, format, field checks</li>
 *   <li><b>State Validation</b>: Nonce, replay protection</li>
 *   <li><b>Economic Validation</b>: Balance, fee checks</li>
 * </ol>
 *
 * <h2>Validation Levels</h2>
 * <ul>
 *   <li><b>SYNTAX</b>: Fast, stateless checks (no database access)</li>
 *   <li><b>STATE</b>: State-dependent checks (requires account state)</li>
 *   <li><b>ECONOMIC</b>: Economic checks (requires balance/fee validation)</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * // Full validation (all levels)
 * ValidationResult result = validator.validate(tx);
 * if (!result.isValid()) {
 *     log.warn("Transaction invalid: {}", result.getErrorMessage());
 *     return;
 * }
 *
 * // Partial validation (syntax only)
 * ValidationResult syntaxResult = validator.validateSyntax(tx);
 * if (!syntaxResult.isValid()) {
 *     return;  // Reject early without state checks
 * }
 * }</pre>
 *
 * @since Phase 1 - Task 1.3
 */
public interface TransactionValidator {

    /**
     * Perform full validation (all levels).
     *
     * <p>Executes validation in order: SYNTAX -> STATE -> ECONOMIC.
     * Stops at first failure.
     *
     * @param tx transaction to validate
     * @return validation result
     */
    ValidationResult validate(Transaction tx);

    /**
     * Validate transaction syntax (level 1).
     *
     * <p>Checks:
     * <ul>
     *   <li>Signature validity (v, r, s format)</li>
     *   <li>Field constraints (non-null, positive amounts)</li>
     *   <li>Signature verification (cryptographic check)</li>
     * </ul>
     *
     * @param tx transaction to validate
     * @return validation result
     */
    ValidationResult validateSyntax(Transaction tx);

    /**
     * Validate transaction state (level 2).
     *
     * <p>Checks:
     * <ul>
     *   <li>Nonce continuity (expected next nonce)</li>
     *   <li>Replay protection (not already executed)</li>
     *   <li>Account existence</li>
     * </ul>
     *
     * @param tx transaction to validate
     * @return validation result
     */
    ValidationResult validateState(Transaction tx);

    /**
     * Validate transaction economics (level 3).
     *
     * <p>Checks:
     * <ul>
     *   <li>Sufficient balance (amount + fee)</li>
     *   <li>Minimum fee requirement</li>
     *   <li>Maximum fee limit (anti-DoS)</li>
     * </ul>
     *
     * @param tx transaction to validate
     * @return validation result
     */
    ValidationResult validateEconomic(Transaction tx);

    /**
     * Validate transaction at a specific level only.
     *
     * @param tx transaction to validate
     * @param level validation level
     * @return validation result
     */
    ValidationResult validateAt(Transaction tx, ValidationLevel level);
}
