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
import static io.xdag.utils.BasicUtils.xdagHashRate;
import static io.xdag.utils.WalletUtils.checkAddress;
import static io.xdag.utils.WalletUtils.fromBase58;

import com.google.common.collect.Lists;
import io.xdag.Kernel;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.Block;
import io.xdag.core.ChainStats;
import io.xdag.core.ImportResult;
import io.xdag.core.Link;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.core.XdagState;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.net.Channel;
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

    private final Kernel kernel;

    // Block state string constants (Phase 7.1: Replacing BlockState enum)
    private static final String MAIN_STATE = "Main";
    private static final String ACCEPTED_STATE = "Accepted";
    private static final String REJECTED_STATE = "Rejected";
    private static final String PENDING_STATE = "Pending";

    public Commands(Kernel kernel) {
        this.kernel = kernel;
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

    /**
     * List addresses, balances and current transaction quantity
     * @param num Number of addresses to display
     */
    public String account(int num) {
        StringBuilder str = new StringBuilder();
        List<ECKeyPair> list = kernel.getWallet().getAccounts();

        // Sort by balance descending, then by key index descending
        list.sort((o1, o2) -> {
            int compareResult = compareAmountTo(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(o2).toArray()),
                    kernel.getAddressStore().getBalanceByAddress(toBytesAddress(o1).toArray()));
            if (compareResult >= 0) {
                return 1;
            } else {
                return -1;
            }

        });

        for (ECKeyPair keyPair : list) {
            if (num == 0) {
                break;
            }

            UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(toBytesAddress(keyPair).toArray());
            UInt64 exeTxNonceNum = kernel.getAddressStore().getExecutedNonceNum(toBytesAddress(keyPair).toArray());

            str.append(AddressUtils.toBase58Address(keyPair))
                    .append(" ")
                    .append(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(keyPair).toArray()).toDecimal(9, XUnit.XDAG).toPlainString())
                    .append(" XDAG")
                    .append("  [Current TX Quantity: ")
                    .append(txQuantity.toUInt64())
                    .append(", Confirmed TX Quantity: ")
                    .append(exeTxNonceNum.toUInt64())
                    .append("]")
                    .append("\n");
            num--;
        }

        return str.toString();
    }

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
            // Account balance lookup (unchanged from v1)
            XAmount ourBalance = XAmount.ZERO;
            List<ECKeyPair> list = kernel.getWallet().getAccounts();
            for (ECKeyPair k : list) {
                ourBalance = ourBalance.add(kernel.getAddressStore().getBalanceByAddress(toBytesAddress(k).toArray()));
            }
            return String.format("Balance: %s XDAG", ourBalance.toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            // Check if it's an account address or block address
            if (checkAddress(address)) {
                // Account address (Base58 format)
                XAmount balance = kernel.getAddressStore().getBalanceByAddress(fromBase58(address).toArray());
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
                Block block = kernel.getBlockchain().getBlockByHash(hash, false);
                if (block == null) {
                    return "Block not found";
                }

                // v5.1: Calculate balance from Transactions
                List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(hash);
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
            UInt64 ourTxQuantity = UInt64.ZERO;
            UInt64 exeTxQuantit = UInt64.ZERO;
            List<ECKeyPair> list = kernel.getWallet().getAccounts();
            for (ECKeyPair key : list) {
                ourTxQuantity = ourTxQuantity.add(kernel.getAddressStore().getTxQuantity(toBytesAddress(key).toArray()));
                exeTxQuantit = exeTxQuantit.add(kernel.getAddressStore().getExecutedNonceNum(toBytesAddress(key).toArray()));
            }
            return String.format("Current Transaction Quantity: %s, executed Transaction Quantity: %s \n", ourTxQuantity.toLong(), exeTxQuantit.toLong());
        } else {
            UInt64 addressTxQuantity = UInt64.ZERO;
            UInt64 addressExeTxQuantity = UInt64.ZERO;
            if (checkAddress(address)) {
                addressTxQuantity = addressTxQuantity.add(kernel.getAddressStore().getTxQuantity(fromBase58(address).toArray()));
                addressExeTxQuantity = addressExeTxQuantity.add(kernel.getAddressStore().getExecutedNonceNum(fromBase58(address).toArray()));
                return String.format("Current Transaction Quantity: %s, executed Transaction Quantity: %s \n", addressTxQuantity.toLong(), addressExeTxQuantity.toLong());
            } else {
                return "The account address format is incorrect! \n";
            }
        }
    }

    /**
     * Get current blockchain stats
     */
    public String stats() {
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        // Phase 7.3.1: XdagTopStatus merged into ChainStats (deleted)
        ChainStats chainStats = kernel.getBlockchain().getChainStats();

        // Calculate difficulties
        BigInteger currentDiff = chainStats.getTopDifficulty() != null ? chainStats.getTopDifficulty().toBigInteger() : BigInteger.ZERO;
        BigInteger netDiff = chainStats.getMaxDifficulty().toBigInteger();
        BigInteger maxDiff = netDiff.max(currentDiff);

        // Get finalized store statistics (Phase 2 refactor)
        String finalizedStats = "";
        if (kernel.getFinalizedBlockStore() != null) {
            long totalBlocks = kernel.getFinalizedBlockStore().getTotalBlockCount();
            long totalMain = kernel.getFinalizedBlockStore().getTotalMainBlockCount();
            long storageSize = kernel.getFinalizedBlockStore().getStorageSize();

            finalizedStats = String.format("""

                            Finalized Storage:
                         finalized blocks: %d
                           finalized main: %d
                          storage size MB: %d""",
                    totalBlocks,
                    totalMain,
                    storageSize / (1024 * 1024));
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
                              XDAG supply: %s of %s
                          XDAG in address: %s
                        4 hr hashrate KHs: %.9f of %.9f
                        Number of Address: %d%s""",
                kernel.getNetDB().getSize(), kernel.getNetDBMgr().getWhiteDB().getSize(),
                chainStats.getTotalBlockCount(), Math.max(chainStats.getTotalBlockCount(), chainStats.getTotalBlockCount()),
                chainStats.getMainBlockCount(), Math.max(chainStats.getTotalMainBlockCount(), chainStats.getMainBlockCount()),
                chainStats.getExtraCount(),
                chainStats.getNoRefCount(),
                chainStats.getWaitingSyncCount(),
                currentDiff.toString(16),
                maxDiff.toString(16),
                kernel.getBlockchain().getSupply(chainStats.getMainBlockCount()).toDecimal(9, XUnit.XDAG).toPlainString(),
                kernel.getBlockchain().getSupply(Math.max(chainStats.getMainBlockCount(), chainStats.getTotalMainBlockCount())).toDecimal(9, XUnit.XDAG).toPlainString(),
                kernel.getAddressStore().getAllBalance().toDecimal(9, XUnit.XDAG).toPlainString(),
                kernel.getAddressStore().getAddressSize().toLong(),
                finalizedStats
        );
    }

    /**
     * Connect to remote node
     */
    public void connect(String server, int port) {
        kernel.getNodeMgr().doConnect(server, port);
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
            Block block = kernel.getBlockchain().getBlockByHash(blockhash, true);
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
        List<Block> blockList = kernel.getBlockchain().listMainBlocks(n);

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
        List<Block> blockList = kernel.getBlockchain().listMinedBlocks(n);

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
            kernel.testStart();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * Stop test mode
     */
    public void stop() {
        kernel.testStop();
    }

    /**
     * List active connections
     */
    public String listConnect() {
        List<Channel> channelList = kernel.getChannelMgr().getActiveChannels();
        StringBuilder stringBuilder = new StringBuilder();
        for (Channel channel : channelList) {
            stringBuilder.append(channel).append(" ")
                    .append(System.lineSeparator());
        }

        return stringBuilder.toString();
    }

    /**
     * Show websocket channel pool
     */
    public String pool() {
        return ChannelSupervise.showChannel();
    }

    /**
     * Generate new key pair
     */
    public String keygen() {
        kernel.getXdagState().tempSet(XdagState.KEYS);
        kernel.getWallet().addAccountRandom();

        kernel.getWallet().flush();
        int size = kernel.getWallet().getAccounts().size();
        kernel.getXdagState().rollback();
        return "Key " + (size - 1) + " generated and set as default,now key size is:" + size;
    }

    /**
     * Get current XDAG state
     */
    public String state() {
        return kernel.getXdagState().toString();
    }

    /**
     * Get maximum transferable balance
     */
    public String balanceMaxXfer() {
        return getBalanceMaxXfer(kernel);
    }

    /**
     * Calculate maximum transferable balance (Phase 8.1: Restored using AddressStore)
     *
     * Phase 8.1: Simplified v5.1 implementation using address balances.
     * In v5.1, balances are tracked by addresses (not blocks), so we sum
     * confirmed address balances instead of iterating block balances.
     *
     * Note: AddressStore already handles balance confirmation logic,
     * so we don't need to check block timestamps manually.
     *
     * @param kernel Kernel instance
     * @return Formatted maximum transferable balance
     */
    public static String getBalanceMaxXfer(Kernel kernel) {
        // v5.1: Sum up all account balances (already confirmed by AddressStore)
        XAmount totalBalance = XAmount.ZERO;
        List<ECKeyPair> accounts = kernel.getWallet().getAccounts();

        for (ECKeyPair account : accounts) {
            byte[] addressBytes = toBytesAddress(account).toArray();
            XAmount balance = kernel.getAddressStore().getBalanceByAddress(addressBytes);
            totalBalance = totalBalance.add(balance);
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
        // Get address balance
        byte[] addressBytes = hash2byte(wrap.mutableCopy()).toArray();
        XAmount balance = kernel.getAddressStore().getBalanceByAddress(addressBytes);

        String overview = " OverView\n" +
                String.format(" address: %s\n", Base58.encodeCheck(addressBytes)) +
                String.format(" balance: %s\n", balance.toDecimal(9, XUnit.XDAG).toPlainString());

        // Get transaction history
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByAddress(wrap);

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
            Bytes32 blockHash = kernel.getTransactionStore().getBlockByTransaction(tx.getHash());
            if (blockHash != null) {
                Block block = kernel.getBlockchain().getBlockByHash(blockHash, false);
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
     * Get block finalization service statistics (Phase 3)
     *
     * @return Finalization statistics string
     */
    public String finalizeStats() {
        if (kernel.getBlockFinalizationService() == null) {
            return "Block Finalization Service is not running.";
        }

        return kernel.getBlockFinalizationService().getStatistics();
    }

    /**
     * Manually trigger block finalization (Phase 3)
     * For administrative or testing purposes
     *
     * @return Number of blocks finalized
     */
    public String manualFinalize() {
        if (kernel.getBlockFinalizationService() == null) {
            return "Block Finalization Service is not running.";
        }

        long count = kernel.getBlockFinalizationService().manualFinalize();
        return String.format("Manual finalization completed. %d blocks finalized.", count);
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

            // Find account with sufficient balance
            ECKeyPair fromAccount = null;
            Bytes32 fromAddress = null;
            long currentNonce = 0;

            for (ECKeyPair account : kernel.getWallet().getAccounts()) {
                byte[] addr = toBytesAddress(account).toArray();
                XAmount balance = kernel.getAddressStore().getBalanceByAddress(addr);

                if (balance.compareTo(totalRequired) >= 0) {
                    fromAccount = account;
                    fromAddress = keyPair2Hash(account);
                    // Get current nonce
                    UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(addr);
                    currentNonce = txQuantity.toLong() + 1;
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
            kernel.getTransactionStore().saveTransaction(signedTx);

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

            // Validate and add block
            // Phase 4 Layer 3 Task 1.1: Blockchain interface now supports Block
            ImportResult result = kernel.getBlockchain().tryToConnect(block);

            if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                // Update nonce in address store
                byte[] fromAddr = toBytesAddress(fromAccount).toArray();
                kernel.getAddressStore().updateTxQuantity(fromAddr, UInt64.valueOf(currentNonce));

                // Phase 4 Layer 3 Task 1.2: Broadcast Block using new network method
                int ttl = kernel.getConfig().getNodeSpec().getTTL();
                kernel.broadcastBlock(block, ttl);

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
                successMsg.append(String.format("  Status: %s\n", result.name()));
                successMsg.append(String.format("\n✅ Block broadcasted to network (TTL=%d)", ttl));

                return successMsg.toString();
            } else {
                return String.format(
                    "Transaction failed!\n" +
                    "  Result: %s\n" +
                    "  Error: %s",
                    result.name(),
                    result.getErrorInfo() != null ? result.getErrorInfo() : "Unknown error"
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
            Bytes32 toAddress = keyPair2Hash(kernel.getWallet().getDefKey());
            String remark = "account balance to new address";

            // v5.1: Collect address balances directly (no block iteration needed)
            Map<Integer, XAmount> accountBalances = new HashMap<>();
            List<ECKeyPair> accounts = kernel.getWallet().getAccounts();

            for (int i = 0; i < accounts.size(); i++) {
                ECKeyPair account = accounts.get(i);
                byte[] addressBytes = toBytesAddress(account).toArray();
                XAmount balance = kernel.getAddressStore().getBalanceByAddress(addressBytes);

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
                ECKeyPair fromAccount = kernel.getWallet().getAccounts().get(accountIndex);
                Bytes32 fromAddress = keyPair2Hash(fromAccount);

                // Get current nonce
                byte[] addr = toBytesAddress(fromAccount).toArray();
                UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(addr);
                long currentNonce = txQuantity.toLong() + 1;

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
                kernel.getTransactionStore().saveTransaction(signedTx);

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

                // Validate and add block
                ImportResult importResult = kernel.getBlockchain().tryToConnect(block);

                if (importResult == ImportResult.IMPORTED_BEST || importResult == ImportResult.IMPORTED_NOT_BEST) {
                    // Update nonce
                    kernel.getAddressStore().updateTxQuantity(addr, UInt64.valueOf(currentNonce));

                    // Broadcast
                    int ttl = kernel.getConfig().getNodeSpec().getTTL();
                    kernel.broadcastBlock(block, ttl);

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
                            importResult.name()));
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

    /**
     * Distribute node rewards using v5.1 Transaction architecture
     *
     * Phase 4 Layer 3 Phase 4: Node reward distribution migration using v5.1 design.
     *
     * This method distributes accumulated node rewards to the default key address.
     * Unlike legacy xferToNode(), this uses Transaction objects for each reward.
     *
     * Key differences from xferToNode():
     * 1. Uses Transaction instead of Address
     * 2. Uses Block instead of Block
     * 3. Creates one Transaction per account balance
     * 4. Simpler logic: account-to-account transfers (not block-as-input)
     *
     * @param paymentsToNodesMap Map of block hashes and keypairs for node payments
     *                            (from PoolAwardManagerImpl batching)
     *                            Temporarily using Bytes32 as key until full migration
     * @return Transaction result message (StringBuilder for compatibility)
     */
    public StringBuilder xferToNodeV2(Map<Bytes32, ECKeyPair> paymentsToNodesMap) {
        /**
         * TODO Phase 8.5: Rewrite to use Block Transaction system without Address class.
         * This requires implementing amount tracking in Transaction objects for block rewards.
         * Blocked by: Address.getAmount() no longer available in v5.1 architecture.
         * Estimated effort: 6-8 hours.
         */
        log.warn("Node reward distribution temporarily disabled - waiting for v5.1 Transaction migration");
        return new StringBuilder("Node reward distribution temporarily disabled - v5.1 migration in progress");

        /*
        try {
            StringBuilder result = new StringBuilder();
            result.append("Node Reward Distribution (v5.1):\n\n");

            // Target address (default key)
            Bytes32 toAddress = keyPair2Hash(kernel.getWallet().getDefKey());
            String remark = "Pay to " + kernel.getConfig().getNodeSpec().getNodeTag();

            // Aggregate amounts by account (multiple blocks may belong to same account)
            Map<Integer, XAmount> accountRewards = new HashMap<>();
            Map<Integer, ECKeyPair> accountKeys = new HashMap<>();

            // Build account index lookup
            List<ECKeyPair> allAccounts = kernel.getWallet().getAccounts();
            Map<ECKeyPair, Integer> keyToIndex = new HashMap<>();
            for (int i = 0; i < allAccounts.size(); i++) {
                keyToIndex.put(allAccounts.get(i), i);
            }

            // Aggregate rewards by account
            for (Map.Entry<Bytes32, ECKeyPair> entry : paymentsToNodesMap.entrySet()) {
                Bytes32 blockHash = entry.getKey();
                ECKeyPair key = entry.getValue();

                Integer accountIndex = keyToIndex.get(key);
                if (accountIndex == null) {
                    result.append(String.format("  Warning: Unknown account key for block %s (skipped)\n",
                            hash2Address(blockHash)));
                    continue;
                }

                // TODO: Need to get amount from somewhere - Address.getAmount() not available
                // XAmount currentReward = accountRewards.getOrDefault(accountIndex, XAmount.ZERO);
                // accountRewards.put(accountIndex, currentReward.add(???));
                accountKeys.put(accountIndex, key);
            }

            if (accountRewards.isEmpty()) {
                return new StringBuilder("No valid node rewards to distribute.");
            }

            result.append(String.format("Found %d accounts with node rewards\n\n", accountRewards.size()));

            // ... rest of implementation
            return result;

        } catch (Exception e) {
            log.error("xferToNodeV2 failed: " + e.getMessage(), e);
            return new StringBuilder("Node reward distribution failed: " + e.getMessage());
        }
        */
    }
}
