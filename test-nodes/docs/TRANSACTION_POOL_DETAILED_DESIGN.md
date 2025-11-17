# XDAG TransactionPool 详细设计文档

基于Caffeine的TransactionPool实现方案，解决15个已识别问题

---

## 一、设计目标

### 1.1 核心目标

1. **正确性**: 确保交易只在主块中执行一次
2. **安全性**: 支持主块降级时的完整回滚
3. **高性能**: 使用Caffeine缓存，支持10000+ tx/s吞吐量
4. **可靠性**: 提供原子性操作保证，防止状态不一致

### 1.2 非目标

1. ❌ 不实现跨节点的交易同步（由P2P层负责）
2. ❌ 不实现智能合约执行（预留EVM集成接口）
3. ❌ 不实现交易压缩/批处理（未来优化项）

---

## 二、15个潜在问题的解决方案

### 问题 #1: 并发安全问题

**问题描述**:
```java
// Thread 1: addTransaction()
txPool.add(tx1);

// Thread 2: selectTransactions() - 同时读取
List<Transaction> selected = txPool.select(100);

// Thread 3: removeTransactionsByBlock() - 同时删除
txPool.removeByBlock(block);

→ 可能导致ConcurrentModificationException或数据不一致
```

**解决方案**: **多层级锁策略**

```java
public class TransactionPoolImpl implements TransactionPool {

    // 使用ReadWriteLock保护数据结构
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock(true); // fair lock

    // Caffeine Cache（线程安全）
    private final Cache<Bytes32, PendingTransaction> txCache;

    // 账户级别的细粒度锁（减少锁竞争）
    private final ConcurrentHashMap<Bytes, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    @Override
    public boolean addTransaction(Transaction tx) {
        // 1. 获取账户级锁（只锁定发送方账户）
        ReentrantLock accountLock = accountLocks.computeIfAbsent(
            tx.getFrom(),
            k -> new ReentrantLock(true)
        );

        accountLock.lock();
        try {
            // 2. 获取写锁
            poolLock.writeLock().lock();
            try {
                // 验证nonce连续性（在锁保护下）
                if (!validateNonceSequence(tx)) {
                    return false;
                }

                // 添加到cache
                txCache.put(tx.getHash(), new PendingTransaction(tx));

                log.info("Added transaction {} to pool (from={}, nonce={})",
                    tx.getHash().toHexString().substring(0, 16),
                    tx.getFrom().toHexString().substring(0, 8),
                    tx.getNonce());

                return true;

            } finally {
                poolLock.writeLock().unlock();
            }
        } finally {
            accountLock.unlock();
        }
    }

    @Override
    public List<Transaction> selectTransactions(int maxCount) {
        // 只需要读锁
        poolLock.readLock().lock();
        try {
            // Caffeine的asMap()返回线程安全的snapshot
            Map<Bytes32, PendingTransaction> snapshot = txCache.asMap();

            return snapshot.values().stream()
                .sorted(new TransactionComparator())
                .limit(maxCount)
                .map(PendingTransaction::getTransaction)
                .collect(Collectors.toList());

        } finally {
            poolLock.readLock().unlock();
        }
    }
}
```

**锁策略总结**:
- **读操作** (selectTransactions): ReadLock - 允许多线程并发读
- **写操作** (addTransaction, remove): WriteLock - 独占访问
- **账户级锁**: 只锁定相关账户，减少全局锁竞争

---

### 问题 #2: 交易被多个Block引用

**问题描述**:
```
Scenario 1 (同一Epoch):
- Block A (hash=0x0001) 引用 Transaction T1
- Block B (hash=0x0002) 也引用 Transaction T1
- Epoch竞争: Block A wins (smaller hash)
→ Block A成为主块，执行T1
→ Block B成为孤块，T1应该被跳过

Scenario 2 (不同Epoch):
- Epoch 1000: Block A 引用 T1，成为主块，执行T1
- Epoch 1001: Block B 引用 T1，成为主块
→ T1已经执行过，不应该再执行
```

**解决方案**: **全局执行状态追踪 + Block级验证**

#### 2.1 TransactionStore增强

```java
public interface TransactionStore extends XdagLifecycle {

    // 现有方法...
    Transaction getTransaction(Bytes32 hash);
    void saveTransaction(Transaction tx);

    // ========== 新增: 执行状态管理 ==========

    /**
     * 标记交易为已执行
     * @param txHash 交易hash
     * @param blockHash 执行该交易的Block hash
     * @param blockHeight Block的高度
     */
    void markTransactionExecuted(Bytes32 txHash, Bytes32 blockHash, long blockHeight);

    /**
     * 检查交易是否已执行
     * @param txHash 交易hash
     * @return true if executed
     */
    boolean isTransactionExecuted(Bytes32 txHash);

    /**
     * 获取执行该交易的Block信息
     * @param txHash 交易hash
     * @return ExecutionInfo (blockHash, blockHeight, timestamp) or null
     */
    TransactionExecutionInfo getExecutionInfo(Bytes32 txHash);

    /**
     * 撤销交易执行标记（用于链重组）
     * @param txHash 交易hash
     */
    void unmarkTransactionExecuted(Bytes32 txHash);
}

/**
 * 交易执行信息
 */
@Value
@Builder
public class TransactionExecutionInfo {
    Bytes32 executingBlockHash;  // 执行该交易的Block hash
    long executingBlockHeight;   // Block高度
    long executionTimestamp;     // 执行时间戳
    boolean isReversed;          // 是否已回滚
}
```

#### 2.2 DagBlockProcessor增强逻辑

```java
// DagBlockProcessor.java
public ProcessingResult processBlock(Block block) {
    List<Transaction> transactions = extractTransactionsFromBlock(block);

    log.info("Processing {} transactions in block {} (height={})",
        transactions.size(),
        block.getHash().toHexString().substring(0, 16),
        block.getInfo().getHeight());

    int executedCount = 0;
    int skippedCount = 0;

    for (Transaction tx : transactions) {
        // ========== 关键检查: 是否已被执行 ==========
        if (transactionStore.isTransactionExecuted(tx.getHash())) {
            TransactionExecutionInfo execInfo =
                transactionStore.getExecutionInfo(tx.getHash());

            log.warn("Transaction {} already executed by block {} at height {} - SKIPPING",
                tx.getHash().toHexString().substring(0, 16),
                execInfo.getExecutingBlockHash().toHexString().substring(0, 16),
                execInfo.getExecutingBlockHeight());

            skippedCount++;
            continue;  // ✅ 跳过已执行的交易
        }

        // 执行交易
        ProcessingResult result = txProcessor.processTransaction(tx);

        if (!result.isSuccess()) {
            log.error("Transaction {} execution failed: {}",
                tx.getHash().toHexString(), result.getError());
            // 继续处理其他交易（见问题#11的解决方案）
            continue;
        }

        // ========== 标记为已执行 ==========
        transactionStore.markTransactionExecuted(
            tx.getHash(),
            block.getHash(),
            block.getInfo().getHeight()
        );

        executedCount++;

        log.debug("Transaction {} executed successfully (block={}, height={})",
            tx.getHash().toHexString().substring(0, 16),
            block.getHash().toHexString().substring(0, 16),
            block.getInfo().getHeight());
    }

    log.info("Block {} processing complete: {} executed, {} skipped, {} total",
        block.getHash().toHexString().substring(0, 16),
        executedCount,
        skippedCount,
        transactions.size());

    return ProcessingResult.success();
}
```

#### 2.3 实现细节

**RocksDB存储结构**:
```
Key: tx_execution:{tx_hash}
Value: {
    "executingBlockHash": "0x1234...",
    "executingBlockHeight": 12345,
    "executionTimestamp": 1700000000,
    "isReversed": false
}
```

**时序图**:
```
Scenario: Block A和Block B都引用Transaction T1

Time 0: Block A到达，尝试执行T1
  ├─ isTransactionExecuted(T1) → false
  ├─ processTransaction(T1) → success
  └─ markTransactionExecuted(T1, Block A, height=100)

Time 1: Block B到达，尝试执行T1
  ├─ isTransactionExecuted(T1) → true ✓
  ├─ getExecutionInfo(T1) → {blockHash: Block A, height: 100}
  └─ SKIP execution (log warning)

Result: T1只执行一次 ✓
```

