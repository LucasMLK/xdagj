# Phase 4 Migration Plan - Address → Link Complete Migration

**Date**: 2025-10-30
**Branch**: refactor/core-v5.1
**Status**: Planning
**Estimated Duration**: 2-3 weeks

---

## 🎯 Phase 4 目标

完成 XDAG v5.1 架构的**最后也是最关键**的迁移：

1. ✅ **消除 Address 类** - 完全移除过渡类
2. ✅ **激活 Link-based 设计** - BlockV5 的 List<Link>
3. ✅ **集成 Transaction** - 金额信息从 Address 迁移到 Transaction
4. ✅ **重写验证逻辑** - BlockchainImpl 适配新架构
5. ✅ **激活 BlockV5** - 替换旧 Block 成为主实现
6. ✅ **消除 BlockWrapper** - 完全移除所有过渡类

---

## 📊 当前状态分析

### Address vs Link 架构差异

**Address (当前 - 过渡类)**:
```java
public class Address {
    protected MutableBytes32 addressHash;  // 32 bytes
    protected XdagField.FieldType type;    // input/output/in/out (4种类型)
    protected XAmount amount;              // 8 bytes (关键：包含金额！)
    protected boolean isAddress;           // 是地址还是块

    // 使用示例
    Address ref = new Address(hash, XDAG_FIELD_IN, amount, false);
    if (ref.getAmount().subtract(MIN_GAS).isNegative()) {
        // 验证逻辑依赖 amount
    }
}
```

**Link (v5.1 目标)**:
```java
public class Link {
    private final Bytes32 hash;      // 32 bytes (Transaction 或 Block 的 hash)
    private final LinkType type;     // TRANSACTION(0) 或 BLOCK(1) (只有2种类型)

    // 没有 amount！
    // 金额信息存储在 Transaction 对象中
}
```

**核心问题**: Link 没有 amount，但现有代码严重依赖 Address.amount

---

## 🔍 影响范围分析

### Address 使用统计

根据代码扫描结果：
- **总引用数**: 90+ 处
- **主要使用者**:
  - BlockchainImpl.java: 28 处 (最关键)
  - XdagApiImpl.java: 8 处
  - Commands.java: 6 处
  - Block.java (旧): 5 处
  - PoolAwardManagerImpl.java: 4 处
  - 其他文件: 39 处

### 关键依赖代码示例

#### 1. BlockchainImpl.tryToConnect() - 验证逻辑

**当前代码** (依赖 Address.amount):
```java
// Line 298-365: 验证 block 引用
List<Address> all = block.getLinks().stream().distinct().toList();
for (Address ref : all) {
    if (!ref.isAddress) {
        // ... 获取 refBlock

        // 关键：验证金额
        if (ref.getType() == XDAG_FIELD_IN && ref.getAmount().subtract(MIN_GAS).isNegative()) {
            result = ImportResult.INVALID_BLOCK;
            result.setErrorInfo("Ref block's balance < minGas");
            return result;
        }
    } else {
        // 关键：验证地址金额
        if (ref.getType() == XDAG_FIELD_INPUT && ref.getAmount().subtract(MIN_GAS).isNegative()) {
            result = ImportResult.INVALID_BLOCK;
            result.setErrorInfo("Ref block's balance < minGas");
            return result;
        }
    }

    // 关键：判断是否是交易块
    if (compareAmountTo(ref.getAmount(), XAmount.ZERO) != 0) {
        log.debug("Try to connect a tx Block:{}", block.getHash().toHexString());
        updateBlockFlag(block, BI_EXTRA, false);
    }
}
```

**v5.1 目标** (使用 Link + Transaction):
```java
// 需要重写为：
List<Link> links = block.getLinks();
for (Link link : links) {
    if (link.isTransaction()) {
        // 从 TransactionStore 获取 Transaction 对象
        Transaction tx = transactionStore.getTransaction(link.getHash());

        // 使用 Transaction.amount 进行验证
        if (tx.getAmount().subtract(MIN_GAS).isNegative()) {
            result = ImportResult.INVALID_BLOCK;
            result.setErrorInfo("Transaction amount < minGas");
            return result;
        }
    } else {
        // Block 引用
        Block refBlock = getBlockByHash(link.getHash(), false);
        // Block 引用不包含金额信息
    }
}
```

