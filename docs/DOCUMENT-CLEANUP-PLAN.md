# Documentation Cleanup Plan

## Current Issues

- 40 Markdown files (~250 KB) across `docs/`, `docs/bugs/`, `docs/refactoring/`, and `docs/test-reports/`
- Large amount of phase-by-phase journaling that no longer matches the current codebase
- Repeated verification reports describing the same fix
- Troubleshooting diaries preserved even after a bug is fully resolved
- Several duplicated summaries for the same milestone

## Cleanup Strategy

1. **Keep the final state** – delete process logs, keep the final fix/summary docs
2. **Merge duplicated content** – consolidate reports that tell the same story
3. **Preserve root-cause analyses** – historical decision records stay
4. **Drop temporary test notes** – keep deterministic, reproducible reports only

---

## Detailed Actions

### 📁 `docs/bugs/` (23 files → 11 core files)

**Keep**
- BUG-CONSENSUS-006-EpochTimer-Drift.md
- BUG-CONSENSUS-007-HEIGHT-RACE-CONDITION.md
- BUG-CONSENSUS-007-FIX-VERIFICATION.md
- BUG-LINK-NOT-FOUND-ROOT-CAUSE.md
- SECURITY-STORAGE-ATTACK.md
- CONSENSUS-FIX-COMPLETE-SUMMARY.md
- BUG-STORAGE-002-COMPLETE-REPORT.md
- BUG-CONSENSUS-005.md (optional deep dive)

**Delete**
- BUG-CONSENSUS-001/002/003 process logs
- PHASE1–PHASE4 milestone notes
- BUG-CONSENSUS-UNIFIED-FIX-PLAN.md
- CONSENSUS-CLEANUP-REPORT.md
- BUG-STORAGE-002-* partial reports (ASYNC-WRITE-WAL-LOSS / FIX-IMPLEMENTATION / FINAL-VERIFICATION / HISTORICAL-DATA-GAPS-ANALYSIS)

### 📁 `docs/refactoring/` (8 files → 4)

**Keep**
- DAGCHAIN-REFACTORING-PROPOSAL.md
- REFACTORING-PROGRESS-SUMMARY.md
- ORPHAN-CLEANUP-REMOVAL.md
- MULTI-NODE_TEST_REPORT.md

**Delete**
- DAGCHAIN-REFACTORING-PLAN-P1.md
- DAGCHAIN-REFACTORING-PROGRESS-P0.md
- DAGCHAIN-REFACTORING-INTEGRATION-REPORT.md
- PHASE4_INTEGRATION_TEST_REPORT.md

### 📁 `docs/test-reports/` (3 files → 1)

**Keep**
- PHASE4-TEST-1.1-COMPLETE.md

**Delete**
- PHASE4-TEST-1.1-PRELIMINARY.md
- BUG-STORAGE-002-CLEAN-START-TEST.md

---

## Resulting Structure

```
docs/
  ARCHITECTURE.md
  ENGINEERING-ARCHITECTURE.md
  DEVNET_MULTI_NODE.md
  XDAG-1.0-CONSENSUS-PROTOCOL.md
  design/EVM_INTEGRATION_DESIGN.md
  design/SYNC_PROTOCOL_V2.md
  bugs/ (11 curated files)
  refactoring/ (4 curated files)
  test-reports/ (PHASE4-TEST-1.1-COMPLETE.md)
```

---

## Execution Checklist

```
cd /Users/reymondtu/dev/github/xdagj

# Remove redundant bug reports
git rm docs/bugs/BUG-CONSENSUS-001-missing-epoch-forced-block.md
git rm docs/bugs/BUG-CONSENSUS-002-immediate-block-import.md
git rm docs/bugs/BUG-CONSENSUS-003-FIXED.md
git rm docs/bugs/BUG-CONSENSUS-003-NO-CANDIDATE-BLOCK.md
git rm docs/bugs/BUG-CONSENSUS-PHASE1-COMPLETE.md
git rm docs/bugs/BUG-CONSENSUS-PHASE2-COMPLETE.md
git rm docs/bugs/BUG-CONSENSUS-PHASE3-PROGRESS.md
git rm docs/bugs/BUG-CONSENSUS-PHASE4-INTEGRATION-PLAN.md
git rm docs/bugs/BUG-CONSENSUS-UNIFIED-FIX-PLAN.md
git rm docs/bugs/CONSENSUS-CLEANUP-REPORT.md
git rm docs/bugs/BUG-STORAGE-002-ASYNC-WRITE-WAL-LOSS.md
git rm docs/bugs/BUG-STORAGE-002-FIX-IMPLEMENTATION.md
git rm docs/bugs/BUG-STORAGE-002-FINAL-VERIFICATION.md
git rm docs/bugs/BUG-STORAGE-002-HISTORICAL-DATA-GAPS-ANALYSIS.md

# Remove obsolete refactoring logs
git rm docs/refactoring/DAGCHAIN-REFACTORING-PLAN-P1.md
git rm docs/refactoring/DAGCHAIN-REFACTORING-PROGRESS-P0.md
git rm docs/refactoring/DAGCHAIN-REFACTORING-INTEGRATION-REPORT.md
git rm docs/refactoring/PHASE4_INTEGRATION_TEST_REPORT.md

# Remove temporary test reports
git rm docs/test-reports/PHASE4-TEST-1.1-PRELIMINARY.md
git rm docs/test-reports/BUG-STORAGE-002-CLEAN-START-TEST.md
```

---

## Impact

- **Before**: 40 docs (~250 KB)
- **After**: 23 curated docs (~120 KB)
- **Reduction**: 17 docs (43%) and ~130 KB (52%)

**What stays**
- Architecture and consensus references
- Latest bug analyses and verification reports
- Key design decisions
- Deterministic multi-node test evidence

**What goes away**
- Process diaries
- Duplicated summaries
- Temporary verification notes
- Obsolete plans