**边界情况**:
1. **并发执行**: 两个Block在不同线程同时尝试执行同一交易
   - 解决: DagChainImpl.tryToConnect()是synchronized
   - Block处理串行化，避免并发问题

2. **链重组**: Block A被demote后，Block B成为主块
   - 解决: demoteBlockToOrphan()会调用unmarkTransactionExecuted()
   - Block B重新尝试时，T1不再是"已执行"状态

---

### 问题 #3: Nonce连续性的边界情况

**问题描述**:
```
Scenario: Pool中有账户A的多笔交易
- Pool: [nonce=5, nonce=6, nonce=7]
- 账户A当前nonce: 4

情况1: nonce=5被Block引用并执行
→ Pool中应该还剩 [nonce=6, nonce=7]
→ nonce=6现在可以被选择

情况2: nonce=5执行失败
→ Pool中还有 [nonce=5, nonce=6, nonce=7]
→ nonce=6, nonce=7 被阻塞（因为nonce=5未成功）
```

**解决方案**: **基于账户的Nonce链管理**

#### 3.1 数据结构

```java
public class TransactionPoolImpl implements TransactionPool {

    // Caffeine Cache - 主存储
    private final Cache<Bytes32, PendingTransaction> txCache;

    // 账户维度索引: address -> TreeMap(nonce -> tx_hash)
    private final ConcurrentHashMap<Bytes, TreeMap<Long, Bytes32>> accountNonceMap;

    /**
     * 添加交易时，同步更新nonce索引
     */
    @Override
    public boolean addTransaction(Transaction tx) {
        poolLock.writeLock().lock();
        try {
            // 1. 验证nonce连续性
            if (!canAcceptNonce(tx.getFrom(), tx.getNonce())) {
                log.debug("Transaction {} rejected: nonce gap (from={}, nonce={}, expected={})",
                    tx.getHash().toHexString().substring(0, 16),
                    tx.getFrom().toHexString().substring(0, 8),
                    tx.getNonce(),
                    getExpectedNonce(tx.getFrom()));
                return false;
            }

            // 2. 添加到主cache
            PendingTransaction pendingTx = new PendingTransaction(tx);
            txCache.put(tx.getHash(), pendingTx);

            // 3. 更新nonce索引
            accountNonceMap.computeIfAbsent(tx.getFrom(), k -> new TreeMap<>())
                           .put(tx.getNonce(), tx.getHash());

            log.info("Added transaction {} (from={}, nonce={})",
                tx.getHash().toHexString().substring(0, 16),
                tx.getFrom().toHexString().substring(0, 8),
                tx.getNonce());

            return true;

        } finally {
            poolLock.writeLock().unlock();
        }
    }

    /**
     * 检查nonce是否可接受
     */
    private boolean canAcceptNonce(Bytes from, long nonce) {
        // 获取账户当前nonce（已执行的最大nonce）
        UInt64 accountNonce = accountManager.getNonce(from);
        long currentNonce = accountNonce.toLong();

        // 获取Pool中该账户的最大nonce
        TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(from);

        if (accountTxs == null || accountTxs.isEmpty()) {
            // Pool中没有该账户的交易
            // 期望nonce = currentNonce + 1
            return nonce == currentNonce + 1;
        }

        // Pool中有该账户的交易
        // 期望nonce = max(pool_nonce) + 1
        long maxPoolNonce = accountTxs.lastKey();
        return nonce == maxPoolNonce + 1;
    }

    /**
     * 获取账户期望的下一个nonce
     */
    private long getExpectedNonce(Bytes from) {
        UInt64 accountNonce = accountManager.getNonce(from);
        TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(from);

        if (accountTxs == null || accountTxs.isEmpty()) {
            return accountNonce.toLong() + 1;
        }

        return accountTxs.lastKey() + 1;
    }

    /**
     * 移除交易时，清理nonce索引
     */
    @Override
    public void removeTransaction(Bytes32 txHash) {
        poolLock.writeLock().lock();
        try {
            PendingTransaction pendingTx = txCache.getIfPresent(txHash);
            if (pendingTx == null) {
                return;
            }

            Transaction tx = pendingTx.getTransaction();

            // 1. 从主cache移除
            txCache.invalidate(txHash);

            // 2. 从nonce索引移除
            TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(tx.getFrom());
            if (accountTxs != null) {
                accountTxs.remove(tx.getNonce());

                // 如果账户没有交易了，移除整个entry
                if (accountTxs.isEmpty()) {
                    accountNonceMap.remove(tx.getFrom());
                }
            }

            log.debug("Removed transaction {} (from={}, nonce={})",
                tx.getHash().toHexString().substring(0, 16),
                tx.getFrom().toHexString().substring(0, 8),
                tx.getNonce());

        } finally {
            poolLock.writeLock().unlock();
        }
    }
}
```

#### 3.2 Nonce Gap处理策略

**策略1: 严格连续性（推荐）**
```java
/**
 * 只接受nonce连续的交易
 *
 * 优点: 简单、安全、防止nonce攻击
 * 缺点: 如果nonce=5丢失，nonce=6,7无法进入Pool
 */
private boolean canAcceptNonce(Bytes from, long nonce) {
    long expectedNonce = getExpectedNonce(from);
    return nonce == expectedNonce;  // 必须精确匹配
}
```

**策略2: 有限Gap容忍（可选，未来优化）**
```java
/**
 * 允许最多MAX_NONCE_GAP的nonce跳跃
 *
 * 优点: 更灵活，允许短暂的网络延迟
 * 缺点: 复杂度增加，可能被攻击者利用
 */
private static final int MAX_NONCE_GAP = 3;

private boolean canAcceptNonce(Bytes from, long nonce) {
    long expectedNonce = getExpectedNonce(from);

    // 允许最多3个nonce的gap
    return nonce >= expectedNonce &&
           nonce <= expectedNonce + MAX_NONCE_GAP;
}
```

**当前方案**: 使用策略1（严格连续性）

---

### 问题 #4: 回滚时接收方余额不足

**问题描述**:
```
Scenario: 回滚交易时，接收方已经花费了收到的金额

初始状态:
- Sender余额: 100 XDAG
- Receiver余额: 0 XDAG

T1: Sender发送10 XDAG给Receiver (Block A，主块)
执行后:
- Sender余额: 90 XDAG
- Receiver余额: 10 XDAG

T2: Receiver发送8 XDAG给第三方 (Block B，主块)
执行后:
- Sender余额: 90 XDAG
- Receiver余额: 2 XDAG

T3: Block A被demote（epoch竞争输了）
需要回滚T1:
- Sender余额: 90 + 10 = 100 XDAG ✓
- Receiver余额: 2 - 10 = -8 XDAG ✗ 负余额！
```

**解决方案**: **允许临时负余额 + 账户冻结机制**

#### 4.1 账户状态扩展

```java
/**
 * 账户状态
 */
public class AccountState {
    private Bytes address;
    private UInt256 balance;          // 余额（可以为负）
    private UInt64 nonce;
    private boolean isFrozen;         // 是否冻结
    private String frozenReason;      // 冻结原因
    private long frozenTimestamp;     // 冻结时间
}

/**
 * DagAccountManager接口扩展
 */
public interface DagAccountManager {

    // 现有方法...
    UInt256 getBalance(Bytes address);
    void setBalance(Bytes address, UInt256 balance);

    // ========== 新增: 负余额支持 ==========

    /**
     * 设置余额（允许负数）
     * @param address 账户地址
     * @param balance 余额（可能为负）
     * @param allowNegative 是否允许负余额
     * @throws InsufficientBalanceException 如果不允许负余额且balance < 0
     */
    void setBalance(Bytes address, UInt256 balance, boolean allowNegative);

    /**
     * 检查账户余额是否为负
     */
    boolean hasNegativeBalance(Bytes address);

    /**
     * 获取负余额金额（如果余额为正，返回0）
     */
    UInt256 getNegativeBalanceAmount(Bytes address);

    // ========== 新增: 账户冻结机制 ==========

    /**
     * 冻结账户（禁止发起新交易）
     */
    void freezeAccount(Bytes address, String reason);

    /**
     * 解冻账户
     */
    void unfreezeAccount(Bytes address);

    /**
     * 检查账户是否冻结
     */
    boolean isAccountFrozen(Bytes address);
}
```

#### 4.2 回滚逻辑增强

