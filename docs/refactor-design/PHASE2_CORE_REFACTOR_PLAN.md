# Phase 2 核心重构 - 实施计划

**日期**: 2025-10-24
**当前状态**: Phase 0-1 已完成，Phase 2 部分完成（FinalizedBlockStore）
**本次任务**: Phase 2 核心 - 重写 BlockStore 使用新的 BlockInfo

---

## 当前状态分析

### ✅ 已完成
1. **Phase 0-1**: 新数据结构
   - BlockInfo（不可变、类型安全、@Value）
   - BlockLink
   - ChainStats
   - Snapshot
   - CompactSerializer（VarInt 编码）

2. **Phase 2 partial**: FinalizedBlockStore
   - FinalizedBlockStore 实现
   - Bloom Filter + LRU Cache
   - 自动最终化服务
   - **注意**: 这个是额外功能，不影响核心重构

### ❌ 待完成（核心任务）

#### 问题 1: Block.java 使用 LegacyBlockInfo
```java
// 当前 Block.java (line 66)
private LegacyBlockInfo info;  // ❌ 应该用新的 BlockInfo

// 目标
private BlockInfo info;  // ✅ 使用新的不可变 BlockInfo
```

#### 问题 2: BlockStore 接口仍有 SUMS 方法
```java
// 当前 BlockStore.java
void saveBlockSums(Block block);           // ❌ 应该删除
MutableBytes getSums(String key);          // ❌ 应该删除
void putSums(String key, Bytes sums);      // ❌ 应该删除
void updateSum(String key, ...);           // ❌ 应该删除
int loadSum(long starttime, ...);          // ❌ 应该删除
```

#### 问题 3: BlockStore 接口使用 LegacyBlockInfo
```java
// 当前 BlockStore.java (line 60)
void saveBlockInfo(LegacyBlockInfo blockInfo);  // ❌ 应该用 BlockInfo
```

#### 问题 4: 缺少新索引方法
```java
// 目标: 需要添加的方法
List<Block> getMainBlocksByHeightRange(long from, long to);  // 主块范围查询
List<Block> getBlocksByEpoch(long epoch);                     // Epoch 查询
List<Bytes32> getBlockReferences(Bytes32 hash);               // 引用查询
```

#### 问题 5: BlockStoreImpl 使用 Kryo 序列化
- 应该使用 CompactSerializer（已实现）
- 目标大小：~180 bytes（当前 ~300 bytes）

---

## Phase 2 核心重构步骤

### Step 1: 准备工作（备份和分支）

```bash
# 1. 提交当前 FinalizedBlockStore 工作
git add docs/refactor-design/PHASE2_CORE_REFACTOR_PLAN.md
git commit -m "docs: Add Phase 2 core refactor plan"

# 2. 创建新分支用于核心重构
git checkout -b refactor/phase2-core-blockstore

# 3. 标记当前状态
git tag phase2-partial-before-core-refactor
```

### Step 2: 更新 BlockStore 接口

**文件**: `src/main/java/io/xdag/db/BlockStore.java`

**任务 2.1**: 删除 SUMS 相关方法
```java
// 删除这些方法（lines 105-113）
- void saveBlockSums(Block block);
- MutableBytes getSums(String key);
- void putSums(String key, Bytes sums);
- void updateSum(String key, long sum, long size, long index);
- int loadSum(long starttime, long endtime, MutableBytes sums);
```

**任务 2.2**: 修改方法签名使用 BlockInfo
```java
// 修改 (line 60)
- void saveBlockInfo(LegacyBlockInfo blockInfo);
+ void saveBlockInfo(BlockInfo blockInfo);
```

**任务 2.3**: 添加新索引方法
```java
// 添加主块范围查询
List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight);

// 添加 Epoch 查询
List<Block> getBlocksByEpoch(long epoch);

// 添加引用查询（用于 Solidification）
List<Bytes32> getBlockReferences(Bytes32 blockHash);

// 添加批量查询（性能优化）
List<Block> getBlocksByHashes(List<Bytes32> hashes);
```

**任务 2.4**: 删除 SUMS 相关常量
```java
// 删除 (line 41)
- byte SUMS_BLOCK_INFO = (byte) 0x40;

// 删除 (line 48)
- String SUM_FILE_NAME = "sums.dat";
```

### Step 3: 更新 Block.java 使用新的 BlockInfo

**文件**: `src/main/java/io/xdag/core/Block.java`

**任务 3.1**: 修改 info 字段类型
```java
// 修改 (line 66)
- private LegacyBlockInfo info;
+ private BlockInfo info;
```

**任务 3.2**: 添加转换构造函数（过渡期）
```java
// 临时保留，用于兼容现有代码
public Block(LegacyBlockInfo legacyInfo) {
    this.info = BlockInfo.fromLegacy(legacyInfo);
    // ... 其他初始化
}

// 新的主构造函数
public Block(BlockInfo info) {
    this.info = info;
    // ... 其他初始化
}
```

**任务 3.3**: 更新所有 getter/setter
```java
// 修改返回类型
- public LegacyBlockInfo getInfo()
+ public BlockInfo getInfo()

// 修改参数类型
- public void setInfo(LegacyBlockInfo info)
+ public void setInfo(BlockInfo info)
```

**任务 3.4**: 更新 Block 构造函数逻辑
- 当前 Block 有多个构造函数，都使用 `new LegacyBlockInfo()`
- 需要改为使用 `BlockInfo.builder()`

### Step 4: 更新 BlockStoreImpl 实现

**文件**: `src/main/java/io/xdag/db/rocksdb/BlockStoreImpl.java`

