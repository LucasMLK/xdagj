# XDAG Mining Code Cleanup Plan

**Date**: 2025-01-14
**Status**: 📋 Plan
**Context**: After implementing Phase 1 (Mining RPC Service), identify obsolete code

---

## 📊 Current Mining Components Analysis

### Components in `src/main/java/io/xdag/consensus/miner/`

| Component | Purpose | Current Status | Future Action |
|-----------|---------|----------------|---------------|
| **BlockGenerator.java** | Generate candidate blocks | ✅ **KEEP** | Used by MiningRpcServiceImpl |
| **MiningManager.java** | Pool server (internal) | ⚠️ **DEPRECATE** | Move to xdagj-pool in Phase 2 |
| **ShareValidator.java** | Validate mining shares | ⚠️ **DEPRECATE** | Move to xdagj-pool in Phase 2 |
| **BlockBroadcaster.java** | Broadcast mined blocks | ⚠️ **DEPRECATE** | Move to xdagj-pool in Phase 2 |
| **MiningTask.java** | Mining task data | ⚠️ **DEPRECATE** | Move to xdagj-pool in Phase 2 |
| **LocalMiner.java** | CPU miner (testing) | ⚠️ **DEPRECATE** | Move to xdagj-miner in Phase 4 |

---

## 🎯 Cleanup Actions

### Action 1: Mark Pool Components as @Deprecated

**Components to deprecate**:
```java
@Deprecated(since = "0.8.2", forRemoval = true)
@ScheduledForRemoval(inVersion = "0.9.0")
```

1. **MiningManager.java**
   - Reason: Internal pool server, should be external (xdagj-pool)
   - Migration: Use external pool server connecting via MiningRpcService
   - Timeline: Remove in v0.9.0 (after Phase 2 complete)

2. **ShareValidator.java**
   - Reason: Pool-specific functionality
   - Migration: Will be in xdagj-pool project
   - Timeline: Remove in v0.9.0

3. **BlockBroadcaster.java**
   - Reason: Pool-specific functionality
   - Migration: Will be in xdagj-pool project
   - Timeline: Remove in v0.9.0

4. **MiningTask.java**
   - Reason: Pool-specific data structure
   - Migration: Will be in xdagj-pool project
   - Timeline: Remove in v0.9.0

5. **LocalMiner.java**
   - Reason: Should be standalone miner program
   - Migration: Will become xdagj-miner project
   - Timeline: Remove in v0.9.0

### Action 2: Keep and Document Node Components

**Components to KEEP**:

1. **BlockGenerator.java**
   - Reason: Core blockchain functionality (generate candidate blocks)
   - Used by: MiningRpcServiceImpl
   - Action: Add clear documentation about its role

2. **MiningRpcServiceImpl** (NEW)
   - Reason: Node's interface to external pools
   - Keep as: Primary mining interface going forward

---

## 📝 Deprecation Messages

### Example for MiningManager:

```java
/**
 * MiningManager - Internal pool server (DEPRECATED)
 *
 * <p><strong>⚠️ DEPRECATION NOTICE</strong>:
 * This class implements an internal pool server that is deprecated and will be
 * removed in version 0.9.0. Mining pool functionality should be moved to the
 * standalone xdagj-pool project.
 *
 * <h2>Migration Path</h2>
 * <p>Instead of using MiningManager directly:
 * <ol>
 *   <li>Deploy xdagj node with {@link io.xdag.rpc.service.MiningRpcServiceImpl}</li>
 *   <li>Deploy separate xdagj-pool connecting to node via RPC</li>
 *   <li>Connect miners to xdagj-pool (not directly to node)</li>
 * </ol>
 *
 * <p>For testing purposes, you can still enable MiningManager temporarily,
 * but this is not recommended for production deployments.
 *
 * @deprecated Since 0.8.2, scheduled for removal in 0.9.0.
 *             Use external pool server (xdagj-pool) instead.
 * @see io.xdag.rpc.service.MiningRpcServiceImpl
 */
@Deprecated(since = "0.8.2", forRemoval = true)
public class MiningManager {
    // ... existing code ...
}
```