```java
// DagChainImpl.java
private void rollbackBlockTransactions(Block block) {
    if (dagKernel == null) return;

    DagAccountManager accountManager = dagKernel.getDagAccountManager();
    TransactionStore transactionStore = dagKernel.getTransactionStore();

    List<Transaction> transactions = extractTransactionsFromBlock(block);

    if (transactions.isEmpty()) {
        return;
    }

    log.warn("Rolling back {} transactions from demoted block {} (height={})",
        transactions.size(),
        block.getHash().toHexString().substring(0, 16),
        block.getInfo().getHeight());

    // ========== 按执行顺序的逆序回滚 ==========
    for (int i = transactions.size() - 1; i >= 0; i--) {
        Transaction tx = transactions.get(i);

        // 检查是否已执行
        if (!transactionStore.isTransactionExecuted(tx.getHash())) {
            log.debug("Transaction {} was not executed, skipping rollback",
                tx.getHash().toHexString().substring(0, 16));
            continue;
        }

        try {
            // 1. 恢复发送方余额
            UInt256 senderBalance = accountManager.getBalance(tx.getFrom());
            UInt256 amountWithFee = tx.getAmount().toUInt256().add(tx.getFee().toUInt256());
            accountManager.setBalance(
                tx.getFrom(),
                senderBalance.add(amountWithFee),
                false  // 发送方不应该出现负余额
            );

            // 2. 扣除接收方余额（允许负余额！）
            UInt256 receiverBalance = accountManager.getBalance(tx.getTo());
            UInt256 newReceiverBalance = receiverBalance.subtract(tx.getAmount().toUInt256());

            // ========== 关键: 允许负余额 ==========
            accountManager.setBalance(
                tx.getTo(),
                newReceiverBalance,
                true  // ✓ 允许负余额
            );

            // ========== 如果余额为负，冻结账户 ==========
            if (accountManager.hasNegativeBalance(tx.getTo())) {
                UInt256 negativeAmount = accountManager.getNegativeBalanceAmount(tx.getTo());

                accountManager.freezeAccount(
                    tx.getTo(),
                    String.format("Negative balance due to rollback: -%s XDAG (tx: %s)",
                        XAmount.ofNanoXDAG(negativeAmount.toLong()).toDecimal(9, XUnit.XDAG),
                        tx.getHash().toHexString().substring(0, 16))
                );

                log.error("Account {} frozen due to negative balance: -{} XDAG",
                    tx.getTo().toHexString().substring(0, 8),
                    XAmount.ofNanoXDAG(negativeAmount.toLong()).toDecimal(9, XUnit.XDAG));
            }

            // 3. 恢复发送方nonce
            accountManager.decrementNonce(tx.getFrom());

            // 4. 取消执行标记
            transactionStore.unmarkTransactionExecuted(tx.getHash());

            log.info("Rolled back transaction {} (from={}, to={}, amount={})",
                tx.getHash().toHexString().substring(0, 16),
                tx.getFrom().toHexString().substring(0, 8),
                tx.getTo().toHexString().substring(0, 8),
                tx.getAmount().toDecimal(9, XUnit.XDAG));

        } catch (Exception e) {
            log.error("Failed to rollback transaction {}: {}",
                tx.getHash().toHexString(), e.getMessage(), e);
            // 继续回滚其他交易（但记录严重错误）
        }
    }

    log.warn("Transaction rollback completed for block {}",
        block.getHash().toHexString().substring(0, 16));
}
```

#### 4.3 冻结账户的交易验证

```java
// DagTransactionProcessor.java
private ValidationResult validateAccountState(Transaction tx) {
    // ========== 新增: 检查账户是否冻结 ==========
    if (accountManager.isAccountFrozen(tx.getFrom())) {
        return ValidationResult.error(String.format(
            "Sender account is frozen: %s (reason: %s)",
            tx.getFrom().toHexString().substring(0, 16),
            accountManager.getFrozenReason(tx.getFrom())
        ));
    }

    // 检查余额（现有逻辑）
    UInt256 balance = accountManager.getBalance(tx.getFrom());
    XAmount required = tx.getAmount().add(tx.getFee());

    if (balance.compareTo(required.toUInt256()) < 0) {
        return ValidationResult.error(String.format(
            "Insufficient balance: have %s, need %s",
            XAmount.ofNanoXDAG(balance.toLong()).toDecimal(9, XUnit.XDAG),
            required.toDecimal(9, XUnit.XDAG)
        ));
    }

    // ... 其他验证 ...

    return ValidationResult.success();
}
```

#### 4.4 负余额恢复机制

```java
/**
 * 账户负余额自动恢复监控
 *
 * 当其他交易向该账户转账时，自动检查是否可以解冻
 */
public class AccountRecoveryMonitor {

    /**
     * 在交易执行后调用
     */
    public void checkAccountRecovery(Transaction tx) {
        Bytes receiverAddress = tx.getTo();

        // 检查接收方是否被冻结
        if (!accountManager.isAccountFrozen(receiverAddress)) {
            return;
        }

        // 检查余额是否已恢复为正
        if (!accountManager.hasNegativeBalance(receiverAddress)) {
            // 余额已恢复，解冻账户
            accountManager.unfreezeAccount(receiverAddress);

            log.info("Account {} automatically unfrozen (balance recovered to positive)",
                receiverAddress.toHexString().substring(0, 16));
        }
    }
}
```

**设计要点总结**:
1. **允许负余额**: 避免回滚失败导致状态不一致
2. **冻结机制**: 防止负余额账户继续发起交易
3. **自动恢复**: 当账户收到新转账，余额变正后自动解冻
4. **透明性**: 用户可以查询账户冻结状态和原因

---

### 问题 #5: 交易验证时机

**问题描述**:
```
Scenario: 交易验证应该在何时进行？

时机1: 交易创建时（Commands.transfer()）
→ 只能验证基本格式（签名、金额）
→ 无法验证账户余额（Pool中的交易可能改变余额）

时机2: 加入Pool时（TransactionPool.addTransaction()）
→ 可以验证当前账户状态
→ 但后续Pool中的交易可能使状态失效

时机3: Block引用时（collectCandidateLinks()）
→ 最接近执行的时机
→ 但如果验证失败，Block已经创建

时机4: Block执行时（DagBlockProcessor.processBlock()）
→ 最准确的验证时机
→ 但验证失败会导致Block有效但交易失败
```

**解决方案**: **多阶段验证策略**

#### 5.1 验证级别定义

```java
/**
 * 交易验证级别
 */
public enum ValidationLevel {
    /**
     * 基础验证（格式、签名）
     * - 签名格式正确
     * - 字段完整性
     * - 数据大小限制
     */
    BASIC,

    /**
     * Pool入池验证（基础 + 当前状态）
     * - BASIC验证
     * - 当前余额充足
     * - Nonce连续性
     * - 手续费合理
     */
    POOL_ENTRY,

    /**
     * 执行前验证（Pool入池 + 最终状态）
     * - POOL_ENTRY验证
     * - 考虑Block中前序交易的状态变化
     * - 账户未冻结
     */
    PRE_EXECUTION,

    /**
     * 完整验证（执行前 + EVM调用）
     * - PRE_EXECUTION验证
     * - EVM gas limit
     * - 智能合约调用验证
     */
    FULL
}
```

#### 5.2 分阶段验证实现

