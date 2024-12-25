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

import io.xdag.rpc.dto.*;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class Web3Impl implements Web3XdagChain {

    private final Web3XdagChain web3XdagChain;

    @Override
    public XdagChainImpl getXdagChain() {
        return web3XdagChain.getXdagChain();
    }

    @Override
    public String xdag_protocolVersion() {
        return web3XdagChain.xdag_protocolVersion();
    }

    @Override
    public Object xdag_syncing() {
        return web3XdagChain.xdag_syncing();
    }

    @Override
    public String xdag_coinbase() {
        return web3XdagChain.xdag_coinbase();
    }

    @Override
    public String xdag_blockNumber() {
        return web3XdagChain.xdag_blockNumber();
    }

    @Override
    public String xdag_getBalance(String address) throws Exception {
        return web3XdagChain.xdag_getBalance(address);
    }

    @Override
    public String xdag_getTotalBalance() throws Exception {
        return web3XdagChain.xdag_getTotalBalance();
    }

    @Override
    public BlockResultDTO xdag_getTransactionByHash(String hash, int page) throws Exception {
        return web3XdagChain.xdag_getTransactionByHash(hash, page);
    }

    @Override
    public BlockResultDTO xdag_getBlockByNumber(String bnOrId, int page) {
        return web3XdagChain.xdag_getBlockByNumber(bnOrId, page);
    }

    @Override
    public BlockResultDTO xdag_getBlockByNumber(String bnOrId, int page, int pageSize) {
        return web3XdagChain.xdag_getBlockByNumber(bnOrId, page, pageSize);
    }

    @Override
    public String xdag_getRewardByNumber(String bnOrId) {
        return web3XdagChain.xdag_getRewardByNumber(bnOrId);
    }

    @Override
    public String xdag_getBalanceByNumber(String bnOrId) {
        return web3XdagChain.xdag_getBalanceByNumber(bnOrId);
    }

    @Override
    public Object xdag_getBlocksByNumber(String bnOrId) {
        return web3XdagChain.xdag_getBlocksByNumber(bnOrId);
    }

    @Override
    public String xdag_sendRawTransaction(String rawData) {
        return web3XdagChain.xdag_sendRawTransaction(rawData);
    }

    @Override
    public String xdag_sendTransaction(CallArguments args) {
        return web3XdagChain.xdag_sendTransaction(args);
    }

    @Override
    public Object xdag_personal_sendTransaction(CallArguments args, String passphrase) {
        return web3XdagChain.xdag_personal_sendTransaction(args, passphrase);
    }

    @Override
    public BlockResultDTO xdag_getBlockByHash(String blockHash, int page, String startTime, String endTime) {
        return web3XdagChain.xdag_getBlockByHash(blockHash, page, startTime, endTime);
    }

    @Override
    public BlockResultDTO xdag_getBlockByHash(String blockHash, int page) {
        return web3XdagChain.xdag_getBlockByHash(blockHash, page);
    }

    @Override
    public BlockResultDTO xdag_getBlockByHash(String blockHash, int page, String startTime, String endTime, int pageSize) {
        return web3XdagChain.xdag_getBlockByHash(blockHash, page, startTime, endTime, pageSize);
    }

    @Override
    public BlockResultDTO xdag_getBlockByHash(String blockHash, int page, int pageSize) {
        return web3XdagChain.xdag_getBlockByHash(blockHash, page, pageSize);
    }

    @Override
    public StatusDTO xdag_getStatus() throws Exception {
        return web3XdagChain.xdag_getStatus();
    }

    @Override
    public Object xdag_netType() throws Exception {
        return web3XdagChain.xdag_netType();
    }

    @Override
    public Object xdag_poolConfig() throws Exception {
        return web3XdagChain.xdag_poolConfig();
    }

    @Override
    public Object xdag_netConnectionList() throws Exception {
        return web3XdagChain.xdag_netConnectionList();
    }

    @Override
    public String xdag_getMaxXferBalance() throws Exception {
        return web3XdagChain.xdag_getMaxXferBalance();
    }

    @Override
    public String[] xdag_accounts() {
        return web3XdagChain.xdag_accounts();
    }

    @Override
    public String xdag_sign(String addr, String data) {
        return web3XdagChain.xdag_sign(addr, data);
    }

    @Override
    public String xdag_chainId() {
        return web3XdagChain.xdag_chainId();
    }
}
