# Phase 3 Migration Plan - BlockV5 Activation

**Date**: 2025-10-30
**Branch**: refactor/core-v5.1
**Current State**: Phase 2 完成，0 编译错误

---

## 🎯 Phase 3 目标

完整激活 v5.1 架构：
1. ✅ 激活 BlockV5 作为新的 Block 实现
2. ✅ 消除过渡类 (Address, BlockWrapper)
3. ✅ 完整迁移到 Link-based 设计
4. ✅ 恢复并通过所有测试

---

## 📊 当前状态分析

### 过渡类现状

```
Block.java (旧)
├── 使用者: Commands.java, Wallet.java, BlockchainImpl.java
├── 依赖: Address, XdagField
└── 特点: 512字节固定结构，使用 Address 引用

Address.java (过渡类)
├── 使用者: Block, BlockchainImpl, Commands, PoolAwardManager
├── 32字节 + 类型 + 金额
└── 将被替换为: Link (33字节，只有 hash + type)

BlockWrapper.java (过渡类)
├── 使用者: Wallet.java (createTransactionBlock)
├── 简单包装: Block + TTL
└── 将被替换为: 直接使用 Block + SyncBlock (网络层已完成)
```

### BlockV5 位置

BlockV5 代码位于：
- **git commit 5c8f3643** 的 `Block.java`
- 在 Phase 2 Step 1 中被重命名为 Block.java
- 在 Phase 2 Step 2 中被旧 Block 替换

需要恢复该版本的 Block.java 作为 BlockV5.java

---

## 🗺️ 迁移策略

### 策略选择：**共存迁移** (Coexistence Migration)

**理由**:
1. **风险控制**: Phase 2 的教训 - 一次性替换导致 200 错误
2. **渐进式**: 允许逐个文件迁移，随时验证
3. **可回滚**: 旧实现保留，迁移失败可快速回退

**实施方案**:
```
步骤 1: 恢复 BlockV5.java (与旧 Block 共存)
步骤 2: 创建工厂类/适配器支持两种 Block
步骤 3: 逐个迁移使用方 (Commands → Wallet → BlockchainImpl)
步骤 4: 删除旧 Block，BlockV5 重命名为 Block
步骤 5: 清理过渡类 (Address, BlockWrapper)
```

---

## 📝 详细步骤

### Step 1: 恢复 BlockV5 和创建适配层 (1天)

**1.1 恢复 BlockV5.java**
```bash
# 从 git 历史恢复 BlockV5 代码
git show 5c8f3643:src/main/java/io/xdag/core/Block.java > src/main/java/io/xdag/core/BlockV5.java
```

**1.2 创建 BlockFactory.java (可选)**
```java
public class BlockFactory {
    // 创建旧版本 Block (Phase 3 过渡期使用)
    public static Block createLegacyBlock(Config config, long timestamp, ...) {
        return new Block(config, timestamp, ...);
    }

    // 创建 v5.1 Block
    public static BlockV5 createBlockV5(BlockHeader header, List<Link> links) {
        return new BlockV5(header, links);
    }
}
```

**受影响文件**:
- 新增: `BlockV5.java` (从 git 恢复)
- 新增: `BlockFactory.java` (可选)
- 编译验证: 确保 BlockV5 可以编译

**预期编译错误**: 0 (BlockV5 独立编译，不影响现有代码)

---

### Step 2: 迁移应用层到 BlockV5 (2天)

**优先级**: Commands → Wallet → BlockchainImpl → PoolAwardManager

#### 2.1 Commands.java (高优先级)

**当前使用 (旧 Block API)**:
```java
// createTransaction() - line 381
Block block = kernel.getBlockchain().createNewBlock(
    addressPairs, toAddresses, false, remark, fee, txNonce);
block.signIn(inputKey);
block.signOut(wallet.getDefKey());
```

**迁移到 BlockV5**:
```java
// 方案 A: 修改 Blockchain.createNewBlock() 返回 BlockV5
BlockV5 block = kernel.getBlockchain().createNewBlockV5(
    addressPairs, toAddresses, false, remark, fee, txNonce);
// BlockV5 使用构建器模式，无需 signIn/signOut (签名在构建时完成)

// 方案 B: 保持接口不变，内部创建 BlockV5 并转换
Block block = kernel.getBlockchain().createNewBlock(...);
// block 内部已经是 BlockV5 包装
```

**修改点**:
- `createTransaction()` (line 381-396)
- `xfer()` (line 285-308)
- `xferToNew()` (line 823-835)
- `xferToNode()` (line 852-867)

**复杂度**: 中等 (需要理解签名流程变化)

#### 2.2 Wallet.java

**当前使用**:
```java
public List<BlockWrapper> createTransactionBlock(...) {
    Block block = new Block(config, timestamp, addresses, refs,
                            mining, keys, remark, defKeyIndex, fee, txNonce);
    block.signOut(keys.get(0));
    return Lists.newArrayList(new BlockWrapper(block, 3));
}
```

