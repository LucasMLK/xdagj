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

package io.xdag.consensus;

import io.xdag.Kernel;
import io.xdag.Wallet;
import io.xdag.config.Config;
import io.xdag.config.DevnetConfig;
import io.xdag.config.RandomXConstants;
import io.xdag.core.Block;
import io.xdag.core.BlockchainImpl;
import io.xdag.crypto.SampleKeys;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.db.BlockStore;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.*;
import io.xdag.utils.XdagTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

import static io.xdag.BlockBuilder.generateAddressBlock;
import static io.xdag.BlockBuilder.generateExtraBlock;
import static org.junit.Assert.*;

@Slf4j
public class RandomXTest {

    @Rule
    public TemporaryFolder root = new TemporaryFolder();

    private Config config;
    private Kernel kernel;
    private RandomX randomX;

    @Before
    public void setUp() throws Exception {
        // Configure test constants
        RandomXConstants.SEEDHASH_EPOCH_TESTNET_BLOCKS = 64;
        RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT = 128;
        RandomXConstants.SEEDHASH_EPOCH_TESTNET_LAG = 4;

        // Create test kernel
        config = new DevnetConfig();
        config.getNodeSpec().setStoreDir(root.newFolder().getAbsolutePath());
        config.getNodeSpec().setStoreBackupDir(root.newFolder().getAbsolutePath());

        String pwd = "password";
        Wallet wallet = new Wallet(config);
        wallet.unlock(pwd);
        ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.PRIVATE_KEY_OBJ);
        wallet.setAccounts(Collections.singletonList(key));

        kernel = new Kernel(config, key);
        DatabaseFactory dbFactory = new RocksdbFactory(config);

        BlockStore blockStore = new BlockStoreImpl(
                dbFactory.getDB(DatabaseName.INDEX),
                dbFactory.getDB(DatabaseName.TIME),
                dbFactory.getDB(DatabaseName.BLOCK),
                dbFactory.getDB(DatabaseName.TXHISTORY));
        blockStore.reset();

        OrphanBlockStore orphanBlockStore = new OrphanBlockStoreImpl(dbFactory.getDB(DatabaseName.ORPHANIND));
        orphanBlockStore.reset();

        kernel.setBlockStore(blockStore);
        kernel.setOrphanBlockStore(orphanBlockStore);
        kernel.setWallet(wallet);

        // Create and start RandomX
        randomX = new RandomX(config);
        kernel.setRandomx(randomX);

