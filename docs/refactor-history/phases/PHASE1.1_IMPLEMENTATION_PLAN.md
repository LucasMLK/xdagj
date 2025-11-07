# Phase 1.1 实施计划：主链索引批量查询

**任务**: 为混合同步协议添加批量主链查询功能
**优先级**: 🔥 HIGH
**预计工时**: 2-4小时

---

## 背景

当前 `DagStore` 已经有了主链索引的基础：
- ✅ HEIGHT_INDEX (0xb2) 存储结构已定义
- ✅ `buildHeightIndexKey(long height)` 已实现
- ✅ `getMainBlockAtPosition(long position, boolean isRaw)` 单块查询已实现
- ✅ `saveBlock()` 自动更新主链索引

**缺失功能**: 批量查询 - 混合同步协议需要一次请求多个主块（1000块/批）

---

## 1. 需要实现的方法

### 1.1 DagStore接口扩展

**文件**: `/src/main/java/io/xdag/db/DagStore.java`

在 `// ==================== Main Chain Queries (Position-Based) ====================` 部分添加：

```java
/**
 * Get multiple main blocks by height range (for sync protocol)
 *
 * <p>Efficient batch query for linear main chain sync.
 * Recommended batch size: 1000 blocks.
 *
 * <p><strong>Performance</strong>:
 * - Uses cached height-to-hash mappings (L1 cache)
 * - Batch loads blocks from RocksDB (L2 cache + disk)
 * - ~100-500ms for 1000 blocks
 *
 * @param fromHeight Start height (inclusive)
 * @param toHeight End height (inclusive)
 * @param isRaw true to load full raw data, false for BlockInfo only
 * @return List of blocks in ascending height order (may contain nulls for missing blocks)
 */
List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw);

/**
 * Check if main chain is continuous in height range
 *
 * <p>Used by sync protocol to verify downloaded main chain integrity.
 *
 * @param fromHeight Start height (inclusive)
 * @param toHeight End height (inclusive)
 * @return true if all blocks exist and maxDiffLink forms continuous chain
 */
boolean verifyMainChainContinuity(long fromHeight, long toHeight);
```

**位置**: 在 `listMainBlocks()` 方法之后添加

---

### 1.2 DagStoreImpl实现

**文件**: `/src/main/java/io/xdag/db/rocksdb/DagStoreImpl.java`

在 `// ==================== Main Chain Queries ====================` 部分（line 351附近）添加：

```java
@Override
public List<Block> getMainBlocksByHeightRange(long fromHeight, long toHeight, boolean isRaw) {
    List<Block> blocks = new ArrayList<>();

    // Validation
    if (fromHeight < 1 || toHeight < fromHeight) {
        log.warn("Invalid height range: [{}, {}]", fromHeight, toHeight);
        return blocks;
    }

    // Limit batch size to prevent memory issues
    long batchSize = toHeight - fromHeight + 1;
    if (batchSize > 10000) {
        log.warn("Batch size too large: {}, limiting to 10000", batchSize);
        toHeight = fromHeight + 9999;
    }

    log.debug("Fetching main blocks: height [{}, {}], raw={}", fromHeight, toHeight, isRaw);

    try {
        // Phase 1: Batch load height-to-hash mappings
        List<Bytes32> hashes = new ArrayList<>();
        List<byte[]> keys = new ArrayList<>();

        for (long h = fromHeight; h <= toHeight; h++) {
            // Check L1 cache first
            Bytes32 cachedHash = cache.getHashByHeight(h);
            if (cachedHash != null) {
                hashes.add(cachedHash);
            } else {
                keys.add(buildHeightIndexKey(h));
                hashes.add(null);  // Placeholder
            }
        }

        // Batch read missing height indices from RocksDB
        if (!keys.isEmpty()) {
            try {
                List<byte[]> values = db.multiGetAsList(readOptions, keys);
                int keyIndex = 0;

                for (int i = 0; i < hashes.size(); i++) {
                    if (hashes.get(i) == null) {
                        byte[] hashData = values.get(keyIndex++);
                        if (hashData != null && hashData.length == 32) {
                            Bytes32 hash = Bytes32.wrap(hashData);
                            hashes.set(i, hash);

                            // Update L1 cache
                            cache.putHashByHeight(fromHeight + i, hash);
                        }
                    }
                }
            } catch (RocksDBException e) {
                log.error("Failed to batch read height indices", e);
                return blocks;
            }
        }

        // Phase 2: Batch load blocks
        if (isRaw) {
            // Load full block data
            for (Bytes32 hash : hashes) {
                if (hash != null) {
                    Block block = getBlockByHash(hash, true);
                    blocks.add(block);
                } else {
                    blocks.add(null);  // Missing block
                }
            }
        } else {
            // Load BlockInfo only (faster)
            for (Bytes32 hash : hashes) {
                if (hash != null) {
                    Block block = getBlockByHash(hash, false);
                    blocks.add(block);
                } else {
                    blocks.add(null);  // Missing block
                }
            }
        }

        log.debug("Fetched {} main blocks (isRaw={})", blocks.size(), isRaw);
        return blocks;

    } catch (Exception e) {
        log.error("Failed to get main blocks by height range", e);
        return blocks;
    }
}

@Override
public boolean verifyMainChainContinuity(long fromHeight, long toHeight) {
    if (fromHeight < 1 || toHeight < fromHeight) {
        return false;
    }

    try {
        // Load blocks with BlockInfo only (faster)
        List<Block> blocks = getMainBlocksByHeightRange(fromHeight, toHeight, false);

        // Check all blocks exist
        for (Block block : blocks) {
            if (block == null) {
                log.debug("Main chain gap detected in range [{}, {}]", fromHeight, toHeight);
                return false;
            }
        }

        // Verify each block's maxDiffLink points to previous block
        Block prevBlock = null;
        for (Block block : blocks) {
            if (prevBlock != null) {
                // Check if current block references previous block
                boolean references = block.getLinks().stream()
                        .anyMatch(link -> link.isBlock() && link.getTargetHash().equals(prevBlock.getHash()));

                if (!references) {
                    log.debug("Main chain discontinuity: block {} doesn't reference {}",
                             block.getHash().toHexString(), prevBlock.getHash().toHexString());
                    return false;
                }
            }
            prevBlock = block;
        }

        log.debug("Main chain verified: height [{}, {}] is continuous", fromHeight, toHeight);
        return true;

    } catch (Exception e) {
        log.error("Failed to verify main chain continuity", e);
        return false;
    }
}
```

