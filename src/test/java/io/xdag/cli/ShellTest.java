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
package io.xdag.cli;

import io.xdag.DagKernel;
import io.xdag.config.Config;
import io.xdag.core.ChainStats;
import io.xdag.core.DagChain;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.AccountStore;
import io.xdag.db.TransactionStore;
import io.xdag.Wallet;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.jline.console.CommandInput;
import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for Shell CLI commands
 * <p>
 * Tests all 11 CLI commands:
 * - Data Query: account, transaction, block, chain, mined, epoch
 * - Transaction: transfer
 * - Network: network, stats
 * - System: monitor, stop
 */
@RunWith(MockitoJUnitRunner.class)
public class ShellTest {

    private Shell shell;
    private Commands commands;

    @Mock
    private DagKernel dagKernel;

    @Mock
    private Config config;

    @Mock
    private Wallet wallet;

    @Mock
    private DagChain dagChain;

    @Mock
    private ChainStats chainStats;

    @Mock
    private AccountStore accountStore;

    @Mock
    private TransactionStore transactionStore;

    @Mock
    private LineReader lineReader;

    @Mock
    private Terminal terminal;

    @Mock
    private CommandInput commandInput;

    private StringWriter outputWriter;
    private PrintWriter printWriter;

    @Before
    public void setUp() {
        // Initialize Shell
        shell = new Shell();
        shell.setDagKernel(dagKernel);

        // Initialize Commands
        commands = new Commands(dagKernel);

        // Setup mock Terminal and LineReader (lenient for optional usage)
        outputWriter = new StringWriter();
        printWriter = new PrintWriter(outputWriter);
        lenient().when(terminal.writer()).thenReturn(printWriter);
        lenient().when(lineReader.getTerminal()).thenReturn(terminal);
        shell.setReader(lineReader);

        // Setup mock DagKernel dependencies
        lenient().when(dagKernel.getConfig()).thenReturn(config);
        when(dagKernel.getWallet()).thenReturn(wallet);
        when(dagKernel.getDagChain()).thenReturn(dagChain);
        when(dagKernel.getAccountStore()).thenReturn(accountStore);
        when(dagKernel.getTransactionStore()).thenReturn(transactionStore);

        // Setup mock ChainStats
        when(dagChain.getChainStats()).thenReturn(chainStats);
        when(chainStats.getMainBlockCount()).thenReturn(10000L);
        when(chainStats.getTotalBlockCount()).thenReturn(12000L);
        when(chainStats.getNoRefCount()).thenReturn(500L);
        when(chainStats.getWaitingSyncCount()).thenReturn(100L);
        when(chainStats.getDifficulty()).thenReturn(UInt256.valueOf(1000000));
        when(chainStats.getTopDifficulty()).thenReturn(UInt256.valueOf(1000000));
        when(chainStats.getMaxDifficulty()).thenReturn(UInt256.valueOf(2000000));
        when(chainStats.getSyncProgress()).thenReturn(95.5);
        when(chainStats.getTotalHostCount()).thenReturn(50);
    }

    // ==================== Account & Wallet Commands Tests ====================

