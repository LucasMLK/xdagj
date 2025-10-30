# Phase 4 Layer 3 完整迁移计划 - Commands.java

**创建日期**: 2025-10-30
**分支**: refactor/core-v5.1
**状态**: 规划阶段
**前置条件**: Layer 2 核心层 100% 完成 ✅, xferV2() PoC 验证成功 ✅

---

## 📋 目标

将 Commands.java 从 Address-based 架构完全迁移到 v5.1 Transaction + BlockV5 架构。

**核心原则**:
1. ✅ 使用 Transaction 替代 Address
2. ✅ 使用 BlockV5 替代 Block
3. ✅ 使用 Link 引用 Transaction/Block
4. ✅ 保持 XDAG 独特功能（块作为交易输入）
5. ✅ 向后兼容（新旧方法并存过渡期）

---

## 🚧 前置基础设施更新（必须优先完成）

### Issue 1: Blockchain 接口不支持 BlockV5

**问题**:
```java
// 当前 Blockchain 接口
public interface Blockchain {
    ImportResult tryToConnect(Block block);  // 只支持 Block
}

// xferV2() PoC 中的临时解决方案
ImportResult result = ((BlockchainImpl) kernel.getBlockchain()).tryToConnect(block);
//                     ^^^^^^^^^^^^^^^^^^^^ 强制转换
```

**影响**:
- 所有使用 BlockV5 的代码都需要强制转换
- 违反接口抽象原则
- 代码可维护性差

**解决方案**: 更新 Blockchain 接口

**实现位置**: `src/main/java/io/xdag/core/Blockchain.java`

**需要添加**:
```java
public interface Blockchain {
    // 现有方法（保持向后兼容）
    ImportResult tryToConnect(Block block);

    // 新增方法（Phase 4 Layer 3）
    ImportResult tryToConnect(BlockV5 block);
}
```

**实现位置**: `src/main/java/io/xdag/core/BlockchainImpl.java`

**状态**: 已实现（Step 2.1 完成），只需要在接口中声明

**优先级**: 🔴 **极高** - 阻塞所有 v5.1 交易创建功能

---

### Issue 2: 网络层不支持 BlockV5

**问题**:
```java
// 当前网络 API
public void broadcastBlock(Block block, int ttl);  // 只支持 Block

// xferV2() PoC 中的临时解决方案
// TODO: 跳过广播（网络层未准备好）
// kernel.broadcastBlock(block, ttl);  // 编译错误
```

**影响**:
- BlockV5 无法广播到网络
- v5.1 交易只能本地执行
- 无法与其他节点同步

**解决方案**: 实现 broadcastBlockV5() 或更新网络协议

**需要实现**:
```java
// 方案 A: 新增重载方法
public void broadcastBlock(BlockV5 block, int ttl);

// 方案 B: 统一方法（推荐）
public void broadcastBlock(Object block, int ttl) {
    if (block instanceof BlockV5) {
        // BlockV5 序列化和广播
    } else if (block instanceof Block) {
        // 旧 Block 序列化和广播
    }
}
```

**实现位置**:
- `src/main/java/io/xdag/Kernel.java`
- `src/main/java/io/xdag/net/` (网络协议层)

**优先级**: 🔴 **极高** - 阻塞完整功能（本地测试可暂时跳过）

---

### Issue 3: 同步机制需要支持 BlockV5

**问题**:
- SyncManager 需要能够接收和验证 BlockV5
- 区块同步协议需要更新

**解决方案**: 扩展 SyncManager

**实现位置**: `src/main/java/io/xdag/consensus/SyncManager.java`

**优先级**: 🟡 **中** - 影响节点同步（单节点测试可暂时跳过）

---

## 📐 XDAG 特性设计决策

### 核心问题: "块作为交易输入" 的 v5.1 实现

**背景**: XDAG 允许块作为交易输入（xferToNew() 功能），但 v5.1 Transaction 只有单个 `from` 地址。

#### 当前实现 (Address-based)
```java
// xferToNew() - 将所有块余额转移到新地址
Map<Address, ECKeyPair> ourBlocks = Maps.newHashMap();

kernel.getBlockStore().fetchOurBlocks(pair -> {
    Block block = pair.getValue();
    // 块作为输入
    ourBlocks.put(
        new Address(block.getHash(), XDAG_FIELD_IN, block.getInfo().getAmount(), false),
        //                           ^^^^^^^^^^^^^^ 块引用类型
        kernel.getWallet().getAccounts().get(index)
    );
});

// 创建交易块，使用块作为输入
List<BlockWrapper> txs = createTransactionBlock(ourBlocks, to, remark, null);
```

