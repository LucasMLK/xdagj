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
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.tuweni.bytes.Bytes32;

/**
 * GetBlocksMessage - Requests block data for specific hashes.
 * <p>
 * Structure:
 * - Count (4 bytes)
 * - Hash 1 (32 bytes)
 * - Hash 2 (32 bytes)
 * - ...
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class GetBlocksMessage extends Message {

  @Getter
  private final List<Bytes32> hashes;

  /**
   * Create from raw bytes
   */
  public GetBlocksMessage(byte[] body) {
    super(XdagMessageCode.GET_BLOCKS, null);
    
    if (body.length < 4) {
        throw new IllegalArgumentException("Invalid GET_BLOCKS message length: " + body.length);
    }
    
    ByteBuffer buffer = ByteBuffer.wrap(body);
    int count = buffer.getInt();
    
    this.hashes = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        if (buffer.remaining() < 32) {
            break; // Truncated message?
        }
        byte[] hashBytes = new byte[32];
        buffer.get(hashBytes);
        this.hashes.add(Bytes32.wrap(hashBytes));
    }
    
    this.body = body;
  }

  /**
   * Create request for specific hashes
   */
  public GetBlocksMessage(List<Bytes32> hashes) {
    super(XdagMessageCode.GET_BLOCKS, null);
    this.hashes = hashes;
    
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  public GetBlocksMessage(Bytes32 singleHash) {
    this(List.of(singleHash));
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.writeInt(hashes.size());
    for (Bytes32 hash : hashes) {
        enc.write(hash.toArray());
    }
  }
}