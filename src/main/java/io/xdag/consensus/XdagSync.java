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

package io.xdag.consensus;

import static io.xdag.config.Constants.REQUEST_BLOCKS_MAX_TIME;
import static io.xdag.config.Constants.REQUEST_WAIT;

import com.google.common.util.concurrent.SettableFuture;
import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.core.AbstractXdagLifecycle;
import io.xdag.core.XdagState;
import io.xdag.crypto.core.CryptoProvider;
import io.xdag.db.BlockStore;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.MutableBytes;

@Slf4j
public class XdagSync extends AbstractXdagLifecycle {

    private static final ThreadFactory factory = BasicThreadFactory.builder()
            .namingPattern("XdagSync-thread-%d")
            .daemon(true)
            .build();

    private final BlockStore blockStore;
    private final ScheduledExecutorService sendTask;
    @Getter
    private final ConcurrentHashMap<Long, SettableFuture<Bytes>> sumsRequestMap;
    @Getter
    private final ConcurrentHashMap<Long, SettableFuture<Bytes>> blocksRequestMap;

    private final LinkedList<Long> syncWindow = new LinkedList<>();

    @Getter
    @Setter
    private Status status;

    private final Kernel kernel;
    private ScheduledFuture<?> sendFuture;
    private long lastRequestTime;

    public XdagSync(Kernel kernel) {
        this.kernel = kernel;
        this.blockStore = kernel.getBlockStore();
        sendTask = new ScheduledThreadPoolExecutor(1, factory);
        sumsRequestMap = new ConcurrentHashMap<>();
        blocksRequestMap = new ConcurrentHashMap<>();
    }

  @Override
  protected void doStart() {

  }

  @Override
  protected void doStop() {

  }

  /**
   * Get the last request time
   *
   * @return Last request time in XDAG timestamp format
   */
  public long getLastTime() {
    return lastRequestTime;
  }

  public enum Status {
        /**
         * Sync states
         */
        SYNCING, SYNC_DONE
    }
}
