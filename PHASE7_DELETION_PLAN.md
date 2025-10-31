# Phase 7: Legacy Code Deletion & Refactoring Plan

**Status**: 📋 **PLANNING**
**Date**: 2025-10-31
**Branch**: `refactor/core-v5.1`
**Goal**: Delete all deprecated legacy v1.0 classes and refactor to v5.1 architecture

---

## Executive Summary

**User Requirement**: "删除这些过时的代码，并且涉及到的地方用新的数据结构重构"
(Delete outdated code and refactor all related places with new data structures)

**Scope**:
- Delete 8 deprecated legacy classes
- Refactor ~70+ files that use these classes
- Full migration to v5.1 architecture (BlockV5, Link, Transaction)

**Estimated Effort**: 40-60 hours (high complexity refactoring)

---

## Legacy Classes Usage Analysis

| Class | Files Using | Total Occurrences | Complexity | Priority |
|-------|-------------|-------------------|------------|----------|
| **Block.java** | 49 | 567 | 🔴 VERY HIGH | 7 (Last) |
| **Address.java** | 26 | 200 | 🟡 MEDIUM | 4 |
| **LegacyBlockInfo.java** | 11 | 62 | 🟠 HIGH | 6 |
| **XdagBlock.java** | 3 | ~20 | 🟢 LOW | 3 |
| **XdagField.java** | 6 | ~30 | 🟡 MEDIUM | 5 |
| **BlockState.java** | 3 | ~10 | 🟢 VERY LOW | 1 (First) |
| **PreBlockInfo.java** | 4 | ~15 | 🟢 LOW | 2 |
| **TxAddress.java** | 1 | ~5 | 🟢 VERY LOW | 2 |

---

## Deletion Strategy: 3-Phase Approach

### Phase 7.1: Quick Wins (Easy Deletions)
**Estimated Time**: 4-6 hours

1. **BlockState.java** ✅ EASIEST
   - Files: 3 (Commands.java, XdagApiImpl.java)
   - Refactor: Inline string constants
   - Risk: Very Low

2. **PreBlockInfo.java** ✅ EASY
   - Files: 4 (SnapshotStore + implementation)
   - Refactor: Direct BlockInfo construction
   - Risk: Low

3. **TxAddress.java** ✅ EASY
   - Files: 1 (Block.java only)
   - Refactor: Will be removed with Block.java
   - Risk: Very Low

### Phase 7.2: Medium Complexity Deletions
**Estimated Time**: 12-16 hours

4. **XdagBlock.java** 🟡 MEDIUM
   - Files: 3 (2 deprecated messages + 1 test)
   - Refactor: Remove deprecated messages, update test
   - Dependencies: Block.java uses it internally
   - Risk: Medium (needs Block.java refactor first)

5. **Address.java** 🟡 MEDIUM
   - Files: 26 (many are tests/deprecated code)
   - Refactor: Replace with Link throughout
   - Risk: Medium

6. **XdagField.java** 🟡 MEDIUM
   - Files: 6 (Block.java, Address.java, XdagBlock.java + 3 others)
   - Refactor: Remove with XdagBlock deletion
   - Risk: Medium

### Phase 7.3: High Complexity Core Deletions
**Estimated Time**: 24-40 hours

7. **LegacyBlockInfo.java** 🟠 HIGH
   - Files: 11 (Storage layer: BlockStore, SnapshotStore, etc.)
   - Refactor: Storage migration to BlockInfo-only format
   - Dependencies: Storage format change
   - Risk: High (requires storage migration)

8. **Block.java** 🔴 VERY HIGH
   - Files: 49 (Core, Storage, Network, Consensus, etc.)
   - Refactor: Replace with BlockV5 everywhere
   - Dependencies: Almost everything
   - Risk: Very High (requires extensive testing)

---

## Detailed Refactoring Plans

### 7.1.1: Delete BlockState.java

