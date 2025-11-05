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
package io.xdag.db.mysql;

import io.xdag.db.TransactionHistoryStore;
import io.xdag.utils.DruidUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TransactionHistoryStoreImpl implements TransactionHistoryStore {

    private static final String SQL_INSERT = "insert into t_transaction_history(faddress,faddresstype,fhash,famount," +
            "ftype,fremark,ftime) values(?,?,?,?,?,?,?)";

    private static final String SQL_QUERY_TXHISTORY_BY_ADDRESS_WITH_TIME = "select faddress,faddresstype,fhash," +
            "famount,ftype,fremark,ftime from t_transaction_history where faddress= ? and ftime >= ? and ftime <= ? order by ftime desc limit ?,?";

    private static final String SQL_QUERY_TXHISTORY_COUNT = "select count(*) from t_transaction_history where faddress=?";

    private static final String SQL_QUERY_TXHISTORY_COUNT_WITH_TIME = "select count(*) from t_transaction_history where faddress=? and ftime >=? and ftime <=?";
    private static final int BLOCK_ADDRESS_FLAG = 0;
    private static final int WALLET_ADDRESS_FLAG = 1;
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_CACHE_SIZE = 50000;
    private final long TX_PAGE_SIZE_LIMIT;
    private Connection connBatch = null;
    private PreparedStatement pstmtBatch = null;
    private int count = 0;
    public static int totalPage = 1;

    public TransactionHistoryStoreImpl(long txPageSizeLimit) {
        this.TX_PAGE_SIZE_LIMIT = txPageSizeLimit;
    }

    // All TX history save/list methods temporarily disabled - interface methods commented out
    /*
    @Override
    public boolean saveTxHistory(TxHistory txHistory) {
        // implementation commented out
    }

    @Override
    public boolean batchSaveTxHistory(TxHistory txHistory, int... cacheNum) {
        // implementation commented out
    }

    @Override
    public List<TxHistory> listTxHistoryByAddress(String address, int page, Object... parameters) {
        // implementation commented out
    }
    */

    @Override
    public int getTxHistoryCount(String address) {
        int count = 0;
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;

        try {
            conn = DruidUtils.getConnection();
            if (conn != null) {
                pstmt = conn.prepareStatement(SQL_QUERY_TXHISTORY_COUNT);
                pstmt.setString(1, address);
                rs = pstmt.executeQuery();
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        } finally {
            DruidUtils.close(conn, pstmt, rs);
        }
        return count;
    }

}
