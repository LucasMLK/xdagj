package io.xdag.rpc;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for XdagApiDocs
 */
public class XdagApiDocsTest {

    @Test
    public void testProtocolVersionRequest() {
        XdagApiDocs.ProtocolVersion.Request request = new XdagApiDocs.ProtocolVersion.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_protocolVersion", request.method);
        assertTrue(request.params.isEmpty());
        assertNull(request.id);
    }

    @Test
    public void testSyncingRequest() {
        XdagApiDocs.Syncing.Request request = new XdagApiDocs.Syncing.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_syncing", request.method);
        assertTrue(request.params.isEmpty());
        assertNull(request.id);
    }

    @Test
    public void testCoinbaseRequest() {
        XdagApiDocs.Coinbase.Request request = new XdagApiDocs.Coinbase.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_coinbase", request.method);
        assertTrue(request.params.isEmpty());
        assertNull(request.id);
    }

    @Test
    public void testBlockNumberRequest() {
        XdagApiDocs.BlockNumber.Request request = new XdagApiDocs.BlockNumber.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_blockNumber", request.method);
        assertTrue(request.params.isEmpty());
        assertNull(request.id);
    }

    @Test
    public void testGetBalanceRequest() {
        XdagApiDocs.GetBalance.Request request = new XdagApiDocs.GetBalance.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_getBalance", request.method);
        assertNull(request.params);  // params will be set when making the request
        assertNull(request.id);
    }

    @Test
    public void testGetTotalBalanceRequest() {
        XdagApiDocs.GetTotalBalance.Request request = new XdagApiDocs.GetTotalBalance.Request();
        assertEquals("2.0", request.jsonrpc);
        assertEquals("xdag_getTotalBalance", request.method);
        assertTrue(request.params.isEmpty());
        assertNull(request.id);
    }

    @Test
    public void testResponseObjects() {
        // Test response objects initialization
        XdagApiDocs.ProtocolVersion.Response protocolResponse = new XdagApiDocs.ProtocolVersion.Response();
        assertEquals("2.0", protocolResponse.jsonrpc);
        assertNull(protocolResponse.result);
        assertNull(protocolResponse.id);

        XdagApiDocs.Syncing.Response syncResponse = new XdagApiDocs.Syncing.Response();
        assertEquals("2.0", syncResponse.jsonrpc);
        assertNull(syncResponse.result);
        assertNull(syncResponse.id);

        XdagApiDocs.GetBalance.Response balanceResponse = new XdagApiDocs.GetBalance.Response();
        assertEquals("2.0", balanceResponse.jsonrpc);
        assertNull(balanceResponse.result);
        assertNull(balanceResponse.id);
    }
}
