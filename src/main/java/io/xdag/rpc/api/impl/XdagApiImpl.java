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

package io.xdag.rpc.api.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.MainnetConfig;
import io.xdag.config.TestnetConfig;
import io.xdag.config.spec.NodeSpec;
import io.xdag.config.spec.RPCSpec;
import io.xdag.core.*;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.exception.AddressFormatException;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.net.Channel;
import io.xdag.rpc.model.request.TransactionRequest;
import io.xdag.rpc.model.response.*;
import io.xdag.rpc.server.core.JsonRpcServer;
import io.xdag.rpc.api.XdagApi;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.WalletUtils;
import io.xdag.utils.XdagTime;
import io.xdag.utils.exception.XdagOverFlowException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static io.xdag.cli.Commands.getStateByFlags;
import static io.xdag.config.Constants.*;
// TODO v5.1: DELETED - XdagField class no longer exists
// import static io.xdag.core.XdagField.FieldType.*;
import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.db.mysql.TransactionHistoryStoreImpl.totalPage;
import static io.xdag.rpc.error.JsonRpcError.*;
import static io.xdag.rpc.util.TypeConverter.toQuantityJsonHex;
import static io.xdag.utils.BasicUtils.*;
import static io.xdag.utils.WalletUtils.*;
import static io.xdag.utils.XdagTime.xdagTimestampToMs;

@Slf4j
public class XdagApiImpl extends AbstractXdagLifecycle implements XdagApi {
    private final Kernel kernel;
    private final Blockchain blockchain;
    private final RPCSpec rpcSpec;
    private JsonRpcServer server;

    public XdagApiImpl(Kernel kernel) {
        this.kernel = kernel;
        this.blockchain = kernel.getBlockchain();
        this.rpcSpec = kernel.getConfig().getRPCSpec();
        this.server = new JsonRpcServer(rpcSpec, this);

        validateConfiguration();
    }

    private void validateConfiguration() {
        if (rpcSpec.isRpcHttpEnabled()) {
            if (StringUtils.isBlank(rpcSpec.getRpcHttpHost())) {
                throw new IllegalArgumentException("RPC host not configured");
            }
            if (rpcSpec.getRpcHttpPort() <= 0 || rpcSpec.getRpcHttpPort() > 65535) {
                throw new IllegalArgumentException("Invalid RPC port");
            }
        }
    }

    @Override
    protected void doStart() {
        try {
            if (rpcSpec.isRpcHttpEnabled()) {
                server.start();
                log.info("JSON-RPC server started on {}:{} (SSL: {})", rpcSpec.getRpcHttpHost(), rpcSpec.getRpcHttpPort(), rpcSpec.isRpcEnableHttps());
            }
        } catch (Exception e) {
            log.error("Failed to start RPC server", e);
            throw new RuntimeException("Failed to start RPC server", e);
        }
    }

    @Override
    protected void doStop() {
        try {
            if (server != null) {
                server.stop();
                server = null;
            }
        } catch (Exception e) {
            log.error("Error while stopping RPC server", e);
        } finally {
            log.info("JSON-RPC server stopped");
        }
    }

