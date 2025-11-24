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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * GetEpochHashesMessage - Requests all block hashes within an epoch range.
 * <p>
 * Structure:
 * - Start Epoch (8 bytes)
 * - End Epoch (8 bytes)
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class GetEpochHashesMessage extends Message {

  @Getter
  private final long startEpoch;
  
  @Getter
  private final long endEpoch;

  /**
   * Create from raw bytes
   */
  public GetEpochHashesMessage(byte[] body) {
    super(XdagMessageCode.GET_EPOCH_HASHES, null);
    
    if (body.length < 16) {
        throw new IllegalArgumentException("Invalid GET_EPOCH_HASHES message length: " + body.length);
    }
    
    ByteBuffer buffer = ByteBuffer.wrap(body);
    this.startEpoch = buffer.getLong();
    this.endEpoch = buffer.getLong();
    
    this.body = body;
  }

  /**
   * Create request for epoch range
   */
  public GetEpochHashesMessage(long startEpoch, long endEpoch) {
    super(XdagMessageCode.GET_EPOCH_HASHES, null);
    this.startEpoch = startEpoch;
    this.endEpoch = endEpoch;
    
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.writeLong(startEpoch);
    enc.writeLong(endEpoch);
  }
}