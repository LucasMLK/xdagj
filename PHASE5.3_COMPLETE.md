# Phase 5.3 Completion Report: Main Chain Method Deprecation

**Date**: 2025-10-31
**Branch**: refactor/core-v5.1
**Commit**: c828618a
**Status**: ✅ **COMPLETE**

---

## 📋 Executive Summary

Phase 5.3 successfully deprecated all 5 legacy Block-based main chain management methods in BlockchainImpl. These methods handle fork resolution and chain reorganization, and are critical for blockchain operation.

**Completed Tasks**: 7 of 7
- ✅ Deprecate findAncestor() method
- ✅ Deprecate updateNewChain() method
- ✅ Deprecate unWindMain() method
- ✅ Deprecate setMain() method
- ✅ Deprecate unSetMain() method
- ✅ Test compilation with deprecation warnings
- ✅ Commit Phase 5.3 changes

**Key Achievement**: All main chain management methods marked for removal as part of v5.1 complete refactor strategy.

---

## 🎯 Methods Deprecated

### 1. findAncestor(Block block, boolean isFork)

**Location**: `BlockchainImpl.java:908`

**Purpose**: Find common ancestor block during fork resolution

**Why Deprecated**:
- Operates on legacy Block objects
- After fresh start with BlockV5 storage, will automatically process BlockV5 from storage
- Complete refactor strategy means method will become obsolete

**Impact**: Critical for blockchain fork resolution

**Called By**:
- `tryToConnect(Block)` at line ~633 during fork handling

**Javadoc Added**:
```java
/**
 * Find common ancestor block during fork resolution (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
 *             refactor and system restart with BlockV5-only storage, all main chain management
 *             should work with BlockV5 structures.
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
 *                   making this Block-based method obsolete</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage.
 *             No code changes needed if Block/BlockV5 interface is compatible. May require
 *             BlockV5-specific version if chain reorganization logic differs significantly.
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain fork resolution. It finds the common
 *             ancestor between the current main chain and a new candidate chain. Used by
 *             {@link #tryToConnect(Block)} during fork handling.
 *
 * @param block The new block triggering fork resolution
 * @param isFork Whether this is a fork scenario (affects BI_MAIN_CHAIN flag updates)
 * @return The common ancestor block, or null if not found
 * @see #tryToConnect(Block)
 * @see #unWindMain(Block)
 * @see #updateNewChain(Block, boolean)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public Block findAncestor(Block block, boolean isFork) {
```

---

### 2. updateNewChain(Block block, boolean isFork)

**Location**: `BlockchainImpl.java:974`

**Purpose**: Update new chain after fork (set BI_MAIN_CHAIN flags)

**Why Deprecated**:
- Operates on legacy Block objects
- Updates BI_MAIN_CHAIN flags for new candidate chain after finding ancestor
- After fresh start, will process BlockV5 automatically

**Impact**: Critical for blockchain fork resolution (chain reorganization)

**Called By**:
- `tryToConnect(Block)` at line ~639 after finding ancestor

**Javadoc Added**:
```java
/**
 * Update new chain after fork (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
 *             refactor and system restart with BlockV5-only storage, all main chain management
 *             should work with BlockV5 structures.
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
 *                   making this Block-based method obsolete</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage.
 *             No code changes needed if Block/BlockV5 interface is compatible. May require
 *             BlockV5-specific version if chain reorganization logic differs significantly.
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain fork resolution. It updates BI_MAIN_CHAIN
 *             flags for the new candidate chain after finding the common ancestor. Used by
 *             {@link #tryToConnect(Block)} during fork handling.
 *
 * @param block The new block that triggered fork resolution
 * @param isFork Whether this is a fork scenario (if false, method returns immediately)
 * @see #tryToConnect(Block)
 * @see #findAncestor(Block, boolean)
 * @see #unWindMain(Block)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public void updateNewChain(Block block, boolean isFork) {
```

---

### 3. unWindMain(Block block)

**Location**: `BlockchainImpl.java:1119`

**Purpose**: Rollback main chain to specified block during fork

**Why Deprecated**:
- Operates on legacy Block objects
- Unwinds main chain by clearing BI_MAIN_CHAIN flags and calling unSetMain()
- After fresh start, will process BlockV5 automatically

**Impact**: Critical for blockchain fork resolution (unwinds main chain)

**Called By**:
- `tryToConnect(Block)` at line ~636 before updating new chain

