# XDAG 交易系统修复计划

基于对当前实现的分析，制定完整的修复路线图

---

## 一、问题优先级评估

### 🔴 P0 - 阻塞性问题（必须立即修复）

1. **Transaction执行时机错误**
   - 当前：在判断主块之前就执行
   - 影响：孤块的Transaction也被执行，导致状态不一致
   - 风险：数据损坏、账户余额错误

2. **缺少Transaction回滚机制**
   - 当前：Block被demote时，Transaction已执行无法回滚
   - 影响：链重组时状态错误
   - 风险：共识失败、分叉

### 🟠 P1 - 关键功能缺失（影响核心功能）

3. **缺少TransactionPool**
   - 当前：Transaction创建后立即放入Block
   - 影响：无法实现手续费优先级、无法批量处理Transaction
   - 风险：无法集成EVM、性能低下

4. **候选块不选择Transaction**
   - 当前：`collectCandidateLinks()`只选择Block引用
   - 影响：矿工创建的Block不包含Transaction
   - 风险：Transaction无法被打包

### 🟡 P2 - 重要改进（影响未来扩展）

5. **缺少Transaction确定性排序**
   - 当前：按Link顺序执行，不确定
   - 影响：EVM集成无法实现
   - 风险：智能合约状态不一致

6. **缺少Transaction验证完整性**
   - 当前：基础验证不完整
   - 影响：可能接受无效Transaction
   - 风险：安全漏洞

### 🟢 P3 - 优化改进（提升体验）

7. **Transaction Pool性能优化**
8. **Transaction广播机制**
9. **Transaction监控和统计**

---

## 二、修复阶段规划

### Phase 0: 紧急修复（1-2天）- 修复P0问题

**目标**: 确保系统不会因为当前bug导致状态损坏

#### Task 0.1: 修正Transaction执行时机
**文件**: `src/main/java/io/xdag/core/DagChainImpl.java`

**当前代码** (line 356-369):
```java
// 处理Block中的Transaction（执行）
if (dagKernel != null) {
    DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
    if (blockProcessor != null) {
        DagBlockProcessor.ProcessingResult processResult =
                blockProcessor.processBlock(blockWithInfo);
        // ... 错误！这里还没判断是否主块就执行了
    }
}
```

**修复代码**:
```java
// 【修复】只有主块才执行Transaction
if (isBestChain && dagKernel != null) {
    DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
    if (blockProcessor != null) {
        DagBlockProcessor.ProcessingResult processResult =
                blockProcessor.processBlock(blockWithInfo);

        if (!processResult.isSuccess()) {
            // Transaction执行失败，记录错误但不拒绝Block
            // （因为Block本身是有效的，只是Transaction有问题）
            log.error("Block {} transaction processing failed: {}",
                    block.getHash().toHexString(), processResult.getError());
        } else {
            log.info("Block {} transactions executed successfully",
                    block.getHash().toHexString());
        }
    }
}
```

**测试验证**:
```java
// 测试用例1: 孤块的Transaction不应执行
@Test
public void testOrphanBlockTransactionNotExecuted() {
    // 1. 创建Transaction
    Transaction tx = createTestTransaction();
    transactionStore.saveTransaction(tx);

    // 2. 创建引用Transaction的Block（但作为孤块）
    Block orphanBlock = createBlockWithTransaction(tx);

    // 3. 导入Block（累积难度低，成为孤块）
    DagImportResult result = dagChain.tryToConnect(orphanBlock);
    assertFalse(result.isMainBlock());

    // 4. 验证Transaction未执行（账户余额未改变）
    UInt256 senderBalance = accountStore.getBalance(tx.getFrom());
    assertEquals(INITIAL_BALANCE, senderBalance);
}

// 测试用例2: 主块的Transaction应执行
@Test
public void testMainBlockTransactionExecuted() {
    // 1. 创建Transaction
    Transaction tx = createTestTransaction();
    transactionStore.saveTransaction(tx);

    // 2. 创建引用Transaction的Block（作为主块）
    Block mainBlock = createBlockWithTransaction(tx);

    // 3. 导入Block（累积难度高，成为主块）
    DagImportResult result = dagChain.tryToConnect(mainBlock);
    assertTrue(result.isMainBlock());

    // 4. 验证Transaction已执行（账户余额已改变）
    UInt256 senderBalance = accountStore.getBalance(tx.getFrom());
    assertEquals(INITIAL_BALANCE.subtract(tx.getAmount()).subtract(tx.getFee()),
                 senderBalance);
}
```

**工作量**: 1小时（代码修改）+ 2小时（测试）
**风险**: 低（修改逻辑清晰）

---

#### Task 0.2: 添加Transaction状态追踪
**文件**: `src/main/java/io/xdag/db/TransactionStore.java`

**目的**: 记录Transaction是否已被执行，防止重复执行

**新增接口**:
```java
public interface TransactionStore extends XdagLifecycle {

    // 现有方法...
    Transaction getTransaction(Bytes32 hash);
    void saveTransaction(Transaction tx);

    // 【新增】Transaction状态管理
    /**
     * 标记Transaction为已执行
     * @param txHash Transaction hash
     * @param blockHash 执行Transaction的Block hash
     */
    void markTransactionExecuted(Bytes32 txHash, Bytes32 blockHash);

    /**
     * 检查Transaction是否已执行
     * @param txHash Transaction hash
     * @return true if executed
     */
    boolean isTransactionExecuted(Bytes32 txHash);

    /**
     * 获取执行Transaction的Block
     * @param txHash Transaction hash
     * @return executing block hash, or null if not executed
     */
    Bytes32 getExecutingBlock(Bytes32 txHash);

    /**
     * 撤销Transaction执行（用于链重组）
     * @param txHash Transaction hash
     */
    void unmarkTransactionExecuted(Bytes32 txHash);
}
```

**实现** (`TransactionStoreImpl.java`):
```java
// 新增索引: tx_hash -> executing_block_hash
private final KVSource<byte[], byte[]> executionIndex;

@Override
public void markTransactionExecuted(Bytes32 txHash, Bytes32 blockHash) {
    executionIndex.put(txHash.toArray(), blockHash.toArray());
    log.debug("Marked transaction {} as executed by block {}",
            txHash.toHexString().substring(0, 16),
            blockHash.toHexString().substring(0, 16));
}

@Override
public boolean isTransactionExecuted(Bytes32 txHash) {
    return executionIndex.get(txHash.toArray()) != null;
}

@Override
public Bytes32 getExecutingBlock(Bytes32 txHash) {
    byte[] blockHashBytes = executionIndex.get(txHash.toArray());
    return blockHashBytes != null ? Bytes32.wrap(blockHashBytes) : null;
}

@Override
public void unmarkTransactionExecuted(Bytes32 txHash) {
    executionIndex.delete(txHash.toArray());
    log.debug("Unmarked transaction {} as executed",
            txHash.toHexString().substring(0, 16));
}
```

**使用**:
```java
// DagBlockProcessor.processBlock()
public ProcessingResult processBlock(Block block) {
    List<Transaction> transactions = extractTransactions(block);

    for (Transaction tx : transactions) {
        // 检查是否已执行
        if (transactionStore.isTransactionExecuted(tx.getHash())) {
            log.warn("Transaction {} already executed, skipping",
                    tx.getHash().toHexString());
            continue;
        }

        // 执行Transaction
        ProcessingResult result = txProcessor.processTransaction(tx);
        if (result.isSuccess()) {
            // 标记为已执行
            transactionStore.markTransactionExecuted(tx.getHash(), block.getHash());
        }
    }

    return ProcessingResult.success();
}
```

