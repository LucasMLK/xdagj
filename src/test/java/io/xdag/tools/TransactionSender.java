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
import io.xdag.config.spec.WalletSpec;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
import io.xdag.crypto.encoding.Base58;
import io.xdag.crypto.keys.ECKeyPair;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.tuweni.bytes.Bytes;

/**
 * Simple tool for sending test transactions between nodes.
 *
 * Usage: java TransactionSender <wallet_file> <password> <to_address> <amount> <api_url>
 *
 * Example:
 *   java TransactionSender test-nodes/suite1/node/devnet/wallet/wallet.data test123 \
 *     Jwm1mN1QH8nwg14XYp8z8CGripGiGSBhW 10 http://127.0.0.1:10001
 */
public class TransactionSender {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.out.println("Usage: TransactionSender <wallet_file> <password> <to_address> <amount> <api_url>");
            System.out.println("Example: TransactionSender test-nodes/suite1/node/devnet/wallet/wallet.data test123 Jwm1mN1QH8nwg14XYp8z8CGripGiGSBhW 10 http://127.0.0.1:10001");
            return;
        }

        String walletFile = args[0];
        String password = args[1];
        String toAddressBase58 = args[2];
        double amount = Double.parseDouble(args[3]);
        String apiUrl = args[4];

        // Create a simple config that just provides wallet path
        Config config = new SimpleWalletConfig(walletFile);
        Wallet wallet = new Wallet(config);

        if (!wallet.unlock(password)) {
            System.err.println("Failed to unlock wallet with provided password");
            return;
        }

        ECKeyPair keyPair = wallet.getDefKey();
        if (keyPair == null) {
            System.err.println("No accounts in wallet");
            return;
        }

        // Get from address
        Bytes fromAddress = io.xdag.crypto.keys.AddressUtils.toBytesAddress(keyPair);
        String fromAddressBase58 = Base58.encodeCheck(fromAddress);
        System.out.println("From address: " + fromAddressBase58);

        // Decode to address
        Bytes toAddress = Bytes.wrap(Base58.decodeCheck(toAddressBase58));
        System.out.println("To address: " + toAddressBase58);

        // Get nonce from API (need to use nonce + 1 for transaction)
        long accountNonce = getNonce(apiUrl, fromAddressBase58);
        long txNonce = accountNonce + 1;  // Transaction nonce = account nonce + 1
        System.out.println("Account nonce: " + accountNonce + ", tx nonce: " + txNonce);

        // Create and sign transaction
        Transaction tx = Transaction.createTransfer(
            fromAddress,
            toAddress,
            XAmount.of((long)(amount * 1_000_000_000L), XUnit.NANO_XDAG),
            txNonce,
            XAmount.of(100, XUnit.MILLI_XDAG),  // 0.1 XDAG fee (minimum)
            3  // chainId for devnet
        );

        Transaction signedTx = tx.sign(keyPair);
        System.out.println("Transaction hash: " + signedTx.getHash().toHexString());
        System.out.println("Amount: " + amount + " XDAG");

        // Verify signature
        if (!signedTx.verifySignature()) {
            System.err.println("Signature verification failed!");
            return;
        }
        System.out.println("Signature verified: OK");

        // Serialize and send
        byte[] txBytes = signedTx.toBytes();
        String txHex = "0x" + Bytes.wrap(txBytes).toHexString();
        System.out.println("Serialized tx length: " + txBytes.length + " bytes");

        // Send to API
        String result = sendTransaction(apiUrl, txHex);
        System.out.println("API response: " + result);
    }

    private static long getNonce(String apiUrl, String address) throws Exception {
        URL url = new URL(apiUrl + "/api/v1/accounts/" + address + "/nonce");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // Simple JSON parsing for nonce field
            int idx = response.indexOf("\"nonce\":");
            if (idx >= 0) {
                int start = idx + 8;
                int end = response.indexOf(",", start);
                if (end < 0) end = response.indexOf("}", start);
                String nonceStr = response.substring(start, end).trim().replace("\"", "");
                // Handle hex format
                if (nonceStr.startsWith("0x")) {
                    return Long.parseLong(nonceStr.substring(2), 16);
                }
                return Long.parseLong(nonceStr);
            }
        }
        return 0;
    }

    private static String sendTransaction(String apiUrl, String signedTxHex) throws Exception {
        URL url = new URL(apiUrl + "/api/v1/transactions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String body = "{\"signedTransactionData\": \"" + signedTxHex + "\"}";

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } else {
            return "Error " + responseCode + ": " +
                   new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Minimal Config implementation that only provides wallet file path
     */
    static class SimpleWalletConfig implements Config {
        private final String walletFilePath;

        SimpleWalletConfig(String walletFilePath) {
            this.walletFilePath = walletFilePath;
        }

        @Override
        public WalletSpec getWalletSpec() {
            final String path = walletFilePath;
            return new WalletSpec() {
                @Override public String getWalletFilePath() { return path; }
                @Override public String getWalletKeyFile() { return ""; }
            };
        }

        // Required Config interface methods - minimal implementations
        @Override public String getConfigName() { return "devnet"; }
        @Override public String getClientId() { return ""; }
        @Override public io.xdag.config.CapabilityTreeSet getClientCapabilities() { return null; }
        @Override public String getRootDir() { return "."; }
        @Override public io.xdag.config.spec.NodeSpec getNodeSpec() { return null; }
        @Override public io.xdag.config.spec.AdminSpec getAdminSpec() { return null; }
        @Override public io.xdag.core.XAmount getMainStartAmount() { return null; }
        @Override public long getXdagEra() { return 0; }
        @Override public long getApolloForkHeight() { return 0; }
        @Override public io.xdag.core.XAmount getApolloForkAmount() { return null; }
        @Override public void changePara(String[] args) {}
        @Override public void setDir() {}
        @Override public io.xdag.config.spec.HttpSpec getHttpSpec() { return null; }
        @Override public io.xdag.config.spec.RandomxSpec getRandomxSpec() { return null; }
        @Override public java.util.List<String> getPoolWhiteIPList() { return null; }
        @Override public io.xdag.config.spec.FundSpec getFundSpec() { return null; }
        @Override public String getNodeTag() { return ""; }
    }
}
