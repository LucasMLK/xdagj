package io.xdag.rpc;

import lombok.Getter;

@Getter
public class JsonRpcException extends RuntimeException {
    private final int code;
    private final String message;
    private final Object data;

    public JsonRpcException(int code, String message) {
        this(code, message, null);
    }

    public JsonRpcException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public JsonRpcException(JsonRpcError error) {
        this(error.getCode(), error.getMessage(), error.getData());
    }

    public static JsonRpcException invalidRequest(String message) {
        return new JsonRpcException(JsonRpcError.INVALID_REQUEST, message);
    }

    public static JsonRpcException methodNotFound(String method) {
        return new JsonRpcException(JsonRpcError.METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static JsonRpcException invalidParams(String message) {
        return new JsonRpcException(JsonRpcError.INVALID_PARAMS, message);
    }

    public static JsonRpcException internalError(String message) {
        return new JsonRpcException(JsonRpcError.INTERNAL_ERROR, message);
    }

    public static JsonRpcException serverError(String message) {
        return new JsonRpcException(JsonRpcError.SERVER_ERROR, message);
    }

    public static JsonRpcException invalidAddress(String message) {
        return new JsonRpcException(JsonRpcError.INVALID_ADDRESS, message);
    }

    public static JsonRpcException blockNotFound(String message) {
        return new JsonRpcException(JsonRpcError.BLOCK_NOT_FOUND, message);
    }

    public static JsonRpcException insufficientFunds(String message) {
        return new JsonRpcException(JsonRpcError.INSUFFICIENT_FUNDS, message);
    }

    public static JsonRpcException transactionError(String message) {
        return new JsonRpcException(JsonRpcError.TRANSACTION_ERROR, message);
    }

    public static JsonRpcException poolError(String message) {
        return new JsonRpcException(JsonRpcError.POOL_ERROR, message);
    }
}
