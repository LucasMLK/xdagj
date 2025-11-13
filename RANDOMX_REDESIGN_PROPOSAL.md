# RandomX 架构重新设计方案

**目标：** 不考虑向后兼容，基于 RandomX 挖矿专业性和 Java 最佳实践的全新设计

**Date:** 2025-11-13
**Status:** ✅ **IMPLEMENTED** - See [RANDOMX_EVENT_DRIVEN_REFACTORING_COMPLETE.md](./RANDOMX_EVENT_DRIVEN_REFACTORING_COMPLETE.md)

> **本方案已全部实施完成（阶段 1-7）。详细的实施报告和验证结果请查看完成报告。**

---

## 一、当前架构的根本问题

### 1.1 现状分析

**现有类：**
- `RandomX.java` (328 行) - Facade
- `RandomXSeedManager.java` (491 行) - 种子管理
- `RandomXHashService.java` (183 行) - 哈希计算
- `RandomXSnapshotLoader.java` (323 行) - 快照加载
- `RandomXMemory.java` (78 行) - 内存状态
- `PoW.java` (40 行) - 简单接口（只有 `receiveNewShare` 方法）

**调用关系：**
```
DagKernel
  └─ randomX: null (未初始化！)

MiningManager
  ├─ randomX (注入，可能为 null)
  ├─ BlockGenerator(randomX)
  └─ ShareValidator(randomX)

BlockGenerator
  └─ randomX.isRandomxFork(epoch)  ✅ 唯一实际使用

ShareValidator
  └─ randomX.randomXBlockHash(data, time)  ✅ 唯一实际使用
```

**未被使用的方法（死代码）：**
```java
❌ randomXSetForkTime(Block)          // 从未调用
❌ randomXUnsetForkTime(Block)         // 从未调用
❌ randomXPoolCalcHash(data, time)     // 从未调用
❌ randomXLoadingSnapshot(byte[])      // 从未调用
❌ randomXLoadingForkTimeSnapshot()    // 从未调用
❌ randomXLoadingSnapshot()            // 从未调用
❌ randomXLoadingForkTime()            // 从未调用
```

### 1.2 核心问题

1. **缺少事件驱动机制**
   - 种子更新方法存在但无人调用
   - 区块连接/断开事件没有触发种子更新
   - 依赖外部显式调用，但外部从不调用

2. **命名不规范**
   - `randomXSetForkTime` 应该是 `updateSeed` 或 `onBlockConnected`
   - `randomXLoadingSnapshot` 应该是 `loadFromSnapshot`
   - 违反 Java 命名规范

3. **职责混乱**
   - RandomX 既是 PoW 算法提供者，又负责区块事件处理
   - 快照加载有 4 个方法做类似的事
   - 缺少清晰的生命周期管理

4. **依赖注入不完整**
   - DagKernel 中 `randomX = null`，从未初始化
   - MiningManager 接收 null 引用
   - 导致 NPE 风险

5. **缺少接口抽象**
   - `PoW.java` 接口设计不合理（只有 receiveNewShare）
   - 无法支持多种 PoW 算法（SHA256, RandomX）
   - 难以测试和扩展

---

## 二、重新设计的架构

### 2.1 设计原则

1. **接口优先（Interface Segregation）**
   - PoW 算法应该是可插拔的
   - 清晰的契约定义

2. **事件驱动（Event-Driven）**
   - 种子更新响应区块链事件
   - 解耦业务逻辑

3. **依赖注入（Dependency Injection）**
   - 所有依赖通过构造函数注入
   - 遵循 Spring 风格（虽然项目不用 Spring）

4. **单一职责（Single Responsibility）**
   - 每个类只做一件事
   - 高内聚，低耦合

5. **清晰命名（Clean Naming）**
   - 符合 Java 命名规范
   - 见名知意

### 2.2 核心架构