#### 设计挑战
```java
// v5.1 Transaction 设计
Transaction {
    Bytes32 from;   // 单个发送地址（不是块哈希）
    Bytes32 to;     // 单个接收地址
    XAmount amount;
    UInt64 nonce;
    XAmount fee;
}
```

**矛盾**:
- Transaction.from 是地址哈希
- XDAG 特性需要块哈希作为输入
- 块哈希 ≠ 地址哈希

---

### 解决方案分析

#### 方案 1: Transaction 不支持块输入 ❌

**设计**: Transaction 只支持地址到地址转账

```java
// 只能从地址发送
Transaction tx = Transaction.builder()
    .from(addressHash)  // 必须是地址
    .to(addressHash)
    .amount(amount)
    .build();
```

**块余额转移**: 使用不同机制
```java
// BlockV5 直接引用块（不创建 Transaction）
List<Link> links = List.of(
    Link.toBlock(block1Hash),
    Link.toBlock(block2Hash)
);
```

**优点**:
- ✅ 保持 Transaction 简洁设计
- ✅ 符合 EVM 兼容目标

**缺点**:
- ❌ 需要重新设计 xferToNew() 逻辑
- ❌ 破坏 XDAG 原有功能
- ❌ 用户体验变化大

**评估**: ⚠️ 不推荐（破坏 XDAG 特性）

---

#### 方案 2: 扩展 Transaction 支持块输入 ❌

**设计**: 添加 isBlock 标志

```java
Transaction {
    Bytes32 from;       // 发送者（地址或块）
    boolean isBlock;    // 标记 from 是否为块
    Bytes32 to;
    XAmount amount;
    UInt64 nonce;
    XAmount fee;
}
```

**优点**:
- ✅ 支持 XDAG 原有功能
- ✅ Transaction 统一处理

**缺点**:
- ❌ 破坏 Transaction 简洁设计
- ❌ 与 EVM 兼容性目标冲突
- ❌ 签名验证逻辑复杂（块无私钥）

**评估**: ❌ 不推荐（破坏 v5.1 设计哲学）

---

#### 方案 3: 使用 Link 引用 + 特殊交易类型 ✅ **推荐**

**设计**: BlockV5 同时包含 Block links 和 Transaction link

```java
// Step 1: 收集有余额的块
List<Bytes32> blockHashes = new ArrayList<>();
XAmount totalAmount = XAmount.ZERO;

kernel.getBlockStore().fetchOurBlocks(pair -> {
    Block block = pair.getValue();
    if (block.getInfo().getAmount().compareTo(XAmount.ZERO) > 0) {
        blockHashes.add(block.getHash());
        totalAmount = totalAmount.add(block.getInfo().getAmount());
    }
});

// Step 2: 创建 Transaction（接收方）
Bytes32 toAddress = keyPair2Hash(kernel.getWallet().getDefKey());
Transaction tx = Transaction.builder()
    .from(toAddress)  // 发送方 = 接收方（自转账）
    .to(toAddress)
    .amount(totalAmount)
    .nonce(currentNonce)
    .fee(XAmount.of(100, XUnit.MILLI_XDAG))
    .build();

// 签名并保存
tx = tx.sign(kernel.getWallet().getDefKey());
kernel.getTransactionStore().saveTransaction(tx);

// Step 3: 创建 BlockV5，同时引用块和交易
List<Link> links = new ArrayList<>();

// 引用所有源块（块余额来源）
for (Bytes32 blockHash : blockHashes) {
    links.add(Link.toBlock(blockHash));
}

// 引用 Transaction（接收方）
links.add(Link.toTransaction(tx.getHash()));

// 创建 BlockV5
BlockV5 block = BlockV5.builder()
    .header(header)
    .links(links)
    .build();
```

