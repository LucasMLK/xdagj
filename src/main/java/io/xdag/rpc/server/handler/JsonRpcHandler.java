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
package io.xdag.rpc.server.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.xdag.rpc.error.JsonRpcError;
import io.xdag.rpc.error.JsonRpcException;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import io.xdag.rpc.server.protocol.JsonRpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@ChannelHandler.Sharable
public class JsonRpcHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonRpcRequestHandler> handlers;

    public JsonRpcHandler(List<JsonRpcRequestHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.method().equals(HttpMethod.POST)) {
            sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED);
            return;
        }

        String content = request.content().toString(StandardCharsets.UTF_8);
        JsonRpcRequest rpcRequest;
        try {
            rpcRequest = mapper.readValue(content, JsonRpcRequest.class);
            if (rpcRequest.getJsonrpc() == null || !rpcRequest.getJsonrpc().equals("2.0")) {
                throw JsonRpcException.invalidRequest("Invalid JSON-RPC version");
            }
        } catch (JsonRpcException e) {
            sendError(ctx, new JsonRpcError(e.getCode(), e.getMessage(), e.getData()));
            return;
        } catch (Exception e) {
            log.debug("Failed to parse JSON-RPC request", e);
            sendError(ctx, new JsonRpcError(JsonRpcError.ERR_PARSE, "Invalid JSON request"));
            return;
        }

        try {
            Object result = dispatch(rpcRequest);
            sendResponse(ctx, new JsonRpcResponse(rpcRequest.getId(), result));
        } catch (JsonRpcException e) {
            log.debug("RPC error: {}", e.getMessage());
            sendError(ctx, new JsonRpcError(e.getCode(), e.getMessage(), e.getData()));
        } catch (Exception e) {
            log.error("Error processing request", e);
            sendError(ctx, new JsonRpcError(JsonRpcError.ERR_INTERNAL, "Internal error"));
        }
    }

    private Object dispatch(JsonRpcRequest request) throws JsonRpcException {
        if (request.getMethod() == null) {
            throw JsonRpcException.invalidRequest("Method cannot be null");
        }

        for (JsonRpcRequestHandler handler : handlers) {
            if (handler.supportsMethod(request.getMethod())) {
                return handler.handle(request);
            }
        }

        throw JsonRpcException.methodNotFound(request.getMethod());
    }

    private void sendResponse(ChannelHandlerContext ctx, JsonRpcResponse response) {
        try {
            ByteBuf content = Unpooled.copiedBuffer(
                    mapper.writeValueAsString(response), 
                    StandardCharsets.UTF_8
            );
            sendHttpResponse(ctx, content, HttpResponseStatus.OK);
        } catch (Exception e) {
            log.error("Error sending response", e);
            sendError(ctx, new JsonRpcError(JsonRpcError.ERR_INTERNAL, "Error serializing response"));
        }
    }

    private void sendError(ChannelHandlerContext ctx, JsonRpcError error) {
        try {
            ByteBuf content = Unpooled.copiedBuffer(
                    mapper.writeValueAsString(new JsonRpcResponse(null, null, error)),
                    StandardCharsets.UTF_8
            );
            sendHttpResponse(ctx, content, HttpResponseStatus.OK);
        } catch (Exception e) {
            log.error("Error sending error response", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        ByteBuf content = Unpooled.copiedBuffer(status.toString(), StandardCharsets.UTF_8);
        sendHttpResponse(ctx, content, status);
    }

    private void sendHttpResponse(ChannelHandlerContext ctx, ByteBuf content, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                content
        );
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json")
                .set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

        // 获取CORS Origin并设置相应的头
//        String origin = ctx.channel().attr(AttributeKey.valueOf("CorsOrigin")).get();
//        if (origin != null) {
//            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
//        }

        ctx.writeAndFlush(response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Channel exception", cause);
        ctx.close();
    }
}
