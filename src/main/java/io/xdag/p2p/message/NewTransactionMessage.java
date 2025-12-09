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
import io.xdag.p2p.utils.SimpleEncoder;
import lombok.Getter;

/**
 * NewTransactionMessage - Broadcast new transaction to P2P network (Phase 3)
 *
 * <p>This message is used for real-time transaction propagation through the
 * P2P network. When a node receives a valid transaction (via RPC or from another peer), it
 * broadcasts this message to all connected peers to spread the transaction quickly.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [1 byte]   ttl            - Time-To-Live (hop count remaining)
 * [variable] transaction    - Serialized Transaction object
 * </pre>
 *
 * <p><strong>TTL (Time-To-Live) Mechanism</strong>:
 * Each message has a TTL value (default 5) that decrements with each hop:
 * <ul>
 *   <li>Initial broadcast: TTL = 5 (max 5 hops)</li>
 *   <li>Each forward: TTL decrements by 1</li>
 *   <li>TTL = 0: Message is dropped, no further forwarding</li>
 *   <li>Prevents infinite propagation in very large networks</li>
 * </ul>
 *
 * <p><strong>Anti-Loop Protection</strong>:
 * This message works together with TransactionBroadcastManager to prevent broadcast loops:
 * <ul>
 *   <li>Before broadcasting: check if already broadcasted recently</li>
 *   <li>On receive: check if already seen recently + check TTL > 0</li>
 *   <li>Do not broadcast back to sender</li>
 * </ul>
 *
 * <p><strong>Usage Flow</strong>:
 * <pre>{@code
 * // Node A: Receive transaction via RPC
 * Transaction tx = parseFromRPC(rpcData);
 * if (transactionPool.addTransaction(tx)) {
 *     // Broadcast to all peers (excluding none)
 *     broadcastManager.broadcastTransaction(tx, null);
 * }
 *
 * // Node B: Receive NEW_TRANSACTION from Node A
 * Transaction tx = message.getTransaction();
 * if (broadcastManager.shouldProcess(tx.getHash())) {
 *     if (transactionPool.addTransaction(tx)) {
 *         // Forward to other peers (excluding Node A)
 *         broadcastManager.broadcastTransaction(tx, senderChannel);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Design Notes</strong>:
 * <ul>
 *   <li>Transaction size is typically 131+ bytes (much smaller than blocks)</li>
 *   <li>No TTL field needed - loop prevention handled by "recently seen" cache</li>
 *   <li>Broadcast is immediate (no batching) for low latency</li>
 * </ul>
 *
 * @see Transaction for transaction structure
 * @since Phase 3 - Network Propagation
 */
@Getter
public class NewTransactionMessage extends Message {

  /**
   * Maximum TTL value (default: 5 hops)
   * <p>
   * This limits transaction propagation to 5 network hops, which is sufficient for most P2P
   * networks while preventing excessive propagation.
   */
  public static final int DEFAULT_TTL = 5;

  /**
   * Time-To-Live (hop count remaining)
   * <p>
   * Decrements by 1 with each forward. When TTL reaches 0, message is dropped.
   */
  private final int ttl;

  /**
   * The transaction being broadcast
   */
  private final Transaction transaction;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes the transaction from message body.
   *
   * @param body serialized message body containing TTL + transaction
   * @throws IllegalArgumentException if deserialization fails
   */
  public NewTransactionMessage(byte[] body) {
    super(XdagMessageCode.NEW_TRANSACTION, null);

    // Deserialize: [1 byte TTL] + [Transaction bytes]
    if (body == null || body.length < 2) {
      throw new IllegalArgumentException("Message body too short");
    }

    // Read TTL (first byte)
    this.ttl = body[0] & 0xFF;

    // Read transaction (remaining bytes)
    byte[] txBytes = new byte[body.length - 1];
    System.arraycopy(body, 1, txBytes, 0, txBytes.length);
    this.transaction = Transaction.fromBytes(txBytes);

    this.body = body;
  }

  /**
   * Constructor for sending message to network (initial broadcast)
   *
   * <p>Creates message with default TTL.
   *
   * @param transaction the transaction to broadcast (must not be null)
   * @throws IllegalArgumentException if transaction is null
   */
  public NewTransactionMessage(Transaction transaction) {
    this(transaction, DEFAULT_TTL);
  }

  /**
   * Constructor for sending message with custom TTL
   *
   * <p>Used when forwarding received transaction with decremented TTL.
   *
   * @param transaction the transaction to broadcast (must not be null)
   * @param ttl         Time-To-Live (hop count)
   * @throws IllegalArgumentException if transaction is null
   */
  public NewTransactionMessage(Transaction transaction, int ttl) {
    super(XdagMessageCode.NEW_TRANSACTION, null);

    // BUGFIX : Add null check for transaction parameter
    // Previously: Would throw NPE in encode() when calling transaction.toBytes()
    // Now: Validate input and provide clear error message
    if (transaction == null) {
      throw new IllegalArgumentException("Transaction cannot be null");
    }

    this.transaction = transaction;
    this.ttl = Math.max(0, Math.min(ttl, DEFAULT_TTL));  // Clamp to [0, DEFAULT_TTL]

    // Serialize transaction
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Serialize: [1 byte TTL] + [Transaction bytes]
    enc.writeByte((byte) ttl);

    byte[] txBytes = transaction.toBytes();
    enc.write(txBytes);
  }

  /**
   * Check if this message should be forwarded
   *
   * @return true if TTL > 0, false otherwise
   */
  public boolean shouldForward() {
    return ttl > 0;
  }

  /**
   * Create a new message for forwarding with decremented TTL
   *
   * @return new message with TTL - 1
   */
  public NewTransactionMessage decrementTTL() {
    return new NewTransactionMessage(transaction, ttl - 1);
  }

  @Override
  public String toString() {
    return String.format(
        "NewTransactionMessage[hash=%s, from=%s, to=%s, amount=%s, ttl=%d, size=%d bytes]",
        transaction.getHash().toHexString().substring(0, 16) + "...",
        transaction.getFrom().toHexString().substring(0, 10) + "...",
        transaction.getTo().toHexString().substring(0, 10) + "...",
        transaction.getAmount().toString(),
        ttl,
        body != null ? body.length : 0
    );
  }
}
