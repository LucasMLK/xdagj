# 混合同步协议 - P2P 消息设计

**版本**: v1.1
**日期**: 2025-11-06
**状态**: Phase 1.5 实现中
**更新**: 添加 Transaction 同步消息

---

## 消息概览

混合同步协议定义了 5 组 10 个新的 P2P 消息，用于高效的区块链同步：

| 消息对 | Request Code | Reply Code | 用途 | 批量大小 |
|--------|--------------|------------|------|---------|
| **Height Query** | 0x1D | 0x1E | 查询对方主链高度 | N/A |
| **Main Blocks** | 0x1F | 0x20 | 批量请求主链块（按高度范围） | 1000 blocks |
| **Epoch Blocks** | 0x21 | 0x22 | 请求 epoch 的所有块 hash | ~10-100 hashes |
| **Blocks Batch** | 0x23 | 0x24 | 批量请求块（按 hash 列表） | 1000 blocks |
| **Transactions Batch** | 0x25 | 0x26 | 批量请求交易（按 hash 列表） | 5000 transactions |

---

## 消息详细设计

### 1. Height Query Messages (0x1D / 0x1E)

#### 1.1 SyncHeightRequestMessage (0x1D)

**功能**: 查询对方节点的主链高度信息

**请求格式**:
```
[Empty body]
```

**字段**: 无（空消息）

**使用场景**: 混合同步协议启动时，首先查询对方高度以确定同步范围

---

#### 1.2 SyncHeightReplyMessage (0x1E)

**功能**: 返回本节点的主链高度信息

**响应格式**:
```
[8 bytes]  mainHeight        - 当前主链高度
[8 bytes]  finalizedHeight   - 最终确定高度 (mainHeight - 16384)
[32 bytes] mainBlockHash     - 当前主链tip块的hash
```

**字段**:
- `mainHeight` (long): 当前主链最高高度
- `finalizedHeight` (long): 最终确定边界高度 = max(0, mainHeight - 16384)
- `mainBlockHash` (Bytes32): 当前主链 tip 块的 hash

**计算逻辑**:
```java
long mainHeight = blockchain.getMainChainLength();
long finalizedHeight = FinalityBoundary.getFinalizedHeight(mainHeight);
Bytes32 tipHash = blockchain.getTopMainBlock().getHash();
```

---

### 2. Main Blocks Messages (0x1F / 0x20)

#### 2.1 SyncMainBlocksRequestMessage (0x1F)

**功能**: 批量请求主链块（按高度范围）

**请求格式**:
```
[8 bytes] fromHeight    - 起始高度（inclusive）
[8 bytes] toHeight      - 结束高度（inclusive）
[4 bytes] maxBlocks     - 最多返回块数（推荐1000）
[1 byte]  isRaw         - 是否返回完整块数据（true=1, false=0）
```

**字段**:
- `fromHeight` (long): 起始高度（包含）
- `toHeight` (long): 结束高度（包含）
- `maxBlocks` (int): 最多返回块数（默认 1000，硬限制 10000）
- `isRaw` (boolean): 是否返回完整块数据（false 仅返回 BlockInfo）

**验证规则**:
```java
// 1. 高度范围验证
if (fromHeight < 0 || toHeight < fromHeight) {
    throw new IllegalArgumentException("Invalid height range");
}

// 2. 批量大小限制
long requestSize = toHeight - fromHeight + 1;
if (requestSize > maxBlocks) {
    toHeight = fromHeight + maxBlocks - 1;  // 自动截断
}

// 3. 硬限制
if (maxBlocks > 10000) {
    maxBlocks = 10000;
}
```

---

#### 2.2 SyncMainBlocksReplyMessage (0x20)

**功能**: 返回主链块列表

**响应格式**:
```
[4 bytes]  blockCount           - 返回的块数量
[变长]     blocks[0..N-1]       - 块数据列表
    每个块:
    [1 byte]   hasBlock          - 是否有数据（1=有, 0=缺失）
    [4 bytes]  blockSize         - 块数据大小（如果 hasBlock=1）
    [变长]     blockData         - 块序列化数据（如果 hasBlock=1）
```

**字段**:
- `blocks` (List<Block>): 主链块列表，按高度升序排列
  - 如果某个高度没有块，对应位置为 null
  - 列表大小 = toHeight - fromHeight + 1