#### 2. BlockchainImpl.applyBlock() - 金额处理

**当前代码** (依赖 Address.amount):
```java
// Line 866-983: 应用 block 到主链
for (Address link : links) {
    if (link.getType() == XDAG_FIELD_IN) {
        // 关键：使用 link.getAmount()
        if (compareAmountTo(sumIn.add(link.getAmount()), sumIn) < 0) {
            log.debug("This input ref's amount less than 0");
            return XAmount.ZERO;
        }
        sumIn = sumIn.add(link.getAmount());
    } else if (link.getType() == XDAG_FIELD_INPUT) {
        // 从地址获取余额
        XAmount balance = addressStore.getBalanceByAddress(hash2byte(link.getAddress()).toArray());

        // 关键：使用 link.amount 验证
        if (compareAmountTo(balance, link.amount) < 0) {
            log.debug("This input ref doesn't have enough amount");
            return XAmount.ZERO;
        }
        sumIn = sumIn.add(link.getAmount());
    }
}
```

**v5.1 目标** (使用 Link + Transaction):
```java
// 需要重写为：
for (Link link : links) {
    if (link.isTransaction()) {
        Transaction tx = transactionStore.getTransaction(link.getHash());

        // 使用 Transaction.amount
        if (tx.getFrom().equals(某个地址)) {
            // 这是 input
            sumIn = sumIn.add(tx.getAmount());
        } else {
            // 这是 output
            sumOut = sumOut.add(tx.getAmount());
        }
    } else {
        // Block 引用不涉及金额
    }
}
```

#### 3. TxHistory - 事务历史记录

**当前代码** (依赖 Address):
```java
// Line 558-595: 记录交易历史
public void onNewTxHistory(Bytes32 addressHash, Bytes32 txHash, XdagField.FieldType type,
                           XAmount amount, long time, byte[] remark, boolean isAddress, int id) {
    Address address = new Address(addressHash, type, amount, isAddress);
    TxHistory txHistory = new TxHistory();
    txHistory.setAddress(address);  // 关键：存储 Address
    txHistory.setHash(BasicUtils.hash2Address(txHash));
    // ...
}
```

**v5.1 目标**:
```java
// 需要重写为：
public void onNewTxHistory(Bytes32 txHash, Transaction tx, long time, byte[] remark) {
    TxHistory txHistory = new TxHistory();
    txHistory.setTransactionHash(txHash);
    txHistory.setFrom(tx.getFrom());
    txHistory.setTo(tx.getTo());
    txHistory.setAmount(tx.getAmount());
    // ... 直接使用 Transaction 信息
}
```

---

## 🗺️ 迁移策略

### 策略选择：分层迁移（Bottom-Up）

**理由**:
1. **风险控制**: 从底层开始，确保每层都稳定
2. **依赖顺序**: 上层依赖下层，先修复下层
3. **测试验证**: 每层完成后都可以验证

### 迁移顺序

```
Layer 1: 数据层 (1周)
├── Transaction 集成
├── TransactionStore 实现
└── 测试验证

Layer 2: 核心层 (1周)
├── BlockchainImpl 重写
├── Block 迁移到 BlockV5
└── 测试验证

Layer 3: 应用层 (3-5天)
├── Commands, Wallet 更新
├── PoolAwardManager 更新
└── 最终测试
```

---

## 📝 详细实施步骤

### Layer 1: 数据层 (Week 1)

#### Step 1.1: 创建 TransactionStore (2天)

**目标**: 存储和检索 Transaction 对象

**新增文件**:
```java
// src/main/java/io/xdag/db/TransactionStore.java
public interface TransactionStore {
    void saveTransaction(Transaction tx);
    Transaction getTransaction(Bytes32 hash);
    boolean hasTransaction(Bytes32 hash);
    List<Transaction> getTransactionsByBlock(Bytes32 blockHash);
}
```

**实现**:
```java
// src/main/java/io/xdag/db/rocksdb/TransactionStoreImpl.java
public class TransactionStoreImpl implements TransactionStore {
    private final RocksdbKVSource transactionSource;

    @Override
    public void saveTransaction(Transaction tx) {
        byte[] key = tx.getHash().toArray();
        byte[] value = tx.toBytes();
        transactionSource.put(key, value);
    }

    @Override
    public Transaction getTransaction(Bytes32 hash) {
        byte[] value = transactionSource.get(hash.toArray());
        if (value == null) return null;
        return Transaction.fromBytes(value);
    }
}
```

