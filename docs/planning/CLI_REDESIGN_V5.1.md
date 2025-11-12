# Shell CLI Commands Redesign for v5.1

**Date**: 2025-11-11
**Purpose**: Redesign Shell CLI commands to align with v5.1 architecture
**Status**: Design Phase

---

## 📊 Current Command Analysis

### Existing Commands (20 total)

| Command | Current Status | v5.1 Compatibility | Action Required |
|---------|---------------|-------------------|-----------------|
| `account` | ✅ Working | ⚠️ Needs Update | Update output format |
| `balance` | ✅ Working | ⚠️ Needs Update | Use AccountStore |
| `block` | ✅ Working | ❌ Broken | Complete redesign |
| `lastblocks` | ⚠️ Partial | ❌ Broken | Needs implementation |
| `mainblocks` | ✅ Working | ⚠️ Needs Update | Use height-based queries |
| `minedblocks` | ✅ Working | ❌ Broken | Redesign for v5.1 |
| `state` | ✅ Working | ✅ Compatible | Minor updates |
| `stats` | ✅ Working | ⚠️ Needs Update | Use ChainStats |
| `xfer` | ✅ Working | ✅ Compatible | Already uses v5.1 |
| `xfertonew` | ✅ Working | ✅ Compatible | Already uses v5.1 |
| `xferv2` | ✅ Working | ✅ Compatible | v5.1 native |
| `xfertonewv2` | ✅ Working | ✅ Compatible | v5.1 native |
| `pool` | ✅ Working | ✅ Compatible | OK |
| `keygen` | ✅ Working | ✅ Compatible | OK |
| `net` | ✅ Working | ✅ Compatible | OK |
| `ttop` | ✅ Working | ✅ Compatible | OK |
| `terminate` | ✅ Working | ✅ Compatible | OK |
| `address` | ✅ Working | ⚠️ Needs Update | Use AccountStore |
| `oldbalance` | ✅ Working | ⚠️ Needs Update | Update logic |
| `txQuantity` | ✅ Working | ⚠️ Needs Update | Use nonce tracking |

**Summary**:
- ✅ Compatible: 9 commands (45%)
- ⚠️ Needs Update: 8 commands (40%)
- ❌ Broken/Missing: 3 commands (15%)

---

## 🎯 Design Goals

### 1. Align with v5.1 Architecture
- Use `DagChain` interface (not internal data structures)
- Leverage `ChainStats`, `BlockInfo`, `EpochStats`
- Work with height-based main chain
- Support epoch-based queries

### 2. Improve User Experience
- Clear, consistent output format
- Helpful error messages
- Support for both address and hash inputs
- Pagination for large results

### 3. Performance
- Efficient queries (avoid full scans)
- Use indexed lookups
- Cache frequently accessed data

### 4. Future-Proof
- Extensible command structure
- Version-aware (support v4/v5 migration)
- Easy to add new commands

---

## 📋 Command Redesign Plan

### Priority 1: Critical Commands (Must Fix)

#### 1. `block` Command
**Current Issues**:
- Uses legacy block structure
- Doesn't show v5.1 fields (BlockInfo, Links, Transactions)

**New Design**:
```
block <address|hash>

Output:
═══════════════════════════════════════════════════
Block Information (v5.1)
═══════════════════════════════════════════════════
Hash:            0x1234abcd...
Timestamp:       2025-11-11 18:30:45 UTC
Epoch:           23693854
Height:          12345 (Main Chain)
State:           MAIN | ORPHAN | PENDING

Block Details:
  Difficulty:    1.234567e15
  Coinbase:      2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
  Size:          2.5 MB / 48 MB (5.2%)

Consensus:
  Cumulative Difficulty: 9.876543e18
  Epoch Winner:          Yes | No
  Epoch Candidates:      5 blocks

Links (Block References):
  → 0xabcd1234... (height: 12344, epoch: 23693853)
  → 0xef567890... (height: 12343, epoch: 23693852)
  [Total: 5 block links]

Transactions:
  ✓ 0xtx1234... (sender: 2gH..., amount: 10.5 XDAG, fee: 0.1)
  ✓ 0xtx5678... (sender: 3kL..., amount: 5.25 XDAG, fee: 0.1)
  [Total: 125 transactions, 1,234.56 XDAG transferred]

Mining:
  Miner:         2mP... (Pool ABC)
  Reward:        1024.0 XDAG
  Shares:        Foundation 5%, Pool 90%, Node 5%
```

**Implementation**:
```java
private void processBlock(CommandInput input) {
    // Parse hash/address
    Bytes32 hash = parseHashOrAddress(input);

    // Query from DagChain
    Block block = dagChain.getBlockByHash(hash, false);
    if (block == null) {
        println("Block not found");
        return;
    }

    // Format output using v5.1 data
    println(formatBlockV5(block));
}
```

---

#### 2. `lastblocks` / `mainblocks` Commands
**Current Issues**:
- `lastblocks` is not implemented
- `mainblocks` needs height-based queries

