# Storage Layer Fix Guide - Remove Block References

**Date**: 2025-11-03
**Status**: Ready to Execute
**Target**: CachedBlockStore.java, BloomFilterBlockStore.java

---

## 问题分析

FinalizedBlockStore接口是**100% BlockInfo-based**，没有任何Block方法！

但实现类（CachedBlockStore, BloomFilterBlockStore）却有很多Block引用，这些都是**多余的、应该删除的**。

---

## CachedBlockStore.java 修复清单

### 1. 删除Block缓存字段 (Line 61)
```java
❌ 删除: private final Cache<Bytes32, Block> blockCache;
❌ 删除: private long blockCacheHits = 0;
❌ 删除: private long blockCacheMisses = 0;
```

### 2. 删除构造函数中的Block缓存初始化 (Line 94-98)
```java
❌ 删除:
// Block cache: full block data
this.blockCache = CacheBuilder.newBuilder()
        .maximumSize(blockCacheSize)
        .expireAfterAccess(5, TimeUnit.MINUTES)
        .recordStats()
        .build();
```

### 3. 删除构造函数参数 (Line 80-83)
```java
❌ 修改前:
public CachedBlockStore(FinalizedBlockStore delegate,
                         long blockInfoCacheSize,
                         long blockCacheSize,  // ← 删除这个参数
                         long heightCacheSize)

✅ 修改后:
public CachedBlockStore(FinalizedBlockStore delegate,
                         long blockInfoCacheSize,
                         long heightCacheSize)
```

### 4. 删除Block相关方法 (Lines 89-109, 134-155, 158-171, 186-203, 295-323, 331-333)
```java
❌ 完全删除这些方法:
- Optional<Block> getBlockByHash(Bytes32 hash)
- Optional<Block> getMainBlockByHeight(long height)
- void saveBlock(Block block)
- long saveBatch(List<Block> blocks)
- List<Block> getMainBlocksByHeightRange(...)
- List<Block> getBlocksByEpoch(long epoch)
- List<Block> getBlocksByEpochRange(...)
```

### 5. 删除统计方法 (Lines 218-221)
```java
❌ 删除:
public double getBlockCacheHitRate() {
    long total = blockCacheHits + blockCacheMisses;
    return total > 0 ? (double) blockCacheHits / total * 100 : 0;
}
```

### 6. 更新CacheStats record (Line 388-395)
```java
❌ 修改前:
public record CacheStats(
        long blockInfoCacheSize,
        long blockCacheSize,  // ← 删除
        long heightCacheSize,
        double blockInfoHitRate,
        double blockHitRate,  // ← 删除
        double heightHitRate
) {}

✅ 修改后:
public record CacheStats(
        long blockInfoCacheSize,
        long heightCacheSize,
        double blockInfoHitRate,
        double heightHitRate
) {}
```

### 7. 更新clearCaches方法 (Line 260-265)
```java
❌ 修改前:
public void clearCaches() {
    blockInfoCache.invalidateAll();
    blockCache.invalidateAll();  // ← 删除
    heightCache.invalidateAll();
    log.info("All caches cleared");
}

✅ 修改后:
public void clearCaches() {
    blockInfoCache.invalidateAll();
    heightCache.invalidateAll();
    log.info("All caches cleared");
}
```

### 8. 更新printStatistics方法 (Line 270-281)
```java
❌ 删除Block Cache部分:
System.out.printf("  Block Cache:%n");
System.out.printf("    Size:      %,d entries%n", blockCache.size());
System.out.printf("    Hit rate:  %.1f%%%n", getBlockCacheHitRate());
```

### 9. 更新默认构造函数 (Line 63-65)
```java
❌ 修改前:
public CachedBlockStore(FinalizedBlockStore delegate) {
    this(delegate, 100_000, 10_000, 50_000);
    //                      ^^^^^^^  ← 删除blockCacheSize参数
}

✅ 修改后:
public CachedBlockStore(FinalizedBlockStore delegate) {
    this(delegate, 100_000, 50_000);
}
```

---

## BloomFilterBlockStore.java 修复清单

**同样的问题**：删除所有Block相关的方法和字段。

按照上面相同的模式修复BloomFilterBlockStore。

---

## 总结

**修复原则**：
1. ✅ 保留：所有BlockInfo相关的方法
2. ❌ 删除：所有Block相关的方法、字段、缓存
3. ✅ FinalizedBlockStore接口没有的方法，实现类也不应该有

**预期结果**：
- CachedBlockStore: 16 errors → 0 errors
- BloomFilterBlockStore: 14 errors → 0 errors

---

## IDEA 操作步骤

1. 打开 CachedBlockStore.java
2. 按上面清单逐条删除Block相关代码
3. 保存文件
4. 编译验证：`mvn compile -DskipTests`
5. 重复步骤 1-4 for BloomFilterBlockStore.java

---

## 为什么会有这些多余代码？

可能的原因：
- FinalizedBlockStore接口之前有Block方法，后来删除了
- CachedBlockStore实现时额外添加了Block缓存功能
- 代码演进过程中没有及时清理

现在我们彻底清理，让实现类与接口保持一致！
