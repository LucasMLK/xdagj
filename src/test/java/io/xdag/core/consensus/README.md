# Consensus Tests

This package contains core consensus tests for XDAG v5.1.

## Test Classes

- `ConsensusTestP0.java` - Priority P0 (must-pass) consensus tests (5 tests)
- `ConsensusTestP1.java` - Priority P1 (high-priority) consensus tests (2 tests)
- `MultiNodeTestEnvironment.java` - Multi-node test infrastructure

## Test Status

✅ **Phase 12.5 Complete** - All 7 tests passing (5 P0 + 2 P1)

## Documentation

All test documentation has been moved to:
**`docs/testing/`**

- [README](../../../../../../../../docs/testing/README.md) - Quick start and overview
- [Test Results Summary](../../../../../../../../docs/testing/TEST-RESULTS-SUMMARY.md) - Detailed test results and bug fixes
- [Consensus Test Plan](../../../../../../../../docs/testing/CONSENSUS_TEST_PLAN.md) - Complete test plan (P0-P3)

## Running Tests

```bash
# Run all P0 tests (must-pass)
mvn test -Dtest=ConsensusTestP0

# Run all P1 tests (high-priority)
mvn test -Dtest=ConsensusTestP1

# Run all consensus tests
mvn test -Dtest=ConsensusTestP0,ConsensusTestP1

# Run specific test
mvn test -Dtest=ConsensusTestP0#testSingleEpochSingleWinner
```