**迁移到 BlockV5**:
```java
public List<BlockV5> createTransactionBlock(...) {
    // 使用 BlockV5 构建器
    BlockHeader header = new BlockHeader(timestamp, ...);
    List<Link> links = convertAddressesToLinks(addresses, refs);
    BlockV5 block = BlockV5.builder()
        .header(header)
        .links(links)
        .build();
    // 签名在构建时自动完成
    return Lists.newArrayList(block);
}
```

**复杂度**: 高 (核心事务创建逻辑)

#### 2.3 BlockchainImpl.java

**当前接口**:
```java
@Override
public Block createNewBlock(
    Map<Bytes32, ECKeyPair> addressPairs,
    List<Bytes32> toAddresses, ...) {
    // 内部转换 Bytes32 → Address
    // 返回旧 Block
}
```

**迁移方案**:
```java
// 阶段 1: 添加新方法，保持旧方法
public BlockV5 createNewBlockV5(...) {
    // 直接使用 Bytes32, 不转 Address
    // 返回 BlockV5
}

// 阶段 2: 旧方法调用新方法并转换
@Override
public Block createNewBlock(...) {
    BlockV5 v5 = createNewBlockV5(...);
    return BlockAdapter.toLegacy(v5);  // 临时适配
}

// 阶段 3: 删除旧方法，重命名新方法
```

**复杂度**: 高 (影响范围大)

---

### Step 3: 消除 Address 类 (2天)

**3.1 Address → Link 转换**

**关键差异**:
```java
// Address (过渡类 - 复杂)
class Address {
    MutableBytes32 addressHash;  // 32 bytes
    FieldType type;              // input/output/in/out
    XAmount amount;              // 8 bytes (金额)
    boolean isAddress;           // 是地址还是块
}

// Link (v5.1 - 极简)
class Link {
    Bytes32 hash;      // 32 bytes (Transaction 或 Block 的 hash)
    LinkType type;     // TRANSACTION(0) 或 BLOCK(1)
    // 没有金额！金额存储在 Transaction 中
}
```

**核心变化**:
- **金额存储位置变化**: Address 包含 amount → Link 无 amount (amount 在 Transaction 中)
- **类型简化**: 4种类型 (IN/OUT/INPUT/OUTPUT) → 2种类型 (TRANSACTION/BLOCK)

**转换策略**:
```java
// 旧代码
Address addr = new Address(hash, XDAG_FIELD_IN, amount, false);

// 新代码
// 1. 创建 Transaction (包含 amount)
Transaction tx = Transaction.builder()
    .from(fromAddress)
    .to(toAddress)
    .amount(amount)
    .build();

// 2. Block 引用 Transaction (只存 hash)
Link link = new Link(tx.getHash(), LinkType.TRANSACTION);
```

**受影响文件**:
- `BlockchainImpl.java`: `createNewBlock()`, `tryToConnect()`, `applyBlock()`
- `Commands.java`: `createTransaction()`
- `PoolAwardManagerImpl.java`: `doPayments()`
- `Block.java` (旧): `getLinks()`, `getInputs()`, `getOutputs()`

**复杂度**: 非常高 ⚠️ (架构级变化)

**风险点**:
- 金额验证逻辑需要重写 (原来在 Block 中检查 Address.amount)
- 事务历史记录需要更新 (依赖 Address 结构)

---

### Step 4: 消除 BlockWrapper (0.5天)

**当前剩余使用**:
- `Wallet.java`: `createTransactionBlock()` 返回 `List<BlockWrapper>`

**迁移**:
```java
// 旧
public List<BlockWrapper> createTransactionBlock(...) {
    Block block = new Block(...);
    return Lists.newArrayList(new BlockWrapper(block, 3));
}

// 新
public List<BlockV5> createTransactionBlock(...) {
    BlockV5 block = BlockV5.builder()...build();
    return Lists.newArrayList(block);
    // TTL 现在由调用方处理 (SyncBlock)
}
```

**复杂度**: 低

---

### Step 5: 测试恢复 (1天)

**5.1 恢复 BlockTest.java**
```bash
# 从 Phase 1.1 的 commit 恢复测试
git show f6ad4f2b:src/test/java/io/xdag/core/BlockTest.java > src/test/java/io/xdag/core/BlockTest.java
```

**5.2 更新测试以匹配新实现**
- 测试 BlockV5 的构建器模式
- 测试 Link 引用机制
- 测试 Block 引用限制 (MIN=1, MAX=16)

**5.3 运行所有测试**
```bash
mvn test -Dtest=BlockTest
mvn test -Dtest=LinkTest
mvn test -Dtest=BlockHeaderTest
mvn test -Dtest=TransactionTest
```

**目标**: 40/40 测试通过

---

## 📊 风险评估