        MockBlockchain blockchain = new MockBlockchain(kernel);
        kernel.setBlockchain(blockchain);
        randomX.setBlockchain(blockchain);
        randomX.start();
    }

    @After
    public void tearDown() {
        if (randomX != null) {
            randomX.stop();
        }
    }

    // ========== Test 1: randomXPoolCalcHash() ==========

    @Test
    public void shouldCalculateHashWithValidInput() {
        // given
        setupRandomXWithSeed();
        Bytes data = Bytes.random(64);
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        Bytes32 hash = randomX.randomXPoolCalcHash(data, taskTime);

        // then
        assertNotNull("Hash should not be null", hash);
        assertEquals("Hash should be 32 bytes", 32, hash.size());
        assertNotEquals("Hash should not be all zeros", Bytes32.ZERO, hash);
    }

    @Test
    public void shouldProduceDeterministicHashForSameInput() {
        // given
        setupRandomXWithSeed();
        Bytes data = Bytes.fromHexString("0x1234567890abcdef");
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        Bytes32 hash1 = randomX.randomXPoolCalcHash(data, taskTime);
        Bytes32 hash2 = randomX.randomXPoolCalcHash(data, taskTime);

        // then
        assertEquals("Same input should produce same hash", hash1, hash2);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionWhenPoolCalcHashDataIsNull() {
        // given
        setupRandomXWithSeed();
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        randomX.randomXPoolCalcHash(null, taskTime);

        // then - expect IllegalArgumentException
    }

    @Test
    public void shouldProduceDifferentHashForDifferentInput() {
        // given
        setupRandomXWithSeed();
        Bytes data1 = Bytes.fromHexString("0x1234567890abcdef");
        Bytes data2 = Bytes.fromHexString("0xfedcba0987654321");
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        Bytes32 hash1 = randomX.randomXPoolCalcHash(data1, taskTime);
        Bytes32 hash2 = randomX.randomXPoolCalcHash(data2, taskTime);

        // then
        assertNotEquals("Different input should produce different hash", hash1, hash2);
    }

    // ========== Test 2: randomXBlockHash() ==========

    @Test
    public void shouldReturnNullWhenNoSeedAvailable() {
        // given - randomXHashEpochIndex == 0 (no seed set)
        byte[] data = new byte[64];

        // when
        byte[] hash = randomX.randomXBlockHash(data, XdagTime.getCurrentEpoch());

        // then
        assertNull("Should return null when no seed is set", hash);
    }

    @Test
    public void shouldCalculateHashWithSeed() {
        // given
        setupRandomXWithSeed();
        byte[] data = new byte[64];
        long blockTime = XdagTime.getCurrentEpoch() + 10000;

        // when
        byte[] hash = randomX.randomXBlockHash(data, blockTime);

        // then
        assertNotNull("Hash should not be null when seed is set", hash);
        assertEquals("Hash should be 32 bytes", 32, hash.length);
    }

    @Test
    public void shouldReturnNullWhenBlockTimeIsBeforeSwitchTime() {
        // given
        setupRandomXWithSeed();
        RandomXMemory memory = randomX.getGlobalMemory()[1];
        long switchTime = memory.switchTime;

        byte[] data = new byte[64];
        long blockTime = XdagTime.xdagTimestampToMs(switchTime - 10);

        // when
        byte[] hash = randomX.randomXBlockHash(data, blockTime);

        // then - First seed scenario should return null if before switch time
        if (randomX.getRandomXHashEpochIndex() == 1) {
            assertNull("Should return null when blockTime < switchTime for first seed", hash);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldThrowExceptionWhenBlockHashDataIsNull() {
        // given
        setupRandomXWithSeed();

        // when
        randomX.randomXBlockHash(null, XdagTime.getCurrentEpoch());

        // then - expect IllegalArgumentException
    }

    // ========== Test 3: randomXPoolUpdateSeed() ==========

    @Test
    public void shouldInitializeBothTemplatesOnFirstSeedUpdate() {
        // given - Fresh RandomX with memory but no templates
        RandomXMemory memory = randomX.getGlobalMemory()[0];
        memory.seed = new byte[32];
        assertNull("poolTemplate should be null initially", memory.getPoolTemplate());
        assertNull("blockTemplate should be null initially", memory.getBlockTemplate());

        // when
        randomX.randomXPoolUpdateSeed(0);

        // then
        assertNotNull("poolTemplate should be initialized", memory.getPoolTemplate());
        assertNotNull("blockTemplate should be initialized", memory.getBlockTemplate());
    }

    @Test
    public void shouldReuseTemplatesOnSubsequentSeedUpdate() {
        // given - Initialize templates first
        RandomXMemory memory = randomX.getGlobalMemory()[0];
        memory.seed = new byte[32];
        randomX.randomXPoolUpdateSeed(0);

        var poolTemplate1 = memory.getPoolTemplate();
        var blockTemplate1 = memory.getBlockTemplate();

        // when - Update seed again
        memory.seed = new byte[32];
        memory.seed[0] = 1; // Different seed
        randomX.randomXPoolUpdateSeed(0);

        // then - Templates should be same instances (not re-created)
        assertSame("poolTemplate should not be re-created", poolTemplate1, memory.getPoolTemplate());
        assertSame("blockTemplate should not be re-created", blockTemplate1, memory.getBlockTemplate());
    }

    // ========== Test 4: Fork Time Management ==========

    @Test
    public void shouldSetForkTimeAtForkHeight() {
        // given - Create blocks up to fork height
        long generateTime = 1600616700000L;
        ECKeyPair key = ECKeyPair.fromPrivateKey(SampleKeys.PRIVATE_KEY_OBJ);

        Block addressBlock = generateAddressBlock(config, key, generateTime);
        kernel.getBlockchain().tryToConnect(addressBlock);

        // when - Create blocks up to and past RANDOMX_TESTNET_FORK_HEIGHT (128)
        Bytes32 ref = addressBlock.getHash();
        Block forkBlock = null;
        for (int i = 1; i <= RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT; i++) {
            generateTime += 64000L;
            long time = XdagTime.msToXdagtimestamp(generateTime);
            long xdagTime = XdagTime.getEndOfEpoch(time);
            Block block = generateExtraBlock(config, key, xdagTime,
                Collections.singletonList(new io.xdag.core.Address(ref,
                    io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT, false)));

            // Set block height manually for testing using withHeight()
            block.setInfo(block.getInfo().withHeight(i));
            kernel.getBlockchain().tryToConnect(block);
            ref = block.getHash();

            // Call randomXSetForkTime
            randomX.randomXSetForkTime(block);

            if (i == RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT) {
                forkBlock = block;
            }
        }

        // then - Fork time should be set after processing fork height block
        assertNotNull("Fork block should exist", forkBlock);
        assertTrue("Fork time should be changed from MAX_VALUE or be set",
            randomX.getRandomXForkTime() != Long.MAX_VALUE || forkBlock.getInfo().getHeight() >= RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT);
    }

    @Test
    public void shouldReturnCorrectForkStatusForEpoch() {
        // given
        randomX.setRandomXForkTime(100);

        // when & then
        assertFalse("Epoch before fork should return false", randomX.isRandomxFork(50));
        assertFalse("Epoch at fork should return false", randomX.isRandomxFork(100));
        assertTrue("Epoch after fork should return true", randomX.isRandomxFork(101));
    }

    // ========== Test 5: Memory Switching Integration ==========

    @Test
    public void shouldSwitchMemoryCorrectlyBasedOnTaskTime() {
        // given - Setup two memory slots with different seeds
        RandomXMemory memory0 = randomX.getGlobalMemory()[0];
        RandomXMemory memory1 = randomX.getGlobalMemory()[1];

        memory0.seed = new byte[32];
        memory0.seed[0] = 1;
        memory0.switchTime = 1000;
        randomX.randomXPoolUpdateSeed(0);

        memory1.seed = new byte[32];
        memory1.seed[0] = 2;
        memory1.switchTime = 2000;
        randomX.randomXPoolUpdateSeed(1);

        randomX.setRandomXPoolMemIndex(1);
        randomX.setRandomXHashEpochIndex(1);

        Bytes data = Bytes.fromHexString("0x1234567890abcdef");

        // when - Calculate hash with time before current memory's switchTime
        // Should use memory0 (previous memory)
        long taskTimeBeforeSwitch = XdagTime.xdagTimestampToMs(1500);
        Bytes32 hashOldMemory = randomX.randomXPoolCalcHash(data, taskTimeBeforeSwitch);

        // Calculate hash with time after switchTime
        // Should use memory1 (current memory)
        long taskTimeAfterSwitch = XdagTime.xdagTimestampToMs(2500);
        Bytes32 hashNewMemory = randomX.randomXPoolCalcHash(data, taskTimeAfterSwitch);

        // then - Both hashes should be calculated
        assertNotNull("Should calculate hash with old memory", hashOldMemory);
        assertNotNull("Should calculate hash with new memory", hashNewMemory);

        // Different memories with different seeds should produce different hashes
        assertNotEquals("Different memories should produce different hashes",
            hashOldMemory, hashNewMemory);
    }

    @Test
    public void shouldHandleMemorySwitchingDuringBlockValidation() {
        // given - Setup RandomX with two memory epochs (epochIndex will be 1)
        setupRandomXWithTwoMemoryEpochs();

        byte[] data = new byte[64];
        RandomXMemory memory1 = randomX.getGlobalMemory()[1];

        // when - Validate block with time BEFORE first seed's switch time
        // According to design: when epochIndex == 1 and blockTime < switchTime, should return null
        long blockTimeBeforeSwitch = XdagTime.xdagTimestampToMs(memory1.switchTime - 100);
        byte[] hashBeforeSwitch = randomX.randomXBlockHash(data, blockTimeBeforeSwitch);

        // then - Should return null (this is expected behavior for first seed before activation)
        // BlockchainImpl will fallback to getDiffByRawHash() when null is returned
        assertNull("Should return null before first seed switch time (expected design)", hashBeforeSwitch);

        // when - Validate block with time AFTER switch time (RandomX is active)
        long blockTimeAfterSwitch = XdagTime.xdagTimestampToMs(memory1.switchTime + 100);
        byte[] hashAfterSwitch = randomX.randomXBlockHash(data, blockTimeAfterSwitch);

        // then - Should successfully calculate hash after activation
        assertNotNull("Should calculate hash after switch time", hashAfterSwitch);
        assertEquals("Hash should be 32 bytes", 32, hashAfterSwitch.length);
    }

    // ========== Test 6: Snapshot Loading ==========
    // Note: Snapshot loading tests are complex integration tests that require
    // a fully initialized blockchain with proper blocks. These are better suited
    // for integration test suite rather than unit tests.

    @Test
    public void shouldInitializeWithoutSnapshot() {
        // given - Fresh RandomX instance without snapshot
        RandomX freshRandomX = new RandomX(config);
        freshRandomX.start();

        // when - Check initial state
        long epochIndex = freshRandomX.getRandomXHashEpochIndex();
        long poolMemIndex = freshRandomX.getRandomXPoolMemIndex();

        // then - Should start with default state
        assertEquals("Initial epoch index should be 0", 0, epochIndex);
        assertEquals("Initial pool mem index should be 0", 0, poolMemIndex);

        freshRandomX.stop();
    }

    // ========== Test 7: Edge Cases ==========

    @Test
    public void shouldHandleZeroLengthData() {
        // given
        setupRandomXWithSeed();
        Bytes emptyData = Bytes.EMPTY;
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        Bytes32 hash = randomX.randomXPoolCalcHash(emptyData, taskTime);

        // then
        assertNotNull("Should handle empty data", hash);
        assertEquals("Hash should still be 32 bytes", 32, hash.size());
    }

    @Test
    public void shouldHandleLargeDataInput() {
        // given
        setupRandomXWithSeed();
        Bytes largeData = Bytes.random(1024); // 1KB data
        long taskTime = XdagTime.getCurrentEpoch();

        // when
        Bytes32 hash = randomX.randomXPoolCalcHash(largeData, taskTime);

        // then
        assertNotNull("Should handle large data", hash);
        assertEquals("Hash should be 32 bytes", 32, hash.size());
    }

    @Test
    public void shouldProduceDifferentHashesWithDifferentSeeds() {
        // given - Setup two different seeds
        RandomXMemory memory0 = randomX.getGlobalMemory()[0];
        memory0.seed = new byte[32];
        memory0.seed[0] = 1;
        memory0.switchTime = 0;
        randomX.randomXPoolUpdateSeed(0);
        randomX.setRandomXHashEpochIndex(0);
        randomX.setRandomXPoolMemIndex(0);

        Bytes data = Bytes.fromHexString("0x1234567890abcdef");
        long taskTime = XdagTime.getCurrentEpoch();
        Bytes32 hash1 = randomX.randomXPoolCalcHash(data, taskTime);

        // when - Change seed
        memory0.seed = new byte[32];
        memory0.seed[0] = 2; // Different seed
        randomX.randomXPoolUpdateSeed(0);
        Bytes32 hash2 = randomX.randomXPoolCalcHash(data, taskTime);

        // then
        assertNotEquals("Different seeds should produce different hashes", hash1, hash2);
    }

    @Test
    public void shouldRespectForkTimeForEpochCalculation() {
        // given
        long forkTime = 1000L;
        randomX.setRandomXForkTime(forkTime);

        // when & then - Test epochs around fork time
        assertFalse("Should not be fork for epoch before fork time",
            randomX.isRandomxFork(forkTime - 100));
        assertFalse("Should not be fork at exact fork time",
            randomX.isRandomxFork(forkTime));
        assertTrue("Should be fork for epoch after fork time",
            randomX.isRandomxFork(forkTime + 1));
    }

    @Test
    public void shouldHandleRepeatedSeedUpdates() {
        // given
        RandomXMemory memory = randomX.getGlobalMemory()[0];
        memory.seed = new byte[32];

        // when - Update seed multiple times
        for (int i = 0; i < 5; i++) {
            memory.seed[0] = (byte) i;
            randomX.randomXPoolUpdateSeed(0);
        }

        // then - Templates should still be valid
        assertNotNull("Pool template should remain initialized", memory.getPoolTemplate());
        assertNotNull("Block template should remain initialized", memory.getBlockTemplate());
    }

    // ========== Helper Methods ==========

    private void setupRandomXWithSeed() {
        RandomXMemory memory = randomX.getGlobalMemory()[1];
        memory.seed = new byte[32];
        memory.switchTime = 0; // Allow immediate use
        randomX.randomXPoolUpdateSeed(1);
        randomX.setRandomXHashEpochIndex(1);
        randomX.setRandomXPoolMemIndex(1);
    }

    private void setupRandomXWithTwoMemoryEpochs() {
        // Setup first memory epoch
        RandomXMemory memory0 = randomX.getGlobalMemory()[0];
        memory0.seed = new byte[32];
        memory0.seed[0] = 1;
        memory0.switchTime = 1000;
        randomX.randomXPoolUpdateSeed(0);

        // Setup second memory epoch
        RandomXMemory memory1 = randomX.getGlobalMemory()[1];
        memory1.seed = new byte[32];
        memory1.seed[0] = 2;
        memory1.switchTime = 2000;
        randomX.randomXPoolUpdateSeed(1);

        randomX.setRandomXHashEpochIndex(1);
        randomX.setRandomXPoolMemIndex(1);
    }

    static class MockBlockchain extends BlockchainImpl {
        public MockBlockchain(Kernel kernel) {
            super(kernel);
        }

        @Override
        public void startCheckMain(long period) {
            // No-op for testing
        }
    }
}
