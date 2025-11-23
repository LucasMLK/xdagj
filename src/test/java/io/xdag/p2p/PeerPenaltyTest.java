package io.xdag.p2p;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.core.XUnit;
import io.xdag.p2p.channel.Channel;

import io.xdag.p2p.message.XdagMessageCode;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class PeerPenaltyTest {

    @Mock
    private DagKernel dagKernel;
    @Mock
    private DagChain dagChain;
    @Mock
    private Channel channel;

    private XdagP2pEventHandler handler;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(dagKernel.getDagChain()).thenReturn(dagChain);
        when(channel.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 12345));
        
        handler = new XdagP2pEventHandler(dagKernel);
    }

    /**
     * Test that a malformed message triggers a penalty (disconnect).
     */
    @Test
    public void testPenalizeOnMalformedMessage() {
        // Create a byte array with correct message code but invalid body (garbage)
        byte[] malformedData = new byte[10];
        malformedData[0] = XdagMessageCode.NEW_BLOCK.toByte(); // Valid message code
        // Rest is zeros, which should fail deserialization for NewBlockMessage
        
        Bytes message = Bytes.wrap(malformedData);

        // Process message
        handler.onMessage(channel, message);

        // Verify that channel.close() was called (penalty applied)
        verify(channel, times(1)).close();
    }

    /**
     * Test that an INVALID_BLOCK triggers a penalty (disconnect).
     */
    @Test
    public void testPenalizeOnInvalidBlock() {
        // 1. Create a valid block structure to pass deserialization
        long time = System.currentTimeMillis();
        Block block = Block.createCandidate(
            time,
            UInt256.ONE,
            Bytes.random(20), // Random coinbase
            Collections.emptyList() // No links
        );
        
        // 2. Manually serialize body: [TTL] + [BlockBytes]
        byte[] blockBytes = block.toBytes();
        byte[] msgBytes = new byte[1 + blockBytes.length];
        msgBytes[0] = 1; // TTL = 1
        System.arraycopy(blockBytes, 0, msgBytes, 1, blockBytes.length);
        
        // 3. Prepend message code (NEW_BLOCK)
        byte[] fullMessage = new byte[msgBytes.length + 1];
        fullMessage[0] = XdagMessageCode.NEW_BLOCK.toByte();
        System.arraycopy(msgBytes, 0, fullMessage, 1, msgBytes.length);
        
        Bytes messageData = Bytes.wrap(fullMessage);

        // 4. Mock DagChain to return INVALID status using factory method
        DagImportResult invalidResult = DagImportResult.invalidBasic("Invalid signature");
        when(dagChain.tryToConnect(any(Block.class))).thenReturn(invalidResult);

        // 5. Process message
        handler.onMessage(channel, messageData);

        // 6. Verify that channel.close() was called
        verify(channel, times(1)).close();
    }

    /**
     * Test that a normal block processing (IMPORTED) does NOT trigger penalty.
     */
    @Test
    public void testNoPenalizeOnValidBlock() {
        // 1. Create a valid block structure
        long time = System.currentTimeMillis();
        Block block = Block.createCandidate(
            time,
            UInt256.ONE,
            Bytes.random(20),
            Collections.emptyList()
        );
        
        // 2. Manually serialize body: [TTL] + [BlockBytes]
        byte[] blockBytes = block.toBytes();
        byte[] msgBytes = new byte[1 + blockBytes.length];
        msgBytes[0] = 1; // TTL = 1
        System.arraycopy(blockBytes, 0, msgBytes, 1, blockBytes.length);
        
        // 3. Prepend message code
        byte[] fullMessage = new byte[msgBytes.length + 1];
        fullMessage[0] = XdagMessageCode.NEW_BLOCK.toByte();
        System.arraycopy(msgBytes, 0, fullMessage, 1, msgBytes.length);
        
        Bytes messageData = Bytes.wrap(fullMessage);

        // 4. Mock DagChain to return SUCCESS using factory method
        // Use mainBlock as an example of success
        DagImportResult successResult = DagImportResult.mainBlock(100L, 10L, UInt256.ONE, true);
        when(dagChain.tryToConnect(any(Block.class))).thenReturn(successResult);

        // 5. Process message
        handler.onMessage(channel, messageData);

        // 6. Verify that channel.close() was NOT called
        verify(channel, never()).close();
    }
}