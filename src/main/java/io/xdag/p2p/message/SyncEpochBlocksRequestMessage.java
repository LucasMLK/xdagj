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

/**
 * SyncEpochBlocksRequestMessage - Request all block hashes in an epoch range
 *
 * <p>Hybrid Sync Protocol - Epoch Blocks Request (0x21)
 *
 * <p><strong>Purpose</strong>:
 * Request all block hashes for a range of epochs. Used for Phase 2 (DAG Area Synchronization) of
 * hybrid sync to get all candidate blocks in multiple epochs with a single request.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [8 bytes] startEpoch - Start epoch number (inclusive)
 * [8 bytes] endEpoch   - End epoch number (inclusive)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code startEpoch}: Start epoch number (inclusive)</li>
 *   <li>{@code endEpoch}: End epoch number (inclusive)</li>
 * </ul>
 *
 * <p><strong>Epoch Definition</strong>:
 * <pre>
 * epoch = timestamp >> 16  (each epoch = 64 seconds)
 * </pre>
 *
 * <p><strong>Typical Data Size</strong>:
 * <ul>
 *   <li>Batch size: 100 epochs</li>
 *   <li>Average blocks per epoch: 10-50</li>
 *   <li>Each hash: 32 bytes</li>
 *   <li>Total response size: ~32KB-160KB per batch</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Request all blocks in epoch range [5000, 5099]
 * SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(5000, 5099);
 * channel.sendMessage(request);
 *
 * // Wait for reply
 * SyncEpochBlocksReplyMessage reply =
 *     channel.waitForResponse(SyncEpochBlocksReplyMessage.class);
 * Map<Long, List<Bytes32>> epochBlocksMap = reply.getEpochBlocksMap();
 *
 * // Process each epoch
 * for (long epoch = 5000; epoch <= 5099; epoch++) {
 *     List<Bytes32> hashes = epochBlocksMap.getOrDefault(epoch, Collections.emptyList());
 *     // Filter for missing blocks...
 * }
 * }</pre>
 *
 * @see SyncEpochBlocksReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncEpochBlocksRequestMessage extends Message {

  /**
   * Start epoch number (inclusive)
   */
  private long startEpoch;

  /**
   * End epoch number (inclusive)
   */
  private long endEpoch;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read startEpoch (long, 8 bytes)</li>
   *   <li>Read endEpoch (long, 8 bytes)</li>
   * </ol>
   *
   * @param body serialized message body
   * @throws IllegalArgumentException if deserialization fails
   */
  public SyncEpochBlocksRequestMessage(byte[] body) {
    super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REQUEST, SyncEpochBlocksReplyMessage.class);

    SimpleDecoder dec = new SimpleDecoder(body);

    // Deserialize epoch range
    this.startEpoch = dec.readLong();
    this.endEpoch = dec.readLong();

    // Set body for reference
    this.body = body;
  }

  /**
   * Constructor for sending message to network
   *
   * <p>Serializes message:
   * <ol>
   *   <li>Write startEpoch (long, 8 bytes)</li>
   *   <li>Write endEpoch (long, 8 bytes)</li>
   * </ol>
   *
   * @param startEpoch start epoch number (inclusive)
   * @param endEpoch   end epoch number (inclusive)
   */
  public SyncEpochBlocksRequestMessage(long startEpoch, long endEpoch) {
    super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REQUEST, SyncEpochBlocksReplyMessage.class);

    this.startEpoch = startEpoch;
    this.endEpoch = endEpoch;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Serialize epoch range
    enc.writeLong(startEpoch);
    enc.writeLong(endEpoch);
  }

  @Override
  public String toString() {
    return String.format(
        "SyncEpochBlocksRequestMessage[startEpoch=%d, endEpoch=%d, range=%d, size=%d bytes]",
        startEpoch,
        endEpoch,
        endEpoch - startEpoch + 1,
        body != null ? body.length : 0
    );
  }
}