**工作量**: 2小时（接口设计）+ 3小时（实现）+ 2小时（测试）
**风险**: 中（需要数据库迁移）

---

#### Task 0.3: 添加Block Demotion时的Transaction回滚
**文件**: `src/main/java/io/xdag/core/DagChainImpl.java`

**当前代码** (`demoteBlockToOrphan()`, line 1994-2026):
```java
private synchronized void demoteBlockToOrphan(Block block) {
    // 更新BlockInfo为orphan
    BlockInfo updatedInfo = block.getInfo().toBuilder()
            .height(0)
            .build();
    dagStore.saveBlockInfo(updatedInfo);

    // ❌ 缺失：没有回滚Transaction执行
}
```

**修复代码**:
```java
private synchronized void demoteBlockToOrphan(Block block) {
    if (block == null || block.getInfo() == null) {
        log.warn("Attempted to demote null block or block without info");
        return;
    }

    long previousHeight = block.getInfo().getHeight();
    if (previousHeight == 0) {
        log.debug("Block {} is already an orphan, skipping demotion",
                block.getHash().toHexString());
        return;
    }

    // 【新增】回滚Transaction执行
    rollbackBlockTransactions(block);

    // 更新BlockInfo为orphan
    BlockInfo updatedInfo = block.getInfo().toBuilder()
            .height(0)
            .build();

    dagStore.saveBlockInfo(updatedInfo);
    Block updatedBlock = block.toBuilder().info(updatedInfo).build();
    dagStore.saveBlock(updatedBlock);

    log.info("Demoted block {} from height {} to orphan (transactions rolled back)",
            block.getHash().toHexString(), previousHeight);
}

/**
 * 回滚Block中的所有Transaction执行
 */
private void rollbackBlockTransactions(Block block) {
    if (dagKernel == null) return;

    DagAccountManager accountManager = dagKernel.getAccountManager();
    TransactionStore transactionStore = dagKernel.getTransactionStore();

    // 提取Block中的所有Transaction
    List<Transaction> transactions = new ArrayList<>();
    for (Link link : block.getLinks()) {
        if (link.isTransaction()) {
            Transaction tx = transactionStore.getTransaction(link.getTargetHash());
            if (tx != null) {
                transactions.add(tx);
            }
        }
    }

    if (transactions.isEmpty()) {
        return;
    }

    log.info("Rolling back {} transactions from demoted block {}",
            transactions.size(), block.getHash().toHexString().substring(0, 16));

    // 【重要】按执行顺序的逆序回滚
    for (int i = transactions.size() - 1; i >= 0; i--) {
        Transaction tx = transactions.get(i);

        // 检查是否已执行
        if (!transactionStore.isTransactionExecuted(tx.getHash())) {
            log.debug("Transaction {} was not executed, skipping rollback",
                    tx.getHash().toHexString().substring(0, 16));
            continue;
        }

        try {
            // 回滚账户状态
            // 1. 恢复发送方余额
            accountManager.addBalance(tx.getFrom(), tx.getAmount().add(tx.getFee()));

            // 2. 扣除接收方余额
            accountManager.subtractBalance(tx.getTo(), tx.getAmount());

            // 3. 恢复发送方nonce
            accountManager.decrementNonce(tx.getFrom());

            // 4. 取消执行标记
            transactionStore.unmarkTransactionExecuted(tx.getHash());

            log.debug("Rolled back transaction {} (from={}, amount={})",
                    tx.getHash().toHexString().substring(0, 16),
                    tx.getFrom().toHexString().substring(0, 8),
                    tx.getAmount().toDecimal(9, XUnit.XDAG));

        } catch (Exception e) {
            log.error("Failed to rollback transaction {}: {}",
                    tx.getHash().toHexString(), e.getMessage(), e);
            // 继续回滚其他Transaction
        }
    }

    log.info("Transaction rollback completed for block {}",
            block.getHash().toHexString().substring(0, 16));
}
```

**需要在DagAccountManager中添加的方法**:
```java
public interface DagAccountManager {
    // 现有方法...
    void addBalance(Bytes address, XAmount amount);
    void subtractBalance(Bytes address, XAmount amount);
    void incrementNonce(Bytes address);

    // 【新增】回滚支持
    void decrementNonce(Bytes address);
}
```

**测试验证**:
```java
@Test
public void testTransactionRollbackOnBlockDemotion() {
    // 1. 创建Transaction并执行
    Transaction tx = createTestTransaction(SENDER, RECEIVER, amount(10));
    Block mainBlock = createMainBlockWithTransaction(tx);
    dagChain.tryToConnect(mainBlock);

    // 验证执行成功
    assertTrue(transactionStore.isTransactionExecuted(tx.getHash()));
    assertEquals(INITIAL_BALANCE.subtract(amount(10)).subtract(FEE),
                 accountStore.getBalance(SENDER));
    assertEquals(amount(10), accountStore.getBalance(RECEIVER));

    // 2. Block被demote（epoch竞争输了）
    dagChain.demoteBlockToOrphan(mainBlock);

    // 3. 验证Transaction已回滚
    assertFalse(transactionStore.isTransactionExecuted(tx.getHash()));
    assertEquals(INITIAL_BALANCE, accountStore.getBalance(SENDER));
    assertEquals(ZERO, accountStore.getBalance(RECEIVER));
}
```

**工作量**: 3小时（实现）+ 3小时（测试）
**风险**: 高（涉及账户状态回滚，必须保证正确性）

---

### Phase 1: 核心功能实现（3-5天）- 实现P1功能

#### Task 1.1: 设计TransactionPool架构
**文件**: 新建 `src/main/java/io/xdag/core/TransactionPool.java`

**接口设计**:
```java
/**
 * TransactionPool - 待处理Transaction池
 *
 * 职责:
 * 1. 接收和存储待处理Transaction
 * 2. 按手续费排序提供Transaction选择
 * 3. 确保nonce连续性
 * 4. 清理过期Transaction
 */
public interface TransactionPool extends XdagLifecycle {

    /**
     * 添加Transaction到池
     * @param tx transaction to add
     * @return true if added successfully
     */
    boolean addTransaction(Transaction tx);

    /**
     * 选择Top N Transaction（按手续费排序）
     * @param maxCount maximum number of transactions
     * @return selected transactions in fee-descending order
     */
    List<Transaction> selectTransactions(int maxCount);

    /**
     * 移除已被Block引用的Transaction
     * @param block block containing transactions
     */
    void removeTransactionsByBlock(Block block);

    /**
     * 获取Pool中Transaction数量
     * @return transaction count
     */
    int size();

    /**
     * 获取指定账户的待处理Transaction
     * @param address account address
     * @return transactions from this account
     */
    List<Transaction> getTransactionsByAccount(Bytes address);

    /**
     * 检查Transaction是否在池中
     * @param txHash transaction hash
     * @return true if in pool
     */
    boolean contains(Bytes32 txHash);

    /**
     * 清理过期Transaction
     * @param maxAgeMillis maximum age in milliseconds
     * @return number of removed transactions
     */
    int cleanupExpired(long maxAgeMillis);

    /**
     * 获取Pool统计信息
     * @return pool statistics
     */
    TransactionPoolStats getStats();
}
```

