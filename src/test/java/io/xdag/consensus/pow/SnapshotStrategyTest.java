/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.xdag.consensus.pow;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for SnapshotStrategy
 *
 * <p>Tests the snapshot loading strategy enum.
 */
public class SnapshotStrategyTest {

    /**
     * Test 1: All strategy values exist
     */
    @Test
    public void testAllStrategiesExist() {
        SnapshotStrategy[] strategies = SnapshotStrategy.values();

        assertNotNull("Strategies array should not be null", strategies);
        assertEquals("Should have exactly 4 strategies", 4, strategies.length);

        // Verify each strategy exists
        assertNotNull("WITH_PRESEED should exist", SnapshotStrategy.WITH_PRESEED);
        assertNotNull("FROM_CURRENT_STATE should exist", SnapshotStrategy.FROM_CURRENT_STATE);
        assertNotNull("FROM_FORK_HEIGHT should exist", SnapshotStrategy.FROM_FORK_HEIGHT);
        assertNotNull("AUTO should exist", SnapshotStrategy.AUTO);
    }

    /**
     * Test 2: Strategy names are correct
     */
    @Test
    public void testStrategyNames() {
        assertEquals("WITH_PRESEED name should match",
            "WITH_PRESEED", SnapshotStrategy.WITH_PRESEED.name());
        assertEquals("FROM_CURRENT_STATE name should match",
            "FROM_CURRENT_STATE", SnapshotStrategy.FROM_CURRENT_STATE.name());
        assertEquals("FROM_FORK_HEIGHT name should match",
            "FROM_FORK_HEIGHT", SnapshotStrategy.FROM_FORK_HEIGHT.name());
        assertEquals("AUTO name should match",
            "AUTO", SnapshotStrategy.AUTO.name());
    }

    /**
     * Test 3: Strategy ordinal values
     */
    @Test
    public void testStrategyOrdinals() {
        // Verify ordinal values (order in enum declaration)
        assertTrue("WITH_PRESEED ordinal should be >= 0",
            SnapshotStrategy.WITH_PRESEED.ordinal() >= 0);
        assertTrue("FROM_CURRENT_STATE ordinal should be >= 0",
            SnapshotStrategy.FROM_CURRENT_STATE.ordinal() >= 0);
        assertTrue("FROM_FORK_HEIGHT ordinal should be >= 0",
            SnapshotStrategy.FROM_FORK_HEIGHT.ordinal() >= 0);
        assertTrue("AUTO ordinal should be >= 0",
            SnapshotStrategy.AUTO.ordinal() >= 0);

        // All ordinals should be unique
        SnapshotStrategy[] strategies = SnapshotStrategy.values();
        for (int i = 0; i < strategies.length; i++) {
            for (int j = i + 1; j < strategies.length; j++) {
                assertNotEquals("Ordinals should be unique",
                    strategies[i].ordinal(), strategies[j].ordinal());
            }
        }
    }

    /**
     * Test 4: Strategy valueOf
     */
    @Test
    public void testValueOf() {
        assertEquals("valueOf should work for WITH_PRESEED",
            SnapshotStrategy.WITH_PRESEED, SnapshotStrategy.valueOf("WITH_PRESEED"));
        assertEquals("valueOf should work for FROM_CURRENT_STATE",
            SnapshotStrategy.FROM_CURRENT_STATE, SnapshotStrategy.valueOf("FROM_CURRENT_STATE"));
        assertEquals("valueOf should work for FROM_FORK_HEIGHT",
            SnapshotStrategy.FROM_FORK_HEIGHT, SnapshotStrategy.valueOf("FROM_FORK_HEIGHT"));
        assertEquals("valueOf should work for AUTO",
            SnapshotStrategy.AUTO, SnapshotStrategy.valueOf("AUTO"));
    }

    /**
     * Test 5: valueOf with invalid name throws exception
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValueOfInvalidName() {
        SnapshotStrategy.valueOf("INVALID_STRATEGY");
    }

    /**
     * Test 6: valueOf with null throws exception
     */
    @Test(expected = NullPointerException.class)
    public void testValueOfNull() {
        SnapshotStrategy.valueOf(null);
    }

    /**
     * Test 7: requiresPreseed method
     */
    @Test
    public void testRequiresPreseed() {
        // WITH_PRESEED requires preseed
        assertTrue("WITH_PRESEED should require preseed",
            SnapshotStrategy.WITH_PRESEED.requiresPreseed());

        // Other strategies don't require preseed
        assertFalse("FROM_CURRENT_STATE should not require preseed",
            SnapshotStrategy.FROM_CURRENT_STATE.requiresPreseed());
        assertFalse("FROM_FORK_HEIGHT should not require preseed",
            SnapshotStrategy.FROM_FORK_HEIGHT.requiresPreseed());
        assertFalse("AUTO should not require preseed",
            SnapshotStrategy.AUTO.requiresPreseed());
    }

