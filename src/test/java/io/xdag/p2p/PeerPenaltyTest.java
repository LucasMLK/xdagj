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
package io.xdag.p2p;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.core.DagChain;
import io.xdag.core.Transaction;
import io.xdag.core.TransactionBroadcastManager;
import io.xdag.core.TransactionPool;
import io.xdag.core.XAmount;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.XdagMessageCode;
import java.net.InetSocketAddress;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class PeerPenaltyTest {

  @Mock private DagKernel dagKernel;
  @Mock private DagChain dagChain;
  @Mock private Channel channel;
  @Mock private TransactionPool transactionPool;
  @Mock private TransactionBroadcastManager broadcastManager;

  private XdagP2pEventHandler handler;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(dagKernel.getDagChain()).thenReturn(dagChain);
    when(dagKernel.getTransactionPool()).thenReturn(transactionPool);
    when(dagKernel.getTransactionBroadcastManager()).thenReturn(broadcastManager);
    when(broadcastManager.shouldProcess(any(Bytes32.class))).thenReturn(true);
    when(channel.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));

    handler = new XdagP2pEventHandler(dagKernel);
  }

  @Test
  public void testPenalizeOnMalformedTransactionMessage() {
    byte[] malformed = new byte[2];
    malformed[0] = XdagMessageCode.NEW_TRANSACTION.toByte();
    malformed[1] = 0x01; // TTL, but missing transaction payload

    handler.onMessage(channel, Bytes.wrap(malformed));

    verify(channel, times(1)).close();
  }

  @Test
  public void testNoPenaltyOnValidTransaction() {
    Transaction tx = Transaction.builder()
        .from(Bytes.wrap(new byte[20]))
        .to(Bytes.wrap(new byte[20]))
        .amount(XAmount.ofXAmount(100))
        .fee(XAmount.ofXAmount(1))
        .nonce(1L)
        .chainId(1L)
        .build();

    byte[] txBytes = tx.toBytes();
    byte[] body = new byte[txBytes.length + 1];
    body[0] = 2; // TTL
    System.arraycopy(txBytes, 0, body, 1, txBytes.length);

    byte[] full = new byte[body.length + 1];
    full[0] = XdagMessageCode.NEW_TRANSACTION.toByte();
    System.arraycopy(body, 0, full, 1, body.length);

    when(transactionPool.addTransaction(any(Transaction.class))).thenReturn(true);

    handler.onMessage(channel, Bytes.wrap(full));

    verify(channel, never()).close();
    verify(transactionPool, times(1)).addTransaction(any(Transaction.class));
  }
}