**Current Usage**:
```java
// Commands.java
public static String getStateByFlags(int flags) {
    if ((flag & (BI_MAIN | BI_MAIN_CHAIN)) != 0) {
        return BlockState.MAIN.getDesc();  // "Main"
    }
    // ...
}

// XdagApiImpl.java
import static io.xdag.core.BlockState.MAIN;
```

**Refactoring**:
```java
// Option 1: Inline string constants in Commands.java
public static String getStateByFlags(int flags) {
    if ((flag & (BI_MAIN | BI_MAIN_CHAIN)) != 0) {
        return "Main";  // Direct string
    }
    // ...
}

// Option 2: Create simple constants class
public class BlockStateConstants {
    public static final String MAIN = "Main";
    public static final String REJECTED = "Rejected";
    public static final String ACCEPTED = "Accepted";
    public static final String PENDING = "Pending";
}
```

**Files to Modify**:
1. Commands.java - Change getStateByFlags() method
2. XdagApiImpl.java - Remove MAIN import, use string literal
3. Delete BlockState.java

**Risk**: ✅ Very Low - Simple string replacement

---

### 7.1.2: Delete PreBlockInfo.java

**Current Usage**:
```java
// SnapshotStoreImpl.java
public void setBlockInfo(LegacyBlockInfo blockInfo, PreBlockInfo preBlockInfo) {
    blockInfo.setSnapshot(preBlockInfo.isSnapshot());
    blockInfo.setSnapshotInfo(preBlockInfo.getSnapshotInfo());
    blockInfo.setFee(XAmount.of(preBlockInfo.getFee()));
    // ... copy 10+ fields
}
```

**Refactoring**:
```java
// Phase 1: Modify setBlockInfo to use BlockInfo directly
public void setBlockInfo(LegacyBlockInfo legacyInfo, BlockInfo modernInfo) {
    legacyInfo.setSnapshot(modernInfo.isSnapshot());
    legacyInfo.setSnapshotInfo(modernInfo.getSnapshotInfo());
    legacyInfo.setFee(modernInfo.getFee());
    // ... copy from BlockInfo
}

// Phase 2 (after LegacyBlockInfo deletion): Remove entirely
// Direct BlockInfo usage, no intermediate DTO needed
```

**Files to Modify**:
1. SnapshotStore.java - Change interface signature
2. SnapshotStoreImpl.java - Update implementation
3. Delete PreBlockInfo.java

**Risk**: 🟡 Low-Medium - Requires LegacyBlockInfo changes

---

### 7.1.3: Delete TxAddress.java

**Current Usage**:
```java
// Block.java (only user)
private TxAddress txNonceField;

public Block(..., UInt64 txNonce) {
    if (txNonce != null) {
        txNonceField = new TxAddress(txNonce);
        typeValue |= XDAG_FIELD_TRANSACTION_NONCE.asByte() << (length++ << 2);
    }
}
```

**Refactoring**:
```java
// Block.java will be deleted in Phase 7.3
// TxAddress is only used by Block.java, so deletion is coupled
// When Block.java is deleted, TxAddress.java can be deleted too

// In BlockV5: Transaction nonce stored in Transaction object, not in block
```

**Files to Modify**:
1. Delete TxAddress.java when Block.java is deleted

**Risk**: ✅ Very Low - Deletion coupled with Block.java

---

### 7.2.1: Delete XdagBlock.java

**Current Usage**:
```java
// NewBlockMessage.java (DEPRECATED)
private final XdagBlock xdagBlock;

// SyncBlockMessage.java (DEPRECATED)
private final XdagBlock xdagBlock;

// Block.java (to be deleted)
private XdagBlock xdagBlock;
public Block(XdagBlock xdagBlock) {
    this.xdagBlock = xdagBlock;
    parse();
}
```

**Refactoring**:
```java
// Step 1: Delete NewBlockMessage.java and SyncBlockMessage.java
// (Already deprecated in Phase 5.1, replaced by NewBlockV5Message/SyncBlockV5Message)

// Step 2: Remove XdagBlock from Block.java
// Block.java uses XdagBlock for 512-byte serialization
// When Block.java is deleted, XdagBlock is no longer needed

// Step 3: Update tests to use BlockV5
```

