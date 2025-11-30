# SECURITY-STORAGE-ATTACK · Potential Storage Exhaustion Vector

**Severity**: Medium (requires considerable hash power; risk currently manageable)  
**Reported**: 2025-11-25  
**Status**: Tracked – mitigation scheduled with future consensus tuning

---

## Description

When the network has fewer than 16 active miners, a malicious miner with enough hash power to remain
inside the top-16 candidate set can inject useless data into the DAG:

1. **Garbage block chain** – attacker mines blocks containing meaningless payloads, each referencing
   the previous garbage block. As long as the block ranks in the top 16 for the epoch, it is kept.
2. **Massive reference fan-out** – attacker fills the block with references to already-spent
   transactions or old blocks. Structural validation passes, so the block is stored even though the
   references contribute nothing.

Because the DAG keeps all top-16 candidates per epoch, an attacker can slowly inflate storage usage
while the network remains underpopulated.

---

## Cost analysis

- Attacker must consistently mine inside the epoch’s top 16, so the attack requires meaningful hash
  power (not zero-cost).
- Attack becomes harder as more honest miners participate; once the network routinely has 16+
  candidates per epoch, the attacker’s probability of staying in the set falls dramatically.

---

## Mitigations

1. **Top-candidate pruning** – enforce secondary heuristics (e.g., minimum transaction count,
   reference diversity) before storing a candidate. Planned for v5.3.
2. **Quota per mining address** – limit how many orphan candidates per epoch a single address can
   contribute.
3. **Monitoring** – add Prometheus counters for orphan size, candidate payload size, and per-address
   contribution to detect anomalies early.

---

## Current stance

- Risk accepted temporarily because devnet/minnet have limited miners and the attack requires steady
  hash power.
- Tracked under SECURITY-STORAGE-ATTACK; mitigation will ship alongside the orphan-pipeline
  hardening.
