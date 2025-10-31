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

package io.xdag.core;

import com.google.common.collect.Lists;
import io.xdag.config.Config;
import io.xdag.crypto.hash.HashUtils;
import io.xdag.crypto.keys.ECKeyPair;
import io.xdag.crypto.keys.PublicKey;
import io.xdag.crypto.keys.Signature;
import io.xdag.crypto.keys.Signer;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.SimpleEncoder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;
import org.apache.tuweni.units.bigints.UInt256;

import java.math.BigInteger;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.xdag.core.XdagField.FieldType.*;

/**
 * Legacy mutable Block class (v1.0 architecture)
 *
 * @deprecated As of v5.1 refactor (Phase 6.5 - Deep Core Cleanup), this class represents
 *             the legacy Block architecture with mutable design and Address-based references.
 *             In v5.1, the immutable {@link BlockV5} architecture is used instead.
 *
 *             <p><b>Why Deprecated:</b>
 *             <ul>
 *             <li><b>Mutable Design:</b> Block allows state mutations (signOut, parse, etc.),
 *                 making it harder to reason about and not thread-safe. BlockV5 is immutable.</li>
 *             <li><b>Address-based References:</b> Uses {@link Address} objects (~64 bytes per reference)
 *                 instead of {@link Link} objects (33 bytes per reference). This wastes 48% memory.</li>
 *             <li><b>Complex Structure:</b> 613 lines of code with embedded signing logic, XdagBlock
 *                 serialization coupling, and legacy field type handling.</li>
 *             <li><b>Fixed Size Limitation:</b> Tied to 512-byte XdagBlock format (16 fields × 32 bytes).
 *                 BlockV5 supports up to 48MB blocks with 1,485,000 links.</li>
 *             <li><b>Transaction Confusion:</b> Transactions represented as special Block objects.
 *                 BlockV5 separates {@link Transaction} objects from blocks.</li>
 *             </ul>
 *
 *             <p><b>v5.1 Replacement: {@link BlockV5}</b>
 *             <pre>{@code
 * // Legacy Block (mutable, 613 lines)
 * Block block = new Block(config, timestamp, links, pendings, mining, keys, remark, defKeyIndex, fee, txNonce);
 * block.signOut(key);  // Mutable: modifies internal state
 * List<Address> refs = block.getLinks();  // ~64 bytes per reference
 *
 * // v5.1 BlockV5 (immutable, cleaner)
 * BlockV5 block = BlockV5.builder()
 *     .header(BlockHeader.builder()
 *         .timestamp(timestamp)
 *         .difficulty(difficulty)
 *         .coinbase(coinbase)
 *         .build())
 *     .links(links)  // Link objects (33 bytes each)
 *     .build();
 * // No signing needed - coinbase in header
 * // Immutable - no state mutations
 *             }</pre>
 *
 *             <p><b>Architecture Comparison:</b>
 *             <table border="1">
 *             <tr><th>Aspect</th><th>Block (Legacy)</th><th>BlockV5 (v5.1)</th><th>Improvement</th></tr>
 *             <tr><td><b>Design</b></td><td>Mutable</td><td>Immutable</td><td>Thread-safe ✅</td></tr>
 *             <tr><td><b>References</b></td><td>Address (~64 bytes)</td><td>Link (33 bytes)</td><td>-48% size ✅</td></tr>
 *             <tr><td><b>Max Block Size</b></td><td>512 bytes</td><td>48MB</td><td>97,656x ✅</td></tr>
 *             <tr><td><b>Max Links</b></td><td>~15</td><td>1,485,000</td><td>99,000x ✅</td></tr>
 *             <tr><td><b>Transaction</b></td><td>Special Block</td><td>Separate objects</td><td>Clearer ✅</td></tr>
 *             <tr><td><b>Hash Caching</b></td><td>Recalculation</td><td>Lazy cache</td><td>O(1) ✅</td></tr>
 *             <tr><td><b>Complexity</b></td><td>613 lines</td><td>Modular design</td><td>Maintainable ✅</td></tr>
 *             </table>
 *
 *             <p><b>Migration Examples:</b>
 *
 *             <p><b>1. Mining Block Creation:</b>
 *             <pre>{@code
 * // Legacy
 * Block miningBlock = blockchain.createNewBlock(keys, addresses, true, remark, fee, nonce);
 *
 * // v5.1
 * BlockV5 miningBlock = blockchain.createMainBlockV5();
 * miningBlock = miningBlock.withNonce(minedNonce);  // Immutable pattern
 *             }</pre>
 *
 *             <p><b>2. Transaction Block Creation:</b>
 *             <pre>{@code
 * // Legacy: Transaction as Block
 * List<SyncBlock> txBlocks = wallet.createTransactionBlock(keys, to, remark, nonce);
 * for (SyncBlock sb : txBlocks) {
 *     blockchain.tryToConnect(sb.getBlock());
 * }
 *
 * // v5.1: Transaction as separate object
 * Transaction tx = Transaction.builder()
 *     .from(fromAddress).to(toAddress).amount(amount)
 *     .nonce(nonce).fee(fee).data(remarkData)
 *     .build().sign(account);
 * transactionStore.saveTransaction(tx);
 *
 * BlockV5 block = BlockV5.builder()
 *     .header(header)
 *     .links(Lists.newArrayList(Link.toTransaction(tx.getHash())))
 *     .build();
 * blockchain.tryToConnect(block);
 *             }</pre>
 *
 *             <p><b>3. Block Synchronization:</b>
 *             <pre>{@code
 * // Legacy: Wrapped in SyncBlock
 * SyncBlock syncBlock = new SyncBlock(block, ttl, peer, isOld);
 * syncManager.importBlock(syncBlock);
 *
 * // v5.1: Direct BlockV5 usage
 * BlockV5 block = blockV5Message.getBlock();
 * ImportResult result = blockchain.tryToConnect(block);
 * if (shouldBroadcast(result)) {
 *     kernel.broadcastBlockV5(block, ttl);
 * }
 *             }</pre>
 *
 *             <p><b>4. Block Storage/Retrieval:</b>
 *             <pre>{@code
 * // Legacy
 * Block block = blockStore.getBlockByHash(hash);  // Returns Block
 *
 * // v5.1
 * BlockV5 block = blockStore.getBlockV5(hash);  // Returns BlockV5
 *             }</pre>
 *
 *             <p><b>Current Usage (17 files):</b>
 *             <ul>
 *             <li>Storage layer: FinalizedBlockStore, CachedBlockStore, BloomFilterBlockStore, OrphanBlockStore</li>
 *             <li>Network layer: XdagP2pHandler, XdagP2pEventHandler, ChannelManager</li>
 *             <li>Deprecated messages: NewBlockMessage, SyncBlockMessage</li>
 *             <li>Consensus: RandomX, XdagSync</li>
 *             <li>Tests: BlockBuilder, CommandsTest, BlockStoreImplTest, RandomXTest</li>
 *             </ul>
 *
 *             <p><b>Backward Compatibility:</b>
 *             <br>Block class implementation is kept for backward compatibility with existing storage.
 *             After full BlockV5-only deployment, this class will be removed in Phase 7.
 *
 *             <p><b>Migration Status:</b>
 *             <ul>
 *             <li>✅ BlockV5 architecture complete (Phase 1-2)</li>
 *             <li>✅ Storage layer supports BlockV5 (Phase 4)</li>
 *             <li>✅ Network layer supports BlockV5 (Phase 3)</li>
 *             <li>✅ Runtime migrated to BlockV5 (Phase 5)</li>
 *             <li>✅ Legacy code removed (Phase 6)</li>
 *             <li>⏳ Storage migration to BlockV5-only (Future Phase 7)</li>
 *             </ul>
 *
 *             <p><b>Performance Impact:</b>
 *             <br>BlockV5 enables:
 *             <ul>
 *             <li>23,200 TPS capacity (96.7% Visa level)</li>
 *             <li>1,485,000 transactions per 48MB block</li>
 *             <li>48% memory reduction per reference</li>
 *             <li>Thread-safe immutable design</li>
 *             <li>O(1) hash lookups (cached)</li>
 *             </ul>
 *
 *             <p><b>Related Deprecations:</b>
 *             <ul>
 *             <li>{@link Address} - Replaced by {@link Link}</li>
 *             <li>{@link io.xdag.core.XdagBlock} - Replaced by BlockV5 serialization</li>
 *             <li>{@link io.xdag.net.message.consensus.NewBlockMessage} - Replaced by NewBlockV5Message</li>
 *             <li>{@link io.xdag.net.message.consensus.SyncBlockMessage} - Replaced by SyncBlockV5Message</li>
 *             </ul>
 *
 *             <p><b>Reference Implementation:</b>
 *             <ul>
 *             <li>{@link BlockchainImpl#createMainBlockV5()} - Mining block creation</li>
 *             <li>{@link BlockchainImpl#tryToConnect(BlockV5)} - Block import</li>
 *             <li>{@link io.xdag.cli.Commands#xferV2} - Transaction creation example</li>
 *             </ul>
 *
 * @see BlockV5
 * @see Link
 * @see Transaction
 * @see BlockHeader
 * @see BlockInfo
 */
