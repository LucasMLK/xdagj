# XDAG Consensus Mechanism Analysis - November 20, 2025

## Executive Summary

**CRITICAL ISSUE DISCOVERED**: Current implementation混合了两种冲突的共识机制（Height-based vs Epoch-based），导致在以下场景中产生逻辑错误：
1. 同一个高度（height）但不同epoch的blocks应该如何竞争？
2. Epoch不连续时（节点停机）的reorganization逻辑
3. 同一个epoch内发生主块回滚时的正确处理

## 1. XDAG Core Consensus Rules

### 1.1 Fundamental Rules (from C code and whitepaper)

```
Rule 1: Epoch-based Block Production
- Every 64 seconds = 1 epoch
- Blocks are produced continuously, assigned to epochs by timestamp
- Multiple blocks can exist in ONE epoch (candidate blocks)

Rule 2: Epoch Winner Selection (Hash Competition)
- Within each epoch, the block with SMALLEST hash wins
- Only ONE winner per epoch becomes a main block
- Losers become orphan blocks (but remain valid and referenceable)

Rule 3: Main Chain Construction
- Main chain = sequence of epoch winners
- Heights are assigned to winners: 1, 2, 3, 4, ...
- Heights MUST be continuous (no gaps)
- Epochs MAY be discontinuous (e.g., 100, 101, 103, 105 if node was offline)

Rule 4: Cumulative Difficulty Calculation
- Only cross-epoch references accumulate difficulty
- Same-epoch references do NOT accumulate difficulty
- This ensures fair competition within each epoch

Rule 5: Chain Selection (Fork Resolution)
- When forks exist, choose the chain with highest cumulative difficulty
- If difficulty equal, choose chain with smaller hash at divergence point
```

### 1.2 Example Scenario: Non-Continuous Epochs

```
Normal Operation:
Height 1: epoch 100, block A
Height 2: epoch 101, block B
Height 3: epoch 102, block C

Node Offline (epoch 103-104 missed):
Height 1: epoch 100, block A
Height 2: epoch 101, block B
Height 3: epoch 105, block D  ← Epoch 105! (skipped 102-104)
Height 4: epoch 106, block E

This is VALID and EXPECTED behavior!
```

## 2. Current Implementation Problems

### 2.1 Problem 1: Conflicting Competition Mechanisms

**Location**: `DagChainImpl.tryToConnect()` lines 297-406

Current code has TWO competing mechanisms:

#### Mechanism A: Height-based Competition (lines 302-341)
```java
// Check if we need to reorganize at this height
Block existingBlock = null;
if (naturalHeight > 0 && naturalHeight <= chainStats.getMainBlockCount()) {
    existingBlock = dagStore.getMainBlockByHeight(naturalHeight, false);
}

if (existingBlock != null) {
    // Compare cumulative difficulties
    if (cumulativeDifficulty.compareTo(existingDifficulty) > 0) {
        // New block replaces existing block AT SAME HEIGHT
        demoteBlocksFromHeight(naturalHeight);
        height = naturalHeight;
        isBestChain = true;
    }
}
```

#### Mechanism B: Epoch-based Competition (lines 356-406)
```java
// Epoch competition check
long blockEpoch = block.getEpoch();
Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

boolean epochWinner = currentWinner == null ||
                      block.getHash().compareTo(currentWinner.getHash()) < 0;

if (epochWinner && currentWinner != null) {
    // This block wins epoch competition
    long replacementHeight = currentWinner.getInfo().getHeight();
    demoteBlockToOrphan(currentWinner);
    height = replacementHeight;  // REPLACEMENT at SAME height
    isBestChain = true;
}
```

**THE CONFLICT**:
- Mechanism A says: "Replace based on cumulative difficulty comparison"
- Mechanism B says: "Replace based on hash comparison"
- These can give DIFFERENT results!

### 2.2 Problem 2: What happens when Height = Epoch competition?

**Scenario**: Two blocks competing for SAME height but DIFFERENT epochs

```
Existing block at height 5:
  - Block A: height=5, epoch=102, hash=0x5678..., cumDiff=1000

New block arrives:
  - Block B: height=5, epoch=105, hash=0x1234..., cumDiff=1200
```

**Questions**:
1. Should Block B win because cumDiff (1200) > Block A's cumDiff (1000)? ← Height competition
2. Should they NOT compete because they're in different epochs? ← Epoch competition
3. What if Block B's hash (0x1234) < Block A's hash (0x5678)? Does hash matter across epochs?

**Current code behavior**:
- First, height competition runs → Block B might win based on cumDiff
- Then, epoch competition runs → But they're in different epochs, so no competition
- **RESULT**: Block B can replace Block A based on cumDiff alone

