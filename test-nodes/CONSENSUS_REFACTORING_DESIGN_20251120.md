# XDAG Consensus Refactoring Design - November 20, 2025

## Executive Summary

**CRITICAL ARCHITECTURAL CLARIFICATION**: Height is for **querying/indexing ONLY**, NOT for consensus. Consensus happens at the **EPOCH level**.

> "我最初的想法是高度方便查询，但不用来做共识，所以每个节点会自己维护一个连续的高度，每个高度对应的都是主块"
>
> Translation: "My original idea is that height is for convenient querying, but not used for consensus. So each node maintains its own continuous height, with each height corresponding to a main block."

This document designs the refactoring to properly separate:
- **Consensus layer** (epoch-based, hash competition)
- **Indexing layer** (height-based, sequential numbering)

## 1. Correct XDAG Architecture

### 1.1 Core Principles

```
Principle 1: Epoch Competition (CONSENSUS LAYER)
  - Every 64 seconds = 1 epoch
  - Multiple blocks can exist in one epoch (candidates)
  - Smallest hash wins the epoch → becomes epoch winner
  - This is the PRIMARY consensus mechanism

Principle 2: Height Assignment (INDEXING LAYER)
  - Each node maintains its own continuous height sequence: 1, 2, 3, 4, ...
  - Heights are assigned to epoch winners sequentially
  - Height is ONLY for convenient querying (API, RPC, CLI)
  - Height is NOT used for consensus decisions
  - Different nodes may have different blocks at the same height - this is OK!

Principle 3: Fork Resolution (SYNCHRONIZATION LAYER)
  - When syncing, nodes compare cumulative difficulty
  - Higher cumulative difficulty wins
  - If equal difficulty, smaller hash at fork point wins
  - After fork resolution, nodes re-assign heights to the new main chain
```

### 1.2 Example Scenario: Different Blocks at Same Height

**This is NORMAL and EXPECTED behavior:**

```
Node A (before sync):
  Height 1: Block A1 (epoch 100, hash 0x1234...)
  Height 2: Block A2 (epoch 101, hash 0x5678...)
  Height 3: Block A3 (epoch 102, hash 0x9abc...)

Node B (before sync):
  Height 1: Block B1 (epoch 100, hash 0x1111...)
  Height 2: Block B2 (epoch 105, hash 0x2222...)
  Height 3: Block B3 (epoch 106, hash 0x3333...)

After sync (assuming Node A has higher cumulative difficulty):
  Both nodes adopt Node A's chain:

Node A (unchanged):
  Height 1: Block A1 (epoch 100)
  Height 2: Block A2 (epoch 101)
  Height 3: Block A3 (epoch 102)

Node B (reorganized):
  Height 1: Block A1 (epoch 100) ← Re-assigned from Node A's chain
  Height 2: Block A2 (epoch 101) ← Re-assigned from Node A's chain
  Height 3: Block A3 (epoch 102) ← Re-assigned from Node A's chain

  Old blocks (B1, B2, B3) become orphans (height=0)
```

**Key Insight**: Heights are node-local indices until consensus is reached. After fork resolution, losing nodes re-index their chain to match the winning chain's epochs (not heights!).

## 2. Current Problems in Code

### 2.1 Problem: Height-Based Competition in `tryToConnect()`

**Location**: `DagChainImpl.java:302-341`

**Current Code (WRONG)**:
```java
// Check if we need to reorganize at this height
Block existingBlock = null;
if (naturalHeight > 0 && naturalHeight <= chainStats.getMainBlockCount()) {
    existingBlock = dagStore.getMainBlockByHeight(naturalHeight, false);
}

if (existingBlock != null) {
    // There's already a block at this natural height - compare difficulties
    UInt256 existingDifficulty = existingBlock.getInfo().getDifficulty();

    if (cumulativeDifficulty.compareTo(existingDifficulty) > 0) {
        // New block has higher difficulty - reorganize!
        demoteBlocksFromHeight(naturalHeight);  // ← WRONG: Height-based competition
        height = naturalHeight;
        isBestChain = true;
    }
}
```

