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

package io.xdag.net;

import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

/**
 * Manages the network database including whitelisted nodes
 */
@Getter
@Slf4j
public class NetDBManager {
    private final String database;
    private final String databaseWhite;
    private final String whiteUrl;
    private final NetDB netDB;
    private NetDB whiteDB;
    private final Config config;

    /**
     * Constructor initializes database paths and network DBs
     */
    public NetDBManager(Config config) {
        this.config = config;
        database = config.getNodeSpec().getNetDBDir();
        databaseWhite = config.getNodeSpec().getWhiteListDir();
        whiteUrl = config.getNodeSpec().getWhitelistUrl();
        whiteDB = new NetDB();
        netDB = new NetDB();
    }

    /**
     * Load whitelist IPs from config
     */
    public void loadFromConfig() {
        for (InetSocketAddress address:config.getNodeSpec().getWhiteIPList()){
            whiteDB.addNewIP(address);
        }
    }

    /**
     * Load whitelist from remote URL and save to local file
     * Issue: Creates new file even if exists, should check first
     * Issue: Potential resource leak if reader not closed in finally block
     */
    public void loadFromUrl() {
        File file = new File(databaseWhite);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    log.debug("Failed to create whitelist file");
                    return;
                }
                // Download whitelist from URL
                URL url = new URL(whiteUrl);
                FileUtils.copyURLToFile(url, file);
            }

            // Read IPs from file
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    whiteDB.addNewIP(ip, port);
                }
            }
            
        } catch (IOException e) {
            log.error("Error loading whitelist from URL", e);
        }
    }

    /**
     * Initialize network database
     */
    public void init() {
        loadFromConfig();
        if(config instanceof DevnetConfig) {
            // Only use config whitelist for devnet
            return;
        }
        loadFromUrl();
    }

    /**
     * Update network database with new entries
     */
    public void updateNetDB(NetDB netDB) {
        if (netDB != null) {
            this.netDB.appendNetDB(netDB);
        }
    }

    /**
     * Check if address is whitelisted
     */
    public boolean canAccept(InetSocketAddress address) {
        return whiteDB.contains(address);
    }

    /**
     * Refresh whitelist from URL
     * Issue: Creates new whiteDB instance inside loop - should be created once
     * Issue: Potential resource leak with reader
     */
    public void refresh() {
        File file = new File(databaseWhite);
        try {
            URL url = new URL(whiteUrl);
            FileUtils.copyURLToFile(url, file);
            
            if (!file.exists() || !file.isFile()) {
                return;
            }

            whiteDB = new NetDB(); // Move outside loop
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2) {
                        String ip = parts[0];
                        int port = Integer.parseInt(parts[1]);
                        whiteDB.addNewIP(ip, port);
                    }
                }
            }
            
        } catch (IOException e) {
            log.error("Error refreshing whitelist", e);
        }
    }

}