**Is this correct?**

### 2.3 Problem 3: determineNaturalHeight() may be wrong

**Location**: `DagChainImpl.determineNaturalHeight()` lines 2186-2217

```java
private long determineNaturalHeight(Block block) {
    // Find the highest parent block height
    long maxParentHeight = 0;
    for (Link link : block.getLinks()) {
        if (link.isBlock()) {
            Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
            if (parent != null && parent.getInfo() != null) {
                long parentHeight = parent.getInfo().getHeight();
                if (parentHeight > maxParentHeight) {
                    maxParentHeight = parentHeight;
                }
            }
        }
    }
    return maxParentHeight + 1;
}
```

**Problem**: What if parent is an ORPHAN (height=0)?
- maxParentHeight = 0
- naturalHeight = 0 + 1 = 1
- But height 1 might already be occupied!

**What if parent was DEMOTED during reorganization?**
- Parent used to be height=5, now height=0
- Child's naturalHeight = 0 + 1 = 1
- But child should actually be at height=6!

## 3. Correct Consensus Logic

### 3.1 Primary Rule: Epoch Competition (ALWAYS applies)

```
For any block B at epoch E:
  1. Find all blocks in epoch E
  2. Winner = block with smallest hash
  3. IF B.hash < currentWinner.hash:
       - Demote currentWinner to orphan
       - Promote B to main block
       - B inherits currentWinner's height (REPLACEMENT)
  4. ELSE:
       - B becomes orphan
```

**Key Insight**: Epoch competition operates at the EPOCH level, not height level!

### 3.2 Secondary Rule: Height Assignment

```
When assigning heights to epoch winners:
  1. Heights must be continuous: 1, 2, 3, 4, ...
  2. Epochs may be discontinuous: 100, 101, 105, 107, ...
  3. Height order follows cumulative difficulty order

Algorithm:
  1. Sort all epoch winners by cumulative difficulty (descending)
  2. Assign heights sequentially: winner[0]=height 1, winner[1]=height 2, ...
  3. If tie in cumulative difficulty, sort by epoch (ascending)
```

### 3.3 Tertiary Rule: Fork Resolution

```
When two forks exist at SAME HEIGHT with DIFFERENT EPOCHS:

Example:
  Fork A: height 5 = epoch 102, cumDiff=1000
  Fork B: height 5 = epoch 105, cumDiff=1200

Resolution:
  1. Compare cumulative difficulties
  2. IF Fork B cumDiff > Fork A cumDiff:
       - Fork B wins
       - Reorganize: demote Fork A blocks, promote Fork B blocks
  3. ELSE IF cumDiff equal:
       - Compare hash at fork point
       - Smaller hash wins
```

**Key Insight**: Height competition happens during REORGANIZATION, not during normal block import!

## 4. Correct Implementation Strategy

### 4.1 Separate Concerns

```
Phase 1: Epoch Competition (During tryToConnect)
  - Determine if block wins its epoch
  - If yes: become epoch winner (but height TBD)
  - If no: become orphan

Phase 2: Height Assignment (Periodic / After sync)
  - Collect all epoch winners
  - Sort by cumulative difficulty
  - Assign continuous heights 1, 2, 3, ...
  - Detect gaps and fill from orphans

Phase 3: Fork Resolution (When better fork detected)
  - Compare forks by cumulative difficulty
  - Reorganize if better fork found
  - Demote old fork, promote new fork
```

### 4.2 Simplified tryToConnect() Logic

```java
public synchronized DagImportResult tryToConnect(Block block) {
    // 1. Validation
    validateBasicRules(block);
    validateMinimumPoW(block);
    validateLinks(block);
    validateDAGRules(block);

    // 2. Calculate cumulative difficulty
    UInt256 cumulativeDifficulty = calculateCumulativeDifficulty(block);

    // 3. Epoch Competition (PRIMARY rule)
    long blockEpoch = block.getEpoch();
    Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

    boolean isEpochWinner = (currentWinner == null) ||
                            (block.getHash().compareTo(currentWinner.getHash()) < 0);

    if (isEpochWinner) {
        if (currentWinner != null) {
            // Replace current winner
            long winnerHeight = currentWinner.getInfo().getHeight();
            demoteBlockToOrphan(currentWinner);

            // NEW winner takes the SAME height (replacement, not addition)
            assignBlockHeight(block, winnerHeight, cumulativeDifficulty);

        } else {
            // First block in this epoch
            // Height assignment deferred to checkNewMain()
            assignBlockHeight(block, 0, cumulativeDifficulty);  // Mark as pending
        }
    } else {
        // Lost epoch competition → orphan
        assignBlockHeight(block, 0, cumulativeDifficulty);
    }

    // 4. Trigger height re-assignment if needed
    if (isEpochWinner) {
        checkNewMain();  // Re-calculate heights for all epoch winners
    }

    return result;
}
```

