# Chain Reorganization Bug Report - November 20, 2025

## Executive Summary

**CRITICAL BUG DISCOVERED**: Chain reorganization creates gaps in the main chain, leaving heights unmapped while mainChainLength continues to increment. This causes severe data inconsistency and breaks chain continuity.

## Problem Discovery

During testing of the height mapping deletion fix, we observed that Node2 successfully performed a chain reorganization by demoting blocks at heights 1-6. However, after reorganization:

- **Node2 reports**: `mainChainLength = 9`
- **Actual state**: Heights 2-6 are EMPTY (API returns `NOT_FOUND`)
- **Working heights**: 1, 7, 8, 9

### Evidence from Logs (20:17:06)

```
2025-11-20 | 20:17:06.975 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x76402be9525d483797d57ba785f3cd316439ed2a811da8fc72503edcca3bdff5 from height 6 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.975 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x92d4f4ae0fa2f3164731fadec3c940a73ed5dbf0d0c5dfa61d16bba1663e4e98 from height 5 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x782bff3bde6f3db86eec3c521b1d9212bf742f348ad0b3e5217c7ae4b617dd99 from height 4 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x23cefdce33401d31df7547b1bfcead64e6adbc01a1f3c51d25d9b2a8d795b889 from height 3 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x8806522ce79dfe978bad7da991c06cf6490f935eca4109cfed1e864711588983 from height 2 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2573] -- Demoted block 0x347a58443d0282f1a84996d5cfab4e3d0b0de5a62c9c0f8f481511b553de6c55 from height 1 to orphan (epoch competition loser)
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagChainImpl:2491] -- Demoted 6 blocks during chain reorganization
2025-11-20 | 20:17:06.976 [HybridSync-Scheduler] [INFO] [io.xdag.core.DagBlockProcessor:149] -- Block processed successfully: hash=0x3d3febce3d01510a6ce4f9e64720c95c2e0647b52d4ac1005f71cf40fed5a512, transactions=0, height=1
```

### Observations:
1. **Demotion worked correctly**: All 6 blocks (heights 1-6) were demoted to orphans
2. **Height 1 was filled**: Block `0x3d3febce3d01510a...` was promoted to height 1
3. **Heights 2-6 were NOT filled**: No promotion logs for these heights
4. **Chain continued at height 7**: Later blocks jumped to heights 7, 8, 9

### API Verification:

**Node1 vs Node2 Comparison:**
```
Height   | Node1 Block Hash                          | Node2 Block Hash      | Match
---------|-------------------------------------------|----------------------|-------
1        | 0x347a58443d0282f1...                     | 0x3d3febce3d01510a... | ✗
2        | 0x8806522ce79dfe97...                     | NOT_FOUND            | ✗
3        | 0x23cefdce33401d31...                     | NOT_FOUND            | ✗
4        | 0x782bff3bde6f3db8...                     | NOT_FOUND            | ✗
5        | 0xf4921625f286d4d4...                     | NOT_FOUND            | ✗
6        | 0x3d3febce3d01510a...                     | NOT_FOUND            | ✗
7        | 0x200c73037bcc2d72...                     | 0x200c73037bcc2d72... | ✓
8        | 0xa1479c01722cb407...                     | 0xa1479c01722cb407... | ✓
9        | -                                          | 0x1e658f08f6259a95... | -
```

**Node2 Status API:**
```json
{
    "mainChainLength": 9,
    "latestBlockHeight": 9,
    "latestBlockHash": "0x1e658f08f6259a95f10d3c9df4d592246b40b65ab2b10fa2f374a9aa346eaa50"
}
```

## Root Cause Analysis

### Location: `DagChainImpl.java:2473-2492`

**The `demoteBlocksFromHeight()` method:**

```java
private synchronized void demoteBlocksFromHeight(long fromHeight) {
    long currentHeight = chainStats.getMainBlockCount();

    log.info("Demoting blocks from height {} to {} (chain reorganization)",
            fromHeight, currentHeight);

    int demotedCount = 0;
    // Demote from highest to fromHeight (reverse order to maintain consistency)
    for (long height = currentHeight; height >= fromHeight; height--) {
        Block block = dagStore.getMainBlockByHeight(height, false);
        if (block != null) {
            demoteBlockToOrphan(block);  // ← This deletes height mappings correctly
            demotedCount++;
            log.debug("Demoted block {} from height {}",
                    block.getHash().toHexString().substring(0, 16), height);
        }
    }

    log.info("Demoted {} blocks during chain reorganization", demotedCount);

    // BUG: Missing step to promote replacement blocks to fill the gaps!
    // After demotion, heights fromHeight to currentHeight are now empty
    // But no code exists to promote new blocks into those empty slots
}
```

