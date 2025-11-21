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
package io.xdag.api.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.xdag.DagKernel;
import io.xdag.api.http.auth.ApiKeyStore;
import io.xdag.api.http.v1.HttpApiHandlerV1;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final HttpApiHandlerV1 handlerV1;

    public HttpApiHandler(DagKernel dagKernel, ApiKeyStore apiKeyStore) {
        this.handlerV1 = new HttpApiHandlerV1(dagKernel, apiKeyStore);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        String version = ApiVersion.getVersionFromPath(uri);

        if (ApiVersion.V1.equals(version)) {
            handlerV1.channelRead0(ctx, request);
        } else {
            handlerV1.channelRead0(ctx, request);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Exception in HTTP handler", cause);
        ctx.close();
    }
}
