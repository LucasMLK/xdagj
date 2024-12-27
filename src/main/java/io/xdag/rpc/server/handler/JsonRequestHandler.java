package io.xdag.rpc.server.handler;

import io.xdag.rpc.model.request.TransactionRequest;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

import static io.xdag.rpc.server.handler.JsonRpcHandler.MAPPER;

@Slf4j
@RequiredArgsConstructor
public class JsonRequestHandler implements JsonRpcRequestHandler {
    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "xdag_getBlockByHash",
            "xdag_getBlockByNumber",
            "xdag_blockNumber",
            "xdag_coinbase",
            "xdag_getBalance",
            "xdag_getTotalBalance",
            "xdag_getStatus",
            "xdag_personal_sendTransaction",
            "xdag_sendRawTransaction",
            "xdag_netConnectionList",
            "xdag_netType",
            "xdag_getRewardByNumber"
    );

    private final XdagApi xdagApi;

    @Override
    public Object handle(JsonRpcRequest request) throws JsonRpcException {
        if (request == null) {
            throw JsonRpcException.invalidRequest("Request cannot be null");
        }
        
        if (request.getMethod() == null) {
            throw JsonRpcException.invalidRequest("Method cannot be null");
        }
        
        try {
            Object[] params = request.getParams();
            return switch (request.getMethod()) {
                case "xdag_getBlockByHash" -> {
                    if (params == null || params.length < 2) {
                        throw JsonRpcException.invalidParams("Missing block hash or page parameters");
                    }
                    String hash = params[0].toString();
                    int page;
                    try {
                        page = Integer.parseInt(params[1].toString());
                    } catch (NumberFormatException e) {
                        throw JsonRpcException.invalidParams("Invalid page number");
                    }
                    if (params.length > 2) {
                        String startTime = params[2].toString();
                        String endTime = params[3].toString();
                        if (params.length > 4) {
                            try {
                                int pageSize = Integer.parseInt(params[4].toString());
                                yield xdagApi.xdag_getBlockByHash(hash, page, startTime, endTime, pageSize);
                            } catch (NumberFormatException e) {
                                throw JsonRpcException.invalidParams("Invalid page size");
                            }
                        }
                        yield xdagApi.xdag_getBlockByHash(hash, page, startTime, endTime);
                    }
                    yield xdagApi.xdag_getBlockByHash(hash, page);
                }
                case "xdag_getBlockByNumber" -> {
                    if (params == null || params.length < 2) {
                        throw JsonRpcException.invalidParams("Missing block number or page parameters");
                    }
                    String bnOrId = params[0].toString();
                    int page;
                    try {
                        page = Integer.parseInt(params[1].toString());
                    } catch (NumberFormatException e) {
                        throw JsonRpcException.invalidParams("Invalid page number");
                    }
                    if (params.length > 2) {
                        try {
                            int pageSize = Integer.parseInt(params[2].toString());
                            yield xdagApi.xdag_getBlockByNumber(bnOrId, page, pageSize);
                        } catch (NumberFormatException e) {
                            throw JsonRpcException.invalidParams("Invalid page size");
                        }
                    }
                    yield xdagApi.xdag_getBlockByNumber(bnOrId, page);
                }
                case "xdag_coinbase" -> xdagApi.xdag_coinbase();
                case "xdag_blockNumber" -> xdagApi.xdag_blockNumber();
                case "xdag_getBalance" -> {
                    if (request.getParams() == null) {
                        throw JsonRpcException.invalidParams("Missing address parameter");
                    }
                    yield xdagApi.xdag_getBalance(request.getParams()[0].toString());
                }
                case "xdag_getTotalBalance" -> xdagApi.xdag_getTotalBalance();
                case "xdag_getStatus" -> xdagApi.xdag_getStatus();
                case "xdag_netConnectionList" -> xdagApi.xdag_netConnectionList();
                case "xdag_netType" -> xdagApi.xdag_netType();
                case "xdag_getRewardByNumber" -> {
                    if (params == null) {
                        throw JsonRpcException.invalidParams("Missing block number parameter");
                    }
                    yield xdagApi.xdag_getRewardByNumber(params[0].toString());
                }
                case "xdag_sendRawTransaction" -> {
                    if (params == null) {
                        throw JsonRpcException.invalidParams("Missing raw data parameter");
                    }
                    yield xdagApi.xdag_sendRawTransaction(params[0].toString());
                }
                case "xdag_personal_sendTransaction" -> {
                    if (params == null || params.length < 2) {
                        throw JsonRpcException.invalidParams("Missing transaction arguments or passphrase");
                    }
                    TransactionRequest txRequest = MAPPER.convertValue(params[0], TransactionRequest.class);;
                    String pwd = params[1].toString();
                    txRequest.setFrom(xdagApi.xdag_coinbase());
                    yield xdagApi.xdag_personal_sendTransaction(txRequest, pwd);
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