**数据结构设计**:
```java
public class TransactionPoolImpl implements TransactionPool {

    // 1. 按手续费排序的全局队列
    private final PriorityQueue<PendingTransaction> feeQueue;

    // 2. 按账户地址分组的Transaction Map
    // Key: address, Value: nonce -> transaction
    private final ConcurrentHashMap<Bytes, TreeMap<Long, PendingTransaction>> txsByAccount;

    // 3. 快速查找的Hash索引
    // Key: tx hash, Value: pending transaction
    private final ConcurrentHashMap<Bytes32, PendingTransaction> txByHash;

    // 4. 配置参数
    private final int maxPoolSize;         // 最大容量（如10000）
    private final long maxTxAgeMillis;     // Transaction最大存活时间（如1小时）
    private final XAmount minFee;          // 最小手续费要求

    // 5. 统计信息
    private final AtomicLong totalAdded = new AtomicLong(0);
    private final AtomicLong totalRemoved = new AtomicLong(0);
    private final AtomicLong totalRejected = new AtomicLong(0);
}

/**
 * 待处理Transaction包装类
 */
@Value
class PendingTransaction implements Comparable<PendingTransaction> {
    Transaction transaction;
    XAmount fee;
    long addedTimestamp;

    @Override
    public int compareTo(PendingTransaction other) {
        // 手续费从高到低排序
        int feeCompare = other.fee.compareTo(this.fee);
        if (feeCompare != 0) return feeCompare;

        // 手续费相同，按添加时间（FIFO）
        return Long.compare(this.addedTimestamp, other.addedTimestamp);
    }
}
```

**工作量**: 4小时（设计）+ 6小时（实现）+ 4小时（单元测试）
**风险**: 中（并发控制、性能优化）

---

#### Task 1.2: 实现TransactionPool核心逻辑

**addTransaction() 实现**:
```java
@Override
public boolean addTransaction(Transaction tx) {
    // 1. 基础验证
    if (tx == null || !tx.isValid()) {
        totalRejected.incrementAndGet();
        return false;
    }

    // 2. 检查是否已在池中
    if (txByHash.containsKey(tx.getHash())) {
        log.debug("Transaction {} already in pool", tx.getHash().toHexString().substring(0, 16));
        return false;
    }

    // 3. 检查是否已被Block引用（已执行）
    if (transactionStore.isTransactionExecuted(tx.getHash())) {
        log.debug("Transaction {} already executed", tx.getHash().toHexString().substring(0, 16));
        totalRejected.incrementAndGet();
        return false;
    }

    // 4. 检查Pool容量
    if (txByHash.size() >= maxPoolSize) {
        // 尝试移除最低手续费的Transaction
        if (!evictLowestFeeTx(tx.getFee())) {
            log.warn("TransactionPool full and new tx fee too low");
            totalRejected.incrementAndGet();
            return false;
        }
    }

    // 5. 验证nonce连续性
    if (!validateNonceSequence(tx)) {
        log.debug("Transaction {} has nonce gap", tx.getHash().toHexString().substring(0, 16));
        totalRejected.incrementAndGet();
        return false;
    }

    // 6. 检查手续费
    if (tx.getFee().compareTo(minFee) < 0) {
        log.debug("Transaction {} fee too low: {} < {}",
                tx.getHash().toHexString().substring(0, 16),
                tx.getFee().toDecimal(9, XUnit.XDAG),
                minFee.toDecimal(9, XUnit.XDAG));
        totalRejected.incrementAndGet();
        return false;
    }

    // 7. 添加到Pool
    PendingTransaction pendingTx = new PendingTransaction(
            tx,
            tx.getFee(),
            System.currentTimeMillis()
    );

    txByHash.put(tx.getHash(), pendingTx);
    feeQueue.add(pendingTx);

    // 添加到账户Map
    txsByAccount.computeIfAbsent(tx.getFrom(), k -> new TreeMap<>())
                .put(tx.getNonce(), pendingTx);

    totalAdded.incrementAndGet();

    log.info("Added transaction {} to pool (from={}, fee={}, pool_size={})",
            tx.getHash().toHexString().substring(0, 16),
            tx.getFrom().toHexString().substring(0, 8),
            tx.getFee().toDecimal(9, XUnit.XDAG),
            txByHash.size());

    return true;
}

/**
 * 验证nonce连续性
 */
private boolean validateNonceSequence(Transaction tx) {
    // 获取账户当前nonce
    UInt64 currentNonce = accountStore.getNonce(tx.getFrom());

    // 获取账户在Pool中的Transaction
    TreeMap<Long, PendingTransaction> accountTxs = txsByAccount.get(tx.getFrom());

    if (accountTxs == null || accountTxs.isEmpty()) {
        // Pool中没有该账户的Transaction，检查是否是下一个nonce
        return tx.getNonce() == currentNonce.toLong() + 1;
    }

    // Pool中有该账户的Transaction，检查是否连续
    Long maxNonce = accountTxs.lastKey();
    return tx.getNonce() == maxNonce + 1;
}
```

**selectTransactions() 实现**:
```java
@Override
public List<Transaction> selectTransactions(int maxCount) {
    List<Transaction> selected = new ArrayList<>();
    Set<Bytes> includedAccounts = new HashSet<>();
    Map<Bytes, Long> accountNextNonce = new HashMap<>();

    // 从手续费队列中选择
    PriorityQueue<PendingTransaction> tempQueue = new PriorityQueue<>(feeQueue);

    while (!tempQueue.isEmpty() && selected.size() < maxCount) {
        PendingTransaction pendingTx = tempQueue.poll();
        Transaction tx = pendingTx.getTransaction();

        // 检查nonce连续性
        if (!canIncludeTransaction(tx, accountNextNonce)) {
            continue;
        }

        // 添加到选择列表
        selected.add(tx);

        // 更新账户下一个期望nonce
        accountNextNonce.put(tx.getFrom(), tx.getNonce() + 1);
    }

    log.info("Selected {} transactions from pool (total: {})",
            selected.size(), txByHash.size());

    return selected;
}

/**
 * 检查Transaction是否可以包含（nonce连续性）
 */
private boolean canIncludeTransaction(Transaction tx, Map<Bytes, Long> accountNextNonce) {
    Bytes from = tx.getFrom();

    if (accountNextNonce.containsKey(from)) {
        // 该账户已有Transaction在当前选择中
        long expectedNonce = accountNextNonce.get(from);
        return tx.getNonce() == expectedNonce;
    } else {
        // 该账户还没有Transaction被选择
        // 检查是否是账户的下一个nonce
        UInt64 currentNonce = accountStore.getNonce(from);
        return tx.getNonce() == currentNonce.toLong() + 1;
    }
}
```

**工作量**: 8小时（实现）+ 6小时（测试）
**风险**: 中（并发控制复杂）

---

#### Task 1.3: 集成TransactionPool到DagKernel

**文件**: `src/main/java/io/xdag/DagKernel.java`