**测试**:
```java
// src/test/java/io/xdag/db/TransactionStoreTest.java
@Test
public void testSaveAndRetrieve() {
    Transaction tx = Transaction.builder()
        .from(Bytes32.random())
        .to(Bytes32.random())
        .amount(XAmount.of(100, XUnit.XDAG))
        .build();

    transactionStore.saveTransaction(tx);
    Transaction retrieved = transactionStore.getTransaction(tx.getHash());

    assertEquals(tx, retrieved);
}
```

**预计时间**: 2 天

---

#### Step 1.2: Transaction 序列化完善 (1天)

**目标**: 确保 Transaction 可以完整序列化/反序列化

**需要检查**:
```java
// Transaction.java
public byte[] toBytes() {
    // 序列化所有字段
}

public static Transaction fromBytes(byte[] bytes) {
    // 反序列化
}
```

**测试**:
```java
@Test
public void testSerializationWithAllFields() {
    Transaction original = Transaction.builder()
        .from(Bytes32.random())
        .to(Bytes32.random())
        .amount(XAmount.of(100, XUnit.XDAG))
        .nonce(UInt64.valueOf(5))
        .fee(XAmount.of(1, XUnit.MILLI_XDAG))
        .data(Bytes.wrap("test data".getBytes()))
        .build();

    byte[] bytes = original.toBytes();
    Transaction deserialized = Transaction.fromBytes(bytes);

    assertEquals(original, deserialized);
}
```

**预计时间**: 1 天

---

#### Step 1.3: BlockchainImpl 集成 TransactionStore (2天)

**目标**: BlockchainImpl 可以访问和使用 TransactionStore

**修改**:
```java
// BlockchainImpl.java
public class BlockchainImpl implements Blockchain {
    private final TransactionStore transactionStore;  // 新增

    public BlockchainImpl(Kernel kernel) {
        // ...
        this.transactionStore = kernel.getTransactionStore();  // 新增
    }
}
```

**Kernel 修改**:
```java
// Kernel.java
@Getter
private TransactionStore transactionStore;

public void init() {
    // ...
    this.transactionStore = new TransactionStoreImpl(/* ... */);
}
```

**预计时间**: 2 天

---

### Layer 2: 核心层 (Week 2)

#### Step 2.1: BlockchainImpl.tryToConnect() 重写 (3天)

**目标**: 使用 Link + Transaction 替代 Address

**当前问题点**:
1. Line 298: `List<Address> all = block.getLinks()`
2. Line 303-330: 验证 `ref.getAmount()`
3. Line 361: 判断 `ref.getAmount() != 0`

**重写方案**:

```java
// 新版本 (使用 Link)
@Override
public synchronized ImportResult tryToConnect(Block block) {
    // ... 前置验证保持不变

    // 关键变化：使用 Link 而非 Address
    List<Link> links = block.getLinks();

    for (Link link : links) {
        if (link.isTransaction()) {
            // Transaction 引用
            Transaction tx = transactionStore.getTransaction(link.getHash());
            if (tx == null) {
                result = ImportResult.NO_PARENT;
                result.setHash(link.getHash());
                result.setErrorInfo("Transaction not found: " + link.getHash().toHexString());
                return result;
            }

            // 验证 Transaction 金额
            if (tx.getAmount().subtract(MIN_GAS).isNegative()) {
                result = ImportResult.INVALID_BLOCK;
                result.setHash(link.getHash());
                result.setErrorInfo("Transaction amount < minGas");
                return result;
            }

            // 验证 Transaction 签名
            if (!tx.verifySignature()) {
                result = ImportResult.INVALID_BLOCK;
                result.setErrorInfo("Invalid transaction signature");
                return result;
            }

        } else {
            // Block 引用
            Block refBlock = getBlockByHash(link.getHash(), false);
            if (refBlock == null) {
                result = ImportResult.NO_PARENT;
                result.setHash(link.getHash());
                result.setErrorInfo("Block not found: " + link.getHash().toHexString());
                return result;
            }

            // 验证 Block 时间
            if (refBlock.getTimestamp() >= block.getTimestamp()) {
                result = ImportResult.INVALID_BLOCK;
                result.setHash(refBlock.getHash());
                result.setErrorInfo("Ref block's time >= block's time");
                return result;
            }
        }
    }

    // ... 后续验证保持类似逻辑
}
```