### 4.3 Height Assignment Logic

```java
private synchronized void checkNewMain() {
    // Collect all epoch winners
    List<Block> epochWinners = new ArrayList<>();

    long currentEpoch = getCurrentEpoch();
    long scanStartEpoch = Math.max(1, currentEpoch - ORPHAN_RETENTION_WINDOW);

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
        Block winner = getWinnerBlockInEpoch(epoch);
        if (winner != null) {
            epochWinners.add(winner);
        }
    }

    // Sort by cumulative difficulty (descending)
    epochWinners.sort((b1, b2) -> {
        int diffCompare = b2.getInfo().getDifficulty().compareTo(b1.getInfo().getDifficulty());
        if (diffCompare != 0) return diffCompare;

        // If tie, sort by epoch (ascending) - earlier epochs first
        return Long.compare(b1.getEpoch(), b2.getEpoch());
    });

    // Assign heights: 1, 2, 3, ...
    long height = 1;
    for (Block winner : epochWinners) {
        if (winner.getInfo().getHeight() != height) {
            promoteBlockToHeight(winner, height);
        }
        height++;
    }

    // Update chain stats
    chainStats = chainStats.withMainBlockCount(height - 1);
    dagStore.saveChainStats(chainStats);
}
```

## 5. Specific Bug: Same Height, Different Epoch

### 5.1 Scenario

```
Current state:
  Height 5: epoch=102, block A, hash=0x5678..., cumDiff=1000

New block arrives:
  Block B: epoch=105, hash=0x1234..., cumDiff=1200

Questions:
  1. What should Block B's height be?
  2. Should Block B replace Block A?
```

### 5.2 Correct Answer

```
Step 1: Epoch Competition
  - Block B is in epoch 105
  - Get winner of epoch 105
  - If Block B has smallest hash in epoch 105:
      → Block B wins epoch 105
      → Block B becomes epoch winner (height TBD)

Step 2: Height Assignment
  - Block B's natural height = max(parent heights) + 1
  - If Block B's cumDiff (1200) > Block A's cumDiff (1000):
      → Block B has higher cumulative difficulty
      → Block B should be AHEAD of Block A in the chain
      → Reorganization needed!

Step 3: Reorganization
  - Find fork point (common ancestor of A and B)
  - Demote Block A (and all descendants)
  - Promote Block B
  - Re-assign heights to maintain continuity

Result:
  Height 5: epoch=105, block B, hash=0x1234..., cumDiff=1200
  Block A: height=0 (orphan), epoch=102, hash=0x5678..., cumDiff=1000
```

### 5.3 Key Insight

**Same height, different epoch means REORGANIZATION is needed!**

This happens when:
1. Node was offline (missed epochs 103-104)
2. Received blocks from different forks
3. Better fork (higher cumDiff) has blocks in different epochs

The correct approach:
1. ALWAYS do epoch competition first (smallest hash wins epoch)
2. Then check if reorganization is needed (cumDiff comparison)
3. During reorganization, demote old blocks and promote new blocks
4. Heights are re-assigned to maintain continuity

## 6. Recommended Fixes

### 6.1 Fix #1: Remove Height-based Competition from tryToConnect()

**Current code (WRONG)**:
```java
if (existingBlock != null) {
    if (cumulativeDifficulty.compareTo(existingDifficulty) > 0) {
        demoteBlocksFromHeight(naturalHeight);  // ← This is WRONG!
        height = naturalHeight;
    }
}
```

**Why WRONG**:
- This compares blocks from DIFFERENT epochs directly at height level
- Ignores epoch competition rule
- Can cause blocks from different epochs to fight for same height

**Correct approach**:
- Remove this height-based competition
- Only do epoch competition in tryToConnect()
- Height reorganization should happen in checkNewMain()

### 6.2 Fix #2: Correct determineNaturalHeight()

**Current code (PROBLEM)**:
```java
private long determineNaturalHeight(Block block) {
    long maxParentHeight = 0;
    for (Link link : block.getLinks()) {
        Block parent = dagStore.getBlockByHash(link.getTargetHash(), false);
        if (parent != null && parent.getInfo() != null) {
            long parentHeight = parent.getInfo().getHeight();
            if (parentHeight > maxParentHeight) {
                maxParentHeight = parentHeight;
            }
        }
    }
    return maxParentHeight + 1;
}
```

**Problem**: Doesn't handle orphan parents correctly