**Files to Modify**:
1. Delete NewBlockMessage.java
2. Delete SyncBlockMessage.java
3. Update BlockStoreImplTest.java to use BlockV5
4. Delete XdagBlock.java (after Block.java deletion)

**Risk**: 🟡 Medium - Requires deleting deprecated messages first

---

### 7.2.2: Delete Address.java

**Current Usage** (26 files, 200 occurrences):

**Main Categories**:
1. **Block.java** (18 occurrences) - Will be deleted
2. **Wallet.java** (10 occurrences) - Transaction creation (deprecated methods)
3. **Test files** (3 files) - BlockBuilder, CommandsTest, TransactionHistoryStoreImplTest
4. **Storage** - TransactionStore, AddressStore interfaces
5. **RPC/CLI** - Commands.java, XdagApiImpl.java

**Refactoring**:

**Category 1: Block.java (Delete entire class)**
```java
// Legacy Block.java
private List<Address> inputs = new CopyOnWriteArrayList<>();
private List<Address> outputs = new CopyOnWriteArrayList<>();

// Replacement: BlockV5.java
private List<Link> links;  // Links to transactions/blocks
// Amount stored in Transaction objects, not in links
```

**Category 2: Wallet.java Transaction Creation**
```java
// Legacy Wallet.java (createTransactionBlock - DEPRECATED)
List<Address> to = ...;
List<SyncBlock> txBlocks = wallet.createTransactionBlock(keys, to, remark, nonce);

// v5.1 Wallet (xferV2)
Transaction tx = Transaction.builder()
    .from(fromAddress)
    .to(toAddress)
    .amount(amount)
    .nonce(nonce)
    .build().sign(account);
```

**Category 3: Storage Layer**
```java
// Legacy TransactionStore
void saveAddress(Bytes32 hash, List<Address> inputs, List<Address> outputs);

// v5.1 TransactionStore
void saveTransaction(Transaction tx);  // Transaction contains Links, not Addresses
```

**Files to Modify** (26 files):
1. Block.java - DELETE
2. Wallet.java - Remove deprecated createTransactionBlock methods
3. BlockBuilder.java (test) - Use BlockV5 builder
4. CommandsTest.java (test) - Update assertions
5. TransactionHistoryStoreImplTest.java (test) - Use Link/Transaction
6. TransactionStore.java - Remove Address-based methods
7. AddressStore.java - Review if needed (may keep for address balance tracking)
8. Commands.java - Replace Address with Link in display logic
9. XdagApiImpl.java - Use BlockV5/Link in RPC responses
10. ... (16 more files)

**Risk**: 🟡 Medium - Many files, but most are tests or deprecated code

---

### 7.2.3: Delete XdagField.java

**Current Usage** (6 files):
1. Block.java - Field type handling (DELETE)
2. Address.java - FieldType enum usage (DELETE)
3. XdagBlock.java - Field array (DELETE)
4. BlockchainImpl.java - createNewBlock() (DEPRECATED)
5. TxAddress.java - Constructor (DELETE)
6. Config/AbstractConfig - Header field type

**Refactoring**:
```java
// Legacy XdagField.FieldType
XDAG_FIELD_IN, XDAG_FIELD_OUT,  // Block references
XDAG_FIELD_INPUT, XDAG_FIELD_OUTPUT,  // Address references
XDAG_FIELD_SIGN_IN, XDAG_FIELD_SIGN_OUT,  // Signatures
XDAG_FIELD_COINBASE,  // Miner address

// v5.1 Link.Type (simpler)
TO_TRANSACTION,  // Link to transaction
TO_BLOCK         // Link to block
```

**Files to Modify**:
1. Delete XdagField.java after Block/Address/XdagBlock deleted
2. Config.java - Replace XDAG_FIELD_HEAD with constant
3. Update any remaining references to use Link.Type

