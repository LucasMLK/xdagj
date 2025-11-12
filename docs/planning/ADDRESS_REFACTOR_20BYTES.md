# Address Refactoring: 32 bytes → 20 bytes

**Date**: 2025-11-11
**Priority**: HIGH (Storage Optimization)
**Status**: PLANNING

---

## 📋 Executive Summary

Refactor XDAG address representation from 32-byte `Bytes32` to 20-byte `Bytes` to eliminate storage waste.

**Impact**:
- **Storage savings**: 24 bytes per account (12 in key + 12 in value)
- **Scalability**: ~30% reduction in AccountStore disk usage
- **Performance**: Slightly faster serialization/deserialization
- **Breaking change**: Requires full codebase update

**Scope**:
- 9 phases covering core model, storage, business logic, and tests
- ~20 files to modify
- No backward compatibility needed (still in development)

---

## 🎯 Why This Matters

### Current Waste (32-byte addresses)

```
Every account in RocksDB:
  Key:   [PREFIX: 1 byte] + [Address: 32 bytes] = 33 bytes
         └── 12 bytes wasted (padding zeros)

  Value: Account object with 32-byte address field
         └── 12 bytes wasted (padding zeros)

Total waste per account: 24 bytes
```

### Scale Impact

| Accounts | Current Storage | After Refactor | Savings |
|----------|----------------|----------------|---------|
| 100K     | ~7.3 MB        | ~5.0 MB        | **2.3 MB (31%)** |
| 1M       | ~73 MB         | ~50 MB         | **23 MB (31%)** |
| 10M      | ~730 MB        | ~500 MB        | **230 MB (31%)** |

With RocksDB overhead (Bloom filters, indexes), actual savings ~40-50%.

---

## 📂 Files to Modify

### Phase 1: Core Data Model

#### `/src/main/java/io/xdag/core/Account.java`

**Changes**:
```java
// BEFORE:
Bytes32 address;  // 32 bytes

// AFTER:
Bytes address;    // 20 bytes (hash160)
```

**Impact**:
- Constructor validation: ensure exactly 20 bytes
- Serialization: `toBytes()` and `fromBytes()` use 20 bytes
- Factory methods: `createEOA(Bytes)`, `createContract(Bytes, ...)`

**Lines to modify**: ~15 locations

---

### Phase 2: AccountStore Interface

#### `/src/main/java/io/xdag/db/AccountStore.java`

**Changes**:
```java
// BEFORE:
Optional<Account> getAccount(Bytes32 address);
boolean hasAccount(Bytes32 address);
UInt256 getBalance(Bytes32 address);
void setBalance(Bytes32 address, UInt256 balance);
UInt256 addBalance(Bytes32 address, UInt256 amount);
UInt256 subtractBalance(Bytes32 address, UInt256 amount);
UInt64 getNonce(Bytes32 address);
void setNonce(Bytes32 address, UInt64 nonce);
UInt64 incrementNonce(Bytes32 address);
Account createContractAccount(Bytes32 address, Bytes32 codeHash, Bytes32 storageRoot);

// AFTER:
Optional<Account> getAccount(Bytes address);
boolean hasAccount(Bytes address);
UInt256 getBalance(Bytes address);
void setBalance(Bytes address, UInt256 balance);
UInt256 addBalance(Bytes address, UInt256 amount);
UInt256 subtractBalance(Bytes address, UInt256 amount);
UInt64 getNonce(Bytes address);
void setNonce(Bytes address, UInt64 nonce);
UInt64 incrementNonce(Bytes address);
Account createContractAccount(Bytes address, Bytes32 codeHash, Bytes32 storageRoot);
```

**Impact**:
- All implementations must change signatures
- Javadoc updates: "32 bytes" → "20 bytes"

**Lines to modify**: ~30 locations (interface + comments)

---

### Phase 3: AccountStoreImpl

#### `/src/main/java/io/xdag/db/rocksdb/AccountStoreImpl.java`

**Changes**:

1. **Key generation** (Line 688-692):
```java
// BEFORE:
private byte[] makeAccountKey(Bytes32 address) {
    byte[] key = new byte[1 + address.size()];  // 1 + 32 = 33
    key[0] = PREFIX_ACCOUNT;
    System.arraycopy(address.toArray(), 0, key, 1, address.size());
    return key;
}

// AFTER:
private byte[] makeAccountKey(Bytes address) {
    if (address.size() != 20) {
        throw new IllegalArgumentException("Address must be 20 bytes, got: " + address.size());
    }
    byte[] key = new byte[1 + 20];  // 1 + 20 = 21
    key[0] = PREFIX_ACCOUNT;
    System.arraycopy(address.toArray(), 0, key, 1, 20);
    return key;
}
```

2. **Address extraction** (Line 627):
```java
// BEFORE:
byte[] addressBytes = Arrays.copyOfRange(key, 1, key.length);  // 32 bytes
addresses.add(Bytes32.wrap(addressBytes));

// AFTER:
byte[] addressBytes = Arrays.copyOfRange(key, 1, 21);  // 20 bytes
addresses.add(Bytes.wrap(addressBytes));
```

