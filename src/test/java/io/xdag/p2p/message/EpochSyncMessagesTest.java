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
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Test;

public class EpochSyncMessagesTest {

  @Test
  public void testGetEpochHashesMessage() {
    long start = 1000L;
    long end = 2000L;
    
    GetEpochHashesMessage msg = new GetEpochHashesMessage(start, end);
    
    assertEquals(start, msg.getStartEpoch());
    assertEquals(end, msg.getEndEpoch());
    assertEquals(XdagMessageCode.GET_EPOCH_HASHES, msg.getCode());

    GetEpochHashesMessage deserialized = new GetEpochHashesMessage(msg.getBody());
    assertEquals(start, deserialized.getStartEpoch());
    assertEquals(end, deserialized.getEndEpoch());
  }

  @Test
  public void testEpochHashesReplyMessage() {
    Map<Long, List<Bytes32>> data = new LinkedHashMap<>();
    data.put(100L, List.of(Bytes32.random(), Bytes32.random()));
    data.put(101L, List.of(Bytes32.random()));
    
    EpochHashesReplyMessage msg = new EpochHashesReplyMessage(data);
    
    assertEquals(2, msg.getEpochHashes().size());
    assertEquals(2, msg.getEpochHashes().get(100L).size());
    assertEquals(XdagMessageCode.EPOCH_HASHES_REPLY, msg.getCode());

    EpochHashesReplyMessage deserialized = new EpochHashesReplyMessage(msg.getBody());
    assertEquals(2, deserialized.getEpochHashes().size());
    assertTrue(deserialized.getEpochHashes().containsKey(100L));
    assertEquals(data.get(100L).get(0), deserialized.getEpochHashes().get(100L).get(0));
  }
}