**Why Wrong**:
- Compares blocks at the SAME HEIGHT but possibly DIFFERENT EPOCHS
- Height should NOT be used for consensus decisions
- This causes blocks from different epochs to fight for the same height during import
- Violates the principle: "Height is for querying only"

**Correct Approach**:
- Remove this entire section (lines 302-341)
- Height assignment should happen AFTER consensus is reached
- During block import, ONLY check epoch competition

### 2.2 Problem: `determineNaturalHeight()` Logic

**Location**: `DagChainImpl.java:2431-2462`

**Current Code**:
```java
private long determineNaturalHeight(Block block) {
    if (isGenesisBlock(block)) {
        return 1;
    }

    long maxParentHeight = 0;

    // Find the highest parent block height
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

**Why Wrong**:
- Tries to calculate height during block import
- Height calculation depends on parent heights
- If parent is orphan (height=0), calculation is wrong
- This is trying to use height for consensus decisions

**Correct Approach**:
- Don't calculate height during import
- Mark block as "pending height assignment" (height=0 initially)
- Assign heights later based on epoch order and cumulative difficulty

## 3. Refactoring Design

### 3.1 New Block Import Flow

```
Step 1: Basic Validation
  ✓ validateBasicRules(block)
  ✓ validateMinimumPoW(block)
  ✓ validateEpochLimit(block)
  ✓ validateLinks(block)
  ✓ validateDAGRules(block)

Step 2: Calculate Cumulative Difficulty
  ✓ cumulativeDifficulty = calculateCumulativeDifficulty(block)

Step 3: Epoch Competition (PRIMARY CONSENSUS)
  ✓ blockEpoch = block.getEpoch()
  ✓ currentWinner = getWinnerBlockInEpoch(blockEpoch)

  If no winner exists:
    → Block becomes epoch winner (height TBD)

  If winner exists:
    If block.hash < currentWinner.hash:
      → Block wins, demote old winner to orphan
      → Block becomes new epoch winner (height TBD)
    Else:
      → Block loses, becomes orphan (height=0)

Step 4: Height Assignment (INDEXING, NOT CONSENSUS)
  → Don't assign final height yet
  → Mark block as "pending height assignment"
  → Final heights assigned by checkNewMain()

Step 5: Process Block (if epoch winner)
  ✓ Execute transactions (DagBlockProcessor)
  ✓ Update chain stats
  ✓ Notify listeners

Step 6: Trigger Height Re-assignment
  → Call checkNewMain() to re-calculate heights for all epoch winners