@Deprecated(since = "0.8.1", forRemoval = true)
@Slf4j
@Getter
@Setter
public class Block implements Cloneable {

    public static final int MAX_LINKS = 15;
    /**
     * Whether the block exists locally
     */
    public boolean isSaved;
    private Address coinBase;

    // ========== Phase 2 Core Refactor: Use immutable BlockInfo ==========
    private BlockInfo info;

    private long transportHeader;
    /**
     * List of block links (inputs and outputs)
     */
    private List<Address> inputs = new CopyOnWriteArrayList<>();

    private TxAddress txNonceField;
    /**
     * Outputs including pretop
     */
    private List<Address> outputs = new CopyOnWriteArrayList<>();
    /**
     * Record public keys (prefix + compressed public key)
     */
    private List<PublicKey> pubKeys = new CopyOnWriteArrayList<>();
    private Map<Signature, Integer> insigs = new LinkedHashMap<>();
    private Signature outsig;
    /**
     * Main block nonce records miner address and nonce
     */
    private Bytes32 nonce;
    private XdagBlock xdagBlock;
    private boolean parsed;
    private boolean isOurs;
    private byte[] encoded;
    private int tempLength;
    private boolean pretopCandidate;
    private BigInteger pretopCandidateDiff;

    public Block(
            Config config,
            long timestamp,
            List<Address> links,
            List<Address> pendings,
            boolean mining,
            List<ECKeyPair> keys,
            String remark,
            int defKeyIndex,
            XAmount fee,
            UInt64 txNonce) {
        parsed = true;

        // Build type during construction
        long typeValue = 0;
        int lenghth = 0;

        typeValue |= ((long) config.getXdagFieldHeader().asByte()) << (lenghth++ << 2);

        if (txNonce != null) {
            txNonceField = new TxAddress(txNonce);
            typeValue |= ((long) XDAG_FIELD_TRANSACTION_NONCE.asByte()) << (lenghth++ << 2);
        }

        if (CollectionUtils.isNotEmpty(links)) {
            for (Address link : links) {
                XdagField.FieldType type = link.getType();
                typeValue |= ((long) type.asByte()) << (lenghth++ << 2);
                if (type == XDAG_FIELD_OUT || type == XDAG_FIELD_OUTPUT) {
                    outputs.add(link);
                } else if (type == XDAG_FIELD_IN || type == XDAG_FIELD_INPUT) {
                    inputs.add(link);
                } else if (type == XDAG_FIELD_COINBASE) {
                    this.coinBase = link;
                    outputs.add(link);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(pendings)) {
            for (Address pending : pendings) {
                XdagField.FieldType type = pending.getType();
                typeValue |= ((long) type.asByte()) << (lenghth++ << 2);
                if (type == XDAG_FIELD_OUT || type == XDAG_FIELD_OUTPUT) {
                    outputs.add(pending);
                } else if (type == XDAG_FIELD_IN || type == XDAG_FIELD_INPUT) {
                    inputs.add(pending);
                } else if (type == XDAG_FIELD_COINBASE) {
                    this.coinBase = pending;
                    outputs.add(pending);
                }
            }
        }

        Bytes remarkBytes = null;
        if (StringUtils.isAsciiPrintable(remark)) {
            typeValue |= ((long) XDAG_FIELD_REMARK.asByte()) << (lenghth++ << 2);
            byte[] data = remark.getBytes(StandardCharsets.UTF_8);
            byte[] safeRemark = new byte[32];
            Arrays.fill(safeRemark, (byte) 0);
            System.arraycopy(data, 0, safeRemark, 0, Math.min(data.length, 32));
            remarkBytes = Bytes.wrap(safeRemark);
        }

        if (CollectionUtils.isNotEmpty(keys)) {
            for (ECKeyPair key : keys) {
                PublicKey publicKey = key.getPublicKey();
                byte[] keydata = publicKey.toBytes().toArray();
                boolean yBit = BytesUtils.toByte(BytesUtils.subArray(keydata, 0, 1)) == 0x03;
                XdagField.FieldType type = yBit ? XDAG_FIELD_PUBLIC_KEY_1 : XDAG_FIELD_PUBLIC_KEY_0;
                typeValue |= ((long) type.asByte()) << (lenghth++ << 2);
                pubKeys.add(key.getPublicKey());
            }
            for (int i = 0; i < keys.size(); i++) {
                if (i != defKeyIndex) {
                    typeValue |= ((long) XDAG_FIELD_SIGN_IN.asByte()) << (lenghth++ << 2);
                    typeValue |= ((long) XDAG_FIELD_SIGN_IN.asByte()) << (lenghth++ << 2);
                } else {
                    typeValue |= ((long) XDAG_FIELD_SIGN_OUT.asByte()) << (lenghth++ << 2);
                    typeValue |= ((long) XDAG_FIELD_SIGN_OUT.asByte()) << (lenghth++ << 2);
                }
            }
        }

        if (defKeyIndex < 0) {
            typeValue |= ((long) XDAG_FIELD_SIGN_OUT.asByte()) << (lenghth++ << 2);
            typeValue |= ((long) XDAG_FIELD_SIGN_OUT.asByte()) << (lenghth << 2);
        }

        if (mining) {
            typeValue |= ((long) XDAG_FIELD_SIGN_IN.asByte()) << (MAX_LINKS << 2);
        }

        // Create immutable BlockInfo
        this.info = BlockInfo.builder()
                .hash(null)  // Will be calculated later
                .timestamp(timestamp)
                .height(0)  // Will be set later
                .type(typeValue)
                .flags(0)  // Will be set later
                .difficulty(org.apache.tuweni.units.bigints.UInt256.ZERO)
                .ref(null)
                .maxDiffLink(null)
                .amount(XAmount.ZERO)
                .fee(fee)
                .remark(remarkBytes)
                .isSnapshot(false)
                .snapshotInfo(null)
                .build();
    }

    /**
     * main block
     */
    public Block(Config config, long timestamp,
                 List<Address> pendings,
                 boolean mining) {
        this(config, timestamp, null, pendings, mining, null, null, -1, XAmount.ZERO, null);
    }

    /**
     * Read from raw block of 512 bytes
     */
    public Block(XdagBlock xdagBlock) {
        this.xdagBlock = xdagBlock;
        // info will be created in parse() method
        parse();
    }

    public Block(LegacyBlockInfo blockInfo) {
        this.info = BlockInfo.fromLegacy(blockInfo);
        this.isSaved = true;
        this.parsed = true;
    }

    /**
     * Create Block from new immutable BlockInfo (Phase 2 core refactor)
     * This constructor directly uses the immutable BlockInfo
     *
     * @param blockInfo The new immutable BlockInfo
     */
    public Block(BlockInfo blockInfo) {
        this.info = blockInfo;
        this.isSaved = true;
        this.parsed = true;
    }

    /**
     * Calculate block hash
     */
    private byte[] calcHash() {
        if (xdagBlock == null) {
            xdagBlock = getXdagBlock();
        }
        return Bytes32.wrap(HashUtils.doubleSha256(Bytes.wrap(xdagBlock.getData())).reverse()).toArray();
    }

    /**
     * Recalculate to avoid directly updating hash when miner sends share
     */
    public Bytes32 recalcHash() {
        xdagBlock = new XdagBlock(toBytes());
        return Bytes32.wrap(HashUtils.doubleSha256(Bytes.wrap(xdagBlock.getData())).reverse());
    }

    /**
     * Parse 512 bytes data
     */
    public void parse() {
        if (this.parsed) {
            return;
        }

        // Collect data in temporary variables
        byte[] hash = calcHash();
        Bytes32 header = Bytes32.wrap(xdagBlock.getField(0).getData());
        this.transportHeader = header.getLong(0, ByteOrder.LITTLE_ENDIAN);
        long typeValue = header.getLong(8, ByteOrder.LITTLE_ENDIAN);
        long timestamp = header.getLong(16, ByteOrder.LITTLE_ENDIAN);
        XAmount fee = XAmount.of(header.getLong(24, ByteOrder.LITTLE_ENDIAN), XUnit.NANO_XDAG);
        Bytes remarkBytes = null;

        for (int i = 1; i < XdagBlock.XDAG_BLOCK_FIELDS; i++) {
            XdagField field = xdagBlock.getField(i);
            if (field == null) {
                throw new IllegalArgumentException("xdagBlock field:" + i + " is null");
            }
            switch (field.getType()) {
                case XDAG_FIELD_TRANSACTION_NONCE -> txNonceField = new TxAddress(field);
                case XDAG_FIELD_IN -> inputs.add(new Address(field, false));
                case XDAG_FIELD_INPUT -> inputs.add(new Address(field, true));
                case XDAG_FIELD_OUT -> outputs.add(new Address(field, false));
                case XDAG_FIELD_OUTPUT -> outputs.add(new Address(field, true));
                case XDAG_FIELD_REMARK -> remarkBytes = field.getData();
                case XDAG_FIELD_COINBASE -> {
                    this.coinBase = new Address(field, true);
                    outputs.add(new Address(field, true));
                }
                case XDAG_FIELD_SIGN_IN, XDAG_FIELD_SIGN_OUT -> {
                    BigInteger r;
                    BigInteger s;
                    int j, signo_s = -1;
                    XdagField ixf;
                    for (j = i; j < XdagBlock.XDAG_BLOCK_FIELDS; ++j) {
                        ixf = xdagBlock.getField(j);
                        if (ixf.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()
                                || ixf.getType() == XDAG_FIELD_SIGN_OUT) {
                            if (j > i && signo_s < 0 && ixf.getType().ordinal() == xdagBlock.getField(i).getType()
                                    .ordinal()) {
                                signo_s = j;
                                r = xdagBlock.getField(i).getData().toUnsignedBigInteger();
                                s = xdagBlock.getField(signo_s).getData().toUnsignedBigInteger();

                                // r and s are 0, the signature is illegal, or it is a pseudo block sent by the miner
                                if (r.compareTo(BigInteger.ZERO) == 0 && s.compareTo(BigInteger.ZERO) == 0) {
                                    r = BigInteger.ONE;
                                    s = BigInteger.ONE;
                                }

                                Signature tmp = Signature.create(r, s, (byte) 0);
                                if (ixf.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()) {
                                    insigs.put(tmp, i);
                                } else {
                                    outsig = tmp;
                                }
                            }
                        }
                    }
                    if (i == MAX_LINKS && field.getType().ordinal() == XDAG_FIELD_SIGN_IN.ordinal()) {
                        this.nonce = Bytes32.wrap(xdagBlock.getField(i).getData());
                    }
                }
                case XDAG_FIELD_PUBLIC_KEY_0, XDAG_FIELD_PUBLIC_KEY_1 -> {
                    Bytes key = xdagBlock.getField(i).getData();
                    boolean yBit = (field.getType().ordinal() == XDAG_FIELD_PUBLIC_KEY_1.ordinal());
                    PublicKey publicKey = PublicKey.fromXCoordinate(key, yBit);
                    pubKeys.add(publicKey);
                }
                default -> {
                }
                //                    log.debug("no match xdagBlock field type:" + field.getType());
            }
        }

        // Create immutable BlockInfo at the end
        // Store full hash (32 bytes)
        Bytes32 fullHash = Bytes32.wrap(hash);

        this.info = BlockInfo.builder()
                .hash(fullHash)
                .timestamp(timestamp)
                .height(0)  // Will be set later
                .type(typeValue)
                .flags(0)  // Will be set later
                .difficulty(UInt256.ZERO)  // Will be set later
                .ref(null)  // Will be set later
                .maxDiffLink(null)  // Will be set later
                .amount(XAmount.ZERO)  // Will be set later
                .fee(fee)
                .remark(remarkBytes)
                .isSnapshot(false)
                .snapshotInfo(null)
                .build();

        this.parsed = true;
    }

    public byte[] toBytes() {
        SimpleEncoder encoder = new SimpleEncoder();
        encoder.write(getEncodedBody());

        for (Signature sig : insigs.keySet()) {
            encoder.writeSignature(BytesUtils.subArray(sig.encodedBytes().toArray(), 0, 64));
        }
        if (outsig != null) {
            encoder.writeSignature(BytesUtils.subArray(outsig.encodedBytes().toArray(), 0, 64));
        }
        int length = encoder.getWriteFieldIndex();
        tempLength = length;
        int res;
        if (length == 16) {
            return encoder.toBytes();
        }
        res = 15 - length;
        for (int i = 0; i < res; i++) {
            encoder.writeField(new byte[32]);
        }
        Bytes32 nonceNotNull = Objects.requireNonNullElse(nonce, Bytes32.ZERO);
        encoder.writeField(nonceNotNull.toArray());
        return encoder.toBytes();
    }

    /**
     * without signature
     */
    private byte[] getEncodedBody() {
        SimpleEncoder encoder = new SimpleEncoder();
        encoder.writeField(getEncodedHeader());
        List<Address> all = Lists.newArrayList();
        all.addAll(inputs);
        all.addAll(outputs);
        if(txNonceField != null) {
            encoder.writeField(txNonceField.getData().reverse().toArray());
        }
        for (Address link : all) {
            encoder.writeField(link.getData().reverse().toArray());
        }
        if (info.getRemark() != null) {
            encoder.write(info.getRemark().toArray());
        }
        for (PublicKey publicKey : pubKeys) {
            byte[] pubkeyBytes = publicKey.toBytes().toArray();
            byte[] key = BytesUtils.subArray(pubkeyBytes, 1, 32);
            encoder.writeField(key);
        }
        encoded = encoder.toBytes();
        return encoded;
    }

    private byte[] getEncodedHeader() {
        //byte[] fee = BytesUtils.longToBytes(getFee(), true);
        byte[] fee = BytesUtils.longToBytes(Long.parseLong(getFee().toString()), true);
        byte[] time = BytesUtils.longToBytes(getTimestamp(), true);
        byte[] type = BytesUtils.longToBytes(getType(), true);
        byte[] transport = new byte[8];
        return BytesUtils.merge(transport, type, time, fee);
    }

    public XdagBlock getXdagBlock() {
        if (xdagBlock != null) {
            return xdagBlock;
        }
        xdagBlock = new XdagBlock(toBytes());
        return xdagBlock;
    }

    public void signIn(ECKeyPair ecKey) {
        sign(ecKey, XDAG_FIELD_SIGN_IN);
    }

    public void signOut(ECKeyPair ecKey) {
        sign(ecKey, XDAG_FIELD_SIGN_OUT);
    }

    private void sign(ECKeyPair ecKey, XdagField.FieldType type) {
        byte[] encoded = toBytes();
        // log.debug("sign encoded:{}", Hex.toHexString(encoded));
        byte[] pubkeyBytes = ecKey.getPublicKey().toBytes().toArray();
        byte[] digest = BytesUtils.merge(encoded, pubkeyBytes);
        //log.debug("sign digest:{}", Hex.toHexString(digest));
        Bytes32 hash = HashUtils.doubleSha256(Bytes.wrap(digest));
        //log.debug("sign hash:{}", Hex.toHexString(hash.toArray()));
        Signature signature = Signer.sign(hash, ecKey);
        if (type == XDAG_FIELD_SIGN_OUT) {
            outsig = signature;
        } else {
            insigs.put(signature, tempLength);
        }
    }

    /**
     * Only match input signatures and return useful keys
     */
    public List<PublicKey> verifiedKeys() {
        List<PublicKey> keys = getPubKeys();
        List<PublicKey> res = Lists.newArrayList();
        Bytes digest;
        Bytes32 hash;
        for (Signature sig : this.getInsigs().keySet()) {
            digest = getSubRawData(this.getInsigs().get(sig) - 1);
            for (PublicKey publicKey : keys) {
                byte[] pubkeyBytes = publicKey.toBytes().toArray();
                hash = HashUtils.doubleSha256(Bytes.wrap(digest, Bytes.wrap(pubkeyBytes)));
                if (Signer.verify(hash, sig, publicKey)) {
                    res.add(publicKey);
                }
            }
        }
        digest = getSubRawData(getOutsigIndex() - 2);
        for (PublicKey publicKey : keys) {
            byte[] pubkeyBytes = publicKey.toBytes().toArray();
            hash = HashUtils.doubleSha256(Bytes.wrap(digest, Bytes.wrap(pubkeyBytes)));
            if (Signer.verify(hash, this.getOutsig(), publicKey)) {
                res.add(publicKey);
            }
        }
        return res;
    }

    /**
     * Get the field index of output signature
     */
    public int getOutsigIndex() {
        int i = 1;
        long temp = this.info.getType();
        while (i < XdagBlock.XDAG_BLOCK_FIELDS && (temp & 0xf) != 5) {
            temp = temp >> 4;
            i++;
        }
        return i;
    }

    public Bytes32 getHash() {
        if (this.info.getHash() == null) {
            byte[] hash = calcHash();
            // Store full 32-byte hash
            Bytes32 fullHash = Bytes32.wrap(hash);
            // Update info with new hash
            this.info = this.info.withHash(fullHash);
        }
        return this.info.getHash();
    }

    /**
     * @deprecated Use {@link #getHash()} instead. This method now returns the same value as getHash().
     */
    @Deprecated
    public Bytes32 getHashLow() {
        return getHash();
    }

    public Signature getOutsig() {
        return outsig == null ? null : outsig;
    }

    @Override
    public String toString() {
        return String.format("Block info:[Hash:{%s}][Time:{%s}]", getHash().toHexString(),
                Long.toHexString(getTimestamp()));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Block block = (Block) o;
        return Objects.equals(getHash(), block.getHash());
    }

    @Override
    public int hashCode() {
        return Bytes.of(this.getHash().toArray()).hashCode();
    }

    public long getTimestamp() {
        return this.info.getTimestamp();
    }

    public long getType() {
        return this.info.getType();
    }

    public XAmount getFee() {
        return this.info.getFee();
    }

    /**
     * Get data of first length fields for signing
     */
    public MutableBytes getSubRawData(int length) {
        Bytes data = getXdagBlock().getData();
        MutableBytes res = MutableBytes.create(512);
        res.set(0, data.slice(0, (length + 1) * 32));
        for (int i = length + 1; i < 16; i++) {
            long type = data.getLong(8, ByteOrder.LITTLE_ENDIAN);
            byte typeB = (byte) (type >> (i << 2) & 0xf);
            if (XDAG_FIELD_SIGN_IN.asByte() == typeB || XDAG_FIELD_SIGN_OUT.asByte() == typeB) {
                continue;
            }
            res.set((i) * 32, data.slice((i) * 32, 32));
        }
        return res;
    }

    private void setType(XdagField.FieldType type, int n) {
        long typeByte = type.asByte();
        long newType = this.info.getType() | (typeByte << (n << 2));
        // Update info with new type value
        this.info = this.info.withType(newType);
    }

    public List<Address> getLinks() {
        List<Address> links = Lists.newArrayList();
        links.addAll(getInputs());
        links.addAll(getOutputs());
        return links;
    }

    // ========== Phase 2 Core Refactor: New accessors for BlockInfo ==========

    /**
     * Get new immutable BlockInfo (Phase 2 core refactor)
     * Returns the BlockInfo directly
     */
    public BlockInfo getBlockInfo() {
        return this.info;
    }

    @Override
    public Object clone() {
        Block ano = null;
        try {
            ano = (Block) super.clone();
        } catch (CloneNotSupportedException e) {
            log.error(e.getMessage(), e);
        }
        return ano;
    }
}