**数据结构**:
```java
public class SyncMainBlocksReplyMessage {
    private final List<Block> blocks;  // 可能包含 null

    // blocks.size() == (toHeight - fromHeight + 1)
    // blocks.get(i) 对应高度 fromHeight + i
}
```

**示例**:
```
请求: fromHeight=100, toHeight=105
响应: blocks = [block100, block101, null, block103, block104, block105]
      // 高度102缺失
```

---

### 3. Epoch Blocks Messages (0x21 / 0x22)

#### 3.1 SyncEpochBlocksRequestMessage (0x21)

**功能**: 请求某个 epoch 的所有块 hash

**请求格式**:
```
[8 bytes] epoch    - Epoch 号（timestamp / 64）
```

**字段**:
- `epoch` (long): Epoch 号

**Epoch 定义**:
```
epoch = timestamp / 64
```

**使用场景**: DAG 区域同步（Phase 2），获取某个 epoch 的所有候选块

---

#### 3.2 SyncEpochBlocksReplyMessage (0x22)

**功能**: 返回 epoch 的所有块 hash

**响应格式**:
```
[8 bytes]  epoch          - Epoch 号
[4 bytes]  hashCount      - Hash 数量
[变长]     hashes[0..N-1] - 块 hash 列表（每个32字节）
```

**字段**:
- `epoch` (long): Epoch 号
- `hashes` (List<Bytes32>): 该 epoch 的所有块 hash

**数据来源**:
```java
List<Block> blocks = dagStore.getCandidateBlocksInEpoch(epoch);
List<Bytes32> hashes = blocks.stream()
    .map(Block::getHash)
    .collect(Collectors.toList());
```

**典型大小**:
- 平均每个 epoch 有 10-50 个块
- 每个 hash 32 bytes
- 总大小：320-1600 bytes

---

### 4. Blocks Batch Messages (0x23 / 0x24)

#### 4.1 SyncBlocksRequestMessage (0x23)

**功能**: 批量请求块（按 hash 列表）

**请求格式**:
```
[4 bytes]  hashCount          - Hash 数量
[变长]     hashes[0..N-1]     - 块 hash 列表（每个32字节）
[1 byte]   isRaw              - 是否返回完整块数据
```

**字段**:
- `hashes` (List<Bytes32>): 要请求的块 hash 列表
- `isRaw` (boolean): 是否返回完整块数据

**限制**:
```java
// 最多一次请求1000个块
if (hashes.size() > 1000) {
    hashes = hashes.subList(0, 1000);
}
```

**使用场景**: Solidification 阶段补全缺失块

---

#### 4.2 SyncBlocksReplyMessage (0x24)

**功能**: 返回请求的块列表

**响应格式**:
```
[4 bytes]  blockCount         - 返回的块数量
[变长]     blocks[0..N-1]     - 块数据列表
    每个块:
    [32 bytes] blockHash      - 块hash
    [1 byte]   hasBlock       - 是否有数据（1=有, 0=缺失）
    [4 bytes]  blockSize      - 块数据大小（如果 hasBlock=1）
    [变长]     blockData      - 块序列化数据（如果 hasBlock=1）
```

**字段**:
- `blocks` (List<Block>): 块列表
  - 顺序与请求的 hash 列表对应
  - 如果某个 hash 没有找到，对应位置为 null

**数据结构**:
```java
public class SyncBlocksReplyMessage {
    private final List<Block> blocks;  // 可能包含 null

    // blocks.size() == request.hashes.size()
    // blocks.get(i) 对应 request.hashes.get(i)
}
```

---

## 消息序列化

### 编码规则

使用 `SimpleEncoder` 进行编码：

```java
public void encode(SimpleEncoder enc) {
    enc.writeLong(value1);           // 8 bytes
    enc.writeInt(value2);             // 4 bytes
    enc.writeBoolean(value3);         // 1 byte
    enc.writeBytes(bytes);            // 4 bytes length + data

    // List 编码
    enc.writeInt(list.size());        // 列表长度
    for (Item item : list) {
        // 编码每个元素
    }
}
```

### 解码规则

使用 `SimpleDecoder` 进行解码：