**New Design**:
```
mainblocks [size] [offset]

Examples:
  mainblocks           # Show last 20 main blocks
  mainblocks 50        # Show last 50 main blocks
  mainblocks 20 100    # Show 20 blocks starting from height (current - 100)

Output:
Main Chain Blocks (Latest 20)
═══════════════════════════════════════════════════════════
Height  Epoch      Hash          Time        Difficulty  Txs
═══════════════════════════════════════════════════════════
12345   23693854   0x1234abcd... 18:30:45   1.23e15    125
12344   23693853   0xabcd1234... 18:29:41   1.22e15     98
12343   23693852   0xef567890... 18:28:37   1.21e15    103
...
```

**Implementation**:
```java
private void processMainBlocks(CommandInput input) {
    int size = parseSize(input, DEFAULT_LIST_NUM);
    int offset = parseOffset(input, 0);

    long currentHeight = dagChain.getMainChainLength();
    long startHeight = Math.max(1, currentHeight - offset);
    long endHeight = Math.max(1, startHeight - size + 1);

    println(formatMainBlocks(startHeight, endHeight));
}
```

---

#### 3. `minedblocks` Command
**Current Issues**:
- Uses legacy "our blocks" concept
- Doesn't work with v5.1 mining

**New Design**:
```
minedblocks [size]

Output:
Blocks Mined by This Node (Latest 20)
═══════════════════════════════════════════════════════════
Height  Epoch      Hash          Time        Reward    Pool
═══════════════════════════════════════════════════════════
12340   23693849   0x9876fedc... 18:25:30   1024.0   ABC
12330   23693839   0x5432abef... 18:15:20   1024.0   ABC
...

Total Mined: 1,234 blocks
Total Rewards: 1,234,567.89 XDAG
```

**Implementation**:
```java
private void processMinedblocks(CommandInput input) {
    int size = parseSize(input, DEFAULT_LIST_NUM);

    // Query from DagChain
    List<Block> minedBlocks = dagChain.listMinedBlocks(size);

    println(formatMinedBlocks(minedBlocks));
}
```

---

### Priority 2: Important Updates

#### 4. `stats` Command
**Current Issues**:
- Doesn't show v5.1 ChainStats
- Missing epoch information

**New Design**:
```
stats

Output:
XDAG Network Statistics (v5.1)
═══════════════════════════════════════════════════════════

Chain Status:
  Main Chain Length:      12,345 blocks
  Current Epoch:          23,693,854
  Max Difficulty:         9.876543e18
  Top Block Hash:         0x1234abcd...
  Top Block Height:       12,345

Network:
  Connected Peers:        42
  Total Blocks:           15,678 (12,345 main + 3,333 orphan)
  Blocks/sec:             2.5
  Sync Progress:          100%

Consensus:
  Finality Window:        16,384 epochs (≈12 days)
  Max Reorg Depth:        32,768 epochs (≈24 days)
  Current Winner:         0xabcd... (epoch 23,693,854)

Mining (if enabled):
  Mining Address:         2gH...
  Hash Rate:              123.4 MH/s
  Blocks Mined:           1,234
  Total Rewards:          1,234,567.89 XDAG

Storage:
  Database Size:          12.3 GB
  Orphan Queue:           45 blocks
  Pending Txs:            12 transactions
```

**Implementation**:
```java
private void processStats(CommandInput input) {
    ChainStats chainStats = dagChain.getChainStats();
    long currentEpoch = dagChain.getCurrentEpoch();
    EpochStats epochStats = dagChain.getEpochStats(currentEpoch);

    println(formatStatsV5(chainStats, epochStats));
}
```

---

#### 5. `balance` Command
**Current Issues**:
- Doesn't use AccountStore
- Output format inconsistent

**New Design**:
```
balance [address]

# Show all accounts
balance

Output:
Wallet Balance Summary
═══════════════════════════════════════════════════════════
Address                                          Balance
═══════════════════════════════════════════════════════════
2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM  1,234.56
3kLmNo8pQRs2tUvWxYz9ABCDEFGHIJKLMNOPQRSTUVWX  5,678.90
...

Total Balance: 6,913.46 XDAG

# Show specific address
balance 2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM

Output:
Account Balance (v5.1)
═══════════════════════════════════════════════════════════
Address:         2gHjwW7kNTj8VTg7yoS5fMT1APU7gGFSXm8jFL9qLMNYSZPM
Balance:         1,234.56 XDAG
Nonce:           42
Last Activity:   2025-11-11 18:30:45 UTC

Transaction History:
  Sent:          456.78 XDAG (123 transactions)
  Received:      1,691.34 XDAG (234 transactions)
```

**Implementation**:
```java
private void processBalance(CommandInput input) {
    String address = parseAddress(input);

    if (address == null) {
        // Show all accounts
        List<Account> accounts = accountStore.getAccounts();
        println(formatAllBalances(accounts));
    } else {
        // Show specific account
        Account account = accountStore.getAccountByAddress(address);
        println(formatAccountBalance(account));
    }
}
```

---

### Priority 3: New Commands (Nice to Have)

#### 6. `epoch` Command (NEW)
**Purpose**: Query epoch information

