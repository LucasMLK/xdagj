# Phase 8.2: RPC API Method Analysis

**Status**: ✅ Analysis Complete
**Date**: 2025-11-04
**Branch**: refactor/core-v5.1

---

## Executive Summary

After reviewing XdagApiImpl.java in the context of v5.1 refactor, **4 out of 11 disabled methods are obsolete** and should be deleted. The remaining 7 methods need restoration using v5.1 patterns.

**Key insight**: v5.1's Transaction architecture makes several legacy RPC methods redundant.

---

## Method Classification

### ✅ Already Working (3 methods)

1. **`xdag_getBlocksByNumber(String bnOrId)`** ✅
   - Lines: 220-231
   - Status: Fully functional
   - Uses: `blockchain.listMainBlocks()` + `transferBlockToBriefBlockResultDTO()`
   - Note: Calls disabled helper but still works

2. **`doXfer(...)`** ✅
   - Lines: 886-1204
   - Status: Fully migrated to BlockV5 + Transaction
   - Supports: Single-account and multi-account transactions
   - Phase 8.1 completion

3. **Account balance part of `xdag_getBalance(String address)`** ✅
   - Lines: 251-254
   - Status: Works for Base58 addresses
   - Uses: `AddressStore.getBalanceByAddress()`

---

## ⛔ OBSOLETE - Should Delete (4 methods)

### 1. `xdag_sendRawTransaction(String rawData)` ❌ DELETE

**Lines**: 421-482

**Reason**: Replaced by `doXfer()` + `xdag_personal_sendTransaction()`

**Analysis**:
- Legacy method accepts raw Block bytes with Address objects
- v5.1: We have `doXfer()` that creates Transaction objects internally
- v5.1: We have `xdag_personal_sendTransaction()` for RPC transaction creation
- This method's functionality is **fully covered** by existing methods

**Recommendation**: DELETE this method entirely

---

### 2. `checkTransaction(Block block)` ❌ DELETE

**Lines**: 1258-1275 (commented out)

**Reason**: Validation moved to `doXfer()` and Transaction.isValid()

**Analysis**:
- Legacy method validates Block.getInputs() for empty inputs and reject addresses
- v5.1: `doXfer()` validates Transaction objects using `tx.isValid()` and `tx.verifySignature()`
- v5.1: Address rejection can be checked at Transaction level
- This method is **no longer needed**

**Recommendation**: DELETE this commented-out method

---

### 3. `getType(Block block)` ❌ DELETE

**Lines**: 827-843 (commented out)

**Reason**: Type determination logic outdated

**Analysis**:
```java
// Legacy logic:
if (getStateByFlags(block.getInfo().getFlags()).equals("Main")) {
    return "Main";
} else if (block.getInsigs() == null || block.getInsigs().isEmpty()) {
    if (CollectionUtils.isEmpty(block.getInputs()) && CollectionUtils.isEmpty(block.getOutputs())) {
        return "Wallet";
    } else {
        return "Transaction";
    }
}
```

- Uses Block.getInputs(), getOutputs(), getInsigs() - **all deleted in v5.1**
- Uses BlockInfo.flags - **deleted in v5.1**
- v5.1: Type can be determined from BlockInfo.isMainBlock() and Link structure
- This method is **not reusable**

**Recommendation**: DELETE and reimplement type logic inline if needed

---

### 4. `getLinks(Block block)` ❌ DELETE

**Lines**: 690-737 (commented out)

**Reason**: Link structure completely different in v5.1

**Analysis**:
- Legacy method extracts Address objects from Block.getInputs() and Block.getOutputs()
- v5.1: Block has Link objects (33 bytes), not Address objects (64 bytes)
- v5.1: Links reference Transaction hashes, not Address amounts
- This method's logic is **incompatible with v5.1**

**Recommendation**: DELETE and replace with new `getLinksV5(BlockV5)` if needed

---

## 🔧 NEED RESTORATION (7 methods)

### Category 1: Balance Queries (2 methods)

#### 1. `xdag_getBalanceByNumber(String bnOrId)` 🔧

**Lines**: 145-157

**Current issue**: Returns "0.0" (disabled)