**修改**:
```java
public class DagKernel implements XdagLifecycle {

    // 现有组件...
    private DagChain dagChain;
    private TransactionStore transactionStore;
    private DagTransactionProcessor transactionProcessor;

    // 【新增】TransactionPool
    private TransactionPool transactionPool;

    public void initialize() {
        // ... 现有初始化代码 ...

        // 【新增】初始化TransactionPool
        this.transactionPool = new TransactionPoolImpl(
                config.getTransactionPoolSpec(),
                transactionStore,
                accountStore
        );

        log.info("TransactionPool initialized: maxSize={}, minFee={}",
                config.getTransactionPoolSpec().getMaxPoolSize(),
                config.getTransactionPoolSpec().getMinFee());
    }

    @Override
    public void start() {
        // ... 现有启动代码 ...

        // 【新增】启动TransactionPool
        transactionPool.start();
    }

    @Override
    public void stop() {
        // 【新增】停止TransactionPool
        if (transactionPool != null) {
            transactionPool.stop();
        }

        // ... 现有停止代码 ...
    }

    // Getter
    public TransactionPool getTransactionPool() {
        return transactionPool;
    }
}
```

**配置** (`src/main/java/io/xdag/config/spec/TransactionPoolSpec.java`):
```java
@Data
@Builder
public class TransactionPoolSpec {

    /**
     * TransactionPool最大容量
     * 默认: 10000
     */
    @Builder.Default
    private int maxPoolSize = 10000;

    /**
     * Transaction最大存活时间（毫秒）
     * 默认: 1小时
     */
    @Builder.Default
    private long maxTxAgeMillis = 3600_000;

    /**
     * 最小手续费要求（nano XDAG）
     * 默认: 100 milli-XDAG = 100,000,000 nano XDAG
     */
    @Builder.Default
    private long minFeeNano = 100_000_000;

    /**
     * 清理过期Transaction的间隔（毫秒）
     * 默认: 5分钟
     */
    @Builder.Default
    private long cleanupIntervalMillis = 300_000;

    public XAmount getMinFee() {
        return XAmount.of(minFeeNano, XUnit.NANO_XDAG);
    }
}
```

**工作量**: 2小时（集成）+ 1小时（配置）+ 2小时（测试）
**风险**: 低

---

#### Task 1.4: 修改Commands.transfer()使用TransactionPool

**文件**: `src/main/java/io/xdag/cli/Commands.java`

**当前代码** (line 1088-1230):
```java
public String transfer(double sendAmount, String toAddress, String remark, double feeMilliXdag) {
    // ... 创建和签名Transaction ...

    // 保存Transaction到TransactionStore
    dagKernel.getTransactionStore().saveTransaction(signedTx);

    // ❌ 错误：立即创建Block
    List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));
    Block block = Block.builder()
            .header(header)
            .links(links)
            .build();

    // 尝试导入Block
    DagImportResult result = dagKernel.getDagChain().tryToConnect(block);

    if (result.isMainBlock()) {
        // ... 返回成功 ...
    } else {
        return "Transaction failed!";
    }
}
```

**修复代码**:
```java
public String transfer(double sendAmount, String toAddress, String remark, double feeMilliXdag) {
    try {
        // 1. 转换金额
        XAmount amount = XAmount.of(BigDecimal.valueOf(sendAmount), XUnit.XDAG);
        XAmount fee = XAmount.of(BigDecimal.valueOf(feeMilliXdag), XUnit.MILLI_XDAG);
        XAmount totalRequired = amount.add(fee);

        // 2. 解析收款地址
        Bytes to;
        if (checkAddress(toAddress)) {
            to = AddressUtils.fromBase58Address(toAddress);
        } else {
            return "Invalid recipient address format. Please use Base58 address format.";
        }

        // 3. 查找有足够余额的账户
        ECKeyPair fromAccount = null;
        Bytes fromAddress = null;
        long currentNonce = 0;

        for (ECKeyPair account : dagKernel.getWallet().getAccounts()) {
            Bytes addr = Bytes.wrap(toBytesAddress(account));
            XAmount balance = getAccountBalance(addr);

            if (balance.compareTo(totalRequired) >= 0) {
                fromAccount = account;
                fromAddress = Bytes.wrap(toBytesAddress(account));
                currentNonce = getAccountNonce(addr) + 1;
                break;
            }
        }

        if (fromAccount == null) {
            return "Balance not enough. Need " +
                   totalRequired.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG";
        }

        // 4. 编码remark
        Bytes remarkData = Bytes.EMPTY;
        if (remark != null && !remark.isEmpty()) {
            remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));
        }

        // 5. 创建Transaction
        Transaction tx = Transaction.builder()
                .from(fromAddress)
                .to(to)
                .amount(amount)
                .nonce(currentNonce)
                .fee(fee)
                .data(remarkData)
                .build();

        // 6. 签名Transaction
        Transaction signedTx = tx.sign(fromAccount);

        // 7. 验证Transaction
        if (!signedTx.isValid()) {
            return "Transaction validation failed.";
        }

        if (!signedTx.verifySignature()) {
            return "Transaction signature verification failed.";
        }

        // 8. 保存Transaction到TransactionStore
        dagKernel.getTransactionStore().saveTransaction(signedTx);

        // 9. 【修改】添加到TransactionPool（不再立即创建Block）
        boolean added = dagKernel.getTransactionPool().addTransaction(signedTx);

        if (!added) {
            return "Failed to add transaction to pool. " +
                   "Please check fee and nonce.";
        }

        // 10. 返回成功信息
        StringBuilder successMsg = new StringBuilder();
        successMsg.append("Transaction submitted successfully!\n");
        successMsg.append(String.format("  Transaction hash: %s\n",
                signedTx.getHash().toHexString().substring(0, 16) + "..."));
        successMsg.append(String.format("  From: %s\n",
                fromAddress.toHexString().substring(0, 16) + "..."));
        successMsg.append(String.format("  To: %s\n",
                to.toHexString().substring(0, 16) + "..."));
        successMsg.append(String.format("  Amount: %s XDAG\n",
                amount.toDecimal(9, XUnit.XDAG).toPlainString()));
        successMsg.append(String.format("  Fee: %s XDAG\n",
                fee.toDecimal(9, XUnit.XDAG).toPlainString()));
        successMsg.append(String.format("  Nonce: %d\n", currentNonce));
        successMsg.append("  Status: Pending (waiting for block inclusion)\n");

        if (remark != null && !remark.isEmpty()) {
            successMsg.append(String.format("  Remark: %s\n", remark));
        }

        return successMsg.toString();

    } catch (Exception e) {
        log.error("transfer failed: {}", e.getMessage(), e);
        return "Transaction failed: " + e.getMessage();
    }
}
```