**Javadoc Added**:
```java
/**
 * Rollback main chain to specified block (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
 *             refactor and system restart with BlockV5-only storage, all main chain management
 *             should work with BlockV5 structures.
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
 *                   making this Block-based method obsolete</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage.
 *             No code changes needed if Block/BlockV5 interface is compatible. May require
 *             BlockV5-specific version if chain reorganization logic differs significantly.
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain fork resolution. It unwinds the main chain
 *             back to the common ancestor by clearing BI_MAIN_CHAIN flags and calling
 *             {@link #unSetMain(Block)} for main blocks. Used by {@link #tryToConnect(Block)}
 *             during fork handling.
 *
 * @param block The common ancestor block to unwind to (or null to unwind all)
 * @see #tryToConnect(Block)
 * @see #findAncestor(Block, boolean)
 * @see #updateNewChain(Block, boolean)
 * @see #unSetMain(Block)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public void unWindMain(Block block) {
```

---

### 4. setMain(Block block)

**Location**: `BlockchainImpl.java:1681`

**Purpose**: Set block as main block (accept reward, apply transactions)

**Why Deprecated**:
- Operates on legacy Block objects
- Critical for main chain management (extends or forks chain)
- After fresh start, will process BlockV5 automatically

**Impact**: Critical for blockchain main chain management

**Called By**:
- `checkNewMain()` at line ~1078 during main chain consensus

**Implementation Details**:
```java
public void setMain(Block block) {
    synchronized (this) {
        // 1. Remove from memOrphanPool if EXTRA block
        // 2. Set BI_MAIN flag
        // 3. Accept block reward (based on height)
        // 4. Increment nmain counter
        // 5. Recursively execute via applyBlock(true, block)
        // 6. Collect transaction fees
        // 7. Set block's ref to point to itself
    }
}
```

**Javadoc Added**:
```java
/**
 * Set the main chain with block as the main block - either fork or extend (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
 *             refactor and system restart with BlockV5-only storage, all main chain management
 *             should work with BlockV5 structures.
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
 *                   making this Block-based method obsolete</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage.
 *             No code changes needed if Block/BlockV5 interface is compatible. May require
 *             BlockV5-specific version if chain management logic differs significantly.
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain main chain management. It sets a block as
 *             the main block (either extending or forking the chain), accepts block reward,
 *             applies transactions recursively, and collects fees. Used by {@link #checkNewMain()}
 *             during main chain consensus.
 *
 * @param block The block to set as main block
 * @see #checkNewMain()
 * @see #unSetMain(Block)
 * @see #applyBlock(boolean, Block)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public void setMain(Block block) {
```

---

### 5. unSetMain(Block block)

**Location**: `BlockchainImpl.java:1750`

**Purpose**: Cancel main block status (remove rewards, unapply transactions)

**Why Deprecated**:
- Operates on legacy Block objects
- Critical for fork resolution (chain reorganization)
- After fresh start, will process BlockV5 automatically

**Impact**: Critical for blockchain fork resolution

**Called By**:
- `unWindMain(Block)` at line ~1136 during fork handling

**Implementation Details**:
```java
public void unSetMain(Block block) {
    synchronized (this) {
        // 1. Clear BI_MAIN flag
        // 2. Decrement nmain counter
        // 3. Remove reward and fees via acceptAmount(block, -amount)
        // 4. Recursively unapply via unApplyBlock(block)
        // 5. Reset block height to 0
    }
}
```

**Javadoc Added**:
```java
/**
 * Cancel Block main block status (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects. After complete
 *             refactor and system restart with BlockV5-only storage, all main chain management
 *             should work with BlockV5 structures.
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5,
 *                   making this Block-based method obsolete</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring is required</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage.
 *             No code changes needed if Block/BlockV5 interface is compatible. May require
 *             BlockV5-specific version if chain management logic differs significantly.
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain fork resolution. It cancels a block's main
 *             block status during chain reorganization, removing rewards and unapplying all
 *             transactions. Used by {@link #unWindMain(Block)} during fork handling.
 *
 * @param block The block to unset as main block
 * @see #unWindMain(Block)
 * @see #setMain(Block)
 * @see #unApplyBlock(Block)
 */
@Deprecated(since = "0.8.1", forRemoval = true)
// TODO: Change to new way to cancel main block reward
public void unSetMain(Block block) {
```

---

## 🔄 Fork Resolution Flow

**Phase 5.3 deprecated methods work together to handle blockchain forks**:

```
tryToConnect(Block) detects fork
      ↓
   [FORK DETECTED: new block difficulty > top difficulty]
      ↓
findAncestor(block, isFork)  ← Phase 5.3 ✅
   - Find common ancestor
   - Set BI_MAIN_CHAIN flags on new chain
      ↓
unWindMain(blockRef)  ← Phase 5.3 ✅
   - Clear BI_MAIN_CHAIN flags on old chain
   - Call unSetMain() for each main block  ← Phase 5.3 ✅
      ↓
updateNewChain(block, isFork)  ← Phase 5.3 ✅
   - Set BI_MAIN_CHAIN flags on new chain
      ↓
checkNewMain()
   - Find block with BI_MAIN_CHAIN flag
   - Call setMain() to make it main block  ← Phase 5.3 ✅
```

---

## 🧪 Testing Results

### Compilation Status

**Command**: `mvn clean compile -DskipTests`

**Result**: ✅ **BUILD SUCCESS**

**Deprecation Warnings**:
- ✅ Phase 5.1 warnings visible (NewBlockMessage, SyncBlockMessage)
- ✅ Phase 5.2 warnings visible (createNewBlock)
- ⚠️ Phase 5.3 warnings not visible (methods called within same class)

**Explanation**: Java compiler doesn't emit deprecation warnings for uses of deprecated methods within the same class that declares them. This is expected behavior.

**Verification**:
```bash
# Check for Phase 5.1 deprecation warnings (network messages)
mvn clean compile -DskipTests 2>&1 | grep -E "NewBlockMessage|SyncBlockMessage"
# Output: Multiple warnings found ✅

# Check for Phase 5.2 deprecation warnings (block creation)
mvn clean compile -DskipTests 2>&1 | grep -E "createNewBlock"
# Output: Warnings found in XdagPow, PoolAwardManagerImpl ✅

# Check for Phase 5.3 deprecation warnings (main chain methods)
mvn clean compile -DskipTests 2>&1 | grep -E "findAncestor|unWindMain|setMain"
# Output: No warnings (expected - same class) ⚠️
```

**Sample Deprecation Warnings**:
```
[WARNING] /Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/p2p/XdagP2pEventHandler.java:[138,38]
[removal] io.xdag.net.message.consensus 中的 NewBlockMessage 已过时, 且标记为待删除

[WARNING] /Users/reymondtu/dev/github/xdagj/src/main/java/io/xdag/consensus/XdagPow.java:[181,32]
[removal] Blockchain 中的 createNewBlock(Map<Bytes32,ECKeyPair>,List<Bytes32>,boolean,String,XAmount,UInt64) 已过时, 且标记为待删除
```

---

## 📊 Code Changes Summary

**File Modified**: `src/main/java/io/xdag/core/BlockchainImpl.java`

**Lines Changed**: +155, -5

**Changes Breakdown**:
- Added 5 × @Deprecated annotations (5 lines)
- Added 5 × comprehensive Javadoc (150 lines)
- Total: 155 lines added

**Deprecation Pattern**:
```java
/**
 * [Method description] (legacy v1.0 implementation).
 *
 * @deprecated As of v5.1 refactor, this method operates on legacy Block objects...
 *
 *             <p><b>Migration Path:</b>
 *             <ul>
 *               <li>Phase 5.3 (Current): Mark as @Deprecated</li>
 *               <li>Post-Restart: After fresh start, all blocks in storage are BlockV5...</li>
 *               <li>Future: May need BlockV5-specific version if significant refactoring...</li>
 *             </ul>
 *
 *             <p><b>Replacement Strategy:</b>
 *             After complete refactor, this method will process BlockV5 objects from storage...
 *
 *             <p><b>Impact:</b>
 *             This method is critical for blockchain [operation type]...
 *
 * @param [parameters]
 * @see [related methods]
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public [ReturnType] [methodName]([Parameters]) {
    // Existing implementation unchanged
}
```

---

## 🎯 Design Decisions

### Decision 1: Keep Block Methods (Don't Create BlockV5 Versions)

**Chosen**: Keep Block-based methods, mark @Deprecated

**Rationale**:
1. **Complete refactor strategy**: System will start fresh with BlockV5 storage
2. **Automatic BlockV5 processing**: After restart, methods will process BlockV5 from storage
3. **Simpler migration path**: No need to duplicate complex fork resolution logic
4. **Dead code after restart**: Methods will become obsolete once BlockV5-only

**Trade-off**:
- ❌ No BlockV5-specific versions during transition
- ✅ Simpler codebase (no duplication)
- ✅ Clearer migration path
- ✅ Less code to maintain