3. **All method signatures** - Change `Bytes32` → `Bytes`:
   - Line 265: `saveAccount(Account account)`
   - Line 299: `getAccount(Bytes address)`
   - Line 322: `hasAccount(Bytes address)`
   - Line 339: `deleteAccount(Bytes address)`
   - Line 371: `getBalance(Bytes address)`
   - Line 378: `setBalance(Bytes address, ...)`
   - Line 392: `addBalance(Bytes address, ...)`
   - Line 400: `subtractBalance(Bytes address, ...)`
   - Line 439: `getNonce(Bytes address)`
   - Line 446: `setNonce(Bytes address, ...)`
   - Line 460: `incrementNonce(Bytes address)`
   - Line 530: `createContractAccount(Bytes address, ...)`
   - Line 605: `getAllAddresses(int limit)` return type

**Lines to modify**: ~50 locations

---

### Phase 4: Transaction Model

#### `/src/main/java/io/xdag/core/Transaction.java`

**Changes**:
```java
// BEFORE:
Bytes32 from;
Bytes32 to;

// AFTER:
Bytes from;  // 20 bytes
Bytes to;    // 20 bytes
```

**Additional validation**:
```java
// In constructor/builder:
if (from != null && from.size() != 20) {
    throw new IllegalArgumentException("from address must be 20 bytes");
}
if (to != null && to.size() != 20) {
    throw new IllegalArgumentException("to address must be 20 bytes");
}
```

**Serialization changes**:
- `toBytes()`: Write 20-byte addresses
- `fromBytes()`: Read 20-byte addresses

**Lines to modify**: ~20 locations

---

### Phase 5: BasicUtils Refactoring

#### `/src/main/java/io/xdag/utils/BasicUtils.java`

**Changes**:

1. **DELETE `keyPair2Hash()` method** (Lines 151-156):
```java
// REMOVE THIS - no longer needed!
public static Bytes32 keyPair2Hash(ECKeyPair keyPair) {
    Bytes ret = Bytes.wrap(toBytesAddress(keyPair));
    MutableBytes32 res = MutableBytes32.create();
    res.set(8, ret);
    return res;
}
```

2. **UPDATE `pubAddress2Hash()`** (Lines 139-144):
```java
// BEFORE:
public static Bytes32 pubAddress2Hash(String address) throws AddressFormatException {
    Bytes ret = Bytes.wrap(WalletUtils.fromBase58(address));
    MutableBytes32 res = MutableBytes32.create();
    res.set(8, ret);
    return res;
}

// AFTER:
public static Bytes pubAddress2Bytes(String address) throws AddressFormatException {
    return Bytes.wrap(WalletUtils.fromBase58(address));
}
```

3. **UPDATE `hash2byte()` methods** (Lines 163-175):
```java
// BEFORE: Extract 20 bytes from padded 32 bytes
public static Bytes hash2byte(MutableBytes32 hash){
    return hash.slice(8,20);
}

// AFTER: Direct return (no padding)
// DELETE THIS - addresses are already 20 bytes!
```

**Key insight**: With native 20-byte addresses, we eliminate all padding/extraction logic!

**Lines to modify**: ~30 locations (mostly deletions)

---

### Phase 6: Commands.java Updates

#### `/src/main/java/io/xdag/cli/Commands.java`

**Changes - Replace all address handling**:

```java
// BEFORE:
Bytes32 addr = keyPair2Hash(keyPair);

// AFTER:
Bytes addr = toBytesAddress(keyPair);
```

**Locations** (7 places):
1. Line 155-157: `account()` method
2. Line 526-527: `balance()` method
3. Line 577-579: `txQuantity()` method
4. Line 732-733: `stats()` method
5. Line 1182-1183: `getBalanceMaxXfer()` method
6. Line 1354-1362: `transfer()` method
7. Line 1506-1507: `consolidate()` method

**Helper method updates**:
```java
// Lines 114-124: Update getAccountBalance()
private XAmount getAccountBalance(Bytes address) {  // Not Bytes32
    UInt256 balance = dagKernel.getAccountStore().getBalance(address);
    return uint256ToXAmount(balance);
}

// Lines 122-124: Update getAccountNonce()
private long getAccountNonce(Bytes address) {  // Not Bytes32
    return dagKernel.getAccountStore().getNonce(address).toLong();
}

// Lines 129-131: Update updateAccountNonce()
private void updateAccountNonce(Bytes address, long nonce) {  // Not Bytes32
    dagKernel.getAccountStore().setNonce(address, UInt64.valueOf(nonce));
}
```

**Lines to modify**: ~50 locations

---

### Phase 7: DagChain & Block Model

#### Files to check and possibly update:

1. **`/src/main/java/io/xdag/core/BlockHeader.java`**
   - `coinbase` field: Change to `Bytes` (20 bytes)

2. **`/src/main/java/io/xdag/core/Link.java`**
   - Check if any address fields need updating

3. **`/src/main/java/io/xdag/core/DagChain.java`** (interface)
   - Any address-based query methods