**新增查询命令** (查看Transaction状态):
```java
/**
 * 查询Transaction状态
 */
public String transactionStatus(String txHashStr) {
    Bytes32 txHash = BasicUtils.getHash(txHashStr);
    if (txHash == null) {
        return "Invalid transaction hash format";
    }

    // 1. 查询Transaction
    Transaction tx = dagKernel.getTransactionStore().getTransaction(txHash);
    if (tx == null) {
        return "Transaction not found";
    }

    StringBuilder output = new StringBuilder();
    output.append("═══════════════════════════════════════════════════\n");
    output.append("Transaction Status\n");
    output.append("═══════════════════════════════════════════════════\n");
    output.append(String.format("Hash:   %s\n", txHash.toHexString()));
    output.append(String.format("From:   %s\n", tx.getFrom().toHexString().substring(0, 16) + "..."));
    output.append(String.format("To:     %s\n", tx.getTo().toHexString().substring(0, 16) + "..."));
    output.append(String.format("Amount: %s XDAG\n",
            tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString()));
    output.append(String.format("Fee:    %s XDAG\n",
            tx.getFee().toDecimal(9, XUnit.XDAG).toPlainString()));
    output.append(String.format("Nonce:  %d\n", tx.getNonce()));
    output.append("\n");

    // 2. 检查状态
    if (dagKernel.getTransactionStore().isTransactionExecuted(txHash)) {
        // 已执行
        Bytes32 blockHash = dagKernel.getTransactionStore().getExecutingBlock(txHash);
        Block block = dagKernel.getDagChain().getBlockByHash(blockHash, false);

        output.append("Status: EXECUTED\n");
        output.append(String.format("Block:  %s\n", blockHash.toHexString()));
        if (block != null && block.getInfo() != null) {
            output.append(String.format("Height: %d\n", block.getInfo().getHeight()));
            long timestamp = XdagTime.xdagTimestampToMs(block.getTimestamp());
            output.append(String.format("Time:   %s\n",
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").format(timestamp)));
        }
    } else if (dagKernel.getTransactionPool().contains(txHash)) {
        // 在Pool中等待
        output.append("Status: PENDING (in transaction pool)\n");
        output.append("Waiting for block inclusion...\n");
    } else {
        // 被Block引用但未执行
        Bytes32 blockHash = dagKernel.getTransactionStore().getBlockByTransaction(txHash);
        if (blockHash != null) {
            Block block = dagKernel.getDagChain().getBlockByHash(blockHash, false);
            if (block != null && block.getInfo() != null && block.getInfo().getHeight() == 0) {
                output.append("Status: INCLUDED (in orphan block)\n");
                output.append(String.format("Block:  %s (orphan)\n", blockHash.toHexString()));
                output.append("Waiting for block to become main block...\n");
            } else {
                output.append("Status: UNKNOWN\n");
            }
        } else {
            output.append("Status: UNKNOWN\n");
        }
    }

    return output.toString();
}
```

**工作量**: 3小时（修改）+ 2小时（新增命令）+ 3小时（测试）
**风险**: 低

---

#### Task 1.5: 修改collectCandidateLinks()选择Transaction

**文件**: `src/main/java/io/xdag/core/DagChainImpl.java`

**当前代码** (line 773-825):
```java
private List<Link> collectCandidateLinks() {
    List<Link> links = new ArrayList<>();

    // 1. 添加主链Block引用
    if (currentMainHeight > 0) {
        Block prevMainBlock = dagStore.getMainBlockByHeight(currentMainHeight, false);
        links.add(Link.toBlock(prevMainBlock.getHash()));
    }

    // 2. 添加孤块引用
    List<Bytes32> orphanHashes = orphanBlockStore.getOrphan(maxOrphans, sendTime);
    for (Bytes32 orphanHash : orphanHashes) {
        links.add(Link.toBlock(orphanHash));
    }

    // ❌ 缺失：没有添加Transaction引用

    return links;
}
```

**修复代码**:
```java
private List<Link> collectCandidateLinks() {
    List<Link> links = new ArrayList<>();
    long timestamp = XdagTime.getCurrentTimestamp();
    long sendEpoch = timestamp / 64;

    long currentMainHeight = chainStats.getMainBlockCount();
    log.debug("Collecting candidate links: mainBlockCount={}, sendEpoch={}",
            currentMainHeight, sendEpoch);

    // 1. 添加主链Block引用（with epoch boundary check）
    if (currentMainHeight > 0) {
        Block prevMainBlock = dagStore.getMainBlockByHeight(currentMainHeight, false);

        if (prevMainBlock != null) {
            long prevEpoch = prevMainBlock.getEpoch();

            if (prevEpoch < sendEpoch) {
                links.add(Link.toBlock(prevMainBlock.getHash()));
                log.debug("Added main block reference: {} (epoch {}, different from send epoch {})",
                        prevMainBlock.getHash().toHexString().substring(0, 16),
                        prevEpoch, sendEpoch);
            } else {
                Block preEpochMainBlock = findPreviousEpochMainBlock(prevMainBlock, sendEpoch);
                if (preEpochMainBlock != null) {
                    links.add(Link.toBlock(preEpochMainBlock.getHash()));
                    log.debug("Added pre-epoch main block reference: {} (epoch {})",
                            preEpochMainBlock.getHash().toHexString().substring(0, 16),
                            preEpochMainBlock.getEpoch());
                }
            }
        }
    }

    // 2. 添加孤块引用（limit to 5 to save space for transactions）
    int maxOrphans = Math.min(5, Block.MAX_BLOCK_LINKS - links.size() - 2);
    if (maxOrphans > 0) {
        long[] sendTime = new long[2];
        sendTime[0] = timestamp;

        List<Bytes32> orphanHashes = orphanBlockStore.getOrphan(maxOrphans, sendTime);
        if (orphanHashes != null && !orphanHashes.isEmpty()) {
            for (Bytes32 orphanHash : orphanHashes) {
                links.add(Link.toBlock(orphanHash));
            }
            log.debug("Added {} orphan block references", orphanHashes.size());
        }
    }

    // 3. 【新增】添加Transaction引用
    int maxTxs = Block.MAX_BLOCK_LINKS - links.size();
    if (maxTxs > 0 && dagKernel != null) {
        TransactionPool txPool = dagKernel.getTransactionPool();
        if (txPool != null) {
            List<Transaction> selectedTxs = txPool.selectTransactions(maxTxs);

            for (Transaction tx : selectedTxs) {
                links.add(Link.toTransaction(tx.getHash()));
            }

            if (!selectedTxs.isEmpty()) {
                log.info("Added {} transaction references to candidate block (total_fee={})",
                        selectedTxs.size(),
                        calculateTotalFee(selectedTxs));
            }
        }
    }

    log.info("Collected {} links for candidate block (epoch {}): {} blocks, {} transactions",
            links.size(), sendEpoch,
            links.stream().filter(Link::isBlock).count(),
            links.stream().filter(Link::isTransaction).count());

    return links;
}

/**
 * 计算Transaction列表的总手续费
 */
private XAmount calculateTotalFee(List<Transaction> transactions) {
    XAmount totalFee = XAmount.ZERO;
    for (Transaction tx : transactions) {
        totalFee = totalFee.add(tx.getFee());
    }
    return totalFee;
}
```

**工作量**: 2小时（修改）+ 2小时（测试）
**风险**: 低

---

#### Task 1.6: Transaction执行后从Pool移除

**文件**: `src/main/java/io/xdag/core/DagChainImpl.java`

**修改** (在`tryToConnect()`中):
```java
public synchronized DagImportResult tryToConnect(Block block) {
    // ... 前面的验证和判断代码 ...

    // 【修改】只有主块才执行Transaction
    if (isBestChain && dagKernel != null) {
        DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
        if (blockProcessor != null) {
            DagBlockProcessor.ProcessingResult processResult =
                    blockProcessor.processBlock(blockWithInfo);

            if (processResult.isSuccess()) {
                log.info("Block {} transactions executed successfully",
                        block.getHash().toHexString());

                // 【新增】从TransactionPool移除已执行的Transaction
                TransactionPool txPool = dagKernel.getTransactionPool();
                if (txPool != null) {
                    txPool.removeTransactionsByBlock(blockWithInfo);
                }
            } else {
                log.error("Block {} transaction processing failed: {}",
                        block.getHash().toHexString(), processResult.getError());
            }
        }
    }

    // ... 后续代码 ...
}
```

