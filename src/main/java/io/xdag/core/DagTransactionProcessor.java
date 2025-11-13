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

import io.xdag.db.TransactionStore;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

import java.util.ArrayList;
import java.util.List;

import static io.xdag.core.XUnit.NANO_XDAG;

/**
 * DagTransactionProcessor - Complete transaction processing for Dag layer
 *
 * <p>This class handles all aspects of transaction processing including
 * validation, account state updates, and transaction persistence.
 *
 * <h2>Processing Flow</h2>
 * <pre>
 * 1. Validate transaction signature
 * 2. Validate account state (balance, nonce)
 * 3. Ensure receiver account exists
 * 4. Update account states (via DagAccountManager)
 * 5. Save transaction to store
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * DagTransactionProcessor processor = new DagTransactionProcessor(
 *     accountManager,
 *     transactionStore
 * );
 *
 * // Process single transaction
 * ProcessingResult result = processor.processTransaction(tx);
 * if (result.isSuccess()) {
 *     // Transaction processed successfully
 * }
 *
 * // Process block transactions
 * ProcessingResult result = processor.processBlockTransactions(block, txs);
 * </pre>
 *
 * @since XDAGJ
 */
@Slf4j
public class DagTransactionProcessor {

    private final DagAccountManager accountManager;
    private final TransactionStore transactionStore;

    /**
     * Create DagTransactionProcessor
     *
     * @param accountManager account state manager
     * @param transactionStore transaction storage
     */
    public DagTransactionProcessor(
            DagAccountManager accountManager,
            TransactionStore transactionStore
    ) {
        this.accountManager = accountManager;
        this.transactionStore = transactionStore;
    }

    /**
     * Process a single transaction
     *
     * <p>This method:
     * <ol>
     *   <li>Validates transaction signature</li>
     *   <li>Validates account state</li>
     *   <li>Ensures receiver exists</li>
     *   <li>Updates account states</li>
     *   <li>Saves transaction</li>
     * </ol>
     *
     * @param tx transaction to process
     * @return processing result
     */
    public ProcessingResult processTransaction(Transaction tx) {
        // 1. Validate transaction signature
        if (!validateSignature(tx)) {
            log.warn("Transaction signature validation failed: {}", tx.getHash().toHexString());
            return ProcessingResult.error("Invalid transaction signature");
        }

        // 2. Validate account state
        ValidationResult validation = validateAccountState(tx);
        if (!validation.isSuccess()) {
            log.warn("Transaction account validation failed: {}, error: {}",
                    tx.getHash().toHexString(), validation.getError());
            return ProcessingResult.error(validation.getError());
        }

        // 3. Ensure receiver account exists
        accountManager.ensureAccountExists(tx.getTo());

        // 4. Update account states
        try {
            updateAccountStates(tx);
        } catch (Exception e) {
            log.error("Failed to update account states for tx: {}",
                    tx.getHash().toHexString(), e);
            return ProcessingResult.error("Failed to update account states: " + e.getMessage());
        }

        // 5. Save transaction to store
        transactionStore.saveTransaction(tx);

        log.debug("Transaction processed: hash={}, from={}, to={}, amount={}",
                tx.getHash().toHexString(),
                tx.getFrom().toHexString(),
                tx.getTo().toHexString(),
                tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString());

        return ProcessingResult.success();
    }

    /**
     * Process all transactions in a block
     *
     * <p>Processes transactions sequentially. If any transaction fails,
     * the entire block processing fails and no further transactions are processed.
     *
     * @param block block being processed
     * @param transactions transactions to process
     * @return processing result
     */
    public ProcessingResult processBlockTransactions(Block block, List<Transaction> transactions) {
        List<String> processedTxHashes = new ArrayList<>();

        for (Transaction tx : transactions) {
            ProcessingResult result = processTransaction(tx);
            if (!result.isSuccess()) {
                log.warn("Transaction processing failed in block {}, tx {}: {}",
                        block.getHash().toHexString(),
                        tx.getHash().toHexString(),
                        result.getError());

                return ProcessingResult.error(
                        String.format("Block transaction processing failed: %s", result.getError())
                );
            }
            processedTxHashes.add(tx.getHash().toHexString());
        }

        log.info("Successfully processed {} transactions for block {}",
                transactions.size(), block.getHash().toHexString());

        return ProcessingResult.success();
    }

