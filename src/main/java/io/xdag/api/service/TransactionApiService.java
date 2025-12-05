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

package io.xdag.api.service;

import static io.xdag.utils.BasicUtils.address2PubAddress;

import io.xdag.DagKernel;
import io.xdag.api.service.dto.PagedResult;
import io.xdag.api.service.dto.TransactionInfo;
import io.xdag.core.Block;
import io.xdag.core.BlockInfo;
import io.xdag.core.Transaction;
import io.xdag.utils.TimeUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

/**
 * Transaction API Service Provides transaction-related data access for both CLI and RPC
 */
@Slf4j
public class TransactionApiService {

  private final DagKernel dagKernel;

  public TransactionApiService(DagKernel dagKernel) {
    this.dagKernel = dagKernel;
  }

  /**
   * Get transaction by hash
   *
   * @param txHash Transaction hash
   * @return Transaction information or null if not found
   */
  public TransactionInfo getTransaction(Bytes32 txHash) {
    try {
      Transaction tx = dagKernel.getTransactionStore().getTransaction(txHash);
      if (tx == null) {
        return null;
      }

      return buildTransactionInfo(tx);

    } catch (Exception e) {
      log.error("Failed to get transaction: {}", txHash.toHexString(), e);
      return null;
    }
  }

  /**
   * Get transactions by address
   *
   * @param address Account address bytes (20 bytes)
   * @return List of transaction information
   */
  public List<TransactionInfo> getTransactionsByAddress(Bytes address) {
    try {
      List<Transaction> transactions = dagKernel.getTransactionStore()
          .getTransactionsByAddress(address);
      List<TransactionInfo> result = new ArrayList<>();

      for (Transaction tx : transactions) {
        result.add(buildTransactionInfo(tx));
      }

      return result;

    } catch (Exception e) {
      log.error("Failed to get transactions by address", e);
      return new ArrayList<>();
    }
  }

  /**
   * Get transactions by block hash
   *
   * @param blockHash Block hash
   * @return List of transaction information
   */
  public List<TransactionInfo> getTransactionsByBlock(Bytes32 blockHash) {
    try {
      List<Transaction> transactions = dagKernel.getTransactionStore()
          .getTransactionsByBlock(blockHash);
      List<TransactionInfo> result = new ArrayList<>();

      for (Transaction tx : transactions) {
        result.add(buildTransactionInfo(tx));
      }

      return result;

    } catch (Exception e) {
      log.error("Failed to get transactions by block: {}", blockHash.toHexString(), e);
      return new ArrayList<>();
    }
  }

  /**
   * Get recent transactions ordered by block height (newest first).
   *
   * <p><b>IMPORTANT</b>: The {@code total} count returned by this method represents the total number of
   * transactions in the system (including transactions in orphan blocks), NOT just main chain transactions.
   * This is a known limitation because calculating the exact number of main chain transactions would
   * require traversing all blocks, which is too expensive for pagination.
   *
   * <p><b>Consequence</b>: The last few pages may be empty if many transactions are in orphan blocks.
   * For example, if total=10000 but only 8000 are in main chain, pages 81-100 will be empty.
   *
   * <p><b>TODO (DEBT-005)</b>: Add a dedicated main chain transaction counter to fix this issue.
   *
   * @param page page number (1-based)
   * @param size page size
   * @return paged transaction info list
   */
  public PagedResult<TransactionInfo> getRecentTransactionsPage(int page, int size) {
    // BUGFIX (BUG-026): Document limitation - total includes all transactions, not just main chain
    // This means the last few pages may be empty if many transactions are in orphan blocks
    long total = dagKernel.getTransactionStore().getTransactionCount();
    if (total <= 0) {
      return PagedResult.empty();
    }

    long safePage = Math.max(page, 1);
    int safeSize = Math.max(size, 1);
    long offset = (safePage - 1L) * safeSize;
    if (offset >= total) {
      return PagedResult.of(Collections.emptyList(), total);
    }

    List<TransactionInfo> items = new ArrayList<>(safeSize);
    long skip = offset;
    long currentHeight = dagKernel.getDagChain().getMainChainLength();

    outer:
    for (long height = currentHeight; height >= 1; height--) {
      Block block = dagKernel.getDagChain().getMainBlockByHeight(height);
      if (block == null) {
        continue;
      }

      List<TransactionInfo> blockTransactions = getTransactionsByBlock(block.getHash());
      int blockCount = blockTransactions.size();
      if (blockCount == 0) {
        continue;
      }

      if (skip >= blockCount) {
        skip -= blockCount;
        continue;
      }

      int startIndex = blockCount - 1 - (int) skip;
      skip = 0;
      for (int idx = startIndex; idx >= 0; idx--) {
        items.add(blockTransactions.get(idx));
        if (items.size() >= safeSize) {
          break outer;
        }
      }
    }

    return PagedResult.of(items, total);
  }

  /**
   * Build TransactionInfo from Transaction object
   */
  private TransactionInfo buildTransactionInfo(Transaction tx) {
    TransactionInfo.TransactionInfoBuilder builder = TransactionInfo.builder()
        .hash(tx.getHash().toHexString())
        .from(address2PubAddress(tx.getFrom()))
        .to(address2PubAddress(tx.getTo()))
        .amount(tx.getAmount())
        .fee(tx.getFee())
        .nonce(tx.getNonce())
        .isValid(tx.isValid())
        .signatureValid(tx.verifySignature());

    // Decode remark if present
    // BUGFIX (BUG-027): Safe UTF-8 decoding with validation
    // Previously: new String(bytes, UTF_8) could produce � replacement characters for invalid sequences
    // Now: Use String constructor which replaces malformed input with \uFFFD safely
    if (tx.getData() != null && tx.getData().size() > 0) {
      try {
        byte[] data = tx.getData().toArray();
        // Validate printable characters only (filter control characters except newline/tab)
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
          char c = (char) (b & 0xFF);
          // Allow printable ASCII (32-126), newline (10), tab (9), and extended UTF-8
          if (c >= 32 || c == '\n' || c == '\t' || (b & 0x80) != 0) {
            sb.append(c);
          } else {
            sb.append('?'); // Replace control characters with '?'
          }
        }
        String remark = sb.toString();
        if (!remark.isEmpty()) {
          builder.remark(remark);
        }
      } catch (Exception e) {
        log.warn("Failed to decode transaction data as UTF-8: {}", tx.getHash().toHexString());
        // Skip remark if decoding fails
      }
    }

    // Add signature (v, r, s combined - full values)
    if (tx.getR() != null && tx.getS() != null) {
      String sig = String.format("v:%02x r:%s s:%s",
          tx.getV(),
          tx.getR().toHexString(),
          tx.getS().toHexString());
      builder.signature(sig);
    }

    // Get block information
    Bytes32 blockHash = dagKernel.getTransactionStore().getBlockByTransaction(tx.getHash());
    if (blockHash != null) {
      builder.blockHash(blockHash.toHexString());

      Block block = dagKernel.getDagChain().getBlockByHash(blockHash);
      if (block != null) {
        long timestamp = TimeUtils.epochNumberToTimeMillis(block.getEpoch());
        builder.timestamp(timestamp);

        BlockInfo blockInfo = block.getInfo();
        if (blockInfo != null) {
          builder.epoch(blockInfo.getEpoch());

          if (blockInfo.isMainBlock()) {
            builder.blockHeight(blockInfo.getHeight());
            builder.status("Confirmed (Main)");
          } else {
            builder.status("Unconfirmed (Orphan)");
          }
        }
      }
    } else {
      builder.status("Pending");
    }

    return builder.build();
  }
}