### Decision 2: Comprehensive Javadoc for All Deprecated Methods

**Rationale**:
- Explain why method is deprecated (operates on legacy Block objects)
- Document migration path (Phase 5.3 → Post-restart → Future)
- Describe replacement strategy (automatic BlockV5 processing)
- Highlight impact (critical for fork resolution / main chain management)

**Benefit**: Clear documentation for future maintainers

### Decision 3: Use @Deprecated(since = "0.8.1", forRemoval = true)

**Rationale**:
- `since = "0.8.1"`: Matches current version
- `forRemoval = true`: Explicitly marks methods for removal (not just discouraged)
- Clear signal that methods will be removed after complete refactor

---

## 🔗 Related Phases

### Completed Phases

**Phase 5.1**: ✅ Network Message Deprecation
- Deprecated NewBlockMessage (0x18)
- Deprecated SyncBlockMessage (0x19)
- Both marked for removal after v5.1 migration

**Phase 5.2**: ✅ Block Creation Deprecation
- Deprecated createNewBlock()
- Deprecated createMainBlock()
- Deprecated createLinkBlock()
- Deprecated Blockchain.createNewBlock() interface method

**Phase 5.3**: ✅ Main Chain Method Deprecation (This Phase)
- Deprecated findAncestor()
- Deprecated updateNewChain()
- Deprecated unWindMain()
- Deprecated setMain()
- Deprecated unSetMain()

### Pending Phases

**Phase 5.4**: ⏳ Blockchain Interface Method Deprecation
- Deprecate Blockchain.tryToConnect(Block)
- Impact: Mark interface method for removal
- Estimated: 0.1 days

**Phase 5.5**: ⏳ Convert to BlockV5
- Create BlockV5 creation methods for mining
- Create BlockV5 creation methods for wallet
- Update RandomX to validate BlockV5 POW
- Update XdagSync to manage BlockV5 synchronization
- Estimated: 2 days

---

## 📈 Phase 5 Overall Progress

```
Phase 5.1 - Network Messages:      ████████████████████  100% ✅
Phase 5.2 - Block Creation:        ████████████████████  100% ✅
Phase 5.3 - Main Chain Methods:    ████████████████████  100% ✅
Phase 5.4 - Interface Methods:     ░░░░░░░░░░░░░░░░░░░░    0% ⏳
Phase 5.5 - Convert to BlockV5:    ░░░░░░░░░░░░░░░░░░░░    0% ⏳
-------------------------------------------------------
Overall Phase 5:                   ████████████░░░░░░░░   60%
```

### By Component

| Component | Phase 5.1 | Phase 5.2 | Phase 5.3 | Phase 5.4 | Phase 5.5 | Status |
|-----------|-----------|-----------|-----------|-----------|-----------|--------|
| **NewBlockMessage** | ✅ | - | - | - | - | Deprecated |
| **SyncBlockMessage** | ✅ | - | - | - | - | Deprecated |
| **createNewBlock()** | - | ✅ | - | - | - | Deprecated |
| **createMainBlock()** | - | ✅ | - | - | - | Deprecated |
| **createLinkBlock()** | - | ✅ | - | - | - | Deprecated |
| **Blockchain.createNewBlock()** | - | ✅ | - | - | - | Deprecated |
| **findAncestor()** | - | - | ✅ | - | - | Deprecated |
| **updateNewChain()** | - | - | ✅ | - | - | Deprecated |
| **unWindMain()** | - | - | ✅ | - | - | Deprecated |
| **setMain()** | - | - | ✅ | - | - | Deprecated |
| **unSetMain()** | - | - | ✅ | - | - | Deprecated |
| **Blockchain.tryToConnect(Block)** | - | - | - | ⏳ | - | Pending |
| **Mining BlockV5 creation** | - | - | - | - | ⏳ | Pending |
| **Wallet BlockV5 creation** | - | - | - | - | ⏳ | Pending |
| **RandomX BlockV5 support** | - | - | - | - | ⏳ | Pending |
| **XdagSync BlockV5 support** | - | - | - | - | ⏳ | Pending |

---

## 🚀 Next Steps

### Immediate Next: Phase 5.4 - Blockchain Interface Method Deprecation

**Task**: Deprecate `Blockchain.tryToConnect(Block)` interface method

**File**: `src/main/java/io/xdag/core/Blockchain.java`

**Estimated Time**: 0.1 days (10-15 minutes)

