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
package io.xdag.db.rocksdb;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import io.xdag.core.SnapshotInfo;
import io.xdag.core.XAmount;
import io.xdag.core.XUnit;
// Phase 7.3.1: XdagTopStatus import removed (class deleted, merged into ChainStats)
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;
import io.xdag.db.AddressStore;
import io.xdag.db.BlockStore;
import io.xdag.db.SnapshotStore;
import io.xdag.db.TransactionHistoryStore;
import io.xdag.db.execption.DeserializationException;
import io.xdag.db.execption.SerializationException;
import io.xdag.utils.BasicUtils;
import io.xdag.utils.BytesUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.bouncycastle.util.encoders.Hex;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.rocksdb.RocksIterator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static io.xdag.config.Constants.BI_OURS;
import static io.xdag.db.AddressStore.ADDRESS_SIZE;
import static io.xdag.db.AddressStore.CURRENT_TRANSACTION_QUANTITY;
import static io.xdag.db.BlockStore.*;
import static io.xdag.utils.BasicUtils.compareAmountTo;

@Slf4j
public class SnapshotStoreImpl implements SnapshotStore {

    private final RocksdbKVSource snapshotSource;

    private final Kryo kryo;
    @Getter
    private XAmount ourBalance = XAmount.ZERO;
    @Getter
    private XAmount allBalance = XAmount.ZERO;
    @Getter
    private long nextTime;
    @Getter
    private long height;


    public SnapshotStoreImpl(RocksdbKVSource snapshotSource) {
        this.snapshotSource = snapshotSource;
        this.kryo = new Kryo();
        kryoRegister();
    }

    @Override
    public void init() {
        snapshotSource.init();
    }

    @Override
    public void reset() {
        snapshotSource.reset();
    }

    /**
     * Create blockchain snapshot (Phase 8.3.2 - Deferred to Phase 8.4)
     *
     * @deprecated Phase 8.3.2: Requires full v5.1 snapshot system migration (Phase 8.4).
     * This method is a complex data migration tool (~150-200 lines) that needs:
     * - LegacyBlockInfo → BlockV5 migration
     * - TransactionStore integration for balance calculation
     * - Comprehensive testing with real blockchain data
     *
     * @param blockSource RocksDB block source
     * @param indexSource RocksDB index source
     * @throws UnsupportedOperationException Always - not yet migrated to v5.1
     */
    @Override
    public void makeSnapshot(RocksdbKVSource blockSource, RocksdbKVSource indexSource) {
        log.warn("makeSnapshot() requires full v5.1 snapshot system migration");
        log.warn("This feature is temporarily unavailable - see Phase 8.4 planning");
        log.warn("Called from: XdagCli.java:515 (CLI snapshot command)");
        throw new UnsupportedOperationException(
            "Snapshot creation not yet migrated to v5.1. " +
            "This requires Phase 8.4 implementation (~4-6 hours). " +
            "Please start blockchain from genesis or wait for Phase 8.4."
        );
    }

    /**
     * Load blockchain snapshot into index (Phase 8.3.3 - Deferred to Phase 8.4)
     *
     * @deprecated Phase 8.3.3: Requires full v5.1 snapshot system migration (Phase 8.4).
     * This method is CRITICAL for blockchain initialization (~200-250 lines) that needs:
     * - Block → BlockV5 migration
     * - TxHistory → Transaction migration
     * - Signature validation and index rebuilding
     * - Comprehensive testing to prevent data corruption
     *
     * WARNING: This is called by BlockchainImpl.java:198 during blockchain startup.
     * If snapshot boot is enabled, blockchain CANNOT start until Phase 8.4 is complete.
     *
     * @param blockStore Block storage
     * @param txHistoryStore Transaction history storage
     * @param keys Wallet keys for signature validation
     * @param snapshotTime Snapshot timestamp
     * @throws UnsupportedOperationException Always - not yet migrated to v5.1
     */
    @Override
    public void saveSnapshotToIndex(BlockStore blockStore, TransactionHistoryStore txHistoryStore,
                                     List<ECKeyPair> keys, long snapshotTime) {
        log.error("saveSnapshotToIndex() not migrated to v5.1 - cannot load snapshot");
        log.error("This CRITICAL method is called during blockchain initialization (BlockchainImpl.java:198)");
        log.error("Blockchain must be started from genesis or use v5.1-compatible snapshot");
        log.error("See Phase 8.4 planning for snapshot system migration (~4-6 hours)");
        throw new UnsupportedOperationException(
            "Snapshot loading not yet migrated to v5.1. " +
            "This BLOCKS blockchain initialization if snapshot boot is enabled. " +
            "Please disable snapshot boot or start from genesis. " +
            "Phase 8.4 implementation required (~4-6 hours)."
        );
    }

