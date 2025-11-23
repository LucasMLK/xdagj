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
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

/**
 * SyncTransactionsRequestMessage - Batch request transactions by hash list
 *
 * <p>Hybrid Sync Protocol - Transactions Batch Request (0x25)
 *
 * <p><strong>Purpose</strong>:
 * Request a batch of transactions by their hash list. Used during the Solidification phase to fill
 * missing transactions after block sync.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [Variable] requestId          - UTF-8 encoded string with length prefix (BUGFIX BUG-022)
 * [4 bytes]  hashCount          - Number of hashes
 * [variable] hashes[0..N-1]     - Transaction hash list (each 32 bytes)
 * </pre>
 *
 * <p><strong>BUGFIX (BUG-022)</strong>:
 * Added requestId field to enable correct request-response matching in concurrent scenarios.
 * Without requestId, concurrent requests to different peers could be matched incorrectly.
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
   * Request ID for matching request with reply (BUGFIX BUG-022)
   */
  private String requestId;

  /**
   * List of transaction hashes to request
   */
  private List<Bytes32> hashes;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read requestId (string with length prefix) - BUGFIX BUG-022</li>
   *   <li>Read hashCount (int, 4 bytes)</li>
   *   <li>For each hash: read 32 bytes</li>
   * </ol>
   *
   * @param body serialized message body
   * @throws IllegalArgumentException if deserialization fails
   */
  public SyncTransactionsRequestMessage(byte[] body) {
    super(XdagMessageCode.SYNC_TRANSACTIONS_REQUEST, SyncTransactionsReplyMessage.class);
    this.body = body;

    if (body != null && body.length > 0) {
      SimpleDecoder dec = new SimpleDecoder(body);

      // Deserialize requestId first (BUGFIX BUG-022)
      this.requestId = dec.readString();

      // Deserialize hash count
      int hashCount = dec.readInt();
      this.hashes = new ArrayList<>(hashCount);

      // Deserialize each hash (32 bytes)
      for (int i = 0; i < hashCount; i++) {
        byte[] hashBytes = new byte[32];
        dec.readBytes(hashBytes);
        this.hashes.add(Bytes32.wrap(hashBytes));
      }
    }
  }

  /**
   * Constructor for sending message to network
   *
   * <p>Serializes message:
   * <ol>
   *   <li>Write requestId (string with length prefix) - BUGFIX BUG-022</li>
   *   <li>Write hashCount (int, 4 bytes)</li>
   *   <li>For each hash: write 32 bytes</li>
   * </ol>
   *
   * @param requestId unique request identifier for matching reply (BUGFIX BUG-022)
   * @param hashes list of transaction hashes to request
   */
  public SyncTransactionsRequestMessage(String requestId, List<Bytes32> hashes) {
    super(XdagMessageCode.SYNC_TRANSACTIONS_REQUEST, SyncTransactionsReplyMessage.class);

    // BUGFIX (BUG-074): Add null check for hashes parameter
    // Previously: Would throw NPE in encode() when calling hashes.size()
    // Now: Validate input and provide clear error message
    if (hashes == null) {
      throw new IllegalArgumentException("Hashes list cannot be null");
    }

    // BUGFIX (BUG-075): Enforce maximum 5000 hashes limit as documented (line 56-59)
    // Previously: No limit enforcement
    // Now: Validate and enforce hard limit
    if (hashes.size() > 5000) {
      throw new IllegalArgumentException(
          "Maximum 5000 hashes per request, got: " + hashes.size());
    }

    this.requestId = requestId;
    this.hashes = hashes;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Encode requestId as string with length prefix (BUGFIX BUG-022)
    enc.writeString(requestId);

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
        "SyncTransactionsRequestMessage[requestId=%s, hashes=%d, size=%d bytes]",
        requestId,
        hashes != null ? hashes.size() : 0,
        body != null ? body.length : 0
    );
  }
}