**Risk**: 🟡 Medium - Depends on Block/Address/XdagBlock deletion

---

### 7.3.1: Delete LegacyBlockInfo.java (Storage Migration)

**Current Usage** (11 files):
- BlockStore.java - saveLegacyBlockInfo(), getLegacyBlockInfo()
- BlockStoreImpl.java - Storage format serialization
- BlockchainImpl.java - Block info retrieval
- SnapshotStore.java - Snapshot operations
- Various tests

**Refactoring** (Storage Format Migration):

**Step 1: Dual-Format Storage** (Phase 7.3.1a)
```java
// BlockStore.java
void saveBlockInfo(Bytes32 hash, BlockInfo blockInfo);  // v5.1
@Deprecated
void saveLegacyBlockInfo(Bytes32 hash, LegacyBlockInfo legacy);  // v1.0

BlockInfo getBlockInfo(Bytes32 hash);  // Try v5.1 first, fallback to legacy
```

**Step 2: Migration Tool** (Phase 7.3.1b)
```java
// StorageMigrationTool.java
public void migrateLegacyToModern() {
    // 1. Scan all legacy storage
    // 2. Convert LegacyBlockInfo → BlockInfo
    // 3. Rewrite in v5.1 format
    // 4. Verify data integrity
}
```

**Step 3: Legacy Removal** (Phase 7.3.1c)
```java
// BlockStore.java (v5.1 only)
void saveBlockInfo(Bytes32 hash, BlockInfo blockInfo);
BlockInfo getBlockInfo(Bytes32 hash);
// No more LegacyBlockInfo methods
```

**Files to Modify**:
1. BlockStore.java - Add v5.1 methods, deprecate legacy
2. BlockStoreImpl.java - Implement dual-format storage
3. SnapshotStoreImpl.java - Use BlockInfo directly
4. BlockchainImpl.java - Use BlockInfo only
5. StorageMigrationTool.java - CREATE NEW migration tool
6. Delete LegacyBlockInfo.java after migration complete

**Risk**: 🟠 High
- Storage format change
- Requires data migration
- Backward compatibility concerns

**Mitigation**:
- Dual-format storage during transition
- Migration tool with verification
- Rollback plan

---

### 7.3.2: Delete Block.java (Massive Core Refactoring)

**Current Usage** (49 files, 567 occurrences):

**Major Categories**:
1. **Storage Layer** (8 files)
   - BlockStore, FinalizedBlockStore, CachedBlockStore, BloomFilterBlockStore
   - OrphanBlockStore
2. **Network Layer** (4 files)
   - XdagP2pHandler, ChannelManager, PeerClient
   - XdagP2pEventHandler
3. **Consensus Layer** (4 files)
   - XdagPow, RandomX, XdagSync, SyncManager
4. **Blockchain Core** (2 files)
   - Blockchain.java, BlockchainImpl.java
5. **Deprecated Messages** (2 files)
   - NewBlockMessage, SyncBlockMessage
6. **Wallet/CLI** (2 files)
   - Wallet.java, Commands.java
7. **RPC/API** (2 files)
   - XdagApi, XdagApiImpl
8. **Tests** (~10 files)

**Refactoring Strategy**: **Incremental Layer-by-Layer Migration**

**Phase 7.3.2a: Storage Layer** (Highest Impact)
```java
// Legacy BlockStore
Block getBlockByHash(Bytes32 hash);
void saveBlock(Block block);

// v5.1 BlockStore (Already exists from Phase 4!)
BlockV5 getBlockV5(Bytes32 hash);
void saveBlockV5(BlockV5 blockV5);

// Action: Remove legacy Block methods
```

**Phase 7.3.2b: Network Layer**
```java
// Legacy XdagP2pHandler
private void processNewBlock(Block block) {
    blockchain.tryToConnect(block);  // DEPRECATED
}

// v5.1 XdagP2pHandler
private void processNewBlockV5(BlockV5 blockV5) {
    blockchain.tryToConnect(blockV5);  // v5.1 method
}

// Action: Remove legacy processNewBlock()
```