**执行逻辑** (applyBlockV2()):
```java
// Phase 1: 处理 Block links（递归处理源块）
for (Link link : links) {
    if (link.isBlock()) {
        Block refBlock = getBlockByHash(link.getTargetHash(), true);
        XAmount blockBalance = refBlock.getInfo().getAmount();

        // 扣减源块余额
        refBlock.getInfo().setAmount(XAmount.ZERO);

        // 累积金额
        totalAmount = totalAmount.add(blockBalance);
    }
}

// Phase 2: 处理 Transaction link
for (Link link : links) {
    if (link.isTransaction()) {
        Transaction tx = transactionStore.getTransaction(link.getTargetHash());

        // 验证金额匹配
        if (tx.getAmount().equals(totalAmount)) {
            // 增加接收方余额
            addressStore.updateBalance(tx.getTo().toArray(),
                toBalance.add(tx.getAmount()));
        }
    }
}
```

**优点**:
- ✅ 保持 Transaction 简洁设计
- ✅ 符合 v5.1 Link-based 哲学
- ✅ 保留 XDAG "块作为输入" 功能
- ✅ 执行逻辑清晰（先处理块，再处理交易）

**缺点**:
- ⚠️ Transaction.from = to（看起来奇怪，但合理）
- ⚠️ 需要扩展 applyBlockV2() 处理块余额转移

**评估**: ✅ **强烈推荐** - 平衡设计、功能和兼容性

---

### 最终决策

**采用方案 3: Link 引用 + 特殊交易类型**

**设计文档**:
```
块余额转移交易 (Block Balance Transfer Transaction)

结构:
- BlockV5.links:
  * Link.toBlock(block1Hash)  // 源块 1
  * Link.toBlock(block2Hash)  // 源块 2
  * Link.toBlock(block3Hash)  // 源块 3
  * Link.toTransaction(txHash)  // 接收交易

- Transaction:
  * from: 接收地址（自身）
  * to: 接收地址（自身）
  * amount: 所有源块余额之和
  * nonce: 当前 nonce
  * fee: 固定 gas 费用

语义:
- Block links: 指定余额来源（多个块）
- Transaction link: 指定余额去向（单个地址）
- 自转账: from = to（表示块余额归集）

执行:
1. 遍历 Block links，扣减源块余额
2. 累积总金额
3. 验证 Transaction.amount = 总金额
4. 增加接收地址余额
```

**优势**:
- ✅ 完全符合 v5.1 架构
- ✅ 保留 XDAG 独特功能
- ✅ 为未来更复杂的交易类型提供模式

---

## 📋 迁移顺序和任务分解

### Phase 1: 基础设施更新（1-2天）

#### Task 1.1: 更新 Blockchain 接口 ✅

**目标**: 添加 `tryToConnect(BlockV5)` 方法声明

**文件**: `src/main/java/io/xdag/core/Blockchain.java`

**修改**:
```java
public interface Blockchain {
    // 现有方法
    ImportResult tryToConnect(Block block);

    // 新增方法 (Phase 4 Layer 3 Task 1.1)
    ImportResult tryToConnect(BlockV5 block);

    // ... 其他方法保持不变
}
```

**验证**:
- ✅ 编译成功
- ✅ BlockchainImpl 已有实现（无需修改）

**优先级**: 🔴 极高

---

#### Task 1.2: 实现 broadcastBlockV5() 🔜

**目标**: 支持 BlockV5 广播到网络

**文件**: `src/main/java/io/xdag/Kernel.java`

**新增方法**:
```java
/**
 * Broadcast BlockV5 to network (Phase 4 Layer 3 Task 1.2)
 *
 * @param block BlockV5 to broadcast
 * @param ttl Time-to-live for broadcast
 */
public void broadcastBlockV5(BlockV5 block, int ttl) {
    // Serialize BlockV5
    Bytes blockBytes = block.toBytes();

    // Create network message
    XdagMessage message = new XdagMessage(
        XdagMessageCodes.BLOCKS,
        blockBytes
    );

    // Broadcast to all active channels
    for (Channel channel : channelMgr.getActiveChannels()) {
        channel.sendMessage(message);
    }

    log.debug("BlockV5 broadcasted: {}", block.getHash().toHexString());
}
```

**网络协议更新** (如果需要):
- 可能需要新的消息类型或版本标识
- 确保兼容现有节点

**验证**:
- ✅ 编译成功
- ✅ 单元测试（模拟广播）
- ⏸️ 集成测试（实际网络）- 可延后

**优先级**: 🟡 中高（本地测试可暂时跳过）

---