**复杂度**: 非常高 ⚠️
**预计时间**: 3 天

---

#### Step 2.2: BlockchainImpl.applyBlock() 重写 (3天) ✅

**状态**: ✅ 已完成 (2025-10-30)
**实际耗时**: 0.5天
**实现位置**: BlockchainImpl.java:1044-1121

**完成内容**:
1. ✅ 创建 applyBlockV2() 方法框架
2. ✅ 实现 Transaction link 执行逻辑（from/to 余额更新、fee 收集）
3. ✅ Block link 处理延期到 Step 2.3（需要 BlockInfo 架构）
4. ✅ 编译通过，v5.1 测试套件全部通过（55/55）

**设计决策**:
- Transaction 执行完全实现（不依赖 BlockInfo）
- Block link 递归处理延期（依赖 BlockInfo.BI_MAIN_REF 标志）
- 复用现有 addressStore.updateBalance() 方法
- 详细的 TODO 标记说明 Step 2.3 需要完成的工作

**目标**: 使用 Link + Transaction 处理金额

**关键变化**:

```java
private XAmount applyBlock(boolean flag, Block block) {
    XAmount gas = XAmount.ZERO;
    XAmount sumIn = XAmount.ZERO;
    XAmount sumOut = XAmount.ZERO;

    // 关键：使用 Link 而非 Address
    List<Link> links = block.getLinks();

    for (Link link : links) {
        if (link.isTransaction()) {
            Transaction tx = transactionStore.getTransaction(link.getHash());
            if (tx == null) {
                log.error("Transaction not found: {}", link.getHash());
                return XAmount.ZERO;
            }

            // 处理 Transaction 的 from 和 to
            // from: 减少余额
            XAmount fromBalance = addressStore.getBalanceByAddress(tx.getFrom().toArray());
            if (fromBalance.subtract(tx.getAmount()).subtract(tx.getFee()).isNegative()) {
                log.debug("Insufficient balance for tx: {}", tx.getHash());
                return XAmount.ZERO;
            }
            addressStore.updateBalance(tx.getFrom().toArray(),
                fromBalance.subtract(tx.getAmount()).subtract(tx.getFee()));
            sumIn = sumIn.add(tx.getAmount());

            // to: 增加余额
            XAmount toBalance = addressStore.getBalanceByAddress(tx.getTo().toArray());
            addressStore.updateBalance(tx.getTo().toArray(),
                toBalance.add(tx.getAmount()));
            sumOut = sumOut.add(tx.getAmount());

            // 收取手续费
            gas = gas.add(tx.getFee());

        } else {
            // Block 引用：递归处理
            Block ref = getBlockByHash(link.getHash(), false);
            XAmount ret = applyBlock(false, ref);
            sumGas = sumGas.add(ret);

            if (flag) {
                updateBlockRef(ref, new Address(block));  // TODO: 需要适配
            }
        }
    }

    // 验证总金额
    if (sumIn.compareTo(sumOut.add(gas)) < 0) {
        log.debug("Invalid transaction amounts: in={}, out={}, gas={}",
            sumIn, sumOut, gas);
        return XAmount.ZERO;
    }

    updateBlockFlag(block, BI_APPLIED, true);
    return gas;
}
```

**复杂度**: 非常高 ⚠️
**预计时间**: 3 天

---

#### Step 2.3: Block (旧) → BlockV5 迁移 (1天) ⏳

**状态**: ⏳ 进行中 - Part 1 完成 (2025-10-30)
**实际耗时**: Part 1: 0.5天
**实现位置**: BlockV5.java:87-304

**Part 1 完成内容** (2025-10-30):
1. ✅ 为 BlockV5 添加 BlockInfo 字段（不参与序列化）
2. ✅ 实现 getInfo() 和 withInfo() 方法
3. ✅ 编译通过，v5.1 测试套件全部通过（55/55）
4. ✅ 创建 PHASE4_STEP2.3_PART1_COMPLETION.md 文档

