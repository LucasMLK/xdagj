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
package io.xdag.api.http.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.xdag.DagKernel;
import io.xdag.Network;
import io.xdag.api.service.MiningApiService;
import io.xdag.api.service.dto.AccountInfo;
import io.xdag.api.service.dto.BlockDetail;
import io.xdag.api.service.dto.BlockSummary;
import io.xdag.api.service.dto.BlockSubmitResult;
import io.xdag.api.service.dto.ChainStatsInfo;
import io.xdag.api.service.dto.NodeStatusInfo;
import io.xdag.api.service.dto.PagedResult;
import io.xdag.api.service.dto.TransactionInfo;
import io.xdag.api.service.AccountApiService;
import io.xdag.api.service.BlockApiService;
import io.xdag.api.service.ChainApiService;
import io.xdag.api.service.NetworkApiService;
import io.xdag.api.service.TransactionApiService;
import io.xdag.api.service.dto.RandomXInfo;
import io.xdag.consensus.pow.PowAlgorithm;
import io.xdag.consensus.pow.RandomXMemory;
import io.xdag.consensus.pow.RandomXPow;
import io.xdag.consensus.pow.RandomXSeedManager;
import io.xdag.core.Block;
import io.xdag.core.XUnit;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.api.http.auth.ApiKeyStore;
import io.xdag.api.http.auth.Permission;
import io.xdag.api.http.pagination.PageRequest;
import io.xdag.api.http.pagination.PaginationInfo;
import io.xdag.api.http.response.*;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.TimeUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

