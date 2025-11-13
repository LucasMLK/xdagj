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

package io.xdag.db.rocksdb.impl;

import com.google.common.collect.Lists;
import io.xdag.db.OrphanBlockStore;
import io.xdag.db.rocksdb.base.KVSource;
import io.xdag.utils.BytesUtils;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.tuweni.bytes.Bytes32;
import org.bouncycastle.util.encoders.Hex;

/**
 * OrphanBlockStore implementation
 *
 * Stores orphan blocks with their timestamps.
 * Returns Bytes32 hashes instead of Address objects.
 */
@Slf4j
public class OrphanBlockStoreImpl implements OrphanBlockStore {


    // <hash,nexthash>
    private final KVSource<byte[], byte[]> orphanSource;

    public OrphanBlockStoreImpl(KVSource<byte[], byte[]> orphan) {
        this.orphanSource = orphan;
    }

    public void start() {
        this.orphanSource.init();
        if (orphanSource.get(ORPHAN_SIZE) == null) {
            this.orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(0, false));
        }
    }

    @Override
    public void stop() {
        orphanSource.close();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    public void reset() {
        this.orphanSource.reset();
        this.orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(0, false));
    }

    /**
     * Get orphan block hashes
     *
     * Returns list of Bytes32 hashes instead of Address objects.
     * Filters by timestamp and returns up to 'num' orphans.
     */
    public List<Bytes32> getOrphan(long num, long[] sendtime) {
        List<Bytes32> res = Lists.newArrayList();
        if (orphanSource.get(ORPHAN_SIZE) == null || getOrphanSize() == 0) {
            return null;
        } else {
            long orphanSize = getOrphanSize();
            long addNum = Math.min(orphanSize, num);
            byte[] key = BytesUtils.of(ORPHAN_PREFEX);
            List<Pair<byte[],byte[]>> ans = orphanSource.prefixKeyAndValueLookup(key);
            ans.sort(Comparator.comparingLong(a -> BytesUtils.bytesToLong(a.getValue(), 0, true)));
            for (Pair<byte[],byte[]> an : ans) {
                if (addNum == 0) {
                    break;
                }
                // Null check added to handle missing values
                if (an.getValue() == null) {
                    continue;
                }
                long time =  BytesUtils.bytesToLong(an.getValue(), 0, true);
                if (time <= sendtime[0]) {
                    addNum--;
                    //  Return Bytes32 hash directly (skip prefix byte)
                    res.add(Bytes32.wrap(an.getKey(), 1));
                    sendtime[1] = Math.max(sendtime[1],time);
                }
            }
            sendtime[1] = Math.min(sendtime[1]+1,sendtime[0]);
            return res;
        }
    }

    public void deleteByHash(byte[] hash) {
        log.debug("deleteByHash");
        orphanSource.delete(BytesUtils.merge(ORPHAN_PREFEX, hash));
        long currentsize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
        orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(currentsize - 1, false));
    }

    /**
     * Add orphan block to queue
     *
     * @param hash orphan block hash (Bytes32)
     * @param timestamp block timestamp
     */
    @Override
    public void addOrphan(Bytes32 hash, long timestamp) {
        orphanSource.put(BytesUtils.merge(ORPHAN_PREFEX, hash.toArray()),
                BytesUtils.longToBytes(timestamp, true));
        long currentsize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
        orphanSource.put(ORPHAN_SIZE, BytesUtils.longToBytes(currentsize + 1, false));
        log.debug("Added orphan block {}: timestamp={}, queue size={}",
                hash.toHexString().substring(0, 16), timestamp, currentsize + 1);
    }

    public long getOrphanSize() {
        long currentsize = BytesUtils.bytesToLong(orphanSource.get(ORPHAN_SIZE), 0, false);
        log.debug("current orphan size:{}", currentsize);
        log.debug("Hex:{}", Hex.toHexString(orphanSource.get(ORPHAN_SIZE)));
        return currentsize;
    }

}