**任务 4.1**: 删除 SUMS 实现
```java
// 删除这些方法的实现
- @Override public void saveBlockSums(Block block)
- @Override public MutableBytes getSums(String key)
- @Override public void putSums(String key, Bytes sums)
- @Override public void updateSum(...)
- @Override public int loadSum(...)
```

**任务 4.2**: 删除 SUMS RocksDB 列族
```java
// 删除 sums.dat 相关代码
- 删除 SUMS_BLOCK_INFO 列族
- 删除 sums.dat 文件读写代码
```

**任务 4.3**: 修改序列化使用 CompactSerializer
```java
// 当前使用 Kryo
- byte[] serialized = serialize(blockInfo, BlockInfo.class);

// 改为使用 CompactSerializer
+ byte[] serialized = CompactSerializer.serialize(blockInfo);
```

**任务 4.4**: 实现新索引方法
```java
@Override
public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight) {
    // 使用 BLOCK_HEIGHT 列族
    // 迭代器范围扫描
    // 只返回主块（检查 BI_MAIN 标志）
}

@Override
public List<Block> getBlocksByEpoch(long epoch) {
    // 新的 BLOCK_EPOCH 列族
    // 返回该 epoch 的所有块
}

@Override
public List<Bytes32> getBlockReferences(Bytes32 blockHash) {
    // 新的 BLOCK_REFS 列族
    // 返回引用该块的所有块哈希
}
```

**任务 4.5**: 添加新的 RocksDB 列族
```java
// 在 DatabaseName.java 中添加
+ BLOCK_EPOCH    // Epoch 索引: epoch -> List<blockHash>
+ MAIN_BLOCKS    // 主块索引: height -> blockHash (只包含主块)
+ BLOCK_REFS     // 引用索引: blockHash -> List<referencingHashes>
```

### Step 5: 渐进式迁移策略

由于这是破坏性变更，我们需要：

**选项 A: 双写过渡期（推荐）**
```java
// 过渡期：同时支持新旧格式
public void saveBlock(Block block) {
    // 1. 保存新格式（BlockInfo）
    BlockInfo newInfo = block.getInfo();
    byte[] serialized = CompactSerializer.serialize(newInfo);
    db.put(HASH_BLOCK_INFO, key, serialized);

    // 2. 过渡期：也保存旧格式（可选，用于回滚）
    if (enableLegacyCompat) {
        LegacyBlockInfo legacy = newInfo.toLegacy();
        // 保存到旧位置
    }
}
```

**选项 B: 快照迁移（REFACTOR_PLAN.md 建议）**
- 导出所有块数据（旧格式）
- 转换为新格式
- 导入到新数据库
- 不需要双写

### Step 6: 测试计划

**6.1 单元测试**
- [ ] BlockStoreImplTest: 测试新的序列化
- [ ] BlockStoreImplTest: 测试新的索引查询
- [ ] BlockTest: 测试 Block 使用新的 BlockInfo

**6.2 集成测试**
- [ ] 端到端保存和读取测试
- [ ] 迁移测试（旧数据 → 新数据）
- [ ] 性能测试（对比 Kryo vs CompactSerializer）

**6.3 性能基准**
- [ ] 写入 TPS: 目标 5000+ (当前 200)
- [ ] 读取延迟: 目标 0.2ms (当前 2ms)
- [ ] 存储大小: 目标 ~180 bytes (当前 ~300 bytes)

---

## 实施顺序（最小化破坏）

### 阶段 1: 接口和类型更新（不破坏现有代码）
1. 创建新分支
2. 更新 BlockStore 接口（添加新方法，暂不删除旧方法）
3. 添加 Block 的新构造函数（BlockInfo 版本）
4. 编译通过

### 阶段 2: 实现新功能（增量添加）
1. 在 BlockStoreImpl 实现新索引方法
2. 添加 CompactSerializer 序列化支持（双写）
3. 测试新功能
4. 编译通过，所有测试通过

### 阶段 3: 迁移和删除（破坏性变更）
1. 修改 Block.java info 字段类型
2. 删除 SUMS 方法
3. 删除 Kryo 序列化
4. 修复所有编译错误
5. 更新所有测试

### 阶段 4: 验证和优化
1. 运行所有测试
2. 性能基准测试
3. 代码审查
4. 文档更新

---

## 风险和缓解

### 风险 1: 大量编译错误
**影响**: 修改 Block.info 类型会导致数百个编译错误
**缓解**:
- 分步进行，先添加新接口
- 使用 IDE 的自动重构工具
- 编写迁移脚本辅助

### 风险 2: 数据不兼容
**影响**: 新旧数据格式不兼容
**缓解**:
- 使用 fromLegacy/toLegacy 转换
- 双写过渡期
- 或使用快照迁移

### 风险 3: 性能回退
**影响**: 新实现可能比旧实现慢
**缓解**:
- 每步都做性能测试
- 与基准对比
- 及时调优

---

## 成功标准

### 功能标准
- [ ] 所有 SUMS 方法已删除
- [ ] Block.info 使用新的 BlockInfo
- [ ] 所有新索引方法已实现
- [ ] 使用 CompactSerializer 序列化

### 性能标准
- [ ] 写入 TPS > 5000
- [ ] 读取延迟 < 0.2ms
- [ ] 序列化大小 ~180 bytes

### 质量标准
- [ ] 所有测试通过
- [ ] 无编译警告
- [ ] 代码覆盖率 > 80%

---

## 预计工作量

- **阶段 1**: 2-3 小时（接口更新）
- **阶段 2**: 4-6 小时（实现新功能）
- **阶段 3**: 6-8 小时（迁移和删除）
- **阶段 4**: 2-4 小时（验证优化）

**总计**: 14-21 小时（2-3 天）

---

## 当前行动

**立即开始**: 阶段 1 - 接口和类型更新

让我开始执行...