### Phase 2: 简单交易迁移（2-3天）

#### Task 2.1: 完整实现 xferV2() ✅

**目标**: 从 PoC 扩展为完整功能

**当前状态**: PoC 完成（单账户转账）

**需要添加**:
1. ✅ 支持 remark 参数（保存到 Transaction.data）
2. ✅ 可配置 fee（而非固定 100 milli-XDAG）
3. ⏸️ 支持批量多账户（如原 xfer()）- 可选

**实现** (扩展版本):
```java
public String xferV2(double sendAmount, String toAddress, String remark) {
    // ... (PoC 代码保持，添加以下增强)

    // 1. 处理 remark（编码为 Bytes）
    Bytes remarkData = Bytes.EMPTY;
    if (remark != null && !remark.isEmpty()) {
        remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));

        // 限制 remark 大小（1KB）
        if (remarkData.size() > 1024) {
            return "Remark too long (max 1KB).";
        }
    }

    // 2. 创建 Transaction 时使用 remark
    Transaction tx = Transaction.builder()
        .from(fromAddress)
        .to(to)
        .amount(amount)
        .nonce(currentNonce)
        .fee(fee)
        .data(remarkData)  // ← 使用 remark
        .build();

    // ... 其余逻辑保持
}
```

**优先级**: 🟢 低（PoC 已验证核心功能）

---

#### Task 2.2: 添加 xferV2() CLI 命令 🔜

**目标**: 暴露 xferV2() 给用户

**文件**: `src/main/java/io/xdag/cli/XdagCli.java`（或类似文件）

**添加命令**:
```java
@Command(name = "xferv2", description = "Transfer XDAG using v5.1 Transaction")
public void xferV2(
    @Option(names = {"-a", "--amount"}, required = true) double amount,
    @Option(names = {"-t", "--to"}, required = true) String toAddress,
    @Option(names = {"-r", "--remark"}) String remark
) {
    String result = commands.xferV2(amount, toAddress, remark);
    System.out.println(result);
}
```

**验证**:
- ✅ CLI 编译成功
- ✅ 手动测试转账

**优先级**: 🟡 中

---

### Phase 3: 块余额转移迁移（3-4天）

#### Task 3.1: 实现 xferToNewV2() 🔜

**目标**: 使用 Link 引用实现块余额转移

**文件**: `src/main/java/io/xdag/cli/Commands.java`

