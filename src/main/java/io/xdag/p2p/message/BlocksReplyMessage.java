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
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * BlocksReplyMessage - Returns full block data (response to GetBlocks).
 * <p>
 * Structure:
 * - Count (4 bytes)
 * - Block 1 Size (4 bytes) + Block 1 Bytes
 * - Block 2 Size (4 bytes) + Block 2 Bytes
 * - ...
 */
@EqualsAndHashCode(callSuper = false)
@ToString(exclude = "blocks")
public class BlocksReplyMessage extends Message {

  @Getter
  private final List<Block> blocks;

  /**
   * Create from raw bytes
   */
  public BlocksReplyMessage(byte[] body) {
    super(XdagMessageCode.BLOCKS_REPLY, null);
    
    if (body.length < 4) {
        this.blocks = new ArrayList<>();
        this.body = body;
        return;
    }
    
    ByteBuffer buffer = ByteBuffer.wrap(body);
    int count = buffer.getInt();
    
    this.blocks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        if (buffer.remaining() < 4) break;
        
        int blockSize = buffer.getInt();
        if (buffer.remaining() < blockSize) break;
        
        byte[] blockBytes = new byte[blockSize];
        buffer.get(blockBytes);
        
        try {
            this.blocks.add(Block.fromBytes(blockBytes));
        } catch (Exception e) {
            // Skip invalid blocks but try to continue
        }
    }
    
    this.body = body;
  }

  /**
   * Create reply with blocks
   */
  public BlocksReplyMessage(List<Block> blocks) {
    super(XdagMessageCode.BLOCKS_REPLY, null);
    this.blocks = blocks;
    
    SimpleEncoder enc = new SimpleEncoder();
    encode(enc);
    this.body = enc.toBytes();
  }

  @Override
  public void encode(SimpleEncoder enc) {
    enc.writeInt(blocks.size());
    
    for (Block block : blocks) {
        byte[] bytes = block.toBytes();
        enc.writeInt(bytes.length);
        enc.write(bytes);
    }
  }
}