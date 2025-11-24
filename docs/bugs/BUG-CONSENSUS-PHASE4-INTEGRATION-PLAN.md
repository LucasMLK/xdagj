# BUG-CONSENSUS Phase 4: Integration Testing Plan

**Date**: 2025-11-24
**Status**: Ready to Execute
**Prerequisites**: Phase 1-3 Complete, All Unit Tests Pass (418/418)

---

## Objectives

Verify epoch-based consensus works correctly in realistic deployment scenarios:
1. Single-node operation with epoch boundaries
2. Multi-node P2P synchronization
3. Pool server integration
4. Performance and stability

---

## Test Suite 1: Single Node Devnet Testing

### 1.1 Basic Epoch Alignment Test

**Goal**: Verify epoch timer triggers at precise 64-second boundaries

**Steps**:
```bash
# Start devnet node with epoch consensus
cd test-nodes/suite1/node
./start-node.sh

# Monitor logs for epoch boundaries
tail -f logs/xdag.log | grep "Epoch.*ended"
```

**Expected Behavior**:
- Epoch boundaries trigger every ~64 seconds (63999-64001ms)
- Log shows: `═══════════ Epoch N ended ═══════════`
- No epoch skips
- Timing drift < 100ms over 10 epochs

**Success Criteria**:
- [ ] First 10 epochs trigger without errors
- [ ] Average epoch duration: 63999-64001ms
- [ ] No epoch skips detected
- [ ] Memory usage stable (< 2GB)

---

### 1.2 Backup Miner Activation Test

**Goal**: Verify backup miner triggers when no pool solutions received

**Setup**:
- Node running without pool connections
- EpochConsensusManager enabled
- No external miners

**Expected Behavior**:
- Backup miner triggers at T=59s (5 seconds before epoch end)
- Successfully mines block by T=64s
- Block imported at epoch boundary
- Next epoch starts immediately

**Success Criteria**:
- [ ] Backup miner triggers in all epochs (no pool)
- [ ] Block production success rate: 100%
- [ ] Block timestamps align to epoch end (lower 16 bits = 0xffff)
- [ ] Chain continues without gaps

---

### 1.3 Solution Collection Test

**Goal**: Verify solutions collected during epoch, best selected at T=64s

**Setup**:
- Mock pool submitting solutions at T=10s, T=30s, T=50s
- Different difficulties: 0xfff0, 0xfff5, 0xfff8

**Expected Behavior**:
- All 3 solutions accepted and collected
- No immediate import (wait until T=64s)
- At T=64s: highest difficulty (0xfff8) selected
- Only winner block imported
- Backup miner **not** triggered (solutions exist)

**Success Criteria**:
- [ ] 3 solutions collected
- [ ] Highest difficulty solution wins
- [ ] Import happens at T=64s (not before)
- [ ] Backup miner does not activate

---

## Test Suite 2: Multi-Node P2P Testing

### 2.1 Two-Node Synchronization

**Goal**: Verify epoch consensus synchronized across 2 nodes

**Setup**:
```bash
# Terminal 1: Start pool node
cd test-nodes/suite1/pool
./start-pool.sh

# Terminal 2: Start miner node
cd test-nodes/suite1/miner
./start-miner.sh

# Wait for P2P connection established
```

**Expected Behavior**:
- Both nodes process same epochs
- Pool's winning solution propagates to miner
- Both nodes import same block at epoch end
- Chain stays synchronized

**Success Criteria**:
- [ ] Both nodes at same block height after 10 epochs
- [ ] Same block hashes at each height
- [ ] Solution propagation < 2 seconds
- [ ] No fork divergence

---

### 2.2 Three-Node Competition

**Goal**: Verify multiple nodes with pools compete correctly

**Setup**:
- Suite1 pool + miner (pool A)
- Suite2 pool + miner (pool B)
- Connect via P2P

**Test Cases**:
1. Pool A submits higher difficulty → A wins
2. Pool B submits higher difficulty → B wins
3. Tied difficulty → first submitted wins
4. No submissions → backup miner activates

**Success Criteria**:
- [ ] Correct winner selected in all 4 cases
- [ ] All nodes agree on winner
- [ ] No forks or divergence
- [ ] Losing solutions discarded

---

## Test Suite 3: Pool Integration Testing

### 3.1 External Pool Connection

**Goal**: Verify real pool server (xdagj-pool) integration

**Setup**:
```bash
# Start XDAG node with epoch consensus
./xdagj-node --enable-epoch-consensus

# Start external pool (xdagj-pool)
cd ../xdagj-pool
./start-pool.sh
```

**Pool Operations**:
1. Pool fetches candidate block via `/api/mining/candidate`
2. Pool mines and finds nonce
3. Pool submits via `/api/mining/submit`

**Expected Behavior**:
- Candidate provided at epoch start (T=0s)
- Solution accepted and collected (T=?s)
- At T=64s: best solution selected and imported
- Pool receives success response

**Success Criteria**:
- [ ] Candidate block valid (correct epoch)
- [ ] Solution submission accepted
- [ ] Block imported if best solution
- [ ] Pool notified of result

---

### 3.2 Multiple Pools Competition

**Goal**: Verify 3+ pools competing for same epoch

**Setup**:
- Node running
- Pool A, B, C connected
- All mining same epoch

