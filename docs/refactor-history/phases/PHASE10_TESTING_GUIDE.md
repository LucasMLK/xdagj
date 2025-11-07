# Phase 10 测试指南 - XDAG v5.1 DagStore

## ⚠️ AI 测试注意事项

**重要规则**：
1. ✅ **只测试 Phase 10 的新代码** - 不要回顾老代码
2. ✅ **只看这个文件** - 所有需要的信息都在这里
3. ✅ **不要阅读 BlockchainImpl** - 我们不使用它
4. ✅ **从简单到复杂** - 按照下面的顺序逐步测试

---

## 📋 Phase 10 新代码清单（仅测试这些）

### 1. ValidationResult.java (105 行)
**位置**: `/src/main/java/io/xdag/core/ValidationResult.java`
**职责**: 验证结果封装
**依赖**: 无
**状态**: ✅ 编译通过

### 2. DagAccountManager.java (220 行)
**位置**: `/src/main/java/io/xdag/core/DagAccountManager.java`
**职责**: 纯粹的账户 CRUD 操作（不包含交易处理）
**依赖**: AccountStore, Config
**状态**: ✅ 编译通过

**关键方法**:
```java
// 查询
UInt256 getBalance(Bytes address)
UInt64 getNonce(Bytes address)
boolean hasAccount(Bytes address)

// 更新
UInt256 addBalance(Bytes address, UInt256 amount)
UInt256 subtractBalance(Bytes address, UInt256 amount)
UInt64 incrementNonce(Bytes address)

// 创建
void ensureAccountExists(Bytes address)
void createAccount(Bytes address, UInt256 initialBalance)
```

### 3. DagTransactionProcessor.java (330 行)
**位置**: `/src/main/java/io/xdag/core/DagTransactionProcessor.java`
**职责**: 完整的交易处理（验证 + 账户更新 + 持久化）
**依赖**: DagAccountManager, TransactionStore
**状态**: ✅ 编译通过

**关键方法**:
```java
ProcessingResult processTransaction(Transaction tx)
ProcessingResult processBlockTransactions(Block block, List<Transaction> txs)
```

**处理流程**:
1. 验证签名（TODO: 暂时返回 true）
2. 验证账户状态（余额、nonce）
3. 确保 receiver 账户存在
4. 更新账户状态（调用 DagAccountManager）
5. 保存交易到 TransactionStore

**类型转换**（重要！）:
```java
// Transaction 使用 XAmount/long
// AccountStore 使用 UInt256/UInt64
UInt256 txAmount = UInt256.valueOf(tx.getAmount().toXAmount().toLong());
UInt64 txNonce = UInt64.valueOf(tx.getNonce());
```

### 4. DagBlockProcessor.java (268 行)
**位置**: `/src/main/java/io/xdag/core/DagBlockProcessor.java`
**职责**: Block 处理（验证 + 交易提取 + 持久化）
**依赖**: DagStore, DagTransactionProcessor, DagAccountManager
**状态**: ✅ 编译通过

**关键方法**:
```java
ProcessingResult processBlock(Block block)
Block getBlock(Bytes32 hash, boolean withLinks)
boolean hasBlock(Bytes32 hash)
```

**处理流程**:
1. 验证基本结构
2. 保存 Block 到 DagStore
3. 提取 Transactions（TODO: 暂时返回空列表）
4. 处理 Transactions

### 5. DagKernel.java (修改)
**位置**: `/src/main/java/io/xdag/DagKernel.java`
**修改内容**: 添加了 Dag 处理组件
**状态**: ✅ 编译通过

**新增字段**:
```java
private DagAccountManager dagAccountManager;
private DagTransactionProcessor dagTransactionProcessor;
private DagBlockProcessor dagBlockProcessor;
```

**新增 Getter 方法**:
```java
public DagAccountManager getDagAccountManager()
public DagTransactionProcessor getDagTransactionProcessor()
public DagBlockProcessor getDagBlockProcessor()
```

---

## 🧪 测试策略

### 阶段 1: 单元测试（最简单）
**目标**: 测试单个类的功能，使用 Mock 依赖

```
1. ValidationResult - 5 分钟
2. DagAccountManager - 30 分钟
3. DagTransactionProcessor - 45 分钟
4. DagBlockProcessor - 30 分钟
```

### 阶段 2: 集成测试（中等）
**目标**: 测试 Dag 组件之间的协作

