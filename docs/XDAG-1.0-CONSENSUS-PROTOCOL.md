# XDAG 1.0 Consensus Protocol

**Version**: 1.0
**Status**: Draft
**Last Updated**: 2025-11-21

## Abstract

XDAG 1.0 is an epoch-based Directed Acyclic Graph (DAG) blockchain that combines Proof-of-Work (PoW) consensus with a unique block selection mechanism. Unlike traditional blockchains where blocks form a linear chain, XDAG allows multiple blocks to compete within the same time window (epoch), with the winner determined by hash value. This design enables high transaction throughput while maintaining decentralization and security.

---

## 1. Core Concepts

### 1.1 Epoch

An **epoch** is a fixed 64-second time window that serves as the fundamental unit of time in XDAG.

- **Duration**: 64 seconds
- **Epoch Number**: `timestamp / 64` (integer division)
- **Purpose**: Groups blocks into time-based competition rounds
- **Genesis Epoch**: Defined by network configuration (mainnet/testnet/devnet)

### 1.2 Block Types

All blocks in XDAG 1.0 are **candidate blocks** that participate in epoch competition:

- **Candidate Block**: Any block mined within an epoch
- **Main Block (Winner)**: The candidate with the smallest hash in an epoch
- **Orphan Block (Loser)**: All other candidates in the epoch
- **Genesis Block**: The first block (epoch 0, difficulty = 1)

**Important**: There is no structural difference between block types. The distinction is determined by consensus rules during block processing.

### 1.3 Block Structure

#### Header (Fixed Size)
- **Epoch Number**: 8 bytes (which 64-second window)
- **Difficulty Target**: 32 bytes (PoW validation threshold)
- **Nonce**: 32 bytes (PoW solution)
- **Coinbase Address**: 20 bytes (mining reward recipient)
- **Hash**: 32 bytes (cached block hash)

#### Links (Variable Size)
A block contains **references** to other blocks and transactions:

- **Block Links**: References to previous epoch's candidate blocks
  - **Maximum**: 16 block references (`MAX_BLOCK_LINKS`)
  - **Purpose**: Connect to previous epoch's top candidates

- **Transaction Links**: References to pending transactions
  - **Maximum**: 1,484,984 transaction references
  - **Current Limit**: 1,024 transactions (conservative initial deployment)
  - **Purpose**: Include user transactions in the blockchain

**Total Link Capacity**: Up to 1,485,000 links per block (`MAX_LINKS_PER_BLOCK`)

#### Block Metadata (Not Serialized)
- **Height**: Sequential number in main chain
- **Cumulative Difficulty**: Sum of all difficulties from genesis
- **Status**: MAIN, ORPHAN, or PENDING

---

## 2. Proof-of-Work (PoW)

### 2.1 Hash Calculation

Block hash is computed as:
```
hash = Keccak256(epoch || difficulty || nonce || coinbase || links_count || link[0] || link[1] || ...)
```

Where:
- `epoch`: 8 bytes
- `difficulty`: 32 bytes
- `nonce`: 32 bytes
- `coinbase`: 20 bytes
- `links_count`: 4 bytes (number of links)
- Each `link[i]`: 33 bytes (type + hash)

### 2.2 PoW Validation

A block is considered valid if:
```
hash ≤ baseDifficultyTarget
```

Where:
- `hash`: Block's Keccak256 hash (interpreted as UInt256)
- `baseDifficultyTarget`: Network-wide difficulty threshold
- Smaller hash = more computational work

### 2.3 Initial Difficulty Targets

**Mainnet/Testnet**:
```
baseDifficultyTarget = 2^192
```
- Requires approximately 8 zero bytes in hash
- Average mining time: ~5 hours per block with 1 GH/s
- Network effect (1000 miners @ 10 GH/s): ~346 blocks/epoch

**Devnet**:
```
baseDifficultyTarget = 2^256 - 1 (MAX)
```
- Accepts any block (no PoW required)
- Used for development and testing

---

## 3. Epoch-Based Consensus

