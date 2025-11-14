# XDAG 挖矿架构重构方案

**日期**: 2025-01-14
**状态**: 提案 (Proposal)
**作者**: Architecture Team

---

## 问题陈述

当前 `xdagj` 节点将矿池功能（MiningManager）混入节点核心，导致：
- 节点职责不清晰
- 无法独立部署矿池
- 无法独立开发矿工
- 与标准区块链架构不符

## 架构原则

**单一职责原则（Single Responsibility Principle）**:
- 节点 → 区块链核心
- 矿池 → 挖矿协调
- 矿工 → PoW 计算

---

## 正确的三层架构

```
┌─────────────────┐
│   xdagj (Node)  │  ← 区块链节点
│                 │
│  • DagChain     │
│  • P2P Network  │
│  • Consensus    │
│  • RPC API      │
└────────┬────────┘
         │ RPC
         │
┌────────▼────────┐
│ xdagj-pool      │  ← 矿池服务器（新项目）
│                 │
│  • RPC Client   │
│  • Task Manager │
│  • Share Valid  │
│  • Stratum      │
└────────┬────────┘
         │ Stratum
         │
┌────────▼────────┐
│ xdagj-miner     │  ← 矿工程序（新项目）
│                 │
│  • Nonce Loop   │
│  • Hash Calc    │
│  • GPU/CPU      │
└─────────────────┘
```

---

## 重构计划

### 阶段 1: 定义清晰的接口边界

#### 1.1 节点 RPC API（xdagj 提供）

```java
// 节点应该提供的 RPC 接口
public interface NodeMiningRpcService {
    /**
     * 获取当前候选区块（供矿池使用）
     * 矿池定期调用此接口获取最新的挖矿任务
     */
    Block getCandidateBlock(String poolId);

    /**
     * 提交挖矿结果（矿池提交最佳区块）
     * 矿池找到满足难度的区块后提交给节点
     */
    BlockSubmitResult submitMinedBlock(Block block, String poolId);

    /**
     * 获取当前难度目标
     */
    UInt256 getCurrentDifficultyTarget();

    /**
     * 获取 RandomX 状态信息
     */
    RandomXInfo getRandomXInfo();
}
```

#### 1.2 矿池 Stratum API（xdagj-pool 提供）

```java
// 矿池应该提供的 Stratum 接口
public interface PoolStratumService {
    /**
     * 矿工登录
     */
    LoginResponse login(String workerId, String password);

    /**
     * 获取挖矿任务（矿工调用）
     */
    MiningJob getJob(String workerId);

    /**
     * 提交 share（矿工调用）
     */
    ShareSubmitResult submitShare(String workerId, String nonce, String jobId);

    /**
     * 获取矿工统计
     */
    WorkerStats getWorkerStats(String workerId);
}
```

---

### 阶段 2: 从 xdagj 移除矿池代码

#### 2.1 需要移除的组件

从 `xdagj` 项目中移除：
- ❌ `MiningManager.java`
- ❌ `ShareValidator.java`
- ❌ `BlockBroadcaster.java`
- ❌ `LocalMiner.java`
- ❌ `MiningTask.java`

#### 2.2 需要保留的组件

在 `xdagj` 项目中保留：
- ✅ `BlockGenerator.java` → 重命名为 `CandidateBlockGenerator.java`
- ✅ `PowAlgorithm.java` 接口
- ✅ `RandomXPow.java`
- ✅ `Sha256Pow.java`

#### 2.3 需要添加的组件

在 `xdagj` 项目中添加：
- ➕ `NodeMiningRpcService.java` - RPC 接口
- ➕ `MiningRpcServiceImpl.java` - RPC 实现
- ➕ `CandidateBlockCache.java` - 候选区块缓存

**实现示例**:

