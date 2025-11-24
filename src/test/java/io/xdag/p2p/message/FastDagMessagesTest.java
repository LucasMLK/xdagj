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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import java.util.ArrayList;
import java.util.List;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Test;

public class FastDagMessagesTest {

  @Test
  public void testNewBlockHashMessage() {
    Bytes32 hash = Bytes32.random();
    long epoch = 12345L;
    int ttl = 3;

    NewBlockHashMessage msg = new NewBlockHashMessage(hash, epoch, ttl);
    
    assertEquals(hash, msg.getHash());
    assertEquals(epoch, msg.getEpoch());
    assertEquals(ttl, msg.getTtl());
    assertEquals(XdagMessageCode.NEW_BLOCK_HASH, msg.getCode());

    // Test serialization/deserialization
    NewBlockHashMessage deserialized = new NewBlockHashMessage(msg.getBody());
    assertEquals(msg.getHash(), deserialized.getHash());
    assertEquals(msg.getEpoch(), deserialized.getEpoch());
    assertEquals(msg.getTtl(), deserialized.getTtl());
  }

  @Test
  public void testGetBlocksMessage() {
    List<Bytes32> hashes = new ArrayList<>();
    hashes.add(Bytes32.random());
    hashes.add(Bytes32.random());

    GetBlocksMessage msg = new GetBlocksMessage(hashes);
    
    assertEquals(2, msg.getHashes().size());
    assertEquals(hashes.get(0), msg.getHashes().get(0));
    assertEquals(XdagMessageCode.GET_BLOCKS, msg.getCode());

    // Test serialization/deserialization
    GetBlocksMessage deserialized = new GetBlocksMessage(msg.getBody());
    assertEquals(2, deserialized.getHashes().size());
    assertEquals(msg.getHashes().get(0), deserialized.getHashes().get(0));
  }

  @Test
  public void testBlocksReplyMessage() {
    // Create a dummy block
    BlockHeader header = BlockHeader.builder()
        .epoch(100L)
        .difficulty(UInt256.ONE)
        .nonce(Bytes32.random())
        .coinbase(Bytes.random(20))
        .build();
    
    Block block = Block.builder()
        .header(header)
        .links(new ArrayList<>())
        .build();
        
    // Cache hash
    block = block.withHash(block.getHash());

    List<Block> blocks = List.of(block);
    BlocksReplyMessage msg = new BlocksReplyMessage(blocks);
    
    assertEquals(1, msg.getBlocks().size());
    assertEquals(XdagMessageCode.BLOCKS_REPLY, msg.getCode());

    // Test serialization/deserialization
    BlocksReplyMessage deserialized = new BlocksReplyMessage(msg.getBody());
    assertEquals(1, deserialized.getBlocks().size());
    assertEquals(block.getHash(), deserialized.getBlocks().get(0).getHash());
  }
}