**Part 2 待完成**:
1. ⏸️ 完成 applyBlockV2() 延期功能（Block link 递归处理）
2. ⏸️ 在 tryToConnectV2() 中创建和管理 BlockInfo
3. ⏸️ 更新 BlockInfo 标志（BI_MAIN_REF, BI_APPLIED 等）

**目标**: 激活 BlockV5，删除旧 Block

**步骤**:
1. 重命名 Block.java → Block.java.legacy (备份)
2. 重命名 BlockV5.java → Block.java
3. 更新所有导入
4. 验证编译

**预计时间**: 1 天

---

### Layer 3: 应用层 (Week 3, 3-5天)

#### Step 3.1: Commands.java 更新 (1天)

**修改点**:
```java
// createTransaction() - 使用 Transaction 替代 Address
public Block createTransaction(...) {
    // 创建 Transaction 对象
    Transaction tx = Transaction.builder()
        .from(fromAddress)
        .to(toAddress)
        .amount(amount)
        .nonce(nonce)
        .fee(fee)
        .build();

    // 保存到 TransactionStore
    transactionStore.saveTransaction(tx);

    // 创建 Block，引用 Transaction
    List<Link> links = List.of(Link.toTransaction(tx.getHash()));
    Block block = BlockV5.builder()
        .header(...)
        .links(links)
        .build();

    return block;
}
```

**预计时间**: 1 天

---

#### Step 3.2: Wallet.java 更新 (1天)

**修改点**: 类似 Commands.java

**预计时间**: 1 天

---

#### Step 3.3: PoolAwardManagerImpl.java 更新 (1天)

**修改点**: 奖励分发使用 Transaction

**预计时间**: 1 天

---

#### Step 3.4: 删除过渡类 (0.5天)

**删除**:
- Address.java
- BlockWrapper.java
- Block.java.legacy

**预计时间**: 0.5 天

---

#### Step 3.5: 最终测试和验证 (1-2天)

**测试项目**:
1. 单元测试: 40/40 通过
2. 集成测试: 创建交易 → 打包 → 验证 → 上链
3. 回归测试: 确保现有功能不受影响

**预计时间**: 1-2 天

---

## 📊 风险评估

| 风险项 | 风险等级 | 影响 | 缓解措施 |
|--------|---------|------|----------|
| TransactionStore 性能 | 中 | 可能影响同步速度 | 使用 RocksDB，批量写入优化 |
| BlockchainImpl 重写引入 bug | 高 | 可能导致共识失败 | 充分测试，保留旧代码备份 |
| Address → Link 遗漏转换 | 中 | 编译错误或运行时错误 | 代码审查，全面搜索 Address 引用 |
| 金额验证逻辑错误 | 高 | 可能导致双花 | 详细的单元测试，测试网验证 |
| 测试覆盖不足 | 中 | 未发现的 bug | 增加集成测试，边界测试 |

---

## 🔄 回滚计划

如果 Phase 4 失败，回滚步骤：

### 方案 A: Git 回滚 (快速)
```bash
# 回滚到 Phase 3 完成状态
git reset --hard 09a90954  # Phase 3 final commit
```

### 方案 B: 分层回滚 (渐进)
```bash
# Layer 1 失败：删除 TransactionStore
rm -rf src/main/java/io/xdag/db/TransactionStore*.java
git checkout HEAD -- src/main/java/io/xdag/core/BlockchainImpl.java

# Layer 2 失败：恢复旧 Block
mv Block.java BlockV5.java
mv Block.java.legacy Block.java

# Layer 3 失败：恢复应用层
git checkout HEAD -- src/main/java/io/xdag/cli/Commands.java
git checkout HEAD -- src/main/java/io/xdag/Wallet.java
```

---

## 📈 成功标准

Phase 4 完成的标志：

1. ✅ **编译**: 0 errors, BUILD SUCCESS
2. ✅ **测试**: 所有单元测试 + 集成测试通过
3. ✅ **架构**: 完全使用 Link + Transaction (无 Address)
4. ✅ **性能**: TransactionStore 性能满足要求
5. ✅ **清理**: 删除所有过渡类 (Address, BlockWrapper, Block.legacy)

---

## 📅 时间表