**完整实现**:
```java
/**
 * Transfer block balances to new address (v5.1)
 *
 * Phase 4 Layer 3 Task 3.1: Use Block links + Transaction for balance transfer.
 *
 * @return Transaction result message
 */
public String xferToNewV2() {
    StringBuilder str = new StringBuilder();
    str.append("Block Balance Transfer (v5.1) :{ ").append("\n");

    // Step 1: 收集有余额的块
    List<Bytes32> blockHashes = new ArrayList<>();
    XAmount totalAmount = XAmount.ZERO;

    kernel.getBlockStore().fetchOurBlocks(pair -> {
        Block block = pair.getValue();

        // 跳过太新的块（少于 2 * CONFIRMATIONS_COUNT 轮次）
        if (XdagTime.getCurrentEpoch() < XdagTime.getEpoch(block.getTimestamp()) + 2 * CONFIRMATIONS_COUNT) {
            return false;
        }

        // 收集有余额的块
        if (block.getInfo().getAmount().compareTo(XAmount.ZERO) > 0) {
            blockHashes.add(block.getHash());
            totalAmount = totalAmount.add(block.getInfo().getAmount());
            str.append("  Source block: ").append(hash2Address(block.getHash()))
               .append(" amount: ").append(block.getInfo().getAmount().toDecimal(9, XUnit.XDAG).toPlainString())
               .append(" XDAG\n");
        }
        return false;
    });

    if (blockHashes.isEmpty()) {
        return "No blocks with balance available for transfer.";
    }

    // Step 2: 创建接收地址（默认密钥）
    Bytes32 toAddress = keyPair2Hash(kernel.getWallet().getDefKey());

    // Step 3: 获取 nonce
    byte[] toAddrBytes = toBytesAddress(kernel.getWallet().getDefKey()).toArray();
    UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(toAddrBytes);
    long currentNonce = txQuantity.toLong() + 1;

    // Step 4: 创建 Transaction（自转账）
    XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
    XAmount netAmount = totalAmount.subtract(fee);

    Transaction tx = Transaction.builder()
        .from(toAddress)  // 自转账（块余额归集）
        .to(toAddress)
        .amount(netAmount)
        .nonce(currentNonce)
        .fee(fee)
        .data(Bytes.wrap("block balance transfer".getBytes(StandardCharsets.UTF_8)))
        .build();

    // Step 5: 签名 Transaction
    Transaction signedTx = tx.sign(kernel.getWallet().getDefKey());

    // Step 6: 验证 Transaction
    if (!signedTx.isValid()) {
        return "Transaction validation failed.";
    }

    if (!signedTx.verifySignature()) {
        return "Transaction signature verification failed.";
    }

    // Step 7: 保存 Transaction
    kernel.getTransactionStore().saveTransaction(signedTx);

    // Step 8: 创建 BlockV5，引用块和交易
    List<Link> links = new ArrayList<>();

    // 引用所有源块
    for (Bytes32 blockHash : blockHashes) {
        links.add(Link.toBlock(blockHash));
    }

    // 引用 Transaction
    links.add(Link.toTransaction(signedTx.getHash()));

    // 创建 BlockHeader
    BlockHeader header = BlockHeader.builder()
        .timestamp(XdagTime.getCurrentTimestamp())
        .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
        .nonce(Bytes32.ZERO)
        .coinbase(toAddress)
        .hash(null)
        .build();

    // 创建 BlockV5
    BlockV5 block = BlockV5.builder()
        .header(header)
        .links(links)
        .info(null)
        .build();

    // Step 9: 验证并连接块
    ImportResult result = kernel.getBlockchain().tryToConnect(block);  // ← 使用新接口

    if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
        // 更新 nonce
        kernel.getAddressStore().updateTxQuantity(toAddrBytes, UInt64.valueOf(currentNonce));

        // 广播 BlockV5
        int ttl = kernel.getConfig().getNodeSpec().getTTL();
        kernel.broadcastBlockV5(block, ttl);  // ← 使用新方法

        str.append("\n")
           .append("  Transaction hash: ").append(signedTx.getHash().toHexString().substring(0, 16)).append("...\n")
           .append("  Block hash: ").append(hash2Address(block.getHash())).append("\n")
           .append("  Total amount: ").append(totalAmount.toDecimal(9, XUnit.XDAG).toPlainString()).append(" XDAG\n")
           .append("  Fee: ").append(fee.toDecimal(9, XUnit.XDAG).toPlainString()).append(" XDAG\n")
           .append("  Net amount: ").append(netAmount.toDecimal(9, XUnit.XDAG).toPlainString()).append(" XDAG\n")
           .append("  Source blocks: ").append(blockHashes.size()).append("\n")
           .append("  Status: ").append(result.name()).append("\n");

        return str.append("}, it will take several minutes to complete the transaction.").toString();
    } else {
        return String.format(
            "Block balance transfer failed!\n" +
            "  Result: %s\n" +
            "  Error: %s",
            result.name(),
            result.getErrorInfo() != null ? result.getErrorInfo() : "Unknown error"
        );
    }
}
```

**关键设计**:
- ✅ Transaction.from = to（自转账语义）
- ✅ Block links 指定余额来源
- ✅ Transaction link 指定余额去向
- ✅ 保留原有功能（块余额归集）

**优先级**: 🟡 中高

---

#### Task 3.2: 扩展 applyBlockV2() 支持块余额转移 🔜

**目标**: 处理包含 Block links + Transaction link 的 BlockV5

**文件**: `src/main/java/io/xdag/core/BlockchainImpl.java`

**修改位置**: applyBlockV2() 方法（lines 1073-1186）

