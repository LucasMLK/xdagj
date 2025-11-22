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

import com.google.common.collect.Lists;
import com.typesafe.config.ConfigFactory;
import io.xdag.Network;
import io.xdag.config.spec.AdminSpec;
import io.xdag.config.spec.FundSpec;
import io.xdag.config.spec.HttpSpec;
import io.xdag.config.spec.NodeSpec;
import io.xdag.config.spec.RandomxSpec;
import io.xdag.config.spec.SnapshotSpec;
import io.xdag.config.spec.WalletSpec;
import io.xdag.core.XAmount;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.SystemUtils;

@Slf4j
@Getter
@Setter
public class AbstractConfig implements Config, AdminSpec, NodeSpec, WalletSpec, HttpSpec,
    SnapshotSpec, RandomxSpec, FundSpec {

  protected String configName;

  // Foundation configuration
  protected String fundAddress;
  protected double fundRation;
  protected double nodeRation;

  // Network configuration
  protected Network network;
  protected short networkVersion;

  // Node configuration
  protected String nodeIp;
  protected int nodePort;
  protected String nodeTag;
  protected int maxConnections = 1024;
  protected int netMaxFrameBodySize = 128 * 1024;

  // Storage configuration
  protected String rootDir;
  protected String storeDir;
  protected String storeBackupDir;
  protected int storeMaxOpenFiles = 1024;
  protected int storeMaxThreads = 1;
  protected boolean storeFromBackup = false;

  // Whitelist configuration
  protected List<InetSocketAddress> whiteIPList = Lists.newArrayList();
  protected List<String> poolWhiteIPList = Lists.newArrayList();

  // Wallet configuration
  protected String walletFilePath;
  protected String walletKeyFile;

  // Pool configuration
  protected int waitEpoch = 32;

  // XDAG configuration
  protected long xdagEra;
  protected XAmount mainStartAmount;
  protected long apolloForkHeight;
  protected XAmount apolloForkAmount;

  // RPC configuration
  protected String rpcHttpHost = "127.0.0.1";
  protected int rpcHttpPort = 10001;
  protected boolean rpcHttpEnabled = false;
  protected boolean rpcEnableHttps = false;
  protected String rpcHttpCorsOrigins = "*";
  protected String rpcHttpsCertFile;
  protected String rpcHttpsKeyFile;
  protected int rpcHttpMaxContentLength = 1024 * 1024; // 1MB
  protected boolean rpcHttpAuthEnabled = false;
  protected String[] rpcHttpApiKeys = new String[0];

  // RPC netty configuration
  protected int rpcHttpBossThreads = 1;
  protected int rpcHttpWorkerThreads = 4; // 0 means use Netty default (2 * CPU cores)


  // Snapshot configuration
  protected boolean snapshotEnabled = false;
  protected long snapshotHeight;
  protected long snapshotTime;
  protected boolean isSnapshotJ;

  // RandomX configuration
  protected boolean flag;

  protected AbstractConfig(String rootDir, String configName, Network network,
      short networkVersion) {
    this.rootDir = rootDir;
    this.configName = configName;
    this.network = network;
    this.networkVersion = networkVersion;
    getSetting();
    setDir();
  }

  public void setDir() {
    storeDir = getRootDir() + "/rocksdb/xdagdb";
    storeBackupDir = getRootDir() + "/rocksdb/xdagdb/backupdata";
  }

  @Override
  public HttpSpec getHttpSpec() {
    return this;
  }

  @Override
  public SnapshotSpec getSnapshotSpec() {
    return this;
  }

  @Override
  public RandomxSpec getRandomxSpec() {
    return this;
  }

  @Override
  public FundSpec getFundSpec() {
    return this;
  }

  @Override
  public Network getNetwork() {
    return this.network;
  }

  @Override
  public short getNetworkVersion() {
    return this.networkVersion;
  }

  @Override
  public String getNodeTag() {
    return this.nodeTag;
  }

  @Override
  public String getClientId() {
    return String.format("%s/v%s-%s/%s",
        Constants.CLIENT_NAME,
        Constants.CLIENT_VERSION,
        SystemUtils.OS_NAME,
        SystemUtils.OS_ARCH);
  }

  @Override
  public CapabilityTreeSet getClientCapabilities() {
    return CapabilityTreeSet.of(Capability.FULL_NODE, Capability.LIGHT_NODE);
  }

  @Override
  public NodeSpec getNodeSpec() {
    return this;
  }

  @Override
  public AdminSpec getAdminSpec() {
    return this;
  }

  @Override
  public WalletSpec getWalletSpec() {
    return this;
  }

  @Override
  public String getWalletFilePath() {
    return walletFilePath;
  }

  @Override
  public String getWalletKeyFile() {
    return walletKeyFile;
  }

  public void getSetting() {
    com.typesafe.config.Config config = ConfigFactory.load(getConfigName());

    poolWhiteIPList = config.hasPath("pool.whiteIPs") ? config.getStringList("pool.whiteIPs")
        : Collections.singletonList("127.0.0.1");
    log.info("Pool whitelist {}. Any IP allowed? {}", poolWhiteIPList,
        poolWhiteIPList.contains("0.0.0.0"));
    nodeIp = config.hasPath("node.ip") ? config.getString("node.ip") : "127.0.0.1";
    nodePort = config.hasPath("node.port") ? config.getInt("node.port") : 8001;
    nodeTag = config.hasPath("node.tag") ? config.getString("node.tag") : "xdagj";
    fundAddress = config.hasPath("fund.address") ? config.getString("fund.address")
        : "4duPWMbYUgAifVYkKDCWxLvRRkSByf5gb";
    fundRation = config.hasPath("fund.ration") ? config.getDouble("fund.ration") : 5;
    nodeRation = config.hasPath("node.ration") ? config.getDouble("node.ration") : 5;
    List<String> whiteIpList = config.getStringList("node.whiteIPs");
    log.debug("{} IP access", whiteIpList.size());
    for (String addr : whiteIpList) {
      String ip = addr.split(":")[0];
      int port = Integer.parseInt(addr.split(":")[1]);
      whiteIPList.add(new InetSocketAddress(ip, port));
    }
    // RPC configuration
    rpcHttpEnabled = config.hasPath("rpc.http.enabled") && config.getBoolean("rpc.http.enabled");
    rpcHttpHost =
        config.hasPath("rpc.http.host") ? config.getString("rpc.http.host") : "127.0.0.1";
    rpcHttpPort = config.hasPath("rpc.http.port") ? config.getInt("rpc.http.port") : 10001;
    rpcHttpAuthEnabled =
        config.hasPath("rpc.http.auth.enabled") && config.getBoolean("rpc.http.auth.enabled");
    if (rpcHttpAuthEnabled && config.hasPath("rpc.http.auth.apiKeys")) {
      List<String> keyList = config.getStringList("rpc.http.auth.apiKeys");
      rpcHttpApiKeys = keyList.toArray(new String[0]);
    }
    flag = config.hasPath("randomx.flags.fullmem") && config.getBoolean("randomx.flags.fullmem");

  }

  @Override
  public void changePara(String[] args) {
    if (args == null || args.length == 0) {
      return;
    }

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "-f":
          // Set root directory
          if (i + 1 < args.length) {
            i++;
            this.rootDir = args[i];
          }
          break;
        case "-p":
          // Set node IP and port (format: host:port)
          if (i + 1 < args.length) {
            i++;
            this.changeNode(args[i]);
          }
          break;
        default:
          // Ignore unknown arguments (network flags -d/-t are handled by Launcher)
          break;
      }
    }
  }

  public void changeNode(String host) {
    String[] args = host.split(":");
    this.nodeIp = args[0];
    this.nodePort = Integer.parseInt(args[1]);
  }

  @Override
  public int getMaxConnections() {
    return this.maxConnections;
  }

  @Override
  public int getNetMaxFrameBodySize() {
    return this.netMaxFrameBodySize;
  }

  @Override
  public int getStoreMaxOpenFiles() {
    return this.storeMaxOpenFiles;
  }

  @Override
  public int getStoreMaxThreads() {
    return this.storeMaxThreads;
  }

  @Override
  public boolean isStoreFromBackup() {
    return this.storeFromBackup;
  }

  @Override
  public List<String> getPoolWhiteIPList() {
    return poolWhiteIPList;
  }

  @Override
  public boolean isRpcHttpEnabled() {
    return rpcHttpEnabled;
  }

  @Override
  public String getRpcHttpHost() {
    return rpcHttpHost;
  }

  @Override
  public int getRpcHttpPort() {
    return rpcHttpPort;
  }

  @Override
  public boolean isRpcEnableHttps() {
    return rpcEnableHttps;
  }

  @Override
  public String getRpcHttpCorsOrigins() {
    return rpcHttpCorsOrigins;
  }

  @Override
  public int getRpcHttpMaxContentLength() {
    return rpcHttpMaxContentLength;
  }

  @Override
  public int getRpcHttpBossThreads() {
    return rpcHttpBossThreads;
  }

  @Override
  public int getRpcHttpWorkerThreads() {
    return rpcHttpWorkerThreads;
  }

  @Override
  public String getRpcHttpsCertFile() {
    return rpcHttpsCertFile;
  }

  @Override
  public String getRpcHttpsKeyFile() {
    return rpcHttpsKeyFile;
  }

  @Override
  public boolean isRpcHttpAuthEnabled() {
    return rpcHttpAuthEnabled;
  }

  @Override
  public String[] getRpcHttpApiKeys() {
    return rpcHttpApiKeys;
  }

  @Override
  public boolean isSnapshotEnabled() {
    return snapshotEnabled;
  }

  @Override
  public boolean isSnapshotJ() {
    return isSnapshotJ;
  }

  @Override
  public long getSnapshotHeight() {
    return snapshotHeight;
  }

  @Override
  public boolean getRandomxFlag() {
    return flag;
  }

  @Override
  public long getXdagEra() {
    return xdagEra;
  }

  @Override
  public XAmount getMainStartAmount() {
    return mainStartAmount;
  }

  @Override
  public long getApolloForkHeight() {
    return apolloForkHeight;
  }

  @Override
  public XAmount getApolloForkAmount() {
    return apolloForkAmount;
  }

  @Override
  public void setSnapshotJ(boolean isSnapshot) {
    this.isSnapshotJ = isSnapshot;
  }

  @Override
  public void snapshotEnable() {
    snapshotEnabled = true;
  }

  @Override
  public long getSnapshotTime() {
    return snapshotTime;
  }
}