```java
public MyMessage(byte[] body) {
    super(MessageCode.XXX, YyyMessage.class);
    SimpleDecoder dec = new SimpleDecoder(body);

    this.value1 = dec.readLong();
    this.value2 = dec.readInt();
    this.value3 = dec.readBoolean();
    this.bytes = dec.readBytes();

    // List 解码
    int count = dec.readInt();
    this.list = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
        // 解码每个元素
    }

    this.body = body;
}
```

---

## 使用示例

### 场景1: 查询对方高度

```java
// 发送请求
SyncHeightRequestMessage request = new SyncHeightRequestMessage();
channel.sendMessage(request);

// 接收响应
SyncHeightReplyMessage reply = channel.waitForResponse(SyncHeightReplyMessage.class);
long remoteHeight = reply.getMainHeight();
long remoteFinalized = reply.getFinalizedHeight();
```

### 场景2: 批量同步主链

```java
// 请求主链块 [1000, 2000)
SyncMainBlocksRequestMessage request = new SyncMainBlocksRequestMessage(
    1000,    // fromHeight
    1999,    // toHeight
    1000,    // maxBlocks
    false    // 不需要完整数据
);
channel.sendMessage(request);

// 接收响应
SyncMainBlocksReplyMessage reply = channel.waitForResponse(SyncMainBlocksReplyMessage.class);
List<Block> blocks = reply.getBlocks();  // 1000个块（可能有null）

// 处理块
for (int i = 0; i < blocks.size(); i++) {
    Block block = blocks.get(i);
    if (block != null) {
        long height = 1000 + i;
        blockchain.importBlock(block);
    }
}
```

### 场景3: DAG区域同步

```java
// 请求 epoch 5000 的所有块
SyncEpochBlocksRequestMessage request = new SyncEpochBlocksRequestMessage(5000);
channel.sendMessage(request);

// 接收响应
SyncEpochBlocksReplyMessage reply = channel.waitForResponse(SyncEpochBlocksReplyMessage.class);
List<Bytes32> hashes = reply.getHashes();

// 过滤已有的块
List<Bytes32> missingHashes = hashes.stream()
    .filter(hash -> !blockStore.hasBlock(hash))
    .collect(Collectors.toList());

// 批量请求缺失的块
SyncBlocksRequestMessage blocksReq = new SyncBlocksRequestMessage(missingHashes, true);
channel.sendMessage(blocksReq);

SyncBlocksReplyMessage blocksReply = channel.waitForResponse(SyncBlocksReplyMessage.class);
List<Block> blocks = blocksReply.getBlocks();
```

---

## 性能指标

### 网络带宽

| 消息类型 | 请求大小 | 响应大小 | 备注 |
|---------|---------|---------|------|
| Height Query | ~0 bytes | 48 bytes | 极小 |
| Main Blocks (1000) | 25 bytes | ~500KB | BlockInfo模式 |
| Main Blocks (1000, raw) | 25 bytes | ~50MB | 完整块数据 |
| Epoch Blocks | 8 bytes | ~320-1600 bytes | Hash列表 |
| Blocks Batch (100) | ~3KB | ~5MB | 完整块数据 |

### 性能目标

- **Height Query**: < 10ms (P99)
- **Main Blocks (1000)**: < 500ms (P99)
- **Epoch Blocks**: < 100ms (P99)
- **Blocks Batch (100)**: < 1s (P99)

---

## 实现清单

### Phase 1.5 任务

- [x] 扩展 MessageCode 枚举（0x1D - 0x24）
- [ ] 实现 SyncHeightRequestMessage
- [ ] 实现 SyncHeightReplyMessage
- [ ] 实现 SyncMainBlocksRequestMessage
- [ ] 实现 SyncMainBlocksReplyMessage
- [ ] 实现 SyncEpochBlocksRequestMessage
- [ ] 实现 SyncEpochBlocksReplyMessage
- [ ] 实现 SyncBlocksRequestMessage
- [ ] 实现 SyncBlocksReplyMessage
- [ ] 更新 MessageFactory 注册新消息
- [ ] 编写单元测试

---

**最后更新**: 2025-11-06
**作者**: Claude Code
**状态**: 设计完成，开始实现

---

### 5. Transactions Batch Messages (0x25 / 0x26)

#### 5.1 SyncTransactionsRequestMessage (0x25)

**功能**: 批量请求 Transaction（按 hash 列表）