```

### 3.2 Refactored `tryToConnect()` Method

**New Implementation**:

```java
@Override
public synchronized DagImportResult tryToConnect(Block block) {
    try {
        // Step 1: Basic validation
        DagImportResult basicValidation = validateBasicRules(block);
        if (basicValidation != null) return basicValidation;

        DagImportResult powValidation = validateMinimumPoW(block);
        if (powValidation != null) return powValidation;

        DagImportResult epochLimitValidation = validateEpochLimit(block);
        if (epochLimitValidation != null) return epochLimitValidation;

        DagImportResult linkValidation = validateLinks(block);
        if (linkValidation != null) return linkValidation;

        DAGValidationResult dagValidation = validateDAGRules(block);
        if (!dagValidation.isValid()) {
            return DagImportResult.invalidDAG(dagValidation);
        }

        // Step 2: Calculate cumulative difficulty
        UInt256 cumulativeDifficulty = calculateCumulativeDifficulty(block);

        // Step 3: Epoch Competition (PRIMARY CONSENSUS - ONLY THIS MATTERS)
        long blockEpoch = block.getEpoch();
        Block currentWinner = getWinnerBlockInEpoch(blockEpoch);

        boolean isEpochWinner = (currentWinner == null) ||
                                (block.getHash().compareTo(currentWinner.getHash()) < 0);

        long height;
        boolean isBestChain;

        if (isEpochWinner) {
            if (currentWinner != null) {
                // Replace current winner - keep same height (replacement, not addition)
                long replacementHeight = currentWinner.getInfo().getHeight();
                demoteBlockToOrphan(currentWinner);
                height = replacementHeight;
            } else {
                // First block in this epoch - height will be assigned by checkNewMain()
                height = 0;  // Mark as pending height assignment
            }
            isBestChain = true;
        } else {
            // Lost epoch competition - orphan
            height = 0;
            isBestChain = false;
        }

        // Step 4: Save block with pending height (or replacement height)
        BlockInfo blockInfo = BlockInfo.builder()
                .hash(block.getHash())
                .epoch(block.getEpoch())
                .height(height)
                .difficulty(cumulativeDifficulty)
                .build();

        dagStore.saveBlockInfo(blockInfo);
        Block blockWithInfo = block.toBuilder().info(blockInfo).build();
        dagStore.saveBlock(blockWithInfo);

        // Index transactions (for all blocks)
        indexTransactions(block);

        // Step 5: Process block (ONLY for epoch winners)
        if (isBestChain) {
            // Execute transactions
            if (dagKernel != null) {
                DagBlockProcessor blockProcessor = dagKernel.getDagBlockProcessor();
                if (blockProcessor != null) {
                    DagBlockProcessor.ProcessingResult processResult =
                            blockProcessor.processBlock(blockWithInfo);

                    if (!processResult.isSuccess()) {
                        log.error("Block {} transaction processing failed: {}",
                                block.getHash().toHexString(), processResult.getError());
                    }
                }
            }

            // Update chain statistics
            updateChainStatsForNewMainBlock(blockInfo);

            // Difficulty adjustment and orphan cleanup
            checkAndAdjustDifficulty(blockInfo.getHeight(), block.getEpoch());
            cleanupOldOrphans(block.getEpoch());

            // Notify listeners
            notifyListeners(blockWithInfo);
            notifyDagchainListeners(blockWithInfo);
        } else {
            // Add orphan to orphan store
            try {
                orphanBlockStore.addOrphan(block.getHash(), block.getEpoch());
            } catch (Exception e) {
                log.error("Failed to add orphan block to store: {}", e.getMessage());
            }
        }

        // Step 6: Re-assign heights based on epoch order and cumulative difficulty
        if (isBestChain || height == 0) {
            checkNewMain();  // Re-calculate heights for all epoch winners
        }

        // Retry orphan blocks
        retryOrphanBlocks();

        // Return result
        if (isBestChain) {
            return DagImportResult.mainBlock(blockEpoch, height, cumulativeDifficulty, isEpochWinner);
        } else {
            return DagImportResult.orphan(blockEpoch, cumulativeDifficulty, isEpochWinner);
        }

    } catch (Exception e) {
        log.error("Error importing block {}: {}", block.getHash().toHexString(), e.getMessage(), e);
        return DagImportResult.error(e, "Exception during import: " + e.getMessage());
    }
}
```

**Key Changes**:
1. **REMOVED lines 302-341**: Height-based competition logic
2. **REMOVED**: `determineNaturalHeight()` call
3. **KEPT**: Epoch-based competition logic (lines 356-406)
4. **CHANGED**: Heights are assigned by `checkNewMain()`, not during import
5. **SIMPLIFIED**: Block either wins epoch (becomes main) or loses (becomes orphan)

### 3.3 New `checkNewMain()` Implementation

**Purpose**: Assign sequential heights to epoch winners based on epoch order and cumulative difficulty

```java
@Override
public synchronized void checkNewMain() {
    log.debug("Running checkNewMain() - assigning heights to epoch winners");

    // Step 1: Collect all epoch winners
    long currentEpoch = getCurrentEpoch();
    long scanStartEpoch = Math.max(1, currentEpoch - ORPHAN_RETENTION_WINDOW);

    Map<Long, Block> epochWinners = new TreeMap<>();  // Sorted by epoch

    for (long epoch = scanStartEpoch; epoch <= currentEpoch; epoch++) {
        Block winner = getWinnerBlockInEpoch(epoch);
        if (winner != null) {
            epochWinners.put(epoch, winner);
        }
    }

    if (epochWinners.isEmpty()) {
        log.warn("No epoch winners found in range {} to {}", scanStartEpoch, currentEpoch);
        return;
    }

    // Step 2: Sort epoch winners by epoch number (ascending - earliest first)
    // Heights follow epoch order, NOT cumulative difficulty order
    List<Block> sortedWinners = new ArrayList<>(epochWinners.values());
    sortedWinners.sort(Comparator.comparingLong(Block::getEpoch));

    // Step 3: Assign continuous heights: 1, 2, 3, 4, ...
    long height = 1;
    for (Block winner : sortedWinners) {
        // Check if height needs update
        if (winner.getInfo().getHeight() != height) {
            log.debug("Assigning height {} to epoch {} winner {}",
                    height, winner.getEpoch(),
                    winner.getHash().toHexString().substring(0, 16));

            promoteBlockToHeight(winner, height);
        }
        height++;
    }

    // Step 4: Update chain stats
    Block topBlock = sortedWinners.get(sortedWinners.size() - 1);
    chainStats = chainStats
            .withMainBlockCount(height - 1)
            .withMaxDifficulty(topBlock.getInfo().getDifficulty())
            .withTopBlock(topBlock.getHash())
            .withTopDifficulty(topBlock.getInfo().getDifficulty());

    dagStore.saveChainStats(chainStats);

    log.info("Height assignment completed: {} epoch winners assigned heights 1 to {}",
            sortedWinners.size(), height - 1);
}
```

**Key Points**:
- Heights are assigned in **EPOCH ORDER**, not cumulative difficulty order
- This ensures height continuity: 1, 2, 3, 4, ...
- Epoch order may have gaps (100, 101, 105, 107) - this is normal
- Heights remain continuous even when epochs are discontinuous

### 3.4 Fork Resolution During Sync

**When does fork resolution happen?**

During synchronization, when a node receives blocks from a peer with higher cumulative difficulty:

```java
// In HybridSyncManager or similar sync component

