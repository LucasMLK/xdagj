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
package io.xdag.api.http.response;

import io.xdag.api.http.pagination.PaginationInfo;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response for epoch blocks query with pagination
 */
@Data
@Builder
public class EpochBlocksResponse {
    /**
     * The queried epoch number
     */
    private long epoch;

    /**
     * Total number of blocks in this epoch (across all pages)
     */
    private long blockCount;

    /**
     * Pagination metadata
     */
    private PaginationInfo pagination;

    /**
     * Blocks in the current page
     */
    private List<BlockSummaryResponse> blocks;

    public static EpochBlocksResponse of(long epoch, List<BlockSummaryResponse> blocks,
                                          PaginationInfo pagination, long total) {
        return EpochBlocksResponse.builder()
                .epoch(epoch)
                .blockCount(total)
                .pagination(pagination)
                .blocks(blocks)
                .build();
    }
}
