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
package io.xdag.p2p.message;

import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

import java.util.ArrayList;
import java.util.List;

/**
 * SyncTransactionsRequestMessage - Batch request transactions by hash list
 *
 * <p>Hybrid Sync Protocol - Transactions Batch Request (0x25)
 *
 * <p><strong>Purpose</strong>:
 * Request a batch of transactions by their hash list. Used during the
 * Solidification phase to fill missing transactions after block sync.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  hashCount          - Number of hashes
 * [variable] hashes[0..N-1]     - Transaction hash list (each 32 bytes)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code hashes}: List of transaction hashes to request</li>
 * </ul>
 *
 * <p><strong>Limits</strong>:
 * <pre>{@code
 * // Maximum 5000 transactions per request
 * if (hashes.size() > 5000) {
 *     hashes = hashes.subList(0, 5000);
 * }
 * }</pre>
 *
 * <p><strong>Usage Scenario</strong>:
 * After syncing blocks, collect missing transaction references and batch request them:
 * <pre>{@code
 * // Step 1: Collect missing transaction hashes from synced blocks
 * Set<Bytes32> missingTxHashes = new HashSet<>();
 *
 * for (Block block : syncedBlocks) {
 *     for (Link txLink : block.getTransactionLinks()) {
 *         Bytes32 txHash = txLink.getTargetHash();
 *         if (!transactionStore.hasTransaction(txHash)) {
 *             missingTxHashes.add(txHash);
 *         }
 *     }
 * }
 *
 * log.info("Found {} missing transactions", missingTxHashes.size());
 *
 * // Step 2: Batch request missing transactions (max 5000 per request)
 * List<Bytes32> hashList = new ArrayList<>(missingTxHashes);
 * for (int i = 0; i < hashList.size(); i += 5000) {
 *     int end = Math.min(i + 5000, hashList.size());
 *     List<Bytes32> batch = hashList.subList(i, end);
 *
 *     // Send request
 *     SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(batch);
 *     channel.sendMessage(request);
 *
 *     // Receive response
 *     SyncTransactionsReplyMessage reply =
 *         channel.waitForResponse(SyncTransactionsReplyMessage.class);
 *
 *     // Save transactions
 *     for (Transaction tx : reply.getTransactions()) {
 *         if (tx != null) {
 *             transactionStore.saveTransaction(tx);
 *         }
 *     }
 * }
 *
 * log.info("Transaction solidification completed");
 * }</pre>
 *
 * <p><strong>Design Rationale</strong>:
 * Without dedicated transaction sync, would need 800+ network roundtrips per block.
 * With batch sync, reduces to 1 roundtrip for 5000 transactions (800x improvement).
 *
 * @see SyncTransactionsReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncTransactionsRequestMessage extends Message {

    /**
     * List of transaction hashes to request
     */
    private List<Bytes32> hashes;

    /**
     * Constructor for receiving message from network
     *
     * <p>Deserializes message body:
     * <ol>
     *   <li>Read hashCount (int, 4 bytes)</li>
     *   <li>For each hash: read 32 bytes</li>
     * </ol>
     *
     * @param body serialized message body
     * @throws IllegalArgumentException if deserialization fails
     */
    public SyncTransactionsRequestMessage(byte[] body) {
        super(XdagMessageCode.SYNC_TRANSACTIONS_REQUEST, SyncTransactionsReplyMessage.class);

        SimpleDecoder dec = new SimpleDecoder(body);

        // Deserialize hash count
        int hashCount = dec.readInt();
        this.hashes = new ArrayList<>(hashCount);

        // Deserialize each hash (32 bytes)
        for (int i = 0; i < hashCount; i++) {
            byte[] hashBytes = new byte[32];
            dec.readBytes(hashBytes);
            this.hashes.add(Bytes32.wrap(hashBytes));
        }

        // Set body for reference
        this.body = body;
    }

    /**
     * Constructor for sending message to network
     *
     * <p>Serializes message:
     * <ol>
     *   <li>Write hashCount (int, 4 bytes)</li>
     *   <li>For each hash: write 32 bytes</li>
     * </ol>
     *
     * @param hashes list of transaction hashes to request
     */
    public SyncTransactionsRequestMessage(List<Bytes32> hashes) {
        super(XdagMessageCode.SYNC_TRANSACTIONS_REQUEST, SyncTransactionsReplyMessage.class);

        this.hashes = hashes;

        // Serialize message body
        SimpleEncoder enc = new SimpleEncoder();
        encode(enc);
        this.body = enc.toBytes();
    }

    @Override
    public void encode(SimpleEncoder enc) {
        // Serialize hash count
        enc.writeInt(hashes.size());

        // Serialize each hash (32 bytes)
        for (Bytes32 hash : hashes) {
            enc.write(hash.toArray());
        }
    }

    @Override
    public String toString() {
        return String.format(
            "SyncTransactionsRequestMessage[hashes=%d, size=%d bytes]",
            hashes != null ? hashes.size() : 0,
            body != null ? body.length : 0
        );
    }
}
