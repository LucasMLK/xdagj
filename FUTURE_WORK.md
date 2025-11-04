# Future Work - XDAGJ v5.1+

**文档版本**: 1.0
**创建日期**: 2025-11-04
**Phase 状态**: Phase 7.3 完成，Phase 8 规划中

本文档记录了低优先级的 TODO 和未来改进项，这些不阻塞当前 v5.1 核心功能的完成。

---

## 📋 目录

1. [配置和优化](#配置和优化)
2. [共识和同步](#共识和同步)
3. [网络层改进](#网络层改进)
4. [存储优化](#存储优化)
5. [矿池功能](#矿池功能)
6. [架构改进](#架构改进)

---

## 🔧 配置和优化

### AbstractConfig.java

#### 设置挖矿线程数
**文件**: `src/main/java/io/xdag/config/AbstractConfig.java:284`
**TODO**: Set mining thread count

**描述**: 需要添加配置项允许用户设置挖矿线程数量。

**影响**: 低 - 性能优化
**优先级**: P3
**预估工作量**: 1-2小时

**实现建议**:
```java
// Add to AbstractConfig
protected int miningThreadCount = Runtime.getRuntime().availableProcessors();

public int getMiningThreadCount() {
    return miningThreadCount;
}
```

---

#### 仅加载区块模式
**文件**: `src/main/java/io/xdag/config/AbstractConfig.java:295`
**TODO**: Only load block but no run

**描述**: 添加 "load-only" 模式，用于分析、导出、或数据库维护。

**影响**: 低 - 维护工具
**优先级**: P4
**预估工作量**: 2-4小时

**实现建议**:
```java
// Add command line option: --load-only
protected boolean loadOnlyMode = false;
```

---

## 🤝 共识和同步

### SyncManager.java

#### 修改共识算法
**文件**: `src/main/java/io/xdag/consensus/SyncManager.java:407`
**TODO**: Modify consensus

**描述**: 预留的共识算法修改接口，可能用于未来的共识升级。

**影响**: 高 - 但当前不需要
**优先级**: P5（未来考虑）
**预估工作量**: 未知（依赖具体需求）

**备注**: 保留此 TODO 直到明确共识升级需求。

---

#### 发送区块请求via P2P
**文件**: `src/main/java/io/xdag/consensus/SyncManager.java:475`
**TODO**: Send block request via P2P - need to add method to XdagP2pEventHandler

**描述**: 需要在 XdagP2pEventHandler 添加方法支持区块请求。

**影响**: 中 - 同步性能
**优先级**: P3
**预估工作量**: 2-3小时

**实现建议**:
```java
// In XdagP2pEventHandler
public void requestBlockV5ByHash(Channel channel, Bytes32 hash) {
    BlockV5RequestMessage msg = new BlockV5RequestMessage(hash, chainStats);
    channel.send(Bytes.wrap(msg.getBody()));
}
```

---

#### 成功导入后移除
**文件**: `src/main/java/io/xdag/consensus/SyncManager.java:555`
**TODO**: Need to remove after successful import

**描述**: 成功导入区块后从某个数据结构中移除（需要上下文确认）。

**影响**: 中 - 内存管理
**优先级**: P3
**预估工作量**: 1小时

**备注**: 需要审查具体代码确定移除什么。

---

#### 默认保持同步状态
**文件**: `src/main/java/io/xdag/consensus/SyncManager.java:573`
**TODO**: Currently stays in sync by default, not responsible for block generation

**描述**: 设计注释，说明同步管理器不负责区块生成。

**影响**: 无 - 文档
**优先级**: P5
**预估工作量**: 删除此 TODO 即可

---

#### 未接收请求块的超时
**文件**: `src/main/java/io/xdag/consensus/SyncManager.java:523`
**TODO**: Consider timeout for unreceived request block

**描述**: 添加超时机制处理未接收到的请求区块。

**影响**: 中 - 健壮性
**优先级**: P3
**预估工作量**: 2-4小时

**实现建议**:
```java
// Use ScheduledExecutorService for timeout handling
private void requestBlockWithTimeout(Bytes32 hash, long timeoutMs) {
    ScheduledFuture<?> timeout = scheduler.schedule(() -> {
        handleBlockRequestTimeout(hash);
    }, timeoutMs, TimeUnit.MILLISECONDS);
    pendingRequests.put(hash, timeout);
}
```

---

### XdagSync.java

#### 设置同步开始时间/快照时间
**文件**: `src/main/java/io/xdag/consensus/XdagSync.java:86`
**TODO**: Set sync start time/snapshot time

**描述**: 在同步开始时设置时间戳，用于统计和监控。

**影响**: 低 - 监控
**优先级**: P4
**预估工作量**: 30分钟

**实现建议**:
```java
private long syncStartTime;
private long snapshotTime;

protected void doStart() {
    if (status != Status.SYNCING) {
        status = Status.SYNCING;
        syncStartTime = System.currentTimeMillis();
        snapshotTime = XdagTime.getCurrentTimestamp();
        // ...
    }
}
```

---

### XdagPow.java

#### 限制矿池提交份额数量
**文件**: `src/main/java/io/xdag/consensus/XdagPow.java:683`
**TODO**: Limit the number of shares submitted by each pool within each block production cycle

**描述**: 防止单个矿池在区块生产周期内提交过多份额。

**影响**: 中 - 防止攻击
**优先级**: P2
**预估工作量**: 4-6小时

**实现建议**:
```java
// Track shares per pool per cycle
private Map<String, AtomicInteger> sharesPerPoolPerCycle = new ConcurrentHashMap<>();
private static final int MAX_SHARES_PER_POOL = 100;

public boolean acceptShare(String poolId, Share share) {
    int currentCount = sharesPerPoolPerCycle
        .computeIfAbsent(poolId, k -> new AtomicInteger(0))
        .incrementAndGet();

    if (currentCount > MAX_SHARES_PER_POOL) {
        log.warn("Pool {} exceeded max shares limit", poolId);
        return false;
    }
    return true;
}
```

---

## 🌐 网络层改进

### XdagP2pHandler.java

#### 处理多区块请求
**文件**: `src/main/java/io/xdag/net/XdagP2pHandler.java:425, 428`
**TODO**:
- paulochen 处理多区块请求
- 如果请求时间间隔过大，启动新线程发送，目的是避免攻击

**描述**: 优化多区块请求处理，防止大范围请求导致的资源耗尽攻击。

**影响**: 中 - 安全性和性能
**优先级**: P2
**预估工作量**: 4-6小时

**实现建议**:
```java
private static final long MAX_TIME_RANGE = 86400; // 1 day
private static final int MAX_BLOCKS_PER_REQUEST = 1000;
private ExecutorService blockSendExecutor = Executors.newCachedThreadPool();

protected void processBlocksRequest(BlocksRequestMessage msg) {
    long timeRange = msg.getEndtime() - msg.getStarttime();

    // Check if time range is too large
    if (timeRange > MAX_TIME_RANGE) {
        log.warn("Large time range request: {} from {}", timeRange, channel.getRemoteAddress());
        // Use separate thread to avoid blocking
        blockSendExecutor.submit(() -> {
            sendBlocksInBatches(msg);
        });
    } else {
        sendBlocksInBatches(msg);
    }
}

private void sendBlocksInBatches(BlocksRequestMessage msg) {
    List<BlockV5> blocks = chain.getBlocksByTime(msg.getStarttime(), msg.getEndtime());

    // Limit blocks per request
    if (blocks.size() > MAX_BLOCKS_PER_REQUEST) {
        log.warn("Too many blocks requested: {}, limiting to {}",
                 blocks.size(), MAX_BLOCKS_PER_REQUEST);
        blocks = blocks.subList(0, MAX_BLOCKS_PER_REQUEST);
    }

    // Send in batches with rate limiting
    for (int i = 0; i < blocks.size(); i++) {
        if (i > 0 && i % 100 == 0) {
            Thread.sleep(100); // Rate limit: 100ms per 100 blocks
        }
        SyncBlockV5Message blockMsg = new SyncBlockV5Message(blocks.get(i), 1);
        msgQueue.sendMessage(blockMsg);
    }
}
```

---

## 💾 存储优化

### BlockFinalizationService.java

#### 可选删除finalized区块节省空间
**文件**: `src/main/java/io/xdag/core/BlockFinalizationService.java:207`
**TODO**: Optionally delete from active BlockStore to save space

**描述**: 将 finalized 区块从活跃存储移除，只保留在 FinalizedBlockStore。

**影响**: 中 - 存储优化
**优先级**: P3
**预估工作量**: 4-6小时

**实现建议**:
```java
// Add config option
private boolean deleteFromActiveStore = false;

private void finalizeBlock(BlockV5 block) {
    // Save to FinalizedBlockStore
    finalizedBlockStore.saveBlockV5(block);

    // Optionally delete from active store
    if (deleteFromActiveStore) {
        blockStore.deleteBlockV5(block.getHash());
        log.debug("Deleted finalized block from active store: {}",
                 block.getHash().toHexString());
    }
}
```

---

### BlockchainImpl.java

#### 重新设计onNewTxHistory
**文件**: `src/main/java/io/xdag/core/BlockchainImpl.java:382`
**TODO**: Redesign onNewTxHistory() to work directly with Transaction objects

**描述**: 当前交易历史记录基于 Link，需要重新设计为直接使用 Transaction 对象。

**影响**: 中 - 架构改进
**优先级**: P3
**预估工作量**: 6-8小时

**实现建议**:
```java
// Old approach (Link-based)
private void recordTransactionHistory(Link link) {
    if (link.isTransaction()) {
        Transaction tx = transactionStore.getTransaction(link.getTargetHash());
        // Record history...
    }
}

// New approach (Transaction-based)
private void recordTransactionHistory(Transaction tx, BlockV5 block) {
    TxHistoryEntry entry = TxHistoryEntry.builder()
        .txHash(tx.getHash())
        .blockHash(block.getHash())
        .from(tx.getFrom())
        .to(tx.getTo())
        .amount(tx.getAmount())
        .fee(tx.getFee())
        .timestamp(block.getTimestamp())
        .build();

    txHistoryStore.saveEntry(entry);
}
```

---

### AddressStoreImpl.java

#### 将计算移到应用层
**文件**: `src/main/java/io/xdag/db/rocksdb/AddressStoreImpl.java:114`
**TODO**: Move calculation to application layer

**描述**: 将某些计算逻辑从存储层移到应用层，提高代码可测试性和灵活性。

**影响**: 低 - 架构改进
**优先级**: P4
**预估工作量**: 2-3小时

**备注**: 需要审查具体代码确定哪些计算。

---

### OrphanBlockStoreImpl.java

#### ✅ 判断时间问题 - 已修复 (2025-11-04 Phase 8.1)
**文件**: `src/main/java/io/xdag/db/rocksdb/OrphanBlockStoreImpl.java:97`
**状态**: ✅ **已完成** - Phase 8.1

**原TODO**: 判断时间，这里出现过orphanSource获取key时为空的情况

**描述**: 修复 orphanSource 获取 key 时可能为空的问题。

**影响**: 中 - 稳定性
**优先级**: P2
**工作量**: 已实现（lines 97-100 添加了空值检查）

**实现代码**:
```java
for (Pair<byte[],byte[]> an : ans) {
    if (addNum == 0) {
        break;
    }
    // Null check added to handle missing values
    if (an.getValue() == null) {
        continue;
    }
    long time = BytesUtils.bytesToLong(an.getValue(), 0, true);
    // ... process orphan block
}
```

**验证**: 空指针问题已修复，代码运行稳定

---

## ⛏️ 矿池功能

### PoolAwardManagerImpl.java

#### Nonce跟踪
**文件**: `src/main/java/io/xdag/pool/PoolAwardManagerImpl.java:312, 318`
**TODO**:
- Note: Using nonce = 0 for now (TODO: implement proper nonce tracking)
- nonce (TODO: track properly)

**描述**: 实现正确的 nonce 跟踪系统，用于矿池奖励分配。

**影响**: 中 - 功能完整性
**优先级**: P2
**预估工作量**: 4-6小时

**实现建议**:
```java
// Add nonce tracking to wallet or pool manager
private Map<Bytes32, AtomicLong> accountNonces = new ConcurrentHashMap<>();

public long getNextNonce(Bytes32 address) {
    return accountNonces
        .computeIfAbsent(address, k -> new AtomicLong(0))
        .getAndIncrement();
}

// Use in reward distribution
BlockV5 rewardBlock = blockchain.createRewardBlockV5(
    hash,
    recipients,
    amounts,
    sourceKey,
    getNextNonce(sourceAddress),  // Proper nonce tracking
    MIN_GAS.multiply(recipients.size())
);
```

---

## 🏗️ 架构改进

### Transaction.java

#### ✅ 实现签名提取 - 已完成 (2025-11-04)
**文件**: `src/main/java/io/xdag/core/Transaction.java:178, 192, 202`
**状态**: ✅ **已完成** - Phase 2

**描述**: 完整实现 Transaction 签名的提取和验证。

**影响**: 高 - 核心功能
**优先级**: P1 ⚠️
**工作量**: 4小时

**实现方案**:
使用 xdagj-crypto 0.1.4 库的 Signature 类方法:
- `getRBytes()` - 返回 R 组件 (Bytes32)
- `getSBytes()` - 返回 S 组件 (Bytes32)
- `getRecId()` - 返回恢复 ID (byte)

**实现代码**:
```java
public Transaction sign(ECKeyPair keyPair) {
    Bytes32 hash = getHash();
    Signature signature = Signer.sign(hash, keyPair);

    // Extract v, r, s components from Signature object
    Bytes32 rValue = signature.getRBytes();
    Bytes32 sValue = signature.getSBytes();
    int vValue = signature.getRecId() & 0xFF;

    return this.toBuilder()
            .v(vValue)
            .r(rValue)
            .s(sValue)
            .build();
}
```

**验证**: 编译通过，verifySignature() 方法兼容

---

### Commands.java

#### 获取 amount
**文件**: `src/main/java/io/xdag/cli/Commands.java:1202`
**TODO**: Need to get amount from somewhere - Address.getAmount() not available

**描述**: Address 类已删除，需要从其他地方获取 amount 信息。

**影响**: 中 - CLI 功能
**优先级**: P3
**预估工作量**: 2-3小时

**实现建议**:
```java
// Use TransactionStore to get amount
Transaction tx = transactionStore.getTransaction(txHash);
if (tx != null) {
    XAmount amount = tx.getAmount();
    // Use amount...
}
```

---

### SnapshotStoreImpl.java

#### toCanonical 修复
**文件**: `src/main/java/io/xdag/db/rocksdb/SnapshotStoreImpl.java:253`
**TODO FIXME**: toCanonical

**描述**: 签名验证时需要正确处理签名的规范化形式。

**影响**: 高 - 安全性
**优先级**: P1 ⚠️
**预估工作量**: 2-4小时

**实现建议**:
```java
// Use canonical signature format
Signature canonicalSig = Signature.toCanonical(outSig);
if (Signer.verify(hash, canonicalSig, keyPair.getPublicKey())) {
    // Signature valid
}

// Or implement toCanonical in Signature class
public Signature toCanonical() {
    // Ensure S component is in lower half of curve order
    BigInteger s = new BigInteger(1, extractS());
    BigInteger halfCurveOrder = CURVE_ORDER.divide(BigInteger.valueOf(2));

    if (s.compareTo(halfCurveOrder) > 0) {
        s = CURVE_ORDER.subtract(s);
        // Rebuild signature with canonical S
        return new Signature(extractR(), s.toByteArray(), extractV());
    }
    return this;
}
```

---

#### ✅ 恢复交易数量 - 已实现 (2025-11-04 Phase 8.1)
**文件**: `src/main/java/io/xdag/db/rocksdb/SnapshotStoreImpl.java:334`
**状态**: ✅ **已完成** - Phase 8.1

**原TODO**: Restore the transaction quantity for each address from the snapshot

**描述**: 从快照恢复每个地址的交易数量统计。

**影响**: 中 - 数据完整性
**优先级**: P3
**工作量**: 已实现（lines 335-340）

**实现代码**:
```java
// Transaction quantity restoration implemented below (lines 335-340)
else if (Hex.toHexString(address).startsWith("50")) {
    UInt64 exeTxNonceNum = UInt64.fromBytes(Bytes.wrap(iter.value())).toUInt64();
    byte[] TxQuantityKey = BytesUtils.merge(CURRENT_TRANSACTION_QUANTITY,
        BytesUtils.byte32ToArray(BytesUtils.arrayToByte32(
            Arrays.copyOfRange(address, 1, 21))).toArrayUnsafe());
    addressStore.snapshotTxQuantity(TxQuantityKey, exeTxNonceNum);
    addressStore.snapshotExeTxNonceNum(address, exeTxNonceNum);
}
```

**验证**: 交易数量恢复功能完整，快照加载正常

---

## 📊 优先级总结

### P1 - 高优先级（核心功能） ⚠️
1. ✅ Transaction 签名提取实现 - **已完成** (2025-11-04 Phase 2)
2. SnapshotStoreImpl toCanonical 修复（4小时）

**总计**: 4小时（剩余）

### P2 - 中高优先级（安全和稳定性）
1. XdagPow 限制矿池份额（6小时）
2. XdagP2pHandler 多区块请求处理（6小时）
3. PoolAwardManagerImpl nonce 跟踪（6小时）
4. ✅ OrphanBlockStoreImpl 空指针修复 - **已完成** (2025-11-04 Phase 8.1)

**总计**: 18小时

### P3 - 中优先级（功能增强）
1. SyncManager 各项改进（10小时）
2. BlockFinalizationService 存储优化（6小时）
3. BlockchainImpl onNewTxHistory 重构（8小时）
4. 其他架构改进（10小时）

**总计**: 34小时

### P4-P5 - 低优先级（优化和文档）
1. 配置项添加（5小时）
2. 监控和日志（3小时）
3. 文档更新（2小时）

**总计**: 10小时

---

## 📈 实施建议

### 第一阶段（2-3天）
- 完成所有 P1 任务（核心功能）
- 开始 P2 任务（安全性）

### 第二阶段（1周）
- 完成所有 P2 任务
- 开始 P3 任务

### 第三阶段（2周）
- 完成 P3 任务
- 选择性完成 P4-P5 任务

---

## 📝 维护说明

本文档应在以下情况更新：
1. 完成任何 TODO 项后标记为完成
2. 发现新的改进机会时添加新项
3. 优先级变更时更新
4. 每个 Phase 完成后审查

**最后更新**: 2025-11-04
**下次审查**: Phase 8 完成时