**Scenarios**:
1. **Normal Competition**: A submits diff=100, B submits diff=150, C submits diff=120
   - Expected: B wins
2. **Tied Difficulty**: A submits diff=100 at T=10s, B submits diff=100 at T=20s
   - Expected: A wins (first submitted)
3. **Late Submission**: A submits at T=65s (after epoch end)
   - Expected: Rejected (wrong epoch)

**Success Criteria**:
- [ ] Scenario 1: B's block imported
- [ ] Scenario 2: A's block imported
- [ ] Scenario 3: Late submission rejected
- [ ] All pools notified correctly

---

## Test Suite 4: Performance & Stability

### 4.1 Long-Running Stability Test

**Goal**: Verify system stable over 100+ epochs (~2 hours)

**Metrics to Monitor**:
- Memory usage (should be stable)
- CPU usage (should be < 50%)
- Epoch timing precision (should be ±100ms)
- Solution collection count
- Block production success rate

**Success Criteria**:
- [ ] 100 consecutive epochs without crash
- [ ] Memory usage < 2GB (no leaks)
- [ ] 100% block production (no skipped epochs)
- [ ] Average epoch duration: 63999-64001ms

---

### 4.2 Stress Test: High Solution Volume

**Goal**: Verify system handles many concurrent submissions

**Setup**:
- 10 pools submitting solutions
- Each pool submits 5 solutions per epoch
- Total: 50 solutions per epoch

**Expected Behavior**:
- All valid solutions accepted
- Highest difficulty still wins
- No performance degradation
- Memory usage scales linearly

**Success Criteria**:
- [ ] All 50 solutions processed per epoch
- [ ] Correct winner selected
- [ ] Processing time < 1 second
- [ ] No RejectedExecutionException

---

### 4.3 Restart & Recovery Test

**Goal**: Verify system recovers gracefully from restart

**Steps**:
1. Node running, processing epoch N
2. Kill node at T=30s (mid-epoch)
3. Restart node
4. Verify epoch N+1 starts correctly

**Expected Behavior**:
- Node detects current epoch on startup
- Skips incomplete epoch N
- Starts fresh at epoch N+1
- No data corruption

**Success Criteria**:
- [ ] Clean startup after mid-epoch kill
- [ ] Epoch context recreated correctly
- [ ] No stale solutions carried over
- [ ] Chain continues from last finalized block

---

## Test Suite 5: Edge Cases & Error Handling

### 5.1 Clock Skew Test

**Goal**: Verify behavior when system clock adjusted

**Scenarios**:
1. Clock moved forward 10 seconds mid-epoch
2. Clock moved backward 10 seconds mid-epoch

**Expected Behavior**:
- System detects clock change
- Safely transitions to correct epoch
- No epoch number regression
- No duplicate epoch processing

**Success Criteria**:
- [ ] Forward jump handled gracefully
- [ ] Backward jump detected and logged
- [ ] No crash or data corruption
- [ ] Chain integrity maintained

---

### 5.2 Network Partition Test

**Goal**: Verify behavior during P2P network split

**Setup**:
- 3 nodes: A, B, C
- Initial state: all connected
- Action: Isolate node C from A and B

**Expected Behavior**:
- A and B continue synchronized
- C operates independently
- On reconnection: C syncs from A/B
- No permanent fork

**Success Criteria**:
- [ ] A and B stay synchronized during partition
- [ ] C produces blocks independently
- [ ] Reunion: C syncs and adopts longer chain
- [ ] No data loss

---

## Execution Plan

### Phase 4.1: Single Node Tests (Day 1)
- Test Suite 1.1, 1.2, 1.3
- Estimated time: 2 hours

### Phase 4.2: Multi-Node Tests (Day 2)
- Test Suite 2.1, 2.2
- Estimated time: 3 hours

### Phase 4.3: Pool Integration (Day 3)
- Test Suite 3.1, 3.2
- Estimated time: 2 hours

### Phase 4.4: Performance & Stability (Day 4)
- Test Suite 4.1, 4.2, 4.3
- Estimated time: 4 hours (includes long-running test)

### Phase 4.5: Edge Cases (Day 5)
- Test Suite 5.1, 5.2
- Estimated time: 2 hours

---

## Success Criteria Summary

**Must Pass**:
- [ ] All single-node tests pass
- [ ] Multi-node synchronization works
- [ ] Pool integration functional
- [ ] 100-epoch stability test passes

**Nice to Have**:
- [ ] High-volume stress test passes
- [ ] Edge cases handled gracefully

**Blockers**:
- Epoch skips (critical bug)
- Node crashes (stability issue)
- Forks in multi-node setup (consensus failure)

---

## Next Steps After Phase 4

If all tests pass:
1. Create comprehensive test report
2. Update documentation with test results
3. Prepare for production deployment
4. Create monitoring dashboards

If tests fail:
1. Document failure mode
2. Fix identified issues
3. Re-run failed tests
4. Update this plan as needed

---

**Plan Status**: Ready to Execute
**Estimated Total Time**: 13 hours across 5 days
**Risk Level**: Medium (new consensus mechanism)

_Generated with [Claude Code](https://claude.com/claude-code)_
_Co-Authored-By: Claude <noreply@anthropic.com>_