**新增逻辑**:
```java
// Phase 1: Process Block links recursively first
XAmount blockTransferAmount = XAmount.ZERO;  // ← 新增：累积块余额转移金额

for (Link link : links) {
    if (link.isBlock()) {
        Block refBlock = getBlockByHash(link.getTargetHash(), false);

        // ... 现有递归处理逻辑 ...

        // ========== 新增：块余额转移处理 ==========
        // 检查是否为块余额转移交易（同时有 Block links 和 Transaction link）
        boolean hasTransactionLink = links.stream().anyMatch(Link::isTransaction);

        if (hasTransactionLink) {
            // 块余额转移模式：扣减源块余额
            XAmount refBlockAmount = refBlock.getInfo().getAmount();
            if (refBlockAmount.compareTo(XAmount.ZERO) > 0) {
                // 扣减源块余额
                BlockInfo refInfo = refBlock.getInfo();
                BlockInfo updatedRefInfo = refInfo.toBuilder()
                    .amount(XAmount.ZERO)  // 清零源块余额
                    .build();
                saveBlockInfo(updatedRefInfo);

                // 累积转移金额
                blockTransferAmount = blockTransferAmount.add(refBlockAmount);

                log.debug("Block balance transfer: deducted {} from block {}",
                         refBlockAmount, refBlock.getHash().toHexString());
            }
        }
        // ========== 块余额转移处理结束 ==========
    }
}

// Phase 2: Process Transaction links
for (Link link : links) {
    if (link.isTransaction()) {
        Transaction tx = transactionStore.getTransaction(link.getTargetHash());

        // ... 现有 Transaction 执行逻辑 ...

        // ========== 新增：验证块余额转移金额 ==========
        if (blockTransferAmount.compareTo(XAmount.ZERO) > 0) {
            // 块余额转移模式：验证金额匹配
            XAmount expectedAmount = blockTransferAmount.subtract(tx.getFee());

            if (!tx.getAmount().equals(expectedAmount)) {
                log.error("Block balance transfer amount mismatch: expected={}, actual={}",
                         expectedAmount, tx.getAmount());
                return XAmount.ZERO;  // 验证失败
            }

            // 验证自转账（from = to）
            if (!tx.getFrom().equals(tx.getTo())) {
                log.error("Block balance transfer must be self-transfer (from=to)");
                return XAmount.ZERO;
            }

            log.debug("Block balance transfer validated: {} blocks -> {} XDAG",
                     links.stream().filter(Link::isBlock).count(),
                     tx.getAmount());
        }
        // ========== 块余额转移验证结束 ==========

        // ... 余额更新逻辑保持不变 ...
    }
}
```

**验证**:
- ✅ 块余额正确扣减
- ✅ Transaction 金额验证通过
- ✅ 接收地址余额正确增加
- ✅ 测试用例覆盖

**优先级**: 🟡 中高

---

### Phase 4: 节点奖励分发迁移（2-3天）

#### Task 4.1: 实现 xferToNodeV2() 🔜

**目标**: 使用 Transaction 分发节点奖励

**文件**: `src/main/java/io/xdag/cli/Commands.java`

**当前签名**:
```java
public StringBuilder xferToNode(Map<Address, ECKeyPair> paymentsToNodesMap);
```

**v5.1 签名**:
```java
/**
 * Distribute block rewards to node (v5.1)
 *
 * Phase 4 Layer 3 Task 4.1: Use Transaction for node payments.
 *
 * @param payments Map of node addresses to payment amounts
 * @return StringBuilder containing transaction result message
 */
public StringBuilder xferToNodeV2(Map<Bytes32, XAmount> payments);
```

