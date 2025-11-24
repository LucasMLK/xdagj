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

import io.xdag.core.Block;
import io.xdag.p2p.utils.SimpleEncoder;
import java.nio.ByteBuffer;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * NewBlockHashMessage - Broadcasts a new block's existence (Inv).
 * <p>
 * Structure:
 * - Hash (32 bytes)
 * - Epoch (8 bytes)
 * - TTL (1 byte) - Hop limit to prevent infinite flooding
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class NewBlockHashMessage extends Message {

  @Getter
  private final Bytes32 hash;
  
  @Getter
  private final long epoch;
  
  @Getter
  private final int ttl;

  /**
   * Create message from raw bytes (deserialization)
   */
  public NewBlockHashMessage(byte[] body) {
    super(XdagMessageCode.NEW_BLOCK_HASH, null);
    
    if (body.length < 41) { // 32 + 8 + 1
        throw new IllegalArgumentException("Invalid NEW_BLOCK_HASH message length: " + body.length);
    }
    
    ByteBuffer buffer = ByteBuffer.wrap(body);
    
    byte[] hashBytes = new byte[32];
    buffer.get(hashBytes);
    this.hash = Bytes32.wrap(hashBytes);
    
    this.epoch = buffer.getLong();
    this.ttl = buffer.get() & 0xFF; // Unsigned byte
    
    this.body = body;
  }

  /**
   * Create message for broadcasting a block
   */
  public NewBlockHashMessage(Block block, int ttl) {
    this(block.getHash(), block.getEpoch(), ttl);
  }

  public NewBlockHashMessage(Bytes32 hash, long epoch, int ttl) {
    super(XdagMessageCode.NEW_BLOCK_HASH, null);
    this.hash = hash;
    this.epoch = epoch;
    this.ttl = ttl;
    
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.write(hash.toArray());
    enc.writeLong(epoch);
    enc.writeByte((byte) ttl);
  }

  /**
   * Create a new message with decremented TTL
   */
  public NewBlockHashMessage decrementTTL() {
    if (ttl <= 0) {
      return this;
    }
    return new NewBlockHashMessage(hash, epoch, ttl - 1);
  }

  public boolean shouldForward() {
    return ttl > 0;
  }
}