4. **`/src/main/java/io/xdag/consensus/DagChainImpl.java`**
   - Coinbase address handling
   - Reward distribution logic

**Estimated lines**: ~30 locations

---

### Phase 8: Test Updates

#### `/src/test/java/io/xdag/cli/ShellTest.java`

**Changes**:
```java
// BEFORE:
Bytes32 addr = keyPair2Hash(keyPair);
when(accountStore.getBalance(any(Bytes32.class))).thenReturn(...);

// AFTER:
Bytes addr = toBytesAddress(keyPair);
when(accountStore.getBalance(any(Bytes.class))).thenReturn(...);
```

**Mock setup**:
```java
// Update all Mockito matchers:
- any(Bytes32.class) → any(Bytes.class)
- eq(Bytes32) → eq(Bytes)
```

**Lines to modify**: ~40 locations

---

#### Other test files to update:

1. **`AccountStoreTest.java`** (if exists)
2. **`TransactionTest.java`**
3. **`BasicUtilsTest.java`**
4. **Integration tests**

---

### Phase 9: Compilation & Verification

#### Step-by-step verification:

1. **Compile core model**:
```bash
mvn compile -pl :xdagj-core
```

2. **Compile storage**:
```bash
mvn compile -pl :xdagj-db
```

3. **Compile business logic**:
```bash
mvn compile
```

4. **Run unit tests**:
```bash
mvn test -Dtest=ShellTest
mvn test -Dtest=AccountStoreTest
```

5. **Full test suite**:
```bash
mvn test
```

6. **Integration test**:
```bash
# Test real account creation and storage
mvn test -Dtest=*Integration*
```

---

## 🔧 Implementation Order

### Critical Path (Must do sequentially):

```
1. Account.java (data model)
   ↓
2. AccountStore.java (interface)
   ↓
3. AccountStoreImpl.java (implementation)
   ↓
4. Transaction.java (depends on address type)
   ↓
5. BasicUtils.java (utility functions)
   ↓
6. Commands.java (business logic)
   ↓
7. DagChain/Block (consensus)
   ↓
8. Tests (verification)
   ↓
9. Compile & verify
```

**Estimated time**: 4-6 hours for full refactor + testing

---

## ⚠️ Potential Issues & Solutions

### Issue 1: Address comparison

**Problem**: `Bytes32.equals()` vs `Bytes.equals()`

**Solution**: Both work the same way, no issues

---

### Issue 2: Serialization format changes

**Problem**: Existing test data may be in 32-byte format

**Solution**: Regenerate all test data with 20-byte addresses

---

### Issue 3: Hash functions expecting 32 bytes

**Problem**: Some hash functions may assume 32-byte input

**Solution**:
- Audit all hash function calls
- Most should work with variable-length `Bytes`
- For fixed-size hash functions, use explicit padding only where needed

---

### Issue 4: External API compatibility

**Problem**: REST API or RPC may expose addresses

**Solution**:
- APIs use Base58 encoding (20 bytes is standard)
- No breaking changes to external interfaces
- Only internal representation changes

---

## ✅ Success Criteria

1. ✅ **Compilation**: Zero compilation errors
2. ✅ **Tests**: All existing tests pass (100%)
3. ✅ **Storage**: AccountStore uses 21-byte keys (1 + 20)
4. ✅ **Account**: Account objects are 61 bytes (EOA) instead of 73 bytes
5. ✅ **Performance**: No performance regression
6. ✅ **Documentation**: All javadocs updated to reflect 20-byte addresses

---

## 📊 Before & After Comparison

### Account Storage Format

```
BEFORE (v1.0 current):
┌─────────────────────────────────────────────┐
│ RocksDB Key                                 │
├─────────────────────────────────────────────┤
│ PREFIX (1) │ ADDRESS (32 = 12 pad + 20)    │
└─────────────────────────────────────────────┘
             33 bytes

┌──────────────────────────────────────────────────────────┐
│ Account Value                                            │
├──────────────────────────────────────────────────────────┤
│ address(32) │ balance(32) │ nonce(8) │ has_code(1)      │
└──────────────────────────────────────────────────────────┘
             73 bytes (EOA)

AFTER (refactored):
┌──────────────────────────────┐
│ RocksDB Key                  │
├──────────────────────────────┤
│ PREFIX (1) │ ADDRESS (20)   │
└──────────────────────────────┘
             21 bytes (-36%)

┌──────────────────────────────────────────────────────────┐
│ Account Value                                            │
├──────────────────────────────────────────────────────────┤
│ address(20) │ balance(32) │ nonce(8) │ has_code(1)      │
└──────────────────────────────────────────────────────────┘
             61 bytes (EOA) (-16%)
```

**Total savings per account**: 24 bytes (12 in key + 12 in value)

---

## 📝 Notes

- This refactoring is **safe** because we're still in development phase
- No migration tools needed (fresh start)
- Will significantly improve long-term scalability
- Aligns with Ethereum and Bitcoin address standards (20 bytes)

---

**Status**: Ready for implementation
**Next Step**: Begin Phase 1 - Account.java refactoring