**Phase 7.3.2c: Consensus Layer**
```java
// Legacy XdagPow
public Block createMiningBlock() {
    return blockchain.createNewBlock(...);  // DEPRECATED
}

// v5.1 XdagPow (Already refactored in Phase 5.5!)
public BlockV5 createMiningBlock() {
    return blockchain.createMainBlockV5();
}

// Action: Already done in Phase 5.5! Just remove legacy fallback
```

**Phase 7.3.2d: Blockchain Core**
```java
// Legacy Blockchain.java interface
@Deprecated
ImportResult tryToConnect(Block block);
@Deprecated
Block createNewBlock(...);

// v5.1 Blockchain.java
ImportResult tryToConnect(BlockV5 blockV5);
BlockV5 createMainBlockV5();

// Action: Remove deprecated methods
```

**Phase 7.3.2e: Deprecated Messages**
```java
// Delete these files entirely:
// - NewBlockMessage.java (replaced by NewBlockV5Message)
// - SyncBlockMessage.java (replaced by SyncBlockV5Message)
```

**Phase 7.3.2f: Wallet/CLI**
```java
// Legacy Wallet
@Deprecated
List<SyncBlock> createTransactionBlock(...);

// v5.1 Wallet
Transaction createTransaction(...);

// Legacy Commands (accountcmd)
private Block getLatestBlock();

// v5.1 Commands
private BlockV5 getLatestBlock();
```

**Phase 7.3.2g: RPC/API**
```java
// Legacy XdagApi
Block getBlock(String hash);

// v5.1 XdagApi
BlockV5 getBlock(String hash);
// Or return BlockDTO (data transfer object)
```

**Files to Modify** (~49 files):

**Storage** (8):
1. BlockStore.java - Remove Block methods
2. FinalizedBlockStore/Impl - Use BlockV5
3. CachedBlockStore - Use BlockV5
4. BloomFilterBlockStore - Use BlockV5
5. OrphanBlockStore/Impl - Use BlockV5

**Network** (4):
6. XdagP2pHandler.java - Remove legacy block processing
7. ChannelManager.java - Use BlockV5
8. PeerClient.java - Use BlockV5
9. XdagP2pEventHandler.java - Use BlockV5

**Consensus** (4):
10. XdagPow.java - Remove legacy mining (already done)
11. RandomX.java - Use BlockV5
12. XdagSync.java - Use BlockV5
13. SyncManager.java - Remove SyncBlock wrapper

**Core** (2):
14. Blockchain.java - Remove deprecated methods
15. BlockchainImpl.java - Remove Block implementations

**Messages** (2):
16. NewBlockMessage.java - DELETE
17. SyncBlockMessage.java - DELETE

**Wallet/CLI** (3):
18. Wallet.java - Remove createTransactionBlock
19. Commands.java - Use BlockV5
20. Kernel.java - Use BlockV5

**RPC** (2):
21. XdagApi.java - Remove Block methods
22. XdagApiImpl.java - Return BlockV5/DTO

**Utils** (2):
23. BlockUtils.java - Use BlockV5
24. WalletUtils.java - Use BlockV5

**Tests** (~15 files):
25-40. Update all test files

**Risk**: 🔴 **VERY HIGH**
- Touches ~50 files across all layers
- Storage + Network + Consensus + Core
- Extensive testing required
- High probability of bugs

**Mitigation**:
1. **Incremental Approach**: One layer at a time
2. **Comprehensive Testing**: Unit + Integration + System tests after each layer
3. **Feature Flags**: Enable v5.1-only mode gradually
4. **Rollback Plan**: Keep deprecated methods during transition
5. **Code Review**: Thorough review of each change

---

## Testing Strategy

### Phase 7.1 Testing (Quick Wins)
**Scope**: BlockState, PreBlockInfo, TxAddress deletion

