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

package io.xdag.rpc;

import io.xdag.rpc.dto.BlockResultDTO;
import io.xdag.rpc.dto.StatusDTO;


public interface Web3XdagChain {

    class CallArguments {
        public String from;
        public String to;
        public String value;
        public String remark;
        @Override
        public String toString() {
            return "CallArguments{" +
                    "from='" + from + '\'' +
                    ", to='" + to + '\'' +
                    ", value='" + value + '\'' +
                    ", remark='" + remark + '\'' +
                    '}';
        }
    }

    default String[] xdag_accounts() {
        return getXdagChain().accounts();
    }

    default String xdag_sign(String addr, String data) {
        return getXdagChain().sign(addr, data);
    }

    default String xdag_chainId() {
        return getXdagChain().chainId();
    }

    XdagChainImpl getXdagChain();

    String xdag_protocolVersion();

    Object xdag_syncing();

    String xdag_coinbase();

    String xdag_blockNumber();

    String xdag_getBalance(String address) throws Exception;

    String xdag_getTotalBalance() throws Exception;

    default BlockResultDTO xdag_getTransactionByHash(String hash, int page) throws Exception {
        return xdag_getBlockByHash(hash, page);
    }

    default BlockResultDTO xdag_getBlockByNumber(String bnOrId, int page) {
        return getXdagChain().getBlockByNumber(bnOrId, page);
    }

    default BlockResultDTO xdag_getBlockByNumber(String bnOrId, int page, int pageSize) {
        return getXdagChain().getBlockByNumber(bnOrId, page, pageSize);
    }

    default String xdag_getRewardByNumber(String bnOrId) {
        return getXdagChain().getRewardByNumber(bnOrId);
    }

    default String xdag_getBalanceByNumber(String bnOrId) {
        return getXdagChain().getBalanceByNumber(bnOrId);
    }

    default Object xdag_getBlocksByNumber(String bnOrId) {
        return getXdagChain().getBlocksByNumber(bnOrId);
    }

    default String xdag_sendRawTransaction(String rawData) {
        return getXdagChain().sendRawTransaction(rawData);
    }

    default String xdag_sendTransaction(CallArguments args) {
        return getXdagChain().sendTransaction(args);
    }

    default Object xdag_personal_sendTransaction(CallArguments args, String passphrase) {
        return getXdagChain().personalSendTransaction(args, passphrase);
    }

    default BlockResultDTO xdag_getBlockByHash(String blockHash, int page, String startTime, String endTime) {
        return getXdagChain().getBlockByHash(blockHash, page, startTime, endTime);
    }

    default BlockResultDTO xdag_getBlockByHash(String blockHash, int page) {
        return getXdagChain().getBlockByHash(blockHash, page);
    }

    default BlockResultDTO xdag_getBlockByHash(String blockHash, int page, String startTime, String endTime, int pageSize) {
        return getXdagChain().getBlockByHash(blockHash, page, startTime, endTime, pageSize);
    }

    default BlockResultDTO xdag_getBlockByHash(String blockHash, int page, int pageSize) {
        return getXdagChain().getBlockByHash(blockHash, page, pageSize);
    }

    StatusDTO xdag_getStatus() throws Exception;

    Object xdag_netType() throws Exception;

    Object xdag_poolConfig() throws Exception;

    Object xdag_netConnectionList() throws Exception;

    String xdag_getMaxXferBalance() throws Exception;
}