### 3.1 Within-Epoch Competition

**Multiple Candidates per Epoch**:
- Any number of miners can submit candidate blocks in the same epoch
- All candidates with `hash ≤ baseDifficultyTarget` are valid
- Candidates compete for the winner position

**Winner Selection**:
```
Winner = candidate with minimum(hash) in the epoch
```

**Example**:
```
Epoch 1000:
  - Block A: hash = 0x0000012345... (winner - smallest hash)
  - Block B: hash = 0x0000056789... (orphan)
  - Block C: hash = 0x0000098765... (orphan)
```

### 3.2 Main Chain Selection

The **main chain** is determined by cumulative difficulty:

```
Main Chain = chain with maximum(cumulative_difficulty)
```

Where:
- `cumulative_difficulty` = sum of all main block difficulties from genesis
- Each main block adds its `difficulty` to the cumulative total
- Follows "heaviest chain wins" principle (similar to Bitcoin)

**Difficulty Calculation**:
```
block_difficulty = MAX_UINT256 / hash
```
- Smaller hash → larger difficulty
- Encourages miners to find smaller hashes

### 3.3 Height Assignment

Main blocks receive sequential heights:
- Genesis block: height = 1
- Next main block: height = previous_height + 1
- Orphan blocks: height = 0 (not part of main chain)

**Example**:
```
Epoch    Main Block    Height
-----------------------------
0        Genesis       1
1        Block A1      2
2        Block B1      3
3        (no winner)   -
4        Block D1      4
```

---

## 4. Mining Strategy

### 4.1 Reference Selection

When creating a new candidate block, miners must reference:

**1. Previous Epoch's Top 16 Candidates** (`MAX_BLOCK_LINKS`):
- Get the previous main block's epoch
- Collect all candidates in that epoch
- Sort by work (difficulty) in descending order
- Select top 16 candidates
- Add 16 block links to the new candidate

**Rationale**:
- Ensures connection to previous epoch
- Gives orphan blocks a chance to be referenced
- Prevents "orphan accumulation" problem
- Maintains DAG structure integrity

**2. Pending Transactions from Pool** (up to 1,024):
- Query transaction pool for pending transactions
- Sort by transaction fee (highest first)
- Select up to 1,024 transactions
- Add transaction links to the candidate

**Total Links**: 16 block links + up to 1,024 transaction links = 1,040 links per block

### 4.2 Mining Restrictions

**Reference Depth Limit**:
```
current_epoch - previous_main_block_epoch ≤ MINING_MAX_REFERENCE_DEPTH (16 epochs)
```

**Purpose**: Prevent outdated nodes from mining blocks with stale references

**Enforcement**:
- If node is more than 16 epochs behind: mining is blocked
- Node must sync to latest epoch before mining can resume
- Exception: Devnet (allows any reference depth for testing)

### 4.3 Orphan Block Retention

**Retention Window**: 16,384 epochs (~12 days)

**Rationale**:
- XDAG protocol allows referencing blocks within 12-day window
- Older orphan blocks cannot become main blocks
- Periodic cleanup prevents unbounded storage growth

**Cleanup Policy**:
- Runs every 100 epochs (`ORPHAN_CLEANUP_INTERVAL`)
- Deletes orphan blocks older than 16,384 epochs
- Main blocks are never deleted

---

## 5. Difficulty Adjustment

### 5.1 Goals

Maintain optimal block production rate:
- **Target**: 150 qualifying blocks per epoch
- **Accept**: Top 100 blocks per epoch (to manage storage)
- **Effect**: Controls orphan block growth

### 5.2 Adjustment Algorithm

**Triggers**:
- Every 1,000 epochs (`DIFFICULTY_ADJUSTMENT_INTERVAL`)
- Recalculates `baseDifficultyTarget` based on recent block counts

**Formula** (simplified):
```
If avg_blocks_per_epoch > TARGET_BLOCKS_PER_EPOCH:
    baseDifficultyTarget = baseDifficultyTarget * 0.9  (harder, 10% decrease)
Else if avg_blocks_per_epoch < TARGET_BLOCKS_PER_EPOCH * 0.8:
    baseDifficultyTarget = baseDifficultyTarget * 1.1  (easier, 10% increase)
```