public void handleFork(List<Block> remoteBlocks) {
    // Step 1: Find fork point (common ancestor)
    Block forkPoint = findCommonAncestor(remoteBlocks);

    if (forkPoint == null) {
        log.error("Cannot find common ancestor - chains are too divergent");
        return;
    }

    // Step 2: Compare cumulative difficulties at fork point
    UInt256 localDifficulty = getLocalChainDifficulty();
    UInt256 remoteDifficulty = calculateRemoteChainDifficulty(remoteBlocks);

    if (remoteDifficulty.compareTo(localDifficulty) > 0) {
        // Remote chain wins - perform reorganization
        log.warn("Remote chain has higher difficulty ({} > {}), reorganizing",
                remoteDifficulty.toDecimalString(),
                localDifficulty.toDecimalString());

        performChainReorganization(remoteBlocks, forkPoint);
    } else {
        // Local chain wins - reject remote blocks
        log.info("Local chain has higher or equal difficulty, keeping local chain");
    }
}

private void performChainReorganization(List<Block> remoteBlocks, Block forkPoint) {
    // Step 1: Demote local blocks after fork point
    long forkHeight = forkPoint.getInfo().getHeight();
    demoteBlocksFromHeight(forkHeight + 1);

    // Step 2: Import remote blocks (they will win epoch competitions)
    for (Block remoteBlock : remoteBlocks) {
        DagImportResult result = dagChain.tryToConnect(remoteBlock);
        if (!result.isMainBlock() && !result.isOrphan()) {
            log.error("Failed to import remote block during reorganization: {}",
                    result.getStatus());
        }
    }

    // Step 3: Re-assign heights (done automatically by checkNewMain())
    dagChain.checkNewMain();

    log.info("Chain reorganization completed at fork point height {}", forkHeight);
}
```

## 4. Summary of Changes

### 4.1 Files to Modify

**`/Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/core/DagChainImpl.java`**

1. **`tryToConnect()` method (lines 248-504)**:
   - DELETE: Lines 297-341 (height-based competition)
   - DELETE: Call to `determineNaturalHeight()`
   - KEEP: Lines 356-406 (epoch-based competition)
   - CHANGE: Set `height = 0` for new epoch winners (pending assignment)
   - CHANGE: Call `checkNewMain()` at end to assign heights

2. **`checkNewMain()` method (lines 1818-1837)**:
   - REPLACE: Entire implementation with new height assignment logic
   - Sort epoch winners by epoch number
   - Assign continuous heights 1, 2, 3, ...

3. **`demoteBlocksFromHeight()` method (lines 2464-2586)**:
   - KEEP: Existing gap-filling logic (already correct)
   - This handles reorganization cleanup

4. **`determineNaturalHeight()` method (lines 2431-2462)**:
   - DELETE: This method is no longer needed
   - Height is assigned by `checkNewMain()`, not calculated during import

### 4.2 Behavior Changes

**Before Refactoring**:
- Block import calculates "natural height" based on parent heights
- Height-based competition happens during import
- Blocks from different epochs compete for same height
- Height gaps can occur after reorganization

**After Refactoring**:
- Block import ONLY checks epoch competition (smallest hash wins)
- Heights are assigned separately by `checkNewMain()`
- Heights follow epoch order: epoch 100 → height 1, epoch 101 → height 2, ...
- Each node maintains continuous heights even with discontinuous epochs
- Fork resolution compares cumulative difficulty, then re-assigns heights

## 5. Testing Strategy

### 5.1 Test Case 1: Epoch Discontinuity

**Setup**:
```
Node has epoch winners at: 100, 101, 105, 107, 110
(Epochs 102-104, 106, 108-109 are missing - node was offline)
```

**Expected Result**:
```
Heights assigned:
  Height 1: epoch 100
  Height 2: epoch 101
  Height 3: epoch 105  ← Gap in epochs, but heights continuous
  Height 4: epoch 107
  Height 5: epoch 110
