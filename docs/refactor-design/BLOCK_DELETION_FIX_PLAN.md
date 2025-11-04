# Block/Address/XdagBlock删除后的修复计划

**日期**: 2025-11-03
**状态**: 🚧 修复中
**目标**: 用BlockV5完全重写xdagj

---

## ✅ 已确认：核心接口100% v5.1

### 1. Blockchain接口 ✅
```java
// src/main/java/io/xdag/core/Blockchain.java
✅ tryToConnect(BlockV5)
✅ createMainBlockV5()
✅ createGenesisBlockV5()
✅ createRewardBlockV5()
✅ getBlockByHash() → BlockV5
✅ getBlockByHeight() → BlockV5
✅ listMainBlocks() → List<BlockV5>
✅ listMinedBlocks() → List<BlockV5>
✅ getBlocksByTime() → List<BlockV5>
❌ 删除: getBlockTxHistoryByAddress() - TxHistory功能暂时移除
```

### 2. BlockStore接口 ✅
```java
// src/main/java/io/xdag/db/BlockStore.java
✅ saveBlockV5(BlockV5)
✅ getBlockV5ByHash(Bytes32, boolean) → BlockV5
✅ getRawBlockV5ByHash(Bytes32) → BlockV5
✅ getBlockV5InfoByHash(Bytes32) → BlockV5
✅ saveBlockInfoV2(BlockInfo)
✅ getBlockReferences(Bytes32) → List<Bytes32>
```

### 3. FinalizedBlockStore接口 ✅
```java
// src/main/java/io/xdag/db/store/FinalizedBlockStore.java
✅ saveBlockInfo(BlockInfo)
✅ getBlockInfoByHash(Bytes32) → Optional<BlockInfo>
✅ getMainBlockInfoByHeight(long) → Optional<BlockInfo>
✅ 完全基于BlockInfo，无Block/Address引用
```

---

## 🔴 需要修复的文件 (按优先级)

### Priority 1: 核心共识 (BlockchainImpl)

**文件**: `src/main/java/io/xdag/core/BlockchainImpl.java`

**问题类型**:
1. ❌ `LinkedHashMap<Bytes, Block> memOrphanPool` - 需要改为BlockV5
2. ❌ `setMain(Block)` - 需要删除或改为BlockV5
3. ❌ `unSetMain(Block)` - 需要删除或改为BlockV5
4. ❌ `applyBlock(Block)` - 已有applyBlockV2(BlockV5)，删除旧版
5. ❌ `unApplyBlock(Block)` - 需要改为BlockV5版本
6. ❌ `getMaxDiffLink(Block)` - 已有getMaxDiffLinkV5()，删除旧版
7. ❌ `calculateBlockDiff(Block)` - 需要改为BlockV5版本
8. ❌ `isAccountTx(Block)`, `isTxBlock(Block)` - 需要改为BlockV5版本
9. ❌ `onNewBlock(Block)` - 已有onNewBlockV5()，删除旧版
10. ❌ `blockEqual(Block, Block)` - 需要改为BlockV5版本
11. ❌ `processNonceAfterTransactionExecution(Address)` - Address已删除
12. ❌ `onNewTxHistory()` - TxHistory功能暂时移除
13. ❌ `getBlockTxHistoryByAddress()` - TxHistory功能暂时移除

**修复策略**:
```
阶段1: 删除已有BlockV5版本的旧方法
- 删除onNewBlock(Block) → 已有onNewBlockV5()
- 删除applyBlock(Block) → 已有applyBlockV2(BlockV5)
- 删除getMaxDiffLink(Block) → 已有getMaxDiffLinkV5()

阶段2: 删除setMain/unSetMain (Phase 8.3.3决策)
- 删除setMain(Block) - 核心共识，暂时不实现
- 删除unSetMain(Block) - 分叉逻辑，暂时不实现

阶段3: 修改内存池类型
- memOrphanPool: LinkedHashMap<Bytes, BlockV5>

阶段4: 删除TxHistory相关
- 删除onNewTxHistory()
- 删除getBlockTxHistoryByAddress()

阶段5: 修改辅助方法
- isAccountTx(BlockV5), isTxBlock(BlockV5)
- blockEqual(BlockV5, BlockV5)
- calculateBlockDiff(BlockV5)
```

---

### Priority 2: 存储实现 (BlockStoreImpl)

