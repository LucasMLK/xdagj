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
package io.xdag.pool;

// ========================================
// TODO: 矿池奖励功能暂时禁用
//
// 原因：需要适配 v5.1 新架构：
// - Kernel -> DagKernel
// - Blockchain -> DagChain
// - SyncManager -> HybridSyncManager
//
// 计划在后续版本中重新实现
// ========================================

/*
import static io.xdag.config.Constants.MIN_GAS;
import static io.xdag.pool.PoolAwardManagerImpl.BlockRewardHistorySender.awardMessageHistoryQueue;
import static io.xdag.utils.BasicUtils.div;
import static io.xdag.utils.BasicUtils.keyPair2Hash;
import static io.xdag.utils.BasicUtils.pubAddress2Hash;
import static io.xdag.utils.BytesUtils.compareTo;
import static io.xdag.utils.WalletUtils.checkAddress;

import io.xdag.Wallet;
import io.xdag.cli.Commands;
import io.xdag.config.Config;
import io.xdag.core.AbstractXdagLifecycle;
import io.xdag.core.Block;
import io.xdag.core.ImportResult;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BasicUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;

@Slf4j
public class PoolAwardManagerImpl extends AbstractXdagLifecycle implements PoolAwardManager, Runnable {
    // ... 所有实现代码已注释 ...
}
*/