```

### 5.2 Test Case 2: Epoch Competition During Import

**Setup**:
```
Existing: epoch 100 has winner with hash 0x5678...
New block: epoch 100 with hash 0x1234...
```

**Action**: Import new block via `tryToConnect()`

**Expected Result**:
- New block has smaller hash (0x1234 < 0x5678)
- New block wins epoch competition
- Old winner demoted to orphan (height=0)
- New winner takes old winner's height (replacement)
- Logs: "Block 0x1234... wins epoch 100 competition (smaller hash than 0x5678...)"

### 5.3 Test Case 3: Fork Resolution and Height Re-assignment

**Setup**:
```
Node A:
  Height 1: epoch 100, cumDiff 1000
  Height 2: epoch 101, cumDiff 2000
  Height 3: epoch 102, cumDiff 3000

Node B (before sync):
  Height 1: epoch 100, cumDiff 1000  (same as Node A)
  Height 2: epoch 105, cumDiff 2500  (different epoch!)
  Height 3: epoch 106, cumDiff 3500  (different epoch!)
```

**Action**: Node B syncs with Node A

**Fork Detection**:
- Fork point: epoch 100 (common block)
- Node A chain difficulty: 3000
- Node B chain difficulty: 3500
- Node B has higher difficulty → Node B's chain wins

**Expected Result**:
- Node A reorganizes to Node B's chain
- Node A demotes its blocks at heights 2-3
- Node A imports Node B's blocks (epochs 105, 106)
- Node A's new state:
  ```
  Height 1: epoch 100, cumDiff 1000  (unchanged)
  Height 2: epoch 105, cumDiff 2500  (from Node B)
  Height 3: epoch 106, cumDiff 3500  (from Node B)
  ```
- Heights remain continuous (1, 2, 3) even though epochs jumped (100 → 105 → 106)

### 5.4 Test Case 4: Same Height, Different Epochs (Normal Behavior)

**Setup**:
```
Before sync:
  Node A: Height 5 = epoch 102
  Node B: Height 5 = epoch 105
