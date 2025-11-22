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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.xdag.DagKernel;
import io.xdag.api.http.auth.ApiKeyStore;
import io.xdag.api.http.auth.Permission;
import io.xdag.config.spec.HttpSpec;
import java.io.File;
import java.net.InetAddress;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpApiServer {

  private final HttpSpec httpSpec;
  private final DagKernel dagKernel;
  private final ApiKeyStore apiKeyStore;
  private Channel channel;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;

  public HttpApiServer(final HttpSpec httpSpec, final DagKernel dagKernel) {
    this.httpSpec = httpSpec;
    this.dagKernel = dagKernel;
    this.apiKeyStore = initApiKeyStore();
  }

  private ApiKeyStore initApiKeyStore() {
    boolean authEnabled = httpSpec.isRpcHttpAuthEnabled();
    ApiKeyStore store = new ApiKeyStore(authEnabled);

    if (authEnabled) {
      String[] apiKeys = httpSpec.getRpcHttpApiKeys();
      if (apiKeys != null && apiKeys.length > 0) {
        for (String keyConfig : apiKeys) {
          // BUGFIX (BUG-024): Use split(":", 2) to handle API keys containing colons
          // Previously: split(":") would incorrectly split "my:complex:key:READ" into 4 parts
          // Now: split(":", 2) produces ["my:complex:key", "READ"] (correct)
          String[] parts = keyConfig.split(":", 2);

          if (parts.length != 2) {
            // BUGFIX (BUG-024): Log warning for malformed API key configuration
            // Previously: silently skipped malformed configs, users had no feedback
            log.warn("Malformed API key configuration (expected 'key:permission'): {}", keyConfig);
            continue;
          }

          String key = parts[0].trim();
          String permissionStr = parts[1].trim();

          // Validate key is not empty
          if (key.isEmpty()) {
            log.warn("Empty API key in configuration: {}", keyConfig);
            continue;
          }

          // BUGFIX (BUG-024): Catch invalid permission names
          // Previously: IllegalArgumentException would crash server startup
          // Now: log error and skip invalid configuration
          try {
            Permission permission = Permission.valueOf(permissionStr.toUpperCase());
            store.addApiKey(key, permission);
            log.debug("Registered API key: {} with permission: {}",
                key.substring(0, Math.min(8, key.length())) + "...", permission);
          } catch (IllegalArgumentException e) {
            log.error("Invalid permission '{}' in API key configuration: {}. Valid permissions: {}",
                permissionStr, keyConfig, java.util.Arrays.toString(Permission.values()));
          }
        }
      }
      log.info("API authentication enabled with {} API key(s)", store.size());
    } else {
      log.info("API authentication disabled");
    }

    return store;
  }

  public void start() {
    try {
      bossGroup = new MultiThreadIoEventLoopGroup(httpSpec.getRpcHttpBossThreads(),
          NioIoHandler.newFactory());
      workerGroup = new MultiThreadIoEventLoopGroup(httpSpec.getRpcHttpWorkerThreads(),
          NioIoHandler.newFactory());

      final SslContext sslCtx = initSslContext();

      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel.class)
          .handler(new LoggingHandler(LogLevel.INFO))
          .childOption(ChannelOption.SO_KEEPALIVE, true)
          .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
              ChannelPipeline p = ch.pipeline();

              if (sslCtx != null) {
                p.addLast(sslCtx.newHandler(ch.alloc()));
              }

              p.addLast(new HttpServerCodec());
              p.addLast(new HttpObjectAggregator(httpSpec.getRpcHttpMaxContentLength()));
              p.addLast(new CorsHandler(httpSpec.getRpcHttpCorsOrigins()));
              p.addLast(new HttpApiHandler(dagKernel, apiKeyStore));
            }
          });

      String protocol = sslCtx != null ? "https" : "http";
      log.info("Starting HTTP API server on {}://{}:{}",
          protocol, httpSpec.getRpcHttpHost(), httpSpec.getRpcHttpPort());

      channel = b.bind(InetAddress.getByName(httpSpec.getRpcHttpHost()),
          httpSpec.getRpcHttpPort()).sync().channel();

      log.info("HTTP API server started successfully");
      log.info("  - RESTful API:  {}://{}:{}/api/v1/",
          protocol, httpSpec.getRpcHttpHost(), httpSpec.getRpcHttpPort());

    } catch (Exception e) {
      stop();
      throw new RuntimeException("Failed to start HTTP API server", e);
    }
  }

  private SslContext initSslContext() {
    if (!httpSpec.isRpcEnableHttps()) {
      return null;
    }

    try {
      String certFile = httpSpec.getRpcHttpsCertFile();
      String keyFile = httpSpec.getRpcHttpsKeyFile();

      if (certFile == null || keyFile == null) {
        log.warn("HTTPS enabled but cert/key files not configured, falling back to HTTP");
        return null;
      }

      File cert = new File(certFile);
      File key = new File(keyFile);

      if (!cert.exists() || !key.exists()) {
        log.warn("HTTPS cert/key files not found, falling back to HTTP");
        return null;
      }

      log.info("Initializing HTTPS with cert: {}", certFile);
      return SslContextBuilder.forServer(cert, key).build();
    } catch (Exception e) {
      log.error("Failed to initialize SSL context, falling back to HTTP", e);
      return null;
    }
  }

  public void stop() {
    log.info("Stopping HTTP API server...");

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

    log.info("HTTP API server stopped");
  }
}