```java
/**
 * 交易验证器
 */
public class TransactionValidator {

    private final DagAccountManager accountManager;
    private final TransactionStore transactionStore;
    private final Config config;

    /**
     * 执行指定级别的验证
     */
    public ValidationResult validate(Transaction tx, ValidationLevel level) {
        switch (level) {
            case BASIC:
                return validateBasic(tx);

            case POOL_ENTRY:
                ValidationResult basicResult = validateBasic(tx);
                if (!basicResult.isValid()) return basicResult;
                return validatePoolEntry(tx);

            case PRE_EXECUTION:
                ValidationResult poolResult = validate(tx, ValidationLevel.POOL_ENTRY);
                if (!poolResult.isValid()) return poolResult;
                return validatePreExecution(tx);

            case FULL:
                ValidationResult preResult = validate(tx, ValidationLevel.PRE_EXECUTION);
                if (!preResult.isValid()) return preResult;
                return validateFull(tx);

            default:
                throw new IllegalArgumentException("Unknown validation level: " + level);
        }
    }

    /**
     * 基础验证（Level 1）
     */
    private ValidationResult validateBasic(Transaction tx) {
        // 1. 检查签名
        if (!tx.hasValidSignatureFormat()) {
            return ValidationResult.error("Invalid signature format");
        }

        if (!tx.verifySignature()) {
            return ValidationResult.error("Signature verification failed");
        }

        // 2. 检查签名者 = from地址
        Bytes recoveredAddress = tx.recoverFromAddress();
        if (!recoveredAddress.equals(tx.getFrom())) {
            return ValidationResult.error("Signature does not match from address");
        }

        // 3. 检查金额非负
        if (tx.getAmount().isNegative()) {
            return ValidationResult.error("Amount cannot be negative");
        }

        if (tx.getFee().isNegative()) {
            return ValidationResult.error("Fee cannot be negative");
        }

        // 4. 检查data大小
        if (tx.getData() != null && tx.getData().size() > Transaction.MAX_DATA_SIZE) {
            return ValidationResult.error(String.format(
                "Data too large: %d bytes (max: %d)",
                tx.getData().size(), Transaction.MAX_DATA_SIZE
            ));
        }

        // 5. 检查接收方地址
        if (tx.getTo() == null || tx.getTo().size() != 20) {
            return ValidationResult.error("Invalid recipient address");
        }

        return ValidationResult.success();
    }

    /**
     * Pool入池验证（Level 2）
     */
    private ValidationResult validatePoolEntry(Transaction tx) {
        // 1. 检查是否已执行
        if (transactionStore.isTransactionExecuted(tx.getHash())) {
            return ValidationResult.error("Transaction already executed");
        }

        // 2. 检查账户是否存在
        if (!accountManager.accountExists(tx.getFrom())) {
            return ValidationResult.error("Sender account does not exist");
        }

        // 3. 检查余额（考虑Pool中前序交易）
        UInt256 balance = accountManager.getBalance(tx.getFrom());
        XAmount required = tx.getAmount().add(tx.getFee());

        if (balance.compareTo(required.toUInt256()) < 0) {
            return ValidationResult.error(String.format(
                "Insufficient balance: have %s, need %s",
                XAmount.ofNanoXDAG(balance.toLong()).toDecimal(9, XUnit.XDAG),
                required.toDecimal(9, XUnit.XDAG)
            ));
        }

        // 4. 检查nonce
        UInt64 accountNonce = accountManager.getNonce(tx.getFrom());
        long expectedNonce = accountNonce.toLong() + 1;

        // 注意: 这里需要考虑Pool中已有的交易
        // 由TransactionPool.canAcceptNonce()负责详细检查

        // 5. 检查手续费
        XAmount minFee = config.getTransactionPoolSpec().getMinFee();
        if (tx.getFee().compareTo(minFee) < 0) {
            return ValidationResult.error(String.format(
                "Fee too low: %s < %s",
                tx.getFee().toDecimal(9, XUnit.XDAG),
                minFee.toDecimal(9, XUnit.XDAG)
            ));
        }

        return ValidationResult.success();
    }

    /**
     * 执行前验证（Level 3）
     */
    private ValidationResult validatePreExecution(Transaction tx) {
        // 1. 检查账户是否冻结
        if (accountManager.isAccountFrozen(tx.getFrom())) {
            return ValidationResult.error(String.format(
                "Sender account is frozen: %s",
                accountManager.getFrozenReason(tx.getFrom())
            ));
        }

        // 2. 重新检查余额（Block中前序交易可能已消耗）
        // 这个检查在processTransaction()中进行

        // 3. 检查nonce精确匹配
        UInt64 accountNonce = accountManager.getNonce(tx.getFrom());
        long expectedNonce = accountNonce.toLong() + 1;

        if (tx.getNonce() != expectedNonce) {
            return ValidationResult.error(String.format(
                "Invalid nonce: have %d, expected %d",
                tx.getNonce(), expectedNonce
            ));
        }

        return ValidationResult.success();
    }

    /**
     * 完整验证（Level 4）- 预留EVM集成
     */
    private ValidationResult validateFull(Transaction tx) {
        // TODO: EVM gas limit validation
        // TODO: Smart contract call validation
        return ValidationResult.success();
    }
}
```

#### 5.3 各阶段调用验证

```java
// ========== 阶段1: 交易创建时 ==========
// Commands.java - transfer()
Transaction signedTx = tx.sign(fromAccount);

// 基础验证
ValidationResult result = transactionValidator.validate(signedTx, ValidationLevel.BASIC);
if (!result.isValid()) {
    return "Transaction validation failed: " + result.getError();
}

// ========== 阶段2: 加入Pool时 ==========
// TransactionPoolImpl.java - addTransaction()
public boolean addTransaction(Transaction tx) {
    // Pool入池验证
    ValidationResult result = transactionValidator.validate(tx, ValidationLevel.POOL_ENTRY);
    if (!result.isValid()) {
        log.debug("Transaction {} rejected at pool entry: {}",
            tx.getHash().toHexString().substring(0, 16),
            result.getError());
        totalRejected.incrementAndGet();
        return false;
    }

    // ... 添加到Pool ...
}

// ========== 阶段3: Block执行时 ==========
// DagTransactionProcessor.java - processTransaction()
public ProcessingResult processTransaction(Transaction tx) {
    // 执行前验证
    ValidationResult result = transactionValidator.validate(tx, ValidationLevel.PRE_EXECUTION);
    if (!result.isValid()) {
        return ProcessingResult.error("Pre-execution validation failed: " + result.getError());
    }

    // ... 执行交易 ...
}
```

**设计总结**:
- **创建时**: BASIC验证（快速失败，避免无效交易）
- **入Pool时**: POOL_ENTRY验证（过滤掉当前无效的交易）
- **执行时**: PRE_EXECUTION验证（确保最终状态有效）
- **未来**: FULL验证（EVM集成时启用）

---

### 问题 #6-#9: Epoch竞争、时间戳、手续费、Pool容量

由于篇幅限制，这些问题的详细解决方案见文档后续章节。

**简要方案**:
- **#6 Epoch竞争交易**: 回滚机制已覆盖，demoteBlockToOrphan()处理
- **#7 交易时间戳**: 在BASIC验证中检查timestamp合理性
- **#8 手续费分配**: 在DagBlockProcessor中累积手续费，分配给coinbase
- **#9 Pool容量**: Caffeine的maximumSize + 手续费驱逐策略

---

### 问题 #10: 多个Block引用同一交易（详细方案）

**已在问题#2中详细解决**，核心机制：
1. **全局执行状态追踪**: TransactionStore.isTransactionExecuted()
2. **执行时检查**: 如果已执行，跳过
3. **回滚支持**: unmarkTransactionExecuted()允许链重组后重新执行

**额外考虑 - 交易手续费分配**:
```java
// 问题: 如果Block A和Block B都引用Transaction T1
// Block A成为主块，执行T1，获得手续费
// Block B也成为主块（不同epoch），但T1已执行
// → Block B不应获得T1的手续费

/**
 * 手续费分配逻辑增强
 */
public class FeeDistributor {

    public XAmount calculateBlockFees(Block block, List<Transaction> executedTransactions) {
        XAmount totalFees = XAmount.ZERO;

        // 只统计在这个Block中首次执行的交易的手续费
        for (Transaction tx : executedTransactions) {
            TransactionExecutionInfo execInfo =
                transactionStore.getExecutionInfo(tx.getHash());

            // 检查是否是这个Block首次执行
            if (execInfo.getExecutingBlockHash().equals(block.getHash())) {
                totalFees = totalFees.add(tx.getFee());
            }
        }

        return totalFees;
    }
}
```

---

### 问题 #11: 交易执行失败处理（详细方案）

**问题细化**:
```
Scenario: Transaction T1被Block A引用，Block A成为主块

情况1: T1验证失败（签名错误）
→ 这种情况不应该发生（Pool入池时已验证）
→ 如果发生，说明数据被篡改，拒绝Block？

情况2: T1执行失败（余额不足）
→ Pool入池时余额充足
→ Block中前序交易消耗了余额
→ T1无法执行

情况3: T1执行失败（nonce不匹配）
→ Pool入池时nonce正确
→ Block中前序交易改变了nonce
→ T1无法执行

问题: Block A本身是有效的，但其中的交易失败了，应该如何处理？
```

**解决方案**: **交易失败不影响Block有效性 + 显式失败记录**

#### 11.1 交易执行结果类型

