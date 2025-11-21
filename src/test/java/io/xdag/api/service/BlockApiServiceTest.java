package io.xdag.api.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.api.dto.BlockSummary;
import io.xdag.api.dto.PagedResult;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.DagChain;
import io.xdag.db.TransactionStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link BlockApiService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class BlockApiServiceTest {

    @Mock
    private DagKernel dagKernel;

    @Mock
    private TransactionApiService transactionApiService;

    @Mock
    private DagChain dagChain;

    @Mock
    private TransactionStore transactionStore;

    private BlockApiService blockApiService;

    @Before
    public void setUp() {
        when(dagKernel.getDagChain()).thenReturn(dagChain);
        when(dagKernel.getTransactionStore()).thenReturn(transactionStore);

        blockApiService = new BlockApiService(dagKernel, transactionApiService);
    }

    @Test
    public void getMainBlocksPageReturnsLatestBlocks() {
        when(dagChain.getMainChainLength()).thenReturn(5L);

        Map<Long, Block> blocks = new HashMap<>();
        Map<String, Integer> txCountByHash = new HashMap<>();
        for (long height = 1; height <= 5; height++) {
            Block block = createBlock(height);
            blocks.put(height, block);
            txCountByHash.put(block.getHash().toHexString(), (int) height);
        }

        when(dagChain.getMainBlockByHeight(anyLong()))
                .thenAnswer(invocation -> blocks.get(invocation.getArgument(0)));

        when(transactionStore.getTransactionsByBlock(any(Bytes32.class)))
                .thenAnswer(invocation -> {
                    Bytes32 hash = invocation.getArgument(0);
                    int count = txCountByHash.getOrDefault(hash.toHexString(), 0);
                    return IntStream.range(0, count)
                            .mapToObj(i -> mock(io.xdag.core.Transaction.class))
                            .collect(Collectors.toList());
                });

        PagedResult<BlockSummary> result = blockApiService.getMainBlocksPage(1, 2);

        assertEquals(5, result.getTotal());
        assertEquals(2, result.getItems().size());
        BlockSummary first = result.getItems().get(0);
        BlockSummary second = result.getItems().get(1);

        assertEquals(5, first.getHeight());
        assertEquals(4, second.getHeight());
        assertEquals(txCountByHash.get(first.getHash()).intValue(), first.getTransactionCount());
        assertEquals(txCountByHash.get(second.getHash()).intValue(), second.getTransactionCount());
    }

    @Test
    public void getMainBlocksPageReturnsEmptyWhenOffsetExceedsChain() {
        when(dagChain.getMainChainLength()).thenReturn(3L);

        PagedResult<BlockSummary> result = blockApiService.getMainBlocksPage(5, 2);

        assertEquals(3, result.getTotal());
        assertTrue(result.getItems().isEmpty());
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

        // Trigger hash generation so summaries reference deterministic values.
        block.getHash();
        return block;
    }
}
