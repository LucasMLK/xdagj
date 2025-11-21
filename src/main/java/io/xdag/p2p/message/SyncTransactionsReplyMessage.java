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

import io.xdag.core.Transaction;
import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

/**
 * SyncTransactionsReplyMessage - Reply with requested transactions
 *
 * <p>Hybrid Sync Protocol - Transactions Batch Reply (0x26)
 *
 * <p><strong>Purpose</strong>:
 * Returns a batch of transactions matching the requested hash list, in response to a
 * {@link SyncTransactionsRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  txCount            - Number of transactions returned
 * [variable] transactions[0..N-1] - Transaction data list
 *     Each transaction:
 *     [32 bytes] txHash         - Transaction hash
 *     [1 byte]   hasTx          - Whether transaction exists (1=yes, 0=missing)
 *     [4 bytes]  txSize         - Transaction data size (if hasTx=1)
 *     [variable] txData         - Serialized transaction data (if hasTx=1)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code transactions}: List of transactions matching request order</li>
 *   <li>Order corresponds to request hash list</li>
 *   <li>If a hash is not found, corresponding height is null</li>
 *   <li>transactions.size() == request.hashes.size()</li>
 *   <li>transactions.get(i) corresponds to request.hashes.get(i)</li>
 * </ul>
 *
 * <p><strong>Data Structure</strong>:
 * <pre>{@code
 * public class SyncTransactionsReplyMessage {
 *     private final List<Transaction> transactions;  // May contain nulls
 *
 *     // transactions.size() == request.hashes.size()
 *     // transactions.get(i) corresponds to request.hashes.get(i)
 * }
 * }</pre>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(messageBody);
 * List<Transaction> transactions = reply.getTransactions();
 *
 * // Process transactions
 * for (Transaction tx : transactions) {
 *     if (tx != null) {
 *         transactionStore.saveTransaction(tx);
 *     }
 * }
 *
 * // Sending reply
 * List<Transaction> transactions = new ArrayList<>();
 * for (Bytes32 hash : requestHashes) {
 *     Transaction tx = transactionStore.getTransactionByHash(hash);
 *     transactions.add(tx);  // May be null if not found
 * }
 * SyncTransactionsReplyMessage reply = new SyncTransactionsReplyMessage(transactions);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * <p><strong>Performance</strong>:
 * <ul>
 *   <li>Batch size: 5000 transactions per request</li>
 *   <li>Average transaction size: ~256 bytes</li>
 *   <li>Expected response size: ~25 MB</li>
 *   <li>Target latency: < 2s (P99)</li>
 * </ul>
 *
 * @see SyncTransactionsRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncTransactionsReplyMessage extends Message {

  /**
   * List of transactions (may contain nulls for missing transactions) Order corresponds to request
   * hash list
   */
  private List<Transaction> transactions;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read txCount (int, 4 bytes)</li>
   *   <li>For each transaction:
   *     <ul>
   *       <li>Read txHash (32 bytes)</li>
   *       <li>Read hasTx (boolean, 1 byte)</li>
   *       <li>If hasTx: read txSize (int, 4 bytes) and txData</li>
   *       <li>If !hasTx: add null to list</li>
   *     </ul>
   *   </li>
   * </ol>
   *
   * @param body serialized message body
   * @throws IllegalArgumentException if deserialization fails
   */
  public SyncTransactionsReplyMessage(byte[] body) {
    super(XdagMessageCode.SYNC_TRANSACTIONS_REPLY, null);

    SimpleDecoder dec = new SimpleDecoder(body);

    // Read transaction count
    int txCount = dec.readInt();
    this.transactions = new ArrayList<>(txCount);

    // Read each transaction
    for (int i = 0; i < txCount; i++) {
      // Read transaction hash (32 bytes) - for reference/verification
      byte[] hashBytes = new byte[32];
      dec.readBytes(hashBytes);
      @SuppressWarnings("unused")
      Bytes32 txHash = Bytes32.wrap(hashBytes);

      // Check if transaction exists
      boolean hasTx = dec.readBoolean();

      if (hasTx) {
        // Read transaction size and data
        int txSize = dec.readInt();
        byte[] txBytes = new byte[txSize];
        dec.readBytes(txBytes);

        // Deserialize transaction
        Transaction tx = Transaction.fromBytes(txBytes);
        this.transactions.add(tx);
      } else {
        // Missing transaction
        this.transactions.add(null);
      }
    }

    // Set body for reference
    this.body = body;
  }

  /**
   * Constructor for sending message to network
   *
   * <p>Serializes message:
   * <ol>
   *   <li>Write txCount (int, 4 bytes)</li>
   *   <li>For each transaction:
   *     <ul>
   *       <li>Write txHash (32 bytes)</li>
   *       <li>Write hasTx (boolean, 1 byte)</li>
   *       <li>If tx != null: write txSize and txData</li>
   *       <li>If tx == null: write hasTx=false only</li>
   *     </ul>
   *   </li>
   * </ol>
   *
   * @param transactions list of transactions (may contain nulls)
   */
  public SyncTransactionsReplyMessage(List<Transaction> transactions) {
    super(XdagMessageCode.SYNC_TRANSACTIONS_REPLY, null);

    this.transactions = transactions;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Write transaction count
    enc.writeInt(transactions.size());

    // Write each transaction
    for (Transaction tx : transactions) {
      if (tx != null) {
        // Write transaction hash (32 bytes)
        enc.write(tx.getHash().toArray());

        // Transaction exists
        enc.writeBoolean(true);

        // Serialize transaction
        byte[] txBytes = tx.toBytes();
        enc.writeInt(txBytes.length);
        enc.write(txBytes);
      } else {
        // Write placeholder hash (all zeros)
        enc.write(new byte[32]);

        // Transaction missing
        enc.writeBoolean(false);
      }
    }
  }

  @Override
  public String toString() {
    long nonNullCount =
        transactions != null ? transactions.stream().filter(Objects::nonNull).count() : 0;
    return String.format(
        "SyncTransactionsReplyMessage[total=%d, nonNull=%d, size=%d bytes]",
        transactions != null ? transactions.size() : 0,
        nonNullCount,
        body != null ? body.length : 0
    );
  }
}
