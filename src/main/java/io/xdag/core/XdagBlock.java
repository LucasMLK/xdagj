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

import static io.xdag.core.XdagField.FieldType.fromByte;

import java.nio.ByteOrder;
import org.apache.tuweni.bytes.MutableBytes;
import org.apache.tuweni.bytes.MutableBytes32;

import lombok.Getter;

/**
 * Legacy 512-byte block serialization format (v1.0 architecture)
 *
 * @deprecated As of v5.1 refactor (Phase 6.5 - Deep Core Cleanup), this class represents
 *             the legacy fixed 512-byte block serialization format.
 *             In v5.1, BlockV5 uses variable-size serialization supporting up to 48MB blocks.
 *
 *             <p><b>Why Deprecated:</b>
 *             <ul>
 *             <li><b>Fixed Size Limitation:</b> XdagBlock is always 512 bytes (16 fields × 32 bytes).
 *                 This limits blocks to ~15 references maximum. BlockV5 supports up to 48MB with
 *                 1,485,000 links.</li>
 *             <li><b>Tight Coupling:</b> XdagBlock is tightly coupled with {@link Block} class parsing.
 *                 BlockV5 has cleaner separation between structure and serialization.</li>
 *             <li><b>Complex Field Handling:</b> Uses {@link XdagField} array with type encoding in header.
 *                 BlockV5 uses direct Link-based references with simpler serialization.</li>
 *             <li><b>Only Used by Deprecated Messages:</b> Primary usage is in deprecated
 *                 NewBlockMessage and SyncBlockMessage (Phase 5.1). BlockV5 messages don't use XdagBlock.</li>
 *             </ul>
 *
 *             <p><b>v5.1 Replacement: BlockV5 Serialization</b>
 *             <pre>{@code
 * // Legacy: Fixed 512-byte format
 * XdagBlock xdagBlock = new XdagBlock(data);  // Must be exactly 512 bytes
 * XdagField[] fields = xdagBlock.getFields();  // 16 fields max
 * Block block = new Block(xdagBlock);  // Parse into Block
 *
 * // v5.1: Variable-size serialization
 * BlockV5 block = BlockV5.builder()
 *     .header(header)
 *     .links(links)  // Can have 1,485,000 links
 *     .build();
 * byte[] serialized = BlockV5Serializer.serialize(block);  // Variable size up to 48MB
 * BlockV5 deserialized = BlockV5Serializer.deserialize(serialized);
 *             }</pre>
 *
 *             <p><b>Size Comparison:</b>
 *             <table border="1">
 *             <tr><th>Format</th><th>Size</th><th>Max References</th><th>Scalability</th></tr>
 *             <tr><td><b>XdagBlock</b></td><td>512 bytes (fixed)</td><td>~15</td>
 *                 <td>Limited ❌</td></tr>
 *             <tr><td><b>BlockV5</b></td><td>Variable (up to 48MB)</td><td>1,485,000</td>
 *                 <td>Highly scalable ✅</td></tr>
 *             </table>
 *
 *             <p><b>Current Usage (3 files):</b>
 *             <ul>
 *             <li>{@link io.xdag.net.message.consensus.NewBlockMessage} - ⚠️ Deprecated (Phase 5.1)</li>
 *             <li>{@link io.xdag.net.message.consensus.SyncBlockMessage} - ⚠️ Deprecated (Phase 5.1)</li>
 *             <li>BlockStoreImplTest.java - Test file</li>
 *             </ul>
 *
 *             <p><b>Structure:</b>
 *             <pre>
 * XdagBlock Format (512 bytes):
 * ┌─────────────────────────────────────┐
 * │ Field 0: Header (32 bytes)          │  Transport (8) + Type (8) + Time (8) + Fee (8)
 * ├─────────────────────────────────────┤
 * │ Field 1-14: Data fields (448 bytes) │  Inputs, Outputs, Keys, Signatures, Remark
 * ├─────────────────────────────────────┤
 * │ Field 15: Nonce (32 bytes)          │  Mining nonce
 * └─────────────────────────────────────┘
 *
 * BlockV5 Format (variable size):
 * ┌─────────────────────────────────────┐
 * │ BlockHeader (104 bytes)             │  Timestamp, Difficulty, Nonce, Coinbase, Hash
 * ├─────────────────────────────────────┤
 * │ Links (33 bytes each, up to 1.4M)  │  Link(hash 32 + type 1)
 * └─────────────────────────────────────┘
 *             </pre>
 *
 *             <p><b>Why 512 Bytes is Limiting:</b>
 *             <br>With 16 fields × 32 bytes = 512 bytes:
 *             <ul>
 *             <li>Field 0: Header (required)</li>
 *             <li>Field 15: Nonce (mining blocks)</li>
 *             <li>Fields 1-14: Available for data</li>
 *             <li>After keys + signatures + remark: ~5-10 refs max</li>
 *             <li><b>Result:</b> Severely limits transaction capacity</li>
 *             </ul>
 *
 *             <br>BlockV5 with 48MB:
 *             <ul>
 *             <li>Header: 104 bytes (one-time)</li>
 *             <li>Each link: 33 bytes</li>
 *             <li>Available: ~48MB - 104 bytes</li>
 *             <li><b>Result:</b> (48MB - 104) / 33 = 1,485,000 links</li>
 *             </ul>
 *
 *             <p><b>Migration Path:</b>
 *             <br>XdagBlock is primarily used for:
 *             <ol>
 *             <li><b>Network Messages:</b> NewBlockMessage/SyncBlockMessage already deprecated (Phase 5.1).
 *                 Use NewBlockV5Message/SyncBlockV5Message instead.</li>
 *             <li><b>Block Parsing:</b> Block.java constructor takes XdagBlock.
 *                 Block.java also deprecated (Phase 6.5). Use BlockV5 directly.</li>
 *             <li><b>Storage:</b> Eventually will migrate to BlockV5-only storage (Phase 7).</li>
 *             </ol>
 *
 *             <p><b>Performance Impact:</b>
 *             <br>Removing XdagBlock limitation enables:
 *             <ul>
 *             <li>23,200 TPS (vs ~100 TPS with 512-byte blocks)</li>
 *             <li>1,485,000 transactions per block (vs ~15 max)</li>
 *             <li>48MB block capacity (vs 512 bytes)</li>
 *             <li>Variable-size encoding (more efficient)</li>
 *             </ul>
 *
 *             <p><b>Related Deprecations:</b>
 *             <ul>
 *             <li>{@link Block} - Uses XdagBlock for parsing</li>
 *             <li>{@link XdagField} - Field types used by XdagBlock</li>
 *             <li>{@link Address} - Address-based references in XdagBlock fields</li>
 *             <li>{@link io.xdag.net.message.consensus.NewBlockMessage} - Uses XdagBlock</li>
 *             <li>{@link io.xdag.net.message.consensus.SyncBlockMessage} - Uses XdagBlock</li>
 *             </ul>
 *
 * @see BlockV5
 * @see BlockHeader
 * @see Link
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public class XdagBlock {

    public static final int XDAG_BLOCK_FIELDS = 16;

    /**
     * Block data with signature
     */
    private MutableBytes data;
    /**
     * -- GETTER --
     * Get block sums
     */
    @Getter
    private long sum;
    private XdagField[] fields;

    public XdagBlock() {
        fields = new XdagField[XDAG_BLOCK_FIELDS];
    }

    public XdagBlock(XdagField[] fields) {
        this.fields = fields;
    }

    public XdagBlock(byte[] data) {
        this(MutableBytes.wrap(data));
    }

    public XdagBlock(MutableBytes data) {
        this.data = data;
        if (data != null && data.size() == 512) {
            fields = new XdagField[XDAG_BLOCK_FIELDS];
            for (int i = 0; i < XDAG_BLOCK_FIELDS; i++) {
                MutableBytes32 fieldBytes = MutableBytes32.create();
                fieldBytes.set(0, data.slice(i * 32, 32));
                fields[i] = new XdagField(fieldBytes);
                fields[i].setType(fromByte(getMsgCode(i)));
            }
            for (int i = 0; i < XDAG_BLOCK_FIELDS; i++) {
                sum += fields[i].getSum();
                fields[i].setType(fromByte(getMsgCode(i)));
            }
        }
    }

    public byte getMsgCode(int n) {
        long type = this.data.getLong(8, ByteOrder.LITTLE_ENDIAN);
        return (byte) (type >> (n << 2) & 0xf);
    }

    public XdagField[] getFields() {
        if (this.fields == null) {
            throw new Error("no fields");
        } else {
            return this.fields;
        }
    }

    public XdagField getField(int number) {
        XdagField[] fields = getFields();
        return fields[number];
    }

    public MutableBytes getData() {
        if (this.data == null) {
            this.data = MutableBytes.create(512);
            for (int i = 0; i < XDAG_BLOCK_FIELDS; i++) {
                sum += fields[i].getSum();
                int index = i * 32;
                this.data.set(index, fields[i].getData().reverse());
            }
        }
        return data;
    }

}
