# Height Mapping Bug Fix - November 20, 2025

## Problem Discovery

While testing the chain reorganization fix with a comparison script (`compare_chain_heights.sh`), I discovered a **critical database bug**: Node2's blockchain had multiple blocks mapping to the same heights, with different blocks returning for the same height query.

### Symptoms:
- Height 2-81 at Node2 all returned the same block hash: `0x7cabf58c611da63b...`
- This hash actually belonged to Node1's block at height 1
- The script correctly showed the database state - blocks were being stored at multiple heights simultaneously

### Root Cause:
Located in `DagStoreImpl.saveBlock()` (line 204-207):
```java
// 5. Index by height (if main block)
if (info.getHeight() > 0) {
  byte[] heightKey = buildHeightIndexKey(info.getHeight());
  batch.put(heightKey, hash.toArray());  // ← Only WRITES, never DELETES
}
```

**The Problem:** When `demoteBlockToOrphan()` changes a block's height from X to 0, it calls `saveBlock()` which:
1. Skips writing a new height index (because height=0)
2. **Does NOT delete the old height->hash mapping at height X**
3. Result: Old mapping remains in database forever

When the same block is later promoted to a different height Y:
- Database now has TWO mappings for the same block
- Height X → block hash (orphaned mapping)
- Height Y → block hash (current mapping)

## Solution Implemented

### 1. Added new interface method in `DagStore.java`:
```java
/**
 * Delete height-to-hash mapping for a specific height
 * This is used during chain reorganization to remove orphaned height mappings.
 */
void deleteHeightMapping(long height);
```

### 2. Implemented in `DagStoreImpl.java`:
```java
@Override
public void deleteHeightMapping(long height) {
    if (height <= 0) {
        log.warn("Invalid height for deletion: {}", height);
        return;
    }

    try {
        // Delete height->hash mapping
        byte[] heightKey = buildHeightIndexKey(height);
        db.delete(writeOptions, heightKey);

        // Invalidate cache
        cache.invalidateHeightMapping(height);

        log.debug("Deleted height mapping for height: {}", height);

    } catch (RocksDBException e) {
        log.error("Failed to delete height mapping for height {}: {}", height, e.getMessage());
        throw new RuntimeException("Failed to delete height mapping", e);
    }
}
```

### 3. Updated `DagChainImpl.demoteBlockToOrphan()`:
```java
// BUGFIX: Delete old height mapping BEFORE updating BlockInfo
try {
    dagStore.deleteHeightMapping(previousHeight);
    log.debug("Deleted height mapping for height {} before demotion",
            previousHeight);
} catch (Exception e) {
    log.error("Failed to delete height mapping for height {}: {}",
            previousHeight, e.getMessage());
    // Continue with demotion even if delete fails (safer to continue than abort)
}
```

## Testing Tools Created

### `compare_chain_heights.sh`
A diagnostic script that:
- Queries both nodes' API for block hashes at each height
- Displays side-by-side comparison with colored output
- Shows match/mismatch status
- Provides summary statistics

Usage:
```bash
cd /Users/reymondtu/dev/github/xdagj/test-nodes
./compare_chain_heights.sh
```

This script was instrumental in discovering the bug and will be useful for verifying the fix.

## Next Steps

1. ✅ Fix implemented in code
2. ⏳ Rebuild xdagj with fixes
3. ⏳ Deploy to test environment
4. ⏳ Run `compare_chain_heights.sh` to verify chains converge properly
5. ⏳ Monitor logs for "Deleted height mapping" messages
6. ⏳ Verify no duplicate height mappings occur

## Impact

This fix ensures:
- **Data Integrity**: Each height maps to exactly one block
- **Consensus Correctness**: Nodes can properly reorganize chains without corruption
- **API Accuracy**: `/api/v1/blocks/{height}` returns correct blocks
- **Clean Reorganization**: Old height mappings are properly cleaned up

## Files Modified

1. `/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/db/DagStore.java`
   - Added `deleteHeightMapping(long height)` interface method

2. `/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/db/rocksdb/impl/DagStoreImpl.java`
   - Implemented `deleteHeightMapping()` with RocksDB delete operation
   - Includes cache invalidation

3. `/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/core/DagChainImpl.java`
   - Modified `demoteBlockToOrphan()` to delete old height mapping before demotion
   - Added error handling to continue even if delete fails

## Files Created

1. `/Users/reymondtu/dev/github/xdagj/test-nodes/compare_chain_heights.sh`
   - Diagnostic script for comparing block hashes at each height between two nodes