```
1. DagAccountManager + AccountStore - 20 分钟
2. DagTransactionProcessor + DagAccountManager - 30 分钟
3. DagBlockProcessor + DagTransactionProcessor - 30 分钟
```

### 阶段 3: DagKernel 端到端测试（复杂）
**目标**: 测试完整的 DagKernel 启动和运行

```
1. DagKernel 启动测试 - 20 分钟
2. 完整交易处理流程 - 30 分钟
```

**总预估时间**: 4 小时

---

## 📝 测试文件命名规范

**单元测试**:
- `DagAccountManagerTest.java`
- `DagTransactionProcessorTest.java`
- `DagBlockProcessorTest.java`
- `ValidationResultTest.java`

**集成测试**:
- `DagAccountManagerIntegrationTest.java`
- `DagTransactionProcessorIntegrationTest.java`
- `DagKernelIntegrationTest.java`

**测试目录**:
```
/src/test/java/io/xdag/core/
  ├── DagAccountManagerTest.java
  ├── DagTransactionProcessorTest.java
  ├── DagBlockProcessorTest.java
  ├── ValidationResultTest.java
  └── integration/
      ├── DagAccountManagerIntegrationTest.java
      ├── DagTransactionProcessorIntegrationTest.java
      └── DagKernelIntegrationTest.java
```

---

## 🎯 测试优先级

### P0 - 必须测试（核心功能）
1. ✅ DagAccountManager 的账户 CRUD 操作
2. ✅ DagTransactionProcessor 的账户状态验证
3. ✅ DagTransactionProcessor 的账户状态更新
4. ✅ DagKernel 的启动和停止

### P1 - 应该测试（重要功能）
1. DagBlockProcessor 的 Block 验证
2. DagTransactionProcessor 的批量交易处理
3. 类型转换正确性（XAmount ↔ UInt256）

### P2 - 可以测试（边界情况）
1. 余额不足的情况
2. Nonce 不匹配的情况
3. 账户不存在的情况

---

## 🚫 不要测试的内容

1. ❌ **BlockchainImpl** - 我们不使用它
2. ❌ **Kernel (legacy)** - 我们使用 DagKernel
3. ❌ **BlockWrapper** - Phase 7 已删除
4. ❌ **BlockInfo** - Phase 6 已废弃
5. ❌ **老的 Block 类** - 使用新的 v5.1 Block

---

## 🔧 测试工具和依赖

**已有的测试工具**:
```java
// JUnit 5
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

// Mockito
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;
```

**测试数据生成**:
```java
// 创建测试地址
Bytes address = Bytes.wrap(new byte[20]);

// 创建测试金额
UInt256 balance = UInt256.valueOf(1000000000L); // 1 XDAG

// 创建测试 nonce
UInt64 nonce = UInt64.valueOf(0);
```

---

## 📊 测试覆盖率目标

- **DagAccountManager**: 90%+
- **DagTransactionProcessor**: 85%+
- **DagBlockProcessor**: 80%+
- **ValidationResult**: 100%

---

## ⚡ 快速开始（推荐顺序）

1. **从最简单的开始**: ValidationResult
2. **测试核心逻辑**: DagAccountManager
3. **测试交易处理**: DagTransactionProcessor
4. **测试 Block 处理**: DagBlockProcessor
5. **端到端测试**: DagKernel

---

## 📌 重要提醒

1. **不要创建新的业务逻辑** - 只测试现有代码
2. **不要修改被测试的类** - 除非发现 bug
3. **使用 Mock 隔离依赖** - 单元测试不依赖数据库
4. **集成测试使用真实组件** - 验证实际行为
5. **每个测试方法只测一个场景** - 保持简单

---

## 🐛 已知的 TODO（不影响测试）

1. `DagTransactionProcessor.validateSignature()` - 签名验证未实现（暂时返回 true）
2. `DagBlockProcessor.extractTransactions()` - 交易提取未实现（暂时返回空列表）
3. `DagKernel.createDagChain()` - DagChainImpl 集成待完成（返回 null）

**测试策略**:
- 对于 TODO 1: 可以暂时跳过签名验证测试
- 对于 TODO 2: 可以测试空交易列表的情况
- 对于 TODO 3: 验证组件已正确初始化即可

---

**文档版本**: v1.0
**创建时间**: 2025-11-06
**适用阶段**: Phase 10 测试阶段
**预计测试时间**: 4 小时
