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

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.BasicUtils.address2Hash;
import static io.xdag.utils.BasicUtils.address2PubAddress;
import static io.xdag.utils.BasicUtils.getHash;
import static io.xdag.utils.BasicUtils.hash2Address;
import static io.xdag.utils.WalletUtils.checkAddress;
import static io.xdag.utils.WalletUtils.fromBase58;

import com.google.common.collect.Lists;
import io.xdag.DagKernel;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.DagImportResult;
import io.xdag.core.Link;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.XdagTime;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;

/**
 * Command line interface for XDAG operations
 */
@Getter
@Slf4j
public class Commands {

    private final DagKernel dagKernel;

    // Block state string constants (Phase 7.1: Replacing BlockState enum)
    private static final String MAIN_STATE = "Main";

    public Commands(DagKernel dagKernel) {
        this.dagKernel = dagKernel;
    }

    // ========== v5.1 AccountStore Helper Methods ==========

    /**
     * Convert UInt256 to XAmount for display
     */
    private static XAmount uint256ToXAmount(UInt256 balance) {
        // Convert from wei to nano units
        return XAmount.ofXAmount(balance.toLong());
    }

    /**
     * Get account balance from AccountStore with XAmount conversion
     */
    private XAmount getAccountBalance(Bytes address) {
        UInt256 balance = dagKernel.getAccountStore().getBalance(address);
        return uint256ToXAmount(balance);
    }

    /**
     * Get account nonce from AccountStore
     */
    private long getAccountNonce(Bytes address) {
        return dagKernel.getAccountStore().getNonce(address).toLong();
    }

    /**
     * Update account nonce in AccountStore
     */
    private void updateAccountNonce(Bytes address, long nonce) {
        dagKernel.getAccountStore().setNonce(address, UInt64.valueOf(nonce));
    }

    /**
     * List addresses, balances and nonces (v5.1 implementation)
     * <p>
     * Phase 8.1: Restored using v5.1 AccountStore.
     * Shows account addresses with balances and transaction nonces.
     * <p>
     * v5.1 Simplifications:
     * - Uses AccountStore.getBalance() for account balances
     * - Uses AccountStore.getNonce() instead of txQuantity
     * - No longer shows "Confirmed TX Quantity" (simplified in v5.1)
     *
     * @param num Number of addresses to display
     * @return Formatted account list
     */
    public String account(int num) {
        StringBuilder str = new StringBuilder();
        List<ECKeyPair> list = dagKernel.getWallet().getAccounts();

        // Sort by balance descending
        list.sort((o1, o2) -> {
            Bytes addr1 = Bytes.wrap(toBytesAddress(o1));
            Bytes addr2 = Bytes.wrap(toBytesAddress(o2));
            XAmount balance1 = getAccountBalance(addr1);
            XAmount balance2 = getAccountBalance(addr2);
            return balance2.compareTo(balance1); // Descending order
        });

        for (ECKeyPair keyPair : list) {
            if (num == 0) {
                break;
            }

            Bytes addr = Bytes.wrap(toBytesAddress(keyPair));
            XAmount balance = getAccountBalance(addr);
            long nonce = getAccountNonce(addr);

            str.append(AddressUtils.toBase58Address(keyPair))
                    .append(" ")
                    .append(balance.toDecimal(9, XUnit.XDAG).toPlainString())
                    .append(" XDAG")
                    .append("  [Nonce: ")
                    .append(nonce)
                    .append("]")
                    .append("\n");
            num--;
        }

        return str.toString();
    }

    /**
     * Print header for block list display (enhanced v5.1 format)
     */
    public static String printHeaderBlockList() {
        return """
                ═══════════════════════════════════════════════════════════════════════════════════════
                Height    Address                            Time                  Difficulty    Txs
                ═══════════════════════════════════════════════════════════════════════════════════════
                """;
    }

    // ========== Phase 8.1: Block Display Methods ==========

    /**
     * Print Block in list format (v5.1 enhanced implementation)
     * <p>
     * Phase 8.1: Block display for CLI commands.
     * Enhanced to show v5.1 fields: height, address, time, difficulty, transaction count.
     *
     * @param block Block to print
     * @param dagKernel DagKernel for querying transaction count
     * @return Formatted string for block list display
     */
    public String printBlockEnhanced(Block block, DagKernel dagKernel) {
        BlockInfo info = block.getInfo();

        if (info == null) {
            return "Block info not available";
        }

        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());

        // Get transaction count from TransactionStore
        int txCount = dagKernel.getTransactionStore().getTransactionsByBlock(block.getHash()).size();

        // Format difficulty (show in hex, truncated)
        String difficultyStr = info.getDifficulty().toBigInteger().toString(16);
        if (difficultyStr.length() > 10) {
            difficultyStr = difficultyStr.substring(0, 10) + "...";
        }