**实现**:
```java
public StringBuilder xferToNodeV2(Map<Bytes32, XAmount> payments) {
    StringBuilder str = new StringBuilder("Node Reward Distribution (v5.1) :{ ");

    // 获取矿池地址（发送方）
    Bytes32 poolAddress = keyPair2Hash(kernel.getWallet().getDefKey());
    byte[] poolAddrBytes = toBytesAddress(kernel.getWallet().getDefKey()).toArray();

    // 获取 nonce
    UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(poolAddrBytes);
    long baseNonce = txQuantity.toLong();

    // 为每个节点创建 Transaction
    List<BlockV5> blocks = new ArrayList<>();
    int successCount = 0;

    for (Map.Entry<Bytes32, XAmount> payment : payments.entrySet()) {
        Bytes32 nodeAddress = payment.getKey();
        XAmount amount = payment.getValue();
        long currentNonce = baseNonce + successCount + 1;

        // 创建 Transaction
        XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
        Transaction tx = Transaction.builder()
            .from(poolAddress)
            .to(nodeAddress)
            .amount(amount)
            .nonce(currentNonce)
            .fee(fee)
            .data(Bytes.wrap(
                String.format("Pay to %s", kernel.getConfig().getNodeSpec().getNodeTag())
                    .getBytes(StandardCharsets.UTF_8)
            ))
            .build();

        // 签名 Transaction
        Transaction signedTx = tx.sign(kernel.getWallet().getDefKey());

        // 验证
        if (!signedTx.isValid() || !signedTx.verifySignature()) {
            str.append("  [FAILED] Node: ").append(nodeAddress.toHexString().substring(0, 16))
               .append("... - Validation failed\n");
            continue;
        }

        // 保存 Transaction
        kernel.getTransactionStore().saveTransaction(signedTx);

        // 创建 BlockV5
        List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));
        BlockHeader header = BlockHeader.builder()
            .timestamp(XdagTime.getCurrentTimestamp())
            .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
            .nonce(Bytes32.ZERO)
            .coinbase(poolAddress)
            .hash(null)
            .build();

        BlockV5 block = BlockV5.builder()
            .header(header)
            .links(links)
            .info(null)
            .build();

        // 验证并连接
        ImportResult result = kernel.getBlockchain().tryToConnect(block);

        if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
            // 更新 nonce
            kernel.getAddressStore().updateTxQuantity(poolAddrBytes, UInt64.valueOf(currentNonce));

            // 广播
            int ttl = kernel.getConfig().getNodeSpec().getTTL();
            kernel.broadcastBlockV5(block, ttl);

            str.append("  [SUCCESS] Node: ").append(hash2Address(nodeAddress))
               .append(" Amount: ").append(amount.toDecimal(9, XUnit.XDAG).toPlainString())
               .append(" XDAG, Block: ").append(hash2Address(block.getHash()))
               .append("\n");

            successCount++;
            blocks.add(block);
        } else {
            str.append("  [FAILED] Node: ").append(nodeAddress.toHexString().substring(0, 16))
               .append("... - Import failed: ").append(result.name())
               .append("\n");
        }
    }

    str.append("}\n");
    str.append(String.format("Total: %d payments, %d succeeded, %d failed",
                             payments.size(), successCount, payments.size() - successCount));

    return str;
}
```

**优先级**: 🟡 中

---

#### Task 4.2: 更新 PoolAwardManagerImpl.java 🔜

**目标**: 调用 xferToNodeV2() 分发奖励

**文件**: `src/main/java/io/xdag/pool/PoolAwardManagerImpl.java`

**当前调用**:
```java
// 构造 Map<Address, ECKeyPair>
Map<Address, ECKeyPair> paymentsToNodesMap = ...;
commands.xferToNode(paymentsToNodesMap);
```

**v5.1 调用**:
```java
// 构造 Map<Bytes32, XAmount>
Map<Bytes32, XAmount> payments = new HashMap<>();
for (...) {
    Bytes32 nodeAddress = ...;
    XAmount amount = ...;
    payments.put(nodeAddress, amount);
}

commands.xferToNodeV2(payments);
```

**验证**:
- ✅ 奖励正确分发
- ✅ 节点余额正确增加
- ✅ 矿池余额正确扣减

**优先级**: 🟡 中

---

### Phase 5: 批量交易支持（可选，3-4天）

#### Task 5.1: 实现批量多账户转账 ⏸️

**目标**: 支持原 xfer() 的批量功能

**设计**:
```java
// 从多个账户收集金额，创建多个 Transaction
public String xferV2Batch(double sendAmount, String toAddress, String remark) {
    // 1. 收集多个账户（类似原 xfer()）
    // 2. 为每个账户创建 Transaction
    // 3. 创建多个 BlockV5
    // 4. 批量提交
}
```

**优先级**: 🟢 低（可延后）

---

### Phase 6: 清理和测试（2-3天）

#### Task 6.1: 删除过渡类 ⏸️

**目标**: 删除不再需要的类

**待删除**:
- `src/main/java/io/xdag/core/Address.java`
- `src/main/java/io/xdag/core/BlockWrapper.java`
- `src/main/java/io/xdag/core/Block.java.legacy` (如果存在)

**前置条件**:
- ✅ 所有 Address 使用已迁移
- ✅ 所有 BlockWrapper 使用已迁移

**验证**:
- ✅ 全局搜索无 Address 引用
- ✅ 编译成功
- ✅ 测试全部通过

**优先级**: 🟢 低（最后阶段）

---

#### Task 6.2: 集成测试 ⏸️

**目标**: 完整的端到端测试