```java
/**
 * 交易执行结果
 */
public enum TransactionExecutionStatus {
    /**
     * 执行成功
     */
    SUCCESS,

    /**
     * 执行失败 - 余额不足
     */
    FAILED_INSUFFICIENT_BALANCE,

    /**
     * 执行失败 - Nonce不匹配
     */
    FAILED_INVALID_NONCE,

    /**
     * 执行失败 - 账户冻结
     */
    FAILED_ACCOUNT_FROZEN,

    /**
     * 执行失败 - 其他原因
     */
    FAILED_OTHER,

    /**
     * 跳过 - 已被其他Block执行
     */
    SKIPPED_ALREADY_EXECUTED
}

/**
 * 交易执行详情
 */
@Value
@Builder
public class TransactionExecutionDetail {
    Bytes32 transactionHash;
    TransactionExecutionStatus status;
    String errorMessage;              // 错误信息（如果失败）
    Bytes32 executingBlockHash;       // 执行该交易的Block
    long executingBlockHeight;        // Block高度
    long executionTimestamp;          // 执行时间戳
    XAmount gasUsed;                  // 消耗的gas（预留EVM）
}
```

#### 11.2 TransactionStore增强

```java
public interface TransactionStore extends XdagLifecycle {

    // ========== 执行详情记录 ==========

    /**
     * 保存交易执行详情
     */
    void saveExecutionDetail(TransactionExecutionDetail detail);

    /**
     * 获取交易执行详情
     */
    TransactionExecutionDetail getExecutionDetail(Bytes32 txHash);

    /**
     * 查询失败的交易
     * @param blockHash Block hash
     * @return 该Block中失败的交易列表
     */
    List<TransactionExecutionDetail> getFailedTransactions(Bytes32 blockHash);
}
```

#### 11.3 交易执行器增强

```java
// DagTransactionProcessor.java
public ProcessingResult processTransaction(Transaction tx) {
    Bytes32 txHash = tx.getHash();

    try {
        // 1. 执行前验证
        ValidationResult validationResult =
            transactionValidator.validate(tx, ValidationLevel.PRE_EXECUTION);

        if (!validationResult.isValid()) {
            // 验证失败，记录并返回
            log.warn("Transaction {} validation failed: {}",
                txHash.toHexString().substring(0, 16),
                validationResult.getError());

            return ProcessingResult.failure(
                TransactionExecutionStatus.FAILED_INVALID_NONCE,  // 或其他状态
                validationResult.getError()
            );
        }

        // 2. 执行交易
        // 2.1 扣除发送方余额
        UInt256 senderBalance = accountManager.getBalance(tx.getFrom());
        XAmount totalAmount = tx.getAmount().add(tx.getFee());

        if (senderBalance.compareTo(totalAmount.toUInt256()) < 0) {
            // 余额不足（Block中前序交易可能消耗了余额）
            return ProcessingResult.failure(
                TransactionExecutionStatus.FAILED_INSUFFICIENT_BALANCE,
                String.format("Insufficient balance at execution: have %s, need %s",
                    XAmount.ofNanoXDAG(senderBalance.toLong()).toDecimal(9, XUnit.XDAG),
                    totalAmount.toDecimal(9, XUnit.XDAG))
            );
        }

        accountManager.subtractBalance(tx.getFrom(), totalAmount);

        // 2.2 增加接收方余额
        accountManager.addBalance(tx.getTo(), tx.getAmount());

        // 2.3 增加发送方nonce
        accountManager.incrementNonce(tx.getFrom());

        log.info("Transaction {} executed successfully (from={}, to={}, amount={})",
            txHash.toHexString().substring(0, 16),
            tx.getFrom().toHexString().substring(0, 8),
            tx.getTo().toHexString().substring(0, 8),
            tx.getAmount().toDecimal(9, XUnit.XDAG));

        return ProcessingResult.success();

    } catch (Exception e) {
        log.error("Transaction {} execution threw exception: {}",
            txHash.toHexString(), e.getMessage(), e);

        return ProcessingResult.failure(
            TransactionExecutionStatus.FAILED_OTHER,
            "Exception during execution: " + e.getMessage()
        );
    }
}
```

#### 11.4 Block处理器增强

```java
// DagBlockProcessor.java
public ProcessingResult processBlock(Block block) {
    List<Transaction> transactions = extractTransactionsFromBlock(block);

    log.info("Processing {} transactions in block {} (height={})",
        transactions.size(),
        block.getHash().toHexString().substring(0, 16),
        block.getInfo().getHeight());

    int successCount = 0;
    int failedCount = 0;
    int skippedCount = 0;

    XAmount totalFees = XAmount.ZERO;
    List<TransactionExecutionDetail> executionDetails = new ArrayList<>();

    for (Transaction tx : transactions) {
        Bytes32 txHash = tx.getHash();

        // 检查是否已执行
        if (transactionStore.isTransactionExecuted(txHash)) {
            skippedCount++;

            executionDetails.add(TransactionExecutionDetail.builder()
                .transactionHash(txHash)
                .status(TransactionExecutionStatus.SKIPPED_ALREADY_EXECUTED)
                .executingBlockHash(block.getHash())
                .executingBlockHeight(block.getInfo().getHeight())
                .executionTimestamp(System.currentTimeMillis())
                .build());

            continue;
        }

        // 执行交易
        ProcessingResult txResult = txProcessor.processTransaction(tx);

        if (txResult.isSuccess()) {
            // ========== 成功 ==========
            successCount++;
            totalFees = totalFees.add(tx.getFee());

            // 标记为已执行
            transactionStore.markTransactionExecuted(
                txHash,
                block.getHash(),
                block.getInfo().getHeight()
            );

            executionDetails.add(TransactionExecutionDetail.builder()
                .transactionHash(txHash)
                .status(TransactionExecutionStatus.SUCCESS)
                .executingBlockHash(block.getHash())
                .executingBlockHeight(block.getInfo().getHeight())
                .executionTimestamp(System.currentTimeMillis())
                .gasUsed(XAmount.ZERO)  // TODO: EVM gas
                .build());

        } else {
            // ========== 失败 ==========
            failedCount++;

            log.warn("Transaction {} execution failed in block {}: {}",
                txHash.toHexString().substring(0, 16),
                block.getHash().toHexString().substring(0, 16),
                txResult.getError());

            // 记录失败详情
            executionDetails.add(TransactionExecutionDetail.builder()
                .transactionHash(txHash)
                .status(txResult.getStatus())
                .errorMessage(txResult.getError())
                .executingBlockHash(block.getHash())
                .executingBlockHeight(block.getInfo().getHeight())
                .executionTimestamp(System.currentTimeMillis())
                .build());

            // ========== 关键决策: 不标记为已执行 ==========
            // 失败的交易不标记为executed，允许后续Block重新尝试

            // ========== 从Pool移除 ==========
            // 失败的交易从Pool移除，避免无限重试
            transactionPool.removeTransaction(txHash);
        }

        // 保存执行详情（成功或失败）
        transactionStore.saveExecutionDetail(executionDetails.get(executionDetails.size() - 1));
    }

    // 分配手续费给coinbase
    if (totalFees.compareTo(XAmount.ZERO) > 0) {
        Bytes coinbase = block.getHeader().getCoinbase();
        accountManager.addBalance(coinbase, totalFees);

        log.info("Distributed total fees {} XDAG to coinbase {}",
            totalFees.toDecimal(9, XUnit.XDAG),
            coinbase.toHexString().substring(0, 16));
    }

    log.info("Block {} processing complete: {} succeeded, {} failed, {} skipped (total: {})",
        block.getHash().toHexString().substring(0, 16),
        successCount,
        failedCount,
        skippedCount,
        transactions.size());

    // ========== Block仍然有效，即使所有交易都失败 ==========
    return ProcessingResult.success();
}
```

#### 11.5 关键设计决策

**决策1: 交易失败不影响Block有效性**
```
理由:
1. Block本身的PoW是有效的
2. Block引用交易只是"尝试执行"，不保证成功
3. 交易失败可能是临时状态（如余额不足）
4. 拒绝Block会导致矿工损失，不公平

结果: Block保持有效，交易标记为失败
```

**决策2: 失败的交易不标记为executed**
```
理由:
1. 后续Block可能重新引用该交易
2. 账户状态可能已恢复（如收到新转账）
3. 允许交易"重试"

风险: 可能被无限重试
缓解: 从Pool移除失败交易，避免无限重试
```

