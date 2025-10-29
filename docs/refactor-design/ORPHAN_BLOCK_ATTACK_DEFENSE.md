# 孤块攻击防御机制

**问题发现日期**: 2025-10-29
**严重程度**: 🔴 高危（Critical）
**状态**: 已设计完整防御方案 ✅

---

## 🚨 问题描述

### 攻击场景

假设网络有N个节点：

```
每个epoch（64秒）：
- N个节点各产生1个候选块（每个48MB）
- 只有1个成为主块
- N-1个成为孤块

孤块存储（12 epochs回滚深度）：
= (N-1) orphans/epoch × 48MB × 12 epochs

举例（1000个节点）：
= 999 × 48MB × 12
= 575 GB！❌ 灾难性的存储成本
```

### 恶意攻击向量

```java
// 恶意节点可以：
1. 创建大量候选块（每个48MB）
2. 填充随机Transaction hash（不存在的）
3. 填充随机Block hash（不存在的）
4. 每64秒产生1个恶意候选块
5. 强制其他节点：
   - 存储这些垃圾孤块
   - 验证这些无效引用
   - 浪费网络带宽和存储
```

**这是严重的DoS攻击！**

---

## 🛡️ 多层防御方案

### 防御层次

```
Layer 1: PoW验证          → 防止低成本spam
Layer 2: 孤块数量限制      → 防止存储爆炸（关键！）
Layer 3: 引用有效性验证    → 防止无效引用
Layer 4: Block大小限制     → 防止超大block
Layer 5: 节点信誉系统      → 长期防御恶意节点
```

---

## 🔐 Layer 1: PoW验证（基础防御）

### 设计原则
候选块必须满足PoW难度要求才能被接受和存储

### 实现代码

```java
/**
 * 验证候选块的PoW
 */
public class CandidateBlockValidator {

    public boolean validatePoW(Block block) {
        // 1. 计算block hash
        Bytes32 hash = block.getHash();

        // 2. 获取block声明的difficulty
        UInt256 declaredDiff = block.getHeader().getDifficulty();

        // 3. 计算该epoch的预期difficulty
        long timestamp = block.getHeader().getTimestamp();
        UInt256 expectedDiff = difficultyCalculator.calculate(timestamp);

        // 4. 验证difficulty是否正确
        if (!declaredDiff.equals(expectedDiff)) {
            log.warn("Block difficulty mismatch: declared={}, expected={}",
                     declaredDiff, expectedDiff);
            return false;
        }

        // 5. 验证PoW是否满足difficulty
        if (hash.compareTo(declaredDiff) > 0) {
            log.warn("Block PoW invalid: hash={} > difficulty={}",
                     hash, declaredDiff);
            return false;
        }

        // 6. 验证timestamp在合理范围内
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - timestamp) > MAX_TIMESTAMP_DRIFT) {
            log.warn("Block timestamp out of range: {} vs {}", timestamp, now);
            return false;
        }

        return true;
    }
}
```

### 防御效果

```
攻击成本：
- 产生1个48MB孤块需要真实算力
- 假设当前difficulty需要平均10秒PoW
- 恶意节点要产生1000个孤块：
  = 1000 blocks × 10秒 = 10,000秒 ≈ 2.8小时

结论：
✅ 提高了攻击成本
❌ 但仍然没有限制孤块数量！
```

---

## 🔐 Layer 2: 孤块数量限制（关键防御！）

### 设计原则
**每个epoch只保留hash最小的K个孤块**（K=10-20）

### 核心洞察

```
关键认识：
- 孤块的价值 = 其引用的Transaction
- 保留所有孤块是不必要的
- 只需保留"最有可能在重组时被使用"的孤块

选择标准：
- hash最小 = PoW难度最好
- 说明该节点投入了最多算力
- 最有可能是诚实节点
- 其引用的Transaction最有价值
```

### 实现代码