**Restoration pattern** (same as CLI `balance()`):
```java
public String xdag_getBalanceByNumber(String bnOrId) {
    BlockV5 block = blockchain.getBlockByHeight(Long.parseLong(bnOrId));
    if (block == null) {
        return "0.0";
    }

    // v5.1: Calculate balance from Transactions
    List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(block.getHash());
    XAmount totalAmount = XAmount.ZERO;
    for (Transaction tx : transactions) {
        totalAmount = totalAmount.add(tx.getAmount());
    }

    return totalAmount.toDecimal(9, XUnit.XDAG).toPlainString();
}
```

**Complexity**: Low (5-10 lines)

---

#### 2. Block hash part of `xdag_getBalance(String address)` 🔧

**Lines**: 256-270

**Current issue**: Returns "0.0" for block addresses

**Restoration pattern**: Same as above, but parse address to hash first

**Complexity**: Low (similar to xdag_getBalanceByNumber)

---

### Category 2: Block Display (3 methods)

#### 3. `transferBlockInfoToBlockResultDTO(BlockV5, int page, ...)` 🔧

**Lines**: 524-565

**Current issue**: Returns minimal response with "0.0" balance

**Restoration pattern**:
```java
private BlockResponse transferBlockInfoToBlockResultDTO(BlockV5 blockV5, int page, Object... parameters) {
    if (blockV5 == null) {
        return null;
    }

    BlockInfo info = blockV5.getInfo();

    // Calculate balance from Transactions
    List<Transaction> txs = kernel.getTransactionStore().getTransactionsByBlock(blockV5.getHash());
    XAmount balance = XAmount.ZERO;
    for (Transaction tx : txs) {
        balance = balance.add(tx.getAmount());
    }

    BlockResponse.BlockResponseBuilder builder = BlockResponse.builder();
    builder.address(hash2Address(blockV5.getHash()))
            .hash(blockV5.getHash().toUnprefixedHexString())
            .balance(balance.toDecimal(9, XUnit.XDAG).toPlainString())
            .type("Snapshot")  // Simplified type
            .blockTime(xdagTimestampToMs(info.getTimestamp()))
            .timeStamp(info.getTimestamp())
            .state(info.isMainBlock() ? "Main" : "Orphan");

    if (page != 0) {
        builder.transactions(getTxHistoryV5(blockV5.getHash(), page, parameters))
                .totalPage(totalPage);
    }

    return builder.build();
}
```

**Complexity**: Medium (20-30 lines)

---

#### 4. `transferBlockToBlockResultDTO(BlockV5, int page, ...)` 🔧

**Lines**: 739-782

**Restoration pattern**: Similar to `transferBlockInfoToBlockResultDTO`, but with full details

**Key changes**:
- Remove flags, remark (deleted in v5.1)
- Calculate balance from Transactions
- Use `getLinksV5()` to show Link structure

**Complexity**: Medium (30-40 lines)

---

#### 5. `transferBlockToBriefBlockResultDTO(BlockV5)` 🔧

**Lines**: 784-825

**Restoration pattern**: Simplified version of `transferBlockToBlockResultDTO`

**Key changes**:
- Only basic info (address, hash, balance, timestamp, state, type, height)
- No transaction history
- Calculate balance from Transactions

**Complexity**: Low (15-20 lines)

---

### Category 3: Transaction History (2 methods)

#### 6. `getTxHistory(String address, int page, ...)` 🔧

**Lines**: 567-606

**Current issue**: Returns empty list

**Restoration pattern** (same as CLI `address()`):
```java
private List<BlockResponse.TxLink> getTxHistory(String address, int page, Object... parameters)
    throws AddressFormatException {

    Bytes32 hash = pubAddress2Hash(address);
    List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByAddress(hash);
    List<BlockResponse.TxLink> txLinks = Lists.newArrayList();

    for (Transaction tx : transactions) {
        // Determine direction
        String direction;
        Bytes32 otherAddress;
        if (tx.getFrom().equals(hash)) {
            direction = "output";  // direction = 1
            otherAddress = tx.getTo();
        } else {
            direction = "input";  // direction = 0
            otherAddress = tx.getFrom();
        }

        BlockResponse.TxLink.TxLinkBuilder txLinkBuilder = BlockResponse.TxLink.builder();
        txLinkBuilder.address(hash2Address(otherAddress))
                .hash(otherAddress.toUnprefixedHexString())
                .amount(tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString())
                .direction(direction.equals("input") ? 0 : 1)
                .time(/* TODO: Get block timestamp from tx hash */)
                .remark(/* TODO: Decode from tx.getData() */);

        txLinks.add(txLinkBuilder.build());
    }

    return txLinks;
}
```

