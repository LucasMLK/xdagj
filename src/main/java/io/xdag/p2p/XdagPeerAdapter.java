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
package io.xdag.p2p;

import io.xdag.Network;
import io.xdag.net.Peer;
import lombok.Getter;

/**
 * Simple adapter to create Peer objects from xdagj-p2p Channel
 * Only used for compatibility with legacy code (BlockWrapper, etc.)
 */
@Getter
public class XdagPeerAdapter extends Peer {

    public XdagPeerAdapter(io.xdag.p2p.channel.Channel channel, Network network, short networkVersion) {
        super(
            network,
            networkVersion,
            channel.getNodeId() != null ? channel.getNodeId() : "unknown",
            channel.getRemoteAddress() != null ? channel.getRemoteAddress().getAddress().getHostAddress() : "unknown",
            channel.getRemoteAddress() != null ? channel.getRemoteAddress().getPort() : 0,
            "xdagj-p2p",  // clientId
            new String[]{"p2p"},  // capabilities
            0L,  // latestBlockNumber - will be updated later
            false,  // isGenerateBlock - will be updated later
            "p2p-node"  // nodeTag
        );
    }
}