        return String.format("%08d  %s  %s  %-12s  %3d",
                info.getHeight(),
                hash2Address(block.getHash()),
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").format(time),
                difficultyStr,
                txCount);
    }

    /**
     * Print detailed Block info (v5.1 enhanced implementation)
     * <p>
     * Phase 8.1: Block detailed display for block info command.
     * Enhanced to show Link and Transaction details as designed in CLI_REDESIGN_V5.1.md
     * <p>
     * Shows:
     * - Basic block info (height, time, state, hash, difficulty, epoch)
     * - Link details (block references with their heights and epochs)
     * - Transaction details (from TransactionStore)
     *
     * @param block Block to display
     * @param dagKernel DagKernel instance for querying link and transaction details
     * @return Formatted detailed block information
     */
    public String printBlockInfoV5Enhanced(Block block, DagKernel dagKernel) {
        BlockInfo info = block.getInfo();

        if (info == null) {
            return "Block info not available";
        }

        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());
        String state = info.isMainBlock() ? MAIN_STATE : "Orphan";

        StringBuilder output = new StringBuilder();
        output.append("═══════════════════════════════════════════════════\n");
        output.append("Block Information (v5.1)\n");
        output.append("═══════════════════════════════════════════════════\n");
        output.append(String.format("Hash:            %s\n", Hex.toHexString(block.getHash().toArray())));
        output.append(String.format("Timestamp:       %s UTC\n",
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").format(time)));
        output.append(String.format("Epoch:           %d\n", info.getEpoch()));
        output.append(String.format("Height:          %s\n",
                info.isMainBlock() ? String.format("%d (Main Chain)", info.getHeight()) : "N/A (Orphan)"));
        output.append(String.format("State:           %s\n", state));
        output.append("\n");

        output.append("Block Details:\n");
        output.append(String.format("  Difficulty:    %s\n", info.getDifficulty().toBigInteger().toString(16)));
        output.append(String.format("  Coinbase:      %s\n", address2PubAddress(block.getHeader().getCoinbase())));
        output.append("\n");

        // Show Links (Block References)
        List<Link> links = block.getLinks();
        if (!links.isEmpty()) {
            output.append("Links (Block References):\n");
            int blockLinkCount = 0;
            int txLinkCount = 0;

            for (Link link : links) {
                if (link.isBlock()) {
                    blockLinkCount++;
                    // Try to get the linked block details
                    Block linkedBlock = dagKernel.getDagChain().getBlockByHash(link.getTargetHash(), false);
                    if (linkedBlock != null && linkedBlock.getInfo() != null) {
                        output.append(String.format("  → %s (height: %d, epoch: %d)\n",
                                Hex.toHexString(link.getTargetHash().toArray()).substring(0, 16) + "...",
                                linkedBlock.getInfo().getHeight(),
                                linkedBlock.getInfo().getEpoch()));
                    } else {
                        output.append(String.format("  → %s (details unavailable)\n",
                                Hex.toHexString(link.getTargetHash().toArray()).substring(0, 16) + "..."));
                    }
                } else if (link.isTransaction()) {
                    txLinkCount++;
                }
            }
            output.append(String.format("  [Total: %d block links, %d transaction links]\n",
                    blockLinkCount, txLinkCount));
            output.append("\n");
        }

        // Show Transactions
        List<Transaction> transactions = dagKernel.getTransactionStore().getTransactionsByBlock(block.getHash());
        if (!transactions.isEmpty()) {
            output.append("Transactions:\n");
            XAmount totalAmount = XAmount.ZERO;
            XAmount totalFees = XAmount.ZERO;

            // Calculate totals for all transactions first
            for (Transaction tx : transactions) {
                totalAmount = totalAmount.add(tx.getAmount());
                totalFees = totalFees.add(tx.getFee());
            }

            // Display first 10 transactions
            int displayCount = Math.min(transactions.size(), 10);
            for (int i = 0; i < displayCount; i++) {
                Transaction tx = transactions.get(i);
                output.append(String.format("  ✓ %s (from: %s, to: %s, amount: %s XDAG, fee: %s XDAG)\n",
                        tx.getHash().toHexString().substring(0, 16) + "...",
                        tx.getFrom().toHexString().substring(0, 8) + "...",
                        tx.getTo().toHexString().substring(0, 8) + "...",
                        tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        tx.getFee().toDecimal(9, XUnit.XDAG).toPlainString()));
            }

            if (transactions.size() > displayCount) {
                output.append(String.format("  ... and %d more transactions\n",
                        transactions.size() - displayCount));
            }

            output.append(String.format("  [Total: %d transactions, %s XDAG transferred, %s XDAG fees]\n",
                    transactions.size(),
                    totalAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                    totalFees.toDecimal(9, XUnit.XDAG).toPlainString()));
        }

        return output.toString();
    }

    /**
     * Print detailed Block info (v5.1 implementation)
     * <p>
     * Phase 8.1: Block detailed display for block info command.
     * v5.1 minimal design shows only available fields from BlockInfo.
     * <p>
     * Note: Transaction details and history are NOT shown here because:
     * - v5.1 uses Link-based references (not embedded amounts)
     * - Transaction data stored separately in TransactionStore
     * - Would require additional queries to show full transaction details
     *
     * @param block Block to display
     * @return Formatted detailed block information
     * @deprecated Use printBlockInfoV5Enhanced() for richer output
     */
    @Deprecated
    public static String printBlockInfoV5(Block block) {
        BlockInfo info = block.getInfo();

        if (info == null) {
            return "Block info not available";
        }

        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());
        String state = info.isMainBlock() ? MAIN_STATE : "Orphan";

        // v5.1 minimal info display (only BlockInfo fields)
        String format = """
                  height: %s
                    time: %s
               timestamp: %s
                   state: %s
                    hash: %s
              difficulty: %s
                   epoch: %d
                   links: %d
               \s""";

        return String.format(format,
                info.isMainBlock() ? String.format("%08d", info.getHeight()) : "N/A (orphan)",
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(time),
                Long.toHexString(block.getTimestamp()),
                state,
                Hex.toHexString(block.getHash().toArray()),
                info.getDifficulty().toBigInteger().toString(16),
                info.getEpoch(),
                block.getLinks().size());
    }

  /**
     * Get balance for address (Phase 8.1: Fully restored using Transaction APIs)
     * <p>
     * Phase 8.1: Restored both account and block balance lookups.
     * - Account balance: Uses AddressStore (unchanged)
     * - Block balance: Calculates from Transactions using TransactionStore
     *
     * @param address Address to check balance for, or null for total balance
     * @return Formatted balance string
     * @throws AddressFormatException if address format is invalid
     */
    public String balance(String address) throws AddressFormatException {
        if (StringUtils.isEmpty(address)) {
            // Account balance lookup (v5.1: Using AccountStore)
            XAmount ourBalance = XAmount.ZERO;
            List<ECKeyPair> list = dagKernel.getWallet().getAccounts();
            for (ECKeyPair k : list) {
                Bytes addr = Bytes.wrap(toBytesAddress(k));
                ourBalance = ourBalance.add(getAccountBalance(addr));
            }
            return String.format("Balance: %s XDAG", ourBalance.toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            // Check if it's an account address or block address
            if (checkAddress(address)) {
                // Account address (Base58 format)
                Bytes addr = Bytes.wrap(fromBase58(address).toArray());
                XAmount balance = getAccountBalance(addr);
                return String.format("Account balance: %s XDAG", balance.toDecimal(9, XUnit.XDAG).toPlainString());
            } else {
                // Block address - calculate balance from Transactions
                Bytes32 hash;
                if (StringUtils.length(address) == 32) {
                    hash = address2Hash(address);
                } else {
                    hash = getHash(address);
                }

                if (hash == null) {
                    return "Invalid address format";
                }

                // Get Block
                Block block = dagKernel.getDagChain().getBlockByHash(hash, false);
                if (block == null) {
                    return "Block not found";
                }

                // v5.1: Calculate balance from Transactions
                List<Transaction> transactions = dagKernel.getTransactionStore().getTransactionsByBlock(hash);
                XAmount totalAmount = XAmount.ZERO;

                for (Transaction tx : transactions) {
                    // Sum up transaction amounts
                    totalAmount = totalAmount.add(tx.getAmount());
                }

                return String.format("Block balance: %s XDAG (from %d transactions)",
                        totalAmount.toDecimal(9, XUnit.XDAG).toPlainString(),
                        transactions.size());
            }
        }
    }

    public String txQuantity(String address) throws AddressFormatException {
        if (StringUtils.isEmpty(address)) {
            // v5.1: Using nonce from AccountStore (replaces txQuantity)
            long totalNonce = 0;
            List<ECKeyPair> list = dagKernel.getWallet().getAccounts();
            for (ECKeyPair key : list) {
                Bytes addr = Bytes.wrap(toBytesAddress(key));
                totalNonce += getAccountNonce(addr);
            }
            return String.format("Total Transaction Nonce: %d\n", totalNonce);
        } else {
            if (checkAddress(address)) {
                // v5.1: Get nonce for account address
                Bytes addr = Bytes.wrap(fromBase58(address).toArray());
                long nonce = getAccountNonce(addr);
                return String.format("Transaction Nonce: %d\n", nonce);
            } else {
                return "The account address format is incorrect! \n";
            }
        }
    }

  /**
     * Get current blockchain statistics (v5.1 enhanced implementation)
     * <p>
     * Phase 8.1: Restored using v5.1 ChainStats.
     * Enhanced with professional formatting and comprehensive statistics.
     * <p>
     * v5.1 Simplifications:
     * - Uses ChainStats for all chain metrics
     * - P2P statistics temporarily unavailable (pending P2pService implementation)
     * - Shows available metrics: blocks, difficulty, orphans, sync status
     *
     * @return Formatted statistics string with enhanced layout
     */
    public String stats() {
        ChainStats chainStats = dagKernel.getDagChain().getChainStats();

        StringBuilder output = new StringBuilder();
        output.append("═══════════════════════════════════════════════════\n");
        output.append("XDAG Network Statistics (v5.1)\n");
        output.append("═══════════════════════════════════════════════════\n");
        output.append("\n");

        // Chain Status
        output.append("Chain Status:\n");
        output.append(String.format("  Main Chain Length:      %,d blocks\n",
                chainStats.getMainBlockCount()));
        output.append(String.format("  Total Blocks:           %,d\n",
                chainStats.getTotalBlockCount()));
        output.append(String.format("  Current Epoch:          %,d\n",
                dagKernel.getDagChain().getCurrentEpoch()));

        // Show top block info
        if (chainStats.getTopBlock() != null) {
            output.append(String.format("  Top Block Hash:         %s\n",
                    chainStats.getTopBlock().toHexString().substring(0, 16) + "..."));
        }
        output.append("\n");

        // Network Statistics
        output.append("Network:\n");

        // Get P2P statistics (v5.1: Using P2pService if available)
        int connectedHosts = 0;
        int totalHosts = chainStats.getTotalHostCount();
        if (dagKernel.getP2pService() != null) {
            // TODO: Implement P2pService.getActiveChannelCount()
            connectedHosts = 0; // Placeholder
        }
        output.append(String.format("  Connected Peers:        %d of %d\n",
                connectedHosts, totalHosts));

        output.append(String.format("  Orphan Blocks:          %,d\n",
                chainStats.getNoRefCount()));
        output.append(String.format("  Waiting Sync:           %,d\n",
                chainStats.getWaitingSyncCount()));
        output.append(String.format("  Sync Progress:          %.1f%%\n",
                chainStats.getSyncProgress()));
        output.append("\n");

        // Consensus & Difficulty
        output.append("Consensus:\n");
        UInt256 currentDiff = chainStats.getTopDifficulty() != null ?
                chainStats.getTopDifficulty() : UInt256.ZERO;
        UInt256 maxDiff = chainStats.getMaxDifficulty();

        output.append(String.format("  Current Difficulty:     %s\n",
                formatDifficulty(currentDiff)));
        output.append(String.format("  Max Difficulty:         %s\n",
                formatDifficulty(maxDiff)));
        output.append(String.format("  Finality Window:        %,d epochs (≈12 days)\n",
                16384));
        output.append("\n");

        // Wallet Statistics
        output.append("Wallet:\n");
        XAmount totalBalance = XAmount.ZERO;
        int accountCount = 0;
        List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();
        for (ECKeyPair account : accounts) {
            Bytes addr = Bytes.wrap(toBytesAddress(account));
            totalBalance = totalBalance.add(getAccountBalance(addr));
            accountCount++;
        }

        output.append(String.format("  XDAG in Wallets:        %s XDAG\n",
                totalBalance.toDecimal(9, XUnit.XDAG).toPlainString()));
        output.append(String.format("  Number of Wallets:      %d\n",
                accountCount));

        return output.toString();
    }

    /**
     * Format difficulty value for display
     */
    private String formatDifficulty(UInt256 difficulty) {
        String hex = difficulty.toHexString();
        if (hex.length() > 16) {
            return "0x" + hex.substring(0, 16) + "...";
        }
        return "0x" + hex;
    }

    /**
     * Connect to remote node (v5.1: Using P2pService)
     */
    public void connect(String server, int port) {
        if (dagKernel.getP2pService() != null) {
            // TODO: Implement P2pService connect method
            log.warn("P2P connect not yet implemented in v5.1");
        } else {
            log.warn("P2pService not available");
        }
    }

    /**
     * Get block info by hash (Phase 8.1: Enhanced with Link and Transaction details)
     * <p>
     * Phase 8.1: Restored CLI command using Block.
     * Uses getBlockByHash() from Blockchain and printBlockInfoV5Enhanced() for rich display.
     *
     * @param blockhash Block hash to lookup
     * @return Formatted block information with Links and Transactions
     */
    public String block(Bytes32 blockhash) {
        try {
            Block block = dagKernel.getDagChain().getBlockByHash(blockhash, true);
            if (block == null) {
                return "Block not found";
            }
            return printBlockInfoV5Enhanced(block, dagKernel);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "error, please check log";
    }

    /**
     * Get block info by address (Phase 8.1: Enhanced with Link and Transaction details)
     * <p>
     * Phase 8.1: Restored CLI command using Block.
     * Converts address to hash, then uses block(Bytes32).
     *
     * @param address Block address (various formats supported)
     * @return Formatted block information with Links and Transactions
     */
    public String block(String address) {
        Bytes32 hash = address2Hash(address);
        return block(hash);
    }

  /**
     * List main blocks (Phase 8.1: Enhanced with v5.1 formatting)
     * <p>
     * Phase 8.1: Restored CLI command using Block display.
     * Enhanced to show difficulty and transaction count.
     *
     * @param n Number of blocks to list
     * @return Formatted list of main blocks with detailed information
     */
    public String mainblocks(int n) {
        List<Block> blockList = dagKernel.getDagChain().listMainBlocks(n);

        if (CollectionUtils.isEmpty(blockList)) {
            return "No main blocks found";
        }

        StringBuilder output = new StringBuilder();
        output.append("Main Chain Blocks (Latest ").append(blockList.size()).append(")\n");
        output.append(printHeaderBlockList());

        for (Block block : blockList) {
            output.append(printBlockEnhanced(block, dagKernel)).append("\n");
        }

        return output.toString();
    }

    /**
     * List mined blocks (Phase 8.1: Enhanced with v5.1 formatting)
     * <p>
     * Phase 8.1: Restored CLI command using Block display.
     * Enhanced to show difficulty and transaction count.
     *
     * @param n Number of blocks to list
     * @return Formatted list of mined blocks with detailed information
     */
    public String minedBlocks(int n) {
        List<Block> blockList = dagKernel.getDagChain().listMinedBlocks(n);

        if (CollectionUtils.isEmpty(blockList)) {
            return "No mined blocks found";
        }

        StringBuilder output = new StringBuilder();
        output.append("Blocks Mined by This Node (Latest ").append(blockList.size()).append(")\n");
        output.append(printHeaderBlockList());

        for (Block block : blockList) {
            output.append(printBlockEnhanced(block, dagKernel)).append("\n");
        }

        // Calculate total rewards (simplified - assuming 1024 XDAG per block)
        // TODO: Use actual reward calculation from blockchain consensus
        XAmount totalRewards = XAmount.of(blockList.size() * 1024L, XUnit.XDAG);

        output.append("\n");
        output.append(String.format("Total Mined: %d blocks\n", blockList.size()));
        output.append(String.format("Estimated Rewards: %s XDAG\n",
                totalRewards.toDecimal(9, XUnit.XDAG).toPlainString()));

        return output.toString();
    }

    /**
     * Start test mode
     */
    public void run() {
        try {
            dagKernel.start();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Stop test mode
     */
    public void stop() {
      dagKernel.stop();
    }

    /**
     * List active connections (v5.1: Using P2pService)
     */
    public String listConnect() {
        if (dagKernel.getP2pService() != null) {
            // TODO: Implement P2pService channel list
            return "P2P channel listing not yet implemented in v5.1";
        } else {
            return "P2pService not available";
        }
    }

    /**
     * Show websocket channel pool (v5.1: Mining pool disabled)
     */
    public String pool() {
        // TODO: Re-implement pool functionality in v5.1
        return "Mining pool functionality temporarily disabled in v5.1\n" +
               "Will be re-implemented in future version";
    }

    /**
     * Generate new key pair (v5.1: Simplified without XdagState)
     */
    public String keygen() {
        dagKernel.getWallet().addAccountRandom();
        dagKernel.getWallet().flush();
        int size = dagKernel.getWallet().getAccounts().size();
        return "Key " + (size - 1) + " generated and set as default, now key size is: " + size;
    }

    /**
     * Get current XDAG state (v5.1: Using DagChain stats)
     */
    public String state() {
        ChainStats stats = dagKernel.getDagChain().getChainStats();
        return String.format("""
                XDAG State:
                  Main Blocks: %d
                  Total Blocks: %d
                  Difficulty: %s
                  Orphans: %d
                  Waiting Sync: %d""",
                stats.getMainBlockCount(),
                stats.getTotalBlockCount(),
                stats.getDifficulty().toHexString(),
                stats.getNoRefCount(),
                stats.getWaitingSyncCount());
    }

    /**
     * Get epoch information (NEW v5.1 command)
     * <p>
     * CLI Redesign v5.1: New epoch command for querying epoch details.
     * Shows epoch time range, candidates, winner, and consensus information.
     *
     * @param epochNumber Epoch number to query (null for current epoch)
     * @return Formatted epoch information
     */
    public String epoch(Long epochNumber) {
        try {
            // Get epoch number (current if not specified)
            long epoch = epochNumber != null ? epochNumber : dagKernel.getDagChain().getCurrentEpoch();
            long currentEpoch = dagKernel.getDagChain().getCurrentEpoch();
            boolean isCurrent = (epoch == currentEpoch);

            // Get epoch time range
            long[] timeRange = dagKernel.getDagChain().getEpochTimeRange(epoch);
            long startTime = timeRange[0];
            long endTime = timeRange[1];
            long duration = endTime - startTime; // in seconds

            // Get candidate blocks in this epoch
            List<Block> candidates = dagKernel.getDagChain().getCandidateBlocksInEpoch(epoch);

            StringBuilder output = new StringBuilder();
            output.append("═══════════════════════════════════════════════════\n");
            if (isCurrent) {
                output.append("Current Epoch Information\n");
            } else {
                output.append(String.format("Epoch %d Information\n", epoch));
            }
            output.append("═══════════════════════════════════════════════════\n");
            output.append(String.format("Epoch Number:    %d%s\n", epoch, isCurrent ? " (Current)" : ""));

            // Format time range
            long startTimeMs = XdagTime.xdagTimestampToMs(startTime);
            long endTimeMs = XdagTime.xdagTimestampToMs(endTime);
            output.append(String.format("Time Range:      %s - %s\n",
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss").format(startTimeMs),
                    FastDateFormat.getInstance("HH:mm:ss").format(endTimeMs)));
            output.append(String.format("Duration:        %d seconds\n", duration));

            // If current epoch, show progress
            if (isCurrent) {
                long currentTime = XdagTime.getCurrentTimestamp();
                long elapsed = currentTime - startTime;
                double progress = (double) elapsed / duration * 100.0;
                output.append(String.format("Elapsed:         %d / %d seconds (%.1f%%)\n",
                        elapsed, duration, progress));
                output.append(String.format("Next Epoch:      In %d seconds\n", duration - elapsed));
            }

            output.append("\n");
            output.append("Blocks in Epoch:\n");
            output.append(String.format("  Total Candidates:  %d blocks\n", candidates.size()));

            // Find winner and orphans
            Block winner = null;
            List<Block> orphans = new ArrayList<>();
            UInt256 minHash = UInt256.MAX_VALUE;

            for (Block block : candidates) {
                // Winner is the block with smallest hash
                UInt256 blockHash = UInt256.fromBytes(block.getHash());
                if (blockHash.compareTo(minHash) < 0) {
                    if (winner != null) {
                        orphans.add(winner);
                    }
                    winner = block;
                    minHash = blockHash;
                } else {
                    orphans.add(block);
                }
            }

            if (winner != null) {
                output.append(String.format("  Winner:            %s (height: %d)\n",
                        Hex.toHexString(winner.getHash().toArray()).substring(0, 16) + "...",
                        winner.getInfo().getHeight()));
            } else {
                output.append("  Winner:            Not yet determined\n");
            }
            output.append(String.format("  Orphans:           %d blocks\n", orphans.size()));

            // Show consensus details
            output.append("\n");
            output.append("Consensus:\n");
            output.append("  Selection Rule:    Smallest hash wins\n");

            if (!candidates.isEmpty()) {
                // Calculate average difficulty
                UInt256 totalDifficulty = UInt256.ZERO;
                for (Block block : candidates) {
                    totalDifficulty = totalDifficulty.add(block.getInfo().getDifficulty());
                }
                UInt256 avgDifficulty = totalDifficulty.divide(candidates.size());
                output.append(String.format("  Average Difficulty: %s\n",
                        avgDifficulty.toBigInteger().toString(16)));
                output.append(String.format("  Total Difficulty:   %s\n",
                        totalDifficulty.toBigInteger().toString(16)));
            }

            return output.toString();

        } catch (Exception e) {
          log.error("epoch command failed: {}", e.getMessage(), e);
          return "Error querying epoch information: " + e.getMessage();
        }
    }

    /**
     * Get maximum transferable balance
     */
    public String balanceMaxXfer() {
        return getBalanceMaxXfer(dagKernel);
    }

    /**
     * Calculate maximum transferable balance (Phase 8.1: Using AccountStore)
     * <p>
     * v5.1: Simplified implementation using account balances from AccountStore.
     * In v5.1, balances are tracked by addresses (not blocks), so we sum
     * confirmed address balances directly.
     *
     * @param dagKernel Kernel instance
     * @return Formatted maximum transferable balance
     */
    public static String getBalanceMaxXfer(DagKernel dagKernel) {
        // v5.1: Sum up all account balances
        XAmount totalBalance = XAmount.ZERO;
        List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();

        for (ECKeyPair account : accounts) {
            Bytes address = Bytes.wrap(toBytesAddress(account));
            UInt256 balance = dagKernel.getAccountStore().getBalance(address);
            totalBalance = totalBalance.add(uint256ToXAmount(balance));
        }

        return String.format("%s", totalBalance.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    /**
     * Get address transaction history (Phase 8.1: Restored using Transaction APIs)
     * <p>
     * Phase 8.1: Restored address transaction history using TransactionStore.
     * Shows all transactions involving the address (sent or received).
     * <p>
     * Note: Pagination not yet implemented in v5.1 TransactionStore.
     * Future enhancement: Add paging support to TransactionStore.getTransactionsByAddress()
     *
     * @param wrap Address bytes (20 bytes)
     * @param page Page number (currently unused - shows all transactions)
     * @return Formatted transaction history
     */
    public String address(Bytes wrap, int page) {
        // Get address balance (v5.1: Using AccountStore)
        XAmount balance = getAccountBalance(wrap);

        String overview = " OverView\n" +
                String.format(" address: %s\n", address2PubAddress(wrap)) +
                String.format(" balance: %s\n", balance.toDecimal(9, XUnit.XDAG).toPlainString());

        // Get transaction history
        List<Transaction> transactions = dagKernel.getTransactionStore().getTransactionsByAddress(wrap);

        if (transactions.isEmpty()) {
            return overview + "\nNo transaction history found.";
        }

        StringBuilder txHistory = new StringBuilder();
        txHistory.append("-----------------------------------------------------------------------------------------------------------------------------\n");
        txHistory.append("                               histories of address: details\n");
        txHistory.append(" direction  address                                    amount                 time\n");
        txHistory.append("-----------------------------------------------------------------------------------------------------------------------------\n");

        for (Transaction tx : transactions) {
            // Determine direction
            String direction;
            org.apache.tuweni.bytes.Bytes otherAddress;
            if (tx.getFrom().equals(wrap)) {
                direction = "   output";
                otherAddress = tx.getTo();
            } else {
                direction = "    input";
                otherAddress = tx.getFrom();
            }

            // Phase 9.1: Get transaction timestamp from containing block
            String timeStr = "";
            Bytes32 blockHash = dagKernel.getTransactionStore().getBlockByTransaction(tx.getHash());
            if (blockHash != null) {
                Block block = dagKernel.getDagChain().getBlockByHash(blockHash, false);
                if (block != null) {
                    long timestamp = XdagTime.xdagTimestampToMs(block.getTimestamp());
                    timeStr = FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(timestamp);
                }
            }

            // If timestamp not found, show tx hash instead
            if (timeStr.isEmpty()) {
                timeStr = tx.getHash().toHexString().substring(0, 16) + "...";
            }

            String addressStr = otherAddress != null ? address2PubAddress(otherAddress) : "UNKNOWN";

            txHistory.append(String.format("%s: %s           %s   %s\n",
                    direction,
                    addressStr,
                    tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                    timeStr));
        }

        // Phase 9.1: Transaction timestamp lookup implemented using reverse index
        return overview + "\n" + txHistory;
    }

  // ========== Phase 4 Layer 3: v5.1 Transaction Methods ==========

    /**
     * Transfer XDAG to another address
     * <p>
     * Uses v5.1 Transaction + Block architecture with configurable fee and remark support.
     * This is a convenience method that uses the default fee of 100 milli-XDAG.
     *
     * @param sendAmount Amount to send
     * @param toAddress Recipient address (Base58 encoded)
     * @param remark Optional transaction remark (encoded to Transaction.data field)
     * @return Transaction result message
     */
    public String transfer(double sendAmount, String toAddress, String remark) {
        // Use default fee of 100 milli-XDAG
        return transfer(sendAmount, toAddress, remark, 100.0);
    }

    /**
     * Transfer XDAG to another address (full implementation)
     * <p>
     * Uses v5.1 Transaction + Block architecture with configurable fee and remark support.
     * <p>
     * Key features:
     * 1. Uses Transaction instead of legacy Address
     * 2. Uses Block for transaction container
     * 3. Uses Link to reference Transaction
     * 4. Stores Transaction in TransactionStore
     * 5. Supports configurable fee
     * 6. Properly encodes remark to Transaction.data field
     * 7. Simplified: only single-account transfers (no batch)
     *
     * @param sendAmount Amount to send
     * @param toAddress Recipient address (Base58 encoded)
     * @param remark Optional transaction remark (encoded to Transaction.data field)
     * @param feeMilliXdag Transaction fee in milli-XDAG (e.g., 100.0 = 0.1 XDAG)
     * @return Transaction result message
     */
    public String transfer(double sendAmount, String toAddress, String remark, double feeMilliXdag) {
        try {
            // Convert amount
            XAmount amount = XAmount.of(BigDecimal.valueOf(sendAmount), XUnit.XDAG);
            XAmount fee = XAmount.of(BigDecimal.valueOf(feeMilliXdag), XUnit.MILLI_XDAG);
            XAmount totalRequired = amount.add(fee);

            // Parse recipient address (only Base58 format supported for account transfers)
            Bytes to;
            if (checkAddress(toAddress)) {
                // Base58 address format
                to = AddressUtils.fromBase58Address(toAddress);
            } else {
                return "Invalid recipient address format. Please use Base58 address format.";
            }

            // Find account with sufficient balance (v5.1: Using AccountStore)
            ECKeyPair fromAccount = null;
            Bytes fromAddress = null;
            long currentNonce = 0;

            for (ECKeyPair account : dagKernel.getWallet().getAccounts()) {
                Bytes addr = Bytes.wrap(toBytesAddress(account));
                XAmount balance = getAccountBalance(addr);

                if (balance.compareTo(totalRequired) >= 0) {
                    fromAccount = account;
                    fromAddress = Bytes.wrap(toBytesAddress(account));
                    // Get current nonce (v5.1: Using AccountStore)
                    currentNonce = getAccountNonce(addr) + 1;
                    break;
                }
            }

            if (fromAccount == null) {
                return "Balance not enough. Need " + totalRequired.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG";
            }

            // Phase 2 Task 2.1: Process remark and encode to Transaction.data field
            Bytes remarkData = Bytes.EMPTY;
            if (remark != null && !remark.isEmpty()) {
                // Encode remark as UTF-8 bytes
                remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));
            }

            // Create Transaction
            Transaction tx = Transaction.builder()
                    .from(fromAddress)
                    .to(to)
                    .amount(amount)
                    .nonce(currentNonce)
                    .fee(fee)
                    .data(remarkData)  // Phase 2 Task 2.1: Encoded remark
                    .build();

            // Sign Transaction
            Transaction signedTx = tx.sign(fromAccount);

            // Validate Transaction
            if (!signedTx.isValid()) {
                return "Transaction validation failed.";
            }

            if (!signedTx.verifySignature()) {
                return "Transaction signature verification failed.";
            }

            // Save Transaction to TransactionStore
            dagKernel.getTransactionStore().saveTransaction(signedTx);

            // Create Block with Transaction link
            List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));

            // Create BlockHeader
            BlockHeader header = BlockHeader.builder()
                    .timestamp(XdagTime.getCurrentTimestamp())
                    .difficulty(UInt256.ZERO)
                    .nonce(Bytes32.ZERO)
                    .coinbase(fromAddress)
                    .hash(null)  // Will be calculated by Block.getHash()
                    .build();

            // Create Block
            Block block = Block.builder()
                    .header(header)
                    .links(links)
                    .info(null)  // Will be initialized by tryToConnectV2()
                    .build();

            // Validate and add block (v5.1: DagImportResult)
            DagImportResult result = dagKernel.getDagChain().tryToConnect(block);

            if (result.isMainBlock()) {
                // Update nonce in AccountStore (v5.1)
                Bytes fromAddr = Bytes.wrap(toBytesAddress(fromAccount));
                updateAccountNonce(fromAddr, currentNonce);

                // v5.1: Broadcast block using P2pService
                if (dagKernel.getP2pService() != null) {
                    // TODO: Implement P2pService broadcast method
                    log.info("Block broadcast not yet implemented in v5.1: {}", hash2Address(block.getHash()));
                }

                // Phase 2 Task 2.1: Build success message with optional remark
                StringBuilder successMsg = new StringBuilder();
                successMsg.append("Transaction created successfully!\n");
                successMsg.append(String.format("  Transaction hash: %s\n",
                        signedTx.getHash().toHexString().substring(0, 16) + "..."));
                successMsg.append(String.format("  Block hash: %s\n",
                        hash2Address(block.getHash())));
                successMsg.append(String.format("  From: %s\n",
                        fromAddress.toHexString().substring(0, 16) + "..."));
                successMsg.append(String.format("  To: %s\n",
                        to.toHexString().substring(0, 16) + "..."));
                successMsg.append(String.format("  Amount: %s XDAG\n",
                        amount.toDecimal(9, XUnit.XDAG).toPlainString()));
                successMsg.append(String.format("  Fee: %s XDAG\n",
                        fee.toDecimal(9, XUnit.XDAG).toPlainString()));

                // Show remark if present
                if (remark != null && !remark.isEmpty()) {
                    successMsg.append(String.format("  Remark: %s\n", remark));
                }

                successMsg.append(String.format("  Nonce: %d\n", currentNonce));
                successMsg.append(String.format("  Status: %s\n", result.getStatus()));

                return successMsg.toString();
            } else {
                return String.format(
                    """
                        Transaction failed!
                          Result: %s
                          Error: %s""",
                    result.getStatus(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error"
                );
            }

        } catch (Exception e) {
          log.error("transfer failed: {}", e.getMessage(), e);
          return "Transaction failed: " + e.getMessage();
        }
    }

    /**
     * Consolidate account balances to default address
     * <p>
     * v5.1 implementation using address balances and Transaction architecture.
     * Transfers all account balances to the default key address.
     * <p>
     * v5.1 Features:
     * 1. Uses address balances from AccountStore
     * 2. AddressStore handles confirmation logic automatically
     * 3. Creates Transaction objects for each transfer
     * 4. Uses Block for transaction broadcast
     *
     * @return Transaction result message
     */
    public String consolidate() {
        try {
            StringBuilder result = new StringBuilder();
            result.append("Account Balance Transfer (v5.1):\n\n");

            // Target address (default key)
            Bytes toAddress = Bytes.wrap(toBytesAddress(dagKernel.getWallet().getDefKey()));
            String remark = "account balance to new address";

            // v5.1: Collect address balances directly using AccountStore
            Map<Integer, XAmount> accountBalances = new HashMap<>();
            List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();

            for (int i = 0; i < accounts.size(); i++) {
                ECKeyPair account = accounts.get(i);
                Bytes address = Bytes.wrap(toBytesAddress(account));
                XAmount balance = getAccountBalance(address);

                if (balance.compareTo(XAmount.ZERO) > 0) {
                    accountBalances.put(i, balance);
                }
            }

            if (accountBalances.isEmpty()) {
                return "No account balances available for transfer.";
            }

            result.append(String.format("Found %d accounts with confirmed balances\n\n", accountBalances.size()));

            // Transfer each account's balance using xferV2
            int successCount = 0;
            XAmount totalTransferred = XAmount.ZERO;

            for (Map.Entry<Integer, XAmount> entry : accountBalances.entrySet()) {
                int accountIndex = entry.getKey();
                XAmount balance = entry.getValue();

                // Skip if balance is too small (less than fee)
                XAmount fee = XAmount.of(100, XUnit.MILLI_XDAG);
                if (balance.compareTo(fee) <= 0) {
                    result.append(String.format("  Account %d: %.9f XDAG (too small, skipped)\n",
                            accountIndex, balance.toDecimal(9, XUnit.XDAG).doubleValue()));
                    continue;
                }

                // Calculate transfer amount (balance - fee)
                XAmount transferAmount = balance.subtract(fee);

                // Get account key
                ECKeyPair fromAccount = dagKernel.getWallet().getAccounts().get(accountIndex);
                Bytes fromAddress = Bytes.wrap(toBytesAddress(fromAccount));

                // Get current nonce (v5.1: Using AccountStore)
                Bytes addr = Bytes.wrap(toBytesAddress(fromAccount));
                long currentNonce = getAccountNonce(addr) + 1;

                // Create Transaction
                Bytes remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));
                Transaction tx = Transaction.builder()
                        .from(fromAddress)
                        .to(toAddress)
                        .amount(transferAmount)
                        .nonce(currentNonce)
                        .fee(fee)
                        .data(remarkData)
                        .build();

                // Sign Transaction
                Transaction signedTx = tx.sign(fromAccount);

                // Validate Transaction
                if (!signedTx.isValid() || !signedTx.verifySignature()) {
                    result.append(String.format("  Account %d: %.9f XDAG (validation failed)\n",
                            accountIndex, balance.toDecimal(9, XUnit.XDAG).doubleValue()));
                    continue;
                }

                // Save Transaction to TransactionStore
                dagKernel.getTransactionStore().saveTransaction(signedTx);

                // Create Block with Transaction link
                List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));

                BlockHeader header = BlockHeader.builder()
                        .timestamp(XdagTime.getCurrentTimestamp())
                        .difficulty(UInt256.ZERO)
                        .nonce(Bytes32.ZERO)
                        .coinbase(fromAddress)
                        .hash(null)
                        .build();

                Block block = Block.builder()
                        .header(header)
                        .links(links)
                        .info(null)
                        .build();

                // Validate and add block (v5.1: DagImportResult)
                DagImportResult importResult = dagKernel.getDagChain().tryToConnect(block);

                if (importResult.isMainBlock()) {
                    // Update nonce (v5.1: Using AccountStore)
                    updateAccountNonce(addr, currentNonce);

                    // Broadcast (v5.1: Using P2pService)
                    if (dagKernel.getP2pService() != null) {
                        // TODO: Implement P2pService broadcast method
                        log.info("Block broadcast not yet implemented in v5.1: {}", hash2Address(block.getHash()));
                    }

                    // Update stats
                    successCount++;
                    totalTransferred = totalTransferred.add(transferAmount);

                    result.append(String.format("  Account %d: %.9f XDAG → %.9f XDAG (✅ %s)\n",
                            accountIndex,
                            balance.toDecimal(9, XUnit.XDAG).doubleValue(),
                            transferAmount.toDecimal(9, XUnit.XDAG).doubleValue(),
                            hash2Address(block.getHash())));
                } else {
                    result.append(String.format("  Account %d: %.9f XDAG (❌ %s)\n",
                            accountIndex,
                            balance.toDecimal(9, XUnit.XDAG).doubleValue(),
                            importResult.getStatus()));
                }
            }

            result.append("\nSummary:\n");
            result.append(String.format("  Successful transfers: %d\n", successCount));
            result.append(String.format("  Total transferred: %.9f XDAG\n",
                    totalTransferred.toDecimal(9, XUnit.XDAG).doubleValue()));
            result.append("\nIt will take several minutes to complete the transactions.");

            return result.toString();

        } catch (Exception e) {
          log.error("consolidate failed: {}", e.getMessage(), e);
          return "Account balance consolidation failed: " + e.getMessage();
        }
    }
}
