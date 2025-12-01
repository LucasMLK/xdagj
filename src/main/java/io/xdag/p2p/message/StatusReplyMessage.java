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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * StatusReplyMessage - Reply with current chain sync status.
 *
 * <p>Sent in response to {@link GetStatusMessage} to inform the peer about
 * the current chain state, enabling sync decisions.
 *
 * <p>Structure:
 * <ul>
 *   <li>tipEpoch (8 bytes) - epoch of the latest main chain block</li>
 *   <li>mainChainHeight (8 bytes) - current main chain height</li>
 *   <li>difficultyLength (4 bytes) - length of difficulty bytes</li>
 *   <li>difficulty (variable) - cumulative difficulty as BigInteger bytes</li>
 * </ul>
 */
@EqualsAndHashCode(callSuper = false)
@ToString
public class StatusReplyMessage extends Message {

  @Getter
  private final long tipEpoch;

  @Getter
  private final long mainChainHeight;

  @Getter
  private final BigInteger difficulty;

  /**
   * Create from raw bytes (for deserialization)
   */
  public StatusReplyMessage(byte[] body) {
    super(XdagMessageCode.STATUS_REPLY, null);

    if (body.length < 20) {
      throw new IllegalArgumentException("Invalid STATUS_REPLY message length: " + body.length);
    }

    ByteBuffer buffer = ByteBuffer.wrap(body);
    this.tipEpoch = buffer.getLong();
    this.mainChainHeight = buffer.getLong();

    int diffLen = buffer.getInt();
    if (diffLen < 0 || diffLen > 64) {
      throw new IllegalArgumentException("Invalid difficulty length: " + diffLen);
    }

    byte[] diffBytes = new byte[diffLen];
    buffer.get(diffBytes);
    this.difficulty = new BigInteger(1, diffBytes);

    this.body = body;
  }

  /**
   * Create a STATUS_REPLY with chain state
   */
  public StatusReplyMessage(long tipEpoch, long mainChainHeight, BigInteger difficulty) {
    super(XdagMessageCode.STATUS_REPLY, null);
    this.tipEpoch = tipEpoch;
    this.mainChainHeight = mainChainHeight;
    this.difficulty = difficulty != null ? difficulty : BigInteger.ZERO;

    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.writeLong(tipEpoch);
    enc.writeLong(mainChainHeight);

    byte[] diffBytes = difficulty.toByteArray();
    // Remove leading zero byte if present (BigInteger adds it for positive numbers)
    if (diffBytes.length > 1 && diffBytes[0] == 0) {
      byte[] trimmed = new byte[diffBytes.length - 1];
      System.arraycopy(diffBytes, 1, trimmed, 0, trimmed.length);
      diffBytes = trimmed;
    }
    enc.writeInt(diffBytes.length);
    enc.writeBytes(diffBytes);
  }
}