```java
package io.xdag.rpc.service;

import io.xdag.core.Block;
import io.xdag.core.DagChain;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.units.bigints.UInt256;

/**
 * Mining RPC Service - 为矿池提供的节点接口
 */
@Slf4j
public class MiningRpcServiceImpl implements NodeMiningRpcService {

    private final DagChain dagChain;
    private final CandidateBlockCache blockCache;

    public MiningRpcServiceImpl(DagChain dagChain) {
        this.dagChain = dagChain;
        this.blockCache = new CandidateBlockCache();
    }

    @Override
    public Block getCandidateBlock(String poolId) {
        // 生成候选区块（每 64 秒更新）
        Block candidate = dagChain.createCandidateBlock();

        // 缓存候选区块（用于验证提交）
        blockCache.put(candidate.getHash(), candidate);

        log.info("Provided candidate block to pool {}: hash={}",
                poolId, candidate.getHash().toHexString().substring(0, 16));

        return candidate;
    }

    @Override
    public BlockSubmitResult submitMinedBlock(Block block, String poolId) {
        log.info("Received mined block from pool {}: hash={}",
                poolId, block.getHash().toHexString().substring(0, 16));

        // 验证区块是否基于我们提供的候选区块
        if (!blockCache.contains(block.getHashWithoutNonce())) {
            return BlockSubmitResult.rejected("Unknown candidate block");
        }

        // 导入区块到链
        DagImportResult result = dagChain.tryToConnect(block);

        if (result.isSuccess()) {
            log.info("✓ Mined block accepted from pool {}", poolId);
            return BlockSubmitResult.accepted(block.getHash());
        } else {
            log.warn("✗ Mined block rejected from pool {}: {}",
                    poolId, result.getError());
            return BlockSubmitResult.rejected(result.getError());
        }
    }

    @Override
    public UInt256 getCurrentDifficultyTarget() {
        return dagChain.getChainStats().getBaseDifficultyTarget();
    }

    @Override
    public RandomXInfo getRandomXInfo() {
        // 返回 RandomX 状态信息
        return RandomXInfo.builder()
                .enabled(true)
                .currentEpoch(dagChain.getCurrentEpoch())
                .forkEpoch(/* fork epoch */)
                .build();
    }
}
```

---

### 阶段 3: 创建独立的矿池项目（xdagj-pool）

#### 3.1 项目结构

```
xdagj-pool/
├── src/main/java/io/xdag/pool/
│   ├── PoolServer.java              ← 主入口
│   ├── config/
│   │   └── PoolConfig.java          ← 矿池配置
│   ├── node/
│   │   ├── NodeRpcClient.java       ← 连接节点的 RPC 客户端
│   │   └── CandidateBlockFetcher.java
│   ├── stratum/
│   │   ├── StratumServer.java       ← Stratum 协议服务器
│   │   ├── StratumConnection.java
│   │   └── StratumProtocol.java
│   ├── worker/
│   │   ├── WorkerManager.java       ← 矿工管理
│   │   ├── WorkerSession.java
│   │   └── WorkerStats.java
│   ├── task/
│   │   ├── MiningTaskManager.java   ← 任务分配
│   │   ├── MiningTask.java
│   │   └── TaskDistributor.java
│   ├── share/
│   │   ├── ShareValidator.java      ← Share 验证（从 xdagj 迁移）
│   │   ├── ShareProcessor.java
│   │   └── BestShareTracker.java
│   ├── payment/
│   │   ├── PaymentCalculator.java   ← 收益计算
│   │   ├── ShareAccounting.java
│   │   └── PayoutManager.java
│   └── api/
│       └── PoolApiServer.java       ← 矿池 Web API
├── pom.xml
└── README.md
```

#### 3.2 核心组件

**PoolServer.java** - 矿池主程序:

```java
package io.xdag.pool;

import io.xdag.pool.config.PoolConfig;
import io.xdag.pool.node.NodeRpcClient;
import io.xdag.pool.stratum.StratumServer;
import io.xdag.pool.worker.WorkerManager;
import lombok.extern.slf4j.Slf4j;

/**
 * XDAG Pool Server - 独立的矿池服务器
 */
@Slf4j
public class PoolServer {

    private final PoolConfig config;
    private final NodeRpcClient nodeClient;
    private final StratumServer stratumServer;
    private final WorkerManager workerManager;
    private final MiningTaskManager taskManager;
    private final ShareValidator shareValidator;

    public PoolServer(PoolConfig config) {
        this.config = config;

        // 连接到节点
        this.nodeClient = new NodeRpcClient(
                config.getNodeRpcUrl(),
                config.getPoolId()
        );

        // 初始化组件
        this.workerManager = new WorkerManager();
        this.taskManager = new MiningTaskManager(nodeClient);
        this.shareValidator = new ShareValidator(config.getPowAlgorithm());

        // 启动 Stratum 服务器
        this.stratumServer = new StratumServer(
                config.getStratumPort(),
                workerManager,
                taskManager,
                shareValidator
        );
    }

    public void start() {
        log.info("========================================");
        log.info("Starting XDAG Pool Server");
        log.info("========================================");
        log.info("Pool ID: {}", config.getPoolId());
        log.info("Node RPC: {}", config.getNodeRpcUrl());
        log.info("Stratum Port: {}", config.getStratumPort());
        log.info("========================================");

        // 启动组件
        nodeClient.start();
        taskManager.start();
        stratumServer.start();

        log.info("✓ Pool server started successfully");
    }

    public void stop() {
        log.info("Stopping XDAG Pool Server...");
        stratumServer.stop();
        taskManager.stop();
        nodeClient.stop();
        log.info("✓ Pool server stopped");
    }

    public static void main(String[] args) {
        PoolConfig config = PoolConfig.load("pool.conf");
        PoolServer server = new PoolServer(config);
        server.start();

        // Wait for shutdown signal
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
    }
}
```

