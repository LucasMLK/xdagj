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
package io.xdag.config;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JsonRpcConfig {
    private String host = "127.0.0.1";
    private int port = 10001;
    private String corsOrigins = "*";
    private boolean enabled = true;
    private int maxContentLength = 1024 * 1024; // 1MB
    private int bossThreads = 1;
    private int workerThreads = 0; // 0 means use Netty default (2 * CPU cores)
    private boolean enableHttps = false;
    private String certFile;
    private String keyFile;

    public JsonRpcConfig() {
    }

    public JsonRpcConfig(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public JsonRpcConfig(String host, int port, String corsOrigins) {
        this.host = host;
        this.port = port;
        this.corsOrigins = corsOrigins;
    }
}