**决策3: 失败交易从Pool移除**
```
理由:
1. 避免候选Block重复选择失败交易
2. 减少Pool容量占用
3. 用户需要修正交易（如增加余额）后重新提交

用户体验: 提供CLI命令查询交易失败原因
```

#### 11.6 用户查询失败交易

```java
// Commands.java
public String transactionStatus(String txHashStr) {
    Bytes32 txHash = BasicUtils.getHash(txHashStr);

    // 查询执行详情
    TransactionExecutionDetail detail =
        dagKernel.getTransactionStore().getExecutionDetail(txHash);

    if (detail == null) {
        // 检查是否在Pool中
        if (dagKernel.getTransactionPool().contains(txHash)) {
            return "Status: PENDING (in transaction pool)";
        } else {
            return "Transaction not found";
        }
    }

    StringBuilder output = new StringBuilder();
    output.append("═══════════════════════════════════════════════════\n");
    output.append("Transaction Execution Status\n");
    output.append("═══════════════════════════════════════════════════\n");
    output.append(String.format("Hash:   %s\n", txHash.toHexString()));
    output.append(String.format("Status: %s\n", detail.getStatus()));

    switch (detail.getStatus()) {
        case SUCCESS:
            output.append(String.format("Block:  %s\n",
                detail.getExecutingBlockHash().toHexString()));
            output.append(String.format("Height: %d\n",
                detail.getExecutingBlockHeight()));
            break;

        case FAILED_INSUFFICIENT_BALANCE:
        case FAILED_INVALID_NONCE:
        case FAILED_ACCOUNT_FROZEN:
        case FAILED_OTHER:
            output.append(String.format("Error:  %s\n", detail.getErrorMessage()));
            output.append(String.format("Block:  %s\n",
                detail.getExecutingBlockHash().toHexString()));
            output.append("\nAction Required: Fix the issue and resubmit the transaction\n");
            break;

        case SKIPPED_ALREADY_EXECUTED:
            output.append("This transaction was already executed by another block\n");
            break;
    }

    return output.toString();
}
```

**设计总结**:
1. **Block有效性**: 交易失败不影响Block
2. **执行状态**: 只有成功的交易标记为executed
3. **重试机制**: 失败交易从Pool移除，需要用户重新提交
4. **透明性**: 提供详细的失败原因查询

---

### 问题 #12: 回滚原子性（详细方案）

**问题描述**:
```java
// 回滚包含5个步骤
private void rollbackBlockTransactions(Block block) {
    for (Transaction tx : transactions) {
        // Step 1: 恢复发送方余额
        accountManager.addBalance(tx.getFrom(), tx.getAmount().add(tx.getFee()));

        // Step 2: 扣除接收方余额
        accountManager.subtractBalance(tx.getTo(), tx.getAmount());

        // Step 3: 恢复发送方nonce
        accountManager.decrementNonce(tx.getFrom());  // ❌ 这里失败了！

        // Step 4: 取消执行标记
        transactionStore.unmarkTransactionExecuted(tx.getHash());  // ❌ 未执行

        // Step 5: 返回Pool
        transactionPool.addTransaction(tx);  // ❌ 未执行
    }
}

结果: Step 1-2已完成，Step 3-5未完成 → 状态不一致！
```

**用户要求**: 需要事务支持（all-or-nothing）

**解决方案**: **基于RocksDB的事务性回滚**

#### 12.1 RocksDB事务支持

RocksDB原生支持事务（WriteBatch + Atomic Write）

```java
/**
 * 事务性存储接口
 */
public interface TransactionalStore {

    /**
     * 开始事务
     * @return 事务ID
     */
    String beginTransaction();

    /**
     * 提交事务
     * @param txId 事务ID
     * @throws RollbackException if commit fails
     */
    void commitTransaction(String txId) throws RollbackException;

    /**
     * 回滚事务
     * @param txId 事务ID
     */
    void rollbackTransaction(String txId);
}

/**
 * RocksDB事务实现
 */
public class RocksDBTransactionManager implements TransactionalStore {

    private final RocksDB db;
    private final ConcurrentHashMap<String, WriteBatch> transactions = new ConcurrentHashMap<>();
    private final AtomicLong txIdGenerator = new AtomicLong(0);

    @Override
    public String beginTransaction() {
        String txId = "tx-" + txIdGenerator.incrementAndGet();
        WriteBatch batch = new WriteBatch();
        transactions.put(txId, batch);

        log.debug("Transaction {} started", txId);
        return txId;
    }

    @Override
    public void commitTransaction(String txId) throws RollbackException {
        WriteBatch batch = transactions.remove(txId);
        if (batch == null) {
            throw new RollbackException("Transaction not found: " + txId);
        }

        try (WriteOptions options = new WriteOptions()) {
            // 原子性写入
            db.write(options, batch);
            batch.close();

            log.debug("Transaction {} committed", txId);

        } catch (RocksDBException e) {
            log.error("Failed to commit transaction {}: {}", txId, e.getMessage());
            batch.close();
            throw new RollbackException("Commit failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void rollbackTransaction(String txId) {
        WriteBatch batch = transactions.remove(txId);
        if (batch != null) {
            batch.close();
            log.debug("Transaction {} rolled back", txId);
        }
    }

    /**
     * 在事务中执行Put操作
     */
    public void putInTransaction(String txId, byte[] key, byte[] value) throws RollbackException {
        WriteBatch batch = transactions.get(txId);
        if (batch == null) {
            throw new RollbackException("Transaction not found: " + txId);
        }

        try {
            batch.put(key, value);
        } catch (RocksDBException e) {
            throw new RollbackException("Put failed: " + e.getMessage(), e);
        }
    }

    /**
     * 在事务中执行Delete操作
     */
    public void deleteInTransaction(String txId, byte[] key) throws RollbackException {
        WriteBatch batch = transactions.get(txId);
        if (batch == null) {
            throw new RollbackException("Transaction not found: " + txId);
        }

        try {
            batch.delete(key);
        } catch (RocksDBException e) {
            throw new RollbackException("Delete failed: " + e.getMessage(), e);
        }
    }
}
```

#### 12.2 AccountManager事务支持

```java
/**
 * 账户管理器事务接口
 */
public interface DagAccountManager {

    // ========== 事务性操作 ==========

    /**
     * 在事务中设置余额
     */
    void setBalanceInTransaction(String txId, Bytes address, UInt256 balance)
        throws RollbackException;

    /**
     * 在事务中增加余额
     */
    void addBalanceInTransaction(String txId, Bytes address, XAmount amount)
        throws RollbackException;

    /**
     * 在事务中扣除余额
     */
    void subtractBalanceInTransaction(String txId, Bytes address, XAmount amount)
        throws RollbackException;

    /**
     * 在事务中增加nonce
     */
    void incrementNonceInTransaction(String txId, Bytes address)
        throws RollbackException;

    /**
     * 在事务中减少nonce
     */
    void decrementNonceInTransaction(String txId, Bytes address)
        throws RollbackException;
}

/**
 * 实现
 */
public class DagAccountManagerImpl implements DagAccountManager {

    private final RocksDBTransactionManager txManager;
    private final KVSource<byte[], byte[]> accountStore;

    @Override
    public void setBalanceInTransaction(String txId, Bytes address, UInt256 balance)
        throws RollbackException {

        // 获取当前账户状态
        AccountState state = getAccountState(address);

        // 更新余额
        AccountState newState = state.toBuilder()
            .balance(balance)
            .build();

        // 序列化并写入事务
        byte[] key = address.toArray();
        byte[] value = serializeAccountState(newState);
        txManager.putInTransaction(txId, key, value);

        log.debug("Transaction {}: Set balance for {} to {}",
            txId, address.toHexString().substring(0, 8), balance.toDecimalString());
    }

    @Override
    public void incrementNonceInTransaction(String txId, Bytes address)
        throws RollbackException {

        AccountState state = getAccountState(address);
        UInt64 newNonce = state.getNonce().add(UInt64.ONE);

        AccountState newState = state.toBuilder()
            .nonce(newNonce)
            .build();

        byte[] key = address.toArray();
        byte[] value = serializeAccountState(newState);
        txManager.putInTransaction(txId, key, value);

        log.debug("Transaction {}: Incremented nonce for {} to {}",
            txId, address.toHexString().substring(0, 8), newNonce.toLong());
    }

    @Override
    public void decrementNonceInTransaction(String txId, Bytes address)
        throws RollbackException {

        AccountState state = getAccountState(address);

        if (state.getNonce().equals(UInt64.ZERO)) {
            throw new RollbackException("Cannot decrement nonce below 0");
        }

        UInt64 newNonce = state.getNonce().subtract(UInt64.ONE);

        AccountState newState = state.toBuilder()
            .nonce(newNonce)
            .build();

        byte[] key = address.toArray();
        byte[] value = serializeAccountState(newState);
        txManager.putInTransaction(txId, key, value);

        log.debug("Transaction {}: Decremented nonce for {} to {}",
            txId, address.toHexString().substring(0, 8), newNonce.toLong());
    }
}
```

