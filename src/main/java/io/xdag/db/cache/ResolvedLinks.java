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

package io.xdag.db.cache;

import io.xdag.core.Block;
import io.xdag.core.Transaction;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import org.apache.tuweni.bytes.Bytes32;

/**
 * ResolvedLinks - Result of link resolution
 *
 * <p>Contains all resolved Block and Transaction entities from a Block's links,
 * along with any missing references.
 *
 * <p>Used by {@link DagEntityResolver} to provide complete link information
 * for Block validation and processing.
 *
 * @since XDAGJ
 * @see DagEntityResolver
 */
@Data
@Builder
public class ResolvedLinks {

    /** All referenced blocks that were successfully resolved */
    @Builder.Default
    private final List<Block> referencedBlocks = new ArrayList<>();

    /** All referenced transactions that were successfully resolved */
    @Builder.Default
    private final List<Transaction> referencedTransactions = new ArrayList<>();

    /** Hashes of links that could not be resolved (missing dependencies) */
    @Builder.Default
    private final List<Bytes32> missingReferences = new ArrayList<>();

    /**
     * Check if all links were successfully resolved
     *
     * @return true if no missing references
     */
    public boolean hasAllReferences() {
        return missingReferences.isEmpty();
    }

  /**
     * Get total number of missing references
     *
     * @return Count of missing links
     */
    public int getMissingCount() {
        return missingReferences.size();
    }

}
