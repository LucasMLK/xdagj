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

import io.xdag.db.DagStore;
import io.xdag.db.TransactionStore;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.List;

/**
 * DagBlockProcessor - Block processor for Dag layer
 *
 * <p>This class handles block validation and processing for DagChain,
 * coordinating block storage, transaction processing, and account state updates.
 *
 * <h2>Processing Flow</h2>
 * <pre>
 * 1. Validate basic block structure
 * 2. Save block to DagStore
 * 3. Extract transactions from block
 * 4. Process transactions (account updates)
 * 5. Update block metadata
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>
 * DagBlockProcessor processor = new DagBlockProcessor(
 *     dagStore,
 *     txProcessor,
 *     accountManager
 * );
 *
 * // Process new block
 * ProcessingResult result = processor.processBlock(block);
 * if (result.isSuccess()) {
 *     // Block processed successfully
 * }
 * </pre>
 *
 * @since XDAGJ
 */
@Slf4j
public class DagBlockProcessor {

    private final DagStore dagStore;
    private final TransactionStore transactionStore;
    private final DagTransactionProcessor txProcessor;
    private final DagAccountManager accountManager;

    /**
     * Create DagBlockProcessor
     *
     * @param dagStore block storage
     * @param transactionStore transaction storage
     * @param txProcessor transaction processor
     * @param accountManager account state manager
     */
    public DagBlockProcessor(
            DagStore dagStore,
            TransactionStore transactionStore,
            DagTransactionProcessor txProcessor,
            DagAccountManager accountManager
    ) {
        this.dagStore = dagStore;
        this.transactionStore = transactionStore;
        this.txProcessor = txProcessor;
        this.accountManager = accountManager;
    }

    /**
     * Process a new block
     *
     * <p>This method:
     * <ol>
     *   <li>Validates basic block structure</li>
     *   <li>Saves block to DagStore</li>
     *   <li>Extracts transactions from block</li>
     *   <li>Processes transactions (account updates)</li>
     *   <li>Updates block metadata</li>
     * </ol>
     *
     * @param block block to process
     * @return processing result
     */
    public ProcessingResult processBlock(Block block) {
        // 1. Validate basic block structure
        if (!validateBasicStructure(block)) {
            // Handle null block case carefully
            String hashStr = (block != null && block.getHash() != null)
                    ? block.getHash().toHexString()
                    : "<null>";
            log.warn("Block structure validation failed: {}", hashStr);
            return ProcessingResult.error("Invalid block structure");
        }

        // 2. Save block to DagStore
        try {
            dagStore.saveBlock(block);
            log.debug("Block saved to DagStore: {}", block.getHash().toHexString());
        } catch (Exception e) {
            log.error("Failed to save block to DagStore: {}", block.getHash().toHexString(), e);
            return ProcessingResult.error("Failed to save block: " + e.getMessage());
        }

        // 3. Extract transactions from block
        List<Transaction> transactions = extractTransactions(block);
        log.debug("Extracted {} transactions from block {}",
                transactions.size(), block.getHash().toHexString());

        // 4. Process transactions (account state updates)
        if (!transactions.isEmpty()) {
            DagTransactionProcessor.ProcessingResult txResult =
                    txProcessor.processBlockTransactions(block, transactions);

            if (!txResult.isSuccess()) {
                log.warn("Block transaction processing failed: {}, error: {}",
                        block.getHash().toHexString(), txResult.getError());
                return ProcessingResult.error(txResult.getError());
            }
        }

        // 5. Log success
        log.info("Block processed successfully: hash={}, transactions={}, height={}",
                block.getHash().toHexString(),
                transactions.size(),
                block.getInfo().getHeight());

        return ProcessingResult.success();
    }

    /**
     * Validate basic block structure
     *
     * <p>Checks:
     * <ul>
     *   <li>Block hash is not null</li>
     *   <li>Block info is not null</li>
     *   <li>Block has valid timestamp</li>
     *   <li>Block timestamp is at epoch end (0xffff)</li>
     * </ul>
     *
     * @param block block to validate
     * @return true if structure is valid
     */
    private boolean validateBasicStructure(Block block) {
        if (block == null) {
            log.warn("Block is null");
            return false;
        }

        if (block.getHash() == null) {
            log.warn("Block hash is null");
            return false;
        }

        if (block.getInfo() == null) {
            log.warn("Block info is null for block: {}", block.getHash().toHexString());
            return false;
        }

        if (block.getTimestamp() <= 0) {
            log.warn("Block has invalid timestamp: {}", block.getTimestamp());
            return false;
        }

        // IMPORTANT: Main blocks must have timestamp at epoch end (lower 16 bits = 0xffff)
        // This matches C code validation: (time & 0xffff) == 0xffff (block.c:677)
        if ((block.getTimestamp() & 0xffff) != 0xffff) {
            log.warn("Block timestamp {} is not at epoch end (must be 0xffff)",
                    Long.toHexString(block.getTimestamp()));
            return false;
        }

        return true;
    }

    /**
     * Extract transactions from block
     *
     * <p>Retrieves transactions from TransactionStore based on block's transaction links.
     *
     * @param block block to extract transactions from
     * @return list of transactions
     */
    private List<Transaction> extractTransactions(Block block) {
        List<Transaction> transactions = new ArrayList<>();

        // Get all transaction links from the block
        List<Link> txLinks = block.getTransactionLinks();

        if (txLinks.isEmpty()) {
            return transactions;
        }

        log.debug("Extracting {} transaction links from block {}",
                txLinks.size(), block.getHash().toHexString());

        // Retrieve each transaction from TransactionStore
        for (Link link : txLinks) {
            Bytes32 txHash = link.getTargetHash();
            Transaction tx = transactionStore.getTransaction(txHash);

            if (tx == null) {
                log.warn("Transaction not found in store: {}", txHash.toHexString());
                // Skip missing transactions - they may not have been saved yet
                continue;
            }

            transactions.add(tx);
        }

        log.debug("Extracted {} transactions from block {}",
                transactions.size(), block.getHash().toHexString());

        return transactions;
    }

    /**
     * Get block by hash
     *
     * @param hash block hash
     * @param withLinks whether to include links
     * @return block or null if not found
     */
    public Block getBlock(Bytes32 hash, boolean withLinks) {
        return dagStore.getBlockByHash(hash, withLinks);
    }

    /**
     * Check if block exists
     *
     * @param hash block hash
     * @return true if block exists
     */
    public boolean hasBlock(Bytes32 hash) {
        return dagStore.hasBlock(hash);
    }

    /**
     * ProcessingResult - Block processing result
     *
     * <p>Wrapper for block processing outcome with error details.
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
