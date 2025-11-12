# XDAG DAG 同步保护机制

## 核心问题

在DAG同步过程中，可能遇到以下攻击或异常情况：

### 1. 循环引用（Cycle Reference）
```
Block A -> Block B -> Block C -> Block A
```
递归同步时会死循环！

### 2. 恶意深度引用（Deep Reference Attack）
```
Main Block -> Block1 -> Block2 -> ... -> Block100000
```
一个主块引用了极深的DAG，导致同步资源耗尽。

### 3. 重复引用（Duplicate References）
```
Block A -> Block X
Block B -> Block X
Block C -> Block X
...
```
同一个块被多次引用，可能导致重复处理。

### 4. 无效引用（Invalid Reference）
```
Block A -> Non-existent Block
```
引用不存在的块，导致同步失败。

### 5. 时间异常（Time Anomaly）
```
Block(time=2025) -> Block(time=2020)
```
引用未来或过去太远的块，可能是攻击。

## 解决方案：多层防护机制

### Layer 1: 访问集合（Visited Set）

**防止循环引用和重复处理**

```java
public class SafeDAGTraversal {

    /**
     * 安全的DAG遍历（防循环）
     */
    public void traverseDAG(DagNode startNode, Consumer<DagNode> processor) {
        Set<Bytes32> visited = new HashSet<>();
        Queue<DagNode> queue = new LinkedList<>();

        queue.add(startNode);
        visited.add(startNode.getHash());

        while (!queue.isEmpty()) {
            DagNode node = queue.poll();

            // 处理节点
            processor.accept(node);

            // 遍历所有引用
            for (DagLink link : getAllLinks(node)) {
                Bytes32 targetHash = link.getTargetHash();

                // 防护1: 检查是否已访问（防循环）
                if (visited.contains(targetHash)) {
                    continue; // 跳过已访问的块
                }

                // 防护2: 检查是否超过限制
                if (visited.size() >= MAX_TRAVERSE_BLOCKS) {
                    log.warn("DAG traversal exceeded limit: {}", MAX_TRAVERSE_BLOCKS);
                    return;
                }

                // 加载并添加到队列
                DagNode targetNode = blockStore.get(targetHash);
                if (targetNode != null) {
                    queue.add(targetNode);
                    visited.add(targetHash);
                }
            }
        }
    }

    private static final int MAX_TRAVERSE_BLOCKS = 100000;

    private List<DagLink> getAllLinks(DagNode node) {
        List<DagLink> links = new ArrayList<>();
        links.addAll(node.getInputs());
        links.addAll(node.getOutputs());
        return links;
    }
}
```

### Layer 2: 深度限制（Depth Limit）

**防止恶意深度引用**

```java
public class DepthLimitedTraversal {

    /**
     * 带深度限制的递归遍历
     */
    public void traverseWithDepthLimit(
        DagNode node,
        Set<Bytes32> visited,
        int currentDepth,
        int maxDepth
    ) {
        // 防护1: 循环检测
        if (visited.contains(node.getHash())) {
            return;
        }

        // 防护2: 深度限制
        if (currentDepth > maxDepth) {
            log.warn("DAG traversal exceeded max depth: {}", maxDepth);
            return;
        }

        visited.add(node.getHash());

        // 处理当前节点
        processNode(node);

        // 递归处理子节点
        for (DagLink link : getAllLinks(node)) {
            DagNode childNode = blockStore.get(link.getTargetHash());
            if (childNode != null) {
                traverseWithDepthLimit(childNode, visited, currentDepth + 1, maxDepth);
            }
        }
    }

    // 推荐参数
    private static final int MAX_RECURSION_DEPTH = 1000; // 最大递归深度
}
```

### Layer 3: 时间窗口限制（Time Window）

**防止时间异常引用**

```java
public class TimeWindowValidation {

    /**
     * 验证块引用的时间合理性
     */
    public boolean isValidTimeReference(DagNode parent, DagNode child) {
        long parentTime = parent.getTimestamp();
        long childTime = child.getTimestamp();

        // 防护1: 子块不能在父块之后太久
        if (childTime > parentTime + MAX_FUTURE_REFERENCE) {
            log.warn("Invalid future reference: parent={}, child={}",
                parentTime, childTime);
            return false;
        }

        // 防护2: 子块不能在父块之前太久
        if (childTime < parentTime - MAX_PAST_REFERENCE) {
            log.warn("Invalid past reference: parent={}, child={}",
                parentTime, childTime);
            return false;
        }

        return true;
    }

    // 参数建议
    private static final long MAX_FUTURE_REFERENCE = 64 * 100;  // 100 epochs ≈ 1.7小时
    private static final long MAX_PAST_REFERENCE = 64 * 10000;  // 10000 epochs ≈ 7天
}
```

