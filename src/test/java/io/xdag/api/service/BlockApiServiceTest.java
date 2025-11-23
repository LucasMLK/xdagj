package io.xdag.api.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.xdag.DagKernel;
import io.xdag.api.service.dto.BlockSummary;
import io.xdag.api.service.dto.PagedResult;
import io.xdag.core.Block;
import io.xdag.core.BlockHeader;
import io.xdag.core.BlockInfo;
import io.xdag.core.DagChain;
import io.xdag.store.DagStore;
import io.xdag.store.TransactionStore;
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
    private DagStore dagStore;

    @Mock
    private TransactionStore transactionStore;

    private BlockApiService blockApiService;

    @Before
    public void setUp() {
        when(dagKernel.getDagChain()).thenReturn(dagChain);
        when(dagKernel.getDagStore()).thenReturn(dagStore);
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

        // Mock getTransactionCountByBlock() - this is what BlockApiService actually calls
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenAnswer(invocation -> {
                    Bytes32 hash = invocation.getArgument(0);
                    return txCountByHash.getOrDefault(hash.toHexString(), 0);
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

    @Test
    public void getBlocksByEpochReturnsAllBlocksInEpoch() {
        long epoch = 12345L;
        List<Block> blocksInEpoch = new ArrayList<>();

        // Create 3 blocks in the same epoch (1 main block + 2 orphans)
        for (int i = 0; i < 3; i++) {
            Block block = createBlockWithEpoch(epoch, i == 0); // First is main block
            blocksInEpoch.add(block);
        }

        when(dagStore.getCandidateBlocksInEpoch(epoch)).thenReturn(blocksInEpoch);
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenReturn(0);

        List<BlockSummary> result = blockApiService.getBlocksByEpoch(epoch);

        assertEquals(3, result.size());

        // Verify blocks are sorted by hash (smallest first)
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getHash().compareTo(result.get(i + 1).getHash()) < 0);
        }

        // Verify we have exactly 1 main block and 2 orphan blocks
        long mainCount = result.stream().filter(b -> "Main".equals(b.getState())).count();
        long orphanCount = result.stream().filter(b -> "Orphan".equals(b.getState())).count();
        assertEquals(1, mainCount);
        assertEquals(2, orphanCount);
    }

    @Test
    public void getBlocksByEpochReturnsEmptyWhenNoBlocks() {
        long epoch = 99999L;
        when(dagStore.getCandidateBlocksInEpoch(epoch)).thenReturn(new ArrayList<>());

        List<BlockSummary> result = blockApiService.getBlocksByEpoch(epoch);

        assertTrue(result.isEmpty());
    }

    @Test
    public void getBlocksByEpochPageReturnsPaginatedBlocks() {
        long epoch = 12345L;
        List<Block> blocksInEpoch = new ArrayList<>();

        // Create 25 blocks in the same epoch
        for (int i = 0; i < 25; i++) {
            Block block = createBlockWithEpoch(epoch, i == 0);
            blocksInEpoch.add(block);
        }

        when(dagStore.getCandidateBlocksInEpoch(epoch)).thenReturn(blocksInEpoch);
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenReturn(0);

        // Get first page (size 10)
        PagedResult<BlockSummary> page1 = blockApiService.getBlocksByEpochPage(epoch, 1, 10);

        assertEquals(25, page1.getTotal());
        assertEquals(10, page1.getItems().size());

        // Get second page
        PagedResult<BlockSummary> page2 = blockApiService.getBlocksByEpochPage(epoch, 2, 10);

        assertEquals(25, page2.getTotal());
        assertEquals(10, page2.getItems().size());

        // Get third page (only 5 items)
        PagedResult<BlockSummary> page3 = blockApiService.getBlocksByEpochPage(epoch, 3, 10);

        assertEquals(25, page3.getTotal());
        assertEquals(5, page3.getItems().size());
    }

    @Test
    public void getBlocksByEpochPageReturnsEmptyWhenOffsetExceedsTotal() {
        long epoch = 12345L;
        List<Block> blocksInEpoch = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            blocksInEpoch.add(createBlockWithEpoch(epoch, true));
        }

        when(dagStore.getCandidateBlocksInEpoch(epoch)).thenReturn(blocksInEpoch);
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenReturn(0);

        PagedResult<BlockSummary> result = blockApiService.getBlocksByEpochPage(epoch, 10, 10);

        assertEquals(5, result.getTotal());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    public void getBlocksByEpochRangeReturnsBlocksGroupedByEpoch() {
        long fromEpoch = 100L;
        long toEpoch = 102L;

        // Setup blocks for each epoch (use ArrayList to make them mutable for sorting)
        Map<Long, List<Block>> blocksByEpoch = new HashMap<>();
        blocksByEpoch.put(100L, new ArrayList<>(List.of(
                createBlockWithEpoch(100L, true),
                createBlockWithEpoch(100L, false))));
        blocksByEpoch.put(101L, new ArrayList<>(List.of(
                createBlockWithEpoch(101L, true))));
        blocksByEpoch.put(102L, new ArrayList<>(List.of(
                createBlockWithEpoch(102L, true),
                createBlockWithEpoch(102L, false),
                createBlockWithEpoch(102L, false))));

        when(dagStore.getCandidateBlocksInEpoch(anyLong()))
                .thenAnswer(invocation -> {
                    Long epoch = invocation.getArgument(0);
                    return blocksByEpoch.getOrDefault(epoch, new ArrayList<>());
                });
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenReturn(0);

        List<BlockApiService.EpochBlocks> result = blockApiService.getBlocksByEpochRange(fromEpoch, toEpoch);

        assertEquals(3, result.size());
        assertEquals(100L, result.get(0).getEpoch());
        assertEquals(2, result.get(0).getBlockCount());
        assertEquals(101L, result.get(1).getEpoch());
        assertEquals(1, result.get(1).getBlockCount());
        assertEquals(102L, result.get(2).getEpoch());
        assertEquals(3, result.get(2).getBlockCount());
    }

    @Test
    public void getBlocksByEpochRangeReturnsEmptyWhenInvalidRange() {
        long fromEpoch = 200L;
        long toEpoch = 100L; // Invalid: from > to

        List<BlockApiService.EpochBlocks> result = blockApiService.getBlocksByEpochRange(fromEpoch, toEpoch);

        assertTrue(result.isEmpty());
    }

    @Test
    public void getBlocksByEpochRangeLimitsRangeSizeTo1000() {
        long fromEpoch = 1L;
        long toEpoch = 2000L; // Range > 1000

        // Return a mutable list for sorting
        when(dagStore.getCandidateBlocksInEpoch(anyLong()))
                .thenReturn(new ArrayList<>(List.of(createBlockWithEpoch(1L, true))));
        when(transactionStore.getTransactionCountByBlock(any(Bytes32.class)))
                .thenReturn(0);

        List<BlockApiService.EpochBlocks> result = blockApiService.getBlocksByEpochRange(fromEpoch, toEpoch);

        // Should only query up to epoch 1000 (fromEpoch + 999)
        assertEquals(1000, result.size());
        assertEquals(1L, result.get(0).getEpoch());
        assertEquals(1000L, result.get(999).getEpoch());
    }

    /**
     * Helper method to create a block with specific epoch and main/orphan state
     */
    private Block createBlockWithEpoch(long epoch, boolean isMainBlock) {
        byte[] coinbase = new byte[20];
        coinbase[0] = (byte) (epoch & 0xFF);

        BlockHeader header = BlockHeader.builder()
                .epoch(epoch)
                .difficulty(UInt256.valueOf(1000000))
                .nonce(Bytes32.random())
                .coinbase(Bytes.wrap(coinbase))
                .build();

        BlockInfo info = BlockInfo.builder()
                .hash(Bytes32.random())
                .height(isMainBlock ? epoch : 0) // Orphan blocks have height 0
                .epoch(epoch)
                .difficulty(UInt256.valueOf(1000000))
                .build();

        Block block = Block.builder()
                .header(header)
                .links(new ArrayList<>())
                .info(info)
                .build();

        block.getHash();
        return block;
    }
}