    // Phase 7.1.2: Removed setBlockInfo() method - PreBlockInfo deleted, use LegacyBlockInfo directly

    // Temporarily disabled - waiting for migration to BlockV5
    /*
    // Phase 7.1.2: Removed boolean parameter - always deserialize to LegacyBlockInfo directly
    public void makeSnapshot(RocksdbKVSource blockSource, RocksdbKVSource indexSource) {
        try (RocksIterator iter = indexSource.getDb().newIterator()) {
            for (iter.seek(new byte[]{HASH_BLOCK_INFO}); iter.isValid() && iter.key()[0] < SUMS_BLOCK_INFO; iter.next()) {
                LegacyBlockInfo blockInfo = new LegacyBlockInfo();
                if (iter.value() != null) {
                    try {
                        blockInfo = (LegacyBlockInfo) deserialize(iter.value(), LegacyBlockInfo.class);
                    } catch (DeserializationException e) {
//                        log.error("hash low:{}", Hex.toHexString(blockInfo.getHashlow()));
                        log.error("can't deserialize data:{}", Hex.toHexString(iter.value()));
                        log.error(e.getMessage(), e);
                    }
                    // Has public key or block data
                    if (blockInfo.getSnapshotInfo() != null) {
                        int flag = blockInfo.getFlags();
                        flag &= ~BI_OURS;
                        blockInfo.setFlags(flag);
                        blockInfo.setSnapshot(true);
                        save(iter, blockInfo);
                    } else { // Storage block data without public key and balance
                        if ((blockInfo.getAmount() != null && compareAmountTo(blockInfo.getAmount(), XAmount.ZERO) != 0)) {
//                        if (blockInfo.getAmount() != 0) {
                            blockInfo.setSnapshot(true);
                            blockInfo.setSnapshotInfo(new SnapshotInfo(false, blockSource.get(
                                    BytesUtils.subArray(iter.key(), 1, 32))));
                            int flag = blockInfo.getFlags();
                            flag &= ~BI_OURS;
                            blockInfo.setFlags(flag);
                            save(iter, blockInfo);
                        }
                    }
                }
                if (blockInfo.getHeight() >= height) {
                    height = blockInfo.getHeight();
                    nextTime = blockInfo.getTimestamp();
                }
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        byte[] preSeed = snapshotSource.get(new byte[]{SNAPSHOT_PRESEED});
        snapshotSource.put(new byte[]{SNAPSHOT_PRESEED}, preSeed);
    }
    */

