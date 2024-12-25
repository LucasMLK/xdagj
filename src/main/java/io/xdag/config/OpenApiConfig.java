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

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.jaxrs2.Reader;
import io.xdag.rpc.XdagApiDocs;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OpenApiConfig {
    private final String host;
    private final int port;

    public OpenApiConfig(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public OpenAPI getOpenAPI() {
        OpenAPI openAPI = new OpenAPI()
                .info(new Info()
                        .title("XDAG JSON-RPC API")
                        .description("XDAG区块链JSON-RPC接口文档")
                        .version("1.0.0")
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url(String.format("http://%s:%d", host, port))
                                .description("JSON-RPC服务器")
                ));

        // 扫描API文档类
        Reader reader = new Reader(openAPI);
        try {
            reader.read(XdagApiDocs.class);
        } catch (Exception e) {
            log.error("Error reading OpenAPI documentation", e);
        }

        return openAPI;
    }
}