**请求格式**:
```
[4 bytes]  hashCount          - Hash 数量
[变长]     hashes[0..N-1]     - Transaction hash 列表（每个32字节）
```

**字段**:
- `hashes` (List<Bytes32>): 要请求的 Transaction hash 列表

**限制**:
```java
// 最多一次请求5000个 Transaction
if (hashes.size() > 5000) {
    hashes = hashes.subList(0, 5000);
}
```

**使用场景**: 
1. Block 同步完成后，检测缺失的 Transaction
2. Solidification 阶段批量补全缺失的 Transaction

**数据来源**:
```java
// 从 Block 中提取 Transaction 引用
Set<Bytes32> missingTxHashes = new HashSet<>();
for (Block block : syncedBlocks) {
    for (Link link : block.getTransactionLinks()) {
        Bytes32 txHash = link.getTargetHash();
        if (!transactionStore.hasTransaction(txHash)) {
            missingTxHashes.add(txHash);
        }
    }
}
```

---

#### 5.2 SyncTransactionsReplyMessage (0x26)

**功能**: 返回请求的 Transaction 列表

**响应格式**:
```
[4 bytes]  txCount            - 返回的 Transaction 数量
[变长]     transactions[0..N-1] - Transaction 数据列表
    每个 Transaction:
    [32 bytes] txHash         - Transaction hash
    [1 byte]   hasTx          - 是否有数据（1=有, 0=缺失）
    [4 bytes]  txSize         - Transaction 数据大小（如果 hasTx=1）
    [变长]     txData         - Transaction 序列化数据（如果 hasTx=1）
```

**字段**:
- `transactions` (List<Transaction>): Transaction 列表
  - 顺序与请求的 hash 列表对应
  - 如果某个 hash 没有找到，对应位置为 null

**数据结构**:
```java
public class SyncTransactionsReplyMessage {
    private final List<Transaction> transactions;  // 可能包含 null

    // transactions.size() == request.hashes.size()
    // transactions.get(i) 对应 request.hashes.get(i)
}
```

---

## Transaction 同步流程

### 完整的同步流程

```
Phase 1: 线性主链同步
├─ 同步主链 Block（包含 Transaction 引用）
└─ 收集缺失的 Transaction hash

Phase 2: DAG 区域同步
├─ 同步 DAG Block（包含 Transaction 引用）
└─ 收集缺失的 Transaction hash

Phase 3: Solidification
├─ 批量请求缺失的 Block
├─ 批量请求缺失的 Transaction ← 新增
└─ 验证完整性
```

### 场景：同步后补全 Transaction

```java
// Step 1: 同步完 Block 后，收集缺失的 Transaction
Set<Bytes32> missingTxHashes = new HashSet<>();

for (Block block : syncedBlocks) {
    for (Link txLink : block.getTransactionLinks()) {
        Bytes32 txHash = txLink.getTargetHash();
        if (!transactionStore.hasTransaction(txHash)) {
            missingTxHashes.add(txHash);
        }
    }
}

log.info("Found {} missing transactions", missingTxHashes.size());

// Step 2: 批量请求缺失的 Transaction（每次最多5000个）
List<Bytes32> hashList = new ArrayList<>(missingTxHashes);
for (int i = 0; i < hashList.size(); i += 5000) {
    int end = Math.min(i + 5000, hashList.size());
    List<Bytes32> batch = hashList.subList(i, end);

    // 发送请求
    SyncTransactionsRequestMessage request = new SyncTransactionsRequestMessage(batch);
    channel.sendMessage(request);

    // 接收响应
    SyncTransactionsReplyMessage reply = channel.waitForResponse(
        SyncTransactionsReplyMessage.class
    );

    // 保存 Transaction
    for (Transaction tx : reply.getTransactions()) {
        if (tx != null) {
            transactionStore.saveTransaction(tx);
        }
    }
}

log.info("Transaction solidification completed");
```

---

## 设计权衡

### 为什么需要专门的 Transaction 同步？

**场景对比**:

#### 场景 A: 没有 Transaction 同步消息
```
1. 同步 Block（包含 1000 个 Transaction 引用）
2. 检测到 800 个缺失的 Transaction
3. 逐个请求缺失的 Transaction（800 次网络往返）❌
   或通过通用的 solidification 阶段请求（效率低）
```