```java
/**
 * 孤块存储（限制数量版本）
 */
public class OrphanBlockStore {

    // 配置参数
    private static final int MAX_ORPHANS_PER_EPOCH = 10;  // 每个epoch最多10个孤块
    private static final int MAX_ROLLBACK_DEPTH = 12;     // 12 epochs回滚深度

    // 存储结构
    private final Map<Long, TreeSet<Block>> orphansByEpoch = new ConcurrentHashMap<>();

    /**
     * 添加孤块（自动淘汰劣质孤块）
     */
    public boolean addOrphanBlock(Block block) {
        long epoch = XdagTime.getEpoch(block.getHeader().getTimestamp());

        // 获取或创建该epoch的孤块集合（按hash排序，hash小的在前）
        TreeSet<Block> orphans = orphansByEpoch.computeIfAbsent(epoch,
            k -> new TreeSet<>(Comparator.comparing(Block::getHash))
        );

        synchronized (orphans) {
            // 添加新孤块
            orphans.add(block);

            // 如果超过限制，删除hash最大的（难度最差的）
            if (orphans.size() > MAX_ORPHANS_PER_EPOCH) {
                Block worst = orphans.pollLast();  // 移除hash最大的
                log.info("Evicted worst orphan (hash={}), kept best {} orphans",
                         worst.getHash(), MAX_ORPHANS_PER_EPOCH);
                return false;  // 该block被淘汰
            }
        }

        log.debug("Added orphan block (hash={}) to epoch {}, total: {}",
                  block.getHash(), epoch, orphans.size());
        return true;
    }

    /**
     * 获取指定epoch的所有孤块
     */
    public List<Block> getOrphanBlocks(long epoch) {
        TreeSet<Block> orphans = orphansByEpoch.get(epoch);
        if (orphans == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(orphans);
    }

    /**
     * 清理旧孤块
     */
    public void pruneOldOrphans(long currentMainHeight) {
        long pruneBeforeEpoch = currentMainHeight - MAX_ROLLBACK_DEPTH;

        Iterator<Map.Entry<Long, TreeSet<Block>>> iterator =
            orphansByEpoch.entrySet().iterator();

        int prunedCount = 0;
        while (iterator.hasNext()) {
            Map.Entry<Long, TreeSet<Block>> entry = iterator.next();
            if (entry.getKey() < pruneBeforeEpoch) {
                prunedCount += entry.getValue().size();
                iterator.remove();
            }
        }

        if (prunedCount > 0) {
            log.info("Pruned {} orphan blocks older than epoch {}",
                     prunedCount, pruneBeforeEpoch);
        }
    }
}
```

### 防御效果

```
无防御：
1000个节点 × 48MB × 12 epochs = 575 GB ❌

有防御：
10个孤块/epoch × 48MB × 12 epochs = 5.76 GB ✅

存储减少：575 GB → 5.76 GB（降低100倍！）
```

### 配置调优

```java
// 孤块数量配置
public class OrphanBlockConfig {

    // 保守配置（网络初期）
    public static final int CONSERVATIVE_MAX_ORPHANS = 5;  // 每epoch最多5个

    // 平衡配置（推荐）
    public static final int BALANCED_MAX_ORPHANS = 10;     // 每epoch最多10个

    // 激进配置（高算力分散网络）
    public static final int AGGRESSIVE_MAX_ORPHANS = 20;   // 每epoch最多20个

    /**
     * 根据网络状况动态调整
     */
    public static int calculateMaxOrphans(NetworkMetrics metrics) {
        int activeNodes = metrics.getActiveNodeCount();

        if (activeNodes < 100) {
            return CONSERVATIVE_MAX_ORPHANS;  // 小网络，孤块少
        } else if (activeNodes < 1000) {
            return BALANCED_MAX_ORPHANS;      // 中等网络
        } else {
            return AGGRESSIVE_MAX_ORPHANS;    // 大网络，算力分散
        }
    }
}
```

---

## 🔐 Layer 3: 引用有效性验证（防止无效引用）

### 设计原则
验证block引用的Transaction/Block是否存在，拒绝大量无效引用的block

### 实现代码