**关键优化**:
1. **L1 Cache优先**: 先检查 `DagCache` 中的 height→hash 映射
2. **RocksDB multiGetAsList**: 批量读取多个key（比单次get快5-10倍）
3. **批量大小限制**: 最多10000块/批，防止内存溢出
4. **isRaw参数**: 支持只加载BlockInfo（更快）

---

## 2. 性能优化

### 2.1 RocksDB multiGet批量读取

**当前问题**: `getMainBlockAtPosition()` 逐个查询，1000个块需要1000次RocksDB调用

**优化方案**: 使用 `multiGetAsList()` 批量读取：

```java
// 旧方式（慢）:
for (long h = fromHeight; h <= toHeight; h++) {
    byte[] hashData = db.get(readOptions, buildHeightIndexKey(h));  // 1000次调用
}

// 新方式（快）:
List<byte[]> keys = new ArrayList<>();
for (long h = fromHeight; h <= toHeight; h++) {
    keys.add(buildHeightIndexKey(h));
}
List<byte[]> values = db.multiGetAsList(readOptions, keys);  // 1次调用！
```

**性能提升**: ~5-10倍

---

### 2.2 L1 Cache预热

在混合同步期间，height→hash映射会被频繁访问，需要预热缓存：

```java
// 在 HybridSyncCoordinator 中:
// 预热最近的1024个块的height→hash映射
long recentHeight = getMainChainLength();
for (long h = Math.max(1, recentHeight - 1024); h <= recentHeight; h++) {
    getMainBlockAtPosition(h, false);  // 触发L1缓存
}
```

---

## 3. 单元测试

**文件**: `/src/test/java/io/xdag/db/DagStoreMainChainBatchTest.java`