**Correct approach**:
```java
private long determineNaturalHeight(Block block) {
    // Natural height should be based on EPOCH order, not parent height
    // Because parent might be demoted to orphan (height=0)

    // Instead, use cumulative difficulty to determine position in chain
    UInt256 blockDifficulty = calculateCumulativeDifficulty(block);

    // Find how many main blocks have lower cumulative difficulty
    long position = 1;  // Start at 1 (genesis)

    for (long h = 1; h <= chainStats.getMainBlockCount(); h++) {
        Block mainBlock = dagStore.getMainBlockByHeight(h, false);
        if (mainBlock != null && mainBlock.getInfo() != null) {
            if (mainBlock.getInfo().getDifficulty().compareTo(blockDifficulty) < 0) {
                position++;
            }
        }
    }

    return position;
}
```

### 6.3 Fix #3: Implement Proper Fork Resolution

Add a new method to handle fork resolution:

```java
private synchronized void resolveFork(Block newBlock, long proposedHeight) {
    Block existingBlock = dagStore.getMainBlockByHeight(proposedHeight, false);

    if (existingBlock == null) {
        // No conflict, just assign height
        promoteBlockToHeight(newBlock, proposedHeight);
        return;
    }

    // Fork detected at this height!
    // Compare cumulative difficulties
    UInt256 newDifficulty = newBlock.getInfo().getDifficulty();
    UInt256 existingDifficulty = existingBlock.getInfo().getDifficulty();

    if (newDifficulty.compareTo(existingDifficulty) > 0) {
        // New block wins - reorganize
        log.warn("Fork resolution: new block {} (epoch {}, cumDiff {}) replaces existing {} (epoch {}, cumDiff {})",
                newBlock.getHash().toHexString().substring(0, 16),
                newBlock.getEpoch(),
                newDifficulty.toDecimalString(),
                existingBlock.getHash().toHexString().substring(0, 16),
                existingBlock.getEpoch(),
                existingDifficulty.toDecimalString());

        performChainReorganization(newBlock, proposedHeight);
    } else {
        // Existing block wins - new block stays as orphan
        log.info("Fork resolution: existing block {} wins over new block {} at height {}",
                existingBlock.getHash().toHexString().substring(0, 16),
                newBlock.getHash().toHexString().substring(0, 16),
                proposedHeight);
    }
}
```

## 7. Testing Strategy

### Test Case 1: Same Height, Different Epoch, Higher Difficulty
```
Initial chain:
  Height 1: epoch 100
  Height 2: epoch 101
  Height 3: epoch 102, cumDiff=1000

New block arrives:
  Block X: epoch 105, cumDiff=1500

Expected:
  1. Block X wins epoch 105 competition
  2. Block X's cumDiff (1500) > height 3's cumDiff (1000)
  3. Reorganization triggered
  4. Result: Height 3 = epoch 105, cumDiff=1500
```

### Test Case 2: Same Height, Different Epoch, Lower Difficulty
```
Initial chain:
  Height 1: epoch 100
  Height 2: epoch 101
  Height 3: epoch 102, cumDiff=2000

New block arrives:
  Block X: epoch 105, cumDiff=1500

Expected:
  1. Block X wins epoch 105 competition
  2. Block X's cumDiff (1500) < height 3's cumDiff (2000)
  3. Block X becomes orphan (or gets assigned height 2.5 conceptually)
  4. Result: Block X is orphan, existing chain unchanged
```

### Test Case 3: Node Offline, Epoch Gap
```
Node offline from epoch 102 to 110.
Sync resumes at epoch 111.

Expected:
  - Main chain epochs: 100, 101, 111, 112, ...
  - Heights remain continuous: 1, 2, 3, 4, ...
  - Epochs are discontinuous (expected behavior)
```

## 8. Conclusion

**Root Cause**: Mixing two incompatible consensus mechanisms:
1. Epoch competition (hash-based) ← CORRECT, should be primary
2. Height competition (cumDiff-based) ← Should only apply during reorganization

**Solution**:
1. In `tryToConnect()`: ONLY do epoch competition
2. In `checkNewMain()`: Assign heights based on cumulative difficulty
3. In `resolveFork()`: Handle reorganization when better fork found

**Critical Insight**:
- **Height** = Position in main chain (continuous: 1, 2, 3, ...)
- **Epoch** = Time bucket (may be discontinuous: 100, 101, 105, ...)
- **Epoch competition** determines which block represents each epoch
- **Cumulative difficulty** determines height ordering (during reorganization)

These are TWO SEPARATE concerns that current code conflates!

---
**Report Date**: November 20, 2025, 21:15
**Reported By**: Claude Code Analysis Session
**Status**: ANALYSIS COMPLETE - Refactoring recommended