    /**
     * Test 8: requiresBlockchain method
     */
    @Test
    public void testRequiresBlockchain() {
        // WITH_PRESEED doesn't require blockchain
        assertFalse("WITH_PRESEED should not require blockchain",
            SnapshotStrategy.WITH_PRESEED.requiresBlockchain());

        // Other strategies require blockchain
        assertTrue("FROM_CURRENT_STATE should require blockchain",
            SnapshotStrategy.FROM_CURRENT_STATE.requiresBlockchain());
        assertTrue("FROM_FORK_HEIGHT should require blockchain",
            SnapshotStrategy.FROM_FORK_HEIGHT.requiresBlockchain());
        assertTrue("AUTO should require blockchain",
            SnapshotStrategy.AUTO.requiresBlockchain());
    }

    /**
     * Test 9: Strategy method consistency
     */
    @Test
    public void testMethodConsistency() {
        // WITH_PRESEED: requires preseed, doesn't require blockchain
        assertEquals("WITH_PRESEED preseed requirement",
            true, SnapshotStrategy.WITH_PRESEED.requiresPreseed());
        assertEquals("WITH_PRESEED blockchain requirement",
            false, SnapshotStrategy.WITH_PRESEED.requiresBlockchain());

        // Other strategies: opposite pattern
        for (SnapshotStrategy strategy : SnapshotStrategy.values()) {
            if (strategy != SnapshotStrategy.WITH_PRESEED) {
                assertEquals("Non-WITH_PRESEED should not require preseed: " + strategy,
                    false, strategy.requiresPreseed());
                assertEquals("Non-WITH_PRESEED should require blockchain: " + strategy,
                    true, strategy.requiresBlockchain());
            }
        }
    }

    /**
     * Test 10: Enum comparison
     */
    @Test
    public void testEnumComparison() {
        // Same strategy should be equal
        assertEquals("Same strategy should be equal",
            SnapshotStrategy.AUTO, SnapshotStrategy.AUTO);

        // Different strategies should not be equal
        assertNotEquals("Different strategies should not be equal",
            SnapshotStrategy.AUTO, SnapshotStrategy.WITH_PRESEED);

        // Identity check
        assertSame("Enum values should be singleton",
            SnapshotStrategy.AUTO, SnapshotStrategy.valueOf("AUTO"));
    }

    /**
     * Test 11: Strategy toString
     */
    @Test
    public void testToString() {
        for (SnapshotStrategy strategy : SnapshotStrategy.values()) {
            String str = strategy.toString();
            assertNotNull("toString should not be null for " + strategy, str);
            assertFalse("toString should not be empty for " + strategy, str.isEmpty());
        }
    }

    /**
     * Test 12: Strategy switch statement coverage
     */
    @Test
    public void testSwitchStatementCoverage() {
        // Test that all strategies can be used in switch statement
        for (SnapshotStrategy strategy : SnapshotStrategy.values()) {
            String result = switchTest(strategy);
            assertNotNull("Switch result should not be null for " + strategy, result);
            assertFalse("Switch result should not be empty for " + strategy, result.isEmpty());
        }
    }

    /**
     * Helper method to test switch statement
     */
    private String switchTest(SnapshotStrategy strategy) {
        switch (strategy) {
            case WITH_PRESEED:
                return "Fast loading with preseed";
            case FROM_CURRENT_STATE:
                return "Reconstruct from current state";
            case FROM_FORK_HEIGHT:
                return "Initialize from fork height";
            case AUTO:
                return "Auto-select strategy";
            default:
                return "Unknown strategy";
        }
    }

    /**
     * Test 13: Strategy selection patterns
     */
    @Test
    public void testStrategySelectionPatterns() {
        // WITH_PRESEED should be fast but needs preseed
        assertTrue("WITH_PRESEED needs preseed",
            SnapshotStrategy.WITH_PRESEED.requiresPreseed());

        // AUTO should be flexible (requires blockchain for decision making)
        assertTrue("AUTO should require blockchain for decision",
            SnapshotStrategy.AUTO.requiresBlockchain());
    }

    /**
     * Test 14: Strategy documentation exists
     */
    @Test
    public void testStrategyDocumentation() {
        // Each strategy should be defined (enum constant exists)
        for (SnapshotStrategy strategy : SnapshotStrategy.values()) {
            assertNotNull("Strategy should not be null: " + strategy, strategy);
            assertNotNull("Strategy name should not be null: " + strategy, strategy.name());
            assertFalse("Strategy name should not be empty: " + strategy,
                strategy.name().isEmpty());
        }
    }
}
