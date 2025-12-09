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
package io.xdag.tools;

import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;

/**
 * Simple utility to create test wallets for local node testing
 */
public class CreateTestWallet {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java CreateTestWallet <data-directory> <password>");
            System.exit(1);
        }

        String dataDir = args[0];
        String password = args[1];

        try {
            // Create config with specified data directory
            Config config = new DevnetConfig() {
                @Override
                public String getRootDir() {
                    return dataDir;
                }
            };

            // Create wallet
            Wallet wallet = new Wallet(config);

            // Unlock with password (creates new wallet if doesn't exist)
            if (!wallet.unlock(password)) {
                System.out.println("Creating new wallet...");
            }

            // Initialize HD wallet with fixed mnemonic for testing
            if (!wallet.isHdWalletInitialized()) {
                String fixedMnemonic = "test test test test test test test test test test test test";
                wallet.initializeHdWallet(fixedMnemonic);
                System.out.println("  - HD wallet initialized");
            }

            // Add account using HD wallet
            wallet.addAccountWithNextHdKey();

            // Save wallet
            wallet.flush();

            System.out.println("✅ Wallet created successfully in: " + dataDir);
            System.out.println("  - Root directory: " + config.getRootDir());
            System.out.println("  - Accounts: " + wallet.getAccounts().size());

        } catch (Exception e) {
            System.err.println("❌ Error creating wallet: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