    // Temporarily disabled - waiting for migration to BlockV5
    /*
    public void saveSnapshotToIndex(BlockStore blockStore, TransactionHistoryStore txHistoryStore, List<ECKeyPair> keys,long snapshotTime) {
        try (RocksIterator iter = snapshotSource.getDb().newIterator()) {
            for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                if (iter.key()[0] == HASH_BLOCK_INFO) {
                    LegacyBlockInfo blockInfo = new LegacyBlockInfo();
                    if (iter.value() != null) {
                        try {
                            blockInfo = (LegacyBlockInfo) deserialize(iter.value(), LegacyBlockInfo.class);
                        } catch (DeserializationException e) {
                            log.error("hashlow (legacy format):{}", Hex.toHexString(blockInfo.getHashlow()));
                            log.error("can't deserialize data:{}", Hex.toHexString(iter.value()));
                            log.error(e.getMessage(), e);
                        }
                        int flag = blockInfo.getFlags();
                        int keyIndex = -1;
                        //Determine if it is your own address
                        SnapshotInfo snapshotInfo = blockInfo.getSnapshotInfo();
                        if (blockInfo.getSnapshotInfo() != null) {
                            //public key exists
                            if (snapshotInfo.getType()) {
                                byte[] ecKeyPair = snapshotInfo.getData();
                                for (int i = 0; i < keys.size(); i++) {
                                    ECKeyPair key = keys.get(i);
                                    if (key.getPublicKey().toBytes().compareTo(Bytes.wrap(ecKeyPair)) == 0) {
                                        flag |= BI_OURS;
                                        keyIndex = i;
                                        ourBalance = ourBalance.add(blockInfo.getAmount());
                                        break;
                                    }
                                }
                            } else {    //Verify signature
                                Block block = new Block(new XdagBlock(snapshotInfo.getData()));
                                Signature outSig = block.getOutsig();
                                for (int i = 0; i < keys.size(); i++) {
                                    ECKeyPair keyPair = keys.get(i);
                                    byte[] publicKeyBytes = keyPair.getPublicKey().toBytes().toArray();
                                    Bytes digest = Bytes
                                            .wrap(block.getSubRawData(block.getOutsigIndex() - 2), Bytes.wrap(publicKeyBytes));
                                    Bytes32 hash = HashUtils.doubleSha256(Bytes.wrap(digest));
//                                    if (Signer.verify(hash, Sign.toCanonical(outSig), keyPair.getPublicKey())) {
                                    // TODO FIXME toCanonical
                                    if (Signer.verify(hash, outSig, keyPair.getPublicKey())) {
                                        flag |= BI_OURS;
                                        keyIndex = i;
                                        ourBalance = ourBalance.add(blockInfo.getAmount());
                                        break;
                                    }
                                }
                            }
                        }
                        blockInfo.setFlags(flag);
                        if ((flag & BI_OURS) != 0 && keyIndex > -1) {
                            blockStore.saveOurBlock(keyIndex, blockInfo.getHashlow());
                        }
                        allBalance = allBalance.add(blockInfo.getAmount());
                        blockStore.saveBlockInfo(blockInfo);

                        if(txHistoryStore != null) {
                            XdagField.FieldType fieldType = XdagField.FieldType.XDAG_FIELD_SNAPSHOT;
                            Address address = new Address(Bytes32.wrap(blockInfo.getHashlow()), fieldType, blockInfo.getAmount(),false);

                            TxHistory txHistory = new TxHistory();
                            txHistory.setAddress(address);
                            txHistory.setHash(BasicUtils.hash2Address(address.getAddress()));
                            if(blockInfo.getRemark() != null) {
                                txHistory.setRemark(new String(blockInfo.getRemark(), StandardCharsets.UTF_8));
                            }
                            txHistory.setTimestamp(snapshotTime);
                            txHistoryStore.batchSaveTxHistory(txHistory);
                        }
                    }
                } else if (iter.key()[0] == SNAPSHOT_PRESEED) {
                    blockStore.savePreSeed(iter.value());
                }
            }
            System.out.println("amount in blocks: " + allBalance.toDecimal(9, XUnit.XDAG).toPlainString());
            if (txHistoryStore != null) {
                txHistoryStore.batchSaveTxHistory(null);
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }
    */


