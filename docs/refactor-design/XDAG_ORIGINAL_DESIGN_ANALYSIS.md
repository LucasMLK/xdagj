# XDAG原始设计分析与Phase 6优化方案

**分析日期**: 2025-10-28
**基于**: XDAG White Paper原始设计思想
**目标**: 结合用户洞察,优化Phase 6架构

---

## 📜 XDAG原始设计核心概念

### 1. DAG结构基础

**定义**:
- 每个Block最多15个links (inputs + outputs)
- Block B被Block A **referenced**,如果可以从A通过links到达B
- **Chain**: 一系列block,每个被前一个引用
- **Distinct chain**: 每个block在不同的64秒interval
- **Main chain**: 最大difficulty的distinct chain
- **Main blocks**: 在main chain上的blocks

### 2. Difficulty计算

```
Difficulty_of_block = 1/hash
  where hash = sha256(sha256(block))

Difficulty_of_chain = sum of difficulties of blocks
```

### 3. Transaction有效性

**关键规则**:
> "Transaction is valid if it is referenced by a main block"

这意味着:
```
MainBlock → TxBlock (直接引用)      ✅ Valid
MainBlock → LinkBlock → TxBlock     ✅ Valid (间接引用)
MainBlock → Link1 → Link2 → TxBlock ✅ Valid (多层间接)

TxBlock (未被引用)                  ❌ Invalid
```

### 4. Double Spending防止

> "Valid transactions are strictly ordered depending on main chain and links order. Double spending is prohibited because only first concurrent transaction (by this order) is applied."

**执行顺序**:
1. 按main chain顺序
2. 每个main block内,按links顺序
3. 递归处理referenced blocks

---

## 🎯 与用户洞察的结合

### 用户的关键洞察

1. **Block就是Block** - 不需要MainBlock/TransactionBlock类区分
2. **主块只包含BlockLink** - 引用,不包含交易完整内容
3. **连接块扩展引用** - 突破15个links限制
4. **Account模型** - 已转向Account,不是UTXO
5. **主块轻量化** - vs BTC/ETH的核心优势

### 原始设计的补充

6. **"Referenced by" 关系** - 交易有效性依赖于被主块引用
7. **Distinct chain规则** - 每64秒一个主块
8. **Difficulty累积** - Main chain = 最大累积难度链

---

## 🔄 重新理解连接块的作用

### 误解纠正

**之前的理解** (错误):
> 连接块用于提高TPS

**正确的理解**:
> 连接块用于**扩展引用范围**,让更多交易能被主链间接引用,从而变为valid

### 实际机制

```
场景: 1000个待确认交易

问题: MainBlock只有15个links,如何让所有1000个交易变valid?

解决方案: 连接块
MainBlock (15 links)
├─ Link1 → LinkBlock1 (15 links)
│            ├─ TxBlock1
│            ├─ TxBlock2
│            └─ ... (15个交易)
├─ Link2 → LinkBlock2 (15 links)
│            └─ 15个交易
└─ ... (13个连接块)

结果: 13 × 15 = 195个交易被MainBlock间接引用 → 全部valid!
```

### TPS提升是副作用

**主要目的**: 扩展引用范围,确保交易有效性
**副作用**: 更多交易被确认 → TPS提升

---

## 📐 Phase 6理想架构 (基于原始设计)

### 1. Block统一结构

```java
/**
 * Block - DAG中的统一节点
 *
 * 原始设计原则:
 * 1. 每个Block最多15个links
 * 2. Block通过links形成DAG
 * 3. Transaction通过被主块引用(直接或间接)变为valid
 */
public class Block {
    // 核心元数据
    private BlockInfo info;              // hash, timestamp, difficulty, etc.

    // DAG拓扑 (最多15个)
    private List<BlockLink> links;       // 引用其他blocks

    // 可选数据
    private Optional<Transaction> transfer;  // Account转账
    private Optional<String> remark;
    private Optional<Bytes32> nonce;     // PoW nonce (候选主块)

    // 签名
    private List<Signature> signatures;
    private List<PublicKey> publicKeys;
}
```

### 2. BlockLink简化

```java
/**
 * BlockLink - 纯粹的DAG边
 *
 * 原始设计: "links to another blocks (inputs and outputs)"
 * Phase 6优化: 移除input/output区分,统一为引用
 */
@Value
public class BlockLink {
    Bytes32 targetHash;  // 只需要目标block的hash

    // Phase 6移除:
    // - LinkType (INPUT/OUTPUT/REFERENCE) ❌
    // - XAmount amount ❌ (金额在Transaction中)
}
```