@Slf4j
public class HttpApiHandlerV1 extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final DagKernel dagKernel;
    private final ObjectMapper objectMapper;
    private final ApiKeyStore apiKeyStore;
    private final AccountApiService accountApiService;
    private final TransactionApiService transactionApiService;
    private final BlockApiService blockApiService;
    private final ChainApiService chainApiService;
    private final NetworkApiService networkApiService;

    public HttpApiHandlerV1(DagKernel dagKernel, ApiKeyStore apiKeyStore) {
        this.dagKernel = dagKernel;
        this.apiKeyStore = apiKeyStore;
        this.objectMapper = new ObjectMapper();
        this.accountApiService = new AccountApiService(dagKernel);
        this.transactionApiService = new TransactionApiService(dagKernel);
        this.blockApiService = new BlockApiService(dagKernel, transactionApiService);
        this.chainApiService = new ChainApiService(dagKernel, accountApiService);
        this.networkApiService = new NetworkApiService(dagKernel);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        HttpMethod method = request.method();

        log.debug("HTTP request: {} {}", method, uri);

        if (uri.startsWith("/api/v1/")) {
            handleApiRequest(ctx, request, uri, method);
        } else {
            sendJsonResponse(ctx, NOT_FOUND, createError("Endpoint not found", 404));
        }
    }

    private void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request, String uri, HttpMethod method) {
        try {
            String apiKey = extractApiKey(request);
            Object result = routeRequest(uri, method, request, apiKey);
            if (result == null) {
                sendJsonResponse(ctx, NOT_FOUND, createError("Resource not found", 404));
            } else {
                sendJsonResponse(ctx, OK, result);
            }
        } catch (SecurityException e) {
            log.warn("Unauthorized access: {}", e.getMessage());
            sendJsonResponse(ctx, UNAUTHORIZED, createError(e.getMessage(), 401));
        } catch (IllegalArgumentException e) {
            log.warn("Bad request: {}", e.getMessage());
            sendJsonResponse(ctx, BAD_REQUEST, createError(e.getMessage(), 400));
        } catch (Exception e) {
            log.error("Error handling request: {}", uri, e);
            sendJsonResponse(ctx, INTERNAL_SERVER_ERROR, createError("Internal server error", 500));
        }
    }

    private String extractApiKey(FullHttpRequest request) {
        String auth = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return null;
    }

    private void checkPermission(String apiKey, Permission required) {
        if (!apiKeyStore.hasPermission(apiKey, required)) {
            throw new SecurityException("Insufficient permissions or invalid API key");
        }
    }

    private Object routeRequest(String uri, HttpMethod method, FullHttpRequest request, String apiKey) {
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        String path = decoder.path();
        Map<String, String> params = new HashMap<>();
        decoder.parameters().forEach((key, values) -> {
            if (!values.isEmpty()) {
                params.put(key, values.getFirst());
            }
        });

        if (path.equals("/api/v1/accounts") && method == HttpMethod.GET) {
            checkPermission(apiKey, Permission.READ);
            PageRequest pageRequest = PageRequest.parse(params.get("page"), params.get("size"));
            return handleGetAccounts(pageRequest);
        }

        if (path.equals("/api/v1/blocks") && method == HttpMethod.GET) {
            PageRequest pageRequest = PageRequest.parse(params.get("page"), params.get("size"));
            return handleGetBlocks(pageRequest);
        }

        if (path.matches("/api/v1/accounts/[^/]+/balance") && method == HttpMethod.GET) {
            checkPermission(apiKey, Permission.READ);
            String address = extractPathParam(path, 3);
            String blockNumber = params.getOrDefault("blockNumber", "latest");
            return handleGetBalance(address, blockNumber);
        }

        if (path.matches("/api/v1/accounts/[^/]+/nonce") && method == HttpMethod.GET) {
            checkPermission(apiKey, Permission.READ);
            String address = extractPathParam(path, 3);
            String blockNumber = params.getOrDefault("blockNumber", "latest");
            return handleGetTransactionCount(address, blockNumber);
        }

        if (path.equals("/api/v1/blocks/number") && method == HttpMethod.GET) {
            return handleGetBlockNumber();
        }

        // More specific routes must come before generic /api/v1/blocks/{number}
        if (path.matches("/api/v1/blocks/hash/[^/]+") && method == HttpMethod.GET) {
            String blockHash = extractPathParam(path, 5);
            boolean fullTx = Boolean.parseBoolean(params.getOrDefault("fullTransactions", "false"));
            return handleGetBlockByHash(blockHash, fullTx);
        }

        if (path.equals("/api/v1/blocks/epoch/range") && method == HttpMethod.GET) {
            String fromEpochStr = params.get("fromEpoch");
            String toEpochStr = params.get("toEpoch");
            return handleGetBlocksByEpochRange(fromEpochStr, toEpochStr);
        }

        if (path.matches("/api/v1/blocks/epoch/[^/]+") && method == HttpMethod.GET) {
            String epochStr = extractPathParam(path, 5);
            PageRequest pageRequest = PageRequest.parse(params.get("page"), params.get("size"));
            return handleGetBlocksByEpoch(epochStr, pageRequest);
        }

        // Generic block by number - must be after all /api/v1/blocks/* specific routes
        if (path.matches("/api/v1/blocks/[^/]+") && method == HttpMethod.GET) {
            String blockNumber = extractPathParam(path, 4);
            boolean fullTx = Boolean.parseBoolean(params.getOrDefault("fullTransactions", "false"));
            return handleGetBlockByNumber(blockNumber, fullTx);
        }

        if (path.equals("/api/v1/transactions") && method == HttpMethod.GET) {
            PageRequest pageRequest = PageRequest.parse(params.get("page"), params.get("size"));
            return handleGetTransactions(pageRequest);
        }

        if (path.matches("/api/v1/transactions/[^/]+") && method == HttpMethod.GET) {
            String txHash = extractPathParam(path, 4);
            return handleGetTransactionByHash(txHash);
        }

        if (path.equals("/api/v1/transactions") && method == HttpMethod.POST) {
            checkPermission(apiKey, Permission.WRITE);
            try {
                String body = request.content().toString(CharsetUtil.UTF_8);
                Map<String, String> bodyParams = objectMapper.readValue(body, Map.class);
                String signedData = bodyParams.get("signedTransactionData");
                return handleSendRawTransaction(signedData);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid request body: " + e.getMessage());
            }
        }

        if (path.equals("/api/v1/network/syncing") && method == HttpMethod.GET) {
            return handleGetSyncing();
        }

        if (path.equals("/api/v1/network/chainId") && method == HttpMethod.GET) {
            return handleGetChainId();
        }

        if (path.equals("/api/v1/network/peers/count") && method == HttpMethod.GET) {
            return handleGetPeerCount();
        }

        if (path.equals("/api/v1/network/protocol") && method == HttpMethod.GET) {
            return handleGetProtocolVersion();
        }

        if (path.equals("/api/v1/network/coinbase") && method == HttpMethod.GET) {
            return handleGetCoinbase();
        }

        // Mining RPC endpoints (for pool server)
        if (path.equals("/api/v1/mining/randomx") && method == HttpMethod.GET) {
            return handleGetRandomXInfo();
        }

        if (path.equals("/api/v1/mining/candidate") && method == HttpMethod.GET) {
            String poolId = params.getOrDefault("poolId", "unknown");
            return handleGetCandidateBlock(poolId);
        }

        if (path.equals("/api/v1/mining/submit") && method == HttpMethod.POST) {
            checkPermission(apiKey, Permission.WRITE);
            try {
                String body = request.content().toString(CharsetUtil.UTF_8);
                Map<String, Object> bodyParams = objectMapper.readValue(body, Map.class);
                return handleSubmitMinedBlock(bodyParams);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid request body: " + e.getMessage());
            }
        }

        if (path.equals("/api/v1/mining/difficulty") && method == HttpMethod.GET) {
            return handleGetDifficulty();
        }

        // Node status endpoint
        if (path.equals("/api/v1/node/status") && method == HttpMethod.GET) {
            return handleGetNodeStatus();
        }

        return null;
    }

    private PagedResponse<AccountsResponse.AccountInfo> handleGetAccounts(PageRequest pageRequest) {
        List<AccountInfo> accounts = accountApiService.getAccounts(0);
        int totalCount = accounts.size();

        if (totalCount == 0 || pageRequest.getOffset() >= totalCount) {
            PaginationInfo pagination = PaginationInfo.of(pageRequest, totalCount);
            return PagedResponse.of(List.of(), pagination);
        }

        List<AccountsResponse.AccountInfo> accountInfos = new ArrayList<>();
        int start = pageRequest.getOffset();
        int end = Math.min(start + pageRequest.getSize(), totalCount);

        for (int i = start; i < end; i++) {
            if (i < accounts.size()) {
                AccountInfo accountInfo = accounts.get(i);
                accountInfos.add(AccountsResponse.AccountInfo.builder()
                        .address(accountInfo.getAddress())
                        .balance(accountInfo.getBalance().toDecimal(9, XUnit.XDAG).toPlainString())
                        .nonce(accountInfo.getNonce())
                        .type("hd")
                        .build());
            }
        }

        PaginationInfo pagination = PaginationInfo.of(pageRequest, totalCount);
        return PagedResponse.of(accountInfos, pagination);
    }

    private PagedResponse<BlockSummaryResponse> handleGetBlocks(PageRequest pageRequest) {
        PagedResult<BlockSummary> pagedBlocks =
                blockApiService.getMainBlocksPage(pageRequest.getPage(), pageRequest.getSize());

        List<BlockSummaryResponse> summaries = new ArrayList<>();
        for (BlockSummary summary : pagedBlocks.getItems()) {
            summaries.add(convertBlockSummary(summary));
        }

        PaginationInfo pagination = PaginationInfo.of(pageRequest, pagedBlocks.getTotal());
        return PagedResponse.of(summaries, pagination);
    }

    private AccountBalanceResponse handleGetBalance(String address, String blockNumber) {
        AccountInfo accountInfo = accountApiService.getAccountByAddress(address);

        if (accountInfo == null) {
            return AccountBalanceResponse.builder()
                    .address(address)
                    .balance("0")
                    .blockNumber(resolveBlockNumber(blockNumber))
                    .build();
        }

        return AccountBalanceResponse.builder()
                .address(address)
                .balance(accountInfo.getBalance().toDecimal(9, XUnit.XDAG).toPlainString())
                .blockNumber(resolveBlockNumber(blockNumber))
                .build();
    }

    private AccountNonceResponse handleGetTransactionCount(String address, String blockNumber) {
        AccountInfo accountInfo = accountApiService.getAccountByAddress(address);
        long nonce = (accountInfo != null) ? accountInfo.getNonce() : 0;

        return AccountNonceResponse.builder()
                .address(address)
                .nonce(String.format("0x%x", nonce))
                .blockNumber(resolveBlockNumber(blockNumber))
                .build();
    }

    private BlockNumberResponse handleGetBlockNumber() {
        ChainStatsInfo stats = chainApiService.getChainStats();
        long height = (stats != null && stats.getTopBlockHeight() != null) ? stats.getTopBlockHeight() : 0;

        return BlockNumberResponse.builder()
                .blockNumber(String.format("0x%x", height))
                .build();
    }

    private BlockDetailResponse handleGetBlockByNumber(String blockNumber, boolean fullTransactions) {
        long height = parseBlockNumber(blockNumber);

        io.xdag.core.Block block = dagKernel.getDagChain().getMainBlockByHeight(height);
        if (block == null) {
            return null;
        }

        BlockDetail blockDetail = blockApiService.getBlockDetail(block.getHash());
        // Check if blockDetail is null (block exists but has no BlockInfo)
        if (blockDetail == null) {
            log.warn("Block at height {} exists but has no BlockInfo", height);
            return null;
        }

        return convertBlockDetailToResponse(blockDetail, fullTransactions);
    }

    private BlockDetailResponse handleGetBlockByHash(String blockHash, boolean fullTransactions) {
        Bytes32 hash = Bytes32.fromHexString(blockHash);
        BlockDetail blockDetail = blockApiService.getBlockDetail(hash);

        if (blockDetail == null) {
            return null;
        }

        return convertBlockDetailToResponse(blockDetail, fullTransactions);
    }

    private EpochBlocksResponse handleGetBlocksByEpoch(String epochStr, PageRequest pageRequest) {
        try {
            long epoch = Long.parseLong(epochStr);

            PagedResult<BlockSummary> pagedBlocks =
                    blockApiService.getBlocksByEpochPage(epoch, pageRequest.getPage(), pageRequest.getSize());

            List<BlockSummaryResponse> blockResponses = new ArrayList<>();
            for (BlockSummary summary : pagedBlocks.getItems()) {
                blockResponses.add(convertBlockSummary(summary));
            }

            PaginationInfo pagination = PaginationInfo.of(pageRequest, pagedBlocks.getTotal());
            return EpochBlocksResponse.of(epoch, blockResponses, pagination, pagedBlocks.getTotal());

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid epoch number: " + epochStr);
        }
    }

    private Object handleGetBlocksByEpochRange(String fromEpochStr, String toEpochStr) {
        try {
            if (fromEpochStr == null || toEpochStr == null) {
                throw new IllegalArgumentException("Both fromEpoch and toEpoch parameters are required");
            }

            long fromEpoch = Long.parseLong(fromEpochStr);
            long toEpoch = Long.parseLong(toEpochStr);

            List<BlockApiService.EpochBlocks> epochBlocksList =
                    blockApiService.getBlocksByEpochRange(fromEpoch, toEpoch);

            Map<String, Object> response = new HashMap<>();
            response.put("fromEpoch", fromEpoch);
            response.put("toEpoch", toEpoch);
            response.put("epochCount", epochBlocksList.size());

            List<Map<String, Object>> epochs = new ArrayList<>();
            for (BlockApiService.EpochBlocks epochBlocks : epochBlocksList) {
                Map<String, Object> epochData = new HashMap<>();
                epochData.put("epoch", epochBlocks.getEpoch());
                epochData.put("blockCount", epochBlocks.getBlockCount());

                List<BlockSummaryResponse> blockResponses = new ArrayList<>();
                for (BlockSummary summary : epochBlocks.getBlocks()) {
                    blockResponses.add(convertBlockSummary(summary));
                }
                epochData.put("blocks", blockResponses);

                epochs.add(epochData);
            }
            response.put("epochs", epochs);

            return response;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid epoch number format");
        }
    }

    private TransactionDetailResponse handleGetTransactionByHash(String transactionHash) {
        Bytes32 txHash = Bytes32.fromHexString(transactionHash);
        TransactionInfo tx = transactionApiService.getTransaction(txHash);

        if (tx == null) {
            return null;
        }

        return convertTransactionInfoToResponse(tx);
    }

    private PagedResponse<TransactionDetailResponse> handleGetTransactions(PageRequest pageRequest) {
        PagedResult<TransactionInfo> pagedTransactions =
                transactionApiService.getRecentTransactionsPage(pageRequest.getPage(), pageRequest.getSize());

        List<TransactionDetailResponse> responses = new ArrayList<>();
        for (TransactionInfo tx : pagedTransactions.getItems()) {
            responses.add(convertTransactionInfoToResponse(tx));
        }

        PaginationInfo pagination = PaginationInfo.of(pageRequest, pagedTransactions.getTotal());
        return PagedResponse.of(responses, pagination);
    }

    private SendTransactionResponse handleSendRawTransaction(String signedTransactionData) {
        try {
            // 1. Validate input
            if (signedTransactionData == null || signedTransactionData.isEmpty()) {
                throw new IllegalArgumentException("signedTransactionData is required");
            }

            // Remove 0x prefix if present
            String hexData = signedTransactionData.startsWith("0x")
                    ? signedTransactionData.substring(2)
                    : signedTransactionData;

            // 2. Parse transaction from hex string
            byte[] txBytes = org.apache.tuweni.bytes.Bytes.fromHexString(hexData).toArray();
            io.xdag.core.Transaction transaction = io.xdag.core.Transaction.fromBytes(txBytes);

            log.info("Received transaction submission: hash={}, from={}, to={}, amount={}, nonce={}",
                    transaction.getHash().toHexString().substring(0, 16) + "...",
                    transaction.getFrom().toHexString().substring(0, 16) + "...",
                    transaction.getTo().toHexString().substring(0, 16) + "...",
                    transaction.getAmount().toDecimal(9, io.xdag.core.XUnit.XDAG).toPlainString(),
                    transaction.getNonce());

            // 3. Basic validation
            if (!transaction.isValid()) {
                log.warn("Transaction validation failed: invalid transaction format");
                return SendTransactionResponse.builder()
                        .transactionHash(transaction.getHash().toHexString())
                        .status("rejected")
                        .message("Invalid transaction format")
                        .build();
            }

            // 4. Verify signature
            if (!transaction.verifySignature()) {
                log.warn("Transaction signature verification failed");
                return SendTransactionResponse.builder()
                        .transactionHash(transaction.getHash().toHexString())
                        .status("rejected")
                        .message("Invalid signature")
                        .build();
            }

            // 5. Add to transaction pool
            io.xdag.core.TransactionPool txPool = dagKernel.getTransactionPool();
            if (txPool == null) {
                log.warn("Transaction pool not available");
                return SendTransactionResponse.builder()
                        .transactionHash(transaction.getHash().toHexString())
                        .status("rejected")
                        .message("Transaction pool not available")
                        .build();
            }

            boolean added = txPool.addTransaction(transaction);
            if (added) {
                log.info("Transaction {} added to pool successfully",
                        transaction.getHash().toHexString().substring(0, 16) + "...");

                // Broadcast to P2P network (Phase 3)
                io.xdag.core.TransactionBroadcastManager broadcastManager =
                        dagKernel.getTransactionBroadcastManager();
                if (broadcastManager != null) {
                    // Broadcast to all peers (no exclusion since this is from RPC)
                    broadcastManager.broadcastTransaction(transaction, null);
                    log.debug("Transaction {} broadcast to network",
                            transaction.getHash().toHexString().substring(0, 16) + "...");
                }

                return SendTransactionResponse.builder()
                        .transactionHash(transaction.getHash().toHexString())
                        .status("success")
                        .message("Transaction submitted to pool")
                        .build();
            } else {
                log.warn("Transaction {} rejected by pool (duplicate, invalid nonce, or insufficient balance)",
                        transaction.getHash().toHexString().substring(0, 16) + "...");
                return SendTransactionResponse.builder()
                        .transactionHash(transaction.getHash().toHexString())
                        .status("rejected")
                        .message("Transaction rejected by pool (check nonce, balance, or duplicate)")
                        .build();
            }

        } catch (IllegalArgumentException e) {
            log.warn("Invalid transaction data: {}", e.getMessage());
            return SendTransactionResponse.builder()
                    .transactionHash("0x0")
                    .status("error")
                    .message("Invalid transaction data: " + e.getMessage())
                    .build();
        } catch (Exception e) {
            log.error("Error processing transaction submission", e);
            return SendTransactionResponse.builder()
                    .transactionHash("0x0")
                    .status("error")
                    .message("Internal error: " + e.getMessage())
                    .build();
        }
    }

    private Object handleGetSyncing() {
        ChainStatsInfo stats = chainApiService.getChainStats();

        // Note: syncProgress removed from ChainStats - always return false for now
        // TODO: Implement proper sync state detection using DagChain.isNodeBehind()
        if (stats == null || stats.getSyncProgress() >= 100.0) {
            return false;
        }

        return SyncingResponse.builder()
                .syncing(true)
                .startingBlock("0x0")
                .currentBlock(String.format("0x%x", stats.getTopBlockHeight() != null ? stats.getTopBlockHeight() : 0))
                .highestBlock(String.format("0x%x", stats.getTopBlockHeight() != null ? stats.getTopBlockHeight() : 0))
                .progress(stats.getSyncProgress())
                .build();
    }

    private ChainIdResponse handleGetChainId() {
        Network network = dagKernel.getConfig().getNodeSpec().getNetwork();
        String chainId = switch (network) {
            case MAINNET -> "0x1";
            case TESTNET -> "0x2";
            case DEVNET -> "0x3";
            default -> "0x0";
        };

        return ChainIdResponse.builder()
                .chainId(chainId)
                .networkType(network.name().toLowerCase())
                .build();
    }

    private PeerCountResponse handleGetPeerCount() {
        return PeerCountResponse.builder().peerCount("0x0").build();
    }

    private ProtocolVersionResponse handleGetProtocolVersion() {
        return ProtocolVersionResponse.builder().protocolVersion("5.1.0").build();
    }

    private CoinbaseResponse handleGetCoinbase() {
        if (dagKernel.getWallet() != null && dagKernel.getWallet().getDefKey() != null) {
            return CoinbaseResponse.builder()
                    .coinbase(AddressUtils.toBase58Address(dagKernel.getWallet().getDefKey()))
                    .build();
        }
        return null;
    }

    // ========== Mining RPC Handlers ==========

    private Object handleGetRandomXInfo() {
        try {
            io.xdag.api.service.MiningApiService miningApi = dagKernel.getMiningApiService();
            if (miningApi == null) {
                log.warn("Mining API service not available");
                return null;
            }

            RandomXInfo info = miningApi.getRandomXInfo();
            if (info == null) {
                log.warn("RandomX info returned null");
                return null;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("enabled", info.isEnabled());
            response.put("currentEpoch", info.getCurrentEpoch());
            response.put("forkEpoch", info.getForkEpoch());
            response.put("vmReady", info.isVmReady());
            response.put("seedEpochInterval", info.getSeedEpochInterval());
            response.put("forkActive", info.isForkActive());
            response.put("algorithmName", info.getAlgorithmName());

            return response;
        } catch (Exception e) {
            log.error("Error getting RandomX info", e);
            return null;
        }
    }

    private Object handleGetCandidateBlock(String poolId) {
        try {
            MiningApiService miningApi = dagKernel.getMiningApiService();
            if (miningApi == null) {
                log.warn("Mining API service not available");
                return null;
            }

            Block block = miningApi.getCandidateBlock(poolId);
            if (block == null) {
                log.warn("Failed to generate candidate block for pool '{}'", poolId);
                return null;
            }

            // Convert Block to response format
            Map<String, Object> response = new HashMap<>();
            long epochNumber = block.getEpoch();
            long timestamp = TimeUtils.epochNumberToMainTime(epochNumber);
            response.put("hash", block.getHash().toHexString());
            response.put("timestamp", timestamp);
            response.put("epoch", epochNumber);

            // Block header information
            if (block.getHeader() != null) {
                response.put("difficulty", block.getHeader().getDifficulty().toHexString());
                response.put("nonce", block.getHeader().getNonce().toHexString());
            }

            // Serialize full block data as hex
            byte[] blockBytes = block.toBytes();
            response.put("blockData", Bytes.wrap(blockBytes).toHexString());
            response.put("size", blockBytes.length);

            // Add RandomX flag - always true since we only support RandomX now
            response.put("isRandomX", true);

            // Add RandomX seed if available
            try {
                PowAlgorithm powAlgorithm = dagKernel.getPowAlgorithm();
                if (powAlgorithm instanceof RandomXPow randomXPow) {
                    RandomXSeedManager seedManager = randomXPow.getSeedManager();

                    // Get active memory for current epoch (use getActiveMemory instead of getPoolMemory)
                    RandomXMemory memory = seedManager.getActiveMemory(block.getEpoch());
                    if (memory != null && memory.getSeed() != null) {
                        String seedHex = Bytes.wrap(memory.getSeed()).toHexString();
                        response.put("randomXSeed", seedHex);
                        log.debug("Added RandomX seed to candidate block: {}", seedHex.substring(0, 16) + "...");
                    } else {
                        log.warn("RandomX memory or seed not available for epoch {}",
                                block.getEpoch());
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to get RandomX seed: {}", e.getMessage());
            }

            log.info("Provided candidate block to pool '{}': hash={}", poolId,
                    block.getHash().toHexString().substring(0, 18) + "...");

            return response;

        } catch (Exception e) {
            log.error("Error getting candidate block for pool '{}'", poolId, e);
            return null;
        }
    }

    private Object handleSubmitMinedBlock(Map<String, Object> params) {
        try {
            io.xdag.api.service.MiningApiService miningApi = dagKernel.getMiningApiService();
            if (miningApi == null) {
                log.warn("Mining API service not available");
                Map<String, Object> error = new HashMap<>();
                error.put("accepted", false);
                error.put("message", "Mining API service not available");
                error.put("errorCode", "SERVICE_UNAVAILABLE");
                return error;
            }

            // Parse block from request
            String blockDataHex = (String) params.get("blockData");
            String poolId = (String) params.getOrDefault("poolId", "unknown");

            if (blockDataHex == null || blockDataHex.isEmpty()) {
                Map<String, Object> error = new HashMap<>();
                error.put("accepted", false);
                error.put("message", "Missing blockData parameter");
                error.put("errorCode", "INVALID_REQUEST");
                return error;
            }

            // Convert hex string to Block object
            byte[] blockBytes = org.apache.tuweni.bytes.Bytes.fromHexString(blockDataHex).toArray();
            io.xdag.core.Block block = io.xdag.core.Block.fromBytes(blockBytes);

            // Submit to mining API service
            BlockSubmitResult result = miningApi.submitMinedBlock(block, poolId);

            // Convert result to response
            Map<String, Object> response = new HashMap<>();
            response.put("accepted", result.isAccepted());
            response.put("message", result.getMessage());
            response.put("errorCode", result.getErrorCode());

            if (result.getBlockHash() != null) {
                response.put("blockHash", result.getBlockHash().toHexString());
            }

            log.info("Block submission from pool '{}': accepted={}", poolId, result.isAccepted());

            return response;

        } catch (Exception e) {
            log.error("Error submitting mined block", e);
            Map<String, Object> error = new HashMap<>();
            error.put("accepted", false);
            error.put("message", "Failed to submit block: " + e.getMessage());
            error.put("errorCode", "INTERNAL_ERROR");
            return error;
        }
    }

    private Object handleGetDifficulty() {
        try {
            io.xdag.api.service.MiningApiService miningApi = dagKernel.getMiningApiService();
            if (miningApi == null) {
                log.warn("Mining API service not available");
                return null;
            }

            org.apache.tuweni.units.bigints.UInt256 difficulty = miningApi.getCurrentDifficultyTarget();
            if (difficulty == null) {
                log.warn("Difficulty returned null");
                return null;
            }

            Map<String, Object> response = new HashMap<>();
            response.put("difficulty", difficulty.toHexString());
            response.put("difficultyDecimal", difficulty.toString());

            return response;
        } catch (Exception e) {
            log.error("Error getting difficulty", e);
            return null;
        }
    }

    private Object handleGetNodeStatus() {
        try {
            NodeStatusInfo nodeStatus = chainApiService.getNodeStatus();
            if (nodeStatus == null) {
                log.warn("Node status returned null");
                return null;
            }

            // Convert NodeStatusInfo to response format
            Map<String, Object> response = new HashMap<>();
            response.put("syncState", nodeStatus.getSyncState());
            response.put("isBehind", nodeStatus.isBehind());
            response.put("currentEpoch", nodeStatus.getCurrentEpoch());
            response.put("localLatestEpoch", nodeStatus.getLocalLatestEpoch());
            response.put("epochGap", nodeStatus.getEpochGap());
            response.put("syncLagThreshold", nodeStatus.getSyncLagThreshold());
            response.put("timeLagMinutes", nodeStatus.getTimeLagMinutes());
            response.put("miningStatus", nodeStatus.getMiningStatus());
            response.put("canMine", nodeStatus.isCanMine());
            response.put("miningMaxReferenceDepth", nodeStatus.getMiningMaxReferenceDepth());
            response.put("mainChainLength", nodeStatus.getMainChainLength());
            response.put("latestBlockHash", nodeStatus.getLatestBlockHash());
            response.put("latestBlockHeight", nodeStatus.getLatestBlockHeight());
            response.put("message", nodeStatus.getMessage());
            response.put("warningLevel", nodeStatus.getWarningLevel());

            return response;
        } catch (Exception e) {
            log.error("Error getting node status", e);
            return null;
        }
    }

    private String extractPathParam(String path, int position) {
        String[] parts = path.split("/");
        if (parts.length > position) {
            return parts[position];
        }
        throw new IllegalArgumentException("Invalid path: " + path);
    }

    private long parseBlockNumber(String blockNumber) {
        if (blockNumber == null || blockNumber.equalsIgnoreCase("latest")) {
            ChainStatsInfo stats = chainApiService.getChainStats();
            return (stats != null && stats.getTopBlockHeight() != null) ? stats.getTopBlockHeight() : 0;
        }

        if (blockNumber.equalsIgnoreCase("earliest")) {
            return 0;
        }

        if (blockNumber.equalsIgnoreCase("pending")) {
            ChainStatsInfo stats = chainApiService.getChainStats();
            return (stats != null && stats.getTopBlockHeight() != null) ? stats.getTopBlockHeight() : 0;
        }

        if (blockNumber.startsWith("0x")) {
            return Long.parseLong(blockNumber.substring(2), 16);
        }

        return Long.parseLong(blockNumber);
    }

    private String resolveBlockNumber(String blockNumber) {
        try {
            long height = parseBlockNumber(blockNumber);
            return String.format("0x%x", height);
        } catch (Exception e) {
            ChainStatsInfo stats = chainApiService.getChainStats();
            long height = (stats != null && stats.getTopBlockHeight() != null) ? stats.getTopBlockHeight() : 0;
            return String.format("0x%x", height);
        }
    }

    private BlockDetailResponse convertBlockDetailToResponse(BlockDetail blockDetail, boolean fullTransactions) {
        BlockDetailResponse.BlockDetailResponseBuilder builder = BlockDetailResponse.builder()
                .number(blockDetail.getHeight() != null ? String.format("0x%x", blockDetail.getHeight()) : "0x0")
                .hash(blockDetail.getHash() != null ? blockDetail.getHash() : "0x0")
                .timestamp(String.format("0x%x", blockDetail.getTimestamp()))
                .epoch(String.format("0x%x", blockDetail.getEpoch()))
                .difficulty(blockDetail.getDifficulty() != null ? blockDetail.getDifficulty().toHexString() : "0x0")
                .coinbase(blockDetail.getCoinbase() != null ? blockDetail.getCoinbase() : "")
                .state(blockDetail.getState() != null ? blockDetail.getState() : "unknown");

        List<Object> transactions = new ArrayList<>();
        if (blockDetail.getTransactions() != null) {
            builder.transactionCount(blockDetail.getTransactions().size());
            for (TransactionInfo tx : blockDetail.getTransactions()) {
                if (fullTransactions) {
                    transactions.add(convertTransactionInfoToResponse(tx));
                } else {
                    transactions.add(tx.getHash());
                }
            }
        } else {
            builder.transactionCount(0);
        }
        builder.transactions(transactions);

        List<BlockDetailResponse.LinkInfo> links = new ArrayList<>();
        if (blockDetail.getBlockLinks() != null) {
            for (BlockDetail.LinkInfo linkInfo : blockDetail.getBlockLinks()) {
                links.add(BlockDetailResponse.LinkInfo.builder()
                        .hash(linkInfo.getHash() != null ? linkInfo.getHash() : "0x0")
                        .height(linkInfo.getHeight() != null ? String.format("0x%x", linkInfo.getHeight()) : "0x0")
                        .type(linkInfo.getType() != null ? linkInfo.getType() : "unknown")
                        .build());
            }
        }
        builder.links(links);

        return builder.build();
    }

    private TransactionDetailResponse convertTransactionInfoToResponse(TransactionInfo tx) {
        TransactionDetailResponse.TransactionDetailResponseBuilder builder = TransactionDetailResponse.builder()
                .hash(tx.getHash() != null ? tx.getHash() : "0x0")
                .from(tx.getFrom() != null ? tx.getFrom() : "")
                .to(tx.getTo() != null ? tx.getTo() : "")
                .amount(tx.getAmount() != null ? tx.getAmount().toDecimal(9, XUnit.XDAG).toPlainString() : "0")
                .fee("0")
                .nonce(String.format("0x%x", tx.getNonce()))
                .data("0x")
                .blockNumber(tx.getBlockHeight() != null ? String.format("0x%x", tx.getBlockHeight()) : "0x0")
                .blockHash(tx.getBlockHash() != null ? tx.getBlockHash() : "0x0")
                .timestamp(tx.getTimestamp() != null ? String.format("0x%x", tx.getTimestamp()) : "0x0")
                .status(tx.getStatus() != null ? tx.getStatus() : "unknown")
                .valid(true)
                .signatureValid(true);

        if (tx.getSignature() != null && !tx.getSignature().isEmpty()) {
            builder.signature(TransactionDetailResponse.SignatureInfo.builder()
                    .v("0x0")
                    .r("0x0")
                    .s("0x0")
                    .build());
        }

        return builder.build();
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, HttpResponseStatus status, Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            sendResponse(ctx, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error serializing response", e);
            sendResponse(ctx, INTERNAL_SERVER_ERROR, "text/plain",
                    "Internal server error".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status,
                               String contentType, byte[] content) {
        ByteBuf buffer = ctx.alloc().buffer(content.length);
        buffer.writeBytes(content);

        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status, buffer);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private Map<String, Object> createError(String message, int code) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("code", code);
        return error;
    }

    private BlockSummaryResponse convertBlockSummary(BlockSummary summary) {
        return BlockSummaryResponse.builder()
                .hash(summary.getHash())
                .height(String.format("0x%x", summary.getHeight()))
                .epoch(summary.getEpoch())
                .timestamp(summary.getTimestamp())
                .difficulty(summary.getDifficulty() != null
                        ? summary.getDifficulty().toDecimalString()
                        : "0")
                .transactionCount(summary.getTransactionCount())
                .state(summary.getState())
                .coinbase(summary.getCoinbase())
                .build();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in HTTP handler", cause);
        ctx.close();
    }
}
