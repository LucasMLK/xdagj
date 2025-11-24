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

import io.xdag.p2p.utils.SimpleEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.tuweni.bytes.Bytes32;

/**
 * EpochHashesReplyMessage - Returns map of [Epoch -> List<Hash>].
 * <p>
 * Structure:
 * - Epoch Count (4 bytes)
 * - [Epoch 1 (8 bytes) | Hash Count (4 bytes) | Hash 1 (32) | Hash 2 (32)...]
 * - [Epoch 2 (8 bytes) | Hash Count (4 bytes) | Hash 1 (32)...]
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class EpochHashesReplyMessage extends Message {

  @Getter
  private final Map<Long, List<Bytes32>> epochHashes;

  /**
   * Create from raw bytes
   */
  public EpochHashesReplyMessage(byte[] body) {
    super(XdagMessageCode.EPOCH_HASHES_REPLY, null);
    
    ByteBuffer buffer = ByteBuffer.wrap(body);
    
    if (buffer.remaining() < 4) {
        this.epochHashes = new LinkedHashMap<>();
        this.body = body;
        return;
    }
    
    int epochCount = buffer.getInt();
    this.epochHashes = new LinkedHashMap<>(epochCount);
    
    for (int i = 0; i < epochCount; i++) {
        if (buffer.remaining() < 12) break; // 8 (epoch) + 4 (count)
        
        long epoch = buffer.getLong();
        int hashCount = buffer.getInt();
        
        List<Bytes32> hashes = new ArrayList<>(hashCount);
        for (int j = 0; j < hashCount; j++) {
            if (buffer.remaining() < 32) break;
            byte[] hashBytes = new byte[32];
            buffer.get(hashBytes);
            hashes.add(Bytes32.wrap(hashBytes));
        }
        
        this.epochHashes.put(epoch, hashes);
    }
    
    this.body = body;
  }

  /**
   * Create reply with epoch map
   */
  public EpochHashesReplyMessage(Map<Long, List<Bytes32>> epochHashes) {
    super(XdagMessageCode.EPOCH_HASHES_REPLY, null);
    this.epochHashes = epochHashes;
    
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.writeInt(epochHashes.size());
    
    for (Map.Entry<Long, List<Bytes32>> entry : epochHashes.entrySet()) {
        enc.writeLong(entry.getKey());
        enc.writeInt(entry.getValue().size());
        for (Bytes32 hash : entry.getValue()) {
            enc.write(hash.toArray());
        }
    }
  }
}