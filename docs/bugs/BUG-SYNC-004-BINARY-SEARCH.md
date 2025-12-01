# BUG-SYNC-004: Binary Search Sync for Large Epoch Gaps

## Summary
| Key | Value |
|-----|-------|
| Severity | P1 (memory explosion, sync failure) |
| Discovered | 2025-12-01 |
| Status | Fixed |

## Problem

When a new node joins the network and is far behind the network tip (>1024 epochs), the original forward sync algorithm caused memory explosion and height inconsistency:

1. **Memory explosion**: Forward sync requested all epochs sequentially, but block import triggered recursive dependency fetching for each block's parent links, leading to O(n) memory usage.

2. **Height inconsistency**: During initial sync, real-time `NEW_BLOCK_HASH` broadcasts were processed, causing blocks to be imported out of order. This led to different nodes assigning different heights to the same blocks.

## Root Cause

### Memory Issue
```
Forward sync: epoch 100 → 101 → 102 → ... → 10000
Each block import: fetch parent → fetch grandparent → ...
Result: Memory grows unbounded as dependency chains accumulate
```

### Height Inconsistency
```
Node 1 (synced):     Block A imported at height 5
Node 2 (syncing):    Block B (from broadcast) imported at height 5
                     Block A (from sync) imported at height 6
Result: Same block, different heights across nodes
```

## Solution

### 1. Binary Search Algorithm

Instead of O(n) forward sync, use binary search to locate the minimum epoch where peer has blocks:

```
Binary search: probe epoch 5000 → 2500 → 3750 → ...
Result: Find starting point in O(log n) iterations
```

**Algorithm**:
1. Gap > 1024 epochs → enter binary search mode
2. Probe midpoint epoch with `GET_EPOCH_HASHES`
3. If blocks found → search lower half (peer has older blocks)
4. If no blocks → search upper half (peer's history starts later)
5. Terminate when range <= 256 or iterations >= 20
6. Switch to forward sync from `minValidEpochFound`

### 2. Sync Filter for Real-time Broadcasts

During initial sync, ignore `NEW_BLOCK_HASH` messages:

```java
// XdagP2pEventHandler.handleNewBlockHash()
if (syncManager != null && !syncManager.isSynchronized()) {
    log.debug("Ignoring NEW_BLOCK_HASH during initial sync");
    return;
}
```

### 3. State Machine

```
FORWARD_SYNC ──gap>1024──► BINARY_SEARCH ──complete──► FORWARD_SYNC
                                 │
                                 └── forwardSyncStartEpoch prevents re-trigger
```

## Implementation

### Key Files
- `SyncManager.java` - Binary search state machine
- `XdagP2pEventHandler.java` - Sync filter in handleNewBlockHash()

### Key Fields
```java
private final AtomicReference<SyncState> syncState;
private final AtomicLong binarySearchLow;
private final AtomicLong binarySearchHigh;
private final AtomicLong minValidEpochFound;
private final AtomicLong forwardSyncStartEpoch;  // Prevents re-trigger
```

### Configuration
| Parameter | Value | Description |
|-----------|-------|-------------|
| BINARY_SEARCH_THRESHOLD | 1024 | Gap to trigger binary search |
| MAX_BINARY_SEARCH_ITERATIONS | 20 | Max iterations before fallback |
| MAX_EPOCHS_PER_REQUEST | 256 | Termination range condition |

## Testing

### Unit Tests (`SyncManagerTest`)
- `binarySearch_ShouldTriggerWhenLargeGap` - Trigger at threshold
- `binarySearch_ShouldNotTriggerWhenSmallGap` - Skip for small gaps
- `binarySearch_AfterComplete_ShouldSendRequestDespiteLargeGap` - Forward sync works after binary search
- `binarySearch_ShouldCompleteAfterMaxIterations` - Iteration limit
- `forwardSyncStartEpoch_ShouldPreventReTrigger` - No re-trigger

### Integration Test
Two-node test verified:
- Both nodes sync to same height
- All block hashes match at each height
- No height inconsistency after sync

## Related Bugs

| Bug ID | Relation |
|--------|----------|
| BUG-SYNC-001 | Mining gating (prevents orphans during sync) |
| BUG-HEIGHT-001 | Height assignment by epoch order |