**Tests**:
1. Unit Tests:
   - Commands.getStateByFlags() - String output correct
   - SnapshotStore operations - BlockInfo handled correctly
2. Integration Tests:
   - Snapshot creation/restoration works
   - CLI commands display correct state
3. Regression Tests:
   - Existing functionality unchanged

**Estimated Time**: 2 hours

---

### Phase 7.2 Testing (Medium Complexity)
**Scope**: XdagBlock, Address, XdagField deletion

**Tests**:
1. Unit Tests:
   - Address → Link conversion correct
   - Transaction references use Link
   - Field types mapped correctly
2. Integration Tests:
   - Message serialization/deserialization works
   - Network protocol unchanged
   - Transaction creation uses new format
3. Storage Tests:
   - BlockV5 serialization correct
   - Links stored/retrieved correctly
4. Regression Tests:
   - All existing features work

**Estimated Time**: 6-8 hours

---

### Phase 7.3 Testing (High Complexity)
**Scope**: LegacyBlockInfo, Block.java deletion

**Tests**:
1. Unit Tests:
   - Storage format conversion correct
   - BlockV5 operations work
   - All Block methods have BlockV5 equivalents
2. Integration Tests:
   - Storage migration successful
   - Data integrity preserved
   - Network sync works with v5.1 only
   - Mining produces valid BlockV5
   - Consensus validates BlockV5 correctly
3. System Tests:
   - Full node operation (mining, sync, transactions)
   - Wallet operations (send/receive)
   - RPC API returns correct data
4. Performance Tests:
   - v5.1 performance meets expectations (23,200 TPS)
   - Memory usage acceptable
   - Storage efficiency improved
5. Stress Tests:
   - Large blocks (48MB) handled correctly
   - 1,485,000 links per block works
6. Regression Tests:
   - All Phase 1-6 features preserved

**Estimated Time**: 20-30 hours

**Critical Tests**:
1. **Storage Migration**: 100% data preserved
2. **Network Compatibility**: Can sync with v5.1 nodes
3. **Consensus Integrity**: Chain validation correct
4. **Transaction Validity**: All tx types work
5. **Backward Compatibility**: Can read old blocks (if needed)

---

## Risk Assessment

### Low Risk ✅
- BlockState deletion (string constants)
- PreBlockInfo deletion (temporary DTO)
- TxAddress deletion (only Block.java uses it)

### Medium Risk ⚠️
- XdagBlock deletion (affects messages)
- Address deletion (26 files)
- XdagField deletion (field type mapping)

### High Risk 🚨
- LegacyBlockInfo deletion (storage format change)
- Block.java deletion (49 files, core architecture)

### Critical Risk 🔴
- **Data Loss**: Storage migration fails
  - **Mitigation**: Backup before migration, dual-format storage
- **Network Split**: v5.1-only nodes incompatible
  - **Mitigation**: Backward compatibility period, gradual rollout
- **Consensus Failure**: Chain validation breaks
  - **Mitigation**: Extensive testing, staged deployment
- **Transaction Loss**: Wallet operations fail
  - **Mitigation**: Transaction format validation, test transactions

---

## Rollback Plan

### Phase 7.1 Rollback (Easy)
**If Issues Found**:
1. Restore BlockState.java from git
2. Revert Commands.java changes
3. Recompile and test

**Time**: 30 minutes

### Phase 7.2 Rollback (Medium)
**If Issues Found**:
1. Restore Address.java, XdagBlock.java, XdagField.java
2. Revert all refactored files
3. Restore deprecated messages
4. Full regression testing

**Time**: 2-4 hours

### Phase 7.3 Rollback (Difficult)
**If Critical Issues Found**:
1. **Storage Rollback**:
   - Stop node
   - Restore database backup
   - Redeploy previous version
2. **Code Rollback**:
   - Revert all Phase 7.3 commits
   - Restore Block.java, LegacyBlockInfo.java
   - Full recompilation
3. **Network Rollback**:
   - Coordinate with other nodes
   - Revert to v1.0 compatible version