```

**Expected Behavior**:
- This is NORMAL and OK before sync
- During sync, fork resolution determines winner
- Loser node re-assigns heights to match winner's epoch sequence
- After sync, both nodes have same epochs at corresponding heights

## 6. Migration Plan

### 6.1 Existing Chain Compatibility

**Problem**: Existing chains have heights assigned using the old logic (height-based competition)

**Solution**: Run migration on node startup

```java
public void migrateToEpochBasedHeights() {
    log.info("Migrating to epoch-based height assignment");

    // Step 1: Collect all current main blocks
    List<Block> mainBlocks = new ArrayList<>();
    for (long h = 1; h <= chainStats.getMainBlockCount(); h++) {
        Block block = dagStore.getMainBlockByHeight(h, false);
        if (block != null) {
            mainBlocks.add(block);
        }
    }

    // Step 2: Sort by epoch (ascending)
    mainBlocks.sort(Comparator.comparingLong(Block::getEpoch));

    // Step 3: Re-assign heights in epoch order
    long newHeight = 1;
    for (Block block : mainBlocks) {
        if (block.getInfo().getHeight() != newHeight) {
            log.info("Migrating block {} from height {} to height {}",
                    block.getHash().toHexString().substring(0, 16),
                    block.getInfo().getHeight(),
                    newHeight);

            // Delete old height mapping
            dagStore.deleteHeightMapping(block.getInfo().getHeight());

            // Assign new height
            promoteBlockToHeight(block, newHeight);
        }
        newHeight++;
    }

    log.info("Migration complete: {} blocks re-indexed", mainBlocks.size());
}
```

### 6.2 Rollout Plan

1. **Phase 1**: Deploy refactored code with migration
2. **Phase 2**: All nodes run migration on first startup
3. **Phase 3**: Heights are now epoch-ordered, consensus works correctly
4. **Phase 4**: Verify chain convergence across all nodes

## 7. Benefits of Refactoring

### 7.1 Correctness

✅ **Separates concerns**: Consensus (epoch) vs Indexing (height)

✅ **Matches whitepaper**: Epoch-based consensus as designed

✅ **Handles discontinuous epochs**: Normal behavior when nodes are offline

✅ **Clean fork resolution**: Compare cumulative difficulty, re-assign heights

### 7.2 Simplicity

✅ **Simpler `tryToConnect()`**: Only checks epoch competition, no height calculations

✅ **Clear height assignment**: Single method (`checkNewMain()`) assigns all heights

✅ **No height gaps**: Heights always continuous by design

✅ **Easier debugging**: Height is just an index, not part of consensus

### 7.3 Performance

✅ **Faster block import**: No complex height calculations during import

✅ **Efficient sync**: Compare epochs and cumulative difficulty, not heights

✅ **Reduced reorganization complexity**: Demote and re-assign, no gap filling during import

## 8. Conclusion

This refactoring aligns the code with the correct XDAG architecture:

> **Height is for querying only. Consensus happens at the epoch level.**

By separating these concerns, we achieve:
- Correct epoch-based consensus
- Continuous height indexing
- Clean fork resolution
- Support for discontinuous epochs
- Simpler, more maintainable code

---
**Document Date**: November 20, 2025, 22:00
**Author**: Claude Code Analysis Session
**Status**: DESIGN COMPLETE - Ready for implementation