    @Override
    public void saveAddress(BlockStore blockStore, AddressStore addressStore, TransactionHistoryStore txHistoryStore, List<ECKeyPair> keys, long snapshotTime) {
        try (RocksIterator iter = snapshotSource.getDb().newIterator()) {
            for (iter.seekToFirst(); iter.isValid(); iter.next()) {
                if (iter.key().length < 20) {
                    if (iter.key()[0] == ADDRESS_SIZE) {
                        addressStore.saveAddressSize(iter.value());
                    }
                } else {
                    byte[] address = iter.key(); // address = flag + accountAddress: 30(byte ADDRESS = (byte) 0x30) + fb3fb15072826ffa5f5b6c123029798a27cd0c64
                    if (Hex.toHexString(address).startsWith("30")) {
                        XAmount balance = XAmount.ofXAmount(UInt64.fromBytes(Bytes.wrap(iter.value())).toLong());
                        for (ECKeyPair keyPair : keys) {
                            byte[] publicKeyBytes = keyPair.getPublicKey().toBytes().toArray();
                            byte[] myAddress = HashUtils.sha256hash160(Bytes.wrap(publicKeyBytes)).toArray();
                            if (BytesUtils.compareTo(address, 1, 20, myAddress, 0, 20) == 0) {
                                ourBalance = ourBalance.add(balance);
                            }
                        }
                        allBalance = allBalance.add(balance); //calculate the address balance
                        addressStore.snapshotAddress(address, balance);
                        // Temporarily disabled - waiting for migration to BlockV5
                        /*
                        if (txHistoryStore != null) {
                            XdagField.FieldType fieldType = XdagField.FieldType.XDAG_FIELD_SNAPSHOT;
                            Address addr = new Address(BytesUtils.arrayToByte32(Arrays.copyOfRange(address, 1, 21)),
                                    fieldType, balance, true);
                            TxHistory txHistory = new TxHistory();
                            txHistory.setAddress(addr);
                            txHistory.setHash(BasicUtils.hash2PubAddress(addr.getAddress()));
                            txHistory.setRemark("snapshot");
                            txHistory.setTimestamp(snapshotTime);
                            txHistoryStore.saveTxHistory(txHistory);
                        }
                        */
                    } // TODO: Restore the transaction quantity for each address from the snapshot.
                    else if (Hex.toHexString(address).startsWith("50")) {
                        UInt64 exeTxNonceNum = UInt64.fromBytes(Bytes.wrap(iter.value())).toUInt64();
                        byte[] TxQuantityKey = BytesUtils.merge(CURRENT_TRANSACTION_QUANTITY, BytesUtils.byte32ToArray(BytesUtils.arrayToByte32(Arrays.copyOfRange(address, 1, 21))).toArrayUnsafe());
                        addressStore.snapshotTxQuantity(TxQuantityKey, exeTxNonceNum);
                        addressStore.snapshotExeTxNonceNum(address, exeTxNonceNum);
                    }
                }
            }
            System.out.println("amount in address: " + allBalance.toDecimal(9, XUnit.XDAG).toPlainString());
            //sava Address all Balance as AMOUNT_SUM
            addressStore.saveAmountSum(allBalance);
        }
    }

    // Temporarily disabled - waiting for migration to BlockV5
    /*
    public void save(RocksIterator iter, LegacyBlockInfo blockInfo) {
        byte[] value = null;
        try {
            value = serialize(blockInfo);
        } catch (SerializationException e) {
            log.error(e.getMessage(), e);
        }
        snapshotSource.put(iter.key(), value);
    }
    */

    public Object deserialize(final byte[] bytes, Class<?> type) throws DeserializationException {
        synchronized (kryo) {
            try {
                final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
                final Input input = new Input(inputStream);
                return kryo.readObject(input, type);
            } catch (final IllegalArgumentException | KryoException | NullPointerException exception) {
                log.debug("Deserialize data:{}", Hex.toHexString(bytes));
                throw new DeserializationException(exception.getMessage(), exception);
            }
        }
    }

    public byte[] serialize(final Object obj) throws SerializationException {
        synchronized (kryo) {
            try {
                final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                final Output output = new Output(outputStream);
                kryo.writeObject(output, obj);
                output.flush();
                output.close();
                return outputStream.toByteArray();
            } catch (final IllegalArgumentException | KryoException exception) {
                throw new SerializationException(exception.getMessage(), exception);
            }
        }
    }

    private void kryoRegister() {
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        kryo.register(BigInteger.class);
        kryo.register(byte[].class);
        // kryo.register(LegacyBlockInfo.class);
        // Phase 7.3: XdagStats registration removed (class deleted, using ChainStats + CompactSerializer)
        // Phase 7.3.1: XdagTopStatus registration removed (class deleted, merged into ChainStats)
        kryo.register(SnapshotInfo.class);
        kryo.register(UInt64.class);
        kryo.register(XAmount.class);
        // Phase 7.1.2: Removed PreBlockInfo.class registration (class deleted)
    }

}