**NodeRpcClient.java** - 连接节点:

```java
package io.xdag.pool.node;

import io.xdag.core.Block;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.HttpClient;

/**
 * Node RPC Client - 矿池连接节点的客户端
 */
@Slf4j
public class NodeRpcClient {

    private final String nodeRpcUrl;
    private final String poolId;
    private final HttpClient httpClient;

    public NodeRpcClient(String nodeRpcUrl, String poolId) {
        this.nodeRpcUrl = nodeRpcUrl;
        this.poolId = poolId;
        this.httpClient = createHttpClient();
    }

    /**
     * 从节点获取候选区块
     */
    public Block getCandidateBlock() {
        try {
            // RPC 调用: mining.getCandidateBlock
            JsonRpcRequest request = JsonRpcRequest.builder()
                    .method("mining.getCandidateBlock")
                    .params(poolId)
                    .build();

            JsonRpcResponse response = rpcCall(request);
            return parseBlock(response.getResult());

        } catch (Exception e) {
            log.error("Failed to get candidate block from node", e);
            return null;
        }
    }

    /**
     * 提交挖到的区块给节点
     */
    public boolean submitMinedBlock(Block block) {
        try {
            // RPC 调用: mining.submitBlock
            JsonRpcRequest request = JsonRpcRequest.builder()
                    .method("mining.submitBlock")
                    .params(block, poolId)
                    .build();

            JsonRpcResponse response = rpcCall(request);
            return response.isSuccess();

        } catch (Exception e) {
            log.error("Failed to submit block to node", e);
            return false;
        }
    }
}
```

---

### 阶段 4: 创建独立的矿工程序（xdagj-miner）

#### 4.1 项目结构

```
xdagj-miner/
├── src/main/java/io/xdag/miner/
│   ├── Miner.java                   ← 主入口
│   ├── config/
│   │   └── MinerConfig.java         ← 矿工配置
│   ├── pool/
│   │   ├── PoolConnection.java      ← 连接矿池
│   │   └── StratumClient.java       ← Stratum 客户端
│   ├── engine/
│   │   ├── MiningEngine.java        ← 挖矿引擎（从 LocalMiner 迁移）
│   │   ├── CpuMiner.java            ← CPU 挖矿
│   │   ├── GpuMiner.java            ← GPU 挖矿（可选）
│   │   └── NonceIterator.java       ← Nonce 遍历
│   ├── pow/
│   │   ├── HashCalculator.java      ← 哈希计算
│   │   ├── RandomXHasher.java       ← RandomX 实现
│   │   └── Sha256Hasher.java        ← SHA256 实现
│   └── stats/
│       └── MiningStats.java         ← 统计信息
├── pom.xml
└── README.md
```

#### 4.2 核心组件

**Miner.java** - 矿工主程序:

