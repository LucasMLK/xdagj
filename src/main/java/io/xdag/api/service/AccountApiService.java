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

package io.xdag.api.service;

import static io.xdag.crypto.keys.AddressUtils.toBytesAddress;
import static io.xdag.utils.WalletUtils.checkAddress;
import static io.xdag.utils.WalletUtils.fromBase58;

import io.xdag.DagKernel;
import io.xdag.api.service.dto.AccountInfo;
import io.xdag.core.XAmount;
import io.xdag.crypto.keys.AddressUtils;
import io.xdag.crypto.keys.ECKeyPair;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Account API Service
 * Provides account-related data access for both CLI and RPC
 */
@Slf4j
public class AccountApiService {

    private final DagKernel dagKernel;

    public AccountApiService(DagKernel dagKernel) {
        this.dagKernel = dagKernel;
    }

    /**
     * Convert UInt256 to XAmount for display
     */
    private static XAmount uint256ToXAmount(UInt256 balance) {
        return XAmount.ofXAmount(balance.toLong());
    }

    /**
     * Get account balance from AccountStore
     */
    private XAmount getAccountBalance(Bytes address) {
        UInt256 balance = dagKernel.getAccountStore().getBalance(address);
        return uint256ToXAmount(balance);
    }

    /**
     * Get account nonce from AccountStore
     */
    private long getAccountNonce(Bytes address) {
        return dagKernel.getAccountStore().getNonce(address).toLong();
    }

    /**
     * Get all accounts from wallet sorted by balance
     *
     * @param limit Maximum number of accounts to return (0 = all)
     * @return List of account information sorted by balance descending
     */
    public List<AccountInfo> getAccounts(int limit) {
        List<ECKeyPair> keyPairs = dagKernel.getWallet().getAccounts();

        // Sort by balance descending
        keyPairs.sort((o1, o2) -> {
            Bytes addr1 = Bytes.wrap(toBytesAddress(o1));
            Bytes addr2 = Bytes.wrap(toBytesAddress(o2));
            XAmount balance1 = getAccountBalance(addr1);
            XAmount balance2 = getAccountBalance(addr2);
            return balance2.compareTo(balance1);
        });

        List<AccountInfo> accounts = new ArrayList<>();
        int count = 0;
        for (ECKeyPair keyPair : keyPairs) {
            if (limit > 0 && count >= limit) {
                break;
            }

            Bytes addr = Bytes.wrap(toBytesAddress(keyPair));
            XAmount balance = getAccountBalance(addr);
            long nonce = getAccountNonce(addr);
            String address = AddressUtils.toBase58Address(keyPair);

            accounts.add(AccountInfo.builder()
                    .address(address)
                    .balance(balance)
                    .nonce(nonce)
                    .addressBytes(addr.toArray())
                    .build());
            count++;
        }

        return accounts;
    }

    /**
     * Get account information by address
     *
     * @param address Account address (Base58 format)
     * @return Account information or null if not found
     */
    public AccountInfo getAccountByAddress(String address) {
        try {
            if (!checkAddress(address)) {
                log.error("Invalid address format: {}", address);
                return null;
            }

            Bytes addr = Bytes.wrap(fromBase58(address).toArray());
            XAmount balance = getAccountBalance(addr);
            long nonce = getAccountNonce(addr);

            return AccountInfo.builder()
                    .address(address)
                    .balance(balance)
                    .nonce(nonce)
                    .addressBytes(addr.toArray())
                    .build();

        } catch (Exception e) {
            log.error("Failed to get account by address: {}", address, e);
            return null;
        }
    }

    /**
     * Get total balance of all wallet accounts
     *
     * @return Total balance
     */
    public XAmount getTotalBalance() {
        XAmount totalBalance = XAmount.ZERO;
        List<ECKeyPair> accounts = dagKernel.getWallet().getAccounts();

        for (ECKeyPair account : accounts) {
            Bytes addr = Bytes.wrap(toBytesAddress(account));
            totalBalance = totalBalance.add(getAccountBalance(addr));
        }

        return totalBalance;
    }

    /**
     * Update account nonce (for transaction processing)
     *
     * @param address Account address bytes
     * @param nonce New nonce value
     */
    public void updateAccountNonce(Bytes address, long nonce) {
        dagKernel.getAccountStore().setNonce(address, UInt64.valueOf(nonce));
    }
}
