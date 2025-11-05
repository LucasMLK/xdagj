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

import static io.xdag.config.RandomXConstants.RANDOMX_FORK_HEIGHT;
import static io.xdag.config.RandomXConstants.RANDOMX_TESTNET_FORK_HEIGHT;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_BLOCKS;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_LAG;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_TESTNET_BLOCKS;
import static io.xdag.config.RandomXConstants.SEEDHASH_EPOCH_TESTNET_LAG;
import static io.xdag.config.RandomXConstants.XDAG_RANDOMX;
import static io.xdag.utils.BytesUtils.equalBytes;

import io.xdag.config.Config;
import io.xdag.config.MainnetConfig;
import io.xdag.core.AbstractXdagLifecycle;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.crypto.randomx.RandomXCache;
import io.xdag.crypto.randomx.RandomXFlag;
import io.xdag.crypto.randomx.RandomXTemplate;
import io.xdag.crypto.randomx.RandomXUtils;
import io.xdag.utils.XdagTime;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;


@Slf4j
@Getter
@Setter
public class RandomX extends AbstractXdagLifecycle {
    protected final RandomXMemory[] globalMemory = new RandomXMemory[2];
    protected final Config config;
    protected boolean isTestNet = true;
    protected int mineType;
    protected Set<RandomXFlag> flagSet;
    protected long randomXForkSeedHeight;
    protected long randomXForkLag;
    // Default to maximum value
    protected long randomXForkTime = Long.MAX_VALUE;
    protected long randomXPoolMemIndex;
    protected long randomXHashEpochIndex;
    protected Blockchain blockchain;
    protected boolean isFullMem;
    protected boolean isLargePages;

    public RandomX(Config config) {
        this.config = config;
        if (config instanceof MainnetConfig) {
            isTestNet = false;
        }
        this.mineType = XDAG_RANDOMX;

        flagSet = RandomXUtils.getRecommendedFlags();
        if (config.getRandomxSpec().getRandomxFlag()) {
            flagSet.add(RandomXFlag.LARGE_PAGES);
            flagSet.add(RandomXFlag.FULL_MEM);
        }
    }

    // Public method to check if it's a RandomX fork
    public boolean isRandomxFork(long epoch) {
        return mineType == XDAG_RANDOMX && epoch > randomXForkTime;
    }

    // Public method to set the fork time
    // Phase 8.3.2: Updated to accept Block instead of Block
    public void randomXSetForkTime(Block block) {
        long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        seedEpoch -= 1;
        if (block.getInfo().getHeight() >= randomXForkSeedHeight) {
            long nextMemIndex = randomXHashEpochIndex + 1;
            RandomXMemory nextMemory = globalMemory[(int) (nextMemIndex) & 1];
            if (block.getInfo().getHeight() == randomXForkSeedHeight) {
                randomXForkTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag;
                log.debug("From block height:{}, time:{}, set fork time to:{}", block.getInfo().getHeight(),
                        block.getTimestamp(), randomXForkTime);
            }

            byte[] hash;
            if ((block.getInfo().getHeight() & seedEpoch) == 0) {
                nextMemory.switchTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag + 1;
                nextMemory.seedTime = block.getTimestamp();
                nextMemory.seedHeight = block.getInfo().getHeight();
                log.debug("Set switch time to {}", Long.toHexString(nextMemory.switchTime));

                hash = blockchain.getBlockByHeight(block.getInfo().getHeight() - randomXForkLag).getInfo()
                        .getHash().toArray();
                if (nextMemory.seed == null || !equalBytes(nextMemory.seed, hash)) {
                    nextMemory.seed = Arrays.reverse(hash);
                    log.debug("Next Memory Seed:{}", Hex.toHexString(hash));
                    randomXPoolUpdateSeed(nextMemIndex);
                }
                randomXHashEpochIndex = nextMemIndex;
                nextMemory.isSwitched = 0;
            }
        }
    }

    // Public method to unset the fork time
    // Phase 8.3.2: Updated to accept Block instead of Block
    public void randomXUnsetForkTime(Block block) {
        long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        seedEpoch -= 1;
        if (block.getInfo().getHeight() >= randomXForkSeedHeight) {
            if (block.getInfo().getHeight() == randomXForkSeedHeight) {
                randomXForkTime = -1;
            }
            if ((block.getInfo().getHeight() & seedEpoch) == 0) {
                RandomXMemory memory = globalMemory[(int) (randomXHashEpochIndex & 1)];
                randomXHashEpochIndex -= 1;
                memory.seedTime = -1;
                memory.seedHeight = -1;
                memory.switchTime = -1;
                memory.isSwitched = -1;
            }
        }
    }