### Layer 4: 引用计数限制（Reference Count Limit）

**防止单个块被过度引用**

```java
public class ReferenceLimitValidation {

    /**
     * 验证块的引用数量
     */
    public boolean validateBlockReferences(DagNode block) {
        int inputCount = block.getInputs().size();
        int outputCount = block.getOutputs().size();

        // 防护1: inputs数量限制
        if (inputCount > MAX_INPUTS) {
            log.warn("Block has too many inputs: {}", inputCount);
            return false;
        }

        // 防护2: outputs数量限制
        if (outputCount > MAX_OUTPUTS) {
            log.warn("Block has too many outputs: {}", outputCount);
            return false;
        }

        // 防护3: 总引用数量限制
        if (inputCount + outputCount > MAX_TOTAL_LINKS) {
            log.warn("Block has too many links: {}", inputCount + outputCount);
            return false;
        }

        return true;
    }

    // 参数建议
    private static final int MAX_INPUTS = 16;   // 最多16个inputs
    private static final int MAX_OUTPUTS = 16;  // 最多16个outputs
    private static final int MAX_TOTAL_LINKS = 20; // 总共不超过20个链接
}
```

### Layer 5: 超时保护（Timeout Protection）

**防止同步过程无限挂起**

```java
public class TimeoutProtectedSync {

    /**
     * 带超时的DAG同步
     */
    public void syncDAGWithTimeout(DagNode mainBlock, Duration timeout) {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Future<?> future = executor.submit(() -> {
            try {
                syncDAG(mainBlock);
            } catch (Exception e) {
                log.error("DAG sync failed", e);
            }
        });

        try {
            // 等待完成或超时
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("DAG sync timeout after {}ms", timeout.toMillis());
            future.cancel(true);
        } catch (Exception e) {
            log.error("DAG sync error", e);
        } finally {
            executor.shutdown();
        }
    }

    // 推荐超时时间
    private static final Duration SYNC_TIMEOUT = Duration.ofMinutes(10); // 10分钟
}
```

## 完整的安全同步实现

### 安全的固化流程

```java
public class SafeChainSolidification {

    // 配置参数
    private static final int MAX_SOLIDIFY_BLOCKS = 100000;    // 最多固化10万个块
    private static final int MAX_RECURSION_DEPTH = 1000;      // 最大递归深度
    private static final long MAX_TIME_WINDOW = 64 * 10000;   // 时间窗口7天
    private static final int MAX_LINKS_PER_BLOCK = 20;        // 每个块最多20个链接
    private static final Duration SOLIDIFY_TIMEOUT = Duration.ofMinutes(30);

    /**
     * 安全的主块固化
     */
    public void solidifyMainBlockSafely(DagNode mainBlock) {
        // 防护1: 超时保护
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            solidifyMainBlockInternal(mainBlock);
        });

        try {
            future.get(SOLIDIFY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.error("Solidification timeout for block: {}", mainBlock.getHash());
            future.cancel(true);
            throw new SolidificationException("Timeout", e);
        } catch (Exception e) {
            log.error("Solidification failed", e);
            throw new SolidificationException("Failed", e);
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 内部固化逻辑（带多重防护）
     */
    private void solidifyMainBlockInternal(DagNode mainBlock) {
        Set<Bytes32> visited = new HashSet<>();
        Queue<SolidifyTask> queue = new LinkedList<>();

        // 初始任务
        queue.add(new SolidifyTask(mainBlock, 0));
        visited.add(mainBlock.getHash());

        while (!queue.isEmpty()) {
            SolidifyTask task = queue.poll();
            DagNode node = task.node;
            int depth = task.depth;

            // 防护1: 数量限制
            if (visited.size() > MAX_SOLIDIFY_BLOCKS) {
                log.warn("Solidification exceeded max blocks: {}", MAX_SOLIDIFY_BLOCKS);
                break;
            }

            // 防护2: 深度限制
            if (depth > MAX_RECURSION_DEPTH) {
                log.warn("Solidification exceeded max depth: {}", MAX_RECURSION_DEPTH);
                continue;
            }

            // 防护3: 验证块有效性
            if (!validateBlock(node)) {
                log.warn("Invalid block during solidification: {}", node.getHash());
                continue;
            }

            // 固化当前块
            markAsFinalized(node.getHash());

            // 遍历所有引用
            for (DagLink link : getAllLinks(node)) {
                Bytes32 targetHash = link.getTargetHash();

                // 防护4: 跳过已访问的块
                if (visited.contains(targetHash)) {
                    continue;
                }

                // 加载目标块
                DagNode targetNode = blockStore.get(targetHash);
                if (targetNode == null) {
                    log.warn("Referenced block not found: {}", targetHash);
                    continue;
                }

                // 防护5: 时间窗口验证
                if (!isValidTimeReference(node, targetNode)) {
                    log.warn("Invalid time reference: {} -> {}",
                        node.getHash(), targetHash);
                    continue;
                }

                // 添加到队列
                queue.add(new SolidifyTask(targetNode, depth + 1));
                visited.add(targetHash);
            }
        }

        log.info("Solidification completed: {} blocks processed", visited.size());
    }

    /**
     * 验证块的有效性
     */
    private boolean validateBlock(DagNode block) {
        // 1. 验证引用数量
        int linkCount = block.getInputs().size() + block.getOutputs().size();
        if (linkCount > MAX_LINKS_PER_BLOCK) {
            return false;
        }

        // 2. 验证hash
        Bytes32 computedHash = calculateBlockHash(block);
        if (!computedHash.equals(block.getHash())) {
            return false;
        }

        // 3. 验证签名（可选，已固化的块通常已验证过）
        // if (!verifySignature(block)) {
        //     return false;
        // }

        return true;
    }

    /**
     * 验证时间引用合理性
     */
    private boolean isValidTimeReference(DagNode parent, DagNode child) {
        long timeDiff = Math.abs(parent.getTimestamp() - child.getTimestamp());
        return timeDiff <= MAX_TIME_WINDOW;
    }

    // 辅助类
    private static class SolidifyTask {
        final DagNode node;
        final int depth;

        SolidifyTask(DagNode node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }
}
```