**Complexity**: Medium (25-35 lines)

**Note**: May need reverse index (txHash → blockHash) for timestamps

---

#### 7. `getTxLinks(Block block, int page, ...)` 🔧

**Lines**: 641-687 (commented out)

**Restoration pattern**:
```java
private List<BlockResponse.TxLink> getTxLinks(BlockV5 block, int page, Object... parameters) {
    List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(block.getHash());
    List<BlockResponse.TxLink> txLinks = Lists.newArrayList();

    // 1. Add earning info for Main blocks
    if (block.getInfo().isMainBlock() && block.getInfo().getHeight() > kernel.getConfig().getSnapshotSpec().getSnapshotHeight()) {
        XAmount reward = blockchain.getReward(block.getInfo().getHeight());
        // TODO: Calculate fee from transactions

        BlockResponse.TxLink.TxLinkBuilder txLinkBuilder = BlockResponse.TxLink.builder();
        txLinkBuilder.address(hash2Address(block.getHash()))
                .hash(block.getHash().toUnprefixedHexString())
                .amount(reward.toDecimal(9, XUnit.XDAG).toPlainString())
                .direction(2)  // Earning
                .time(xdagTimestampToMs(block.getTimestamp()))
                .remark("");
        txLinks.add(txLinkBuilder.build());
    }

    // 2. Add transaction history
    for (Transaction tx : transactions) {
        // Build TxLink from Transaction
        // Similar logic to getTxHistory()
    }

    return txLinks;
}
```

**Complexity**: Medium (30-40 lines)

---

## Summary Statistics

| Category | Count | Status |
|----------|-------|--------|
| Already Working | 3 | ✅ No action needed |
| Obsolete (DELETE) | 4 | ⛔ Remove completely |
| Need Restoration | 7 | 🔧 Restore with v5.1 patterns |
| **Total Disabled** | **11** | **4 DELETE + 7 RESTORE** |

---

## Implementation Priority

### Phase 8.2.2: Delete Obsolete Methods (Quick Wins)
1. Delete `xdag_sendRawTransaction()` (lines 421-482)
2. Delete `checkTransaction()` (lines 1258-1275)
3. Delete `getType()` (lines 827-843)
4. Delete `getLinks()` (lines 690-737)

**Estimated time**: 5 minutes
**Lines removed**: ~150 lines

---

### Phase 8.2.3: Restore Essential Methods

**Priority 1: Balance Queries** (needed for basic API functionality)
1. Restore `xdag_getBalanceByNumber()` - 10 lines
2. Restore block hash part of `xdag_getBalance()` - 15 lines

**Priority 2: Block Display** (needed for block queries)
3. Restore `transferBlockToBriefBlockResultDTO()` - 20 lines
4. Restore `transferBlockInfoToBlockResultDTO()` - 30 lines
5. Restore `transferBlockToBlockResultDTO()` - 40 lines

**Priority 3: Transaction History** (nice to have, but more complex)
6. Restore `getTxHistory()` - 35 lines
7. Restore `getTxLinks()` - 40 lines

**Estimated time**: 1-2 hours
**Lines added**: ~190 lines

---

## Key Architectural Insights

### Why These Methods Are Obsolete

1. **`xdag_sendRawTransaction()`**: v5.1 Transaction architecture means we build Transactions internally, not accept raw Block bytes
2. **`checkTransaction()`**: Validation moved to Transaction.isValid() and doXfer()
3. **`getType()`**: Block structure changed (no getInputs/getOutputs/getInsigs)
4. **`getLinks()`**: Link structure changed (33-byte references, not 64-byte Address objects)

### Pattern for Restoration

All 7 restoration methods follow the same v5.1 pattern:
1. Query TransactionStore for amounts (don't use BlockInfo.amount)
2. Use BlockInfo minimal fields (hash, height, difficulty, timestamp)
3. Calculate derived data (balance, type, state) on-demand
4. Use Transaction objects instead of Address objects

---

## Next Steps

1. ✅ **Phase 8.2.1**: Analysis complete (this document)
2. ⏳ **Phase 8.2.2**: Delete 4 obsolete methods
3. ⏳ **Phase 8.2.3**: Restore 7 essential methods
4. ⏳ **Phase 8.2.4**: Create completion report and commit

**Expected result**: Clean RPC API aligned with v5.1 architecture
