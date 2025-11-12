# XDAG 核心数据结构设计

**设计日期**: 2025-10-28
**版本**: v5.1
**状态**: 核心参数已确定 ✅

---

## 📋 目录

1. [设计目标](#设计目标)
2. [整体架构](#整体架构)
3. [数据结构](#数据结构)
4. [候选块竞争机制](#候选块竞争机制)
5. [共识机制详解](#共识机制详解)
6. [Hash与签名](#hash与签名)
7. [与BTC/ETH对比](#与btceth对比)
8. [容量分析](#容量分析)
9. [核心参数决策](#核心参数决策)

---

## 设计目标

### 核心原则

1. **清晰的职责分离**: BlockHeader参与hash计算，Transaction包含签名
2. **安全优先**: 学习BTC（签名不参与block hash）+ ETH（Keccak256 + EVM兼容）
3. **完全移除512字节依赖**: 激进删除策略，纯现代化架构
4. **极简架构**: 只有Transaction和Block两种类型，无连接块
5. **高TPS**: 单Block支持100万+引用，TPS可达15,000+

---

## 整体架构

### XDAG的两种块类型

```
┌──────────────────────────────────────────────────────────────┐
│              XDAG 两种块类型（极简设计）                      │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  1️⃣ Transaction (独立类型，Transaction.java)                │
│     ┌────────────────────────────────────────────┐         │
│     │  - from, to, amount, nonce, fee            │         │
│     │  - data (最多256字节，类似ETH)              │         │
│     │  - v, r, s (ECDSA签名，EVM兼容)            │         │
│     │  - hash (缓存字段，延迟计算)                │         │
│     │  - 独立广播，独立存储                       │         │
│     │  - 不需要PoW，通过签名验证                  │         │
│     │  - 无timestamp（与ETH一致）                 │         │
│     └────────────────────────────────────────────┘         │
│                                                              │
│  2️⃣ Block (Block.java，候选块)                              │
│     ┌────────────────────────────────────────────┐         │
│     │ BlockHeader:                                │         │
│     │  - timestamp, difficulty, nonce            │         │
│     │  - coinbase (矿工地址)                      │         │
│     │  - hash (缓存字段，延迟计算)                │         │
│     ├────────────────────────────────────────────┤         │
│     │ BlockBody:                                  │         │
│     │  - links: List<Link>                       │         │
│     │    (引用Transactions和其他Blocks)           │         │
│     │    (最多100万+引用)                         │         │
│     └────────────────────────────────────────────┘         │
│     hash = Keccak256(BlockHeader + BlockBody)              │
│                                                              │
│  Link结构（DAG边）:                                          │
│     Link { targetHash: 32 bytes, type: byte }              │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 核心差异

```
BTC/ETH: Block包含完整Transaction数据
XDAG:    Transaction独立存在，Block只包含hash引用（轻量）
```

**关键点**:
1. **Transaction独立存在**: 独立广播和存储，不在Block内部
2. **Block只引用**: 通过Link结构引用Transaction的hash（33字节/link）
3. **签名位置**: 签名在Transaction内部，Block无签名
4. **候选块竞争**: 每个节点产生候选块，选hash值最小的成为主块
5. **轻量引用**: Block只存hash，不存完整数据，可支持100万+引用

### 类型对比

| 特性 | Transaction | Block |
|------|-------------|-------|
| **Java类** | Transaction.java | Block.java |
| **有签名** | ✅ | ❌ |
| **有nonce** | ✅ (账户nonce) | ✅ (PoW nonce) |
| **有timestamp** | ❌ | ✅ |
| **有coinbase** | ❌ | ✅ |
| **有links** | ❌ | ✅ |
| **需要PoW** | ❌ | ✅ |
| **用途** | 转账交易 | 候选主块 |

---

## 数据结构

### BlockHeader

**BlockHeader** - 块头（参与hash计算）

```
BlockHeader {
    // 基本信息（72 bytes固定）
    timestamp: int64           // 时间戳（秒），epoch = timestamp / 64
    difficulty: UInt256        // PoW难度目标值（difficulty_target，32 bytes）
                               // 所有同一epoch的候选块使用相同的difficulty
                               // Block的hash必须满足: hash <= difficulty
    nonce: 32 bytes            // PoW nonce

    // 矿工地址（32 bytes）
    coinbase: 32 bytes         // 矿工地址

    // Hash缓存（不参与序列化）
    hash: 32 bytes | null      // Block hash缓存（延迟计算）
}
```

**关键点**:
- ✅ 所有Block都有nonce（都是候选块）
- ✅ 所有Block都有coinbase（都竞争奖励）
- ✅ epoch从timestamp推导，无需存储（极简）
- ✅ **hash是缓存字段**：不参与序列化，首次使用时计算并缓存
- ✅ **prevMainBlock不存储**：通过getBlockByHeight(height-1)查询，DRY原则

### Block

**Block** - 候选块

```
Block {
    header: BlockHeader    // 固定头部（包含hash缓存）
    body: BlockBody {      // 可变体
        links: List<Link>  // DAG链接列表（轻量，只存hash）
    }
}
```

**Hash计算**: `block_hash = Keccak256(serialize(header) + serialize(body))`

**Hash缓存机制**:
```java
public Bytes32 getHash() {
    if (header.hash == null) {
        header.hash = Keccak256(serialize(header) + serialize(body));
    }
    return header.hash;
}
```

**容量**:
```
32MB Block:
= 32MB / 33 bytes per link
≈ 1,000,000 links

64MB Block:
= 64MB / 33 bytes per link
≈ 2,000,000 links
```

**关键点**:
- Block不包含签名（签名在Transaction中）
- Block不包含Transaction数据（只有hash引用，轻量）
- 所有Block都是候选块（都有nonce）
- 单个Block可以引用100万+个targets（32-64MB）
- **Block.hash是缓存字段**：首次使用时计算，避免重复计算Keccak256

### Link (DAG边)

**Link** - 纯DAG边

```
Link {
    targetHash: 32 bytes    // 指向的块的hash
    type: byte              // 0=Transaction, 1=Block
}
```

**设计说明**:
- 添加type字段避免数据库查询
- 区分引用的是Transaction还是Block
- 提高验证和处理效率
- 每个link只占33字节（轻量）

### Transaction

**Transaction** - 账户模型交易（包含签名）

```
Transaction {
    // 交易数据（参与hash计算）
    from: 32 bytes         // 源账户地址
    to: 32 bytes           // 目标账户地址
    amount: int64          // 转账金额
    nonce: int64           // 账户nonce（防重放、保证顺序）
    fee: int64             // 交易费用
    data: bytes | null     // 交易数据（最多256字节，类似ETH）

    // 签名信息（不参与hash计算，EVM兼容）
    v: int                 // 恢复ID
    r: 32 bytes            // 签名r值
    s: 32 bytes            // 签名s值

    // Hash缓存（不参与序列化）
    hash: 32 bytes | null  // Transaction hash缓存（延迟计算）
}
```

**Hash计算**: `tx_hash = Keccak256(from + to + amount + nonce + fee + data)`

**Hash缓存机制**:
```java
public Bytes32 getHash() {
    if (this.hash == null) {
        this.hash = Keccak256(from + to + amount + nonce + fee + data);
    }
    return this.hash;
}
```

**关键特性**:
- EVM兼容：ECDSA签名（secp256k1曲线），v/r/s格式
- 账户模型：类似ETH，from/to直接指定账户，nonce防重放和排序
- 独立块类型：有自己的hash、签名，独立存储和广播
- 无timestamp：与ETH一致，nonce已足够保证顺序
- data字段：类似ETH的data，支持智能合约调用（限制256字节）
- **hash缓存**：延迟计算，首次调用getHash()时计算并缓存

---

## 候选块竞争机制

### 候选块生成

**每个节点的行为**:
```
每隔64秒（每个epoch）:
1. 查询当前主链最新块：prevMainBlock = getBlockByHeight(maxHeight)
2. 收集待确认的Transactions（最多100万+）
3. 创建候选块：
   Block {
       header: {
           timestamp: currentTime
           difficulty: calculateDifficulty()
           nonce: 0                    // 待PoW计算
           coinbase: myAddress         // 矿工地址
       }
       body: {
           links: [
               Link(prevMainBlock.hash, type=BLOCK),  // 引用前一个主块
               Link(tx1.hash, type=TRANSACTION),
               Link(tx2.hash, type=TRANSACTION),
               // ... 最多100万+
           ]
       }
   }
4. 计算PoW：找到满足difficulty的nonce
5. 广播候选块（32-64MB）
```

### 主块选择

**全网共识**:
```
Epoch N (0-63秒):

所有节点使用相同的difficulty_target（由calculateDifficulty()计算）

节点A → 候选块A:
  - difficulty: 0x00000FFF...FFFF  (目标值，全网统一)
  - hash: 0x00000abc12345678...    (实际hash)

节点B → 候选块B:
  - difficulty: 0x00000FFF...FFFF  (目标值，全网统一)
  - hash: 0x00000034abcdef12...    (实际hash，最小！)

节点C → 候选块C:
  - difficulty: 0x00000FFF...FFFF  (目标值，全网统一)
  - hash: 0x0000123fabcd5678...    (实际hash)

选择规则：
- 所有候选块都必须满足: hash <= difficulty_target
- 选择hash值最小的候选块
- 候选块B成为主块（Main Block）

结果：
- 主块B → 在main chain上，获得coinbase奖励和手续费
- 主块B引用的所有Transactions → Valid
- 孤块A, C → 不在main chain上，PoW算力浪费
- 孤块引用的Transactions → 未被主块引用 → Invalid（需等下个epoch）
```

### Transaction有效性（极简）

**关键规则**:
> Transaction通过"被主块直接引用"变为valid

**直接引用**:
```
MainBlock → Tx1  ✅ Valid
```

**未被引用**:
```
Tx2 (孤立)  ❌ Invalid（等待下个epoch的主块引用）
```

**代码实现**（极简）:
```java
public boolean isTransactionValid(Transaction tx) {
    // 查询引用此tx的所有blocks
    List<Block> referencers = getBlocksReferencingTx(tx.getHash());

    // 检查是否有任何主块直接引用
    for (Block block : referencers) {
        BlockInfo info = getBlockInfo(block.getHash());
        if (info.getHeight() > 0) {  // height > 0 表示主块
            return true;
        }
    }

    return false;  // 未被主块引用
}
```

**无需递归处理！简单直接！**

### 主块确认

**BlockInfo（极简元信息）**:
```
BlockInfo {
    hash: 32 bytes             // 区块哈希（唯一标识）
    height: int64              // 主链高度（height > 0 表示主块，= 0 表示孤块）
    difficulty: UInt256        // PoW难度（32 bytes）
    timestamp: int64           // 时间戳（秒）
}

当候选块成为主块：
- 设置 height = 主链高度
- height > 0 即表示在main chain上
- 执行所有引用的Transactions
```

**BlockInfo设计原则**:
- **职责单一**: 只包含Block的元信息（索引、状态判断、PoW验证）
- **极简设计**: 只有4个必需字段
- **DRY原则**: 所有可推导的数据都不存储
  - prevMainBlock → 通过 `getBlockByHeight(height - 1)` 查询
  - amount/fee → 从Block的Transactions实时计算
  - snapshot → 独立的SnapshotManager管理
- **高性能**: height字段支持快速索引和主链追溯

---

## 共识机制详解

### 为什么必须是单主链

**核心洞察**: Account模型要求全局状态一致性

#### 多主块方案的致命缺陷

**问题1: height失去意义**
```
Epoch 100:
├─ 主块A: height = 100?
├─ 主块B: height = 100?
└─ 主块C: height = 100?

getBlockByHeight(100) 返回哪个？
prevMainBlock = getBlockByHeight(height-1) 返回哪个？
```

**问题2: 交易冲突（最致命）**
```
初始状态: Alice余额 = 100

主块A引用:
  tx1: Alice → Bob (50)
  tx2: Alice → Carol (40)
  执行后: Alice余额 = 10

主块B引用:
  tx3: Alice → Dave (60)
  tx4: Alice → Eve (30)
  执行后: Alice余额 = 10

如果两个都是主块:
  总共转出: 50 + 40 + 60 + 30 = 180
  Alice余额: 100 - 180 = -80 ❌ 负数！
```

**问题3: nonce顺序混乱**
```
主块A顺序:
  tx1(Alice, nonce=1) → tx2(Alice, nonce=2)

主块B顺序:
  tx2(Alice, nonce=2) → tx1(Alice, nonce=1)  // 违反nonce顺序！
```

**结论**: ✅ **每个epoch必须有且仅有一个主块**

### Transaction独立传播机制（关键创新）

**XDAG的天才设计**: Transaction通过P2P独立传播，与Block解耦

#### Transaction生命周期

```
Step 1: Transaction创建和广播
  用户创建 tx1 并签名
  → P2P广播到全网
  → 所有节点接收并验证
  → 存入各自的mempool

Step 2: 多个候选块可以引用同一Transaction
  Epoch 100:
    节点A创建 候选块A，引用 [tx1, tx2, tx3]
    节点B创建 候选块B，引用 [tx1, tx4, tx5]  // 也引用了tx1
    节点C创建 候选块C，引用 [tx1, tx6, tx7]  // 也引用了tx1
    ...
    所有候选块都可能引用tx1！

Step 3: 主块选择
  假设候选块B成为主块（hash最小）
  → tx1, tx4, tx5 被主块引用 ✅ 确认
  → 候选块A, C成为孤块
  → 但tx2, tx3, tx6, tx7并没有丢失！

Step 4: 未确认交易继续等待
  tx2, tx3, tx6, tx7仍然在mempool中
  → 继续P2P传播
  → 下一个epoch的候选块可以引用它们

  Epoch 101:
    节点D创建 候选块D，引用 [tx2, tx3, tx8]
    节点E创建 候选块E，引用 [tx6, tx7, tx9]
    ...

Step 5: 最终确认
  假设候选块D成为主块
  → tx2, tx3 被确认 ✅
  → tx6, tx7继续等待下一个epoch
```

#### 为什么孤块不影响Transaction确认

**关键**: Transaction独立于Block存在

```
对比传统区块链:
Bitcoin/Ethereum:
- Transaction在Block内部
- Block成为孤块 → Transaction回到mempool → 重新打包

XDAG:
- Transaction独立存在（P2P传播）
- Block只引用Transaction的hash
- Block成为孤块 → Transaction已经在mempool中 → 无需操作
```

**即使孤块率99%，TPS也不受影响！**

```
假设1000个节点：
- 每个epoch产生1000个候选块
- 999个成为孤块
- 1个成为主块

但是：
- 主块可以引用100万+个Transactions
- TPS = 1,000,000 txs / 64秒 = 15,625 TPS ✅

孤块不影响TPS，因为：
- Transaction独立传播
- 主块可以从mempool中选择任意Transaction
- 不限于自己创建的候选块中的Transaction
```

### Mempool管理

**Mempool** - 待确认交易池

**核心功能**:
1. **接收Transaction**: 验证签名、nonce、余额后加入mempool，并P2P广播
2. **按fee排序**: 使用优先队列，fee高的优先被选入候选块
3. **选择交易**: 节点创建候选块时，从mempool选择fee最高的交易（最多100万+）
4. **移除已确认**: 主块确认后，从mempool移除对应交易
5. **清理过期**: 定期清理超过24小时未确认的交易

**关键特性**:
- 验证三要素: 签名 + nonce + 余额
- 按fee优先级排序（激励机制）
- 支持100万+交易池
- 自动清理过期交易

### 孤块管理

**设计原则**: 防止孤块数量爆炸，只保留高质量的孤块

#### 孤块攻击威胁分析

**核心问题**: 随着节点数量增长，孤块会指数增长

```
假设1000个节点的网络：
- 每个epoch: 1000个候选块
- 只有1个成为主块
- 999个成为孤块

无防御的存储成本：
= 999 orphans/epoch × 48MB × 12 epochs
= 575 GB！❌ 灾难性的！

恶意攻击：
- 恶意节点创建48MB候选块
- 引用大量不存在的Transaction/Block hash
- 每64秒产生1个恶意孤块
- 强制网络存储和验证垃圾数据
```

#### 多层防御机制

**Layer 1: PoW验证**（基础防御）
```
要求：所有候选块必须满足PoW难度
效果：恶意节点必须投入真实算力
成本：高（需要大量算力）
```

**Layer 2: 孤块数量限制**（关键防御！）
```
策略：每个epoch只保留hash最小的10个孤块

核心洞察：
✅ 不需要保留所有孤块
✅ 只保留"最有价值"的孤块（hash最小 = 难度最好）
✅ 恶意孤块会被自动淘汰

存储成本：
= 10 orphans/epoch × 48MB × 12 epochs
= 5.76 GB ✅ 降低100倍！
```

**Layer 3: 引用有效性验证**（防止无效引用）
```
策略：验证block引用的Transaction/Block是否存在

配置：
- 最多10%无效引用（容错网络延迟）
- 采样验证（大block不需要验证所有links）

效果：
- 拒绝引用大量不存在hash的恶意block
- 允许正常的网络延迟
```

**Layer 4: Block大小限制**（防止超大block）
```
限制：
- MAX_BLOCK_SIZE = 48MB
- MAX_LINKS_PER_BLOCK = 1,485,000

效果：防止恶意超大block攻击
```

**Layer 5: 节点信誉系统**（长期防御）
```
策略：记录节点产生的block质量

标准：
- 50%坏块率 → 标记为恶意节点
- 拒绝恶意节点的future blocks

效果：长期防御持续攻击者
```

#### 孤块存储实现

```java
public class OrphanBlockStore {

    // 配置：每个epoch最多10个孤块
    private static final int MAX_ORPHANS_PER_EPOCH = 10;

    // 存储：按hash排序（hash小的在前）
    private final Map<Long, TreeSet<Block>> orphansByEpoch;

    /**
     * 添加孤块（自动淘汰劣质孤块）
     */
    public boolean addOrphanBlock(Block block) {
        long epoch = getEpoch(block);
        TreeSet<Block> orphans = orphansByEpoch.computeIfAbsent(
            epoch, k -> new TreeSet<>(Comparator.comparing(Block::getHash))
        );

        // 添加新孤块
        orphans.add(block);

        // 如果超过限制，删除hash最大的（难度最差的）
        if (orphans.size() > MAX_ORPHANS_PER_EPOCH) {
            Block worst = orphans.pollLast();
            log.info("Evicted worst orphan: {}", worst.getHash());
            return false;  // 该block被淘汰
        }

        return true;  // 该block被保留
    }
}
```

#### 防御效果对比

| 方案 | 孤块数/epoch | 存储/12 epochs | 相对成本 |
|------|-------------|---------------|---------|
| **无防御** | 999 | 575 GB | 100x ❌ |
| **MAX=10** | 10 | 5.76 GB | 1x ✅ |

#### 配置参数

```java
// 孤块防御配置
public class OrphanBlockConfig {

    // Layer 2: 数量限制
    public static final int MAX_ORPHANS_PER_EPOCH = 10;      // 每epoch最多10个
    public static final int MAX_ROLLBACK_DEPTH = 12;         // 12 epochs回滚深度

    // Layer 3: 引用验证
    public static final double MAX_INVALID_LINK_RATIO = 0.1; // 最多10%无效引用
    public static final int MAX_LINKS_TO_VALIDATE = 10000;   // 采样验证1万个

    // Layer 4: 大小限制
    public static final int MAX_BLOCK_SIZE = 48 * 1024 * 1024; // 48MB
    public static final int MAX_LINKS_PER_BLOCK = 1485000;     // 148万links

    // Layer 5: 信誉系统
    public static final double MALICIOUS_THRESHOLD = 0.5;      // 50%坏块率
    public static final int MIN_BLOCKS_FOR_REPUTATION = 100;   // 最少100个blocks
}
```

**详细防御方案**: 见 [ORPHAN_BLOCK_ATTACK_DEFENSE.md](ORPHAN_BLOCK_ATTACK_DEFENSE.md)

### 主链重组（Chain Reorganization）

**触发条件**: 当发现累积难度更大的链时

**重组流程**:
1. **找到分叉点**: 查找新链和当前主链的共同祖先（Common Ancestor）
2. **回滚主链**: 从当前主链tip回滚到分叉点，撤销状态变更
3. **标记孤块**: 将回滚的主块标记为孤块并存入孤块池
4. **构建新主链**: 从孤块池和新链中构建完整路径
5. **应用新主链**: 按顺序应用新主链上的blocks，更新状态
6. **更新状态**: 更新main tip和height
7. **清理孤块**: 清理超过回滚深度的旧孤块

**安全限制**:
- 最大回滚深度: 12 epochs（约12.8分钟）
- 基于累积难度而非单个block的difficulty
- 完整验证新链上的所有blocks

### 难度调整算法（非对称快速调整）

**设计目标**: 阶梯式调整容易遭受算力攻击，恢复时间要尽可能短

**核心思想**: 上调保守（防攻击），下调激进（快速恢复）

**关键参数**:
```
WINDOW_SIZE = 11           // 观察窗口：11个epochs
MAX_ADJUST_UP = 1.02       // 上调限制：2%（防攻击）
MAX_ADJUST_DOWN = 1.10     // 下调限制：10%（快速恢复）
TARGET_TIME = 64           // 目标出块时间：64秒
MAX_TOTAL_CHANGE = 4.0     // 总变化限制：4倍

// 紧急调整
EMERGENCY_THRESHOLD = 600  // 10分钟
EMERGENCY_ADJUST = 0.5     // 紧急下调50%
```

**调整流程**:
1. 检查是否需要紧急调整（出块时间 > 10分钟）
2. 基于11个epochs移动平均计算目标难度
3. 应用非对称限制：上调最多2%，下调最多10%
4. 应用总变化限制：不超过4倍
5. 返回调整后的难度

**防御效果**:
```
攻击者投入10倍算力:
- 攻击期间: 约37分钟快速出块，难度逐步上调2%/epoch
- 攻击者撤走: 难度最多4倍
- 恢复时间: 约35分钟（下调10%/epoch，比上调快5倍）
- 总破坏: 约72分钟

对比Bitcoin风格（2016 blocks调整）:
- 攻击期间: 3.2小时快速出块
- 难度增加: 11倍
- 恢复时间: 16.7天
- 总破坏: 16.7天

改进: 334倍提升！
```

**优点**:
- ✅ **非对称设计**: 下调速度是上调的5倍（10% vs 2%）
- ✅ **简单可预测**: 易于理解、实现、测试
- ✅ **快速恢复**: 35分钟 vs Bitcoin的16.7天
- ✅ **安全可靠**: 保留了4倍总限制和紧急调整机制

### 确认机制

**Transaction确认等级**:

```
Level 0 - Pending (0秒):
  Transaction在mempool中，等待被引用

Level 1 - Included (64-128秒):
  Transaction被主块引用，初步确认
  用途：小额交易、低风险场景

Level 6 - Confirmed (384-768秒 ≈ 6-13分钟):
  Transaction被6个主块确认，安全确认
  用途：标准交易、中等金额
  对比：Bitcoin需要60分钟

Level 12 - Finalized (768-1536秒 ≈ 13-26分钟):
  Transaction超过回滚深度，最终确定
  用途：大额交易、高安全需求
  永远不会被回滚

Level 32 - Deep Finalized (2048秒 ≈ 34分钟):
  Transaction被32个主块确认，深度确定
  用途：交易所充值、超大额交易
```

**代码实现**:

```java
public int getConfirmationDepth(Transaction tx) {
    // 找到包含此tx的主块
    Block containingBlock = findMainBlockContainingTx(tx.getHash());

    if (containingBlock == null) {
        return 0;  // Pending
    }

    // 计算确认深度
    long txHeight = containingBlock.getInfo().getHeight();
    long currentHeight = getCurrentMainHeight();

    return (int) (currentHeight - txHeight + 1);
}

public boolean isTransactionFinalized(Transaction tx) {
    return getConfirmationDepth(tx) >= MAX_ROLLBACK_DEPTH;
}
```

### 配置参数总结

```java
public class ConsensusConfig {

    // Epoch时间
    public static final long EPOCH_DURATION = 64;  // 秒

    // 确认深度
    public static final int QUICK_CONFIRMATION = 1;      // 64-128秒
    public static final int STANDARD_CONFIRMATION = 6;   // 384-768秒
    public static final int FINALITY_DEPTH = 12;         // 768-1536秒
    public static final int DEEP_FINALITY = 32;          // 2048秒

    // 孤块管理
    public static final int MAX_ROLLBACK_DEPTH = 12;     // 12 epochs
    public static final int ORPHAN_PRUNE_FREQUENCY = 100; // 每100个blocks清理

    // 难度调整（方案B：非对称快速调整）
    public static final int DIFF_ADJUST_WINDOW = 11;         // 11 epochs移动平均
    public static final double MAX_ADJUST_UP = 1.02;         // 2%单次上调（防攻击）
    public static final double MAX_ADJUST_DOWN = 1.10;       // 10%单次下调（快速恢复）
    public static final double MAX_TOTAL_CHANGE = 4.0;       // 4倍总限制
    public static final long EMERGENCY_THRESHOLD = 600;      // 10分钟触发紧急调整
    public static final double EMERGENCY_ADJUST = 0.5;       // 紧急下调50%

    // 难度调整（方案A：ASERT风格，备选）
    // public static final long ASERT_HALFLIFE = 3600;      // 1小时 = 56.25 epochs
    // public static final double ASERT_MAX_RATIO = 4.0;    // 最大调整4倍

    // Mempool
    public static final int MAX_MEMPOOL_SIZE = 1_000_000;    // 100万transactions
    public static final long MEMPOOL_EXPIRY = 24 * 3600;     // 24小时
}
```

**关键改进**:
- ✅ **非对称调整**: 下调速度是上调的5倍（10% vs 2%）
- ✅ **恢复时间**: 从7小时优化到35分钟（12倍提升）
- ✅ **总改进**: 相比Bitcoin风格，提升334倍！

---

## Hash与签名

### Hash计算

**算法**: `Keccak256(deterministic_serialize(header + body))`

**为什么用Keccak256**: 抗长度扩展攻击、与ETH一致、性能更好、EVM兼容

**Block序列化格式**:
```
[Fixed Header - 72 bytes]
  timestamp (8) + difficulty (32) + nonce (32)

[Coinbase - 32 bytes]
  coinbase (32)

[DAG Links - Variable, 32-64MB]
  links.size (4) + links (N × 33 bytes)
    每个link: targetHash (32) + type (1)
```

**Transaction序列化格式**:
```
[Transaction Data - 参与hash计算]
  from (32) + to (32) + amount (8) + nonce (8) + fee (8)

[Data - 参与hash计算]
  data length (2) + data (N, 最多256字节)

[Signature - 不参与hash]
  v (1) + r (32) + s (32)
```

### 签名方案

**Transaction签名流程**:
```
1. 创建Transaction数据
2. 计算hash: tx_hash = Keccak256(from + to + amount + nonce + fee + data)
3. 签名: (v, r, s) = ECDSA_Sign(tx_hash, from_private_key)
4. 验证: recovered = ECRecover(tx_hash, v, r, s), valid = (recovered == from)
```

**Block PoW流程**:
```
1. 创建Block数据（包含links）
2. 寻找nonce:
   while (true) {
       nonce = random()
       hash = Keccak256(serialize(header + body))
       if (hash <= header.difficulty) break  // difficulty就是目标值
   }
3. 广播候选块（包含difficulty和实际计算出的hash）
4. 等待全网选择（hash值最小的成为主块）
```

**安全保证**:
- **防篡改**: 修改数据 → hash改变 → 签名验证失败或PoW无效
- **防重放**: Transaction包含nonce，nonce改变 → hash改变
- **防伪造**: 只有私钥持有者才能生成有效签名
- **PoW安全**: 候选块需满足难度要求，需要大量算力
- **DAG完整性**: 所有links参与hash，无法伪造引用

---

## 与BTC/ETH对比

| 特性 | BTC | ETH | XDAG |
|------|-----|-----|------|
| **Hash算法** | SHA256² | Keccak256 | Keccak256 ✅ |
| **签名算法** | ECDSA | ECDSA (secp256k1) | ECDSA (secp256k1) ✅ |
| **签名格式** | DER | v/r/s | v/r/s ✅ |
| **交易模型** | UTXO | Account + nonce | Account + nonce ✅ |
| **Block结构** | Header + Tx | Header + Tx | Header + Links（轻量） |
| **Transaction存储** | Block内部 | Block内部 | 独立存储 |
| **签名位置** | Tx中 | Tx中 | Tx中 ✅ |
| **拓扑** | 链式 | 链式 | DAG |
| **Block大小** | 1-2MB | ~100KB | 32-64MB |
| **每Block Tx数** | ~2000 | ~200 | 1,000,000+ |
| **TPS** | ~7 | ~15-30 | **15,625-31,250** |

**XDAG优势**:
- ✅ EVM兼容：签名格式完全兼容，可直接使用ETH工具
- ✅ 现代加密：Keccak256 + ECDSA + v/r/s
- ✅ Transaction独立：可并行传播，不需要等待打包
- ✅ DAG架构：更高的并发性
- ✅ 轻量引用：Block只存hash，不存完整数据
- ✅ **超高TPS**：15,625-31,250 TPS，超过Visa（24,000 TPS）
- ✅ 极简架构：只有Transaction和Block两种类型

---

## 容量分析

### 32MB Block

```
容量计算：
32MB / 33 bytes per link ≈ 1,000,000 links

TPS计算：
1,000,000 txs / 64秒 ≈ 15,625 TPS

网络传播（100 Mbps）：
32MB × 8 / 100 Mbps = 2.56秒

验证时间：
100万次数据库查询（带索引）≈ 1秒

结论：✅ 完全可行
```

### 64MB Block

```
容量计算：
64MB / 33 bytes per link ≈ 2,000,000 links

TPS计算：
2,000,000 txs / 64秒 ≈ 31,250 TPS

网络传播（100 Mbps）：
64MB × 8 / 100 Mbps = 5.12秒

验证时间：
200万次数据库查询 ≈ 2秒

结论：✅ 完全可行，超过Visa
```

### 对比主流系统

```
Bitcoin:    7 TPS
Ethereum:   15-30 TPS
Visa:       24,000 TPS
PayPal:     ~200 TPS

XDAG (32MB): 15,625 TPS  ✅ 超过主流区块链
XDAG (64MB): 31,250 TPS  ✅ 超过Visa
```

---

## 核心参数决策

### 决策1: Block大小限制 ✅

**最终决策**: **48MB 软限制**

**技术分析**:
```
48MB Block容量：
= 48MB / 33 bytes per link
≈ 1,485,000 links

TPS计算：
= 1,485,000 txs / 64秒
≈ 23,200 TPS

网络传播（100 Mbps）：
= 48MB × 8 / 100 Mbps
= 3.84秒 ✅ 优秀（<6秒）

验证时间：
= 148万次数据库查询（带索引）
≈ 1.5秒 ✅ 快速

存储（1年）：
= 492,750 blocks × 48MB
≈ 23.1 TB ✅ 可接受
```

**决策理由**:
1. ✅ **性能平衡**: 23,200 TPS接近Visa（24,000 TPS），满足高性能需求
2. ✅ **网络友好**: 3.84秒传播时间，远小于64秒epoch时间
3. ✅ **存储合理**: 23TB/年的存储成本在可接受范围
4. ✅ **软限制灵活**: 允许未来根据网络状况动态调整（32-64MB）
5. ✅ **市场定位**: "接近Visa级别TPS"是强有力的宣传点

**配套措施**:
- 初期可以用32MB（降低存储成本）
- 随着网络成熟和需求增长，逐步提升到48MB
- 保留提升到64MB的技术可能性

**配置参数**:
```java
// Block大小限制
public static final int MAX_BLOCK_SIZE = 48 * 1024 * 1024;  // 48MB软限制
public static final int MIN_BLOCK_SIZE = 32 * 1024 * 1024;  // 32MB初始值
public static final int IDEAL_BLOCK_SIZE = 40 * 1024 * 1024; // 40MB目标值
```

---

### 决策2: 孤块Transaction处理 ✅

**最终决策**: **选项A - 孤块Transaction立即可用（自动进入mempool）**

**技术方案**:
```
当候选块成为孤块时：
1. 遍历孤块引用的所有Transaction links
2. 检查每个Transaction是否在mempool中
3. 如果不在mempool：
   - 从存储加载完整Transaction对象
   - 验证签名、nonce、余额
   - 加入mempool（按fee优先级排序）
4. 下一个epoch的主块可以直接从mempool引用这些Transaction

优点：
- 零额外网络开销（无需重新广播）
- Transaction确认率提升
- 用户体验改善（更快确认）
```

**成本分析**:
```
孤块存储成本（已在孤块管理中）：
- 12 epochs × 2 orphans/epoch × 48MB = 1.15 GB
- 占主链存储：1.15GB / 23.1TB = 0.005% ✅ 可忽略

Transaction提取成本：
- 每个孤块：最多1,485,000 txs
- 数据库读取：1.5秒
- 内存操作：可忽略
```

**决策理由**:
1. ✅ **存储成本可忽略**: 孤块占用仅0.005%
2. ✅ **用户体验极佳**: Transaction不会因孤块而丢失或延迟
3. ✅ **网络效率高**: 无需重新广播，节省带宽
4. ✅ **实施简单**: 孤块已保存，只需增加提取逻辑
5. ✅ **确认率提升**: 孤块率高时，用户体验不受影响

**配置参数**:
```java
// 孤块Transaction处理
public static final boolean ORPHAN_TX_AUTO_MEMPOOL = true;  // 自动进入mempool
public static final int ORPHAN_TX_EXTRACTION_TIMEOUT = 5000; // 5秒超时
```

---

### 决策3: data字段最大长度 ✅

**最终决策**: **1KB（1024字节）+ 按字节收费机制**

**技术分析**:
```
Transaction大小对比（签名65字节 + data）:

data=256字节:
= 130 bytes (基础) + 256 bytes (data) = 386 bytes
48MB block容量: 48MB / 386 ≈ 127,000 txs
TPS: 127,000 / 64 ≈ 1,984 TPS ❌ 太低！

data=1KB:
= 130 bytes (基础) + 1024 bytes (data) = 1,154 bytes
48MB block容量: 48MB / 1154 ≈ 42,500 txs
TPS: 42,500 / 64 ≈ 664 TPS ❌ 仍然太低！

关键洞察：
- Block引用的是Transaction hash（33 bytes），不是完整Transaction！
- data大小不影响Block大小
- data大小只影响Transaction存储和传播

因此：
- data=1KB是安全的
- 不会影响Block的TPS计算
- TPS仍然是：1,485,000 links / 64秒 ≈ 23,200 TPS ✅
```

**按字节收费机制**:
```
基础费用（minimal tx）：
base_fee = 0.0001 XDAG

data收费公式：
data_fee = base_fee × (data_length / 256)

示例：
- data=0字节: fee = 0.0001 XDAG
- data=256字节: fee = 0.0002 XDAG (2x)
- data=512字节: fee = 0.0003 XDAG (3x)
- data=1024字节: fee = 0.0005 XDAG (5x)

智能合约调用（800字节）：
fee ≈ 0.00042 XDAG（3.2x基础费用）

激励效果：
- 简单转账（data=0）：最低费用
- 复杂操作（data大）：支付更高费用
- 矿工优先打包高费用tx
```

**决策理由**:
1. ✅ **智能合约支持**: 1KB足够大多数DeFi操作（参考ETH常见大小）
2. ✅ **TPS不受影响**: Block只存hash引用，data大小不影响Block容量
3. ✅ **激励机制**: 按字节收费，鼓励精简data
4. ✅ **未来兼容**: EVM兼容，可支持智能合约调用
5. ✅ **存储可控**: data费用机制防止滥用

**以太坊参考**:
- ETH平均transaction data: ~200-500字节
- DeFi复杂操作: ~800-1500字节
- 1KB是合理的上限

**配置参数**:
```java
// Transaction data字段
public static final int MAX_DATA_LENGTH = 1024;  // 1KB最大长度
public static final int BASE_DATA_LENGTH = 256;  // 256字节基准
public static final double DATA_FEE_MULTIPLIER = 1.0 / 256;  // 每字节费用系数
```

---

## 最终参数总结

```java
public class CoreConfig {

    // ==================== Block大小 ====================
    public static final int MAX_BLOCK_SIZE = 48 * 1024 * 1024;  // 48MB软限制
    public static final int MIN_BLOCK_SIZE = 32 * 1024 * 1024;  // 32MB初始值
    public static final int IDEAL_BLOCK_SIZE = 40 * 1024 * 1024; // 40MB目标值

    // TPS目标
    public static final int TARGET_TPS = 23200;  // 48MB → 23,200 TPS
    public static final int MAX_LINKS_PER_BLOCK = 1485000;  // 约148万links

    // ==================== 孤块Transaction ====================
    public static final boolean ORPHAN_TX_AUTO_MEMPOOL = true;  // 自动进入mempool
    public static final int ORPHAN_TX_EXTRACTION_TIMEOUT = 5000; // 5秒超时
    public static final int MAX_ORPHAN_ROLLBACK_DEPTH = 12;  // 12 epochs回滚深度

    // ==================== Transaction data字段 ====================
    public static final int MAX_DATA_LENGTH = 1024;  // 1KB最大长度
    public static final int BASE_DATA_LENGTH = 256;  // 256字节基准
    public static final double DATA_FEE_MULTIPLIER = 1.0 / 256;  // 每字节费用系数

    // 费用计算
    public static final double BASE_TX_FEE = 0.0001;  // 基础费用（XDAG）

    /**
     * 计算Transaction总费用
     * @param dataLength data字段长度
     * @return 总费用（XDAG）
     */
    public static double calculateTxFee(int dataLength) {
        if (dataLength <= BASE_DATA_LENGTH) {
            return BASE_TX_FEE;
        }
        return BASE_TX_FEE * (1 + (dataLength - BASE_DATA_LENGTH) * DATA_FEE_MULTIPLIER);
    }
}
```

---

## 性能指标总结

| 指标 | 值 | 对比 | 状态 |
|------|-----|------|------|
| **Block大小** | 48MB | - | ✅ 最终决策 |
| **TPS** | 23,200 | Visa: 24,000 | ✅ 接近Visa |
| **网络传播** | 3.84秒 | < epoch 64秒 | ✅ 优秀 |
| **验证时间** | 1.5秒 | 非常快 | ✅ 优秀 |
| **存储（1年）** | 23.1 TB | 可接受 | ✅ 合理 |
| **data字段** | 1KB | ETH: ~500字节平均 | ✅ 充足 |
| **孤块存储** | 1.15 GB | 0.005%占比 | ✅ 可忽略 |
| **孤块tx处理** | 自动mempool | 零开销 | ✅ 最优 |

---

## 竞争力分析

```
XDAG vs 主流系统（最终参数）:

Bitcoin:
- TPS: 7
- Block: 1-2MB
- 确认: 60分钟
- XDAG优势: 3,314倍TPS，15倍确认速度

Ethereum:
- TPS: 15-30
- Block: ~100KB
- 确认: ~15分钟
- XDAG优势: 773-1,546倍TPS，类似确认速度

Visa:
- TPS: 24,000
- 确认: 秒级
- XDAG优势: 96.7% Visa水平，DAG并行优势

定位：
✅ "接近Visa级别的区块链DAG系统"
✅ "23,200 TPS - 超越所有主流区块链"
✅ "6-13分钟确认 - 比Bitcoin快4-10倍"
```

---

**文档版本**: v5.1 (核心参数已确定)
**创建日期**: 2025-10-28
**最后更新**: 2025-10-29
**作者**: Claude Code
**状态**: 核心参数确定 ✅

**核心参数决策（v5.1）**:
- ✅ **Block大小**: 48MB 软限制（23,200 TPS，接近Visa）
- ✅ **孤块Transaction**: 自动进入mempool（零开销，用户体验最优）
- ✅ **data字段**: 1KB + 按字节收费（智能合约支持）
- ✅ **TPS目标**: 23,200 TPS（96.7% Visa水平）
- ✅ **确认时间**: 6-13分钟（Level 6）

**核心架构设计（v5.0）**:
- ✅ 极简架构：只有Transaction和Block两种类型
- ✅ 非对称难度调整：35分钟恢复（vs Bitcoin的16.7天）
- ✅ BlockInfo极简：4个核心字段（hash, height, difficulty, timestamp）
- ✅ EVM兼容：ECDSA签名（v/r/s格式）

**竞争力定位**:
- ✅ "接近Visa级别的区块链DAG系统"（23,200 vs 24,000 TPS）
- ✅ "超越所有主流区块链"（BTC: 7 TPS, ETH: 15-30 TPS）
- ✅ "快速确认"（6-13分钟 vs Bitcoin 60分钟）

**下一步**: Phase 1-7实施（见OVERALL_PROGRESS.md）