### 安全的DAG同步

```java
public class SafeDAGSync {

    /**
     * 安全的epoch同步（带保护）
     */
    public void syncEpochSafely(long epoch) {
        // 1. 请求epoch的块hash列表
        EpochBlocksReply reply = p2pClient.requestEpochBlocks(peerId, epoch);

        // 防护1: 数量限制
        if (reply.getHashes().size() > MAX_BLOCKS_PER_EPOCH) {
            log.warn("Epoch {} has too many blocks: {}", epoch, reply.getHashes().size());
            throw new SyncException("Too many blocks in epoch");
        }

        // 2. 过滤已有的块
        List<Bytes32> missingHashes = reply.getHashes().stream()
            .filter(hash -> !blockStore.hasBlock(hash))
            .limit(MAX_BLOCKS_PER_REQUEST) // 防护2: 限制单次请求数量
            .collect(Collectors.toList());

        if (missingHashes.isEmpty()) {
            return;
        }

        // 3. 批量请求缺失的块
        BlocksReply blocksReply = p2pClient.requestBlocks(peerId, missingHashes);

        // 4. 验证和保存
        for (DagNode block : blocksReply.getBlocks()) {
            // 防护3: 验证块
            if (!validateBlock(block)) {
                log.warn("Invalid block received: {}", block.getHash());
                continue;
            }

            // 防护4: 验证epoch匹配
            if (block.getEpoch() != epoch) {
                log.warn("Block epoch mismatch: expected={}, actual={}",
                    epoch, block.getEpoch());
                continue;
            }

            // 防护5: 验证引用数量
            if (!validateReferences(block)) {
                log.warn("Block has invalid references: {}", block.getHash());
                continue;
            }

            // 保存块
            blockStore.save(block);

            // 应用到链
            try {
                blockchain.tryToConnect(block);
            } catch (Exception e) {
                log.error("Failed to connect block: {}", block.getHash(), e);
            }
        }
    }

    private boolean validateBlock(DagNode block) {
        // 验证hash
        if (!verifyHash(block)) return false;

        // 验证PoW
        if (!verifyPoW(block)) return false;

        // 验证签名
        if (!verifySignature(block)) return false;

        return true;
    }

    private boolean validateReferences(DagNode block) {
        int totalLinks = block.getInputs().size() + block.getOutputs().size();
        return totalLinks <= MAX_LINKS_PER_BLOCK;
    }

    // 配置参数
    private static final int MAX_BLOCKS_PER_EPOCH = 10000;    // 每个epoch最多1万个块
    private static final int MAX_BLOCKS_PER_REQUEST = 1000;   // 单次最多请求1000个块
    private static final int MAX_LINKS_PER_BLOCK = 20;        // 每个块最多20个链接
}
```

## 参数配置建议

### 保守配置（主网推荐）

