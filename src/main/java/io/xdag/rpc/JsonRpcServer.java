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
package io.xdag.rpc;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.xdag.Kernel;
import io.xdag.config.spec.RPCSpec;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class JsonRpcServer {
    private final Kernel kernel;
    private final RPCSpec rpcSpec;
    private final Web3XdagChain web3Service;
    private Channel channel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;


    public JsonRpcServer(final Kernel kernel) {
        this.kernel = kernel;
        this.rpcSpec = kernel.getConfig().getRPCSpec();
        this.web3Service = kernel.getWeb3();
    }

    public void start() {
        if (!kernel.getConfig().getRPCSpec().isRpcHttpEnabled()) {
            log.info("JSON-RPC server is disabled");
            return;
        }

        try {
            // 创建请求处理器
            List<JsonRpcRequestHandler> handlers = new ArrayList<>();
            handlers.add(new JsonRequestHandler(web3Service));

            // 创建SSL上下文（如果启用了HTTPS）
            final SslContext sslCtx;
            if (rpcSpec.isRpcEnableHttps()) {
                File certFile = new File(rpcSpec.getRpcHttpsCertFile());
                File keyFile = new File(rpcSpec.getRpcHttpsKeyFile());
                if (!certFile.exists() || !keyFile.exists()) {
                    throw new RuntimeException("SSL certificate or key file not found");
                }
                sslCtx = SslContextBuilder.forServer(certFile, keyFile).build();
            } else {
                sslCtx = null;
            }

            // 创建事件循环组
            bossGroup = new NioEventLoopGroup(rpcSpec.getRpcHttpBossThreads());
            workerGroup = new NioEventLoopGroup(rpcSpec.getRpcHttpWorkerThreads());

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .handler(new LoggingHandler(LogLevel.INFO))
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            
                            // SSL
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }

                            // HTTP 编解码
                            p.addLast(new HttpServerCodec());
                            // HTTP 消息聚合
                            p.addLast(new HttpObjectAggregator(rpcSpec.getRpcHttpMaxContentLength()));
                            // CORS 处理
                            p.addLast(new CorsHandler(rpcSpec.getRpcHttpCorsOrigins()));
                            // JSON-RPC 处理
                            p.addLast(new JsonRpcHandler(handlers));
                        }
                    });

            channel = b.bind(InetAddress.getByName(rpcSpec.getRpcHttpHost()), rpcSpec.getRpcHttpPort()).sync().channel();
            log.info("JSON-RPC server started on {}:{} (SSL: {})",
                    rpcSpec.getRpcHttpHost(), rpcSpec.getRpcHttpPort(), rpcSpec.isRpcEnableHttps());
        } catch (Exception e) {
            log.error("Failed to start JSON-RPC server", e);
            stop();
            throw new RuntimeException("Failed to start JSON-RPC server", e);
        }
    }

    public void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        log.info("JSON-RPC server stopped");
    }
}