**工作量**: 30分钟（修改）+ 1小时（测试）
**风险**: 低

---

### Phase 2: Transaction排序与EVM准备（2-3天）- 实现P2功能

#### Task 2.1: 实现Transaction确定性排序

**目的**: 确保所有节点对Block中Transaction的执行顺序达成一致

**方案设计**:

```java
/**
 * Transaction排序策略
 *
 * 规则:
 * 1. 按手续费从高到低排序（鼓励矿工选择高手续费Transaction）
 * 2. 手续费相同时，按nonce从小到大（确保账户内顺序）
 * 3. 不同账户手续费相同时，按hash排序（确保确定性）
 */
public class TransactionComparator implements Comparator<Transaction> {

    @Override
    public int compare(Transaction tx1, Transaction tx2) {
        // 1. 首先按手续费从高到低
        int feeCompare = tx2.getFee().compareTo(tx1.getFee());
        if (feeCompare != 0) {
            return feeCompare;
        }

        // 2. 手续费相同，检查是否同一账户
        if (tx1.getFrom().equals(tx2.getFrom())) {
            // 同一账户，按nonce从小到大
            return Long.compare(tx1.getNonce(), tx2.getNonce());
        }

        // 3. 不同账户，手续费相同，按hash排序（确保确定性）
        return tx1.getHash().compareTo(tx2.getHash());
    }
}
```

**在selectTransactions()中应用**:
```java
@Override
public List<Transaction> selectTransactions(int maxCount) {
    List<Transaction> selected = new ArrayList<>();
    // ... 选择逻辑 ...

    // 【重要】最终排序确保确定性
    selected.sort(new TransactionComparator());

    return selected;
}
```

**在processBlockTransactions()中验证**:
```java
public ProcessingResult processBlockTransactions(Block block, List<Transaction> transactions) {
    // 【新增】验证Transaction顺序（防止恶意矿工乱序）
    if (!verifyTransactionOrder(transactions)) {
        log.error("Block {} has invalid transaction order",
                block.getHash().toHexString());
        return ProcessingResult.error("Invalid transaction order");
    }

    // 按顺序执行
    for (Transaction tx : transactions) {
        ProcessingResult result = processTransaction(tx);
        if (!result.isSuccess()) {
            return ProcessingResult.error(
                String.format("Transaction %s failed: %s",
                        tx.getHash().toHexString(), result.getError())
            );
        }
    }

    return ProcessingResult.success();
}

/**
 * 验证Transaction顺序是否符合排序规则
 */
private boolean verifyTransactionOrder(List<Transaction> transactions) {
    if (transactions.size() <= 1) {
        return true;
    }

    TransactionComparator comparator = new TransactionComparator();

    for (int i = 0; i < transactions.size() - 1; i++) {
        Transaction tx1 = transactions.get(i);
        Transaction tx2 = transactions.get(i + 1);

        // tx1应该 <= tx2（按排序规则）
        if (comparator.compare(tx1, tx2) > 0) {
            log.warn("Transaction order violation: tx1={} (fee={}) should not be before tx2={} (fee={})",
                    tx1.getHash().toHexString().substring(0, 16),
                    tx1.getFee().toDecimal(9, XUnit.XDAG),
                    tx2.getHash().toHexString().substring(0, 16),
                    tx2.getFee().toDecimal(9, XUnit.XDAG));
            return false;
        }
    }

    return true;
}
```

**工作量**: 4小时（实现）+ 4小时（测试）
**风险**: 中（需要确保所有节点行为一致）

---

#### Task 2.2: 添加Transaction验证完整性

**增强签名验证**:
```java
// DagTransactionProcessor.java

private boolean validateSignature(Transaction tx) {
    try {
        // 1. 基础验证
        if (tx.getSignature() == null) {
            log.warn("Transaction {} has no signature", tx.getHash().toHexString());
            return false;
        }

        // 2. 验证签名格式
        if (!tx.hasValidSignatureFormat()) {
            log.warn("Transaction {} has invalid signature format",
                    tx.getHash().toHexString());
            return false;
        }

        // 3. 验证签名正确性
        if (!tx.verifySignature()) {
            log.warn("Transaction {} signature verification failed",
                    tx.getHash().toHexString());
            return false;
        }

        // 4. 【新增】验证签名者就是from地址
        Bytes recoveredAddress = tx.recoverFromAddress();
        if (recoveredAddress == null || !recoveredAddress.equals(tx.getFrom())) {
            log.warn("Transaction {} signature does not match from address",
                    tx.getHash().toHexString());
            return false;
        }

        return true;

    } catch (Exception e) {
        log.error("Error validating transaction {} signature: {}",
                tx.getHash().toHexString(), e.getMessage());
        return false;
    }
}
```

**增强账户状态验证**:
```java
private ValidationResult validateAccountState(Transaction tx) {
    // 1. 检查发送方账户是否存在
    if (!accountManager.accountExists(tx.getFrom())) {
        return ValidationResult.error("Sender account does not exist");
    }

    // 2. 检查余额
    UInt256 balance = accountManager.getBalance(tx.getFrom());
    XAmount required = tx.getAmount().add(tx.getFee());

    if (balance.compareTo(required.toUInt256()) < 0) {
        return ValidationResult.error(String.format(
                "Insufficient balance: have %s, need %s",
                XAmount.ofXAmount(balance.toLong()).toDecimal(9, XUnit.XDAG),
                required.toDecimal(9, XUnit.XDAG)
        ));
    }

    // 3. 【增强】检查nonce
    UInt64 accountNonce = accountManager.getNonce(tx.getFrom());
    long expectedNonce = accountNonce.toLong() + 1;

    if (tx.getNonce() != expectedNonce) {
        return ValidationResult.error(String.format(
                "Invalid nonce: have %d, expected %d",
                tx.getNonce(), expectedNonce
        ));
    }

    // 4. 【新增】检查amount和fee不为负
    if (tx.getAmount().isNegative()) {
        return ValidationResult.error("Amount cannot be negative");
    }

    if (tx.getFee().isNegative()) {
        return ValidationResult.error("Fee cannot be negative");
    }

    // 5. 【新增】检查data大小
    if (tx.getData() != null && tx.getData().size() > Transaction.MAX_DATA_SIZE) {
        return ValidationResult.error(String.format(
                "Data too large: %d bytes (max: %d)",
                tx.getData().size(), Transaction.MAX_DATA_SIZE
        ));
    }

    // 6. 【新增】检查接收方地址有效性
    if (tx.getTo() == null || tx.getTo().size() != 20) {
        return ValidationResult.error("Invalid recipient address");
    }

    return ValidationResult.success();
}
```

**工作量**: 3小时（实现）+ 3小时（测试）
**风险**: 低

---

### Phase 3: 优化与监控（1-2天）- 实现P3功能

#### Task 3.1: TransactionPool性能优化

**并发优化**:
```java
public class TransactionPoolImpl implements TransactionPool {

    // 使用ReadWriteLock提高并发性能
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    @Override
    public boolean addTransaction(Transaction tx) {
        lock.writeLock().lock();
        try {
            // ... 添加逻辑 ...
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<Transaction> selectTransactions(int maxCount) {
        lock.readLock().lock();
        try {
            // ... 选择逻辑 ...
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

**定期清理过期Transaction**:
```java
// 启动定期清理任务
private ScheduledExecutorService cleanupExecutor;