```
┌──────────────────────────────────────────────────────────────┐
│                      DagKernel                               │
│  - dagChain: DagChain                                        │
│  - powAlgorithm: PowAlgorithm                                │
│  - miningManager: MiningManager                              │
└────────────┬────────────────────────────┬────────────────────┘
             │                            │
             │                            │
    ┌────────▼─────────┐         ┌───────▼────────┐
    │    DagChain      │         │ PowAlgorithm   │ (Interface)
    │                  │         │                │
    │ + addListener()  │         │ + calculate()  │
    └────────┬─────────┘         │ + verify()     │
             │                   │ + isActive()   │
             │                   └────────△───────┘
             │                            │
             │                   ┌────────┴────────┬──────────────┐
             │                   │                 │              │
             │          ┌────────┴──────┐  ┌──────┴──────┐  ┌───┴────────┐
             │          │  Sha256Pow    │  │ RandomXPow  │  │ EthashPow  │
             │          └───────────────┘  └──────┬──────┘  └────────────┘
             │                                    │
             │                            ┌───────┴────────┐
             │                            │                │
             │                    ┌───────▼──────┐  ┌──────▼────────┐
             │                    │ SeedManager  │  │ HashCalculator│
             │                    │ (internal)   │  │ (internal)    │
             │                    └──────────────┘  └───────────────┘
             │
    ┌────────▼──────────────────────────────────────┐
    │      BlockchainListener (Interface)           │
    │  + onBlockConnected(Block)                    │
    │  + onBlockDisconnected(Block)                 │
    └────────△──────────────────────────────────────┘
             │
    ┌────────┴──────────┐
    │ RandomXPow        │ (implements)
    │                   │
    │ - listens to      │
    │   blockchain      │
    │   events          │
    └───────────────────┘
```

### 2.3 核心接口定义

#### PowAlgorithm (替代现有的 PoW 和 RandomX)

```java
/**
 * Proof of Work Algorithm
 *
 * Provides hash calculation and verification for blockchain consensus.
 */
public interface PowAlgorithm extends Lifecycle {

    /**
     * Calculate hash for block validation
     *
     * @param data Block data
     * @param context Calculation context (timestamp, etc.)
     * @return Hash result, or null if not ready
     */
    byte[] calculateBlockHash(byte[] data, HashContext context);

    /**
     * Calculate hash for pool mining
     *
     * @param data Mining data
     * @param context Calculation context
     * @return Hash result, or null if not ready
     */
    Bytes32 calculatePoolHash(byte[] data, HashContext context);

    /**
     * Check if this algorithm is active for given epoch
     *
     * @param epoch Epoch to check
     * @return true if active
     */
    boolean isActive(long epoch);

    /**
     * Get algorithm name
     *
     * @return Name (e.g., "RandomX", "SHA256")
     */
    String getName();
}
```

#### BlockchainListener (新增)

```java
/**
 * Listener for blockchain events
 *
 * Allows components to react to block connection/disconnection.
 */
public interface BlockchainListener {

    /**
     * Called when a block is connected to the main chain
     *
     * @param block Connected block
     */
    void onBlockConnected(Block block);

    /**
     * Called when a block is disconnected from the main chain
     *
     * @param block Disconnected block
     */
    void onBlockDisconnected(Block block);
}
```

#### HashContext (新增)

```java
/**
 * Context for hash calculation
 *
 * Encapsulates all parameters needed for PoW calculation.
 */
public class HashContext {
    private final long timestamp;
    private final long blockHeight;
    private final long epoch;

    // Getters...

    public static HashContext forBlock(Block block) {
        return new HashContext(
            block.getTimestamp(),
            block.getInfo().getHeight(),
            XdagTime.getEpoch(block.getTimestamp())
        );
    }

    public static HashContext forMining(long timestamp) {
        return new HashContext(
            timestamp,
            -1,
            XdagTime.getEpoch(timestamp)
        );
    }
}
```

### 2.4 RandomXPow 实现

