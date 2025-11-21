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

import io.xdag.config.Config;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.p2p.config.P2pConfig;
import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;

/**
 * Factory class to create P2pConfig from xdagj Config Handles the mapping between xdagj
 * configuration and xdagj-p2p configuration
 */
@Slf4j
public class P2pConfigFactory {

  /**
   * Create P2pConfig from xdagj Config and node keypair
   *
   * @param config  xdagj configuration
   * @param nodeKey node keypair for identity
   * @return configured P2pConfig instance
   */
  public static P2pConfig createP2pConfig(Config config, ECKeyPair nodeKey) {
    P2pConfig p2pConfig = new P2pConfig();

    // Network settings
    p2pConfig.setPort(config.getNodeSpec().getNodePort());
    p2pConfig.setIpV4(config.getNodeSpec().getNodeIp());

    // Connection limits - use default values if not configured
    p2pConfig.setMinConnections(8);  // Default minimum connections
    p2pConfig.setMaxConnections(
        Math.min(config.getNodeSpec().getMaxConnections(), 100)); // Cap at 100

    // Node identity
    p2pConfig.setNodeKey(nodeKey);

    // Network ID - convert Network enum to byte
    p2pConfig.setNetworkId(config.getNodeSpec().getNetwork().id());
    p2pConfig.setNetworkVersion(config.getNodeSpec().getNetworkVersion());
    p2pConfig.setClientId(config.getClientId());

    // Discovery settings
    p2pConfig.setDiscoverEnable(true); // Enable Kademlia DHT discovery
    p2pConfig.setDataDir(config.getRootDir()); // For reputation persistence

    // Use white IP list as seed nodes
    for (InetSocketAddress whiteIp : config.getNodeSpec().getWhiteIPList()) {
      p2pConfig.getSeedNodes().add(whiteIp);
      p2pConfig.getTrustNodes().add(whiteIp.getAddress());
      log.debug("Added seed/trust node: {}:{}",
          whiteIp.getAddress().getHostAddress(), whiteIp.getPort());
    }

    // Protocol settings
    p2pConfig.setNetMaxFrameBodySize(config.getNodeSpec().getNetMaxFrameBodySize());
    p2pConfig.setEnableFrameCompression(true);

    // Disconnection policy
    p2pConfig.setDisconnectionPolicyEnable(
        config.getNodeSpec().getMaxConnections() > 50 // Only enable if > 50 connections
    );

    log.info("P2P configuration created: minConn={}, maxConn={}, seeds={}, port={}",
        p2pConfig.getMinConnections(),
        p2pConfig.getMaxConnections(),
        p2pConfig.getSeedNodes().size(),
        p2pConfig.getPort());

    return p2pConfig;
  }
}