**The Problem:**
1. `demoteBlockToOrphan()` correctly calls `dagStore.deleteHeightMapping(height)` - our fix works! ✓
2. Old blocks are demoted and height mappings are deleted ✓
3. **BUT**: No code promotes replacement blocks to fill the now-empty heights ✗
4. Later blocks continue to extend from the highest remaining height

### Why This Happens:

When blocks are imported via `tryToConnect()`:
- `determineNaturalHeight(block)` calculates height based on parent blocks
- If parent blocks are orphans (height=0) or missing, natural height calculation is wrong
- Block either becomes orphan OR gets assigned to next available height after gaps
- The gaps at heights 2-6 are never filled

### Expected Behavior vs Actual Behavior:

**Expected:**
```
1. Demote blocks at heights 1-6
2. Identify replacement blocks that should fill heights 1-6
3. Promote those blocks in order: height 1 → 2 → 3 → 4 → 5 → 6
4. Result: Continuous chain with no gaps
```

**Actual:**
```
1. Demote blocks at heights 1-6 ✓
2. Import new blocks, but they:
   - Either become orphans (height=0) because parent height calculation fails
   - OR jump to next available height (7, 8, 9) ignoring the gaps
3. Result: Chain with gaps at heights 2-6
```

## Impact

### Severity: CRITICAL

1. **Data Inconsistency**: `mainChainLength` reports 9 but only 4 heights exist (1, 7, 8, 9)
2. **API Failures**: `/api/v1/blocks/{height}` returns `NOT_FOUND` for heights 2-6
3. **Chain Continuity Broken**: Main chain has gaps, violating the fundamental invariant that heights must be continuous
4. **Sync Protocol Fails**: `getMainBlocksByHeightRange()` will return nulls for gap heights
5. **Consensus Issues**: Nodes cannot agree on blocks at specific heights
6. **Height-Based Queries Broken**: Any query by height in the gap range fails

### Affected Components:

- ✗ **DagChainImpl.demoteBlocksFromHeight()** - Doesn't fill gaps after demotion
- ✗ **DagChainImpl.performChainReorganization()** - Doesn't ensure continuous height sequence
- ✗ **DagChainImpl.determineNaturalHeight()** - Can't correctly calculate height when parents are orphans
- ✗ **HybridSyncManager** - Fork detection relies on continuous height sequence
- ✓ **DagStoreImpl.deleteHeightMapping()** - Works correctly (deletes old mappings)
- ✓ **DagStoreImpl.saveBlock()** - Works correctly (writes new mappings)

## Solution Design

### Approach 1: Fix `demoteBlocksFromHeight()` to Promote Replacement Blocks

After demoting blocks, scan orphan blocks and promote them to fill gaps:

```java
private synchronized void demoteBlocksFromHeight(long fromHeight) {
    long currentHeight = chainStats.getMainBlockCount();

    // Step 1: Demote blocks (existing code)
    List<Block> demotedBlocks = new ArrayList<>();
    for (long height = currentHeight; height >= fromHeight; height--) {
        Block block = dagStore.getMainBlockByHeight(height, false);
        if (block != null) {
            demoteBlockToOrphan(block);
            demotedBlocks.add(block);
        }
    }

    // Step 2: NEW - Find replacement blocks to fill gaps
    // Get all blocks in the relevant epoch range
    long minEpoch = demotedBlocks.stream()
            .mapToLong(Block::getEpoch)
            .min()
            .orElse(0);

    long maxEpoch = XdagTime.getCurrentEpochNumber();

    List<Block> replacementCandidates = new ArrayList<>();
    for (long epoch = minEpoch; epoch <= maxEpoch; epoch++) {
        List<Block> candidates = dagStore.getCandidateBlocksInEpoch(epoch);
        // Filter to orphans with sufficient cumulative difficulty
        candidates.stream()
                .filter(b -> b.getInfo() != null && b.getInfo().getHeight() == 0)
                .filter(b -> b.getInfo().getDifficulty().compareTo(UInt256.ZERO) > 0)
                .forEach(replacementCandidates::add);
    }

    // Step 3: Sort candidates by cumulative difficulty and promote to fill gaps
    replacementCandidates.sort((b1, b2) ->
            b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty()));

    long nextHeight = fromHeight;
    for (Block candidate : replacementCandidates) {
        if (nextHeight > currentHeight) {
            break;  // All gaps filled
        }

        promoteBlockToHeight(candidate, nextHeight);
        log.info("Filled gap at height {} with block {}",
                nextHeight, candidate.getHash().toHexString().substring(0, 16));
        nextHeight++;
    }

    // Step 4: Update chain stats
    chainStats = chainStats.withMainBlockCount(Math.max(currentHeight, nextHeight - 1));
    dagStore.saveChainStats(chainStats);
}
```

