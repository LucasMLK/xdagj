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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.api.service.dto.PagedResult;
import io.xdag.api.service.dto.TransactionInfo;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.DagChain;
import io.xdag.core.Transaction;
import io.xdag.core.XAmount;
import io.xdag.store.TransactionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link TransactionApiService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class TransactionApiServiceTest {

    @Mock
    private DagKernel dagKernel;

    @Mock
    private DagChain dagChain;

    @Mock
    private TransactionStore transactionStore;

    private TransactionApiService transactionApiService;

    private final Map<Long, Block> blockByHeight = new HashMap<>();
    private final Map<Bytes32, Block> blockByHash = new HashMap<>();
    private final Map<Bytes32, List<Transaction>> blockTransactions = new HashMap<>();
    private final Map<Bytes32, Bytes32> txToBlock = new HashMap<>();

    @Before
    public void setUp() {
        when(dagKernel.getDagChain()).thenReturn(dagChain);
        when(dagKernel.getTransactionStore()).thenReturn(transactionStore);

        transactionApiService = new TransactionApiService(dagKernel);
    }

    @Test
    public void getRecentTransactionsPageReturnsNewestFirst() {
        prepareChainData();

        PagedResult<TransactionInfo> firstPage =
                transactionApiService.getRecentTransactionsPage(1, 2);

        assertEquals(3, firstPage.getTotal());
        assertEquals(2, firstPage.getItems().size());
        TransactionInfo newest = firstPage.getItems().get(0);
        TransactionInfo secondNewest = firstPage.getItems().get(1);
        assertEquals(Long.valueOf(2L), newest.getBlockHeight());
        assertEquals(Long.valueOf(2L), secondNewest.getBlockHeight());
        assertTrue(newest.getTimestamp() >= secondNewest.getTimestamp());
        assertEquals("Confirmed (Main)", newest.getStatus());

        PagedResult<TransactionInfo> secondPage =
                transactionApiService.getRecentTransactionsPage(2, 2);
        assertEquals(3, secondPage.getTotal());
        assertEquals(1, secondPage.getItems().size());
        assertEquals(Long.valueOf(1L), secondPage.getItems().get(0).getBlockHeight());
    }

    @Test
    public void getRecentTransactionsPageReturnsEmptyWhenOffsetTooLarge() {
        prepareChainData();

        PagedResult<TransactionInfo> result =
                transactionApiService.getRecentTransactionsPage(5, 10);

        assertEquals(3, result.getTotal());
        assertTrue(result.getItems().isEmpty());
    }

    private void prepareChainData() {
        blockByHeight.clear();
        blockByHash.clear();
        blockTransactions.clear();
        txToBlock.clear();

        Block latest = createBlock(2L);
        Block previous = createBlock(1L);
        blockByHeight.put(2L, latest);
        blockByHeight.put(1L, previous);
        blockByHash.put(latest.getHash(), latest);
        blockByHash.put(previous.getHash(), previous);

        List<Transaction> latestBlockTx = List.of(
                createTransaction(1),
                createTransaction(2));
        List<Transaction> previousBlockTx = List.of(createTransaction(3));
        blockTransactions.put(latest.getHash(), latestBlockTx);
        blockTransactions.put(previous.getHash(), previousBlockTx);
        latestBlockTx.forEach(tx -> txToBlock.put(tx.getHash(), latest.getHash()));
        previousBlockTx.forEach(tx -> txToBlock.put(tx.getHash(), previous.getHash()));

        when(dagChain.getMainChainLength()).thenReturn(2L);
        when(dagChain.getMainBlockByHeight(anyLong()))
                .thenAnswer(invocation -> blockByHeight.get(invocation.getArgument(0)));
        when(dagChain.getBlockByHash(any(Bytes32.class), anyBoolean()))
                .thenAnswer(invocation -> blockByHash.get(invocation.getArgument(0)));

        when(transactionStore.getTransactionCount()).thenReturn(3L);
        when(transactionStore.getTransactionsByBlock(any(Bytes32.class)))
                .thenAnswer(invocation ->
                        blockTransactions.getOrDefault(invocation.getArgument(0), List.of()));
        when(transactionStore.getBlockByTransaction(any(Bytes32.class)))
                .thenAnswer(invocation -> txToBlock.get(invocation.getArgument(0)));
    }

    private Block createBlock(long height) {
        byte[] coinbase = new byte[20];
        coinbase[19] = (byte) height;

        BlockHeader header = BlockHeader.builder()
                .epoch(height)
                .difficulty(UInt256.valueOf(height))
                .nonce(Bytes32.random())
                .coinbase(Bytes.wrap(coinbase))
                .build();

        BlockInfo info = BlockInfo.builder()
                .hash(Bytes32.random())
                .height(height)
                .epoch(height)
                .difficulty(UInt256.valueOf(height))
                .build();

        Block block = Block.builder()
                .header(header)
                .links(new ArrayList<>())
                .info(info)
                .build();
        block.getHash();
        return block;
    }

    private Transaction createTransaction(long nonce) {
        byte[] from = new byte[20];
        from[18] = (byte) nonce;
        byte[] to = new byte[20];
        to[17] = (byte) (nonce + 1);

        return Transaction.builder()
                .from(Bytes.wrap(from))
                .to(Bytes.wrap(to))
                .amount(XAmount.of(nonce))
                .fee(XAmount.ZERO)
                .nonce(nonce)
                .v(27)
                .r(Bytes32.random())
                .s(Bytes32.random())
                .build();
    }
}