@Override
public void start() {
    cleanupExecutor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder()
                    .setNameFormat("tx-pool-cleanup-%d")
                    .setDaemon(true)
                    .build()
    );

    // 每5分钟清理一次过期Transaction
    cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpired,
            config.getCleanupIntervalMillis(),
            config.getCleanupIntervalMillis(),
            TimeUnit.MILLISECONDS
    );

    log.info("TransactionPool cleanup task started (interval: {} ms)",
            config.getCleanupIntervalMillis());
}

@Override
public int cleanupExpired(long maxAgeMillis) {
    lock.writeLock().lock();
    try {
        long now = System.currentTimeMillis();
        int removedCount = 0;

        Iterator<Map.Entry<Bytes32, PendingTransaction>> iter = txByHash.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Bytes32, PendingTransaction> entry = iter.next();
            PendingTransaction pendingTx = entry.getValue();

            if (now - pendingTx.getAddedTimestamp() > maxAgeMillis) {
                // 从所有数据结构中移除
                iter.remove();
                feeQueue.remove(pendingTx);

                TreeMap<Long, PendingTransaction> accountTxs =
                        txsByAccount.get(pendingTx.getTransaction().getFrom());
                if (accountTxs != null) {
                    accountTxs.remove(pendingTx.getTransaction().getNonce());
                    if (accountTxs.isEmpty()) {
                        txsByAccount.remove(pendingTx.getTransaction().getFrom());
                    }
                }

                removedCount++;
                totalRemoved.incrementAndGet();
            }
        }

        if (removedCount > 0) {
            log.info("Cleaned up {} expired transactions from pool (age > {} ms)",
                    removedCount, maxAgeMillis);
        }

        return removedCount;

    } finally {
        lock.writeLock().unlock();
    }
}
```

**工作量**: 3小时（实现）+ 2小时（测试）
**风险**: 低

---

#### Task 3.2: 添加TransactionPool监控和统计

**统计信息类**:
```java
@Value
@Builder
public class TransactionPoolStats {
    long totalTransactions;       // Pool中当前Transaction数量
    long totalAdded;             // 累计添加的Transaction数量
    long totalRemoved;           // 累计移除的Transaction数量
    long totalRejected;          // 累计拒绝的Transaction数量

    int uniqueAccounts;          // 涉及的账户数量
    XAmount totalFees;           // Pool中所有Transaction的总手续费

    XAmount minFee;              // 最低手续费
    XAmount maxFee;              // 最高手续费
    XAmount avgFee;              // 平均手续费

    long oldestTxAge;            // 最旧Transaction的年龄（毫秒）
    int maxPoolSize;             // Pool容量上限