#### 场景 B: 有 Transaction 同步消息
```
1. 同步 Block（包含 1000 个 Transaction 引用）
2. 检测到 800 个缺失的 Transaction
3. 批量请求 Transaction（1 次网络往返）✅
```

**性能提升**: **800x 减少网络往返**

---

## 更新后的性能指标

### 网络带宽

| 消息类型 | 请求大小 | 响应大小 | 备注 |
|---------|---------|---------|------|
| Height Query | ~0 bytes | 48 bytes | 极小 |
| Main Blocks (1000) | 25 bytes | ~500KB | BlockInfo模式 |
| Main Blocks (1000, raw) | 25 bytes | ~50MB | 完整块数据 |
| Epoch Blocks | 8 bytes | ~320-1600 bytes | Hash列表 |
| Blocks Batch (100) | ~3KB | ~5MB | 完整块数据 |
| **Transactions Batch (5000)** | ~160KB | ~25MB | **新增** |

### 性能目标

- **Height Query**: < 10ms (P99)
- **Main Blocks (1000)**: < 500ms (P99)
- **Epoch Blocks**: < 100ms (P99)
- **Blocks Batch (100)**: < 1s (P99)
- **Transactions Batch (5000)**: < 2s (P99)

---

## 更新的实现清单

### Phase 1.5 任务

- [x] 扩展 MessageCode 枚举（0x1D - 0x26）
- [x] 实现 SyncHeightRequestMessage
- [x] 实现 SyncHeightReplyMessage
- [x] 实现 SyncMainBlocksRequestMessage
- [x] 实现 SyncMainBlocksReplyMessage
- [x] 实现 SyncEpochBlocksRequestMessage
- [x] 实现 SyncEpochBlocksReplyMessage
- [x] 实现 SyncBlocksRequestMessage
- [x] 实现 SyncBlocksReplyMessage
- [x] 实现 SyncTransactionsRequestMessage ← 新增
- [x] 实现 SyncTransactionsReplyMessage ← 新增
- [x] 更新 MessageFactory 注册新消息
- [x] 扩展 SimpleDecoder 支持固定长度读取
- [x] 编写单元测试（16个测试全部通过）✅

---

## 性能优化建议

### 大规模数据同步优化

对于**5年历史数据**（~2.5M blocks, ~66GB）同步场景，当前串行协议需要 **~29小时**。
详细的性能分析和优化方案见：[HYBRID_SYNC_PERFORMANCE_ANALYSIS.md](./HYBRID_SYNC_PERFORMANCE_ANALYSIS.md)

#### 核心优化策略

| 优化策略 | 性能提升 | 优先级 | 实现复杂度 |
|---------|---------|--------|----------|
| **并行批量下载** (8并发) | 8x加速 | **高** | 中等 |
| **渐进式同步** (快速可用) | 200x首次可用 | **高** | 中等 |
| **LZ4压缩传输** | 30%网络节省 | 中 | 低 |
| **检查点快速同步** | 10-20分钟同步 | 中 | 中等 |
| **差量同步** | 秒级增量 | 高 | 低 |

#### 优化后性能对比

| 指标 | 当前串行 | 优化后 | 改善 |
|------|---------|--------|------|
| **首次可用时间** | 29 hours | **5-10 minutes** | **~200x** |
| **完全同步时间** | 29 hours | **27 minutes** | **~64x** |
| **网络传输** | 66 GB | 46 GB (压缩) | **-30%** |

#### 实施建议

**Phase 1 (必须)**: 并行下载 + 渐进式同步
- 实现 `ParallelSyncCoordinator` (8-12并发)
- 实现 `ProgressiveSyncManager` (快速启动模式)
- 预期: 5-10分钟可用，27分钟完全同步

**Phase 2 (推荐)**: LZ4压缩 + 差量同步
- 实现 `CompressedMessageCodec`
- 实现 `DeltaSyncManager`
- 预期: 30%网络节省，秒级增量同步

**Phase 3 (可选)**: 检查点同步 + 选择性模式
- 实现 `CheckpointSyncManager`
- 支持 Light/Pruned/Archive 模式

---

**最后更新**: 2025-11-06
**作者**: Claude Code
**状态**: 设计完成（v1.1 - 添加 Transaction 同步 + 性能优化建议），开始实现
