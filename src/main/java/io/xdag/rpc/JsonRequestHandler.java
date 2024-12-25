package io.xdag.rpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class JsonRequestHandler implements JsonRpcRequestHandler {
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "xdag_protocolVersion",
            "xdag_syncing",
            "xdag_coinbase",
            "xdag_blockNumber",
            "xdag_getBalance",
            "xdag_getTotalBalance",
            "xdag_getBlockByNumber",
            "xdag_getBlockByHash",
            "xdag_getStatus",
            "xdag_netType",
            "xdag_poolConfig",
            "xdag_netConnectionList",
            "xdag_getMaxXferBalance",
            "xdag_accounts",
            "xdag_sign",
            "xdag_chainId",
            "xdag_getTransactionByHash",
            "xdag_getRewardByNumber",
            "xdag_getBalanceByNumber",
            "xdag_getBlocksByNumber",
            "xdag_sendRawTransaction",
            "xdag_sendTransaction",
            "xdag_personal_sendTransaction"
    );

    private final Web3XdagChain web3Service;

    @Override
    public Object handle(JsonRpcRequest request) throws JsonRpcException {
        try {
            return switch (request.getMethod()) {
                case "xdag_protocolVersion" -> web3Service.xdag_protocolVersion();
                case "xdag_syncing" -> web3Service.xdag_syncing();
                case "xdag_coinbase" -> web3Service.xdag_coinbase();
                case "xdag_blockNumber" -> web3Service.xdag_blockNumber();
                case "xdag_getBalance" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing address parameter");
                    }
                    yield web3Service.xdag_getBalance(request.getParams().get(0).toString());
                }
                case "xdag_getTotalBalance" -> web3Service.xdag_getTotalBalance();
                case "xdag_getBlockByNumber" -> {
                    if (request.getParams() == null || request.getParams().size() < 2) {
                        throw JsonRpcException.invalidParams("Missing block number or page parameters");
                    }
                    String bnOrId = request.getParams().get(0).toString();
                    int page;
                    try {
                        page = Integer.parseInt(request.getParams().get(1).toString());
                    } catch (NumberFormatException e) {
                        throw JsonRpcException.invalidParams("Invalid page number");
                    }
                    if (request.getParams().size() > 2) {
                        try {
                            int pageSize = Integer.parseInt(request.getParams().get(2).toString());
                            yield web3Service.xdag_getBlockByNumber(bnOrId, page, pageSize);
                        } catch (NumberFormatException e) {
                            throw JsonRpcException.invalidParams("Invalid page size");
                        }
                    }
                    yield web3Service.xdag_getBlockByNumber(bnOrId, page);
                }
                case "xdag_getBlockByHash" -> {
                    if (request.getParams() == null || request.getParams().size() < 2) {
                        throw JsonRpcException.invalidParams("Missing block hash or page parameters");
                    }
                    String hash = request.getParams().get(0).toString();
                    int page;
                    try {
                        page = Integer.parseInt(request.getParams().get(1).toString());
                    } catch (NumberFormatException e) {
                        throw JsonRpcException.invalidParams("Invalid page number");
                    }
                    if (request.getParams().size() > 2) {
                        String startTime = request.getParams().get(2).toString();
                        String endTime = request.getParams().get(3).toString();
                        if (request.getParams().size() > 4) {
                            try {
                                int pageSize = Integer.parseInt(request.getParams().get(4).toString());
                                yield web3Service.xdag_getBlockByHash(hash, page, startTime, endTime, pageSize);
                            } catch (NumberFormatException e) {
                                throw JsonRpcException.invalidParams("Invalid page size");
                            }
                        }
                        yield web3Service.xdag_getBlockByHash(hash, page, startTime, endTime);
                    }
                    yield web3Service.xdag_getBlockByHash(hash, page);
                }
                case "xdag_getStatus" -> web3Service.xdag_getStatus();
                case "xdag_netType" -> web3Service.xdag_netType();
                case "xdag_poolConfig" -> web3Service.xdag_poolConfig();
                case "xdag_netConnectionList" -> web3Service.xdag_netConnectionList();
                case "xdag_getMaxXferBalance" -> web3Service.xdag_getMaxXferBalance();
                case "xdag_accounts" -> web3Service.xdag_accounts();
                case "xdag_sign" -> {
                    if (request.getParams() == null || request.getParams().size() < 2) {
                        throw JsonRpcException.invalidParams("Missing address or data parameters");
                    }
                    yield web3Service.xdag_sign(
                            request.getParams().get(0).toString(),
                            request.getParams().get(1).toString()
                    );
                }
                case "xdag_chainId" -> web3Service.xdag_chainId();
                case "xdag_getTransactionByHash" -> {
                    if (request.getParams() == null || request.getParams().size() < 2) {
                        throw JsonRpcException.invalidParams("Missing hash or page parameters");
                    }
                    String hash = request.getParams().get(0).toString();
                    int page;
                    try {
                        page = Integer.parseInt(request.getParams().get(1).toString());
                    } catch (NumberFormatException e) {
                        throw JsonRpcException.invalidParams("Invalid page number");
                    }
                    yield web3Service.xdag_getTransactionByHash(hash, page);
                }
                case "xdag_getRewardByNumber" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing block number parameter");
                    }
                    yield web3Service.xdag_getRewardByNumber(request.getParams().get(0).toString());
                }
                case "xdag_getBalanceByNumber" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing block number parameter");
                    }
                    yield web3Service.xdag_getBalanceByNumber(request.getParams().get(0).toString());
                }
                case "xdag_getBlocksByNumber" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing block number parameter");
                    }
                    yield web3Service.xdag_getBlocksByNumber(request.getParams().get(0).toString());
                }
                case "xdag_sendRawTransaction" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing raw data parameter");
                    }
                    yield web3Service.xdag_sendRawTransaction(request.getParams().get(0).toString());
                }
                case "xdag_sendTransaction" -> {
                    if (request.getParams() == null || request.getParams().isEmpty()) {
                        throw JsonRpcException.invalidParams("Missing transaction arguments");
                    }
                    yield web3Service.xdag_sendTransaction(
                            (Web3XdagChain.CallArguments) request.getParams().get(0)
                    );
                }
                case "xdag_personal_sendTransaction" -> {
                    if (request.getParams() == null || request.getParams().size() < 2) {
                        throw JsonRpcException.invalidParams("Missing transaction arguments or passphrase");
                    }
                    yield web3Service.xdag_personal_sendTransaction(
                            (Web3XdagChain.CallArguments) request.getParams().get(0),
                            request.getParams().get(1).toString()
                    );
                }
                default -> throw JsonRpcException.methodNotFound(request.getMethod());
            };
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error handling request: {}", e.getMessage(), e);
            throw JsonRpcException.internalError("Internal error: " + e.getMessage());
        }
    }

    @Override
    public boolean supportsMethod(String methodName) {
        return SUPPORTED_METHODS.contains(methodName);
    }
}