---

## 🗑️ Code to Remove

### 1. Obsolete DEVNET Test Code

**Status**: ✅ Already cleaned in previous commit

The following DEVNET test code was already removed:
- Auto-submit test share code
- Shortened timeout code for testing

**Remaining DEVNET code**:
The code in MiningManager.java lines 250-260 is **valid and should be kept**:
- Reduces initial mining delay for devnet testing
- This is a legitimate testing optimization, not obsolete code

### 2. Unused Imports/Methods

Will scan for and remove during deprecation process.

---

## 📅 Cleanup Timeline

### Phase 1 (Current) - v0.8.2
- ✅ MiningRpcService implemented
- ⏳ Mark pool components as @Deprecated
- ⏳ Add migration documentation
- **Status**: Can deploy with warning messages

### Phase 2 - v0.8.3 (2-3 weeks)
- Create xdagj-pool project
- Migrate MiningManager, ShareValidator, BlockBroadcaster
- **Status**: Old components deprecated but functional

### Phase 3 - v0.8.4 (1-2 weeks)
- Create xdagj-miner project
- Migrate LocalMiner
- **Status**: Full three-layer architecture

### Phase 4 - v0.9.0 (Final cleanup)
- **Remove all deprecated components**
- Node only contains:
  - BlockGenerator
  - MiningRpcServiceImpl
  - Core blockchain functionality

---

## ⚠️ Breaking Changes Notice

### For v0.9.0

**Removed Classes**:
```java
// These will be REMOVED in v0.9.0:
io.xdag.consensus.miner.MiningManager          → Use xdagj-pool
io.xdag.consensus.miner.ShareValidator         → Use xdagj-pool
io.xdag.consensus.miner.BlockBroadcaster       → Use xdagj-pool
io.xdag.consensus.miner.MiningTask             → Use xdagj-pool
io.xdag.consensus.miner.LocalMiner             → Use xdagj-miner
```

**Migration Required**:
```yaml
# Old (deprecated):
xdagj:
  mining:
    enabled: true
    manager: internal  # ← Will be removed

# New (v0.9.0+):
xdagj:
  mining:
    rpc:
      enabled: true    # ← Node provides RPC only

# Separate pool server:
xdagj-pool:
  node:
    rpcUrl: "http://localhost:10001"
  stratum:
    port: 3333
```

---

## 🔍 Verification Steps

### After Deprecation
1. ✅ Compilation succeeds with warnings
2. ✅ Tests pass with deprecation warnings
3. ✅ Runtime logs show deprecation notices
4. ✅ Documentation updated with migration path

### Before Removal (v0.9.0)
1. ✅ xdagj-pool fully functional
2. ✅ xdagj-miner fully functional
3. ✅ Migration guide published
4. ✅ Users notified via release notes

---

## 📚 Documentation Updates Needed

1. **README.md**
   - Add migration notice
   - Point to xdagj-pool documentation

2. **CHANGELOG.md**
   - Document deprecations in v0.8.2
   - Document removals in v0.9.0

3. **Migration Guide** (new)
   - Step-by-step migration from internal to external pool
   - Configuration examples
   - Troubleshooting

---

## 🎯 Summary

### What to Clean NOW (v0.8.2)
1. ✅ Mark 5 components as @Deprecated
2. ✅ Add migration documentation
3. ✅ Update DagKernel to log deprecation warnings

### What to Keep
1. ✅ BlockGenerator (core functionality)
2. ✅ MiningRpcServiceImpl (new interface)
3. ✅ All PoW algorithms
4. ✅ Valid DEVNET optimizations

### What to Remove LATER (v0.9.0)
1. ⏳ All deprecated pool components
2. ⏳ Configuration options for internal pool
3. ⏳ Internal pool-related tests

---

**Next Action**: Execute deprecation process and create migration documentation.

**Timeline**: Complete deprecation in current sprint, remove in v0.9.0 after 2-3 months.