    @Override
    public BlockResponse xdag_getTransactionByHash(String hash, int page) {
      try {
        return getBlockDTOByHash(hash, page);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String xdag_getBalanceByNumber(String bnOrId) {
        // Phase 8.2.3: Restored using TransactionStore (v5.1 pattern from CLI balance())
        BlockV5 block = blockchain.getBlockByHeight(Long.parseLong(bnOrId));
        if (block == null) {
            return "0.0";
        }

        // v5.1: Calculate balance from Transactions
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(block.getHash());
        XAmount totalAmount = XAmount.ZERO;

        for (Transaction tx : transactions) {
            totalAmount = totalAmount.add(tx.getAmount());
        }

        return totalAmount.toDecimal(9, XUnit.XDAG).toPlainString();
    }

    @Override
    public ConfigResponse xdag_poolConfig() {
        NodeSpec nodeSpec = kernel.getConfig().getNodeSpec();
        ConfigResponse.ConfigResponseBuilder configResponseBuilder = ConfigResponse.builder();
        configResponseBuilder.nodeIp(nodeSpec.getNodeIp());
        configResponseBuilder.nodePort(nodeSpec.getNodePort());
        return configResponseBuilder.build();
    }

    @Override
    public String xdag_protocolVersion() {
        return CLIENT_VERSION;
    }

    @Override
    public BlockResponse xdag_getBlockByHash(String hash, int page) {
      try {
        return getBlockDTOByHash(hash, page);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public BlockResponse xdag_getBlockByHash(String hash, int page, int pageSize) {
      try {
        return getBlockDTOByHash(hash, page, pageSize);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public BlockResponse xdag_getBlockByHash(String hash, int page, String startTime, String endTime) {
      try {
        return getBlockDTOByHash(hash, page, startTime, endTime);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public BlockResponse xdag_getBlockByHash(String hash, int page, String startTime, String endTime, int pageSize) {
      try {
        return getBlockDTOByHash(hash, page, startTime, endTime, pageSize);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public BlockResponse xdag_getBlockByNumber(String bnOrId, int page) {
        return getBlockByNumber(bnOrId, page);
    }

    @Override
    public BlockResponse xdag_getBlockByNumber(String bnOrId, int page, int pageSize) {
        return getBlockByNumber(bnOrId, page, pageSize);
    }

    @Override
    public List<BlockResponse> xdag_getBlocksByNumber(String bnOrId) {
        int number = bnOrId == null ? 20 : Integer.parseInt(bnOrId);// default 20
        List<BlockV5> blocks = blockchain.listMainBlocks(number);
        List<BlockResponse> resultDTOS = Lists.newArrayList();
        for (BlockV5 block : blocks){
            BlockResponse dto = transferBlockToBriefBlockResultDTO(blockchain.getBlockByHash(block.getHash(), false));
            if(dto != null){
                resultDTOS.add(dto);
            }
        }
        return resultDTOS;
    }

    @Override
    public String xdag_coinbase() {
        return Base58.encodeCheck(toBytesAddress(kernel.getCoinbase()));
    }

    @Override
    public String xdag_blockNumber() {
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        long b = blockchain.getChainStats().getMainBlockCount();
        log.debug("xdag_blockNumber(): {}", b);
        return Long.toString(b);
    }

    @Override
    public String xdag_getBalance(String address) throws AddressFormatException {
        Bytes32 hash;
        MutableBytes32 key = MutableBytes32.create();
        String balance;
        if (WalletUtils.checkAddress(address)) {
            // Account address (Base58 format) - unchanged
            hash = pubAddress2Hash(address);
            key.set(8, Objects.requireNonNull(hash).slice(8, 20));
            balance = String.format("%s", kernel.getAddressStore().getBalanceByAddress(fromBase58(address).toArray()).toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            // Block address - Phase 8.2.3: Restored using TransactionStore
            if (StringUtils.length(address) == 32) {
                hash = BasicUtils.address2Hash(address);
            } else {
                hash = BasicUtils.getHash(address);
            }

            if (hash == null) {
                return "0.0";
            }

            // Get BlockV5
            BlockV5 block = blockchain.getBlockByHash(hash, false);
            if (block == null) {
                return "0.0";
            }

            // v5.1: Calculate balance from Transactions
            List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(hash);
            XAmount totalAmount = XAmount.ZERO;

            for (Transaction tx : transactions) {
                totalAmount = totalAmount.add(tx.getAmount());
            }

            balance = totalAmount.toDecimal(9, XUnit.XDAG).toPlainString();
        }
        return balance;
    }

    @Override
    public String xdag_getTransactionNonce(String address) throws AddressFormatException {
        UInt64 txNonce = UInt64.ZERO;
        if (WalletUtils.checkAddress(address)) {
            UInt64 txQuantity = kernel.getAddressStore().getTxQuantity(fromBase58(address).toArray());
            txNonce = txNonce.add(txQuantity.add(UInt64.ONE));
        }
        return String.format("%s", txNonce.toLong());
    }

    @Override
    public String xdag_getTotalBalance() {
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        return String.format("%s", kernel.getBlockchain().getChainStats().getBalance().toDecimal(9, XUnit.XDAG).toPlainString());
    }

    @Override
    public XdagStatusResponse xdag_getStatus() {
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        ChainStats chainStats = blockchain.getChainStats();
        XdagExtStats xdagExtStats = blockchain.getXdagExtStats();
        double hashrateOurs = BasicUtils.xdagHashRate(xdagExtStats.getHashRateOurs());
        double hashrateTotal = BasicUtils.xdagHashRate(xdagExtStats.getHashRateTotal());
        XdagStatusResponse.XdagStatusResponseBuilder builder = XdagStatusResponse.builder();
        builder.nblock(Long.toString(chainStats.getTotalBlockCount()))
                .totalNblocks(Long.toString(chainStats.getTotalBlockCount()))
                .nmain(Long.toString(chainStats.getMainBlockCount()))
                .totalNmain(Long.toString(Math.max(chainStats.getTotalMainBlockCount(), chainStats.getMainBlockCount())))
                .curDiff(toQuantityJsonHex(chainStats.getDifficulty().toBigInteger()))
                .netDiff(toQuantityJsonHex(chainStats.getMaxDifficulty().toBigInteger()))
                .hashRateOurs(toQuantityJsonHex(hashrateOurs))
                .hashRateTotal(toQuantityJsonHex(hashrateTotal))
                .ourSupply(String.format("%s", blockchain.getSupply(chainStats.getMainBlockCount()).toDecimal(9, XUnit.XDAG).toPlainString()))
                .netSupply(String.format("%s", blockchain.getSupply(Math.max(chainStats.getMainBlockCount(), chainStats.getTotalMainBlockCount())).toDecimal(9, XUnit.XDAG).toPlainString()));
        return builder.build();
    }

    @Override
    public ProcessResponse xdag_personal_sendTransaction(TransactionRequest request, String passphrase) {
        log.debug("personalSendTransaction request:{}", request);

        String from = request.getFrom();
        String to = request.getTo();
        String value = request.getValue();
        String remark = request.getRemark();

        ProcessResponse result = ProcessResponse.builder().code(SUCCESS).build();

        checkParam(value, remark, result);
        if (result.getCode() != SUCCESS) {
            return result;
        }

      Bytes32 toHash;
      try {
        toHash = checkTo(to, result);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
      if (result.getCode() != SUCCESS) {
            return result;
        }

      Bytes32 fromHash;
      try {
        fromHash = checkFrom(from, result);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
      if (result.getCode() != SUCCESS) {
            return result;
        }

        checkPassword(passphrase, result);
        if (result.getCode() != SUCCESS) {
            return result;
        }

        // do xfer
        double amount = BasicUtils.getDouble(value);
        doXfer(amount, fromHash, toHash, remark, null, result);

        return result;
    }

    @Override
    public ProcessResponse xdag_personal_sendSafeTransaction(TransactionRequest request, String passphrase) {
        log.debug("personalSendTransaction request:{}", request);

        String from = request.getFrom();
        String to = request.getTo();
        String value = request.getValue();
        String remark = request.getRemark();
        String nonce = request.getNonce();

        ProcessResponse result = ProcessResponse.builder().code(SUCCESS).build();

        checkParam(value, remark, result);
        if (result.getCode() != SUCCESS) {
            return result;
        }

      Bytes32 toHash;
      try {
        toHash = checkTo(to, result);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
      if (result.getCode() != SUCCESS) {
            return result;
        }

      Bytes32 fromHash;
      try {
        fromHash = checkFrom(from, result);
      } catch (AddressFormatException e) {
        throw new RuntimeException(e);
      }
      if (result.getCode() != SUCCESS) {
            return result;
        }

        UInt64 txNonce = UInt64.valueOf(new BigInteger(nonce));

        checkPassword(passphrase, result);
        if (result.getCode() != SUCCESS) {
            return result;
        }

        // do xfer
        double amount = BasicUtils.getDouble(value);
        doXfer(amount, fromHash, toHash, remark, txNonce, result);

        return result;
    }

    @Override
    public String xdag_getRewardByNumber(String bnOrId) {
        try {
            XAmount reward = blockchain.getReward(Long.parseLong(bnOrId));
            return String.format("%s", reward.toDecimal(9, XUnit.XDAG).toPlainString());
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    // Phase 8.2.2: DELETED - xdag_sendRawTransaction()
    // Obsolete: Replaced by doXfer() + xdag_personal_sendTransaction()
    // v5.1 uses Transaction objects internally, not raw Block bytes

    @Override
    public List<NetConnResponse> xdag_netConnectionList() {
        List<NetConnResponse> netConnResponseList = Lists.newArrayList();
        NetConnResponse.NetConnResponseBuilder netConnDTOBuilder = NetConnResponse.builder();
        List<Channel> channelList = kernel.getChannelMgr().getActiveChannels();
        for (Channel channel : channelList) {
            netConnDTOBuilder.connectTime(kernel.getConfig().getSnapshotSpec().getSnapshotTime())
                    .inBound(0)
                    .outBound(0)
                    .nodeAddress(channel.getRemoteAddress());
            netConnResponseList.add(netConnDTOBuilder.build());
        }
        return netConnResponseList;
    }

    @Override
    public String xdag_netType() {
        return kernel.getConfig().getNodeSpec().getNetwork().toString().toLowerCase();
    }

    private BlockResponse transferAccountToBlockResultDTO(String address, int page, Object... parameters)
        throws AddressFormatException {
        XAmount balance = kernel.getAddressStore().getBalanceByAddress(hash2byte(pubAddress2Hash(address).mutableCopy()).toArray());

        BlockResponse.BlockResponseBuilder BlockResultDTOBuilder = BlockResponse.builder();
        BlockResultDTOBuilder.address(address)
                .hash(null)
                .balance(balance.toDecimal(9, XUnit.XDAG).toPlainString())
                .type("Wallet")
                .blockTime(xdagTimestampToMs(kernel.getConfig().getSnapshotSpec().getSnapshotTime()))
                .timeStamp(kernel.getConfig().getSnapshotSpec().getSnapshotTime())
                .state("Accepted");
        if (page != 0) {
            BlockResultDTOBuilder.transactions(getTxHistory(address, page, parameters))
                    .totalPage(totalPage);
        }
        totalPage = 1;
        return BlockResultDTOBuilder.build();
    }

    private BlockResponse transferBlockInfoToBlockResultDTO(BlockV5 blockV5, int page, Object... parameters) {
        if (null == blockV5) {
            return null;
        }

        // Phase 8.2.3: Restored using TransactionStore for balance calculation
        BlockInfo info = blockV5.getInfo();
        if (info == null) {
            return null;
        }

        // Calculate balance from Transactions
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(blockV5.getHash());
        XAmount balance = XAmount.ZERO;
        for (Transaction tx : transactions) {
            balance = balance.add(tx.getAmount());
        }

        // Build response
        BlockResponse.BlockResponseBuilder builder = BlockResponse.builder();
        builder.address(hash2Address(blockV5.getHash()))
                .hash(blockV5.getHash().toUnprefixedHexString())
                .balance(balance.toDecimal(9, XUnit.XDAG).toPlainString())
                .type("Snapshot")  // BlockInfo only, no raw data
                .blockTime(xdagTimestampToMs(info.getTimestamp()))
                .timeStamp(info.getTimestamp())
                .state(info.isMainBlock() ? "Main" : "Orphan");

        // Add transaction history if page != 0
        if (page != 0) {
            builder.transactions(getTxHistoryV5(blockV5.getHash(), page, parameters))
                    .totalPage(totalPage);
        }

        totalPage = 1;
        return builder.build();
    }

    private List<BlockResponse.TxLink> getTxHistory(String address, int page, Object... parameters)
        throws AddressFormatException {
        // Phase 8.2.3: Restored using TransactionStore (v5.1 pattern from CLI address())
        Bytes32 hash = pubAddress2Hash(address);
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByAddress(hash);

        List<BlockResponse.TxLink> txLinks = Lists.newArrayList();

        for (Transaction tx : transactions) {
            // Determine direction
            int direction;
            Bytes32 otherAddress;
            if (tx.getFrom().equals(hash)) {
                direction = 1;  // Output
                otherAddress = tx.getTo();
            } else {
                direction = 0;  // Input
                otherAddress = tx.getFrom();
            }

            // Build TxLink
            BlockResponse.TxLink.TxLinkBuilder txLinkBuilder = BlockResponse.TxLink.builder();
            txLinkBuilder.address(otherAddress != null ? hash2Address(otherAddress) : "UNKNOWN")
                    .hash(tx.getHash().toUnprefixedHexString())
                    .amount(tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString())
                    .direction(direction)
                    .time(0L)  // TODO: Need reverse index (txHash -> blockHash) for timestamps
                    .remark(tx.getData() != null && tx.getData().size() > 0 ?
                            new String(tx.getData().toArray(), StandardCharsets.UTF_8).trim() : "");

            txLinks.add(txLinkBuilder.build());
        }

        return txLinks;
    }

    /**
     * Get transaction history for a block (Phase 8.2.3 new method)
     *
     * @param blockHash Block hash to get transactions for
     * @param page Page number (currently unused)
     * @param parameters Additional parameters (currently unused)
     * @return List of transaction links
     */
    private List<BlockResponse.TxLink> getTxHistoryV5(Bytes32 blockHash, int page, Object... parameters) {
        // Phase 8.2.3: New method using TransactionStore
        BlockV5 block = blockchain.getBlockByHash(blockHash, false);
        if (block == null || block.getInfo() == null) {
            return Lists.newArrayList();
        }

        List<BlockResponse.TxLink> txLinks = Lists.newArrayList();

        // 1. Add earning info for Main blocks
        if (block.getInfo().isMainBlock() &&
            block.getInfo().getHeight() > kernel.getConfig().getSnapshotSpec().getSnapshotHeight()) {

            XAmount reward = blockchain.getReward(block.getInfo().getHeight());
            // TODO: Calculate fee from transactions (sum of tx fees)

            BlockResponse.TxLink.TxLinkBuilder txLinkBuilder = BlockResponse.TxLink.builder();
            txLinkBuilder.address(hash2Address(blockHash))
                    .hash(blockHash.toUnprefixedHexString())
                    .amount(reward.toDecimal(9, XUnit.XDAG).toPlainString())
                    .direction(2)  // Earning
                    .time(xdagTimestampToMs(block.getTimestamp()))
                    .remark("");

            txLinks.add(txLinkBuilder.build());
        }

        // 2. Add transaction history
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(blockHash);

        for (Transaction tx : transactions) {
            // Determine direction relative to this block
            int direction;
            Bytes32 otherAddress;

            if (tx.getFrom().equals(blockHash)) {
                direction = 1;  // Output (block sends)
                otherAddress = tx.getTo();
            } else if (tx.getTo().equals(blockHash)) {
                direction = 0;  // Input (block receives)
                otherAddress = tx.getFrom();
            } else {
                direction = 3;  // Reference (block is neither sender nor receiver)
                otherAddress = tx.getFrom();
            }

            // Build TxLink
            BlockResponse.TxLink.TxLinkBuilder txLinkBuilder = BlockResponse.TxLink.builder();
            txLinkBuilder.address(otherAddress != null ? hash2Address(otherAddress) : "UNKNOWN")
                    .hash(tx.getHash().toUnprefixedHexString())
                    .amount(tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString())
                    .direction(direction)
                    .time(0L)  // TODO: Need reverse index (txHash -> blockHash) for timestamps
                    .remark(tx.getData() != null && tx.getData().size() > 0 ?
                            new String(tx.getData().toArray(), StandardCharsets.UTF_8).trim() : "");

            txLinks.add(txLinkBuilder.build());
        }

        return txLinks;
    }

    public BlockResponse getBlockByNumber(String bnOrId, int page, Object... parameters) {
        BlockV5 blockFalse = blockchain.getBlockByHeight(Long.parseLong(bnOrId));
        if (null == blockFalse) {
            return null;
        }
        BlockV5 blockTrue = blockchain.getBlockByHash(blockFalse.getHash(), true);
        if (blockTrue == null) {
            return transferBlockInfoToBlockResultDTO(blockFalse, page, parameters);
        }
        return transferBlockToBlockResultDTO(blockTrue, page, parameters);
    }

    public BlockResponse getBlockDTOByHash(String hash, int page, Object... parameters)
        throws AddressFormatException {
        Bytes32 blockHash;
        if (WalletUtils.checkAddress(hash)) {
            return transferAccountToBlockResultDTO(hash, page, parameters);
        } else {
            if (StringUtils.length(hash) == 32) {
                blockHash = address2Hash(hash);
            } else {
                blockHash = BasicUtils.getHash(hash);
            }
            BlockV5 block = blockchain.getBlockByHash(blockHash, true);
            if (block == null) {
                block = blockchain.getBlockByHash(blockHash, false);
                return transferBlockInfoToBlockResultDTO(block, page, parameters);
            }
            return transferBlockToBlockResultDTO(block, page, parameters);
        }
    }


    // Phase 8.2.2: DELETED - getTxLinks(Block block, int page, ...)
    // Obsolete: Uses TxHistory, Address, BlockInfo.flags which no longer exist
    // v5.1: Will need to query TransactionStore for transaction details

    // Phase 8.2.2: DELETED - getLinks(Block block)
    // Obsolete: Extracts Address objects from Block.getInputs()/getOutputs()
    // v5.1: Link structure is 33-byte references to Transactions, not Address amounts
    // v5.1: Will need new getLinksV5(BlockV5) implementation

    private BlockResponse transferBlockToBlockResultDTO(BlockV5 blockV5, int page, Object... parameters) {
        if (null == blockV5) {
            return null;
        }

        // Phase 8.2.3: Restored using TransactionStore for balance calculation
        BlockInfo info = blockV5.getInfo();
        if (info == null) {
            return null;
        }

        // Calculate balance from Transactions
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(blockV5.getHash());
        XAmount balance = XAmount.ZERO;
        for (Transaction tx : transactions) {
            balance = balance.add(tx.getAmount());
        }

        // Build response
        BlockResponse.BlockResponseBuilder builder = BlockResponse.builder();
        builder.address(hash2Address(blockV5.getHash()))
                .hash(blockV5.getHash().toUnprefixedHexString())
                .balance(balance.toDecimal(9, XUnit.XDAG).toPlainString())
                .blockTime(xdagTimestampToMs(blockV5.getTimestamp()))
                .timeStamp(blockV5.getTimestamp())
                .diff(toQuantityJsonHex(info.getDifficulty().toBigInteger()))
                .state(info.isMainBlock() ? "Main" : "Orphan")
                .type(getTypeV5(blockV5))
                .height(info.getHeight());

        // Add transaction history if page != 0
        if (page != 0) {
            builder.transactions(getTxHistoryV5(blockV5.getHash(), page, parameters))
                    .totalPage(totalPage);
        }

        totalPage = 1;
        return builder.build();
    }

    private BlockResponse transferBlockToBriefBlockResultDTO(BlockV5 blockV5) {
        if (null == blockV5) {
            return null;
        }

        // Phase 8.2.3: Restored using TransactionStore for balance calculation
        BlockInfo info = blockV5.getInfo();
        if (info == null) {
            return null;
        }

        // Calculate balance from Transactions
        List<Transaction> transactions = kernel.getTransactionStore().getTransactionsByBlock(blockV5.getHash());
        XAmount balance = XAmount.ZERO;
        for (Transaction tx : transactions) {
            balance = balance.add(tx.getAmount());
        }

        // Build response
        BlockResponse.BlockResponseBuilder builder = BlockResponse.builder();
        builder.address(hash2Address(blockV5.getHash()))
                .hash(blockV5.getHash().toUnprefixedHexString())
                .balance(balance.toDecimal(9, XUnit.XDAG).toPlainString())
                .blockTime(xdagTimestampToMs(info.getTimestamp()))
                .timeStamp(info.getTimestamp())
                .diff(toQuantityJsonHex(info.getDifficulty().toBigInteger()))
                .state(info.isMainBlock() ? "Main" : "Orphan")
                .type(getTypeV5(blockV5))
                .height(info.getHeight());

        return builder.build();
    }

    // Phase 8.2.3: DELETED - getType(Block block)
    // Obsolete: Uses Block.getInputs()/getOutputs()/getInsigs() which no longer exist
    // v5.1: Type can be determined from BlockInfo.isMainBlock() and Link structure

    /**
     * Get block type for v5.1 BlockV5 (Phase 8.2.3 restoration)
     *
     * @param block BlockV5 to determine type for
     * @return Block type string (Main/Transaction/Wallet)
     */
    private String getTypeV5(BlockV5 block) {
        if (block == null || block.getInfo() == null) {
            return "Unknown";
        }

        // Main blocks
        if (block.getInfo().isMainBlock()) {
            return "Main";
        }

        // Check links to determine Transaction vs Wallet
        List<Link> links = block.getLinks();
        if (links == null || links.isEmpty()) {
            return "Wallet";
        }

        // If has transaction links, it's a Transaction block
        boolean hasTransactionLinks = links.stream().anyMatch(Link::isTransaction);
        return hasTransactionLinks ? "Transaction" : "Wallet";
    }

    private void checkParam(String value, String remark, ProcessResponse processResponse) {
        try {
            double amount = BasicUtils.getDouble(value);
            if (amount < 0) {
                processResponse.setCode(ERR_XDAG_AMOUNT);
                processResponse.setErrMsg("The transfer amount must be greater than 0");
            }

        } catch (NumberFormatException e) {
            processResponse.setCode(ERR_XDAG_PARAM);
            processResponse.setErrMsg(e.getMessage());
        }
    }

    private void checkPassword(String passphrase, ProcessResponse result) {
        Wallet wallet = new Wallet(kernel.getConfig());
        try {
            boolean res = wallet.unlock(passphrase);
            if (!res) {
                result.setCode(ERR_XDAG_WALLET_LOCKED);
                result.setErrMsg("wallet unlock failed");
            }
        } catch (Exception e) {
            result.setCode(ERR_XDAG_WALLET);
            result.setErrMsg(e.getMessage());
        }
    }

    /**
     * RPC transaction creation method (Phase 8.1: BlockV5 + Transaction migration)
     *
     * Phase 8.1.1: Single-account RPC transactions
     * Phase 8.1.2: Multi-account RPC transactions (deferred)
     *
     * @param sendValue Amount to send in XDAG
     * @param fromAddress Source address (null = multi-account aggregation, deprecated in Phase 8.1.1)
     * @param toAddress Destination address
     * @param remark Optional transaction remark
     * @param txNonce Transaction nonce (null = auto-calculate)
     * @param processResponse Response object to populate
     */
    public void doXfer(
            double sendValue,
            Bytes32 fromAddress,
            Bytes32 toAddress,
            String remark,
            UInt64 txNonce,
            ProcessResponse processResponse
    ) {
        // Validate amount
        XAmount amount;
        try {
            amount = XAmount.of(BigDecimal.valueOf(sendValue), XUnit.XDAG);
        } catch (XdagOverFlowException e) {
            processResponse.setCode(ERR_XDAG_PARAM);
            processResponse.setErrMsg("param invalid");
            return;
        }

        XAmount fee = MIN_GAS;  // 0.1 XDAG fee
        XAmount totalRequired = amount.add(fee);

        // Phase 8.1.1: Single-account BlockV5 + Transaction path
        if (fromAddress != null) {
            // Extract address bytes (20 bytes)
            MutableBytes32 from = MutableBytes32.create();
            from.set(8, fromAddress.slice(8, 20));
            byte[] fromAddr = from.slice(8, 20).toArray();

            // Check balance
            XAmount balance = kernel.getAddressStore().getBalanceByAddress(fromAddr);
            if (compareAmountTo(balance, totalRequired) < 0) {
                processResponse.setCode(ERR_XDAG_BALANCE);
                processResponse.setErrMsg("Insufficient balance. Need " +
                        totalRequired.toDecimal(9, XUnit.XDAG).toPlainString() +
                        " XDAG, have " + balance.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG");
                return;
            }

            // Get account keypair
            ECKeyPair account = kernel.getWallet().getAccount(fromAddr);
            if (account == null) {
                processResponse.setCode(ERR_XDAG_WALLET);
                processResponse.setErrMsg("Account not found in wallet");
                return;
            }

            // Get/validate nonce
            UInt64 finalNonce;
            if (txNonce == null) {
                UInt64 currentTxQuantity = kernel.getAddressStore().getTxQuantity(fromAddr);
                finalNonce = currentTxQuantity.add(UInt64.ONE);
            } else {
                UInt64 expectedNonce = kernel.getAddressStore().getTxQuantity(fromAddr).add(UInt64.ONE);
                if (!txNonce.equals(expectedNonce)) {
                    processResponse.setCode(ERR_XDAG_PARAM);
                    processResponse.setErrMsg("The nonce passed is incorrect. Expected " +
                            expectedNonce.toLong() + ", got " + txNonce.toLong());
                    return;
                }
                finalNonce = txNonce;
            }

            try {
                // Encode remark as bytes
                Bytes remarkData = Bytes.EMPTY;
                if (remark != null && !remark.isEmpty()) {
                    remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));
                }

                // Create Transaction object
                Transaction tx = Transaction.builder()
                        .from(fromAddress)
                        .to(toAddress)
                        .amount(amount)
                        .nonce(finalNonce.toLong())
                        .fee(fee)
                        .data(remarkData)
                        .build();

                // Sign Transaction
                Transaction signedTx = tx.sign(account);

                // Validate Transaction
                if (!signedTx.isValid()) {
                    processResponse.setCode(ERR_XDAG_PARAM);
                    processResponse.setErrMsg("Transaction validation failed");
                    return;
                }

                if (!signedTx.verifySignature()) {
                    processResponse.setCode(ERR_XDAG_PARAM);
                    processResponse.setErrMsg("Transaction signature verification failed");
                    return;
                }

                // Save Transaction to TransactionStore
                kernel.getTransactionStore().saveTransaction(signedTx);

                // Create BlockV5 with Transaction link
                List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));

                // Create BlockHeader
                BlockHeader header = BlockHeader.builder()
                        .timestamp(XdagTime.getCurrentTimestamp())
                        .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                        .nonce(Bytes32.ZERO)
                        .coinbase(fromAddress)
                        .hash(null)  // Will be calculated by BlockV5.getHash()
                        .build();

                // Create BlockV5
                BlockV5 block = BlockV5.builder()
                        .header(header)
                        .links(links)
                        .info(null)  // Will be initialized by tryToConnect()
                        .build();

                // Import to blockchain
                ImportResult result = kernel.getBlockchain().tryToConnect(block);

                if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                    // Update nonce in address store
                    kernel.getAddressStore().updateTxQuantity(fromAddr, finalNonce);

                    // Broadcast BlockV5
                    int ttl = kernel.getConfig().getNodeSpec().getTTL();
                    kernel.broadcastBlockV5(block, ttl);

                    // Success response
                    processResponse.setCode(SUCCESS);
                    processResponse.setResInfo(Lists.newArrayList(
                            BasicUtils.hash2Address(block.getHash())
                    ));

                    log.info("RPC transaction successful (BlockV5): tx={}, block={}, amount={} XDAG",
                            signedTx.getHash().toHexString().substring(0, 16) + "...",
                            BasicUtils.hash2Address(block.getHash()),
                            amount.toDecimal(9, XUnit.XDAG).toPlainString());
                } else {
                    processResponse.setCode(ERR_XDAG_PARAM);
                    processResponse.setErrMsg("Transaction import failed: " + result.name() +
                            (result.getErrorInfo() != null ? " - " + result.getErrorInfo() : ""));

                    log.error("RPC transaction import failed: result={}, block={}",
                            result, block.getHash().toHexString());
                }

            } catch (Exception e) {
                log.error("RPC transaction creation failed", e);
                processResponse.setCode(ERR_XDAG_PARAM);
                processResponse.setErrMsg("Transaction creation failed: " + e.getMessage());
            }

            return;
        }

        // Phase 8.1.2: Multi-account aggregation path
        log.debug("Multi-account RPC transaction requested (from == null)");

        // Find all accounts and calculate how much each can contribute
        List<ECKeyPair> accounts = kernel.getWallet().getAccounts();
        List<ContributingAccount> contributors = new ArrayList<>();
        XAmount remainingAmount = amount;

        for (ECKeyPair account : accounts) {
            if (remainingAmount.compareTo(XAmount.ZERO) <= 0) {
                break;
            }

            byte[] addr = toBytesAddress(account).toArray();
            XAmount balance = kernel.getAddressStore().getBalanceByAddress(addr);

            // Skip accounts without sufficient balance for at least the fee
            if (balance.compareTo(fee) <= 0) {
                continue;
            }

            // Calculate how much this account can contribute (balance - fee)
            XAmount maxContribution = balance.subtract(fee);
            XAmount contribution = remainingAmount.compareTo(maxContribution) <= 0 ? remainingAmount : maxContribution;

            // Get nonce for this account
            UInt64 accountNonce;
            if (txNonce == null) {
                UInt64 currentTxQuantity = kernel.getAddressStore().getTxQuantity(addr);
                accountNonce = currentTxQuantity.add(UInt64.ONE);
            } else {
                // For multi-account, txNonce is not supported (which account's nonce?)
                processResponse.setCode(ERR_XDAG_PARAM);
                processResponse.setErrMsg("Manual nonce not supported for multi-account transactions (from == null)");
                return;
            }

            contributors.add(new ContributingAccount(account, addr, contribution, accountNonce));
            remainingAmount = remainingAmount.subtract(contribution);
        }

        // Check if we have enough total balance
        if (remainingAmount.compareTo(XAmount.ZERO) > 0) {
            processResponse.setCode(ERR_XDAG_BALANCE);
            processResponse.setErrMsg("Insufficient total balance across all accounts. Need " +
                    amount.toDecimal(9, XUnit.XDAG).toPlainString() +
                    " XDAG, missing " + remainingAmount.toDecimal(9, XUnit.XDAG).toPlainString() + " XDAG");
            return;
        }

        if (contributors.isEmpty()) {
            processResponse.setCode(ERR_XDAG_BALANCE);
            processResponse.setErrMsg("No accounts with sufficient balance found");
            return;
        }

        // Create one Transaction per contributing account
        List<String> txHashes = new ArrayList<>();
        int successCount = 0;

        for (ContributingAccount contributor : contributors) {
            try {
                // Encode remark as bytes
                Bytes remarkData = Bytes.EMPTY;
                if (remark != null && !remark.isEmpty()) {
                    remarkData = Bytes.wrap(remark.getBytes(StandardCharsets.UTF_8));
                }

                // Get fromAddress hash for this account
                Bytes32 accountFromAddress = keyPair2Hash(contributor.account);

                // Create Transaction object
                Transaction tx = Transaction.builder()
                        .from(accountFromAddress)
                        .to(toAddress)
                        .amount(contributor.contribution)
                        .nonce(contributor.nonce.toLong())
                        .fee(fee)
                        .data(remarkData)
                        .build();

                // Sign Transaction
                Transaction signedTx = tx.sign(contributor.account);

                // Validate Transaction
                if (!signedTx.isValid()) {
                    log.error("Multi-account transaction validation failed for account {}",
                            Base58.encodeCheck(contributor.address));
                    continue;
                }

                if (!signedTx.verifySignature()) {
                    log.error("Multi-account transaction signature verification failed for account {}",
                            Base58.encodeCheck(contributor.address));
                    continue;
                }

                // Save Transaction to TransactionStore
                kernel.getTransactionStore().saveTransaction(signedTx);

                // Create BlockV5 with Transaction link
                List<Link> links = Lists.newArrayList(Link.toTransaction(signedTx.getHash()));

                // Create BlockHeader
                BlockHeader header = BlockHeader.builder()
                        .timestamp(XdagTime.getCurrentTimestamp())
                        .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                        .nonce(Bytes32.ZERO)
                        .coinbase(accountFromAddress)
                        .hash(null)  // Will be calculated by BlockV5.getHash()
                        .build();

                // Create BlockV5
                BlockV5 block = BlockV5.builder()
                        .header(header)
                        .links(links)
                        .info(null)  // Will be initialized by tryToConnect()
                        .build();

                // Import to blockchain
                ImportResult result = kernel.getBlockchain().tryToConnect(block);

                if (result == ImportResult.IMPORTED_BEST || result == ImportResult.IMPORTED_NOT_BEST) {
                    // Update nonce in address store
                    kernel.getAddressStore().updateTxQuantity(contributor.address, contributor.nonce);

                    // Broadcast BlockV5
                    int ttl = kernel.getConfig().getNodeSpec().getTTL();
                    kernel.broadcastBlockV5(block, ttl);

                    // Add to success list
                    txHashes.add(BasicUtils.hash2Address(block.getHash()));
                    successCount++;

                    log.debug("Multi-account transaction successful: account={}, amount={} XDAG, block={}",
                            Base58.encodeCheck(contributor.address),
                            contributor.contribution.toDecimal(9, XUnit.XDAG).toPlainString(),
                            BasicUtils.hash2Address(block.getHash()));
                } else {
                    log.error("Multi-account transaction import failed for account {}: result={}, error={}",
                            Base58.encodeCheck(contributor.address),
                            result,
                            result.getErrorInfo());
                }

            } catch (Exception e) {
                log.error("Multi-account transaction failed for account {}: {}",
                        Base58.encodeCheck(contributor.address), e.getMessage(), e);
            }
        }

        // Return results
        if (successCount > 0) {
            processResponse.setCode(SUCCESS);
            processResponse.setResInfo(txHashes);

            log.info("Multi-account RPC transaction completed: {} of {} accounts successful, total amount={} XDAG",
                    successCount, contributors.size(), amount.toDecimal(9, XUnit.XDAG).toPlainString());
        } else {
            processResponse.setCode(ERR_XDAG_PARAM);
            processResponse.setErrMsg("All multi-account transactions failed to import");
        }
    }

    /**
     * Helper class for multi-account transaction aggregation
     */
    private static class ContributingAccount {
        final ECKeyPair account;
        final byte[] address;
        final XAmount contribution;
        final UInt64 nonce;

        ContributingAccount(ECKeyPair account, byte[] address, XAmount contribution, UInt64 nonce) {
            this.account = account;
            this.address = address;
            this.contribution = contribution;
            this.nonce = nonce;
        }
    }

    private Bytes32 checkFrom(String fromAddress, ProcessResponse processResponse)
        throws AddressFormatException {
        if (StringUtils.isBlank(fromAddress)) {
            return null;
        } else {
            return checkAddress(fromAddress, processResponse);
        }
    }

    private Bytes32 checkTo(String toAddress, ProcessResponse processResponse)
        throws AddressFormatException {
        if (StringUtils.isBlank(toAddress)) {
            processResponse.setCode(ERR_XDAG_DEST);
            processResponse.setErrMsg("To address is illegal");
            return null;
        } else {
            return checkAddress(toAddress, processResponse);
        }
    }

    private Bytes32 checkAddress(String address, ProcessResponse processResponse)
        throws AddressFormatException {
        Bytes32 hash = null;

        // check whether existed in blockchain
        if (WalletUtils.checkAddress(address)) {
            hash = pubAddress2Hash(address);
        } else {
            processResponse.setCode(ERR_XDAG_ADDRESS);
            processResponse.setErrMsg("To address is illegal");
        }

        return hash;
    }

    // Phase 8.2.2: DELETED - checkTransaction(Block block)
    // Obsolete: Validation moved to Transaction.isValid() and doXfer()
    // v5.1 validates Transaction objects, not Block.getInputs()

    @Override
    public Object xdag_syncing(){
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        long currentBlock = this.blockchain.getChainStats().getMainBlockCount();
        long highestBlock = Math.max(this.blockchain.getChainStats().getTotalMainBlockCount(), currentBlock);
        SyncingResult s = new SyncingResult();
        s.isSyncDone = false;

        Config config = kernel.getConfig();
        if (config instanceof MainnetConfig) {
            if (kernel.getXdagState() != XdagState.SYNC) {
                return s;
            }
        } else if (config instanceof TestnetConfig) {
            if (kernel.getXdagState() != XdagState.STST) {
                return s;
            }
        } else if (config instanceof DevnetConfig) {
            if (kernel.getXdagState() != XdagState.SDST) {
                return s;
            }
        }

        try {
            s.currentBlock = Long.toString(currentBlock);
            s.highestBlock = Long.toString(highestBlock);
            s.isSyncDone = true;

            return s;
        } finally {
            log.debug("xdag_syncing():current {}, highest {}, isSyncDone {}", s.currentBlock, s.highestBlock,
                    s.isSyncDone);
        }

    }

    static class SyncingResult {

        public String currentBlock;
        public String highestBlock;
        public boolean isSyncDone;

    }


}