**Time**: 4-8 hours (depends on data size)

**Prevention**: Dual-format storage during transition period

---

## Timeline Estimate

| Phase | Tasks | Estimated Time | Risk Level |
|-------|-------|----------------|------------|
| **7.1: Quick Wins** | BlockState, PreBlockInfo, TxAddress | 4-6 hours | 🟢 Low |
| **7.2: Medium Complexity** | XdagBlock, Address, XdagField | 12-16 hours | 🟡 Medium |
| **7.3.1: Storage Migration** | LegacyBlockInfo deletion | 12-16 hours | 🟠 High |
| **7.3.2: Core Refactoring** | Block.java deletion | 20-30 hours | 🔴 Very High |
| **Testing** | All phases | 28-38 hours | - |
| **Documentation** | Reports, guides | 4-6 hours | - |
| **Total** | All phases | **80-112 hours** | - |

**Estimated Calendar Time**: 2-3 weeks (full-time development)

---

## Success Metrics

### Code Quality
- [ ] All 8 legacy classes deleted
- [ ] 0 deprecation warnings remaining
- [ ] All tests passing (100% success rate)
- [ ] Code coverage maintained or improved

### Architecture
- [ ] 100% v5.1 architecture (BlockV5, Link, Transaction)
- [ ] No v1.0 code remaining
- [ ] Clean separation of concerns
- [ ] Immutable data structures throughout

### Performance
- [ ] 23,200 TPS capacity verified
- [ ] 48MB block support working
- [ ] 1,485,000 links per block validated
- [ ] Memory usage within acceptable range

### Storage
- [ ] 100% BlockInfo format (no LegacyBlockInfo)
- [ ] Storage migration tool complete
- [ ] Data integrity verified (checksum validation)
- [ ] Backward read compatibility (if required)

### Network
- [ ] v5.1-only messages working
- [ ] Deprecated messages removed (NewBlockMessage, SyncBlockMessage)
- [ ] Network sync verified
- [ ] Peer communication stable

### Testing
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] All system tests pass
- [ ] Performance tests meet targets
- [ ] Stress tests pass

---

## Recommendation

### Immediate Actions (Phase 7.1 - Quick Wins)

✅ **START WITH PHASE 7.1** - Low risk, quick wins:
1. Delete BlockState.java (inline strings)
2. Delete PreBlockInfo.java (direct BlockInfo)
3. Delete TxAddress.java (coupled with Block.java)

**Estimated Time**: 4-6 hours
**Risk**: 🟢 Very Low
**Benefit**: Clean up easiest classes, build confidence

### Short-term (Phase 7.2 - Medium Complexity)

After Phase 7.1 success:
1. Delete XdagBlock.java and deprecated messages
2. Delete Address.java (replace with Link)
3. Delete XdagField.java

**Estimated Time**: 12-16 hours
**Risk**: 🟡 Medium
**Benefit**: Remove message-level legacy code

### Long-term (Phase 7.3 - High Complexity)

**CAREFUL PLANNING REQUIRED**:
1. Storage migration (LegacyBlockInfo)
2. Core refactoring (Block.java)

**Estimated Time**: 32-46 hours
**Risk**: 🔴 Very High
**Benefit**: Complete v1.0 → v5.1 migration

**Recommendation**:
- Allocate dedicated time (1-2 weeks)
- Full testing environment
- Staged rollout plan
- Backup and rollback procedures

---

## Next Steps

1. **User Approval**: Confirm Phase 7 plan
2. **Start Phase 7.1**: Quick wins (BlockState, PreBlockInfo, TxAddress)
3. **Test Thoroughly**: Verify no regressions
4. **Proceed to Phase 7.2**: Medium complexity deletions
5. **Plan Phase 7.3**: High complexity core refactoring
6. **Document Progress**: Track changes and issues

---

**Document Version**: 1.0
**Status**: 📋 PLANNING - Awaiting User Approval
**Next Action**: Begin Phase 7.1 Quick Wins