```
Week 1: 数据层
├── Day 1-2: TransactionStore 创建和测试
├── Day 3: Transaction 序列化完善
└── Day 4-5: BlockchainImpl 集成 TransactionStore

Week 2: 核心层
├── Day 1-3: tryToConnect() 重写
├── Day 4-6: applyBlock() 重写
└── Day 7: Block → BlockV5 迁移

Week 3: 应用层
├── Day 1: Commands.java 更新
├── Day 2: Wallet.java 更新
├── Day 3: PoolAwardManagerImpl.java 更新
├── Day 4: 删除过渡类
└── Day 5-6: 最终测试和验证

Total: 15-18 工作日 (3 周)
```

---

## 📝 检查清单

### 开始 Phase 4 前
- [ ] Phase 3 完全完成且稳定
- [ ] 所有测试通过 (40/40)
- [ ] 团队评审 Phase 4 计划
- [ ] 创建专门的 phase4 分支

### Layer 1 完成
- [ ] TransactionStore 实现并测试
- [ ] Transaction 序列化完善
- [ ] BlockchainImpl 集成 TransactionStore
- [ ] 编译通过，测试通过

### Layer 2 完成
- [ ] tryToConnect() 重写完成
- [ ] applyBlock() 重写完成
- [ ] BlockV5 激活
- [ ] 编译通过，核心测试通过

### Layer 3 完成
- [ ] 应用层全部更新
- [ ] 过渡类删除
- [ ] 所有测试通过
- [ ] 性能测试通过

### Phase 4 最终验证
- [ ] 代码审查完成
- [ ] 集成测试通过
- [ ] 文档更新完成
- [ ] 准备合并到 master

---

## 🚨 关键注意事项

### 1. 不要一次性修改所有文件

**错误做法**:
```bash
# ❌ 一次性删除 Address 类
rm src/main/java/io/xdag/core/Address.java
# 导致 90+ 编译错误！
```

**正确做法**:
```bash
# ✅ 逐层迁移
# Week 1: 只添加 TransactionStore
# Week 2: 只重写 BlockchainImpl
# Week 3: 只更新应用层
```

### 2. 保持向后兼容（在迁移过程中）

**策略**: 在完全迁移完成前，保留 Address 类
```java
// 过渡期：同时支持两种方式
public class BlockchainImpl {
    // 新方法 (使用 Link)
    private ImportResult tryToConnectV2(Block block) { ... }

    // 旧方法 (使用 Address，保留)
    private ImportResult tryToConnectV1(Block block) { ... }

    // 根据 block 类型选择
    public ImportResult tryToConnect(Block block) {
        if (block instanceof BlockV5) {
            return tryToConnectV2(block);
        } else {
            return tryToConnectV1(block);  // 兼容旧 block
        }
    }
}
```

### 3. 充分测试

**必须测试的场景**:
- ✅ 正常交易创建和验证
- ✅ 双花检测
- ✅ 金额不足检测
- ✅ Nonce 重放检测
- ✅ 手续费计算
- ✅ Block 引用验证
- ✅ 主链切换
- ✅ 回滚和重组

---

## 📚 参考资料

- [CORE_DATA_STRUCTURES.md](CORE_DATA_STRUCTURES.md) - v5.1 完整设计
- [PHASE2_COMPLETE_SUMMARY.md](PHASE2_COMPLETE_SUMMARY.md) - Phase 2 经验教训
- [PHASE3_MIGRATION_PLAN.md](PHASE3_MIGRATION_PLAN.md) - Phase 3 保守策略
- [Transaction.java](src/main/java/io/xdag/core/Transaction.java) - Transaction 实现
- [Link.java](src/main/java/io/xdag/core/Link.java) - Link 实现
- [BlockV5.java](src/main/java/io/xdag/core/BlockV5.java) - v5.1 Block 实现

---

**创建日期**: 2025-10-30
**状态**: 详细规划完成，等待批准
**预计开始**: Phase 3 完成后
**预计完成**: 3 周后

---

## 🎯 Phase 4 之后

Phase 4 完成后，v5.1 架构将**完全激活**：

```
✅ 极简设计: Transaction + Block (两种类型)
✅ Link-based: Block 只存储 hash 引用
✅ 高性能: 支持 1,485,000 txs/block
✅ EVM 兼容: Transaction 结构与 Ethereum 类似
✅ 无过渡类: 完全移除 Address, BlockWrapper
```

**下一阶段**:
- Phase 5: 性能优化
- Phase 6: 完整集成测试
- Phase 7: 主网部署准备