### 3. Transaction (Account模型)

```java
/**
 * Transaction - Account模型的转账
 *
 * 原始设计是UTXO模型,Phase 6转向Account模型
 */
@Value
public class Transaction {
    Address from;       // 源账户
    Address to;         // 目标账户
    XAmount amount;     // 金额
    UInt64 nonce;       // 防重放
    XAmount fee;        // 手续费
}
```

### 4. Block的四种语义

虽然都是Block,但通过字段组合表达不同含义:

#### (1) 候选主块 (Main Block Candidate)

```java
Block candidateBlock = Block.builder()
    .links([
        BlockLink(prevMainHash),   // 引用上一个主块
        BlockLink(linkBlock1),     // 引用连接块/交易块
        BlockLink(orphan1),        // 引用孤块
        ...                        // 最多15个
    ])
    .transfer(null)                // 主块不转账
    .nonce(powNonce)               // PoW nonce (挖矿中)
    .build();

// 特征:
// - 有nonce (PoW)
// - 无transfer
// - 通过PoW竞争成为main block
```

#### (2) 主块 (Main Block)

```java
// 与候选块相同,但被选入main chain
// flags包含BI_MAIN
// 原始设计: "Blocks in main chain are called main_blocks"
```

#### (3) 交易块 (Transaction Block)

```java
Block txBlock = Block.builder()
    .links([])                     // 通常为空
    .transfer(Transaction)     // 包含转账
    .nonce(null)
    .build();

// 特征:
// - 有transfer (转账信息)
// - 无nonce
// - 通过被main block引用变为valid
// - 原始设计: "Transaction is valid if it is referenced by a main block"
```

#### (4) 连接块 (Link Block)

```java
Block linkBlock = Block.builder()
    .links([
        BlockLink(txBlock1),       // 引用交易
        BlockLink(txBlock2),
        BlockLink(linkBlock2),     // 可递归引用其他连接块
        ...                        // 最多15个
    ])
    .transfer(null)
    .nonce(null)
    .build();

// 特征:
// - 无transfer,无nonce
// - 纯粹的引用节点
// - 作用: 扩展main block的引用范围
// - 让更多交易被main block间接引用 → valid
```

---

## 🔄 交易有效性机制

### 原始设计的核心规则

> "Transaction is valid if it is referenced by a main block"

### 实现逻辑

```java
/**
 * 检查交易是否有效
 *
 * 原始设计: 交易必须被main block引用(直接或间接)
 */
public boolean isTransactionValid(Block txBlock) {
    // 1. 检查是否被任何block引用
    if (!isReferenced(txBlock)) {
        return false;  // 未被引用 → invalid
    }

    // 2. 检查是否被main block引用(直接或间接)
    return isReferencedByMainBlock(txBlock);
}

/**
 * 递归检查是否被main block引用
 */
private boolean isReferencedByMainBlock(Block block) {
    // 获取所有引用此block的blocks
    List<Block> referencers = getBlocksReferencingThis(block);

    for (Block referencer : referencers) {
        // 如果直接被main block引用
        if (isMainBlock(referencer)) {
            return true;
        }

        // 递归检查间接引用
        if (isReferencedByMainBlock(referencer)) {
            return true;
        }
    }

    return false;
}
```

### 引用关系索引

**Phase 2已实现**:
```
BLOCK_REFS_INDEX (0xB0): hash → refs
存储: 哪些blocks引用了这个block
```

**Phase 6需要**:
- 反向索引: block → 引用它的blocks
- 用于快速判断transaction validity

---

## 🎯 Main Chain选择机制

### 原始设计

```
Main_chain = distinct chain with maximum difficulty
Difficulty_of_chain = sum of difficulties of blocks
Difficulty_of_block = 1/hash
```

### Distinct Chain规则

**原始定义**:
> "Chain is called distinct if every its block belongs to separate 64-seconds interval"

**含义**:
```
Valid Main Chain:
Block1 (timestamp: 0-63秒)
  ↓
Block2 (timestamp: 64-127秒)
  ↓
Block3 (timestamp: 128-191秒)

Invalid Chain (非distinct):
Block1 (timestamp: 10秒)
  ↓
Block2 (timestamp: 50秒)  ❌ 同一个64秒interval
```

### Phase 6实现