```java
public class ConservativeSyncConfig {
    // 遍历限制
    public static final int MAX_TRAVERSE_BLOCKS = 50000;      // 最多遍历5万个块
    public static final int MAX_RECURSION_DEPTH = 500;        // 最大递归深度500

    // 时间限制
    public static final long MAX_TIME_WINDOW = 64 * 5000;     // 时间窗口≈3.5天

    // 引用限制
    public static final int MAX_INPUTS = 8;                   // 最多8个inputs
    public static final int MAX_OUTPUTS = 8;                  // 最多8个outputs
    public static final int MAX_TOTAL_LINKS = 12;             // 总共不超过12个链接

    // 同步限制
    public static final int MAX_BLOCKS_PER_EPOCH = 5000;      // 每epoch最多5000块
    public static final int MAX_BLOCKS_PER_REQUEST = 500;     // 单次请求500块

    // 超时配置
    public static final Duration SYNC_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration SOLIDIFY_TIMEOUT = Duration.ofMinutes(15);
}
```

### 激进配置（测试网）

```java
public class AggressiveSyncConfig {
    // 遍历限制
    public static final int MAX_TRAVERSE_BLOCKS = 200000;     // 最多遍历20万个块
    public static final int MAX_RECURSION_DEPTH = 2000;       // 最大递归深度2000

    // 时间限制
    public static final long MAX_TIME_WINDOW = 64 * 20000;    // 时间窗口≈14天

    // 引用限制
    public static final int MAX_INPUTS = 16;                  // 最多16个inputs
    public static final int MAX_OUTPUTS = 16;                 // 最多16个outputs
    public static final int MAX_TOTAL_LINKS = 24;             // 总共不超过24个链接

    // 同步限制
    public static final int MAX_BLOCKS_PER_EPOCH = 20000;     // 每epoch最多2万块
    public static final int MAX_BLOCKS_PER_REQUEST = 2000;    // 单次请求2000块

    // 超时配置
    public static final Duration SYNC_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration SOLIDIFY_TIMEOUT = Duration.ofHours(1);
}
```

## 监控和告警

### 异常检测

```java
public class DAGSyncMonitor {

    private final Counter cycleDetections = Counter.build()
        .name("dag_sync_cycles_detected")
        .help("Number of cycles detected during DAG sync")
        .register();

    private final Counter depthExceeded = Counter.build()
        .name("dag_sync_depth_exceeded")
        .help("Number of times depth limit was exceeded")
        .register();

    private final Histogram traversalSize = Histogram.build()
        .name("dag_sync_traversal_size")
        .help("Number of blocks traversed in DAG sync")
        .register();

    /**
     * 记录异常
     */
    public void recordAnomaly(String type, DagNode block) {
        log.warn("DAG sync anomaly detected: type={}, block={}", type, block.getHash());

        switch (type) {
            case "CYCLE":
                cycleDetections.inc();
                alertAdministrator("Cycle detected in block: " + block.getHash());
                break;
            case "DEPTH_EXCEEDED":
                depthExceeded.inc();
                alertAdministrator("Depth exceeded for block: " + block.getHash());
                break;
            case "TIME_ANOMALY":
                alertAdministrator("Time anomaly in block: " + block.getHash());
                break;
        }
    }
}
```

## 总结

### 多层防护机制

| 层级 | 防护措施 | 防止的问题 |
|------|---------|-----------|
| **Layer 1** | Visited Set | 循环引用、重复处理 |
| **Layer 2** | Depth Limit | 恶意深度引用 |
| **Layer 3** | Time Window | 时间异常引用 |
| **Layer 4** | Reference Count | 过度引用 |
| **Layer 5** | Timeout | 无限挂起 |

### 关键参数

```java
// 推荐配置（主网）
MAX_TRAVERSE_BLOCKS = 50000      // 最多5万个块
MAX_RECURSION_DEPTH = 500        // 最大深度500
MAX_TIME_WINDOW = 64 * 5000      // 时间窗口3.5天
MAX_LINKS_PER_BLOCK = 12         // 每块最多12个链接
SOLIDIFY_TIMEOUT = 15分钟        // 固化超时
```

### 实施优先级

1. **Phase 1**（必须）：Visited Set + 数量限制
2. **Phase 2**（必须）：深度限制 + 超时保护
3. **Phase 3**（推荐）：时间窗口验证
4. **Phase 4**（推荐）：引用计数限制
5. **Phase 5**（可选）：监控和告警

---

**版本**: v1.0
**创建时间**: 2025-01
**最后更新**: 2025-10-29
**作者**: Claude Code
**状态**: 设计完成
