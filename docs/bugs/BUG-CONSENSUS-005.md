# BUG-CONSENSUS-005: EpochContext Creation Timing Issue

## Summary
**Status**: 🔴 Critical - Blocks are not being produced after genesis
**Priority**: P0
**Discovered**: 2025-11-24 during Phase 4 integration testing

## Problem Description

### Symptoms
- No solutions collected for any epoch after genesis
- Backup miner reports "epoch context not found"
- Block height remains at 1 (only genesis block)
- No blocks are produced despite epoch boundaries triggering correctly

### Root Cause
**EpochTimer and EpochConsensusManager have mismatched epoch numbering**:

1. **Timing Issue**: When epoch N ends (at time T), `TimeUtils.getCurrentEpochNumber()` already returns N+1 because we're at the start of epoch N+1
2. **Context Mismatch**: EpochTimer calls `onEpochEnd(currentEpoch)` which passes N+1, but the context for N+1 doesn't exist yet
3. **Missing Initial Context**: At startup, only current epoch's context is created, not the next epoch's

### Evidence from Logs

```
20:13:40.470 [main] -- Created mining candidate block: epoch=27562287
20:13:40.477 [main] -- EpochTimer starting: current_epoch=27562287, target_epoch=27562287

20:13:52.006 [EpochTimer] -- ═══════════ Epoch 27562288 ended ═══════════
20:13:52.006 [EpochTimer] -- ═══════════ Processing Epoch 27562288 End ═══════════
20:13:52.006 [EpochTimer] -- ⚠ No solutions collected for epoch 27562288

20:15:55.003 [BackupMinerScheduler] -- Cannot trigger backup miner: epoch context not found for epoch 27562289
```

**Analysis**:
- Timer started targeting epoch 27562287's end (time: 1763986431999ms)
- When timer fired, `getCurrentEpoch()` returned 27562288 (already in next epoch)
- Tried to process epoch 27562288's end, but context was never created
- Context for epoch 27562288 should have been created when epoch 27562287 ended

## Detailed Flow

### Expected Flow
```
1. Startup (epoch 27562287):
   - Create context for epoch 27562287
   - Create context for epoch 27562288 (next)

2. Epoch 27562287 ends:
   - Process epoch 27562287 solutions
   - Import best block
   - Context for 27562288 already exists
   - Create context for epoch 27562289

3. Epoch 27562288 ends:
   - Process epoch 27562288 solutions
   - Context already exists
   - ...
```

### Actual Flow
```
1. Startup (epoch 27562287):
   - Create context for epoch 27562287 ✓
   - Missing: context for epoch 27562288 ✗

2. Timer fires (at epoch 27562288 start):
   - getCurrentEpoch() returns 27562288
   - onEpochEnd(27562288) called
   - Tries to get context for 27562288 ✗ NOT FOUND
   - Creates context for epoch 27562289 (wrong!)

3. Backup miner triggers (T=59s of epoch 27562289):
   - Looks for context 27562289 ✗ NOT FOUND
   - Cannot mine
```

## Fix Strategy

### Option 1: Fix EpochTimer to pass correct epoch (RECOMMENDED)
EpochTimer should pass the epoch that just ended, not the current epoch:

```java
// EpochTimer.java line 136-141
epochScheduler.scheduleAtFixedRate(
        () -> {
            try {
                // BUG FIX: Pass the epoch that just ended, not the current one
                long currentEpochNum = getCurrentEpoch();
                long endedEpochNum = currentEpochNum - 1;  // ← Add this
                log.info("═══════════ Epoch {} ended ═══════════", endedEpochNum);
                onEpochEnd.accept(endedEpochNum);  // ← Change this
            } catch (Exception e) {
                log.error("Error processing epoch end", e);
            }
        },
        initialDelay,
        epochDurationMs,
        TimeUnit.MILLISECONDS
);
```

### Option 2: Add initial context creation
In DagKernel startup, create context for both current and next epoch:

```java
// After EpochConsensusManager initialization
long currentEpoch = TimeUtils.getCurrentEpochNumber();
epochConsensusManager.createInitialContexts(currentEpoch, currentEpoch + 1);
```

### Option 3: Fix onEpochEnd to handle current epoch
Make onEpochEnd expect the current epoch number and process previous epoch:

```java
private void onEpochEnd(long currentEpoch) {
    long endedEpoch = currentEpoch - 1;
    log.info("═══════════ Processing Epoch {} End ═══════════", endedEpoch);
    // ... process endedEpoch
}
```

## Recommended Solution

**Combination of Option 1 + startup fix**:

1. **Fix EpochTimer** to pass `currentEpoch - 1` to onEpochEnd
2. **Add startup logic** to create initial context for current+1 epoch
3. **Add defensive check** in onEpochEnd to create missing context if needed

This ensures:
- Correct epoch numbering throughout the system
- Robustness against edge cases
- Clear semantics: onEpochEnd(N) processes epoch N's end

## Files to Modify

1. `src/main/java/io/xdag/consensus/epoch/EpochTimer.java`
   - Line 139: Calculate ended epoch number
   - Line 140: Log ended epoch
   - Line 141: Pass ended epoch to callback

2. `src/main/java/io/xdag/DagKernel.java`
   - After EpochConsensusManager.start(): Create initial context for next epoch

3. `src/main/java/io/xdag/consensus/epoch/EpochConsensusManager.java`
   - Add defensive check in onEpochEnd
   - Add public method for creating initial contexts

## Testing Plan

1. **Verify epoch context creation**:
   ```bash
   grep "Generated candidate block" logs/xdag-info.log
   # Should see context for current + next epoch at startup
   ```

2. **Verify backup miner can find context**:
   ```bash
   grep "Cannot trigger backup miner" logs/xdag-info.log
   # Should be empty
   ```

3. **Verify blocks are produced**:
   ```bash
   curl -s http://127.0.0.1:10001/api/stats | jq '.height'
   # Should increment over time
   ```

4. **Verify epoch processing**:
   ```bash
   grep "Processing Epoch.*End" logs/xdag-info.log
   # Should see consecutive epoch numbers without gaps
   ```

## Related Issues

- **BUG-CONSENSUS-003**: Candidate block was null (fixed - BlockGenerator injection)
- **BUG-CONSENSUS-004**: EpochTimer initial delay calculation (fixed)
- **BUG-CONSENSUS-005**: This issue - epoch context creation timing

## Impact

**Severity**: CRITICAL
- **No new blocks produced** after genesis
- **Chain cannot progress**
- **Miners cannot participate**
- **Network cannot function**

This blocks all Phase 4 testing and must be fixed immediately.