**Constraints**:
- Maximum adjustment: ±10% per interval
- Minimum target: INITIAL_BASE_DIFFICULTY_TARGET
- Maximum target: 2^256 - 1 (effectively no difficulty)

### 5.3 Network Hashrate Adaptation

**Low Hashrate** (few miners):
- Fewer blocks produced per epoch
- Difficulty adjusts down automatically
- Maintains blockchain progress

**High Hashrate** (many miners):
- Many blocks produced per epoch
- Difficulty adjusts up automatically
- Prevents excessive orphan accumulation

---

## 6. Transaction Processing

### 6.1 Transaction Lifecycle

1. **Submission**: User creates and signs transaction
2. **Validation**: Transaction pool validates signature, balance, nonce
3. **Pending**: Transaction enters pool, waiting for inclusion
4. **Selection**: Miner selects high-fee transactions for block
5. **Inclusion**: Transaction link added to mined block
6. **Execution**: When block becomes main block, transaction executes
7. **Finalization**: Transaction effects are permanent after execution

### 6.2 Transaction Pool

**Selection Strategy**:
- Sort pending transactions by fee (descending)
- Sort by nonce (ascending) for same account
- Sort by timestamp (oldest first) as tiebreaker

**Limits**:
- Maximum pool size: configurable (default: 10,000 transactions)
- Per-block limit: 1,024 transactions (initial conservative limit)
- Future scaling: up to 1,484,984 transactions per block

### 6.3 Transaction Fees

**Fee Calculation**:
```
fee = gasPrice * gasUsed
```

**Miner Rewards**:
- Block reward: Fixed per block (halves every ~4 years)
- Transaction fees: Sum of all transaction fees in block
- Total reward: block_reward + sum(transaction_fees)

---

## 7. Network Synchronization

### 7.1 Sync Strategies

**Full Sync**:
- Download all main blocks from genesis
- Validate each block's PoW and links
- Execute all transactions
- Build complete account state

**Fast Sync** (future):
- Download recent state snapshot
- Sync only recent blocks
- Faster initial sync for new nodes

### 7.2 Block Propagation

**New Block Message**:
- When miner finds valid block, broadcast to network
- Peers validate and relay to their peers
- Block propagates to entire network within seconds

**Block Validation**:
1. Check PoW: `hash ≤ baseDifficultyTarget`
2. Verify block structure and limits
3. Validate all block links exist
4. Check epoch correctness
5. Verify signature and nonce

### 7.3 Fork Resolution

**Scenario**: Two chains with different histories

**Resolution**:
```
Select chain with maximum cumulative difficulty
```

**Process**:
1. Node receives competing blocks
2. Calculate cumulative difficulty for each chain
3. Switch to heavier chain if necessary
4. Orphan blocks from abandoned chain
5. Re-execute transactions from new main chain

---

## 8. Economic Model

### 8.1 Block Rewards

**Initial Reward**: Network-dependent (configured in genesis)

**Halving Schedule**:
- Halves every ~4 years
- Based on block count, not time
- Ensures predictable supply schedule

### 8.2 Supply Dynamics

**Total Supply**:
- Defined by genesis configuration
- Approaches asymptotic limit due to halving
- No hard cap (tail emission possible)

**Inflation Rate**:
- Decreases over time due to halving
- Transaction fees become primary miner incentive
- Transition from inflationary to fee-driven security

---

## 9. Security Properties

### 9.1 51% Attack Resistance

**Attack Vector**: Majority hashrate rewrites history

**Defense**:
- Cumulative difficulty makes history rewrite expensive
- Deeper blocks require exponentially more work
- Checkpoints can be added for finality

**Cost**:
```
Cost to rewrite N epochs = network_hashrate * N * 64 seconds
```

### 9.2 Double-Spend Prevention

**Attack Vector**: Spend same coins twice on different chains