**测试场景**:
1. ✅ 简单转账（xferV2）
2. ✅ 块余额转移（xferToNewV2）
3. ✅ 节点奖励分发（xferToNodeV2）
4. ✅ 多节点同步（广播和接收 BlockV5）
5. ✅ 边界情况（余额不足、无效地址、重复 nonce）

**优先级**: 🔴 高（验证迁移成功）

---

## 📊 工作量估算

| Phase | 任务数 | 预计天数 | 优先级 |
|-------|--------|----------|--------|
| Phase 1: 基础设施更新 | 2 | 1-2 | 🔴 极高 |
| Phase 2: 简单交易迁移 | 2 | 2-3 | 🟡 中高 |
| Phase 3: 块余额转移迁移 | 2 | 3-4 | 🟡 中高 |
| Phase 4: 节点奖励分发迁移 | 2 | 2-3 | 🟡 中 |
| Phase 5: 批量交易支持 | 1 | 3-4 | 🟢 低（可选） |
| Phase 6: 清理和测试 | 2 | 2-3 | 🔴 高 |
| **总计** | **11** | **13-19** | - |

**最少路径** (跳过 Phase 5): **10-15 天**

**完整路径**: **13-19 天**

---

## 🎯 里程碑

### Milestone 1: 基础设施就绪 ✅
- ✅ Blockchain 接口更新
- ✅ broadcastBlockV5() 实现
- **交付物**: 可编译的 v5.1 基础设施

### Milestone 2: 简单交易可用 ⏸️
- ✅ xferV2() 完整实现
- ✅ CLI 命令可用
- **交付物**: 用户可用的 v5.1 转账功能

### Milestone 3: 核心功能迁移完成 ⏸️
- ✅ xferToNewV2() 实现
- ✅ xferToNodeV2() 实现
- ✅ applyBlockV2() 扩展
- **交付物**: XDAG 独特功能在 v5.1 中可用

### Milestone 4: 完全迁移 ⏸️
- ✅ 删除过渡类
- ✅ 集成测试通过
- **交付物**: 完全 v5.1 架构，无 legacy 依赖

---

## 🚨 风险和缓解措施

### 风险 1: 网络协议兼容性

**风险**: BlockV5 广播可能导致旧节点崩溃或拒绝连接

**缓解**:
1. 实现协议版本协商
2. 旧节点忽略 BlockV5 消息
3. 逐步升级网络节点

**优先级**: 🔴 高

---

### 风险 2: 块余额转移语义变化

**风险**: 用户可能不理解 from=to 的自转账语义

**缓解**:
1. 详细文档说明设计决策
2. CLI 输出清晰提示
3. 保留原有 xferToNew() 一段时间（标记为 deprecated）

**优先级**: 🟡 中

---

### 风险 3: Nonce 管理复杂性

**风险**: 批量交易时 nonce 可能冲突

**缓解**:
1. 使用 AddressStore.getTxQuantity() 集中管理
2. 每次成功后立即更新 nonce
3. 失败时不更新 nonce（重试可用）

**优先级**: 🟡 中

---

### 风险 4: 性能退化

**风险**: v5.1 引入 TransactionStore 查询，可能影响性能

**缓解**:
1. TransactionStore 使用 RocksDB（已优化）
2. 缓存热点 Transaction
3. 性能测试和优化

**优先级**: 🟢 低（后期优化）

---

## 📚 参考文档

- [PHASE4_MIGRATION_PLAN.md](PHASE4_MIGRATION_PLAN.md) - Phase 4 整体计划
- [PHASE4_LAYER3_ANALYSIS.md](PHASE4_LAYER3_ANALYSIS.md) - Layer 3 分析
- [PHASE4_POC_COMPLETION.md](PHASE4_POC_COMPLETION.md) - PoC 完成总结
- [PHASE4_CURRENT_PROGRESS.md](PHASE4_CURRENT_PROGRESS.md) - 当前进度
- [Transaction.java](src/main/java/io/xdag/core/Transaction.java) - Transaction 实现
- [BlockV5.java](src/main/java/io/xdag/core/BlockV5.java) - BlockV5 实现
- [Link.java](src/main/java/io/xdag/core/Link.java) - Link 实现

---

**创建日期**: 2025-10-30
**状态**: ✅ 计划完成，等待执行
**下一步**: Phase 1 Task 1.1 - 更新 Blockchain 接口
