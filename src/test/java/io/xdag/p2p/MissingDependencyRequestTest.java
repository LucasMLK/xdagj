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

package io.xdag.p2p;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.DagChain;
import io.xdag.core.DagImportResult;
import io.xdag.core.DagImportResult.ErrorDetails;
import io.xdag.core.DagImportResult.ErrorType;
import io.xdag.core.DagImportResult.ImportStatus;
import io.xdag.p2p.channel.Channel;
import io.xdag.p2p.message.BlocksReplyMessage;
import io.xdag.p2p.message.GetBlocksMessage;
import io.xdag.p2p.message.Message;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for BUG-SYNC-006: Missing dependency request handling.
 *
 * <p>When a block import fails due to missing dependency (e.g., orphan block reference),
 * the P2P layer should actively request the missing block from the peer.
 *
 * @since XDAGJ 1.0
 */
public class MissingDependencyRequestTest {

  private XdagP2pEventHandler eventHandler;
  private DagKernel mockKernel;
  private DagChain mockDagChain;
  private Channel mockChannel;

  @Before
  public void setUp() {
    mockKernel = mock(DagKernel.class);
    mockDagChain = mock(DagChain.class);
    mockChannel = mock(Channel.class);

    when(mockKernel.getDagChain()).thenReturn(mockDagChain);
    when(mockChannel.getRemoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8001));