```java
/**
 * 检查chain是否distinct
 */
public boolean isDistinctChain(List<Block> chain) {
    Set<Long> epochs = new HashSet<>();

    for (Block block : chain) {
        long epoch = block.getTimestamp() / 64;  // 64秒interval
        if (epochs.contains(epoch)) {
            return false;  // 同一个epoch,不是distinct
        }
        epochs.add(epoch);
    }

    return true;
}

/**
 * 计算chain的累积难度
 */
public BigInteger calculateChainDifficulty(List<Block> chain) {
    BigInteger totalDiff = BigInteger.ZERO;

    for (Block block : chain) {
        // Difficulty = 1/hash (原始设计)
        // 实际实现: getDiffByRawHash()
        totalDiff = totalDiff.add(block.getInfo().getDifficulty().toBigInteger());
    }

    return totalDiff;
}
```

---

## 🚀 连接块的正确使用

### 场景: 1000个待确认交易

**问题**: MainBlock只有15个links,无法直接引用所有交易

**解决方案**: 连接块树

```
MainBlock (15 links, 在main chain上)
├─ BlockLink → PrevMainBlock
├─ BlockLink → LinkBlock1
│                ├─ BlockLink → TxBlock1   ✅ Valid (被MainBlock间接引用)
│                ├─ BlockLink → TxBlock2   ✅ Valid
│                └─ ... (15个交易)
├─ BlockLink → LinkBlock2
│                ├─ BlockLink → LinkBlock2.1 (递归)
│                │                └─ TxBlock16-30  ✅ Valid
│                └─ BlockLink → TxBlock31-45
└─ ... (13个连接块)

结果:
- MainBlock引用13个连接块
- 每个连接块引用15个交易(或更多连接块)
- 13 × 15 × 15 = 2,925个交易被间接引用
- 所有2,925个交易都valid!
```

### 创建逻辑

```java
/**
 * 创建连接块以扩展引用范围
 */
public Block createLinkBlock(List<Block> blocksToReference) {
    if (blocksToReference.size() > 15) {
        // 递归创建连接块树
        List<Block> linkBlocks = new ArrayList<>();
        for (int i = 0; i < blocksToReference.size(); i += 15) {
            List<Block> batch = blocksToReference.subList(i, Math.min(i + 15, blocksToReference.size()));
            linkBlocks.add(createLinkBlock(batch));  // 递归
        }
        return createLinkBlock(linkBlocks);
    }

    // 创建连接块
    List<BlockLink> links = blocksToReference.stream()
        .map(b -> new BlockLink(b.getHash()))
        .collect(Collectors.toList());

    Block linkBlock = Block.builder()
        .timestamp(XdagTime.getCurrentTimestamp())
        .links(links)
        .transfer(null)  // 连接块不转账
        .nonce(null)     // 连接块不挖矿
        .build();

    // 计算hash并签名
    linkBlock = finalizeBlock(linkBlock);
    return linkBlock;
}
```

---

## 📊 执行顺序与Double Spending防止

### 原始设计

> "Valid transactions are strictly ordered depending on main chain and links order. Double spending is prohibited because only first concurrent transaction (by this order) is applied."

### 执行顺序规则

```
1. 按main chain顺序遍历main blocks
2. 对每个main block,按links顺序处理
3. 递归处理referenced blocks
4. 同一个block只执行一次(BI_APPLIED flag)
```

### Phase 6实现

```java
/**
 * 执行main block
 */
public XAmount executeMainBlock(Block mainBlock) {
    assert (mainBlock.getInfo().getFlags() & BI_MAIN) != 0;

    XAmount totalFees = XAmount.ZERO;

    // 按links顺序处理 (严格顺序!)
    for (BlockLink link : mainBlock.getLinks()) {
        Block linkedBlock = getBlockByHash(link.getTargetHash());

        // 递归执行referenced block
        XAmount fee = executeBlockRecursive(linkedBlock);
        totalFees = totalFees.add(fee);
    }

    return totalFees;
}

/**
 * 递归执行block及其references
 */
private XAmount executeBlockRecursive(Block block) {
    // 1. 检查是否已执行
    if ((block.getInfo().getFlags() & BI_APPLIED) != 0) {
        return XAmount.ZERO;  // 已执行,跳过 (防止重复执行)
    }

    // 2. 如果是交易块,执行转账
    if (block.getTransfer().isPresent()) {
        Transaction transfer = block.getTransfer().get();

        // 验证nonce (防止double spending)
        if (!validateNonce(transfer)) {
            return XAmount.ZERO;  // Nonce错误,跳过 (并发交易,只执行第一个)
        }

        // 执行转账
        executeTransfer(transfer);

        // 标记已执行
        updateBlockFlag(block, BI_APPLIED, true);

        return transfer.getFee();
    }

    // 3. 如果是连接块,递归处理links
    XAmount totalFees = XAmount.ZERO;
    for (BlockLink link : block.getLinks()) {
        Block linkedBlock = getBlockByHash(link.getTargetHash());
        totalFees = totalFees.add(executeBlockRecursive(linkedBlock));
    }

    // 标记已处理
    updateBlockFlag(block, BI_APPLIED, true);

    return totalFees;
}
```