```java
package io.xdag.miner;

import io.xdag.miner.config.MinerConfig;
import io.xdag.miner.engine.MiningEngine;
import io.xdag.miner.pool.PoolConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * XDAG Miner - 独立的挖矿程序
 */
@Slf4j
public class Miner {

    private final MinerConfig config;
    private final PoolConnection poolConnection;
    private final MiningEngine miningEngine;

    public Miner(MinerConfig config) {
        this.config = config;

        // 连接到矿池
        this.poolConnection = new PoolConnection(
                config.getPoolUrl(),
                config.getWorkerId(),
                config.getWorkerPassword()
        );

        // 创建挖矿引擎
        this.miningEngine = createMiningEngine(config);
    }

    public void start() {
        log.info("========================================");
        log.info("Starting XDAG Miner");
        log.info("========================================");
        log.info("Worker ID: {}", config.getWorkerId());
        log.info("Pool: {}", config.getPoolUrl());
        log.info("Mining Type: {}", config.getMiningType());
        log.info("Threads: {}", config.getThreadCount());
        log.info("========================================");

        // 连接矿池
        poolConnection.connect();

        // 开始挖矿
        miningEngine.start();

        log.info("✓ Miner started successfully");
    }

    public void stop() {
        log.info("Stopping XDAG Miner...");
        miningEngine.stop();
        poolConnection.disconnect();
        log.info("✓ Miner stopped");
    }

    public static void main(String[] args) {
        MinerConfig config = MinerConfig.load("miner.conf");
        Miner miner = new Miner(config);
        miner.start();

        // Wait for shutdown signal
        Runtime.getRuntime().addShutdownHook(new Thread(miner::stop));
    }
}
```

**MiningEngine.java** - 挖矿引擎:

```java
package io.xdag.miner.engine;

import io.xdag.miner.pool.PoolConnection;
import io.xdag.miner.pow.HashCalculator;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Mining Engine - 执行实际的挖矿循环
 *
 * 这就是实际的 nonce 遍历代码！
 */
@Slf4j
public class MiningEngine {

    private final PoolConnection pool;
    private final HashCalculator hasher;
    private final int threadCount;
    private volatile boolean running = false;

    public void start() {
        running = true;

        // 启动多个挖矿线程
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> miningLoop(threadId)).start();
        }
    }

    /**
     * 挖矿循环 - 这才是实际的挖矿！
     */
    private void miningLoop(int threadId) {
        log.info("Mining thread {} started", threadId);

        while (running) {
            try {
                // 1. 从矿池获取任务
                MiningJob job = pool.getJob();
                if (job == null) {
                    Thread.sleep(1000);
                    continue;
                }

                // 2. Nonce 遍历循环
                long nonceStart = threadId;
                long nonceStep = threadCount;

                for (long nonce = nonceStart; nonce < Long.MAX_VALUE && running; nonce += nonceStep) {
                    // 3. 计算哈希
                    Bytes32 hash = hasher.calculateHash(
                            job.getBlockData(),
                            nonce
                    );

                    // 4. 检查是否满足难度
                    if (hash.compareTo(job.getDifficultyTarget()) < 0) {
                        // 5. 提交 share
                        boolean accepted = pool.submitShare(nonce, job.getJobId());

                        if (accepted) {
                            log.info("✓ Share accepted! nonce={}, hash={}",
                                    Long.toHexString(nonce),
                                    hash.toHexString().substring(0, 16));
                        }
                    }

                    // 定期检查任务更新
                    if (nonce % 10000 == 0) {
                        if (pool.hasNewJob()) {
                            break;  // 获取新任务
                        }
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Mining error in thread {}", threadId, e);
            }
        }

        log.info("Mining thread {} stopped", threadId);
    }
}
```

---

## 部署架构

### 单矿池部署

```
                ┌─────────────┐
                │   xdagj     │  Port 8001 (P2P)
                │   (Node)    │  Port 10001 (RPC)
                └──────┬──────┘
                       │
                       │ RPC
                       │
                ┌──────▼──────┐
                │ xdagj-pool  │  Port 3333 (Stratum)
                │   (Pool)    │  Port 8080 (Web UI)
                └──────┬──────┘
                       │
                       │ Stratum
           ┌───────────┼───────────┐
           │           │           │
    ┌──────▼─────┐ ┌──▼──────┐ ┌──▼─────┐
    │xdagj-miner │ │xdagj-m  │ │xdagj-m │
    │  (CPU 1)   │ │ (CPU 2) │ │ (GPU)  │
    └────────────┘ └─────────┘ └────────┘
```

### 多矿池部署

```
            ┌─────────────┐
            │   xdagj     │
            │   (Node)    │
            └──────┬──────┘
                   │
        ┌──────────┼──────────┐
        │          │          │
   ┌────▼───┐ ┌───▼────┐ ┌───▼────┐
   │ Pool A │ │ Pool B │ │ Pool C │
   └────┬───┘ └───┬────┘ └───┬────┘
        │         │          │
    [Miners]  [Miners]   [Miners]
```

---

## 迁移步骤

### 步骤 1: 不破坏现有功能