```java
/**
 * 验证block引用的有效性
 */
public class LinkValidationService {

    // 配置参数
    private static final double MAX_INVALID_LINK_RATIO = 0.1;  // 最多10%无效引用
    private static final int MAX_LINKS_TO_VALIDATE = 10000;    // 最多验证1万个links

    public ValidationResult validateLinks(Block block) {
        List<Link> links = block.getBody().getLinks();

        int validLinks = 0;
        int invalidLinks = 0;
        int checkedLinks = 0;

        // 采样验证（对于大block，不需要验证所有links）
        int step = Math.max(1, links.size() / MAX_LINKS_TO_VALIDATE);

        for (int i = 0; i < links.size(); i += step) {
            Link link = links.get(i);
            checkedLinks++;

            if (link.getType() == LinkType.TRANSACTION) {
                // 验证Transaction是否存在
                if (txStore.exists(link.getTargetHash()) ||
                    mempool.contains(link.getTargetHash())) {
                    validLinks++;
                } else {
                    invalidLinks++;
                    log.debug("Invalid tx link: {}", link.getTargetHash());
                }
            } else {
                // 验证Block是否存在
                if (blockStore.exists(link.getTargetHash()) ||
                    orphanStore.exists(link.getTargetHash())) {
                    validLinks++;
                } else {
                    invalidLinks++;
                    log.debug("Invalid block link: {}", link.getTargetHash());
                }
            }
        }

        // 计算无效引用比例
        double invalidRatio = (double) invalidLinks / (validLinks + invalidLinks);

        ValidationResult result = new ValidationResult();
        result.valid = invalidRatio <= MAX_INVALID_LINK_RATIO;
        result.validLinks = validLinks;
        result.invalidLinks = invalidLinks;
        result.checkedLinks = checkedLinks;
        result.invalidRatio = invalidRatio;

        if (!result.valid) {
            log.warn("Block has too many invalid links: {}/{} = {:.1f}% (max {:.1f}%)",
                     invalidLinks, checkedLinks, invalidRatio * 100,
                     MAX_INVALID_LINK_RATIO * 100);
        }

        return result;
    }

    public static class ValidationResult {
        public boolean valid;
        public int validLinks;
        public int invalidLinks;
        public int checkedLinks;
        public double invalidRatio;
    }
}
```

### 防御效果

```
恶意block：
- 引用100万个随机Transaction hash（不存在）
- 无效引用率：100%
- 被拒绝 ✅

正常block（网络延迟）：
- 引用100万个Transaction
- 其中5%暂时不可见（网络同步延迟）
- 无效引用率：5% < 10%阈值
- 被接受 ✅

容错性：
✅ 允许10%容错（网络延迟、同步延迟）
✅ 拒绝恶意block（大量无效引用）
```

---

## 🔐 Layer 4: Block大小限制（防止超大block）

### 实现代码

```java
/**
 * 验证block大小
 */
public class BlockSizeValidator {

    public boolean validateSize(Block block) {
        // 1. 验证links数量
        int linkCount = block.getBody().getLinks().size();
        if (linkCount > MAX_LINKS_PER_BLOCK) {
            log.warn("Block exceeds max links: {} > {}",
                     linkCount, MAX_LINKS_PER_BLOCK);
            return false;
        }

        // 2. 验证序列化后的大小
        byte[] serialized = serialize(block);
        int blockSize = serialized.length;
        if (blockSize > MAX_BLOCK_SIZE) {
            log.warn("Block exceeds max size: {} > {} bytes",
                     blockSize, MAX_BLOCK_SIZE);
            return false;
        }

        return true;
    }
}
```

---

## 🔐 Layer 5: 节点信誉系统（长期防御）

### 设计原则
记录节点产生的block质量，降低/拒绝恶意节点的block

### 实现代码

