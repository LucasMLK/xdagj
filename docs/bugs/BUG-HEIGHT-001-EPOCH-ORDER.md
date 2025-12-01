# BUG-HEIGHT-001: Height Assignment by Epoch Order

## Summary
| Key | Value |
|-----|-------|
| Severity | P2 (data consistency) |
| Discovered | 2025-12-01 |
| Status | Fixed |

## Problem

Different nodes assigned different heights to the same blocks because heights were assigned based on **arrival order** rather than **epoch order**.

**Example**:
```
Node 1 receives blocks:  A (epoch 100) → B (epoch 101) → C (epoch 102)
Node 1 heights:          A=1, B=2, C=3

Node 2 receives blocks:  C (epoch 102) → A (epoch 100) → B (epoch 101)
Node 2 heights:          C=1, A=2, B=3   ← WRONG! Same block, different heights
```

## Root Cause

In `BlockImporter.assignHeight()`:
```java
// OLD (BUG): Assign height based on current chain length
height = chainStats.getMainBlockCount() + 1;
```

This assigns heights based on when blocks arrive, not their epoch order.

## Solution

Assign heights based on epoch order:

```java
// NEW (FIX): Calculate correct position based on epoch order
height = calculateHeightByEpochOrder(blockEpoch, chainStats);

private long calculateHeightByEpochOrder(long blockEpoch, ChainStats chainStats) {
    List<Block> mainBlocks = dagStore.getMainBlocksByHeightRange(1, chainStats.getMainBlockCount());

    // Find correct insertion position based on epoch order
    int insertPosition = 0;
    for (int i = 0; i < mainBlocks.size(); i++) {
        if (blockEpoch > mainBlocks.get(i).getEpoch()) {
            insertPosition = i + 1;
        } else {
            break;
        }
    }

    // If inserting in the middle, shift subsequent heights
    if (insertPosition < mainBlocks.size()) {
        shiftHeights(insertPosition, mainBlocks);
    }

    return insertPosition + 1;
}
```

## Impact

After fix:
- All nodes assign the same height to the same block
- Heights are determined by epoch order, not arrival order
- API queries return consistent results across nodes

## Testing

Two-node test verified:
```
Node 1: Height 6 = 0x2e632194c385427476... (epoch 27571493)
Node 2: Height 6 = 0x2e632194c385427476... (epoch 27571493)  ✓ MATCH
```

## Related Bugs

| Bug ID | Relation |
|--------|----------|
| BUG-SYNC-004 | Sync order affects arrival order |
| BUG-CONSENSUS-007 | Height race condition (different issue) |