    eventHandler = new XdagP2pEventHandler(mockKernel);
  }

  /**
   * Test that missing dependency triggers a GET_BLOCKS request.
   *
   * <p>BUG-SYNC-006: When import fails with MISSING_DEPENDENCY status,
   * the handler should send a GET_BLOCKS request for the missing block.
   */
  @Test
  public void missingDependencyShouldTriggerBlockRequest() throws Exception {
    // Setup: Create a block that will fail with MISSING_DEPENDENCY
    Bytes32 missingHash = Bytes32.fromHexString(
        "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef");

    Block testBlock = Block.createWithNonce(
        1000,
        UInt256.ONE,
        Bytes32.ZERO,
        Bytes.wrap(new byte[20]),
        List.of()
    );

    // Mock import result with MISSING_DEPENDENCY
    DagImportResult missingDepResult = DagImportResult.builder()
        .status(ImportStatus.MISSING_DEPENDENCY)
        .errorDetails(ErrorDetails.builder()
            .errorType(ErrorType.LINK_VALIDATION)
            .message("Link target not found")
            .missingDependency(missingHash)
            .build())
        .build();

    when(mockDagChain.tryToConnect(any(Block.class))).thenReturn(missingDepResult);

    // Call handleMissingDependency via reflection
    Method handleMethod = XdagP2pEventHandler.class.getDeclaredMethod(
        "handleMissingDependency", Channel.class, Block.class, DagImportResult.class);
    handleMethod.setAccessible(true);
    handleMethod.invoke(eventHandler, mockChannel, testBlock, missingDepResult);

    // Verify: GET_BLOCKS request was sent
    ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
    verify(mockChannel, times(1)).send(messageCaptor.capture());

    Message sentMessage = messageCaptor.getValue();
    assertTrue("Should send GetBlocksMessage", sentMessage instanceof GetBlocksMessage);

    GetBlocksMessage getBlocksMsg = (GetBlocksMessage) sentMessage;
    assertEquals("Should request the missing hash", 1, getBlocksMsg.getHashes().size());
    assertEquals("Should request correct hash", missingHash, getBlocksMsg.getHashes().get(0));
  }

  /**
   * Test that duplicate requests are prevented within expiry window.
   *
   * <p>BUG-SYNC-006: To prevent request flooding, the handler should not
   * request the same missing block multiple times within a short window.
   */
  @Test
  public void duplicateRequestsShouldBePrevented() throws Exception {
    Bytes32 missingHash = Bytes32.fromHexString(
        "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890");

    Block testBlock = Block.createWithNonce(
        1000,
        UInt256.ONE,
        Bytes32.ZERO,
        Bytes.wrap(new byte[20]),
        List.of()
    );

    DagImportResult missingDepResult = DagImportResult.builder()
        .status(ImportStatus.MISSING_DEPENDENCY)
        .errorDetails(ErrorDetails.builder()
            .errorType(ErrorType.LINK_VALIDATION)
            .missingDependency(missingHash)
            .build())
        .build();

    when(mockDagChain.tryToConnect(any(Block.class))).thenReturn(missingDepResult);

    Method handleMethod = XdagP2pEventHandler.class.getDeclaredMethod(
        "handleMissingDependency", Channel.class, Block.class, DagImportResult.class);
    handleMethod.setAccessible(true);

    // First call - should send request
    handleMethod.invoke(eventHandler, mockChannel, testBlock, missingDepResult);
    verify(mockChannel, times(1)).send(any(GetBlocksMessage.class));

    // Second call - should NOT send duplicate request
    handleMethod.invoke(eventHandler, mockChannel, testBlock, missingDepResult);
    verify(mockChannel, times(1)).send(any(GetBlocksMessage.class)); // still only 1 call
  }

  /**
   * Test that null error details are handled gracefully.
   */
  @Test
  public void nullErrorDetailsShouldBeHandledGracefully() throws Exception {
    Block testBlock = Block.createWithNonce(
        1000,
        UInt256.ONE,
        Bytes32.ZERO,
        Bytes.wrap(new byte[20]),
        List.of()
    );

    // Import result with null error details
    DagImportResult result = DagImportResult.builder()
        .status(ImportStatus.MISSING_DEPENDENCY)
        .errorDetails(null)
        .build();

    Method handleMethod = XdagP2pEventHandler.class.getDeclaredMethod(
        "handleMissingDependency", Channel.class, Block.class, DagImportResult.class);
    handleMethod.setAccessible(true);

    // Should not throw exception
    handleMethod.invoke(eventHandler, mockChannel, testBlock, result);

    // Should not send any request
    verify(mockChannel, never()).send(any(Message.class));
  }

  /**
   * Test that null missing dependency hash is handled gracefully.
   */
  @Test
  public void nullMissingHashShouldBeHandledGracefully() throws Exception {
    Block testBlock = Block.createWithNonce(
        1000,
        UInt256.ONE,
        Bytes32.ZERO,
        Bytes.wrap(new byte[20]),
        List.of()
    );

    // Import result with error details but null missing dependency
    DagImportResult result = DagImportResult.builder()
        .status(ImportStatus.MISSING_DEPENDENCY)
        .errorDetails(ErrorDetails.builder()
            .errorType(ErrorType.LINK_VALIDATION)
            .missingDependency(null)
            .build())
        .build();

    Method handleMethod = XdagP2pEventHandler.class.getDeclaredMethod(
        "handleMissingDependency", Channel.class, Block.class, DagImportResult.class);
    handleMethod.setAccessible(true);

    // Should not throw exception
    handleMethod.invoke(eventHandler, mockChannel, testBlock, result);

    // Should not send any request
    verify(mockChannel, never()).send(any(Message.class));
  }

  /**
   * Test that tracking cache cleans up expired entries.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void expiredEntriesShouldBeCleanedUp() throws Exception {
    // Access the private tracking map via reflection
    Field trackingField = XdagP2pEventHandler.class.getDeclaredField("recentlyRequestedBlocks");
    trackingField.setAccessible(true);
    Map<Bytes32, Long> trackingMap = (Map<Bytes32, Long>) trackingField.get(eventHandler);

    // Add an "old" entry (simulating expired entry)
    Bytes32 oldHash = Bytes32.fromHexString(
        "0x1111111111111111111111111111111111111111111111111111111111111111");
    trackingMap.put(oldHash, System.currentTimeMillis() - 10 * 60 * 1000); // 10 minutes ago

    // Add a "recent" entry
    Bytes32 recentHash = Bytes32.fromHexString(
        "0x2222222222222222222222222222222222222222222222222222222222222222");
    trackingMap.put(recentHash, System.currentTimeMillis());

    // Trigger cleanup by calling handleMissingDependency
    Bytes32 newHash = Bytes32.fromHexString(
        "0x3333333333333333333333333333333333333333333333333333333333333333");

    Block testBlock = Block.createWithNonce(
        1000,
        UInt256.ONE,
        Bytes32.ZERO,
        Bytes.wrap(new byte[20]),
        List.of()
    );

    DagImportResult result = DagImportResult.builder()
        .status(ImportStatus.MISSING_DEPENDENCY)
        .errorDetails(ErrorDetails.builder()
            .errorType(ErrorType.LINK_VALIDATION)
            .missingDependency(newHash)
            .build())
        .build();

    Method handleMethod = XdagP2pEventHandler.class.getDeclaredMethod(
        "handleMissingDependency", Channel.class, Block.class, DagImportResult.class);
    handleMethod.setAccessible(true);
    handleMethod.invoke(eventHandler, mockChannel, testBlock, result);

    // Old entry should be removed
    assertFalse("Expired entry should be removed", trackingMap.containsKey(oldHash));

    // Recent entry should still exist
    assertTrue("Recent entry should still exist", trackingMap.containsKey(recentHash));

    // New entry should be added
    assertTrue("New entry should be added", trackingMap.containsKey(newHash));
  }
}