**Defense**:
- Transactions execute only on main chain
- Fork resolution based on cumulative difficulty
- Merchants should wait for confirmations

**Recommended Confirmations**:
- Small transactions: 3-6 epochs (~3-6 minutes)
- Large transactions: 20-30 epochs (~20-30 minutes)
- Critical transactions: 100+ epochs (~100+ minutes)

### 9.3 Orphan Flooding

**Attack Vector**: Flood network with low-quality orphan blocks

**Defense**:
- PoW requirement (`baseDifficultyTarget`) limits block production
- Orphan retention window limits storage impact
- Periodic cleanup removes old orphans
- Only top 16 candidates referenced per epoch

---

## 10. Performance Characteristics

### 10.1 Current Throughput

**Block Production**:
- Epoch duration: 64 seconds
- Blocks per epoch: ~150 (target), ~100 (accepted)
- Block rate: ~1.56 blocks/second

**Transaction Throughput**:
- Transactions per block: 1,024 (current limit)
- Blocks per epoch: 1 main block
- **Current TPS**: 1,024 / 64 = **16 TPS**

### 10.2 Future Scaling

**Maximum Capacity**:
- Links per block: 1,485,000 (`MAX_LINKS_PER_BLOCK`)
- Transactions per block: 1,484,984 (after 16 block links)
- **Theoretical TPS**: 1,484,984 / 64 = **23,203 TPS**
- **Comparison**: 96.7% of Visa's peak capacity (~24,000 TPS)

**Scaling Path**:
1. Phase 1: 1,024 txs/block (16 TPS) - current
2. Phase 2: 10,000 txs/block (156 TPS) - medium-term
3. Phase 3: 100,000 txs/block (1,562 TPS) - long-term
4. Phase 4: 1,484,984 txs/block (23,203 TPS) - ultimate capacity

### 10.3 Storage Requirements

**Block Size**:
- Header: ~124 bytes
- Links: 33 bytes per link
- 1,040 links: ~34 KB per block
- Full capacity (1.485M links): ~48 MB per block

**Chain Growth** (current limit):
- Blocks per day: ~1,350 (1 per epoch)
- Data per day: ~46 MB (34 KB * 1,350)
- Annual growth: ~16.5 GB/year

**Chain Growth** (full capacity):
- Blocks per day: ~1,350
- Data per day: ~64 GB (48 MB * 1,350)
- Annual growth: ~23 TB/year

---

## 11. Comparison with Other Protocols

### 11.1 vs Bitcoin

| Feature | Bitcoin | XDAG 1.0 |
|---------|---------|----------|
| Block Time | 10 minutes | 64 seconds (epoch) |
| TPS | ~7 | 16 (current), 23,203 (max) |
| Finality | ~60 minutes | ~6 minutes (6 epochs) |
| Structure | Linear chain | DAG with epoch competition |
| Orphans | Wasted work | Preserved and referenced |

### 11.2 vs Ethereum

| Feature | Ethereum | XDAG 1.0 |
|---------|----------|----------|
| Block Time | ~12 seconds | 64 seconds (epoch) |
| TPS | ~15-30 | 16 (current), 23,203 (max) |
| Consensus | PoS (post-merge) | PoW + epoch competition |
| Smart Contracts | Full VM | Account-based transfers |
| Uncle Blocks | Rewarded but not included | Orphans preserved, can be referenced |

### 11.3 vs IOTA

| Feature | IOTA | XDAG 1.0 |
|---------|------|----------|
| Structure | Tangle (DAG) | Epoch-based DAG |
| Consensus | Coordinator / Consensus Protocol | PoW + heaviest chain |
| Mining | No mining | PoW mining with rewards |
| Orphans | No concept | Explicit orphan management |
| Finality | Probabilistic | Cumulative difficulty based |

---

## 12. Network Parameters

### 12.1 Mainnet