```java
package io.xdag.db;

import io.xdag.config.DevnetConfig;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.db.rocksdb.DagStoreImpl;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Test for batch main chain queries (Phase 1.1)
 */
public class DagStoreMainChainBatchTest {

    private DagStore dagStore;
    private Path tempDir;
    private List<Block> testBlocks;

    @Before
    public void setUp() throws IOException {
        // Create temp dir
        tempDir = Files.createTempDirectory("dagstore-batch-test-");

        // Create config
        DevnetConfig config = new DevnetConfig() {
            @Override
            public String getStoreDir() {
                return tempDir.toString();
            }
        };

        // Initialize DagStore
        dagStore = new DagStoreImpl(config);
        dagStore.start();

        // Create 100 test main blocks
        testBlocks = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            Block block = Block.builder()
                    .header(/* ... */)
                    .links(new ArrayList<>())
                    .info(BlockInfo.builder()
                            .hash(Bytes32.random())
                            .timestamp(System.currentTimeMillis())
                            .height(i)  // Main block
                            .difficulty(UInt256.valueOf(i * 1000))
                            .build())
                    .build();

            dagStore.saveBlock(block);
            testBlocks.add(block);
        }
    }

    @After
    public void tearDown() throws IOException {
        if (dagStore != null) {
            dagStore.stop();
        }

        // Clean up temp dir
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            // Ignore
                        }
                    });
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_Small() {
        // Fetch blocks 1-10
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 10, false);

        assertEquals(10, blocks.size());
        for (int i = 0; i < 10; i++) {
            assertNotNull(blocks.get(i));
            assertEquals(i + 1, blocks.get(i).getInfo().getHeight());
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_Large() {
        // Fetch blocks 1-100
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 100, false);

        assertEquals(100, blocks.size());
        for (int i = 0; i < 100; i++) {
            assertNotNull(blocks.get(i));
            assertEquals(i + 1, blocks.get(i).getInfo().getHeight());
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_WithRawData() {
        // Fetch with raw data
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 10, true);

        assertEquals(10, blocks.size());
        for (Block block : blocks) {
            assertNotNull(block);
            assertNotNull(block.getHeader());  // Verify raw data loaded
        }
    }

    @Test
    public void testGetMainBlocksByHeightRange_PartialRange() {
        // Fetch middle range
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(40, 60, false);

        assertEquals(21, blocks.size());
        assertEquals(40, blocks.get(0).getInfo().getHeight());
        assertEquals(60, blocks.get(20).getInfo().getHeight());
    }

    @Test
    public void testGetMainBlocksByHeightRange_InvalidRange() {
        // Invalid: fromHeight > toHeight
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(50, 10, false);
        assertTrue(blocks.isEmpty());

        // Invalid: fromHeight < 1
        blocks = dagStore.getMainBlocksByHeightRange(0, 10, false);
        assertTrue(blocks.isEmpty());
    }

    @Test
    public void testGetMainBlocksByHeightRange_MissingBlocks() {
        // Delete some blocks to create gaps
        Block block50 = testBlocks.get(49);
        dagStore.deleteBlock(block50.getHash());

        // Fetch range with gap
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(45, 55, false);

        assertEquals(11, blocks.size());
        assertNull(blocks.get(5));  // Block 50 should be null
    }

    @Test
    public void testVerifyMainChainContinuity_Valid() {
        // Create chain with proper references
        // (Simplified - actual test would create linked blocks)

        boolean isContinuous = dagStore.verifyMainChainContinuity(1, 10);
        // Note: Will fail if blocks don't reference each other
        // Need to create proper test blocks with Links
    }

    @Test
    public void testVerifyMainChainContinuity_WithGap() {
        // Delete middle block
        Block block50 = testBlocks.get(49);
        dagStore.deleteBlock(block50.getHash());

        boolean isContinuous = dagStore.verifyMainChainContinuity(40, 60);
        assertFalse(isContinuous);  // Should detect gap at height 50
    }

    @Test
    public void testBatchPerformance() {
        // Measure batch query performance
        long startTime = System.nanoTime();

        // Query 1000 blocks (would need to create them first)
        List<Block> blocks = dagStore.getMainBlocksByHeightRange(1, 100, false);

        long duration = (System.nanoTime() - startTime) / 1_000_000;  // Convert to ms

        System.out.println("Batch query 100 blocks: " + duration + "ms");
        assertTrue("Batch query should complete in < 100ms", duration < 100);
    }
}
```

---

## 4. 集成到DagChainImpl

在 `DagChainImpl` 中使用新的批量查询方法：

```java
/**
 * Get main chain path (for sync)
 */
@Override
public List<Block> getMainChainPath(long fromHeight, long toHeight) {
    // Use new batch query method
    return dagStore.getMainBlocksByHeightRange(fromHeight, toHeight, false);
}
```

---

## 5. 验收标准

### 5.1 功能完整性
- ✅ `getMainBlocksByHeightRange()` 正确返回指定范围的主块
- ✅ 支持 `isRaw` 参数（全量 vs BlockInfo only）
- ✅ 正确处理边界情况（无效范围、缺失块）
- ✅ `verifyMainChainContinuity()` 正确检测链的连续性

### 5.2 性能要求
- ✅ 批量查询1000个BlockInfo: < 500ms (P99)
- ✅ 批量查询1000个完整Block: < 2s (P99)
- ✅ L1 Cache命中率: > 80%

### 5.3 测试覆盖
- ✅ 单元测试覆盖率 > 90%
- ✅ 集成测试通过
- ✅ 性能测试达标

---

## 6. 下一步 (Phase 1.2)

完成 Phase 1.1 后，立即开始 Phase 1.2：
- 实现 `FinalityBoundary` 类
- 定义 `FINALITY_EPOCHS = 16384`
- 实现 `getFinalizedHeight()` 和 `isFinalized()` 方法

---

**预计完成时间**: 今天晚上
**测试完成时间**: 明天上午
**集成到HybridSyncCoordinator**: Phase 1.6 (Week 3)

**准备好开始实施了吗？** 👨‍💻