### Approach 2: Prevent Gaps by Smarter Height Assignment

Modify `tryToConnect()` to detect gaps and assign blocks to fill them:

```java
// In tryToConnect(), after calculating cumulative difficulty
long assignedHeight;
if (naturalHeight > 0 && naturalHeight <= chainStats.getMainBlockCount()) {
    // Check if this height is empty (gap)
    Block existingBlock = dagStore.getMainBlockByHeight(naturalHeight, false);
    if (existingBlock == null) {
        // Gap detected - fill it with this block
        assignedHeight = naturalHeight;
        log.info("Filling gap at height {} with block {}",
                assignedHeight, block.getHash().toHexString().substring(0, 16));
    } else {
        // Height occupied - compare difficulties for reorganization
        assignedHeight = compareAndReorganize(block, existingBlock, naturalHeight);
    }
} else {
    // Normal case - extend chain
    assignedHeight = chainStats.getMainBlockCount() + 1;
}
```

### Recommended Solution: Combination of Both Approaches

1. **Immediate fix**: Implement Approach 1 to handle existing reorganization gaps
2. **Long-term fix**: Implement Approach 2 to prevent gaps during normal operation
3. **Add validation**: Implement `verifyMainChainContinuity()` check after reorganization

## Testing Plan

### Test Case 1: Chain Reorganization with Gap Filling

**Setup:**
```
Node1: Heights 1-10 (reference chain)
Node2: Heights 1-10 (weaker fork)
```

**Action:**
1. Node2 receives Node1's blocks
2. HybridSync detects fork at height 1
3. Triggers `demoteBlocksFromHeight(1)`

**Expected Result:**
- Node2 demotes heights 1-10
- Node2 promotes Node1's blocks to heights 1-10
- No gaps in height sequence
- `verifyMainChainContinuity(1, 10)` returns true

### Test Case 2: Out-of-Order Block Import

**Setup:**
```
Node receives blocks in order: H7, H9, H3, H5, H1, H8, H2, H4, H6
```

**Action:**
1. Import each block via `tryToConnect()`
2. Check height assignments after each import

**Expected Result:**
- Blocks fill heights sequentially: 1, 2, 3, 4, 5, 6, 7, 8, 9
- No gaps created
- mainChainLength === number of blocks at height > 0

### Test Case 3: Recovery from Existing Gaps

**Setup:**
```
Chain currently has: Heights 1, 7, 8, 9 (gaps at 2-6)
Orphan blocks exist for heights 2-6
```

**Action:**
1. Call `checkNewMain()` or trigger reorganization
2. Run gap detection and filling logic

**Expected Result:**
- Gaps at 2-6 are filled with appropriate orphan blocks
- mainChainLength === actual number of blocks
- All height queries return valid blocks

## Files Affected

### Primary Files Requiring Changes:

1. **`/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/core/DagChainImpl.java`**
   - Method: `demoteBlocksFromHeight()` (line 2473)
   - Method: `tryToConnect()` - Add gap detection
   - Method: `verifyMainChainConsistency()` - Add gap validation

2. **`/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/consensus/sync/HybridSyncManager.java`**
   - Add gap detection in fork handling
   - Verify continuous height sequence before accepting sync

### Testing Files to Create:

1. **`/Users/reymondtu/dev/github/xdagj/src/test/java/io/xdag/core/DagChainReorganizationGapTest.java`**
   - Test reorganization gap filling
   - Test out-of-order block import
   - Test gap recovery

## Immediate Actions Required

1. ✗ **STOP PRODUCTION USE** - This bug breaks chain continuity
2. ⏳ **Implement Approach 1** - Add gap filling to `demoteBlocksFromHeight()`
3. ⏳ **Add Validation** - Verify no gaps after reorganization
4. ⏳ **Write Tests** - Comprehensive test coverage for gap scenarios
5. ⏳ **Deploy Fix** - Test in devnet before production

## Related Issues

- **HEIGHT_MAPPING_BUG_FIX_20251120.md** - Fixed height mapping deletion (prerequisite for this fix)
- Height mapping deletion is working correctly; this bug is in the reorganization logic

## Conclusion

The height mapping deletion fix (deleteHeightMapping) is working correctly. The new bug is in the **chain reorganization logic** which fails to promote replacement blocks to fill gaps after demotion. This creates a chain with discontinuous heights, breaking core invariants and causing API failures.

**Priority**: CRITICAL - Must be fixed before production deployment.

---
**Report Date**: November 20, 2025, 20:25
**Reported By**: Claude Code Testing Session
**Status**: BUG CONFIRMED - Solution designed, implementation pending