```
- Genesis Epoch: TBD (mainnet launch)
- Epoch Duration: 64 seconds
- Initial Block Reward: TBD
- Halving Interval: TBD blocks (~4 years)
- Difficulty Target: 2^192
- Max Block Links: 16
- Max TX Links: 1,024 (current), 1,484,984 (capacity)
- Mining Reference Depth: 16 epochs
- Orphan Retention: 16,384 epochs (~12 days)
```

### 12.2 Testnet

```
- Genesis Epoch: TBD
- Epoch Duration: 64 seconds
- Initial Block Reward: TBD (same as mainnet)
- Difficulty Target: 2^192 (or lower for testing)
- Other parameters: Same as mainnet
```

### 12.3 Devnet

```
- Genesis Epoch: Configurable
- Epoch Duration: 64 seconds
- Initial Block Reward: Configurable
- Difficulty Target: 2^256 - 1 (no PoW)
- Mining Reference Depth: Unlimited
- Other parameters: Same as mainnet
```

---

## 13. Future Improvements

### 13.1 Transaction Throughput

**Gradual Increase**:
- Monitor network stability and storage growth
- Increase MAX_TX_LINKS_PER_BLOCK gradually
- Path: 1K → 10K → 100K → 1M+ transactions per block

**Benefits**:
- Higher TPS without protocol changes
- Competitive with centralized payment systems
- Maintains decentralization

### 13.2 Fast Sync

**Snapshot-Based Sync**:
- Periodic state snapshots
- New nodes download recent snapshot + recent blocks
- Reduces initial sync time from days to hours

### 13.3 Layer 2 Solutions

**Payment Channels**:
- Off-chain micropayments
- Periodic settlement on main chain
- Instant finality for channel participants

**State Channels**:
- Off-chain state updates
- Dispute resolution on main chain
- Application-specific logic

---

## 14. Conclusion

XDAG 1.0 presents a unique consensus mechanism that combines:

1. **Epoch-based competition**: Multiple miners can succeed in the same time window
2. **DAG structure**: Orphan blocks are preserved and can be referenced
3. **PoW security**: Work-based chain selection prevents history rewriting
4. **High throughput**: Capable of 23,203 TPS with full link capacity
5. **Fair mining**: All valid blocks contribute to network security

The protocol balances decentralization, security, and performance while maintaining a simple and elegant design. Its epoch-based competition reduces orphan waste compared to traditional blockchains, and its massive link capacity enables Visa-level transaction throughput without sacrificing the security properties of Proof-of-Work consensus.

---

## Appendix A: Terminology

- **Epoch**: 64-second time window for block competition
- **Candidate Block**: Any block mined in an epoch
- **Main Block**: Winner of epoch competition (smallest hash)
- **Orphan Block**: Non-winning candidate block
- **Height**: Sequential position in main chain
- **Cumulative Difficulty**: Sum of difficulties from genesis
- **Link**: Reference to another block or transaction
- **PoW**: Proof-of-Work (hash ≤ difficulty target)
- **TPS**: Transactions Per Second

## Appendix B: Key Constants

```
EPOCH_DURATION = 64 seconds
MAX_BLOCK_LINKS = 16 (references to previous epoch blocks)
MAX_LINKS_PER_BLOCK = 1,485,000 (total capacity)
MAX_TX_LINKS_PER_BLOCK = 1,024 (current conservative limit)
INITIAL_BASE_DIFFICULTY_TARGET = 2^192 (mainnet/testnet)
DEVNET_DIFFICULTY_TARGET = 2^256 - 1 (no PoW)
MINING_MAX_REFERENCE_DEPTH = 16 epochs (~17 minutes)
ORPHAN_RETENTION_WINDOW = 16,384 epochs (~12 days)
TARGET_BLOCKS_PER_EPOCH = 150
MAX_BLOCKS_PER_EPOCH = 100
DIFFICULTY_ADJUSTMENT_INTERVAL = 1,000 epochs
ORPHAN_CLEANUP_INTERVAL = 100 epochs
```

---

**Document Status**: Draft - Subject to updates based on implementation and testing

**Feedback**: Issues and suggestions can be submitted via GitHub
