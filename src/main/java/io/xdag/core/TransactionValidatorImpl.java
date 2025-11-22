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

import static io.xdag.core.ValidationResult.ValidationLevel.ECONOMIC;
import static io.xdag.core.ValidationResult.ValidationLevel.STATE;
import static io.xdag.core.ValidationResult.ValidationLevel.SYNTAX;
import static io.xdag.core.XUnit.NANO_XDAG;

import io.xdag.core.ValidationResult.ValidationLevel;
import io.xdag.store.TransactionStore;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Default implementation of TransactionValidator.
 *
 * <p>This implementation provides comprehensive multi-level validation:
 * <ol>
 *   <li><b>SYNTAX</b>: Fast validation without state access</li>
 *   <li><b>STATE</b>: Nonce and replay protection checks</li>
 *   <li><b>ECONOMIC</b>: Balance and fee validation</li>
 * </ol>
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Fail fast: Stop at first validation failure</li>
 *   <li>Performance: SYNTAX checks are stateless and fast</li>
 *   <li>Separation: Each level is independent and testable</li>
 * </ul>
 *
 * @since Phase 1 - Task 1.3
 */
@Slf4j
public class TransactionValidatorImpl implements TransactionValidator {

  // ========== Dependencies ==========

  private final DagAccountManager accountManager;
  private final TransactionStore transactionStore;
  private final XAmount minFee;
  private final XAmount maxFee;

  /**
   * Create a new TransactionValidatorImpl.
   *
   * @param accountManager   account manager for state queries
   * @param transactionStore transaction store for execution status
   * @param minFee           minimum transaction fee
   * @param maxFee           maximum transaction fee (anti-DoS)
   */
  public TransactionValidatorImpl(
      DagAccountManager accountManager,
      TransactionStore transactionStore,
      XAmount minFee,
      XAmount maxFee) {
    this.accountManager = accountManager;
    this.transactionStore = transactionStore;
    this.minFee = minFee;
    this.maxFee = maxFee;

    log.info("TransactionValidator initialized (minFee={}, maxFee={})",
        minFee.toDecimal(9, XUnit.XDAG),
        maxFee.toDecimal(9, XUnit.XDAG));
  }

  // ========== Public API ==========

  @Override
  public ValidationResult validate(Transaction tx) {
    // Level 1: Syntax validation
    ValidationResult syntaxResult = validateSyntax(tx);
    if (!syntaxResult.isValid()) {
      return syntaxResult;
    }

    // Level 2: State validation
    ValidationResult stateResult = validateState(tx);
    if (!stateResult.isValid()) {
      return stateResult;
    }

    // Level 3: Economic validation
    ValidationResult economicResult = validateEconomic(tx);
    if (!economicResult.isValid()) {
      return economicResult;
    }

    return ValidationResult.success();
  }

  @Override
  public ValidationResult validateSyntax(Transaction tx) {
    try {
      // 1. Check basic transaction validity (built-in checks)
      if (!tx.isValid()) {
        return failure(SYNTAX, "Transaction failed basic validation (amount/fee/nonce/data)");
      }

      // 2. Check signature components
      if (tx.getV() < 0 || tx.getV() > 255) {
        return failure(SYNTAX, String.format("Invalid signature v value: %d", tx.getV()));
      }

      if (tx.getR() == null || tx.getS() == null) {
        return failure(SYNTAX, "Signature r or s is null");
      }

      // 3. Verify signature cryptographically
      if (!tx.verifySignature()) {
        return failure(SYNTAX, "Invalid transaction signature");
      }

      // 4. Check address sizes
      if (tx.getFrom().size() != 20) {
        return failure(SYNTAX, String.format("Invalid from address size: %d (expected 20)",
            tx.getFrom().size()));
      }

      if (tx.getTo().size() != 20) {
        return failure(SYNTAX, String.format("Invalid to address size: %d (expected 20)",
            tx.getTo().size()));
      }

      // 5. Check self-transfer
      if (tx.getFrom().equals(tx.getTo())) {
        return failure(SYNTAX, "Cannot transfer to self");
      }

      return ValidationResult.success();

    } catch (Exception e) {
      log.warn("Syntax validation exception for tx {}: {}",
          getTxShortHash(tx), e.getMessage());
      return failure(SYNTAX, "Syntax validation error: " + e.getMessage());
    }
  }