#### 12.3 TransactionStore事务支持

```java
public interface TransactionStore extends XdagLifecycle {

    /**
     * 在事务中取消执行标记
     */
    void unmarkTransactionExecutedInTransaction(String txId, Bytes32 txHash)
        throws RollbackException;
}

public class TransactionStoreImpl implements TransactionStore {

    private final RocksDBTransactionManager txManager;
    private final KVSource<byte[], byte[]> executionIndex;

    @Override
    public void unmarkTransactionExecutedInTransaction(String txId, Bytes32 txHash)
        throws RollbackException {

        byte[] key = txHash.toArray();
        txManager.deleteInTransaction(txId, key);

        log.debug("Transaction {}: Unmarked transaction {} as executed",
            txId, txHash.toHexString().substring(0, 16));
    }
}
```

#### 12.4 事务性回滚实现

```java
// DagChainImpl.java
private void rollbackBlockTransactions(Block block) {
    if (dagKernel == null) return;

    DagAccountManager accountManager = dagKernel.getDagAccountManager();
    TransactionStore transactionStore = dagKernel.getTransactionStore();
    RocksDBTransactionManager txManager = dagKernel.getTransactionManager();

    List<Transaction> transactions = extractTransactionsFromBlock(block);

    if (transactions.isEmpty()) {
        return;
    }

    log.warn("Rolling back {} transactions from demoted block {} (height={})",
        transactions.size(),
        block.getHash().toHexString().substring(0, 16),
        block.getInfo().getHeight());

    // ========== 开始数据库事务 ==========
    String dbTxId = txManager.beginTransaction();

    try {
        // 按执行顺序的逆序回滚
        for (int i = transactions.size() - 1; i >= 0; i--) {
            Transaction tx = transactions.get(i);

            if (!transactionStore.isTransactionExecuted(tx.getHash())) {
                continue;
            }

            // ========== 在事务中执行回滚操作 ==========

            // Step 1: 恢复发送方余额
            UInt256 senderBalance = accountManager.getBalance(tx.getFrom());
            UInt256 amountWithFee = tx.getAmount().toUInt256().add(tx.getFee().toUInt256());
            accountManager.setBalanceInTransaction(
                dbTxId,
                tx.getFrom(),
                senderBalance.add(amountWithFee)
            );

            // Step 2: 扣除接收方余额
            UInt256 receiverBalance = accountManager.getBalance(tx.getTo());
            UInt256 newReceiverBalance = receiverBalance.subtract(tx.getAmount().toUInt256());
            accountManager.setBalanceInTransaction(
                dbTxId,
                tx.getTo(),
                newReceiverBalance
            );

            // Step 3: 恢复发送方nonce
            accountManager.decrementNonceInTransaction(dbTxId, tx.getFrom());

            // Step 4: 取消执行标记
            transactionStore.unmarkTransactionExecutedInTransaction(dbTxId, tx.getHash());

            log.debug("Transaction {} rolled back in transaction {}",
                tx.getHash().toHexString().substring(0, 16), dbTxId);
        }

        // ========== 原子性提交 ==========
        txManager.commitTransaction(dbTxId);

        log.info("Successfully rolled back {} transactions from block {} (transaction: {})",
            transactions.size(),
            block.getHash().toHexString().substring(0, 16),
            dbTxId);

        // ========== Step 5: 返回Pool（在事务提交后） ==========
        for (int i = transactions.size() - 1; i >= 0; i--) {
            Transaction tx = transactions.get(i);

            // 决策: 是否返回Pool？
            if (shouldReturnToPool(tx, block)) {
                transactionPool.addTransaction(tx);
                log.debug("Transaction {} returned to pool",
                    tx.getHash().toHexString().substring(0, 16));
            }
        }

    } catch (RollbackException e) {
        // ========== 回滚失败，撤销事务 ==========
        log.error("Transaction rollback failed for block {}, aborting transaction {}: {}",
            block.getHash().toHexString().substring(0, 16),
            dbTxId,
            e.getMessage());

        txManager.rollbackTransaction(dbTxId);

        // 严重错误：状态不一致
        throw new IllegalStateException(
            "CRITICAL: Failed to rollback transactions from block " +
            block.getHash().toHexString() + ": " + e.getMessage(),
            e
        );
    }
}

/**
 * 决定交易是否应该返回Pool
 */
private boolean shouldReturnToPool(Transaction tx, Block block) {
    // 策略1: 不返回Pool（用户需要重新提交）
    // return false;

    // 策略2: 返回Pool（自动重试）
    // 检查交易是否仍然有效
    ValidationResult result = transactionValidator.validate(tx, ValidationLevel.POOL_ENTRY);
    return result.isValid();
}
```

#### 12.5 事务回滚保证

**ACID保证**:
1. **原子性 (Atomicity)**: RocksDB WriteBatch保证所有操作要么全部成功，要么全部失败
2. **一致性 (Consistency)**: 验证逻辑确保状态转换合法
3. **隔离性 (Isolation)**: DagChainImpl.tryToConnect()是synchronized，避免并发
4. **持久性 (Durability)**: RocksDB提供持久化存储

**失败处理**:
```java
try {
    // 回滚操作
    txManager.commitTransaction(dbTxId);
} catch (RollbackException e) {
    // 1. 撤销数据库事务
    txManager.rollbackTransaction(dbTxId);

    // 2. 抛出严重错误
    throw new IllegalStateException("CRITICAL: Rollback failed", e);

    // 3. 可选: 标记链状态为corrupted，停止服务
    // dagKernel.markAsCorrupted("Rollback transaction failed");
    // dagKernel.shutdown();
}
```

**设计总结**:
1. **RocksDB WriteBatch**: 提供原子性保证
2. **All-or-Nothing**: 所有操作在同一事务中
3. **失败回滚**: 事务失败时自动撤销
4. **状态一致性**: 避免部分回滚导致的不一致

---

### 问题 #13-#15: 存储、网络、测试

**简要方案**:

**#13 - 存储空间管理**:
- Caffeine eviction policy (size-based + time-based)
- 定期清理过期交易
- 数据库compaction

**#14 - 网络传播**:
- 交易通过Block引用传播（不需要单独的P2P层）
- Pool只存储本地创建或接收到的交易

**#15 - 测试**:
- 单元测试: 每个组件独立测试
- 集成测试: 完整流程测试
- 压力测试: Caffeine性能验证

---

## 三、TransactionPool完整设计

### 3.1 Caffeine配置