```java
/**
 * 节点信誉管理系统
 */
public class NodeReputationManager {

    private final Map<InetAddress, NodeReputation> reputations = new ConcurrentHashMap<>();

    // 配置参数
    private static final double MALICIOUS_THRESHOLD = 0.5;      // 50%坏块率
    private static final int MIN_BLOCKS_FOR_REPUTATION = 100;   // 最少100个blocks
    private static final long REPUTATION_DECAY_TIME = 7 * 24 * 3600; // 7天衰减

    /**
     * 记录节点产生的block质量
     */
    public void recordBlock(InetAddress node, Block block, BlockQuality quality) {
        NodeReputation rep = reputations.computeIfAbsent(node, k -> new NodeReputation());

        synchronized (rep) {
            if (quality == BlockQuality.GOOD) {
                rep.goodBlocks++;
            } else if (quality == BlockQuality.BAD) {
                rep.badBlocks++;
            }
            rep.lastSeen = System.currentTimeMillis();

            // 判断是否为恶意节点
            if (rep.totalBlocks() >= MIN_BLOCKS_FOR_REPUTATION) {
                double badRatio = (double) rep.badBlocks / rep.totalBlocks();
                if (badRatio >= MALICIOUS_THRESHOLD) {
                    rep.malicious = true;
                    log.warn("Node {} marked as malicious (bad ratio: {:.1f}%)",
                             node, badRatio * 100);
                }
            }
        }
    }

    /**
     * 是否应该接受该节点的block
     */
    public boolean shouldAcceptBlock(InetAddress node) {
        NodeReputation rep = reputations.get(node);
        if (rep == null) {
            return true;  // 新节点，接受
        }

        // 拒绝恶意节点的block
        if (rep.malicious) {
            log.debug("Rejected block from malicious node: {}", node);
            return false;
        }

        return true;
    }

    /**
     * 定期清理过期信誉记录
     */
    public void pruneOldReputations() {
        long now = System.currentTimeMillis();

        reputations.entrySet().removeIf(entry -> {
            NodeReputation rep = entry.getValue();
            long age = (now - rep.lastSeen) / 1000;
            return age > REPUTATION_DECAY_TIME && !rep.malicious;
        });
    }

    public static class NodeReputation {
        public int goodBlocks = 0;
        public int badBlocks = 0;
        public long lastSeen = 0;
        public boolean malicious = false;

        public int totalBlocks() {
            return goodBlocks + badBlocks;
        }
    }

    public enum BlockQuality {
        GOOD,   // 高质量block（大部分引用有效）
        BAD,    // 低质量block（大量无效引用）
    }
}
```

### 使用示例

```java
// 接收到候选block时
public void onCandidateBlockReceived(Block block, InetAddress source) {

    // Layer 5: 检查节点信誉
    if (!reputationManager.shouldAcceptBlock(source)) {
        log.warn("Rejected block from malicious node: {}", source);
        return;
    }

    // Layer 1: 验证PoW
    if (!powValidator.validatePoW(block)) {
        reputationManager.recordBlock(source, block, BlockQuality.BAD);
        return;
    }

    // Layer 4: 验证block大小
    if (!sizeValidator.validateSize(block)) {
        reputationManager.recordBlock(source, block, BlockQuality.BAD);
        return;
    }

    // Layer 3: 验证引用有效性
    ValidationResult linkResult = linkValidator.validateLinks(block);
    if (!linkResult.valid) {
        reputationManager.recordBlock(source, block, BlockQuality.BAD);
        return;
    }

    // Layer 2: 尝试添加到孤块存储（可能被淘汰）
    boolean added = orphanStore.addOrphanBlock(block);
    if (added) {
        reputationManager.recordBlock(source, block, BlockQuality.GOOD);
        log.info("Accepted orphan block from {}: {}", source, block.getHash());
    } else {
        log.debug("Orphan block evicted (worse than existing): {}", block.getHash());
    }
}
```

---

## 📊 防御效果总结

### 存储成本对比

| 方案 | 孤块数/epoch | 存储/12 epochs | 相对成本 |
|------|-------------|---------------|---------|
| **无防御** | 999 | 575 GB | 100x ❌ |
| **MAX=5** | 5 | 2.88 GB | 1x ✅ |
| **MAX=10** | 10 | 5.76 GB | 2x ✅ |
| **MAX=20** | 20 | 11.5 GB | 4x ✅ |

### 安全性对比

| 防御层 | 攻击成本 | 存储减少 | 实施复杂度 |
|--------|---------|---------|-----------|
| **Layer 1: PoW** | 高（需算力） | 0 | 低 ✅ |
| **Layer 2: 数量限制** | 高（竞争） | 100倍 ✅ | 低 ✅ |
| **Layer 3: 引用验证** | 高（需真实数据） | - | 中 |
| **Layer 4: 大小限制** | - | - | 低 ✅ |
| **Layer 5: 信誉系统** | 极高（需新身份） | - | 高 |

---

## 🎯 推荐配置

### 保守配置（主网初期）

```java
public class ConservativeOrphanConfig {
    public static final int MAX_ORPHANS_PER_EPOCH = 5;      // 最多5个
    public static final int MAX_ROLLBACK_DEPTH = 12;        // 12 epochs
    public static final double MAX_INVALID_LINK_RATIO = 0.05; // 最多5%无效

    // 存储成本：5 × 48MB × 12 = 2.88 GB
}
```

### 平衡配置（推荐）