  @Override
  public ValidationResult validateState(Transaction tx) {
    try {
      // 1. Check if transaction already executed
      if (transactionStore.isTransactionExecuted(tx.getHash())) {
        return failure(STATE, "Transaction already executed");
      }

      // 2. Check nonce continuity
      UInt64 accountNonce = accountManager.getNonce(tx.getFrom());
      long expectedNonce = accountNonce.toLong() + 1;

      if (tx.getNonce() != expectedNonce) {
        return failure(STATE, String.format(
            "Nonce mismatch: expected %d, got %d",
            expectedNonce, tx.getNonce()));
      }

      return ValidationResult.success();

    } catch (Exception e) {
      log.warn("State validation exception for tx {}: {}",
          getTxShortHash(tx), e.getMessage());
      return failure(STATE, "State validation error: " + e.getMessage());
    }
  }

  @Override
  public ValidationResult validateEconomic(Transaction tx) {
    try {
      // 1. Check minimum fee
      if (tx.getFee().compareTo(minFee) < 0) {
        return failure(ECONOMIC, String.format(
            "Fee too low: %s < %s (minimum)",
            tx.getFee().toDecimal(9, XUnit.XDAG),
            minFee.toDecimal(9, XUnit.XDAG)));
      }

      // 2. Check maximum fee (anti-DoS)
      if (tx.getFee().compareTo(maxFee) > 0) {
        return failure(ECONOMIC, String.format(
            "Fee too high: %s > %s (maximum)",
            tx.getFee().toDecimal(9, XUnit.XDAG),
            maxFee.toDecimal(9, XUnit.XDAG)));
      }

      // 3. Check sufficient balance (amount + fee)
      UInt256 balance = accountManager.getBalance(tx.getFrom());
      XAmount required = tx.getAmount().add(tx.getFee());

      // BUGFIX BUG-080: Convert XAmount to UInt256 safely (avoid longValue() overflow)
      // XAmount stores nano units as long, which is always safe to convert to BigInteger
      BigDecimal requiredNano = required.toDecimal(0, NANO_XDAG);
      UInt256 requiredUInt256 = UInt256.valueOf(requiredNano.toBigInteger());

      if (balance.compareTo(requiredUInt256) < 0) {
        // BUGFIX BUG-080: Safe balance display (avoid toLong() overflow)
        String balanceStr;
        try {
          balanceStr = XAmount.of(balance.toLong(), NANO_XDAG).toDecimal(9, XUnit.XDAG);
        } catch (ArithmeticException e) {
          // Balance too large for long, display raw UInt256
          balanceStr = balance.toDecimalString() + " nano";
        }

        return failure(ECONOMIC, String.format(
            "Insufficient balance: have %s, need %s (amount + fee)",
            balanceStr,
            required.toDecimal(9, XUnit.XDAG)));
      }

      return ValidationResult.success();

    } catch (Exception e) {
      log.warn("Economic validation exception for tx {}: {}",
          getTxShortHash(tx), e.getMessage());
      return failure(ECONOMIC, "Economic validation error: " + e.getMessage());
    }
  }

  // ========== Helper Methods ==========

  /**
   * Create a failure result.
   */
  private ValidationResult failure(ValidationLevel level, String message) {
    return ValidationResult.failure(level, message);
  }

  /**
   * Get short hash for logging.
   */
  private String getTxShortHash(Transaction tx) {
    try {
      return tx.getHash().toHexString().substring(0, 16);
    } catch (Exception e) {
      return "unknown";
    }
  }
}
