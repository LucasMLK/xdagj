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
package io.xdag.rpc.server.handler;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.xdag.core.Block;
import io.xdag.rpc.api.XdagApi;
import io.xdag.rpc.model.response.BlockResponse;
import io.xdag.rpc.model.response.ProcessResponse;
import io.xdag.rpc.model.response.XdagStatusResponse;
import io.xdag.rpc.server.protocol.JsonRpcRequest;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

public class JsonRequestHandlerTest {

    @Mock
    private XdagApi xdagApi;

    private JsonRequestHandler handler;
    private ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new JsonRequestHandler(xdagApi);
    }

    @Test
    public void testGetBlockByHash_WithTransactions() throws Exception {
        // Given
        List<BlockResponse.Link> refs = Arrays.asList(
                BlockResponse.Link.builder()
                        .direction(2)
                        .address("PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr")
                        .hashlow("00000000000000006b62843b22638779414d9b2d78bef91aaa03774b8334db3c")
                        .amount("0.000000000")
                        .build(),
                BlockResponse.Link.builder()
                        .direction(1)
                        .address("Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F")
                        .hashlow("0000000000000000455f756b25705f1adc6c39796c7b74d96b473ee8dda50853")
                        .amount("0.000000000")
                        .build(),
                BlockResponse.Link.builder()
                        .direction(1)
                        .address("5eKnKvTXWUBPZ3bNrC0+2fAuXh+yC+wb")
                        .hashlow("00000000000000001bec0bb21f5e2ef0d93e2daccd76674f4059d7f42aa7e2e5")
                        .amount("0.000000000")
                        .build()
        );

        List<BlockResponse.TxLink> transactions = Collections.singletonList(
                BlockResponse.TxLink.builder()
                        .direction(2)
                        .hashlow("00000000000000006b62843b22638779414d9b2d78bef91aaa03774b8334db3c")
                        .address("PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr")
                        .amount("1024.000000000")
                        .time(1735293631999L)
                        .remark("xdagj-node-1")
                        .build()
        );

        BlockResponse block = BlockResponse.builder()
                .height(2L)
                .balance("1024.000000000")
                .blockTime(1735293631999L)
                .timeStamp(1776940679167L)
                .state("Main")
                .hash("b557441898c582ba6b62843b22638779414d9b2d78bef91aaa03774b8334db3c")
                .address("PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr")
                .remark("xdagj-node-1")
                .diff("0x65ec2ec3e")
                .type("Main")
                .flags("3f")
                .totalPage(1)
                .refs(refs)
                .transactions(transactions)
                .build();

        when(xdagApi.xdag_getBlockByHash(any(), anyInt())).thenReturn(block);

        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getBlockByHash",
                    "params": ["PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr","1"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be instance of BlockResponse", result instanceof BlockResponse);
        BlockResponse response = (BlockResponse) result;
        assertEquals("Incorrect height", 2L, response.getHeight());
        assertEquals("Incorrect balance", "1024.000000000", response.getBalance());
        assertEquals("Incorrect block time", 1735293631999L, response.getBlockTime());
        assertEquals("Incorrect state", "Main", response.getState());
        assertEquals("Incorrect hash", "b557441898c582ba6b62843b22638779414d9b2d78bef91aaa03774b8334db3c", response.getHash());
        assertEquals("Incorrect address", "PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr", response.getAddress());
        assertEquals("Incorrect remark", "xdagj-node-1", response.getRemark());
        assertEquals("Incorrect diff", "0x65ec2ec3e", response.getDiff());
        assertEquals("Incorrect type", "Main", response.getType());
        assertEquals("Incorrect flags", "3f", response.getFlags());
        assertEquals("Incorrect total page", 1, response.getTotalPage());

        // Verify refs
        assertNotNull("Refs should not be null", response.getRefs());
        assertEquals("Incorrect number of refs", 3, response.getRefs().size());
        BlockResponse.Link firstRef = response.getRefs().get(0);
        assertEquals("Incorrect ref direction", 2, firstRef.getDirection());
        assertEquals("Incorrect ref address", "PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr", firstRef.getAddress());

        // Verify transactions
        assertNotNull("Transactions should not be null", response.getTransactions());
        assertEquals("Incorrect number of transactions", 1, response.getTransactions().size());
        BlockResponse.TxLink txLink = response.getTransactions().get(0);
        assertEquals("Incorrect transaction amount", "1024.000000000", txLink.getAmount());
        assertEquals("Incorrect transaction address", "PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr", txLink.getAddress());
        assertEquals("Incorrect transaction remark", "xdagj-node-1", txLink.getRemark());
    }

    @Test
    public void testGetBlockByHash_WithoutTransactions() throws Exception {
        // Given
        List<BlockResponse.Link> refs = Arrays.asList(
                BlockResponse.Link.builder()
                        .direction(2)
                        .address("PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr")
                        .hashlow("00000000000000006b62843b22638779414d9b2d78bef91aaa03774b8334db3c")
                        .amount("0.000000000")
                        .build(),
                BlockResponse.Link.builder()
                        .direction(1)
                        .address("Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F")
                        .hashlow("0000000000000000455f756b25705f1adc6c39796c7b74d96b473ee8dda50853")
                        .amount("0.000000000")
                        .build(),
                BlockResponse.Link.builder()
                        .direction(1)
                        .address("5eKnKvTXWUBPZ3bNrC0+2fAuXh+yC+wb")
                        .hashlow("00000000000000001bec0bb21f5e2ef0d93e2daccd76674f4059d7f42aa7e2e5")
                        .amount("0.000000000")
                        .build()
        );

        BlockResponse block = BlockResponse.builder()
                .height(2L)
                .balance("1024.000000000")
                .blockTime(1735293631999L)
                .timeStamp(1776940679167L)
                .state("Main")
                .hash("b557441898c582ba6b62843b22638779414d9b2d78bef91aaa03774b8334db3c")
                .address("PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr")
                .remark("xdagj-node-1")
                .diff("0x65ec2ec3e")
                .type("Main")
                .flags("3f")
                .totalPage(0)
                .refs(refs)
                .transactions(null)
                .build();

        when(xdagApi.xdag_getBlockByHash(any(), anyInt())).thenReturn(block);

        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getBlockByHash",
                    "params": ["PNs0g0t3A6oa+b54LZtNQXmHYyI7hGJr","0"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be instance of BlockResponse", result instanceof BlockResponse);
        BlockResponse response = (BlockResponse) result;
        assertEquals("Incorrect height", 2L, response.getHeight());
        assertEquals("Incorrect total page", 0, response.getTotalPage());
        assertNull("Transactions should be null", response.getTransactions());
    }

    @Test
    public void testGetBlockByNumber_WithTransactions() throws Exception {
        // Given
        List<BlockResponse.Link> refs = Collections.singletonList(
                BlockResponse.Link.builder()
                        .direction(2)
                        .address("Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F")
                        .hashlow("0000000000000000455f756b25705f1adc6c39796c7b74d96b473ee8dda50853")
                        .amount("0.000000000")
                        .build()
        );

        List<BlockResponse.TxLink> transactions = Collections.singletonList(
                BlockResponse.TxLink.builder()
                        .direction(2)
                        .hashlow("0000000000000000455f756b25705f1adc6c39796c7b74d96b473ee8dda50853")
                        .address("Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F")
                        .amount("1024.000000000")
                        .time(1735293477987L)
                        .remark("")
                        .build()
        );

        BlockResponse block = BlockResponse.builder()
                .height(1L)
                .balance("1024.000000000")
                .blockTime(1735293477987L)
                .timeStamp(1776940521459L)
                .state("Main")
                .hash("33a10497f0f57cd6455f756b25705f1adc6c39796c7b74d96b473ee8dda50853")
                .address("Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F")
                .remark("")
                .diff("0x4f55d5ce5")
                .type("Main")
                .flags("3f")
                .totalPage(1)
                .refs(refs)
                .transactions(transactions)
                .build();

        when(xdagApi.xdag_getBlockByNumber(any(), anyInt())).thenReturn(block);

        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getBlockByNumber",
                    "params": ["1","1"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be instance of BlockResponse", result instanceof BlockResponse);
        BlockResponse response = (BlockResponse) result;
        assertEquals("Incorrect height", 1L, response.getHeight());
        assertEquals("Incorrect balance", "1024.000000000", response.getBalance());
        assertEquals("Incorrect block time", 1735293477987L, response.getBlockTime());
        assertEquals("Incorrect state", "Main", response.getState());
        assertEquals("Incorrect hash", "33a10497f0f57cd6455f756b25705f1adc6c39796c7b74d96b473ee8dda50853", response.getHash());
        assertEquals("Incorrect address", "Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F", response.getAddress());
        assertEquals("Incorrect diff", "0x4f55d5ce5", response.getDiff());
        assertEquals("Incorrect type", "Main", response.getType());
        assertEquals("Incorrect flags", "3f", response.getFlags());
        assertEquals("Incorrect total page", 1, response.getTotalPage());

        // Verify refs
        assertNotNull("Refs should not be null", response.getRefs());
        assertEquals("Incorrect number of refs", 1, response.getRefs().size());
        BlockResponse.Link firstRef = response.getRefs().get(0);
        assertEquals("Incorrect ref direction", 2, firstRef.getDirection());
        assertEquals("Incorrect ref address", "Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F", firstRef.getAddress());

        // Verify transactions
        assertNotNull("Transactions should not be null", response.getTransactions());
        assertEquals("Incorrect number of transactions", 1, response.getTransactions().size());
        BlockResponse.TxLink txLink = response.getTransactions().get(0);
        assertEquals("Incorrect transaction amount", "1024.000000000", txLink.getAmount());
        assertEquals("Incorrect transaction address", "Uwil3eg+R2vZdHtseTls3BpfcCVrdV9F", txLink.getAddress());
        assertEquals("Incorrect transaction remark", "", txLink.getRemark());
    }

    @Test
    public void testBlockNumber() throws Exception {
        // Given
        when(xdagApi.xdag_blockNumber()).thenReturn("0");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_blockNumber",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect block number", "0", result);
    }

    @Test
    public void testCoinbase() throws Exception {
        // Given
        when(xdagApi.xdag_coinbase()).thenReturn("LF82sqRiZuJTDEfQ6GqkE2DpnXrbCu4kK");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_coinbase",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect coinbase address", "LF82sqRiZuJTDEfQ6GqkE2DpnXrbCu4kK", result);
    }

    @Test
    public void testGetBalance() throws Exception {
        // Given
        when(xdagApi.xdag_getBalance(any())).thenReturn("0.000000000");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getBalance",
                    "params": ["LF82sqRiZuJTDEfQ6GqkE2DpnXrbCu4kK"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect balance", "0.000000000", result);
    }

    @Test
    public void testGetTotalBalance() throws Exception {
        // Given
        when(xdagApi.xdag_getTotalBalance()).thenReturn("0.000000000");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getTotalBalance",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect total balance", "0.000000000", result);
    }

    @Test
    public void testGetStatus() throws Exception {
        // Given
        XdagStatusResponse statusResponse = XdagStatusResponse.builder()
                .nblock("1")
                .totalNblocks("1")
                .nmain("0")
                .totalNmain("0")
                .curDiff("0x1d0aeb43d")
                .netDiff("0x1d0aeb43d")
                .hashRateOurs("3.552713678800501E-15")
                .hashRateTotal("3.552713678800501E-15")
                .ourSupply("0.000000000")
                .netSupply("0.000000000")
                .build();

        when(xdagApi.xdag_getStatus()).thenReturn(statusResponse);

        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getStatus",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be instance of XdagStatusResponse", result instanceof XdagStatusResponse);
        XdagStatusResponse response = (XdagStatusResponse) result;
        assertEquals("Incorrect nblock value", "1", response.getNblock());
        assertEquals("Incorrect totalNblocks value", "1", response.getTotalNblocks());
        assertEquals("Incorrect curDiff value", "0x1d0aeb43d", response.getCurDiff());
        assertEquals("Incorrect netDiff value", "0x1d0aeb43d", response.getNetDiff());
        assertEquals("Incorrect hashRateOurs value", "3.552713678800501E-15", response.getHashRateOurs());
        assertEquals("Incorrect hashRateTotal value", "3.552713678800501E-15", response.getHashRateTotal());
        assertEquals("Incorrect ourSupply value", "0.000000000", response.getOurSupply());
        assertEquals("Incorrect netSupply value", "0.000000000", response.getNetSupply());
    }

    @Test
    public void testGetRewardByNumber() throws Exception {
        // Given
        when(xdagApi.xdag_getRewardByNumber(any())).thenReturn("128.000000000");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_getRewardByNumber",
                    "params": ["1000"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect reward value", "128.000000000", result);
    }

    @Test
    public void testNetType() throws Exception {
        // Given
        when(xdagApi.xdag_netType()).thenReturn("devnet");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_netType",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertEquals("Incorrect network type", "devnet", result);
    }

    @Test
    public void testNetConnectionList() throws Exception {
        // Given
        when(xdagApi.xdag_netConnectionList()).thenReturn(List.of());
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_netConnectionList",
                    "params": [],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be a List", result instanceof List);
        assertTrue("Connection list should be empty", ((List<?>) result).isEmpty());
    }

    @Test
    public void testSendRawTransaction() throws Exception {
        // Given
        String rawTx = "00000000000000002863550000000000feffed9d7d01000000000000000000005d453264dfe0f2dcd0b09fff8db233af668bf7aa873176470000000064000000defb03f1a99ce1498f19afa5b0c752d5409bb2fdc4e087e10000000064000000506bc1dc099358e5137292f4efdd57e400f29ba5132aa5d12b18dac1c1f6aaba12dfa82f55245fb4a8a8ddbbe2eb970f80347741ff0907e8844630004981eb230a329c87c2736c8067e7a15190587502e5bf761e4f919aaf84ce62b3f1f5cffc0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
        when(xdagApi.xdag_sendRawTransaction(any())).thenReturn("INVALID_BLOCK Block have no parent");
        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_sendRawTransaction",
                    "params": ["%s"],
                    "id": "1"
                }""".formatted(rawTx);

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should contain error message", result.toString().contains("INVALID_BLOCK"));
        assertEquals("Incorrect error message", "INVALID_BLOCK Block have no parent", result);
    }

    @Test
    public void testPersonalSendTransaction() throws Exception {
        // Given
        ProcessResponse processResponse = ProcessResponse.builder()
                .code(-10201)
                .result(null)
                .errMsg("balance not enough")
                .build();

        when(xdagApi.xdag_personal_sendTransaction(any(), any())).thenReturn(processResponse);

        String requestJson = """
                {
                    "jsonrpc": "2.0",
                    "method": "xdag_personal_sendTransaction",
                    "params": [{
                        "to": "LF82sqRiZuJTDEfQ6GqkE2DpnXrbCu4kK",
                        "value": "100",
                        "remark": "test"
                    }, "123"],
                    "id": "1"
                }""";

        // When
        Object result = handler.handle(mapper.readValue(requestJson, JsonRpcRequest.class));

        // Then
        assertTrue("Result should be instance of ProcessResponse", result instanceof ProcessResponse);
        ProcessResponse response = (ProcessResponse) result;
        assertEquals("Incorrect error code", -10201, response.getCode());
        assertEquals("Incorrect error message", "balance not enough", response.getErrMsg());
        assertNull("Result should be null", response.getResult());
        assertNull("ResInfo should be null", response.getResInfo());
    }
}