**Implementation**:
```java
/**
 * @deprecated Use tryToConnect(BlockV5) instead (Phase 5.4 - v5.1 migration)
 * This method creates legacy Block objects. After complete refactor,
 * all block connection should use BlockV5.
 */
@Deprecated(since = "0.8.1", forRemoval = true)
ImportResult tryToConnect(Block block);
```

### Phase 5.5 - Convert to BlockV5 (2 days)

**High-Priority Conversions**:
1. Mining block creation → createMainBlockV5()
2. Wallet transaction creation → createNewBlockV5()
3. RandomX POW verification → validateBlockV5()
4. XdagSync synchronization → manageBlockV5Sync()

---

## 💡 Key Takeaways

### Success Factors

1. **Consistent Deprecation Pattern**: All 5 methods follow same Javadoc structure
2. **Clear Migration Path**: Phase 5.3 → Post-restart → Future BlockV5 version
3. **Preserved Functionality**: No implementation changes, only documentation
4. **Successful Compilation**: BUILD SUCCESS with visible deprecation warnings

### Challenges Overcome

1. **Same-class deprecation warnings**: Understanding that Java doesn't emit warnings for same-class usage
2. **Complex fork resolution**: Documenting the intricate flow between 5 methods
3. **Complete refactor strategy**: Deciding to keep Block methods rather than create BlockV5 versions

### Lessons Learned

1. **Documentation is critical**: Comprehensive Javadoc helps future maintainers understand deprecation
2. **Complete refactor simplifies migration**: No need for dual Block/BlockV5 versions
3. **Deprecation warnings expected**: Some warnings won't appear due to same-class usage

---

## ✅ Success Criteria Met

### Phase 5.3 Completion Criteria

- ✅ All 5 main chain methods marked @Deprecated
- ✅ Comprehensive Javadoc added to each method
- ✅ Migration path documented (Phase 5.3 → Post-restart → Future)
- ✅ Replacement strategy explained (automatic BlockV5 processing)
- ✅ Impact highlighted (critical for fork resolution / main chain)
- ✅ Compilation successful (BUILD SUCCESS)
- ✅ Changes committed with comprehensive commit message

### Quality Checks

- ✅ All methods use `@Deprecated(since = "0.8.1", forRemoval = true)`
- ✅ All Javadoc follows consistent pattern
- ✅ All methods preserve existing implementation
- ✅ No breaking changes introduced
- ✅ Maven compilation successful
- ✅ Git commit successful with detailed message

---

## 📚 Documentation Updates

### Files Modified

1. **BlockchainImpl.java** - Added @Deprecated to 5 main chain methods
   - findAncestor() - line 908
   - updateNewChain() - line 974
   - unWindMain() - line 1119
   - setMain() - line 1681
   - unSetMain() - line 1750

### New Documentation

1. **PHASE5.3_COMPLETE.md** (This file) - Phase 5.3 completion report

### Related Documents

- [PHASE5_LEGACY_CLEANUP_PLAN.md](PHASE5_LEGACY_CLEANUP_PLAN.md) - Phase 5 overall plan
- [PHASE5.1_COMPLETE.md](PHASE5.1_COMPLETE.md) - Phase 5.1 completion (network messages)
- [PHASE5.2_COMPLETE.md](PHASE5.2_COMPLETE.md) - Phase 5.2 completion (block creation)
- [PHASE4_STORAGE_COMPLETION.md](PHASE4_STORAGE_COMPLETION.md) - Phase 4 completion
- [PHASE3.3_COMPLETE.md](PHASE3.3_COMPLETE.md) - Phase 3.3 completion

---

## 🎊 Conclusion

**Phase 5.3 Status**: ✅ **COMPLETE**

**Key Achievement**: Successfully deprecated all 5 legacy Block-based main chain management methods, marking them for removal as part of the v5.1 complete refactor strategy.

**Impact**:
- Clear migration path documented for future maintainers
- All critical fork resolution methods marked for removal
- System ready for Phase 5.4 (interface method deprecation)
- On track for Phase 5.5 (BlockV5 conversion)

**Next Phase**: Phase 5.4 - Deprecate Blockchain.tryToConnect(Block) interface method

**Recommendation**: Proceed with Phase 5.4 to complete interface method deprecation, then move to Phase 5.5 for BlockV5 conversion (mining and wallet).

---

**Phase 5.3 Completed**: 2025-10-31
**Branch**: refactor/core-v5.1
**Commit**: c828618a
**Status**: ✅ **COMPLETE** - All 5 main chain methods deprecated

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude <noreply@anthropic.com>
