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
 * SyncBlocksRequestMessage - Batch request blocks by hash list
 *
 * <p>Hybrid Sync Protocol - Blocks Batch Request (0x23)
 *
 * <p><strong>Purpose</strong>:
 * Request a batch of blocks by their hash list. Used during the Solidification phase to fill
 * missing blocks discovered during sync.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [Variable] requestId          - UTF-8 encoded string with length prefix (BUGFIX BUG-022)
 * [4 bytes]  hashCount          - Number of hashes
 * [variable] hashes[0..N-1]     - Block hash list (each 32 bytes)
 * [1 byte]   isRaw              - Whether to return full block data
 * </pre>
 *
 * <p><strong>BUGFIX (BUG-022)</strong>:
 * Added requestId field to enable correct request-response matching in concurrent scenarios.
 * Without requestId, concurrent requests to different peers could be matched incorrectly.
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code hashes}: List of block hashes to request</li>
 *   <li>{@code isRaw}: true = return full block data, false = return BlockInfo only</li>
 * </ul>
 *
 * <p><strong>Limits</strong>:
 * <pre>{@code
 * // Maximum 1000 blocks per request
 * if (hashes.size() > 1000) {
 *     hashes = hashes.subList(0, 1000);
 * }
 * }</pre>
 *
 * <p><strong>Usage Scenario</strong>:
 * After syncing the DAG area, detect missing blocks and batch request them:
 * <pre>{@code
 * // Collect missing block hashes
 * Set<Bytes32> missingHashes = new HashSet<>();
 * for (Block block : syncedBlocks) {
 *     for (Link link : block.getLinks()) {
 *         if (link.isBlock() && !blockStore.hasBlock(link.getTargetHash())) {
 *             missingHashes.add(link.getTargetHash());
 *         }
 *     }
 * }
 *
 * // Batch request missing blocks
 * List<Bytes32> hashList = new ArrayList<>(missingHashes);
 * for (int i = 0; i < hashList.size(); i += 1000) {
 *     int end = Math.min(i + 1000, hashList.size());
 *     List<Bytes32> batch = hashList.subList(i, end);
 *
 *     SyncBlocksRequestMessage request = new SyncBlocksRequestMessage(batch, true);
 *     channel.sendMessage(request);
 *
 *     SyncBlocksReplyMessage reply =
 *         channel.waitForResponse(SyncBlocksReplyMessage.class);
 *     // Process blocks...
 * }
 * }</pre>
 *
 * @see SyncBlocksReplyMessage for the response message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncBlocksRequestMessage extends Message {

  /**
   * Request ID for matching request with reply (BUGFIX BUG-022)
   */
  private String requestId;

  /**
   * List of block hashes to request
   */
  private List<Bytes32> hashes;

  /**
   * Whether to return full block data (true) or BlockInfo only (false)
   */
  private boolean isRaw;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read requestId (string with length prefix) - BUGFIX BUG-022</li>
   *   <li>Read hashCount (int, 4 bytes)</li>
   *   <li>For each hash: read 32 bytes</li>
   *   <li>Read isRaw (boolean, 1 byte)</li>
   * </ol>
   *
   * @param body serialized message body
   * @throws IllegalArgumentException if deserialization fails
   */
  public SyncBlocksRequestMessage(byte[] body) {
    super(XdagMessageCode.SYNC_BLOCKS_REQUEST, SyncBlocksReplyMessage.class);
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

      // Deserialize isRaw flag
      this.isRaw = dec.readBoolean();
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
   *   <li>Write isRaw (boolean, 1 byte)</li>
   * </ol>
   *
   * @param requestId unique request identifier for matching reply (BUGFIX BUG-022)
   * @param hashes list of block hashes to request
   * @param isRaw  true = full block data, false = BlockInfo only
   */
  public SyncBlocksRequestMessage(String requestId, List<Bytes32> hashes, boolean isRaw) {
    super(XdagMessageCode.SYNC_BLOCKS_REQUEST, SyncBlocksReplyMessage.class);

    // BUGFIX (BUG-069): Add null check for hashes parameter
    // Previously: Would throw NPE in encode() when calling hashes.size()
    // Now: Validate input and provide clear error message
    if (hashes == null) {
      throw new IllegalArgumentException("Hashes list cannot be null");
    }

    // BUGFIX (BUG-070): Enforce maximum 1000 hashes limit as documented (line 58-61)
    // Previously: No limit enforcement
    // Now: Validate and enforce hard limit
    if (hashes.size() > 1000) {
      throw new IllegalArgumentException(
          "Maximum 1000 hashes per request, got: " + hashes.size());
    }

    this.requestId = requestId;
    this.hashes = hashes;
    this.isRaw = isRaw;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  /**
   * Encode message to bytes
   *
   * <p>Format:
   * [Variable requestId] + [4 bytes hashCount] + [32 bytes per hash] + [1 byte isRaw]
   *
   */
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

    // Serialize isRaw flag
    enc.writeBoolean(isRaw);
  }


  @Override
  public String toString() {
    return String.format(
        "SyncBlocksRequestMessage[requestId=%s, hashes=%d, raw=%b, size=%d bytes]",
        requestId,
        hashes != null ? hashes.size() : 0,
        isRaw,
        body != null ? body.length : 0
    );
  }
}