    // Used during system initialization
    // Wallet type is fast, pool is light
    @Override
    protected void doStart() {
        if (isTestNet) {
            randomXForkSeedHeight = RANDOMX_TESTNET_FORK_HEIGHT;
            randomXForkLag = SEEDHASH_EPOCH_TESTNET_LAG;
        } else {
            randomXForkSeedHeight = RANDOMX_FORK_HEIGHT;
            randomXForkLag = SEEDHASH_EPOCH_LAG;
        }

        long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        if ((randomXForkSeedHeight & (seedEpoch - 1)) != 0) {
            log.error("RandomX fork height {} is not aligned with seed epoch {}, RandomX initialization aborted",
                    randomXForkSeedHeight, seedEpoch);
            throw new IllegalStateException(
                    String.format("RandomX fork height %d must be aligned with seed epoch %d",
                            randomXForkSeedHeight, seedEpoch));
        }

        // Initialize memory and lock
        for (int i = 0; i < 2; i++) {
            globalMemory[i] = new RandomXMemory();
        }
    }

    @Override
    protected void doStop() {
    }


    public Bytes32 randomXPoolCalcHash(Bytes data, long taskTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        Bytes32 hash;
        RandomXMemory memory = globalMemory[(int) (randomXPoolMemIndex) & 1];

        if (taskTime < memory.switchTime) {
            memory = globalMemory[(int) (randomXPoolMemIndex - 1) & 1];
        }

        byte[] bytes = memory.poolTemplate.calculateHash(data.toArray());
        hash = Bytes32.wrap(bytes);

        return hash;
    }

    public byte[] randomXBlockHash(byte[] data, long blockTime) {
        if (data == null) {
            throw new IllegalArgumentException("Input data cannot be null");
        }

        byte[] hash;
        RandomXMemory memory;
        // If there is no seed
        if (randomXHashEpochIndex == 0) {
            log.debug("RandomX hash index is 0, no seed available");
            return null;
        } else if (randomXHashEpochIndex == 1) { // first seed
            memory = globalMemory[(int) (randomXHashEpochIndex) & 1];
            if (blockTime < memory.switchTime) {
                // Block time is less than switch time
                log.debug("Block time {} less than switch time {}", Long.toHexString(blockTime),
                        Long.toHexString(memory.switchTime));
                return null;
            }
        } else {
            memory = globalMemory[(int) (randomXHashEpochIndex) & 1];
            if (blockTime < memory.switchTime) {
                memory = globalMemory[(int) (randomXHashEpochIndex - 1) & 1];
            }
        }

        log.debug("Use seed {}", Hex.toHexString(Arrays.reverse(memory.seed)));
        hash = memory.blockTemplate.calculateHash(data);

        return hash;
    }

    public void randomXPoolUpdateSeed(long memIndex) {
        RandomXMemory rx_memory = globalMemory[(int) (memIndex) & 1];
        // Note: changeKey() updates the RandomX VM with new seed without full re-initialization
        // This is more efficient than creating new templates
        if (rx_memory.getPoolTemplate() == null) {
            RandomXCache cache = new RandomXCache(flagSet);
            cache.init(rx_memory.seed);
            RandomXTemplate template = RandomXTemplate.builder()
                    .cache(cache)
                    .miningMode(config.getRandomxSpec().getRandomxFlag())
                    .flags(flagSet)
                    .build();
            template.init();
            rx_memory.setPoolTemplate(template);
            rx_memory.getPoolTemplate().changeKey(rx_memory.seed);
        } else {
            rx_memory.getPoolTemplate().changeKey(rx_memory.seed);
        }

        if (rx_memory.getBlockTemplate() == null) {
            RandomXCache cache = new RandomXCache(flagSet);
            cache.init(rx_memory.seed);
            RandomXTemplate template = RandomXTemplate.builder()
                    .cache(cache)
                    .miningMode(config.getRandomxSpec().getRandomxFlag())
                    .flags(flagSet)
                    .build();
            template.init();
            rx_memory.setBlockTemplate(template);
            rx_memory.getBlockTemplate().changeKey(rx_memory.seed);
        } else {
            rx_memory.getBlockTemplate().changeKey(rx_memory.seed);
        }
    }

    public void randomXLoadingSnapshot(byte[] preseed) {
        // Load RandomX state from snapshot with preseed
        long firstMemIndex = randomXHashEpochIndex + 1;
        randomXPoolMemIndex = -1;
        RandomXMemory firstMemory = globalMemory[(int) (firstMemIndex) & 1];
        firstMemory.seed = preseed;
        randomXPoolUpdateSeed(firstMemIndex);
        randomXHashEpochIndex = firstMemIndex;
        firstMemory.isSwitched = 0;

        long lag = isTestNet ? SEEDHASH_EPOCH_TESTNET_LAG : SEEDHASH_EPOCH_LAG;
        // Phase 8.3.2: Blockchain interface now returns Block
        randomXForkTime = XdagTime
                .getEpoch(
                        blockchain.getBlockByHeight(config.getSnapshotSpec().getSnapshotHeight() - lag).getTimestamp());
        Block block;
        for (long i = lag; i >= 0; i--) {
            block = blockchain.getBlockByHeight(config.getSnapshotSpec().getSnapshotHeight() - i);
            if (block == null) {
                continue;
            }
            randomXSetForkTime(block);
        }
    }