### Double Spending防止

```java
/**
 * 验证nonce防止double spending
 */
private boolean validateNonce(Transaction transfer) {
    UInt64 expectedNonce = addressStore.getNonce(transfer.getFrom()).add(1);

    if (transfer.getNonce().compareTo(expectedNonce) != 0) {
        // Nonce不匹配
        // 原始设计: "only first concurrent transaction is applied"
        log.debug("Nonce mismatch, expected {}, got {}",
                 expectedNonce, transfer.getNonce());
        return false;
    }

    return true;
}
```

---

## 🎯 Phase 6迁移目标

### 需要移除

1. **XdagField.FieldType** - 16种类型 → 不需要
   - `XDAG_FIELD_IN/OUT` (UTXO遗留)
   - `XDAG_FIELD_INPUT/OUTPUT` (改为Transaction)

2. **BlockLink.LinkType** - INPUT/OUTPUT/REFERENCE → 统一为引用

3. **BlockLink.amount** - 金额 → 移到Transaction

4. **512字节依赖** - XdagBlock/XdagField → CompactSerializer

### 需要保留/强化

1. **"Referenced by"关系**
   - 需要反向索引: block → 引用它的blocks
   - 用于快速判断transaction validity

2. **Distinct chain规则**
   - 每64秒一个main block
   - Phase 6继续保持

3. **Difficulty累积**
   - Main chain = 最大累积难度
   - Phase 6继续保持

4. **严格执行顺序**
   - Main chain顺序
   - Links顺序
   - 递归处理

### 需要新增

1. **Transaction结构**
   - 替代UTXO模型
   - 包含from/to/amount/nonce/fee

2. **反向引用索引**
   ```
   BLOCK_REFERENCERS_INDEX: hash → List<Bytes32>
   存储: 哪些blocks引用了这个block
   ```

3. **Transaction validity检查**
   ```java
   boolean isReferencedByMainBlock(Block txBlock)
   ```

---

## 📊 架构对比

### 原始XDAG设计

```
Block (512字节固定)
├─ 15个XdagField
│  ├─ XDAG_FIELD_IN/OUT (UTXO)
│  ├─ XDAG_FIELD_INPUT/OUTPUT (Account)
│  └─ ...
└─ Hash = SHA256(SHA256(512字节))

优点:
- 固定大小,简单
- UTXO模型清晰

缺点:
- 混合UTXO和Account
- FieldType复杂(16种)
- 512字节限制灵活性
```

### Phase 6设计

```
Block (可变大小,CompactSerializer)
├─ BlockInfo (元数据)
├─ List<BlockLink> links (最多15个)
│  └─ 只有targetHash (统一引用)
├─ Optional<Transaction> (纯Account模型)
└─ Hash = SHA3(CompactSerializer)

优点:
- 纯Account模型
- BlockLink简化(只有hash)
- 可变大小,灵活
- 类型安全

缺点:
- 需要snapshot升级
```

---

## 🎉 总结

### 原始设计的精髓

1. **"Referenced by"关系** - 交易有效性的核心
2. **Distinct chain** - 每64秒一个主块
3. **Difficulty累积** - 最长链选择
4. **严格顺序执行** - 防止double spending

### 用户洞察的贡献

1. **Block统一** - 不需要类区分
2. **主块轻量** - 只有引用,vs BTC/ETH优势
3. **连接块递归** - 扩展引用范围,提高TPS
4. **Account模型** - 转向现代化

### Phase 6最终目标

**保留原始设计精髓** + **现代化实现** + **移除历史包袱**

```
Phase 6 = XDAG原始DAG设计
        + CompactSerializer
        + 纯Account模型
        + SHA3 hash
        - 512字节限制
        - UTXO遗留
        - FieldType复杂性
```

---

**文档版本**: v1.0
**创建日期**: 2025-10-28
**基于**: XDAG White Paper + 用户洞察
**状态**: 设计完成,待实施