```java
/**
 * RandomX Proof of Work Implementation
 *
 * Thread-safe RandomX algorithm with automatic seed management.
 */
@Slf4j
public class RandomXPow implements PowAlgorithm, BlockchainListener {

    // ========== Dependencies (Injected) ==========

    private final Config config;
    private final DagChain dagChain;

    // ========== Internal Services ==========

    private final RandomXSeedManager seedManager;
    private final RandomXHashCalculator hashCalculator;

    // ========== Constructor ==========

    /**
     * Create RandomX PoW with dependency injection
     *
     * @param config System configuration
     * @param dagChain Blockchain (for seed derivation)
     */
    public RandomXPow(Config config, DagChain dagChain) {
        this.config = config;
        this.dagChain = dagChain;

        // Initialize internal services
        Set<RandomXFlag> flags = RandomXUtils.getRecommendedFlags();
        if (config.getRandomxSpec().getRandomxFlag()) {
            flags.add(RandomXFlag.LARGE_PAGES);
            flags.add(RandomXFlag.FULL_MEM);
        }

        this.seedManager = new RandomXSeedManager(config, flags);
        this.hashCalculator = new RandomXHashCalculator(seedManager);

        log.info("RandomXPow created with flags: {}", flags);
    }

    // ========== Lifecycle ==========

    @Override
    public void start() {
        seedManager.setDagChain(dagChain);
        seedManager.initialize();

        // Register as blockchain listener
        dagChain.addListener(this);

        log.info("RandomXPow started");
    }

    @Override
    public void stop() {
        // Unregister listener
        dagChain.removeListener(this);

        seedManager.cleanup();

        log.info("RandomXPow stopped");
    }

    // ========== PowAlgorithm Implementation ==========

    @Override
    public byte[] calculateBlockHash(byte[] data, HashContext context) {
        return hashCalculator.calculateBlockHash(data, context.getTimestamp());
    }

    @Override
    public Bytes32 calculatePoolHash(byte[] data, HashContext context) {
        return hashCalculator.calculatePoolHash(
            Bytes.wrap(data),
            context.getTimestamp()
        );
    }

    @Override
    public boolean isActive(long epoch) {
        return seedManager.isAfterFork(epoch);
    }

    @Override
    public String getName() {
        return "RandomX";
    }

    // ========== BlockchainListener Implementation ==========

    /**
     * Automatically update seed when blocks are connected
     */
    @Override
    public void onBlockConnected(Block block) {
        seedManager.updateSeedForBlock(block);

        long height = block.getInfo().getHeight();
        long epochMask = seedManager.getSeedEpochBlocks() - 1;
        if ((height & epochMask) == 0) {
            log.info("Seed updated at epoch boundary: height={}", height);
        }
    }

    /**
     * Revert seed when blocks are disconnected
     */
    @Override
    public void onBlockDisconnected(Block block) {
        seedManager.revertSeedForBlock(block);
        log.debug("Seed reverted for block at height {}", block.getInfo().getHeight());
    }
}
```

### 2.5 快照加载重新设计

**问题：** 现在有 4 个方法做类似的事

**解决方案：** 统一接口 + 策略模式

```java
/**
 * Snapshot loading strategy
 */
public enum SnapshotStrategy {
    /** Use preseed from snapshot file */
    WITH_PRESEED,

    /** Reconstruct from current blockchain state */
    FROM_CURRENT_STATE,

    /** Initialize from fork activation block */
    FROM_FORK_HEIGHT,

    /** Auto-choose based on chain progress */
    AUTO
}

/**
 * Snapshot loader
 */
public class SnapshotLoader {

    private final RandomXSeedManager seedManager;
    private final DagChain dagChain;
    private final Config config;

    /**
     * Load snapshot with strategy
     *
     * @param strategy Loading strategy
     * @param preseed Preseed (may be null for some strategies)
     */
    public void load(SnapshotStrategy strategy, byte[] preseed) {
        switch (strategy) {
            case WITH_PRESEED:
                loadWithPreseed(preseed);
                break;
            case FROM_CURRENT_STATE:
                loadFromCurrentState();
                break;
            case FROM_FORK_HEIGHT:
                loadFromForkHeight();
                break;
            case AUTO:
                autoLoad(preseed);
                break;
        }
    }

    // Private implementation methods...
}
```

---

## 三、调用关系对比

### 3.1 现状 (Broken)

```java
// DagKernel.java
private RandomX randomX;  // = null, 从未初始化！

// Somewhere (never happens)
randomX.randomXSetForkTime(block);  // 这个调用从不发生
```

### 3.2 新设计 (Event-Driven)

```java
// DagKernel.java
public void initialize() {
    // 1. Create blockchain
    this.dagChain = new DagChainImpl(...);

    // 2. Create PoW algorithm
    this.powAlgorithm = new RandomXPow(config, dagChain);
    powAlgorithm.start();  // Registers as listener automatically

    // 3. Load snapshot
    SnapshotLoader loader = new SnapshotLoader(
        ((RandomXPow) powAlgorithm).getSeedManager(),
        dagChain,
        config
    );
    loader.load(SnapshotStrategy.AUTO, preseed);

    // 4. Create mining manager
    this.miningManager = new MiningManager(this, wallet, powAlgorithm, ttl);
}

// DagChainImpl.java
public void connectBlock(Block block) {
    // ... validate and add block ...

    // Notify all listeners (including RandomXPow)
    notifyBlockConnected(block);  // Triggers seedManager.updateSeedForBlock()
}
```