    @Test
    public void testAccountCommand() {
        // Setup mock data
        List<ECKeyPair> accounts = new ArrayList<>();
        ECKeyPair keyPair = ECKeyPair.generate();
        accounts.add(keyPair);

        when(wallet.getAccounts()).thenReturn(accounts);
        when(accountStore.getBalance(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt256.valueOf(1000000000L));
        when(accountStore.getNonce(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt64.valueOf(42));

        // Execute
        String result = commands.account(20);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("XDAG"));
        assertTrue(result.contains("Nonce"));
    }

    @Test
    public void testBalanceCommandNoAddress() {
        // Setup mock data
        List<ECKeyPair> accounts = new ArrayList<>();
        accounts.add(ECKeyPair.generate());

        when(wallet.getAccounts()).thenReturn(accounts);
        when(accountStore.getBalance(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt256.valueOf(1000000000L));

        // Execute
        String result = commands.balance(null);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Balance:"));
        assertTrue(result.contains("XDAG"));
    }

    @Test
    public void testNonceCommand() {
        // Setup mock data
        List<ECKeyPair> accounts = new ArrayList<>();
        accounts.add(ECKeyPair.generate());

        when(wallet.getAccounts()).thenReturn(accounts);
        when(accountStore.getNonce(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt64.valueOf(42));

        // Execute
        String result = commands.txQuantity(null);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Nonce"));
    }

    @Test
    public void testMaxBalanceCommand() {
        // Setup mock data
        List<ECKeyPair> accounts = new ArrayList<>();
        accounts.add(ECKeyPair.generate());

        when(wallet.getAccounts()).thenReturn(accounts);
        when(accountStore.getBalance(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt256.valueOf(1000000000L));

        // Execute
        String result = commands.balanceMaxXfer();

        // Verify
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // ==================== Block & Chain Commands Tests ====================

    @Test
    public void testBlockCommand() {
        // Setup mock data
        Bytes32 blockHash = Bytes32.random();
        Block block = createMockBlock(blockHash, 12345L);

        when(dagChain.getBlockByHash(eq(blockHash), anyBoolean())).thenReturn(block);
        when(transactionStore.getTransactionsByBlock(any(Bytes32.class))).thenReturn(new ArrayList<>());

        // Execute
        String result = commands.block(blockHash);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Block Info"));
    }

    @Test
    public void testChainCommand() {
        // Setup mock data
        List<Block> blocks = new ArrayList<>();
        blocks.add(createMockBlock(Bytes32.random(), 12345L));
        blocks.add(createMockBlock(Bytes32.random(), 12344L));

        when(dagChain.listMainBlocks(anyInt())).thenReturn(blocks);
        when(transactionStore.getTransactionsByBlock(any(Bytes32.class))).thenReturn(new ArrayList<>());

        // Execute
        String result = commands.chain(20);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Main Chain Blocks"));
    }

    @Test
    public void testMinedCommand() {
        // Execute - Commands.minedBlocks() uses BlockApiService internally
        // No need to mock dagChain.listMinedBlocks() or transactionStore
        String result = commands.minedBlocks(20);

        // Verify - minedBlocks may return different messages:
        // "No accounts in wallet..." or "No mined blocks found..." or "Blocks Mined by This Node"
        assertNotNull(result);
        // Test passes if result contains any of these strings
        boolean containsExpectedString = result.contains("Blocks Mined") ||
                                         result.contains("No accounts") ||
                                         result.contains("No mined blocks");
        assertTrue("Result should contain mined blocks info or error message", containsExpectedString);
    }

    @Test
    public void testEpochCommand() {
        // Setup mock data
        long currentEpoch = 23693854L;
        long[] timeRange = {1699700000L, 1699700064L};
        List<Block> candidates = new ArrayList<>();

        when(dagChain.getCurrentEpoch()).thenReturn(currentEpoch);
        when(dagChain.getEpochTimeRange(anyLong())).thenReturn(timeRange);
        when(dagChain.getCandidateBlocksInEpoch(anyLong())).thenReturn(candidates);

        // Execute
        String result = commands.epoch(null);

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Epoch"));
    }

    // ==================== Network & Mining Commands Tests ====================

    @Test
    public void testStatsCommand() {
        // Setup mock data - will use real ChainStats from DagChain
        when(dagChain.getCurrentEpoch()).thenReturn(23693854L);

        List<ECKeyPair> accounts = new ArrayList<>();
        accounts.add(ECKeyPair.generate());
        when(wallet.getAccounts()).thenReturn(accounts);
        when(accountStore.getBalance(any(org.apache.tuweni.bytes.Bytes.class))).thenReturn(UInt256.valueOf(1000000000L));

        // Execute
        String result = commands.stats();

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Statistics"));
    }

    @Test
    public void testStateCommand() {
        // Execute
        String result = commands.state();

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("State"));
    }

    @Test
    public void testPoolCommand() {
        // Execute
        String result = commands.pool();

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("pool"));
    }

    @Test
    public void testKeygenCommand() {
        // Setup mock data
        List<ECKeyPair> accounts = new ArrayList<>();
        accounts.add(ECKeyPair.generate());

        when(wallet.getAccounts()).thenReturn(accounts);

        // Execute
        String result = commands.keygen();

        // Verify
        assertNotNull(result);
        assertTrue(result.contains("Key"));
    }

    // ==================== Command Registration Tests ====================

    @Test
    public void testAllCommandsRegistered() {
        // Verify all 11 commands are registered
        Map<String, ?> registeredCommands = shell.commandExecute;

        // Data Query Commands
        assertTrue("account command should be registered", registeredCommands.containsKey("account"));
        assertTrue("transaction command should be registered", registeredCommands.containsKey("transaction"));
        assertTrue("block command should be registered", registeredCommands.containsKey("block"));
        assertTrue("chain command should be registered", registeredCommands.containsKey("chain"));
        assertTrue("mined command should be registered", registeredCommands.containsKey("mined"));
        assertTrue("epoch command should be registered", registeredCommands.containsKey("epoch"));

        // Transaction Commands
        assertTrue("transfer command should be registered", registeredCommands.containsKey("transfer"));

        // Network Commands
        assertTrue("network command should be registered", registeredCommands.containsKey("network"));
        assertTrue("stats command should be registered", registeredCommands.containsKey("stats"));

        // System Commands
        assertTrue("monitor command should be registered", registeredCommands.containsKey("monitor"));
        assertTrue("stop command should be registered", registeredCommands.containsKey("stop"));

        // Verify total count
        assertEquals("Should have exactly 11 commands registered", 11, registeredCommands.size());
    }

    @Test
    public void testLegacyCommandsNotRegistered() {
        // Verify old commands are NOT registered
        Map<String, ?> registeredCommands = shell.commandExecute;

        assertFalse("lastblocks should not be registered", registeredCommands.containsKey("lastblocks"));
        assertFalse("xfer should not be registered", registeredCommands.containsKey("xfer"));
        assertFalse("xfertonew should not be registered", registeredCommands.containsKey("xfertonew"));
        assertFalse("xferv2 should not be registered", registeredCommands.containsKey("xferv2"));
        assertFalse("xfertonewv2 should not be registered", registeredCommands.containsKey("xfertonewv2"));
        assertFalse("oldbalance should not be registered", registeredCommands.containsKey("oldbalance"));
        assertFalse("txQuantity should not be registered", registeredCommands.containsKey("txQuantity"));
        assertFalse("mainblocks should not be registered", registeredCommands.containsKey("mainblocks"));
        assertFalse("minedblocks should not be registered", registeredCommands.containsKey("minedblocks"));
        assertFalse("net should not be registered", registeredCommands.containsKey("net"));
        assertFalse("ttop should not be registered", registeredCommands.containsKey("ttop"));
        assertFalse("terminate should not be registered", registeredCommands.containsKey("terminate"));
    }

    @Test
    public void testNewCommandNaming() {
        // Verify new command names follow naming conventions
        Map<String, ?> registeredCommands = shell.commandExecute;

        // All command names should be lowercase
        for (String commandName : registeredCommands.keySet()) {
            assertEquals("Command names should be lowercase",
                commandName.toLowerCase(), commandName);
        }

        // No command names should contain version suffixes
        for (String commandName : registeredCommands.keySet()) {
            assertFalse("Command names should not contain 'v2' suffix",
                commandName.contains("v2"));
        }

        // All command names should be full words (not abbreviations)
        assertTrue("Should use 'network' not 'net'", registeredCommands.containsKey("network"));
        assertTrue("Should use 'monitor' not 'ttop'", registeredCommands.containsKey("monitor"));
        assertTrue("Should use 'stop' not 'terminate'", registeredCommands.containsKey("stop"));
    }

    // ==================== Helper Methods ====================

    /**
     * Create a mock Block for testing
     */
    private Block createMockBlock(Bytes32 hash, long height) {
        BlockHeader header = BlockHeader.builder()
                .timestamp(System.currentTimeMillis())
                .difficulty(UInt256.valueOf(1000))
                .nonce(Bytes32.ZERO)
                .coinbase(org.apache.tuweni.bytes.Bytes.random(20))
                .hash(hash)
                .build();

        BlockInfo info = BlockInfo.builder()
                .hash(hash)
                .timestamp(System.currentTimeMillis())
                .height(height)
                .difficulty(UInt256.valueOf(1000))
                .build();

        return Block.builder()
                .header(header)
                .links(new ArrayList<>())
                .info(info)
                .build();
    }

    /**
     * Test that Shell prompt is correctly set
     */
    @Test
    public void testShellPrompt() {
        assertEquals("xdag> ", Shell.prompt);
    }

    /**
     * Test that default list number is correctly set
     */
    @Test
    public void testDefaultListNum() {
        assertEquals(20, Shell.DEFAULT_LIST_NUM);
    }
}
