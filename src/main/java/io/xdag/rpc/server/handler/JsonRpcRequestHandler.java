package io.xdag.rpc.server.handler;

import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;

public interface JsonRpcRequestHandler {
    /**
     * 处理RPC请求
     *
     * @param request RPC请求
     * @return 处理结果
     * @throws JsonRpcException 如果处理过程中出现错误
     */
    Object handle(JsonRpcRequest request) throws JsonRpcException;

    /**
     * 检查方法是否支持
     *
     * @param methodName 方法名
     * @return 如果支持返回true
     */
    boolean supportsMethod(String methodName);
}