**文件**: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`

**问题类型**:
1. ❌ `saveBlock(Block)` - 已有saveBlockV5()，删除
2. ❌ `getBlockByHash(Bytes32, boolean) → Block` - 已有getBlockV5ByHash()，删除
3. ❌ `getBlockByHeight(long) → Block` - 需要删除
4. ❌ `getBlocksUsedTime() → List<Block>` - 需要改为List<BlockV5>

**修复策略**:
```
1. 删除所有返回Block的方法
2. 确保只有BlockV5相关方法存在
3. 删除LegacyBlockInfo相关方法
```

---

### Priority 3: 网络层

**文件**:
- `src/main/java/io/xdag/net/ChannelManager.java`
- `src/main/java/io/xdag/net/XdagP2pHandler.java`
- `src/main/java/io/xdag/p2p/XdagP2pEventHandler.java`

**问题类型**:
1. ❌ SyncBlock引用 - SyncBlock包含Block对象
2. ❌ 网络消息处理Block对象

**修复策略**:
```
1. 检查SyncBlock是否还在用
2. 如果SyncBlock已deprecated，删除相关代码
3. 确保网络层只处理BlockV5
```

---

### Priority 4: CLI和Pool

**文件**:
- `src/main/java/io/xdag/cli/Commands.java`
- `src/main/java/io/xdag/pool/PoolAwardManagerImpl.java`

**问题类型**:
1. ❌ Address使用 - CLI显示地址
2. ❌ Block查询 - 区块浏览器功能

**修复策略**:
```
1. Address → Bytes32 直接使用
2. Block查询 → 改为BlockV5
```

---

### Priority 5: 其他组件

**文件**:
- `src/main/java/io/xdag/consensus/SyncManager.java` - SyncBlock
- `src/main/java/io/xdag/consensus/XdagSync.java` - Sums统计
- `src/main/java/io/xdag/core/BlockInfo.java` - LegacyBlockInfo转换
- `src/main/java/io/xdag/core/TxHistory.java` - Address字段
- `src/main/java/io/xdag/db/SnapshotStore.java` - LegacyBlockInfo
- `src/main/java/io/xdag/db/OrphanBlockStore.java` - Block返回值

---

## 🎯 修复执行计划

### Step 1: BlockchainImpl清理 (1-2小时)

```bash
# 删除已有BlockV5版本的方法
1. 删除onNewBlock(Block) - line 624
2. 删除applyBlock(Block) - line 951
3. 删除getMaxDiffLink(Block) - line 1930
4. 删除setMain(Block) - line 1255
5. 删除unSetMain(Block) - line 1333
6. 删除unApplyBlock(Block) - line 1139

# 修改内存池类型
7. memOrphanPool: LinkedHashMap<Bytes, Block>
   → LinkedHashMap<Bytes, BlockV5>

# 删除TxHistory
8. 删除onNewTxHistory() - line 475
9. 删除getBlockTxHistoryByAddress() - line 582

# 修改辅助方法签名
10. isAccountTx(Block) → 删除或改为BlockV5
11. isTxBlock(Block) → 删除或改为BlockV5
12. blockEqual(Block, Block) → 删除
13. processNonceAfterTransactionExecution(Address) → 删除
14. calculateBlockDiff(Block) → 改为BlockV5
```

### Step 2: 删除内部helper方法 (30分钟)

```bash
# 删除Block相关helper方法
1. getBlockByHashInternal(Bytes32, boolean) → Block - 删除
2. getBlockByHeightInternal(long) → Block - 删除
3. getBlockByHeightNew(long) → Block - 删除
4. listMainBlocksByHeight(int) → List<Block> - 删除
5. updateBlockFlag(Block, byte, boolean) - 已有updateBlockV5Flag
6. updateBlockRef(Block, Address) - 已有updateBlockV5Ref
7. acceptAmount(Block, XAmount) - 改为BlockV5版本
8. subtractAndAccept(Block, XAmount) - 改为BlockV5版本
9. addAndAccept(Block, XAmount) - 改为BlockV5版本
10. saveBlock(Block) - 删除
```

### Step 3: 存储层清理 (30分钟)

```bash
# BlockStoreImpl
1. 删除所有返回Block的方法
2. 删除saveBlock(Block)
3. 删除getBlockByHash() → Block
4. 删除getBlockByHeight() → Block
5. 删除getBlocksUsedTime() → List<Block>
6. 删除saveBlockInfo(LegacyBlockInfo)
```

### Step 4: 网络层修复 (1小时)

```bash
# 检查SyncManager
1. SyncBlock类是否还在用？
2. 如果不用，删除相关代码

# ChannelManager, XdagP2pHandler
3. 替换Block引用为BlockV5
4. 删除SyncBlock处理逻辑
```

### Step 5: CLI和Pool修复 (30分钟)

```bash
# Commands.java
1. Address引用 → Bytes32
2. Block查询 → BlockV5

# PoolAwardManagerImpl.java
3. Address引用 → Bytes32
```

### Step 6: 清理其他组件 (30分钟)

```bash
# BlockInfo.java
1. 删除fromLegacy(), toLegacy()方法

# TxHistory.java
2. 删除Address字段 → Bytes32

# OrphanBlockStore
3. 返回值Block → 改为Bytes32
```

---

## 📝 修复检查清单

### 编译检查
```bash
# 每完成一个阶段，执行编译检查
mvn clean compile -DskipTests
```

### 接口完整性检查
```bash
# 确保所有接口方法都有实现
grep -rn "NotImplementedError\|TODO" src/main/java/io/xdag/core/BlockchainImpl.java
```

### 引用检查
```bash
# 确保没有Block/Address/XdagBlock残留
grep -rn "import io.xdag.core.Block;" src/main/java --include="*.java"
grep -rn "import io.xdag.core.Address;" src/main/java --include="*.java"
grep -rn "import io.xdag.core.XdagBlock;" src/main/java --include="*.java"
```

---

## 🚀 开始执行

**当前状态**: 准备开始修复
**预估时间**: 4-6小时
**风险等级**: 🟡 中等（接口已清理，主要是实现层修复）

**下一步**:
1. 开始修复BlockchainImpl (Priority 1)
2. 逐步删除Block相关方法
3. 每个阶段完成后编译验证