**Design**:
```
epoch [epoch_number]

# Show current epoch
epoch

Output:
Current Epoch Information
═══════════════════════════════════════════════════════════
Epoch Number:    23,693,854
Time Range:      2025-11-11 18:29:12 - 18:30:16 (64s)
Elapsed:         32s / 64s (50%)
Next Epoch:      In 32 seconds

Blocks in Epoch:
  Total Candidates:  5 blocks
  Winner:            0x1234abcd... (height: 12,345)
  Orphans:           4 blocks

Consensus:
  Winner Selection:  Smallest hash wins
  Average Difficulty: 1.23e15
  Total Difficulty:  6.15e15

# Show specific epoch
epoch 23693850

Output:
Epoch 23,693,850 (Confirmed)
═══════════════════════════════════════════════════════════
Time Range:      2025-11-11 18:24:48 - 18:25:52
Duration:        64 seconds
Blocks:          3 candidates
Winner:          0xabcd1234... (height: 12,341)
```

**Implementation**:
```java
private void processEpoch(CommandInput input) {
    long epoch = parseEpoch(input, dagChain.getCurrentEpoch());

    EpochStats stats = dagChain.getEpochStats(epoch);
    println(formatEpochStats(stats));
}
```

---

#### 7. `chain` Command (NEW)
**Purpose**: Show main chain path and structure

**Design**:
```
chain [start_height] [count]

# Show tip of main chain
chain

Output:
Main Chain Structure (Last 10 Blocks)
═══════════════════════════════════════════════════════════
12345 ← 12344 ← 12343 ← 12342 ← 12341 ← 12340 ← ...
  │      │      │      │      │      │
Epoch: 23693854 → 53 → 52 → 51 → 50 → 49 → ...

12345: 0x1234... [125 txs, 1.23e15 diff]
12344: 0xabcd... [98 txs, 1.22e15 diff]
...

# Show specific range
chain 12300 20

Output shows 20 blocks starting from height 12300
```

---

#### 8. `verify` Command (NEW)
**Purpose**: Verify blockchain integrity

**Design**:
```
verify [--full]

Output:
Blockchain Verification
═══════════════════════════════════════════════════════════
[✓] Genesis block valid
[✓] Main chain continuous (heights 1-12,345)
[✓] All blocks have valid PoW
[✓] Cumulative difficulty consistent
[✓] No orphan blocks on main chain
[✓] Epoch boundaries correct
[✓] Database integrity OK

Verification completed in 2.3 seconds
Result: ✅ PASSED
```

---

## 🛠️ Implementation Plan

### Phase 1: Fix Critical Commands (1-2 days)
1. ✅ Update `block` command
2. ✅ Fix `lastblocks` / `mainblocks`
3. ✅ Redesign `minedblocks`

### Phase 2: Important Updates (1 day)
4. ✅ Update `stats` command
5. ✅ Update `balance` command
6. ✅ Update `account` command
7. ✅ Update `address` command

### Phase 3: New Commands (1-2 days)
8. ✅ Implement `epoch` command
9. ✅ Implement `chain` command
10. ✅ Implement `verify` command

### Phase 4: Testing & Documentation (1 day)
11. ✅ Test all commands
12. ✅ Update user documentation
13. ✅ Create command reference guide

---

## 📝 Code Structure Improvements

### 1. Extract Formatting Logic
Create `ShellFormatters.java`:
```java
public class ShellFormatters {
    public static String formatBlockV5(Block block) { ... }
    public static String formatMainBlocks(List<Block> blocks) { ... }
    public static String formatChainStats(ChainStats stats) { ... }
    public static String formatEpochStats(EpochStats stats) { ... }
}
```

### 2. Extract Parsing Logic
Create `ShellParsers.java`:
```java
public class ShellParsers {
    public static Bytes32 parseHashOrAddress(String input) { ... }
    public static long parseHeight(String input) { ... }
    public static long parseEpoch(String input) { ... }
}
```

### 3. Unified Error Handling
```java
private <T> T executeCommand(Supplier<T> command, String errorMsg) {
    try {
        return command.get();
    } catch (Exception e) {
        log.error(errorMsg, e);
        println("Error: " + errorMsg + " - " + e.getMessage());
        return null;
    }
}
```

---

## 📚 Documentation Updates

### User Guide: `docs/guides/cli-commands.md`
- Complete command reference
- Examples for each command
- Troubleshooting tips

### Design Doc: `docs/design/CLI_V5.1_DESIGN.md`
- Architecture decisions
- Command patterns
- Extension guide

---

## ✅ Success Criteria

1. **All commands work with v5.1 data structures**
   - No direct access to internal fields
   - Use DagChain/AccountStore interfaces

2. **Consistent output format**
   - Professional formatting
   - Easy to read and parse

3. **Performance**
   - All commands respond in < 1 second (typical)
   - Large queries use pagination

4. **User Experience**
   - Clear error messages
   - Helpful usage information
   - Support for both addresses and hashes

---

**Status**: Ready for Implementation
**Priority**: HIGH
**Estimated Effort**: 5-6 days
**Dependency**: v5.1 core implementation complete ✅