| 步骤 | 风险等级 | 主要风险 | 缓解措施 |
|------|---------|---------|---------|
| Step 1 | 低 | BlockV5 恢复失败 | 从 git 恢复，验证编译 |
| Step 2.1-2.2 | 中 | 签名流程变化导致交易失败 | 先在测试环境验证 |
| Step 2.3 | 高 | BlockchainImpl 影响核心共识 | 保留旧方法，逐步迁移 |
| Step 3 | 非常高 | Address → Link 架构级变化 | **分阶段实施，充分测试** |
| Step 4 | 低 | BlockWrapper 简单替换 | 直接删除 |
| Step 5 | 中 | 测试覆盖不足 | 添加集成测试 |

---

## 🚨 关键决策点

### 决策 1: Step 3 是否在 Phase 3 实施？

**问题**: Address → Link 是架构级变化，风险极高

**选项**:
- **A. 在 Phase 3 完成** (激进)
  - 优点: 一次性完成 v5.1 迁移
  - 缺点: 风险集中，可能导致长时间编译失败

- **B. 拆分为 Phase 3 和 Phase 4** (保守，推荐)
  - Phase 3: 激活 BlockV5 (保留 Address)
  - Phase 4: Address → Link 迁移
  - 优点: 风险分散，每个 Phase 都能达到可编译状态
  - 缺点: 时间更长

**推荐**: **选项 B** - 将 Address → Link 移至 Phase 4

### 决策 2: 是否创建适配层？

**问题**: BlockV5 和旧 Block 如何共存？

**选项**:
- **A. 直接替换** (Phase 2 教训)
  - 风险高，可能导致大量错误

- **B. 适配器模式** (推荐)
  - 创建 `BlockAdapter.toV5()` 和 `BlockAdapter.toLegacy()`
  - 允许渐进式迁移

**推荐**: **选项 B**

---

## 📋 修订后的 Phase 3 范围

基于风险评估，**缩小 Phase 3 范围**：

### Phase 3 目标 (修订版)
1. ✅ 激活 BlockV5 作为新 Block
2. ✅ 迁移应用层到 BlockV5 API
3. ✅ 消除 BlockWrapper
4. ✅ 恢复测试
5. ⏸️ **保留 Address** (移至 Phase 4)

### Phase 4 目标 (新增)
1. ✅ Address → Link 迁移
2. ✅ 完整的 Link-based 架构
3. ✅ 金额验证逻辑重写

**理由**: 分散风险，确保每个 Phase 都能达到稳定的可编译状态

---

## 🎯 Phase 3 实施顺序 (最终版)

```
Step 1: 恢复 BlockV5.java (0.5天)
├── 1.1 从 git 恢复 BlockV5
├── 1.2 验证 BlockV5 编译通过
└── 1.3 创建 BlockAdapter (可选)

Step 2: 迁移 Commands.java (0.5天)
├── 2.1 修改 createTransaction()
├── 2.2 修改 xfer(), xferToNew(), xferToNode()
└── 2.3 验证编译

Step 3: 迁移 Wallet.java (1天)
├── 3.1 修改 createTransactionBlock()
├── 3.2 移除 BlockWrapper 返回
└── 3.3 验证事务创建

Step 4: 迁移 BlockchainImpl.java (1.5天)
├── 4.1 添加 createNewBlockV5() 方法
├── 4.2 保留 createNewBlock() 作为适配
├── 4.3 逐步迁移内部实现
└── 4.4 验证共识不受影响

Step 5: 清理和重命名 (0.5天)
├── 5.1 删除旧 Block.java
├── 5.2 重命名 BlockV5.java → Block.java
├── 5.3 删除 BlockWrapper.java
└── 5.4 最终编译验证

Step 6: 测试恢复 (1天)
├── 6.1 恢复 BlockTest.java
├── 6.2 运行所有单元测试
├── 6.3 添加集成测试
└── 6.4 验证 40/40 通过
```

**总预计时间**: 5 天

---

## 📈 成功标准

Phase 3 完成的标志：

1. ✅ **编译**: 0 errors, BUILD SUCCESS
2. ✅ **测试**: 40/40 单元测试通过
3. ✅ **架构**: Block = BlockV5 (BlockHeader + List<Link>)
4. ✅ **清理**: 删除 BlockWrapper
5. ⚠️ **过渡**: 保留 Address (Phase 4 再消除)

---

## 🔄 回滚计划

如果 Phase 3 失败，回滚步骤：

```bash
# 1. 放弃所有更改
git reset --hard <phase-2-final-commit>

# 2. 或者逐步回滚
git revert <commit-hash>

# 3. 恢复到 Phase 2 稳定状态
git checkout refactor/core-v5.1
git reset --hard 5be9ed60  # Phase 2 完成的 commit
```

---

## 📚 参考文档

- [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) - v5.1 完整设计
- [PHASE2_COMPLETE_SUMMARY.md](PHASE2_COMPLETE_SUMMARY.md) - Phase 2 经验总结
- [V5.1_IMPLEMENTATION_STATUS.md](V5.1_IMPLEMENTATION_STATUS.md) - 总体进度

---

**创建日期**: 2025-10-30
**状态**: 待审批
**下一步**: 用户确认后开始 Step 1