    public void randomXLoadingForkTimeSnapshot(byte[] preseed) {
        // If the snapshot is being restarted before the next seed change cycle, use the initial preseed
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        if (blockchain.getChainStats().getMainBlockCount() < config.getSnapshotSpec().getSnapshotHeight() + (isTestNet
                ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS)) {
            randomXLoadingSnapshot(preseed);
        } else {
            this.randomXLoadingSnapshot();
        }
    }

    public void randomXLoadingSnapshot() {
        // Phase 8.3.2: Blockchain interface now returns Block
        Block block;
        long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        if (blockchain.getChainStats().getMainBlockCount() >= config.getSnapshotSpec().getSnapshotHeight()) {
            block = blockchain.getBlockByHeight(
                    config.getSnapshotSpec().getSnapshotHeight());
            randomXForkTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag;

            seedEpoch -= 1;
          doRandomXSeed(seedEpoch);
        }
    }

    public void randomXLoadingSnapshotJ() {
        // Phase 8.3.2: Blockchain interface now returns Block
        Block block;
        long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        if (blockchain.getChainStats().getMainBlockCount() >= config.getSnapshotSpec().getSnapshotHeight()) {
            if (config.getSnapshotSpec().getSnapshotHeight() > RANDOMX_FORK_HEIGHT) {
                block = blockchain.getBlockByHeight(
                        config.getSnapshotSpec().getSnapshotHeight() - config.getSnapshotSpec().getSnapshotHeight() % seedEpoch);
                randomXForkTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag;
            }
            seedEpoch -= 1;
          doRandomXSeed(seedEpoch);
        }
    }

    public void randomXLoadingForkTime() {
        // Phase 8.3.2: Blockchain interface now returns Block
        Block block;
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        if (blockchain.getChainStats().getMainBlockCount() >= randomXForkSeedHeight) {
            block = blockchain.getBlockByHeight(randomXForkSeedHeight);
            randomXForkTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag;

            long seedEpoch = isTestNet ? SEEDHASH_EPOCH_TESTNET_BLOCKS : SEEDHASH_EPOCH_BLOCKS;
            seedEpoch -= 1;
          doRandomXSeed(seedEpoch);
        }
    }

  private void doRandomXSeed(long seedEpoch) {
    // Phase 8.3.2: Blockchain interface now returns Block
    Block block;
    // Phase 7.3: Use ChainStats directly (XdagStats deleted)
    long seedHeight = blockchain.getChainStats().getMainBlockCount() & ~seedEpoch;
    long preSeedHeight = seedHeight - seedEpoch - 1;

    if (preSeedHeight >= randomXForkSeedHeight) {
        randomXHashEpochIndex = 0;
        randomXPoolMemIndex = -1;

        block = blockchain.getBlockByHeight(preSeedHeight);
        long memoryIndex = randomXHashEpochIndex + 1;
        RandomXMemory memory = globalMemory[(int) (memoryIndex) & 1];
        memory.seed = Arrays
                .reverse(blockchain.getBlockByHeight(preSeedHeight - randomXForkLag).getInfo().getHash().toArray());
        memory.switchTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag + 1;
        memory.seedTime = block.getTimestamp();
        memory.seedHeight = block.getInfo().getHeight();

        randomXPoolUpdateSeed(memoryIndex);
        randomXHashEpochIndex = memoryIndex;
        memory.isSwitched = 1;
    }

    if (seedHeight >= randomXForkSeedHeight) {
        block = blockchain.getBlockByHeight(seedHeight);
        long memoryIndex = randomXHashEpochIndex + 1;
        RandomXMemory memory = globalMemory[(int) (memoryIndex) & 1];
        memory.seed = Arrays
                .reverse(blockchain.getBlockByHeight(seedHeight - randomXForkLag).getInfo().getHash().toArray());
        memory.switchTime = XdagTime.getEpoch(block.getTimestamp()) + randomXForkLag + 1;
        memory.seedTime = block.getTimestamp();
        memory.seedHeight = block.getInfo().getHeight();

        randomXPoolUpdateSeed(memoryIndex);
        randomXHashEpochIndex = memoryIndex;
        // Phase 7.3: Use ChainStats directly (XdagStats deleted)
        if (XdagTime.getEpoch(blockchain.getBlockByHeight(blockchain.getChainStats().getMainBlockCount()).getTimestamp())
                >= memory.getSwitchTime()) {
            memory.isSwitched = 1;
        } else {
            memory.isSwitched = 0;
        }
    }
  }
}