---

## 四、迁移计划

### 阶段 1：接口定义 (1-2 小时)

1. 创建 `PowAlgorithm` 接口
2. 创建 `BlockchainListener` 接口
3. 创建 `HashContext` 类
4. 创建 `SnapshotStrategy` 枚举

### 阶段 2：重构 RandomX (2-3 小时)

1. 重命名 `RandomX` → `RandomXPow`
2. 实现 `PowAlgorithm` 接口
3. 实现 `BlockchainListener` 接口
4. 删除未使用的 public 方法
5. 重命名方法（去掉 randomX 前缀）

### 阶段 3：事件系统 (2-3 小时)

1. DagChain 添加 listener 支持
2. DagChainImpl 在 connect/disconnect 时触发事件
3. RandomXPow 注册为 listener

### 阶段 4：快照加载 (1-2 小时)

1. 统一 4 个快照加载方法
2. 实现策略模式
3. 简化 API

### 阶段 5：更新调用方 (1-2 小时)

1. 更新 DagKernel 初始化
2. 更新 MiningManager
3. 更新 BlockGenerator
4. 更新 ShareValidator

### 阶段 6：测试 (2-3 小时)

1. 编译验证
2. 单元测试
3. 集成测试

**总计：** 约 9-15 小时

---

## 五、优势对比

| 维度 | 当前设计 | 新设计 |
|------|---------|--------|
| **接口抽象** | PoW 接口不合理 | PowAlgorithm 清晰定义 |
| **命名规范** | randomXSetForkTime ❌ | onBlockConnected ✅ |
| **事件驱动** | 手动调用（但从不调用）❌ | 自动响应事件 ✅ |
| **依赖注入** | DagKernel 中 null ❌ | 完整注入 ✅ |
| **可测试性** | 难以 mock ❌ | 接口易 mock ✅ |
| **可扩展性** | 硬编码 RandomX ❌ | 可插拔算法 ✅ |
| **死代码** | 7 个未使用方法 ❌ | 无死代码 ✅ |
| **快照加载** | 4 个相似方法 ❌ | 统一策略 ✅ |
| **职责分离** | Facade 做太多事 ❌ | 清晰职责 ✅ |

---

## 六、风险评估

### 低风险
- ✅ 编译错误易发现
- ✅ 接口变更强制更新调用方
- ✅ 测试覆盖

### 中风险
- ⚠️ 事件系统需要在 DagChain 中实现
- ⚠️ 需要完整的集成测试

### 高风险
- ❌ 无（因为是全新部署，快照导入升级）

---

## 七、推荐行动

### 选项 A：全面重构（推荐）

**优点：**
- 彻底解决架构问题
- 符合最佳实践
- 长期可维护

**缺点：**
- 需要 9-15 小时
- 改动较大

**适用场景：** 当前项目（全面停机升级）

### 选项 B：渐进式改进

**优点：**
- 改动较小
- 风险更低

**缺点：**
- 架构问题依然存在
- 技术债务累积

**适用场景：** 生产系统不能停机

---

## 八、结论

基于当前是**全面停机、快照导入升级**的情况，**强烈推荐选项 A（全面重构）**。

这是一个难得的机会，可以彻底解决架构问题，建立一个符合 RandomX 挖矿专业性和 Java 最佳实践的清晰架构。

新架构的核心优势：
1. **事件驱动** - 种子自动更新，无需手动调用
2. **接口清晰** - 可插拔的 PoW 算法
3. **依赖注入** - 完整的依赖管理
4. **职责分离** - 每个类职责明确
5. **符合规范** - Java 命名和设计模式

---

**Next Steps:**

1. 审核本设计方案
2. 确认是否采纳
3. 如采纳，开始实施（预计 9-15 小时）

**Questions?**

- 是否需要保留 Sha256PoW 实现？
- DagChain 的 listener 机制实现细节？
- 快照加载的默认策略？