```java
public class BalancedOrphanConfig {
    public static final int MAX_ORPHANS_PER_EPOCH = 10;     // 最多10个
    public static final int MAX_ROLLBACK_DEPTH = 12;        // 12 epochs
    public static final double MAX_INVALID_LINK_RATIO = 0.1; // 最多10%无效

    // 存储成本：10 × 48MB × 12 = 5.76 GB
}
```

### 激进配置（大型网络）

```java
public class AggressiveOrphanConfig {
    public static final int MAX_ORPHANS_PER_EPOCH = 20;     // 最多20个
    public static final int MAX_ROLLBACK_DEPTH = 12;        // 12 epochs
    public static final double MAX_INVALID_LINK_RATIO = 0.15; // 最多15%无效

    // 存储成本：20 × 48MB × 12 = 11.5 GB
}
```

---

## 🔍 监控指标

### 需要监控的指标

```java
public class OrphanBlockMetrics {

    // 每个epoch的孤块数量
    public static final Gauge orphansPerEpoch = Gauge.build()
        .name("orphan_blocks_per_epoch")
        .help("Number of orphan blocks in each epoch")
        .register();

    // 被淘汰的孤块数量
    public static final Counter evictedOrphans = Counter.build()
        .name("orphan_blocks_evicted_total")
        .help("Total number of evicted orphan blocks")
        .register();

    // 无效引用比例
    public static final Histogram invalidLinkRatio = Histogram.build()
        .name("invalid_link_ratio")
        .help("Ratio of invalid links in received blocks")
        .buckets(0.0, 0.05, 0.1, 0.2, 0.5, 1.0)
        .register();

    // 恶意节点数量
    public static final Gauge maliciousNodes = Gauge.build()
        .name("malicious_nodes_total")
        .help("Number of nodes marked as malicious")
        .register();
}
```

### 告警规则

```
# Prometheus告警规则

# 孤块数量异常高
- alert: TooManyOrphansPerEpoch
  expr: orphan_blocks_per_epoch > 50
  for: 5m
  annotations:
    summary: "Abnormally high number of orphan blocks"
    description: "{{ $value }} orphan blocks in epoch (expected < 20)"

# 大量block被淘汰
- alert: HighOrphanEvictionRate
  expr: rate(orphan_blocks_evicted_total[5m]) > 10
  for: 5m
  annotations:
    summary: "High orphan block eviction rate"
    description: "{{ $value }} orphans evicted per second"

# 发现恶意节点
- alert: MaliciousNodesDetected
  expr: malicious_nodes_total > 0
  for: 1m
  annotations:
    summary: "Malicious nodes detected"
    description: "{{ $value }} nodes marked as malicious"
```

---

## ✅ 实施建议

### Phase 1: 立即实施（主网前）

**必须实施**：
1. ✅ Layer 1: PoW验证（基础安全）
2. ✅ Layer 2: 孤块数量限制（关键防御）
3. ✅ Layer 4: Block大小限制（基础安全）

**配置**：
- MAX_ORPHANS_PER_EPOCH = 10（平衡配置）
- MAX_ROLLBACK_DEPTH = 12
- MAX_BLOCK_SIZE = 48MB

### Phase 2: 主网初期（1-3个月）

**增强防御**：
1. ✅ Layer 3: 引用有效性验证
2. ✅ 监控指标和告警

**调优**：
- 根据实际网络状况调整MAX_ORPHANS_PER_EPOCH
- 监控孤块淘汰率和无效引用率

### Phase 3: 主网成熟（3个月+）

**长期防御**：
1. ✅ Layer 5: 节点信誉系统
2. ✅ 动态参数调整

---

## 📝 总结

### 核心防御策略

```
关键认识：
✅ 不需要保留所有孤块
✅ 只保留"最有价值"的孤块（hash最小 = 难度最好）
✅ 恶意孤块会被自动淘汰

防御效果：
✅ 存储成本：575 GB → 5.76 GB（降低100倍）
✅ 攻击成本：极高（需要真实算力 + 竞争）
✅ 网络安全：多层防御，深度防护

推荐配置：
✅ MAX_ORPHANS_PER_EPOCH = 10（平衡）
✅ MAX_INVALID_LINK_RATIO = 0.1（容错）
✅ 节点信誉系统（长期）
```

---

**文档版本**: v1.0
**创建日期**: 2025-10-29
**作者**: Claude Code
**状态**: 设计完成，待实施 ✅