1. 在 xdagj 中添加 `NodeMiningRpcService`
2. 保持 `MiningManager` 暂时不动
3. 测试 RPC 接口是否正常工作

### 步骤 2: 创建独立项目

1. 创建 `xdagj-pool` 项目
2. 将 `MiningManager`、`ShareValidator` 等迁移到 pool 项目
3. 实现 Stratum 协议

### 步骤 3: 创建矿工程序

1. 创建 `xdagj-miner` 项目
2. 将 `LocalMiner` 迁移并改进为独立矿工
3. 支持 CPU 和 GPU

### 步骤 4: 从节点移除矿池代码

1. 在 xdagj 中标记 `MiningManager` 为 `@Deprecated`
2. 更新文档说明使用 xdagj-pool
3. 在下一个大版本中移除

---

## 配置文件示例

### 节点配置（xdagj.conf）

```hocon
node {
  # 区块链核心配置
  network = "mainnet"
  dataDir = "./data"

  # P2P 网络
  p2p {
    port = 8001
    seeds = ["seed1.xdag.io:8001", "seed2.xdag.io:8001"]
  }

  # RPC API
  rpc {
    enabled = true
    port = 10001
    allowedIps = ["127.0.0.1", "192.168.1.0/24"]
  }

  # 挖矿 RPC（供矿池使用）
  mining {
    enabled = true
    # 允许的矿池 ID（可选，用于鉴权）
    allowedPools = ["pool1", "pool2"]
  }
}
```

### 矿池配置（pool.conf）

```hocon
pool {
  id = "pool1"
  name = "XDAG Community Pool"

  # 连接到节点
  node {
    rpcUrl = "http://localhost:10001"
    rpcUser = "pool"
    rpcPassword = "secret"
  }

  # Stratum 服务器
  stratum {
    port = 3333
    difficulty = 1000  # 初始难度
  }

  # Web API
  api {
    port = 8080
    enabled = true
  }

  # 支付配置
  payment {
    method = "PPLNS"  # 或 "PPS"
    minPayout = "10 XDAG"
    interval = "1 hour"
  }
}
```

### 矿工配置（miner.conf）

```hocon
miner {
  workerId = "worker1"
  workerPassword = "x"

  # 连接到矿池
  pool {
    url = "stratum+tcp://pool.xdag.io:3333"
    backup = "stratum+tcp://backup.xdag.io:3333"
  }

  # 挖矿引擎
  mining {
    type = "CPU"  # 或 "GPU"
    threads = 4
    intensity = "high"
  }

  # GPU 配置（可选）
  gpu {
    devices = [0, 1]  # GPU 设备 ID
    workSize = 256
  }
}
```

---

## 优势

### 节点（xdagj）
- ✅ 更轻量，专注于区块链核心
- ✅ 更容易维护和测试
- ✅ 不需要关心挖矿细节

### 矿池（xdagj-pool）
- ✅ 独立部署和扩展
- ✅ 可以连接任何 xdagj 节点
- ✅ 独立开发和更新
- ✅ 支持多种支付算法

### 矿工（xdagj-miner）
- ✅ 独立的挖矿程序
- ✅ 可以选择任何矿池
- ✅ 易于优化（CPU/GPU）
- ✅ 跨平台支持

---

## 兼容性

### 向后兼容

- 保留 `MiningManager` 作为 `@Deprecated` 一个版本
- 提供迁移指南
- 更新文档

### 协议兼容

- 节点 RPC 使用 JSON-RPC 2.0
- 矿池使用标准 Stratum 协议
- 易于与第三方矿工集成

---

## 时间表

| 阶段 | 任务 | 预计时间 |
|------|------|----------|
| 1 | 设计接口和架构 | 已完成 |
| 2 | 实现节点 RPC API | 1 周 |
| 3 | 创建 xdagj-pool 项目 | 2-3 周 |
| 4 | 创建 xdagj-miner 项目 | 1-2 周 |
| 5 | 集成测试 | 1 周 |
| 6 | 文档和部署 | 1 周 |

**总计**: 6-8 周

---

## 结论

将挖矿架构拆分为三个独立组件是正确的设计决策：
- **节点**专注于区块链核心
- **矿池**负责协调和管理
- **矿工**执行实际的 PoW 计算

这符合标准区块链架构，便于维护、扩展和部署。

---

**状态**: 等待批准
**下一步**: 实现节点 RPC API
