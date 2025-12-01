# Optimization Plan & Technical Debt (XDAGJ 1.0 - Clean Slate Design)

This document tracks identified optimization opportunities, technical debt, and feature enhancements. 
Since XDAGJ 1.0 is a clean-slate design, solutions prioritize **optimal architecture** over backward compatibility.

## Priority Levels
- **P0 (Critical)**: Immediate attention required. Affects core stability or security.
- **P1 (High)**: Major improvement needed. Significantly affects performance or UX.
- **P2 (Medium)**: Good to have. Improves efficiency or maintainability.
- **P3 (Low)**: Minor cleanup or cosmetic improvements.
- **R&I (Research)**: Long-term architectural evolution and innovation.

## Optimization Backlog

| ID | Priority | Title | Status |
|----|----------|-------|--------|
| OPT-001 | P1 | Active Transaction Fetching for Pending Blocks | Pending |
| OPT-002 | P1 | Transaction-Triggered Orphan Retry | Pending |
| OPT-003 | P2 | Efficient Transaction Broadcasting (Gossip/Inv) | Pending |
| OPT-004 | P3 | Block Broadcasting Optimization (Observation) | Pending |
| OPT-005 | P3 | Randomized Peer Broadcasting Order | Pending |
| OPT-006 | P0 | Implement Missing Transaction Sync in SyncManager | Pending |
| OPT-007 | P1 | Protocol-Level Snapshot Verification (Archive Root) | Pending |
| OPT-008 | P1 | Orphan Transaction Pruning (Storage Leak Fix) | Pending |
| OPT-009 | P0 | EVM Integration & The Three Roots Architecture | Pending |
| OPT-010 | P1 | Stratum V1 Server Integration (Mining Architecture) | Pending |
| OPT-011 | P1 | Unified Gas Model & EIP-1559 Adaptation | Pending |
| OPT-012 | P2 | Parallel EVM Execution (Leveraging DAG) | Pending |
| OPT-013 | P2 | Block Explorer Integration (Blockscout) | Pending |
| RI-001 | R&I | Sub-Second PoW Consensus (GhostDAG Evolution) | Research |

---

### OPT-001: Active Transaction Fetching for Pending Blocks

**Description:**
Currently, when a block arrives with a missing transaction dependency, the system returns `MISSING_DEPENDENCY` and stores the block as `PENDING` (orphan). However, it relies entirely on the missing transaction arriving later via normal broadcast.

**Proposed Solution (Optimal):**
- **Proactive Request**: Upon receiving `MISSING_DEPENDENCY`, `XdagP2pEventHandler` must immediately identify if the missing hash is a transaction and send a `GET_TRANSACTIONS` request to the peer that sent the block.

---

### OPT-002: Transaction-Triggered Orphan Retry

**Description:**
Pending (orphan) blocks are only retried when a **new block** arrives.

**Proposed Solution (Optimal):**
- **Event-Driven Architecture**: Implement an `EventBus`. When `TransactionPool` adds a valid transaction, trigger targeted retry for dependent blocks.

---

### OPT-003: Efficient Transaction Broadcasting (Gossip/Inv)

**Description:**
Simple flood fill causes bandwidth storms.

**Proposed Solution (Optimal):**
- **Gossipsub-like Protocol**: Eager push for close peers, lazy pull (INV) for distant peers.

---

### OPT-004: Block Broadcasting Optimization (Observation)

**Description:**
XDAG blocks are small (<1KB).

**Proposed Solution (Optimal):**
- **Compact Block Propagation**: Always send `Header + ShortIDs`.

---

### OPT-005: Randomized Peer Broadcasting Order

**Description:**
Fixed broadcast order leaks privacy.

**Proposed Solution (Optimal):**
- **Randomized & weighted**: Shuffle peers before broadcasting.

---

### OPT-006: Implement Missing Transaction Sync in SyncManager

**Description:**
Sync logic for missing transactions is unimplemented (empty TODO).

**Proposed Solution (Optimal):**
- Implement full `identifyMissingTransactions` logic.

---

### OPT-007: Protocol-Level Snapshot Verification (Archive Root)

**Description:**
Need efficient verification for historical data snapshots.

**Proposed Solution (Optimal - Clean Slate):**
- **Archive Root**: Use Hierarchical Merkle Tree (Block Hash -> Epoch Root -> Archive Root).

---

### OPT-008: Orphan Transaction Pruning (Storage Leak Fix)

**Description:**
Orphan transactions leak storage.

**Proposed Solution (Optimal):**
- **Reference Counting (RefCount)**: Track usage of transactions. Delete when `refCount == 0`.

---

### OPT-009: EVM Integration & The Three Roots Architecture

**Description:**
To support Smart Contracts, XDAGJ must integrate an EVM.

**Proposed Architecture:**
1.  **Block Header Upgrade**: Add `stateRoot`, `transactionsRoot`, `receiptsRoot`, `logsBloom`.
2.  **State Management**: Use **Besu Storage** (`besu-kvstore`, `besu-trie`) over RocksDB.
3.  **Execution**: Integrate `besu-evm`.
4.  **Compatibility**: Implement JSON-RPC with standard ETH address derivation.

---

### OPT-010: Stratum V1 Server Integration (Mining Architecture)

**Description:**
In-process mining is inefficient.

**Proposed Architecture (Optimal):**
- **Protocol**: **Stratum V1 (TCP JSON-RPC)** for XMRig compatibility.
- **Server**: **Netty** based asynchronous server.
- **Features**: Header-Only Jobs, VarDiff.

---

### OPT-011: Unified Gas Model & EIP-1559 Adaptation

**Description:**
Integrating EVM requires a robust Fee Market.

**Proposed Solution:**
- **EIP-1559**: Implement BaseFee + PriorityFee.
- **DAG Adaptation**: Calculate `BaseFee` based on global network utilization.

---

### OPT-012: Parallel EVM Execution (Leveraging DAG)

**Description:**
EVM execution is traditionally single-threaded.

**Proposed Solution:**
- **Dependency Analysis**: Within an Epoch, identify transactions that touch disjoint state.
- **Parallel Scheduler**: Execute disjoint transactions on separate threads.

---

### OPT-013: Block Explorer Integration (Blockscout)

**Description:**
A robust block explorer is essential.

**Proposed Solution:**
- **Deploy Blockscout**: Supports contract verification, read/write contract, and tokens.
- **Requirement**: Full JSON-RPC compatibility.

---

### RI-001: Sub-Second PoW Consensus (GhostDAG Evolution)

**Description:**
Current 64-second epochs are conservative. Modern PoW DAGs (e.g., Kaspa) achieve sub-second block times without sacrificing decentralization.

**Research Direction:**
- **GhostDAG Protocol**: Move from strict Epoch-based consensus to **GhostDAG** (a greedy variation of PHANTOM). This allows the network to tolerate high orphan rates by incorporating them as "uncles" into the DAG ordering.
- **Goal**: 1 Block/Second (or faster) on PoW.
- **Impact**: Requires abandoning the "Epoch" concept for ordering and using DAG topology instead.