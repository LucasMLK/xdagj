package io.xdag.p2p.message;

import io.xdag.p2p.utils.SimpleDecoder;
import io.xdag.p2p.utils.SimpleEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

/**
 * SyncEpochBlocksReplyMessage - Reply with block hashes in epoch range
 *
 * <p>Hybrid Sync Protocol - Epoch Blocks Reply (0x22)
 *
 * <p><strong>Purpose</strong>:
 * Returns a map of epoch -> block hashes for the specified epoch range, in response to a
 * {@link SyncEpochBlocksRequestMessage}.
 *
 * <p><strong>Message Format</strong>:
 * <pre>
 * [4 bytes]  epochCount           - Number of epochs with blocks
 * For each epoch:
 *   [8 bytes]  epoch              - Epoch number
 *   [4 bytes]  hashCount          - Number of hashes in this epoch
 *   [variable] hashes[0..N-1]     - Block hash list (each 32 bytes)
 * </pre>
 *
 * <p><strong>Fields</strong>:
 * <ul>
 *   <li>{@code epochBlocksMap}: Map of epoch number to list of block hashes</li>
 * </ul>
 *
 * <p><strong>Optimization</strong>:
 * Only epochs that contain blocks are included in the map.
 * Empty epochs are omitted to reduce network traffic.
 *
 * <p><strong>Data Source</strong>:
 * <pre>{@code
 * Map<Long, List<Bytes32>> epochBlocksMap = new HashMap<>();
 * for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
 *     List<Block> blocks = dagStore.getCandidateBlocksInEpoch(epoch);
 *     if (!blocks.isEmpty()) {
 *         List<Bytes32> hashes = blocks.stream()
 *             .map(Block::getHash)
 *             .collect(Collectors.toList());
 *         epochBlocksMap.put(epoch, hashes);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>Typical Size</strong>:
 * <ul>
 *   <li>Batch size: 100 epochs</li>
 *   <li>Epochs with blocks: ~50-80 (50-80% occupancy)</li>
 *   <li>Average blocks per epoch: 10-50</li>
 *   <li>Each hash: 32 bytes</li>
 *   <li>Total size: ~16KB-128KB per batch</li>
 * </ul>
 *
 * <p><strong>Usage</strong>:
 * <pre>{@code
 * // Receiving reply
 * SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(messageBody);
 * Map<Long, List<Bytes32>> epochBlocksMap = reply.getEpochBlocksMap();
 *
 * // Process each epoch in the requested range
 * for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
 *     List<Bytes32> hashes = epochBlocksMap.getOrDefault(epoch, Collections.emptyList());
 *     if (hashes.isEmpty()) {
 *         continue;  // Empty epoch, skip
 *     }
 *
 *     // Filter for missing blocks
 *     List<Bytes32> missingHashes = hashes.stream()
 *         .filter(hash -> !blockStore.hasBlock(hash))
 *         .collect(Collectors.toList());
 *     // Batch request missing blocks...
 * }
 *
 * // Sending reply
 * Map<Long, List<Bytes32>> epochBlocksMap = new HashMap<>();
 * for (long epoch = startEpoch; epoch <= endEpoch; epoch++) {
 *     List<Block> blocks = dagStore.getCandidateBlocksInEpoch(epoch);
 *     if (!blocks.isEmpty()) {
 *         List<Bytes32> hashes = blocks.stream()
 *             .map(Block::getHash)
 *             .collect(Collectors.toList());
 *         epochBlocksMap.put(epoch, hashes);
 *     }
 * }
 * SyncEpochBlocksReplyMessage reply = new SyncEpochBlocksReplyMessage(epochBlocksMap);
 * channel.sendMessage(reply);
 * }</pre>
 *
 * @see SyncEpochBlocksRequestMessage for the request message
 * @see <a href="../../../../../HYBRID_SYNC_MESSAGES.md">Hybrid Sync Protocol</a>
 * @since XDAGJ
 */
@Getter
@Setter
public class SyncEpochBlocksReplyMessage extends Message {

  /**
   * Map of epoch number to list of block hashes Only epochs with blocks are included
   */
  private Map<Long, List<Bytes32>> epochBlocksMap;

  /**
   * Constructor for receiving message from network
   *
   * <p>Deserializes message body:
   * <ol>
   *   <li>Read epochCount (int, 4 bytes)</li>
   *   <li>For each epoch:</li>
   *   <ol>
   *     <li>Read epoch (long, 8 bytes)</li>
   *     <li>Read hashCount (int, 4 bytes)</li>
   *     <li>For each hash: read 32 bytes</li>
   *   </ol>
   * </ol>
   *
   * @param body serialized message body
   * @throws IllegalArgumentException if deserialization fails
   */
  public SyncEpochBlocksReplyMessage(byte[] body) {
    super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REPLY, null);

    SimpleDecoder dec = new SimpleDecoder(body);

    // Deserialize epoch count
    int epochCount = dec.readInt();
    this.epochBlocksMap = new HashMap<>(epochCount);

    // Deserialize each epoch's blocks
    for (int i = 0; i < epochCount; i++) {
      // Read epoch number
      long epoch = dec.readLong();

      // Read hash count for this epoch
      int hashCount = dec.readInt();
      List<Bytes32> hashes = new ArrayList<>(hashCount);

      // Read each hash (32 bytes)
      for (int j = 0; j < hashCount; j++) {
        byte[] hashBytes = new byte[32];
        dec.readBytes(hashBytes);
        hashes.add(Bytes32.wrap(hashBytes));
      }

      this.epochBlocksMap.put(epoch, hashes);
    }

    // Set body for reference
    this.body = body;
  }

  /**
   * Constructor for sending message to network
   *
   * <p>Serializes message:
   * <ol>
   *   <li>Write epochCount (int, 4 bytes)</li>
   *   <li>For each epoch with blocks:</li>
   *   <ol>
   *     <li>Write epoch (long, 8 bytes)</li>
   *     <li>Write hashCount (int, 4 bytes)</li>
   *     <li>For each hash: write 32 bytes</li>
   *   </ol>
   * </ol>
   *
   * @param epochBlocksMap map of epoch number to list of block hashes
   */
  public SyncEpochBlocksReplyMessage(Map<Long, List<Bytes32>> epochBlocksMap) {
    super(XdagMessageCode.SYNC_EPOCH_BLOCKS_REPLY, null);

    this.epochBlocksMap = epochBlocksMap;

    // Serialize message body
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    // Serialize epoch count
    enc.writeInt(epochBlocksMap.size());

    // Serialize each epoch's blocks
    for (Map.Entry<Long, List<Bytes32>> entry : epochBlocksMap.entrySet()) {
      long epoch = entry.getKey();
      List<Bytes32> hashes = entry.getValue();

      // Write epoch number
      enc.writeLong(epoch);

      // Write hash count
      enc.writeInt(hashes.size());

      // Write each hash (32 bytes)
      for (Bytes32 hash : hashes) {
        enc.write(hash.toArray());
      }
    }
  }

  @Override
  public String toString() {
    int totalBlocks = epochBlocksMap.values().stream()
        .mapToInt(List::size)
        .sum();
    return String.format(
        "SyncEpochBlocksReplyMessage[epochs=%d, totalBlocks=%d, size=%d bytes]",
        epochBlocksMap != null ? epochBlocksMap.size() : 0,
        totalBlocks,
        body != null ? body.length : 0
    );
  }
}
