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

package io.xdag.config.spec;

import io.xdag.rpc.modules.ModuleDescription;
import java.util.List;

/**
 * Interface for RPC configuration specifications
 * Defines methods to access RPC server configuration parameters
 */
public interface RPCSpec {

    /**
     * Get the list of RPC modules
     * @return List of ModuleDescription objects containing RPC module configurations
     */
    List<ModuleDescription> getRpcModules();

    /**
     * Check if RPC server is enabled
     * @return Boolean indicating if RPC is enabled
     */
    boolean isRPCEnabled();

    /**
     * Get the host address for RPC server
     * @return String containing the RPC server host address
     */
    String getRPCHost();

    /**
     * Get the HTTP port number for RPC server
     * @return Integer containing the RPC server HTTP port number
     */
    int getRPCPortByHttp();

}
