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

import static io.xdag.config.Constants.BI_APPLIED;
import static io.xdag.config.Constants.BI_MAIN;
import static io.xdag.config.Constants.BI_MAIN_CHAIN;
import static io.xdag.config.Constants.BI_MAIN_REF;
import static io.xdag.config.Constants.BI_OURS;
import static io.xdag.config.Constants.BI_REF;
import static io.xdag.config.Constants.BI_REMARK;
import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.BasicUtils.address2Hash;
import static io.xdag.utils.BasicUtils.compareAmountTo;
import static io.xdag.utils.BasicUtils.getHash;
import static io.xdag.utils.BasicUtils.hash2Address;
import static io.xdag.utils.BasicUtils.hash2byte;
import static io.xdag.utils.BasicUtils.keyPair2Hash;
import static io.xdag.utils.BasicUtils.pubAddress2Hash;
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
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.p2p.channel.Channel;
import io.xdag.pool.ChannelSupervise;
import io.xdag.utils.XdagTime;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private static final String ACCEPTED_STATE = "Accepted";
    private static final String REJECTED_STATE = "Rejected";
    private static final String PENDING_STATE = "Pending";

    public Commands(DagKernel dagKernel) {
        this.dagKernel = dagKernel;
    }

    // ========== v5.1 AccountStore Helper Methods ==========

    /**
     * Convert XAmount to UInt256 for AccountStore
     */
    private static UInt256 xAmountToUInt256(XAmount amount) {
        // XAmount stores nano units (1 XDAG = 10^9 nano)
        // UInt256 stores wei units (1 XDAG = 10^9 wei)
        return UInt256.valueOf(amount.toXAmount().toLong());
    }

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
    private XAmount getAccountBalance(Bytes32 address) {
        UInt256 balance = dagKernel.getAccountStore().getBalance(address);
        return uint256ToXAmount(balance);
    }

    /**
     * Get account nonce from AccountStore
     */
    private long getAccountNonce(Bytes32 address) {
        return dagKernel.getAccountStore().getNonce(address).toLong();
    }

    /**
     * Update account nonce in AccountStore
     */
    private void updateAccountNonce(Bytes32 address, long nonce) {
        dagKernel.getAccountStore().setNonce(address, UInt64.valueOf(nonce));
    }

    /**
     * List addresses, balances and nonces (v5.1 implementation)
     *
     * Phase 8.1: Restored using v5.1 AccountStore.
     * Shows account addresses with balances and transaction nonces.
     *
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
            Bytes32 addr1 = Bytes32.wrap(toBytesAddress(o1).toArray());
            Bytes32 addr2 = Bytes32.wrap(toBytesAddress(o2).toArray());
            XAmount balance1 = getAccountBalance(addr1);
            XAmount balance2 = getAccountBalance(addr2);
            return balance2.compareTo(balance1); // Descending order
        });

        for (ECKeyPair keyPair : list) {
            if (num == 0) {
                break;
            }

            Bytes32 addr = Bytes32.wrap(toBytesAddress(keyPair).toArray());
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
     * Print header for block list display
     */
    public static String printHeaderBlockList() {
        return """
                ---------------------------------------------------------------------------------------------------------
                height        address                            time                      state     mined by           \s
                ---------------------------------------------------------------------------------------------------------
                """;
    }

    // ========== Phase 8.1: Block Display Methods ==========

    /**
     * Print Block in list format (v5.1 implementation)
     *
     * Phase 8.1: Block display for CLI commands.
     * Shows minimal v5.1 BlockInfo fields (hash, height, timestamp).
     *
     * @param block Block to print
     * @return Formatted string for block list display
     */
    public static String printBlock(Block block) {
        return printBlock(block, false);
    }

    /**
     * Print Block with optional address-only mode (v5.1 implementation)
     *
     * Phase 8.1: Block display for CLI commands.
     * v5.1 minimal design: Only hash, height, timestamp, difficulty available.
     * No flags, amount, fee, or remark in v5.1 BlockInfo.
     *
     * @param block Block to print
     * @param print_only_addresses If true, print only address and height
     * @return Formatted string for block list display
     */
    public static String printBlock(Block block, boolean print_only_addresses) {
        StringBuilder sbd = new StringBuilder();
        BlockInfo info = block.getInfo();

        if (info == null) {
            return "Block info not available";
        }

        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());

        if (print_only_addresses) {
            sbd.append(String.format("%s   %08d",
                    hash2Address(block.getHash()),
                    info.getHeight()));
        } else {
            // v5.1: Simplified state (Main if height > 0, Orphan if height = 0)
            String state = info.isMainBlock() ? MAIN_STATE : "Orphan";

            sbd.append(String.format("%08d   %s   %s   %-8s  %-32s",
                    info.getHeight(),
                    hash2Address(block.getHash()),
                    FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(time),
                    state,
                    ""));  // v5.1: No remark field in minimal BlockInfo
        }
        return sbd.toString();
    }

    /**
     * Print detailed Block info (v5.1 implementation)
     *
     * Phase 8.1: Block detailed display for block info command.
     * v5.1 minimal design shows only available fields from BlockInfo.
     *
     * Note: Transaction details and history are NOT shown here because:
     * - v5.1 uses Link-based references (not embedded amounts)
     * - Transaction data stored separately in TransactionStore
     * - Would require additional queries to show full transaction details
     *
     * @param block Block to display
     * @return Formatted detailed block information
     */
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
                """;

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
     * Get block state description from flags
     */
    public static String getStateByFlags(int flags) {
        int flag = flags & ~(BI_OURS | BI_REMARK);
        // 1F
        if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED | BI_MAIN | BI_MAIN_CHAIN)) {
            return MAIN_STATE;
        }
        // 1C
        if (flag == (BI_REF | BI_MAIN_REF | BI_APPLIED)) {
            return ACCEPTED_STATE;
        }
        // 18
        if (flag == (BI_REF | BI_MAIN_REF)) {
            return REJECTED_STATE;
        }
        return PENDING_STATE;
    }

//    /**
//     * List addresses, balances and current transaction quantity
//     * @param num Number of addresses to display
//     */
//    public String account(int num) {
//        StringBuilder str = new StringBuilder();
//        List<ECKeyPair> list = dagKernel.getWallet().getAccounts();
//
//        // Sort by balance descending, then by key index descending
//        list.sort((o1, o2) -> {
//            int compareResult = compareAmountTo(dagKernel.getAddressStore().getBalanceByAddress(toBytesAddress(o2).toArray()),
//                dagKernel.getAddressStore().getBalanceByAddress(toBytesAddress(o1).toArray()));
//            if (compareResult >= 0) {
//                return 1;
//            } else {
//                return -1;
//            }
//
//        });
//
//        for (ECKeyPair keyPair : list) {
//            if (num == 0) {
//                break;
//            }
//
//            UInt64 txQuantity = dagKernel.getAddressStore().getTxQuantity(toBytesAddress(keyPair).toArray());
//            UInt64 exeTxNonceNum = dagKernel.getAddressStore().getExecutedNonceNum(toBytesAddress(keyPair).toArray());
//
//            str.append(AddressUtils.toBase58Address(keyPair))
//                    .append(" ")
//                    .append(dagKernel.getAddressStore().getBalanceByAddress(toBytesAddress(keyPair).toArray()).toDecimal(9, XUnit.XDAG).toPlainString())
//                    .append(" XDAG")
//                    .append("  [Current TX Quantity: ")
//                    .append(txQuantity.toUInt64())
//                    .append(", Confirmed TX Quantity: ")
//                    .append(exeTxNonceNum.toUInt64())
//                    .append("]")
//                    .append("\n");
//            num--;
//        }
//
//        return str.toString();
//    }

    /**
     * Get balance for address (Phase 8.1: Fully restored using Transaction APIs)
     *
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
                Bytes32 addr = Bytes32.wrap(toBytesAddress(k).toArray());
                ourBalance = ourBalance.add(getAccountBalance(addr));
            }
            return String.format("Balance: %s XDAG", ourBalance.toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            // Check if it's an account address or block address
            if (checkAddress(address)) {
                // Account address (Base58 format)
                Bytes32 addr = Bytes32.wrap(fromBase58(address).toArray());
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
                Bytes32 addr = Bytes32.wrap(toBytesAddress(key).toArray());
                totalNonce += getAccountNonce(addr);
            }
            return String.format("Total Transaction Nonce: %d\n", totalNonce);
        } else {
            if (checkAddress(address)) {
                // v5.1: Get nonce for account address
                Bytes32 addr = Bytes32.wrap(fromBase58(address).toArray());
                long nonce = getAccountNonce(addr);
                return String.format("Transaction Nonce: %d\n", nonce);
            } else {
                return "The account address format is incorrect! \n";
            }
        }
    }

//    /**
//     * Get current blockchain stats
//     */
//    public String stats() {
//        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
//        // Phase 7.3.1: XdagTopStatus merged into ChainStats (deleted)
//        ChainStats chainStats = dagKernel.getDagChain().getChainStats();
//
//        // Calculate difficulties
//        BigInteger currentDiff = chainStats.getTopDifficulty() != null ? chainStats.getTopDifficulty().toBigInteger() : BigInteger.ZERO;
//        BigInteger netDiff = chainStats.getMaxDifficulty().toBigInteger();
//        BigInteger maxDiff = netDiff.max(currentDiff);
//
//        // Get finalized store statistics (Phase 2 refactor)
//        String finalizedStats = "";
//        if (dagKernel.getFinalizedBlockStore() != null) {
//            long totalBlocks = dagKernel.getFinalizedBlockStore().getTotalBlockCount();
//            long totalMain = dagKernel.getFinalizedBlockStore().getTotalMainBlockCount();
//            long storageSize = dagKernel.getFinalizedBlockStore().getStorageSize();
//
//            finalizedStats = String.format("""
//
//                            Finalized Storage:
//                         finalized blocks: %d
//                           finalized main: %d
//                          storage size MB: %d""",
//                    totalBlocks,
//                    totalMain,
//                    storageSize / (1024 * 1024));
//        }
//
//        return String.format("""
//                        Statistics for ours and maximum known parameters:
//                                    hosts: %d of %d
//                                   blocks: %d of %d
//                              main blocks: %d of %d
//                             extra blocks: %d
//                            orphan blocks: %d
//                         wait sync blocks: %d
//                         chain difficulty: %s of %s
//                              XDAG supply: %s of %s
//                          XDAG in address: %s
//                        4 hr hashrate KHs: %.9f of %.9f
//                        Number of Address: %d%s""",
//                dagKernel.getNetDB().getSize(), kernel.getNetDBMgr().getWhiteDB().getSize(),
//                chainStats.getTotalBlockCount(), Math.max(chainStats.getTotalBlockCount(), chainStats.getTotalBlockCount()),
//                chainStats.getMainBlockCount(), Math.max(chainStats.getTotalMainBlockCount(), chainStats.getMainBlockCount()),
//                chainStats.getExtraCount(),
//                chainStats.getNoRefCount(),
//                chainStats.getWaitingSyncCount(),
//                currentDiff.toString(16),
//                maxDiff.toString(16),
//                kernel.getBlockchain().getSupply(chainStats.getMainBlockCount()).toDecimal(9, XUnit.XDAG).toPlainString(),
//                kernel.getBlockchain().getSupply(Math.max(chainStats.getMainBlockCount(), chainStats.getTotalMainBlockCount())).toDecimal(9, XUnit.XDAG).toPlainString(),
//                kernel.getAddressStore().getAllBalance().toDecimal(9, XUnit.XDAG).toPlainString(),
//                kernel.getAddressStore().getAddressSize().toLong(),
//                finalizedStats
//        );
//    }

    /**
     * Get current blockchain statistics (v5.1 implementation)
     *
     * Phase 8.1: Restored using v5.1 ChainStats.
     * Shows comprehensive blockchain statistics.
     *
     * v5.1 Simplifications:
     * - Uses ChainStats for all chain metrics
     * - P2P statistics temporarily unavailable (pending P2pService implementation)
     * - Finalized block statistics temporarily unavailable (pending implementation)
     * - Supply calculation temporarily unavailable (pending implementation)
     * - Shows available metrics: blocks, difficulty, orphans, sync status
     *
     * @return Formatted statistics string
     */
    public String stats() {
        ChainStats chainStats = dagKernel.getDagChain().getChainStats();

        // Calculate difficulties
        UInt256 currentDiff = chainStats.getTopDifficulty() != null ?
                chainStats.getTopDifficulty() : UInt256.ZERO;
        UInt256 netDiff = chainStats.getMaxDifficulty();
        UInt256 maxDiff = netDiff.compareTo(currentDiff) > 0 ? netDiff : currentDiff;

        // Get P2P statistics (v5.1: Using P2pService if available)
        int connectedHosts = 0;
        int totalHosts = chainStats.getTotalHostCount();
        if (dagKernel.getP2pService() != null) {
            // TODO: Implement P2pService.getActiveChannelCount()
            // connectedHosts = dagKernel.getP2pService().getActiveChannelCount();
            connectedHosts = 0; // Placeholder
        }

        // Get account balance totals (v5.1: Using AccountStore)
        XAmount totalBalance = XAmount.ZERO;
        int accountCount = 0;
        List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();
        for (ECKeyPair account : accounts) {
            Bytes32 addr = Bytes32.wrap(toBytesAddress(account).toArray());
            totalBalance = totalBalance.add(getAccountBalance(addr));
            accountCount++;
        }

        return String.format("""
                        Statistics for ours and maximum known parameters:
                                    hosts: %d of %d
                                   blocks: %d of %d
                              main blocks: %d of %d
                             extra blocks: %d
                            orphan blocks: %d
                         wait sync blocks: %d
                         chain difficulty: %s of %s
                          XDAG in wallets: %s
                        Number of wallets: %d
                              Sync status: %.1f%%""",
                connectedHosts, totalHosts,
                chainStats.getTotalBlockCount(), Math.max(chainStats.getTotalBlockCount(), chainStats.getTotalBlockCount()),
                chainStats.getMainBlockCount(), Math.max(chainStats.getTotalMainBlockCount(), chainStats.getMainBlockCount()),
                chainStats.getExtraCount(),
                chainStats.getNoRefCount(),
                chainStats.getWaitingSyncCount(),
                currentDiff.toHexString(),
                maxDiff.toHexString(),
                totalBalance.toDecimal(9, XUnit.XDAG).toPlainString(),
                accountCount,
                chainStats.getSyncProgress()
        );
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
     * Get block info by hash (Phase 8.1: Restored using Block)
     *
     * Phase 8.1: Restored CLI command using Block.
     * Uses getBlockByHash() from Blockchain and printBlockInfoV5() for display.
     *
     * @param blockhash Block hash to lookup
     * @return Formatted block information
     */
    public String block(Bytes32 blockhash) {
        try {
            Block block = dagKernel.getDagChain().getBlockByHash(blockhash, true);
            if (block == null) {
                return "Block not found";
            }
            return printBlockInfoV5(block);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return "error, please check log";
    }

    /**
     * Get block info by address (Phase 8.1: Restored using Block)
     *
     * Phase 8.1: Restored CLI command using Block.
     * Converts address to hash, then uses block(Bytes32).
     *
     * @param address Block address (various formats supported)
     * @return Formatted block information
     */
    public String block(String address) {
        Bytes32 hash = address2Hash(address);
        return block(hash);
    }

    // Phase 9.3: printBlockInfo() deprecated in v5.1 (uses Block, Address, TxHistory which no longer exist)
    // Replaced by printBlockInfoV5() which uses Block and TransactionStore
    /*
    public String printBlockInfo(Block block, boolean raw) {
        block.parse();
        long time = XdagTime.xdagTimestampToMs(block.getTimestamp());
        String heightFormat = ((block.getInfo().getFlags() & BI_MAIN) == 0 ? "" : "    height: %08d\n");
        String otherFormat = """
                      time: %s
                 timestamp: %s
                     flags: %s
                     state: %s
                      hash: %s
                    remark: %s
                difficulty: %s
                   balance: %s  %s
                -----------------------------------------------------------------------------------------------------------------------------
                                               block as transaction: details
                 direction  address                                    amount
                       fee: %s           %s""";
        StringBuilder inputs = null;
        StringBuilder outputs = null;
        if (raw) {
            if (!block.getInputs().isEmpty()) {
                inputs = new StringBuilder();
                for (Address input : block.getInputs()) {
                    inputs.append(String.format("     input: %s           %s%n",
                            input.getIsAddress() ? Base58.encodeCheck(hash2byte(input.getAddress())) : hash2Address(input.getAddress()),
                            input.getAmount().toDecimal(9, XUnit.XDAG).toPlainString()
                    ));
                }
            }
            if (!block.getOutputs().isEmpty()) {
                outputs = new StringBuilder();
                for (Address output : block.getOutputs()) {
                    if (output.getType().equals(XDAG_FIELD_COINBASE)) continue;
                    outputs.append(String.format("    output: %s           %s%n",
                            output.getIsAddress() ? Base58.encodeCheck(
                                hash2byte(output.getAddress())) : hash2Address(output.getAddress()),
                            getStateByFlags(block.getInfo().getFlags()).equals(MAIN_STATE) ? output.getAmount().toDecimal(9, XUnit.XDAG).toPlainString() :
                                    block.getInputs().isEmpty() ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                                            output.getAmount().subtract(MIN_GAS).toDecimal(9, XUnit.XDAG).toPlainString()
                    ));
                }
            }
        }

        String txHisFormat = """
                -----------------------------------------------------------------------------------------------------------------------------
                                               block as address: details
                 direction  address                                    amount                 time
                """;
        StringBuilder tx = new StringBuilder();
        if (getStateByFlags(block.getInfo().getFlags()).equals(MAIN_STATE) && block.getInfo().getHeight() > kernel.getConfig().getSnapshotSpec().getSnapshotHeight()) {
            tx.append(String.format("    earn: %s           %s   %s%n", hash2Address(block.getHash()),
                            kernel.getBlockchain().getReward(block.getInfo().getHeight()).toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(XdagTime.xdagTimestampToMs(block.getTimestamp()))))
                    .append(String.format("fee earn: %s           %s   %s%n", hash2Address(block.getHash()),
                            kernel.getBlockStore().getBlockInfoByHash(block.getHash()).getFee().toDecimal(9, XUnit.XDAG).toPlainString(),
                            FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                    .format(XdagTime.xdagTimestampToMs(block.getTimestamp()))));
        }
        for (TxHistory txHistory : kernel.getBlockchain().getBlockTxHistoryByAddress(block.getHash(), 1)) {
            Address address = txHistory.getAddress();
            BlockInfo blockInfo = kernel.getBlockchain().getBlockByHash(address.getAddress(), false).getInfo();
            if ((blockInfo.getFlags() & BI_APPLIED) == 0) {
                continue;
            }
            if (address.getType().equals(XDAG_FIELD_IN)) {
                tx.append(String.format("    input: %s           %s  %s%n", hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            } else if (address.getType().equals(XDAG_FIELD_OUT)) {
                tx.append(String.format("   output: %s           %s   %s%n", hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            } else {
                tx.append(String.format(" snapshot: %s           %s   %s%n",
                        hash2Address(address.getAddress()),
                        address.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                        FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS")
                                .format(txHistory.getTimestamp())));
            }
        }

        return String.format(heightFormat, block.getInfo().getHeight()) + String.format(otherFormat,
                FastDateFormat.getInstance("yyyy-MM-dd HH:mm:ss.SSS").format(time),
                Long.toHexString(block.getTimestamp()),
                Integer.toHexString(block.getInfo().getFlags()),
                getStateByFlags(block.getInfo().getFlags()),
                Hex.toHexString(block.getInfo().getHash().toArray()),
                block.getInfo().getRemark() == null ? StringUtils.EMPTY : new String(block.getInfo().getRemark().toArray(), StandardCharsets.UTF_8),
                block.getInfo().getDifficulty().toBigInteger().toString(16),
                hash2Address(block.getHash()), block.getInfo().getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                block.getInfo().getRef() == null ? "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" : hash2Address(block.getInfo().getRef()),
                block.getInfo().getRef() == null ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                        (getStateByFlags(block.getInfo().getFlags()).equals(MAIN_STATE) ? kernel.getBlockStore().getBlockInfoByHash(block.getHash()).getFee().toDecimal(9, XUnit.XDAG).toPlainString() :
                                (block.getInputs().isEmpty() ? XAmount.ZERO.toDecimal(9, XUnit.XDAG).toPlainString() :
                                        MIN_GAS.multiply(block.getOutputs().size()).toDecimal(9, XUnit.XDAG).toPlainString()))
        )
                + "\n"
                + (inputs == null ? "" : inputs.toString()) + (outputs == null ? "" : outputs.toString())
                + "\n"
                + txHisFormat
                + "\n"
                + tx
                ;
    }
    */

    /**
     * List main blocks (Phase 8.1: Restored using Block)
     *
     * Phase 8.1: Restored CLI command using Block display.
     * Uses listMainBlocks() from Blockchain and printBlock() for display.
     *
     * @param n Number of blocks to list
     * @return Formatted list of main blocks
     */
    public String mainblocks(int n) {
        List<Block> blockList = dagKernel.getDagChain().listMainBlocks(n);

        if (CollectionUtils.isEmpty(blockList)) {
            return "empty";
        }

        return printHeaderBlockList() +
                blockList.stream().map(Commands::printBlock).collect(Collectors.joining("\n"));
    }

    /**
     * List mined blocks (Phase 8.1: Restored using Block)
     *
     * Phase 8.1: Restored CLI command using Block display.
     * Uses listMinedBlocks() from Blockchain and printBlock() for display.
     *
     * @param n Number of blocks to list
     * @return Formatted list of mined blocks
     */
    public String minedBlocks(int n) {
        List<Block> blockList = dagKernel.getDagChain().listMinedBlocks(n);

        if (CollectionUtils.isEmpty(blockList)) {
            return "empty";
        }

        return printHeaderBlockList() +
                blockList.stream().map(Commands::printBlock).collect(Collectors.joining("\n"));
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
        return String.format("XDAG State (v5.1):\n" +
                "  Main Blocks: %d\n" +
                "  Total Blocks: %d\n" +
                "  Difficulty: %s\n" +
                "  Orphans: %d\n" +
                "  Waiting Sync: %d",
                stats.getMainBlockCount(),
                stats.getTotalBlockCount(),
                stats.getDifficulty().toHexString(),
                stats.getNoRefCount(),
                stats.getWaitingSyncCount());
    }

    /**
     * Get maximum transferable balance
     */
    public String balanceMaxXfer() {
        return getBalanceMaxXfer(dagKernel);
    }

    /**
     * Calculate maximum transferable balance (Phase 8.1: Using AccountStore)
     *
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
            Bytes32 address = Bytes32.wrap(toBytesAddress(account).toArray());
            UInt256 balance = dagKernel.getAccountStore().getBalance(address);
            totalBalance = totalBalance.add(uint256ToXAmount(balance));
        }

        return String.format("%s", totalBalance.toDecimal(9, XUnit.XDAG).toPlainString());
    }

    /**
     * Get address transaction history (Phase 8.1: Restored using Transaction APIs)
     *
     * Phase 8.1: Restored address transaction history using TransactionStore.
     * Shows all transactions involving the address (sent or received).
     *
     * Note: Pagination not yet implemented in v5.1 TransactionStore.
     * Future enhancement: Add paging support to TransactionStore.getTransactionsByAddress()
     *
     * @param wrap Address bytes
     * @param page Page number (currently unused - shows all transactions)
     * @return Formatted transaction history
     */
    public String address(Bytes32 wrap, int page) {
        // Get address balance (v5.1: Using AccountStore)
        XAmount balance = getAccountBalance(wrap);

        String overview = " OverView\n" +
                String.format(" address: %s\n", Base58.encodeCheck(hash2byte(wrap.mutableCopy()).toArray())) +
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
            Bytes32 otherAddress;
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

            String addressStr = otherAddress != null ? hash2Address(otherAddress) : "UNKNOWN";

            txHistory.append(String.format("%s: %s           %s   %s\n",
                    direction,
                    addressStr,
                    tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString(),
                    timeStr));
        }

        // Phase 9.1: Transaction timestamp lookup implemented using reverse index
        return overview + "\n" + txHistory.toString();
    }


    /**
     * Get block finalization service statistics (v5.1: Not available)
     *
     * @return Finalization statistics string
     */
    public String finalizeStats() {
        // TODO: Re-implement block finalization in v5.1
        return "Block Finalization Service not available in v5.1\n" +
               "Will be re-implemented in future version";
    }

    /**
     * Manually trigger block finalization (v5.1: Not available)
     *
     * @return Number of blocks finalized
     */
    public String manualFinalize() {
        // TODO: Re-implement block finalization in v5.1
        return "Block Finalization Service not available in v5.1\n" +
               "Will be re-implemented in future version";
    }

    // ========== Phase 4 Layer 3: v5.1 Transaction Methods ==========

    /**
     * Transfer XDAG using v5.1 Transaction architecture (convenience overload with default fee)
     *
     * Phase 4 Layer 3 Phase 2: Full implementation with configurable fee and remark support.
     *
     * This is a convenience method that uses the default fee of 100 milli-XDAG.
     *
     * @param sendAmount Amount to send
     * @param toAddress Recipient address (Base58 encoded)
     * @param remark Optional transaction remark (encoded to Transaction.data field)
     * @return Transaction result message
     */
    public String xferV2(double sendAmount, String toAddress, String remark) {
        // Use default fee of 100 milli-XDAG
        return xferV2(sendAmount, toAddress, remark, 100.0);
    }

    /**
     * Transfer XDAG using v5.1 Transaction architecture (full implementation)
     *
     * Phase 4 Layer 3 Phase 2: Full implementation with configurable fee and remark support.
     *
     * Key differences from xfer():
     * 1. Uses Transaction instead of Address
     * 2. Uses Block instead of Block
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
    public String xferV2(double sendAmount, String toAddress, String remark, double feeMilliXdag) {
        try {
            // Convert amount
            XAmount amount = XAmount.of(BigDecimal.valueOf(sendAmount), XUnit.XDAG);
            XAmount fee = XAmount.of(BigDecimal.valueOf(feeMilliXdag), XUnit.MILLI_XDAG);
            XAmount totalRequired = amount.add(fee);

            // Parse recipient address
            Bytes32 to;
            if (checkAddress(toAddress)) {
                // Base58 address format
                to = pubAddress2Hash(toAddress);
            } else if (toAddress.length() == 32) {
                // Hash format
                to = address2Hash(toAddress);
            } else {
                to = getHash(toAddress);
            }

            if (to == null) {
                return "Invalid recipient address format.";
            }

            // Find account with sufficient balance (v5.1: Using AccountStore)
            ECKeyPair fromAccount = null;
            Bytes32 fromAddress = null;
            long currentNonce = 0;

            for (ECKeyPair account : dagKernel.getWallet().getAccounts()) {
                Bytes32 addr = Bytes32.wrap(toBytesAddress(account).toArray());
                XAmount balance = getAccountBalance(addr);

                if (balance.compareTo(totalRequired) >= 0) {
                    fromAccount = account;
                    fromAddress = keyPair2Hash(account);
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
                Bytes32 fromAddr = Bytes32.wrap(toBytesAddress(fromAccount).toArray());
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
                    "Transaction failed!\n" +
                    "  Result: %s\n" +
                    "  Error: %s",
                    result.getStatus(),
                    result.getErrorMessage() != null ? result.getErrorMessage() : "Unknown error"
                );
            }

        } catch (Exception e) {
            log.error("xferV2 failed: " + e.getMessage(), e);
            return "Transaction failed: " + e.getMessage();
        }
    }

    /**
     * Transfer account balances to default address (Phase 8.1: Fully restored)
     *
     * Phase 8.1: v5.1 implementation using address balances and Transaction architecture.
     * Transfers all account balances to the default key address.
     *
     * v5.1 Simplifications:
     * 1. Uses address balances (not block balances)
     * 2. AddressStore handles confirmation logic automatically
     * 3. Creates Transaction objects for each transfer
     * 4. Uses Block for transaction broadcast
     *
     * @return Transaction result message
     */
    public String xferToNewV2() {
        try {
            StringBuilder result = new StringBuilder();
            result.append("Account Balance Transfer (v5.1):\n\n");

            // Target address (default key)
            Bytes32 toAddress = keyPair2Hash(dagKernel.getWallet().getDefKey());
            String remark = "account balance to new address";

            // v5.1: Collect address balances directly using AccountStore
            Map<Integer, XAmount> accountBalances = new HashMap<>();
            List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();

            for (int i = 0; i < accounts.size(); i++) {
                ECKeyPair account = accounts.get(i);
                Bytes32 address = Bytes32.wrap(toBytesAddress(account).toArray());
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
                Bytes32 fromAddress = keyPair2Hash(fromAccount);

                // Get current nonce (v5.1: Using AccountStore)
                Bytes32 addr = Bytes32.wrap(toBytesAddress(fromAccount).toArray());
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

            result.append(String.format("\nSummary:\n"));
            result.append(String.format("  Successful transfers: %d\n", successCount));
            result.append(String.format("  Total transferred: %.9f XDAG\n",
                    totalTransferred.toDecimal(9, XUnit.XDAG).doubleValue()));
            result.append("\nIt will take several minutes to complete the transactions.");

            return result.toString();

        } catch (Exception e) {
            log.error("xferToNewV2 failed: " + e.getMessage(), e);
            return "Block balance transfer failed: " + e.getMessage();
        }
    }
}