    // ==================== Private Helper Methods ====================

    /**
     * Validate transaction signature
     *
     * <p>TODO: Implement actual signature verification logic
     *
     * @param tx transaction to validate
     * @return true if signature is valid
     */
    private boolean validateSignature(Transaction tx) {
        // TODO: Implement signature verification
        // For now, assume all signatures are valid
        return true;
    }

    /**
     * Validate account state for transaction
     *
     * <p>Checks:
     * <ul>
     *   <li>Sender account exists</li>
     *   <li>Sender has sufficient balance</li>
     *   <li>Transaction nonce matches sender nonce</li>
     * </ul>
     *
     * @param tx transaction to validate
     * @return validation result
     */
    private ValidationResult validateAccountState(Transaction tx) {
        // 1. Check if sender account exists
        if (!accountManager.hasAccount(tx.getFrom())) {
            return ValidationResult.error("Sender account does not exist: " + tx.getFrom().toHexString());
        }

        // 2. Check sender balance
        UInt256 balance = accountManager.getBalance(tx.getFrom());
        // Convert XAmount to UInt256 (nano units)
        UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, NANO_XDAG).longValue());
        UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, NANO_XDAG).longValue());
        UInt256 required = txAmount.add(txFee);

        if (balance.compareTo(required) < 0) {
            return ValidationResult.error(
                    String.format("Insufficient balance: address=%s, has=%s, needs=%s",
                            tx.getFrom().toHexString(),
                            balance.toDecimalString(),
                            required.toDecimalString())
            );
        }

        // 3. Check nonce
        UInt64 expectedNonce = accountManager.getNonce(tx.getFrom());
        // Convert long nonce to UInt64
        UInt64 txNonce = UInt64.valueOf(tx.getNonce());

        if (!txNonce.equals(expectedNonce)) {
            return ValidationResult.error(
                    String.format("Invalid nonce: address=%s, expected=%d, got=%d",
                            tx.getFrom().toHexString(),
                            expectedNonce.toLong(),
                            txNonce.toLong())
            );
        }

        return ValidationResult.success();
    }

    /**
     * Update account states based on transaction
     *
     * @param tx transaction to process
     */
    private void updateAccountStates(Transaction tx) {
        // Convert XAmount to UInt256 (nano units)
        UInt256 txAmount = UInt256.valueOf(tx.getAmount().toDecimal(0, NANO_XDAG).longValue());
        UInt256 txFee = UInt256.valueOf(tx.getFee().toDecimal(0, NANO_XDAG).longValue());

        // Update sender account
        accountManager.subtractBalance(tx.getFrom(), txAmount.add(txFee));
        accountManager.incrementNonce(tx.getFrom());

        // Update receiver account
        accountManager.addBalance(tx.getTo(), txAmount);

        log.debug("Account state updated: from={}, to={}, amount={}, fee={}",
                tx.getFrom().toHexString(),
                tx.getTo().toHexString(),
                tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                tx.getFee().toDecimal(9, XUnit.XDAG).toPlainString());
    }

    /**
     * ProcessingResult - Transaction processing result
     *
     * <p>Wrapper for transaction processing outcome with error details.
     */
    @Value
    public static class ProcessingResult {
        boolean success;
        String error;

        /**
         * Create success result
         *
         * @return success result
         */
        public static ProcessingResult success() {
            return new ProcessingResult(true, null);
        }

        /**
         * Create error result
         *
         * @param error error message
         * @return error result
         */
        public static ProcessingResult error(String error) {
            return new ProcessingResult(false, error);
        }

        /**
         * Check if processing succeeded
         *
         * @return true if success
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * Check if processing failed
         *
         * @return true if error
         */
        public boolean isError() {
            return !success;
        }

        @Override
        public String toString() {
            return success ? "ProcessingResult{success}" : "ProcessingResult{error='" + error + "'}";
        }
    }
}
