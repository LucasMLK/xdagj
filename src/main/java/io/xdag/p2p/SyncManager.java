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

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.channel.ChannelManager;
import io.xdag.p2p.message.GetEpochHashesMessage;
import io.xdag.p2p.message.XdagMessageCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * FastDAG Sync Manager (v3.0).
 *
 * <p>Periodically schedules {@link GetEpochHashesMessage} requests so the node can learn about
 * new epochs and fetch missing blocks via {@link XdagMessageCode#GET_BLOCKS}.
 */
@Slf4j
public class SyncManager implements AutoCloseable {

  private static final long SYNC_INTERVAL_MS = 5_000;
  private static final long MAX_EPOCHS_PER_REQUEST = 256;
  private static final long MAX_PIPELINE_GAP = 4_096;

  private final DagKernel dagKernel;
  private final DagChain dagChain;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FastDag-SyncManager");
        t.setDaemon(true);
        return t;
      });
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicLong lastRequestedEpoch = new AtomicLong(-1);
  private final boolean enableScheduler;

  public SyncManager(DagKernel dagKernel) {
    this(dagKernel, true);
  }

  SyncManager(DagKernel dagKernel, boolean enableScheduler) {
    this.dagKernel = Objects.requireNonNull(dagKernel, "DagKernel cannot be null");
    this.dagChain = Objects.requireNonNull(dagKernel.getDagChain(), "DagChain cannot be null");
    this.enableScheduler = enableScheduler;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    if (enableScheduler) {
      executor.scheduleWithFixedDelay(this::performSync,
          SYNC_INTERVAL_MS, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);
      log.info("FastDAG SyncManager started (interval={} ms, batch={} epochs)",
          SYNC_INTERVAL_MS, MAX_EPOCHS_PER_REQUEST);
    } else {
      log.info("FastDAG SyncManager started in manual mode");
    }
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    executor.shutdownNow();
    log.info("FastDAG SyncManager stopped");
  }

  @Override
  public void close() {
    stop();
  }

  void performSync() {
    if (!running.get()) {
      return;
    }

    P2pService p2pService = dagKernel.getP2pService();
    if (p2pService == null) {
      return;
    }

    List<Channel> channels = getActiveChannels(p2pService);
    if (channels.isEmpty()) {
      return;
    }

    long currentEpoch = dagChain.getCurrentEpoch();
    long localTipEpoch = getLocalTipEpoch();
    lastRequestedEpoch.updateAndGet(prev -> Math.max(prev, localTipEpoch));

    if (localTipEpoch >= currentEpoch) {
      return;
    }

    long outstanding = lastRequestedEpoch.get() - localTipEpoch;
    if (outstanding > MAX_PIPELINE_GAP) {
      log.debug("FastDAG sync paused (pipeline gap {} epochs)", outstanding);
      return;
    }

    long startEpoch = Math.max(localTipEpoch + 1, lastRequestedEpoch.get() + 1);
    if (startEpoch > currentEpoch) {
      return;
    }

    long endEpoch = Math.min(startEpoch + MAX_EPOCHS_PER_REQUEST - 1, currentEpoch);
    Channel channel = selectChannel(channels);
    sendEpochRequest(channel, startEpoch, endEpoch);
  }

  private void sendEpochRequest(Channel channel, long startEpoch, long endEpoch) {
    try {
      log.debug("Requesting epoch hashes from {}: [{}-{}]",
          channel.getRemoteAddress(), startEpoch, endEpoch);
      channel.send(new GetEpochHashesMessage(startEpoch, endEpoch));
      lastRequestedEpoch.set(endEpoch);
    } catch (Exception e) {
      log.warn("Failed to send GET_EPOCH_HASHES to {}: {}",
          channel.getRemoteAddress(), e.getMessage());
    }
  }

  private Channel selectChannel(List<Channel> channels) {
    if (channels.size() == 1) {
      return channels.get(0);
    }
    int index = ThreadLocalRandom.current().nextInt(channels.size());
    return channels.get(index);
  }

  private long getLocalTipEpoch() {
    long height = dagChain.getMainChainLength();
    if (height <= 0) {
      return 0;
    }
    try {
      Block tip = dagChain.getMainBlockByHeight(height);
      if (tip != null) {
        return tip.getEpoch();
      }
    } catch (Exception e) {
      log.warn("Failed to read main block at height {}: {}", height, e.getMessage());
    }
    return 0;
  }

  private List<Channel> getActiveChannels(P2pService service) {
    try {
      ChannelManager manager = service.getChannelManager();
      if (manager == null) {
        return List.of();
      }
      var map = manager.getChannels();
      if (map == null || map.isEmpty()) {
        return List.of();
      }
      return new ArrayList<>(map.values());
    } catch (Exception e) {
      log.warn("Failed to enumerate P2P channels: {}", e.getMessage());
      return List.of();
    }
  }
}
