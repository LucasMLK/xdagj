package io.xdag.consensus.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.DagChain;
import io.xdag.core.Link;
import io.xdag.store.DagStore;
import io.xdag.store.cache.DagEntityResolver;
import io.xdag.store.cache.ResolvedLinks;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HybridSyncManagerTest {

    @Mock private DagKernel dagKernel;
    @Mock private DagChain dagChain;
    @Mock private HybridSyncP2pAdapter p2pAdapter;
    @Mock private DagStore dagStore;
    @Mock private DagEntityResolver entityResolver;

    private HybridSyncManager hybridSyncManager;

    @Before
    public void setUp() {
        when(dagKernel.getDagStore()).thenReturn(dagStore);
        when(dagKernel.getEntityResolver()).thenReturn(entityResolver);
        hybridSyncManager = new HybridSyncManager(dagKernel, dagChain, p2pAdapter);
    }

    @Test
    public void identifyMissingBlocksLoadsRawBlockData() throws Exception {
        Bytes32 pendingHash = filledBytes((byte) 1);
        Bytes32 missingHash = filledBytes((byte) 2);

        Block pendingBlock = Block.builder()
                .header(BlockHeader.builder()
                        .epoch(100L)
                        .difficulty(UInt256.valueOf(1))
                        .nonce(Bytes32.ZERO)
                        .coinbase(Bytes.wrap(new byte[20]))
                        .build())
                .links(List.of(Link.toBlock(missingHash)))
                .build();

        // Mock DagStore.getPendingBlocks() to return pending block hashes
        when(dagStore.getPendingBlocks(anyInt(), anyLong()))
                .thenReturn(Collections.singletonList(pendingHash));
        when(dagStore.getBlockByHash(pendingHash, true)).thenReturn(pendingBlock);

        ResolvedLinks resolvedLinks = ResolvedLinks.builder()
                .referencedBlocks(new ArrayList<>())
                .referencedTransactions(new ArrayList<>())
                .missingReferences(new ArrayList<>(Collections.singletonList(missingHash)))
                .build();
        when(entityResolver.resolveAllLinks(pendingBlock)).thenReturn(resolvedLinks);

        Method method = HybridSyncManager.class.getDeclaredMethod("identifyMissingBlocks");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<Bytes32> result = (Set<Bytes32>) method.invoke(hybridSyncManager);

        assertEquals(1, result.size());
        assertTrue(result.contains(missingHash));
        verify(dagStore).getBlockByHash(pendingHash, true);
        verify(dagStore, never()).getBlockByHash(pendingHash, false);
    }

    private static Bytes32 filledBytes(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Bytes32.wrap(bytes);
    }
}
