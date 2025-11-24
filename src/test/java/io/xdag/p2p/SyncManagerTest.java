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

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.GetEpochHashesMessage;
import io.xdag.p2p.message.Message;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SyncManagerTest {

  @Mock private DagKernel dagKernel;
  @Mock private DagChain dagChain;
  @Mock private P2pService p2pService;
  @Mock private ChannelManager channelManager;
  @Mock private Channel channel;
  @Mock private Block tipBlock;

  private SyncManager syncManager;

  @Before
  public void setUp() {
    when(dagKernel.getDagChain()).thenReturn(dagChain);
    when(dagKernel.getP2pService()).thenReturn(p2pService);
    when(p2pService.getChannelManager()).thenReturn(channelManager);
    when(channelManager.getChannels())
        .thenReturn(Map.of(new InetSocketAddress("127.0.0.1", 5000), channel));

    when(dagChain.getMainChainLength()).thenReturn(10L);
    when(tipBlock.getEpoch()).thenReturn(128L);
    when(dagChain.getMainBlockByHeight(10L)).thenReturn(tipBlock);

    syncManager = new SyncManager(dagKernel, false);
    syncManager.start();
  }

  @After
  public void tearDown() {
    syncManager.stop();
  }

  @Test
  public void shouldRequestEpochsWhenBehind() {
    when(dagChain.getCurrentEpoch()).thenReturn(256L);

    syncManager.performSync();

    verify(channel).send(argThat((Message msg) ->
        msg instanceof GetEpochHashesMessage request
            && request.getStartEpoch() == 129L
            && request.getEndEpoch() >= request.getStartEpoch()));
  }

  @Test
  public void shouldSkipWhenUpToDate() {
    when(dagChain.getCurrentEpoch()).thenReturn(128L);

    syncManager.performSync();

    verify(channel, never()).send(any(Message.class));
  }
}