    Map<String, Long> accountTxCounts;  // 每个账户的Transaction数量（Top 10）
}
```

**实现**:
```java
@Override
public TransactionPoolStats getStats() {
    lock.readLock().lock();
    try {
        XAmount totalFees = XAmount.ZERO;
        XAmount minFee = null;
        XAmount maxFee = null;
        long oldestTimestamp = Long.MAX_VALUE;

        for (PendingTransaction pendingTx : txByHash.values()) {
            XAmount fee = pendingTx.getFee();
            totalFees = totalFees.add(fee);

            if (minFee == null || fee.compareTo(minFee) < 0) {
                minFee = fee;
            }
            if (maxFee == null || fee.compareTo(maxFee) > 0) {
                maxFee = fee;
            }

            if (pendingTx.getAddedTimestamp() < oldestTimestamp) {
                oldestTimestamp = pendingTx.getAddedTimestamp();
            }
        }

        XAmount avgFee = txByHash.isEmpty() ?
                XAmount.ZERO :
                totalFees.divide(txByHash.size());

        long oldestAge = txByHash.isEmpty() ?
                0 :
                System.currentTimeMillis() - oldestTimestamp;

        // 统计Top 10账户
        Map<String, Long> topAccounts = txsByAccount.entrySet().stream()
                .sorted((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()))
                .limit(10)
                .collect(Collectors.toMap(
                        e -> e.getKey().toHexString().substring(0, 16) + "...",
                        e -> (long) e.getValue().size(),
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        return TransactionPoolStats.builder()
                .totalTransactions(txByHash.size())
                .totalAdded(totalAdded.get())
                .totalRemoved(totalRemoved.get())
                .totalRejected(totalRejected.get())
                .uniqueAccounts(txsByAccount.size())
                .totalFees(totalFees)
                .minFee(minFee != null ? minFee : XAmount.ZERO)
                .maxFee(maxFee != null ? maxFee : XAmount.ZERO)
                .avgFee(avgFee)
                .oldestTxAge(oldestAge)
                .maxPoolSize(config.getMaxPoolSize())
                .accountTxCounts(topAccounts)
                .build();

    } finally {
        lock.readLock().unlock();
    }
}
```

**添加CLI命令**:
```java
// Shell.java
commandExecute.put("txpool", new CommandMethods(this::processTxPool, this::defaultCompleter));

// Commands.java
public String txpool() {
    TransactionPoolStats stats = dagKernel.getTransactionPool().getStats();

    StringBuilder output = new StringBuilder();
    output.append("═══════════════════════════════════════════════════\n");
    output.append("Transaction Pool Statistics\n");
    output.append("═══════════════════════════════════════════════════\n");
    output.append(String.format("Total Transactions:  %,d / %,d (%.1f%% full)\n",
            stats.getTotalTransactions(),
            stats.getMaxPoolSize(),
            100.0 * stats.getTotalTransactions() / stats.getMaxPoolSize()));
    output.append(String.format("Unique Accounts:     %,d\n", stats.getUniqueAccounts()));
    output.append("\n");

    output.append("Lifetime Statistics:\n");
    output.append(String.format("  Added:             %,d\n", stats.getTotalAdded()));
    output.append(String.format("  Removed:           %,d\n", stats.getTotalRemoved()));
    output.append(String.format("  Rejected:          %,d\n", stats.getTotalRejected()));
    output.append("\n");

    output.append("Fee Statistics:\n");
    output.append(String.format("  Total Fees:        %s XDAG\n",
            stats.getTotalFees().toDecimal(9, XUnit.XDAG)));
    output.append(String.format("  Min Fee:           %s XDAG\n",
            stats.getMinFee().toDecimal(9, XUnit.XDAG)));
    output.append(String.format("  Max Fee:           %s XDAG\n",
            stats.getMaxFee().toDecimal(9, XUnit.XDAG)));
    output.append(String.format("  Avg Fee:           %s XDAG\n",
            stats.getAvgFee().toDecimal(9, XUnit.XDAG)));
    output.append("\n");

    output.append(String.format("Oldest Transaction:  %d seconds ago\n",
            stats.getOldestTxAge() / 1000));
    output.append("\n");

    if (!stats.getAccountTxCounts().isEmpty()) {
        output.append("Top Accounts (by transaction count):\n");
        int rank = 1;
        for (Map.Entry<String, Long> entry : stats.getAccountTxCounts().entrySet()) {
            output.append(String.format("  %2d. %s: %,d txs\n",
                    rank++, entry.getKey(), entry.getValue()));
        }
    }

    return output.toString();
}
```

**工作量**: 4小时（实现）+ 2小时（CLI）+ 2小时（测试）
**风险**: 低

---

## 三、测试计划

### 3.1 单元测试

**TransactionPool测试**:
```java
@Test
public void testAddTransaction_Success()
@Test
public void testAddTransaction_DuplicateRejected()
@Test
public void testAddTransaction_AlreadyExecutedRejected()
@Test
public void testAddTransaction_PoolFullEviction()
@Test
public void testAddTransaction_NonceGapRejected()
@Test
public void testSelectTransactions_FeeOrdering()
@Test
public void testSelectTransactions_NonceSequence()
@Test
public void testCleanupExpired()
@Test
public void testRemoveTransactionsByBlock()
```

**Transaction执行测试**:
```java
@Test
public void testTransactionExecutionOnlyForMainBlock()
@Test
public void testTransactionNotExecutedForOrphanBlock()
@Test
public void testTransactionRollbackOnBlockDemotion()
@Test
public void testDuplicateTransactionExecutionPrevented()
```

**Transaction排序测试**:
```java
@Test
public void testTransactionOrdering_ByFee()
@Test
public void testTransactionOrdering_SameAccountByNonce()
@Test
public void testTransactionOrdering_DifferentAccountByHash()
@Test
public void testTransactionOrderValidation()
```

### 3.2 集成测试

```java
@Test
public void testEndToEndTransactionFlow() {
    // 1. 创建Transaction
    Transaction tx = createTransaction();

    // 2. 添加到Pool
    assertTrue(txPool.addTransaction(tx));

    // 3. 矿工创建候选Block（包含Transaction）
    Block candidate = dagChain.createCandidateBlock();
    assertTrue(candidate.getLinks().stream().anyMatch(Link::isTransaction));

    // 4. Block被接受为主块
    DagImportResult result = dagChain.tryToConnect(candidate);
    assertTrue(result.isMainBlock());

    // 5. Transaction已执行
    assertTrue(transactionStore.isTransactionExecuted(tx.getHash()));

    // 6. Transaction从Pool移除
    assertFalse(txPool.contains(tx.getHash()));

    // 7. 账户状态已更新
    assertEquals(expectedBalance, accountStore.getBalance(tx.getFrom()));
}

@Test
public void testTransactionRollbackOnChainReorg() {
    // 测试链重组时Transaction回滚
}
```

### 3.3 性能测试

```java
@Test
public void testTransactionPoolThroughput() {
    // 测试TransactionPool吞吐量
    // 目标: 10000 tx/s
}

@Test
public void testTransactionSelectionPerformance() {
    // 测试Transaction选择性能
    // 目标: < 10ms for 10000 transactions
}
```

---

## 四、风险管理

### 4.1 数据迁移风险

**风险**: 现有数据库schema变化

**缓解措施**:
1. 提供数据库迁移脚本
2. 保留向后兼容性
3. 提供回滚方案

### 4.2 共识一致性风险

**风险**: Transaction排序不一致导致分叉

**缓解措施**:
1. 严格的排序规则
2. 排序验证机制
3. 充分的单元测试

### 4.3 性能风险

**风险**: TransactionPool成为性能瓶颈

**缓解措施**:
1. 使用高效数据结构
2. 并发控制优化
3. 性能测试和profiling

---

## 五、发布计划

### 5.1 版本规划

**v5.2.0-alpha (Phase 0完成)**
- Transaction执行时机修复
- Transaction状态追踪
- Block demotion回滚

**v5.2.0-beta (Phase 1完成)**
- TransactionPool实现
- Transaction选择机制
- Commands集成

**v5.2.0-rc (Phase 2完成)**
- Transaction排序
- 增强验证

**v5.2.0 (Phase 3完成)**
- 性能优化
- 监控统计

### 5.2 文档更新

1. **TRANSACTION_POOL_DESIGN.md** - TransactionPool详细设计文档
2. **TRANSACTION_ORDERING_SPEC.md** - Transaction排序规范
3. **API_CHANGES.md** - API变更说明
4. **MIGRATION_GUIDE.md** - 升级迁移指南

---

## 六、总工作量估算

| Phase | 任务 | 工作量 | 风险 |
|-------|------|--------|------|
| **Phase 0** | 紧急修复 | **1-2天** | 中-高 |
| Task 0.1 | Transaction执行时机修正 | 3h | 低 |
| Task 0.2 | Transaction状态追踪 | 7h | 中 |
| Task 0.3 | Block demotion回滚 | 6h | 高 |
| **Phase 1** | 核心功能 | **3-5天** | 中 |
| Task 1.1 | TransactionPool架构设计 | 14h | 中 |
| Task 1.2 | TransactionPool核心逻辑 | 14h | 中 |
| Task 1.3 | DagKernel集成 | 5h | 低 |
| Task 1.4 | Commands.transfer()修改 | 8h | 低 |
| Task 1.5 | collectCandidateLinks()修改 | 4h | 低 |
| Task 1.6 | Pool移除逻辑 | 1.5h | 低 |
| **Phase 2** | 排序与验证 | **2-3天** | 中 |
| Task 2.1 | Transaction确定性排序 | 8h | 中 |
| Task 2.2 | 验证完整性增强 | 6h | 低 |
| **Phase 3** | 优化与监控 | **1-2天** | 低 |
| Task 3.1 | 性能优化 | 5h | 低 |
| Task 3.2 | 监控统计 | 8h | 低 |
| **总计** | | **7-12天** | |

**备注**: 以上估算为纯开发时间，不包括code review、文档编写、部署测试等。

---

## 七、立即行动项

### 优先级 P0 - 立即执行（今天）

1. ✅ **Task 0.1**: 修正Transaction执行时机
   - 文件: `DagChainImpl.java` line 356-369
   - 改动: 添加`if (isBestChain)`条件
   - 测试: 创建orphan block测试用例

2. ✅ **Task 0.2**: 添加Transaction状态追踪
   - 文件: `TransactionStore.java`, `TransactionStoreImpl.java`
   - 改动: 新增execution index
   - 测试: 验证执行状态正确记录

### 优先级 P1 - 本周完成

3. **Task 0.3**: Block demotion回滚 (明天)
4. **Task 1.1-1.2**: TransactionPool设计与实现 (后天开始)

---

**文档版本**: 1.0
**创建日期**: 2025-11-16
**预计完成**: 2025-11-28 (12个工作日)
**负责人**: 开发团队
**审核人**: Tech Lead

---

## 附录A：关键代码位置索引

| 组件 | 文件路径 | 关键方法 |
|------|---------|---------|
| DagChain | `io/xdag/core/DagChainImpl.java` | `tryToConnect()`, `collectCandidateLinks()` |
| TransactionProcessor | `io/xdag/core/DagTransactionProcessor.java` | `processTransaction()`, `processBlockTransactions()` |
| BlockProcessor | `io/xdag/core/DagBlockProcessor.java` | `processBlock()` |
| Commands | `io/xdag/cli/Commands.java` | `transfer()` |
| TransactionStore | `io/xdag/db/TransactionStore.java` | Interface定义 |
| TransactionStoreImpl | `io/xdag/db/rocksdb/impl/TransactionStoreImpl.java` | RocksDB实现 |

---

## 附录B：相关文档

- [TRANSACTION_FLOW_COMPLETE.md](./TRANSACTION_FLOW_COMPLETE.md) - 完整Transaction流程分析
- [TRANSACTION_SELECTION_EXPLAINED.md](./TRANSACTION_SELECTION_EXPLAINED.md) - Transaction选择机制（旧版，需更新）
- [MINING_FLOW_EXPLAINED.md](./MINING_FLOW_EXPLAINED.md) - 挖矿流程说明