```java
/**
 * TransactionPool实现（基于Caffeine）
 */
@Slf4j
public class TransactionPoolImpl implements TransactionPool {

    // ========== Caffeine Cache配置 ==========
    private final Cache<Bytes32, PendingTransaction> txCache;

    // 账户nonce索引
    private final ConcurrentHashMap<Bytes, TreeMap<Long, Bytes32>> accountNonceMap;

    // 读写锁
    private final ReadWriteLock poolLock = new ReentrantReadWriteLock(true);

    // 配置
    private final TransactionPoolSpec config;
    private final DagAccountManager accountManager;
    private final TransactionStore transactionStore;
    private final TransactionValidator transactionValidator;

    // 统计
    private final AtomicLong totalAdded = new AtomicLong(0);
    private final AtomicLong totalRemoved = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);

    public TransactionPoolImpl(
        TransactionPoolSpec config,
        DagAccountManager accountManager,
        TransactionStore transactionStore,
        TransactionValidator transactionValidator
    ) {
        this.config = config;
        this.accountManager = accountManager;
        this.transactionStore = transactionStore;
        this.transactionValidator = transactionValidator;
        this.accountNonceMap = new ConcurrentHashMap<>();

        // ========== Caffeine Cache构建 ==========
        this.txCache = Caffeine.newBuilder()
            // 容量限制
            .maximumSize(config.getMaxPoolSize())

            // 过期策略: 1小时未被访问则移除
            .expireAfterAccess(config.getMaxTxAgeMillis(), TimeUnit.MILLISECONDS)

            // 移除监听器: 清理nonce索引
            .removalListener((Bytes32 key, PendingTransaction value, RemovalCause cause) -> {
                if (value != null) {
                    Transaction tx = value.getTransaction();

                    // 清理nonce索引
                    TreeMap<Long, Bytes32> accountTxs = accountNonceMap.get(tx.getFrom());
                    if (accountTxs != null) {
                        accountTxs.remove(tx.getNonce());
                        if (accountTxs.isEmpty()) {
                            accountNonceMap.remove(tx.getFrom());
                        }
                    }

                    log.debug("Transaction {} removed from pool (cause: {})",
                        key.toHexString().substring(0, 16), cause);

                    totalRemoved.incrementAndGet();
                }
            })

            // 统计记录
            .recordStats()

            .build();

        log.info("TransactionPool initialized with Caffeine (maxSize={}, maxAge={}ms)",
            config.getMaxPoolSize(), config.getMaxTxAgeMillis());
    }

    // ... 实现方法（见前文）...
}
```

### 3.2 配置规范

```java
/**
 * TransactionPool配置
 */
@Data
@Builder
public class TransactionPoolSpec {

    /**
     * Pool最大容量
     * 默认: 10000笔交易
     */
    @Builder.Default
    private int maxPoolSize = 10000;

    /**
     * 交易最大存活时间（毫秒）
     * 默认: 1小时
     */
    @Builder.Default
    private long maxTxAgeMillis = 3600_000L;

    /**
     * 最低手续费（nano XDAG）
     * 默认: 100 milli-XDAG = 100,000,000 nano XDAG
     */
    @Builder.Default
    private long minFeeNano = 100_000_000L;

    /**
     * 清理任务间隔（毫秒）
     * 默认: 5分钟
     */
    @Builder.Default
    private long cleanupIntervalMillis = 300_000L;

    public XAmount getMinFee() {
        return XAmount.of(minFeeNano, XUnit.NANO_XDAG);
    }
}
```

---

## 四、实施计划

### Phase 0: 紧急修复（已完成 ✅）

- ✅ Task 0.1: 修正交易执行时机（DagChainImpl.java:360-377）

### Phase 0.2: 代码清理（1天）

**目标**: 清理遗留的无效代码、注释和测试，提高代码质量

#### Task 0.2.1: 清理无效代码（4小时）

**清理标准**:
1. **Dead Code**: 未被调用的方法、类
2. **注释掉的代码**: 被注释的代码块（保留TODO/FIXME注释）
3. **废弃的实现**: 标记为@Deprecated但未删除的代码
4. **重复代码**: 功能重复的工具方法
5. **临时调试代码**: System.out.println、printStackTrace等

**清理范围**:
- `src/main/java/io/xdag/core/`
- `src/main/java/io/xdag/db/`
- `src/main/java/io/xdag/cli/`

**清理示例**:
```java
// ❌ Before: 废弃代码
@Deprecated
public void oldProcessBlock(Block block) { ... }

// ✅ After: 直接删除
```

---

#### Task 0.2.2: 清理无效注释（3小时）

**清理标准**:
- ❌ 过时注释（与代码不符）
- ❌ 空洞注释（无信息量）
- ❌ 废弃TODO（已完成）

**保留注释**:
- ✅ Javadoc
- ✅ 复杂逻辑说明
- ✅ 有效TODO/FIXME

---

#### Task 0.2.3: 清理无效单元测试（4小时）

**清理标准**:
1. **失败的测试**: 长期失败且未修复
2. **@Ignore测试**: 无说明原因
3. **空测试**: 没有断言
4. **过时测试**: 测试已删除功能
5. **编译失败测试**: 引用不存在的类

**重点清理**:
- `GenTestAddress.java` (编译失败)
- `ConsensusTestP0.java` (引用不存在的类)
- `ConsensusTestP1.java` (引用不存在的类)
- `TransactionExecutionTimingTest.java` (依赖问题)

**清理流程**:
```bash
# 1. 运行测试识别问题
mvn clean test 2>&1 | tee test-report.txt

# 2. 分类处理:
#    - 可修复 → 修复
#    - 过时 → 删除
#    - 需重写 → 标记TODO + @Ignore("原因")
```

---

#### Task 0.2.4: 清理导入和依赖（2小时）

**清理import**: 使用IDE Optimize Imports功能

**清理依赖**: 检查pom.xml中未使用的依赖

---

#### Task 0.2.5: 统一代码格式（1小时）

```bash
# 统一格式化
mvn spotless:apply
```

---

**Phase 0.2 总结**:
- 工作量: 14小时（约2天）
- 预期结果:
  - 删除所有无效代码和注释
  - 修复或删除失败测试
  - 统一代码格式
  - 减少技术债务

---

### Phase 0.5: 事务支持基础设施（2-3天）

**Task 0.5.1**: 实现RocksDBTransactionManager
- 文件: `src/main/java/io/xdag/db/rocksdb/RocksDBTransactionManager.java`
- 功能: WriteBatch封装、事务管理
- 工作量: 6小时

**Task 0.5.2**: AccountManager事务支持
- 文件: `src/main/java/io/xdag/core/DagAccountManagerImpl.java`
- 功能: xxxInTransaction()方法
- 工作量: 4小时

**Task 0.5.3**: TransactionStore事务支持
- 文件: `src/main/java/io/xdag/db/rocksdb/impl/TransactionStoreImpl.java`
- 功能: unmarkTransactionExecutedInTransaction()
- 工作量: 2小时

**Task 0.5.4**: 测试事务回滚
- 文件: `src/test/java/io/xdag/db/RocksDBTransactionTest.java`
- 功能: 验证原子性
- 工作量: 4小时

### Phase 1: 核心功能（3-4天）

**Task 1.1**: 实现Caffeine-based TransactionPool
- 文件: `src/main/java/io/xdag/core/TransactionPoolImpl.java`
- 工作量: 10小时

**Task 1.2**: 增强TransactionStore
- 新增执行状态追踪、详情记录
- 工作量: 6小时

**Task 1.3**: 实现TransactionValidator多级验证
- 文件: `src/main/java/io/xdag/core/TransactionValidator.java`
- 工作量: 8小时

**Task 1.4**: 实现事务性回滚
- 修改rollbackBlockTransactions()使用RocksDB事务
- 工作量: 6小时

### Phase 2: 集成与优化（2-3天）

**Task 2.1**: 集成TransactionPool到DagKernel
**Task 2.2**: 修改Commands.transfer()
**Task 2.3**: 修改collectCandidateLinks()
**Task 2.4**: 增强DagBlockProcessor

### Phase 3: 测试与文档（2天）

**Task 3.1**: 单元测试
**Task 3.2**: 集成测试
**Task 3.3**: 更新用户文档

---

## 五、风险评估

| 风险项 | 影响 | 概率 | 缓解措施 |
|--------|------|------|----------|
| RocksDB事务性能 | 中 | 低 | Benchmark测试，优化WriteBatch大小 |
| 回滚失败导致状态不一致 | 高 | 低 | 充分测试，失败时停止服务 |
| Pool容量不足 | 中 | 中 | 动态调整maxPoolSize |
| Caffeine eviction策略不合理 | 中 | 中 | 监控统计，调整参数 |
| 并发安全问题 | 高 | 低 | 充分的锁测试 |

---

## 六、总结

本设计文档解决了15个潜在问题，重点解决了：

1. **#10 - 多个Block引用**: 全局执行状态追踪，跳过已执行交易
2. **#11 - 交易失败处理**: Block有效性独立于交易，失败交易记录详情
3. **#12 - 回滚原子性**: RocksDB WriteBatch提供事务保证

**核心设计原则**:
- ✅ 安全第一：事务性回滚，状态一致性保证
- ✅ 性能优化：Caffeine缓存，读写锁并发控制
- ✅ 用户友好：详细的错误信息，透明的状态查询

**下一步**: 根据本文档实施代码修改

---

**文档版本**: 1.0
**创建日期**: 2025-11-16
**作者**: 开发团队
**审核状态**: 待审核
