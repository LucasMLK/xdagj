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

import io.xdag.crypto.encoding.Base58;
import io.xdag.utils.BytesUtils;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.MutableBytes32;
import org.apache.tuweni.units.bigints.UInt64;

/**
 * Legacy Address class for block/transaction references with amount (v1.0 architecture)
 *
 * @deprecated As of v5.1 refactor (Phase 6.5 - Deep Core Cleanup), this class represents
 *             legacy Address-based block references that include both hash and amount.
 *             In v5.1, the {@link Link} class is used for references (33 bytes vs ~64 bytes).
 *
 *             <p><b>Why Deprecated:</b>
 *             <ul>
 *             <li><b>Memory Inefficiency:</b> Address uses ~64 bytes per reference (hash 32 + amount 8 + metadata 24).
 *                 Link uses only 33 bytes (hash 32 + type 1), saving 48% memory.</li>
 *             <li><b>Mutable Design:</b> Address uses MutableBytes32 and has mutable fields.
 *                 Link is immutable and thread-safe.</li>
 *             <li><b>Embedded Amount:</b> Address stores amount in the reference itself.
 *                 In v5.1, amounts are stored in Transaction objects, not in links.</li>
 *             <li><b>Complex Parsing:</b> Address requires parse() method and getData() serialization.
 *                 Link has simple, direct construction.</li>
 *             <li><b>Tied to XdagField:</b> Address is tightly coupled with XdagField.FieldType.
 *                 Link uses its own simpler Link.Type enum.</li>
 *             </ul>
 *
 *             <p><b>v5.1 Replacement: {@link Link}</b>
 *             <pre>{@code
 * // Legacy Address (~64 bytes)
 * Address addr = new Address(hash, XdagField.FieldType.XDAG_FIELD_OUT, amount, false);
 * MutableBytes32 addressHash = addr.getAddress();  // Requires parsing
 * XAmount amount = addr.getAmount();
 *
 * // v5.1 Link (33 bytes)
 * Link link = Link.toTransaction(txHash);  // or Link.toBlock(blockHash)
 * Bytes32 hash = link.getHash();  // Direct access, no parsing
 * // Amount stored in Transaction object, not in link
 *             }</pre>
 *
 *             <p><b>Size Comparison:</b>
 *             <table border="1">
 *             <tr><th>Class</th><th>Size</th><th>Fields</th></tr>
 *             <tr><td><b>Address</b></td><td>~64 bytes</td>
 *                 <td>hash (32) + amount (8) + type + flags + metadata</td></tr>
 *             <tr><td><b>Link</b></td><td>33 bytes</td>
 *                 <td>hash (32) + type (1)</td></tr>
 *             <tr><td><b>Savings</b></td><td><b>-48%</b></td><td>Simpler, faster ✅</td></tr>
 *             </table>
 *
 *             <p><b>Architecture Change:</b>
 *             <br>In legacy Block architecture, references (Address objects) contain amount information.
 *             This couples the DAG structure with transaction amounts.
 *
 *             <br>In BlockV5 architecture, Link objects only contain references (hash + type).
 *             Transaction amounts are stored in Transaction objects, achieving clear separation.
 *
 *             <pre>{@code
 * // Legacy: Amount in reference
 * Block block = ...;
 * for (Address input : block.getInputs()) {
 *     Bytes32 hash = input.getAddress();
 *     XAmount amount = input.getAmount();  // Amount in Address
 * }
 *
 * // v5.1: Amount in Transaction
 * BlockV5 block = ...;
 * for (Link link : block.getLinks()) {
 *     if (link.getType() == Link.Type.TO_TRANSACTION) {
 *         Transaction tx = transactionStore.get(link.getHash());
 *         XAmount amount = tx.getAmount();  // Amount in Transaction
 *     }
 * }
 *             }</pre>
 *
 *             <p><b>Migration Path:</b>
 *             <pre>{@code
 * // Legacy: Create Address for block reference
 * Address blockRef = new Address(blockHash, XdagField.FieldType.XDAG_FIELD_OUT, false);
 * Address txRef = new Address(txHash, XdagField.FieldType.XDAG_FIELD_OUTPUT, amount, true);
 *
 * // v5.1: Create Link for reference (no amount)
 * Link blockLink = Link.toBlock(blockHash);
 * Link txLink = Link.toTransaction(txHash);
 * // Amount stored separately in Transaction object
 *             }</pre>
 *
 *             <p><b>Current Usage:</b>
 *             <br>Address is only used in 3 test files:
 *             <ul>
 *             <li>BlockBuilder.java (test utility)</li>
 *             <li>CommandsTest.java (unit test)</li>
 *             <li>TransactionHistoryStoreImplTest.java (unit test)</li>
 *             </ul>
 *
 *             <p><b>Performance Impact:</b>
 *             <br>Replacing Address with Link enables:
 *             <ul>
 *             <li>48% memory savings per reference</li>
 *             <li>1,485,000 links per 48MB block (vs ~750K with Address)</li>
 *             <li>Simpler, faster parsing (no getData() serialization)</li>
 *             <li>Thread-safe immutable design</li>
 *             </ul>
 *
 *             <p><b>Related Deprecations:</b>
 *             <ul>
 *             <li>{@link Block} - Uses Address objects, replaced by BlockV5 with Link</li>
 *             <li>{@link XdagField} - FieldType enum coupled with Address</li>
 *             <li>{@link XdagBlock} - 512-byte format with Address-based fields</li>
 *             </ul>
 *
 * @see Link
 * @see Link.Type
 * @see BlockV5
 * @see Transaction
 */
@Deprecated(since = "0.8.1", forRemoval = true)
public class Address {

    /**
     * Data to be placed in the field in normal order
     */
    protected MutableBytes32 data;
    
    /**
     * Field type: input/output/output without amount
     */
    @Getter
    @Setter
    protected XdagField.FieldType type;
    
    /**
     * Transfer amount (input or output)
     */
    protected XAmount amount = XAmount.ZERO;
    
    /**
     * Lower 192 bits of address hash
     */
    protected MutableBytes32 addressHash;

    /**
     * Flag indicating if this is an address
     */
    protected boolean isAddress;

    /**
     * Flag indicating if the address has been parsed
     */
    protected boolean parsed = false;

    public Address(XdagField field, Boolean isAddress) {
        this.isAddress = isAddress;
        this.type = field.getType();
        this.data = MutableBytes32.wrap(field.getData().reverse().mutableCopy());
        parse();
    }

    /**
     * Constructor used only for ref and maxdifflink
     */
    public Address(Bytes32 hash, boolean isAddress) {
        this.isAddress = isAddress;
        this.type = XdagField.FieldType.XDAG_FIELD_OUT;
        addressHash = MutableBytes32.create();
        if(!isAddress){
            this.addressHash = hash.mutableCopy();
        }else {
            this.addressHash.set(8,hash.mutableCopy().slice(8,20));
        }
        this.amount = XAmount.ZERO;
        parsed = true;
    }

    /**
     * Constructor used only for ref and maxdifflink
     */
    public Address(Block block) {
        this.isAddress = false;
        this.addressHash = block.getHash().mutableCopy();
        parsed = true;
    }

    public Address(Bytes32 blockHash, XdagField.FieldType type, Boolean isAddress) {
        this.isAddress = isAddress;
        this.type = type;
        this.addressHash = MutableBytes32.create();

        // Set address/hash (keeping full 32 bytes in addressHash)
        if(!isAddress){
            this.addressHash = blockHash.mutableCopy();
        }else {
            this.addressHash.set(8, blockHash.slice(8, 20));
        }

        // Amount is ZERO for this constructor (used for block references without amount)
        this.amount = XAmount.ZERO;
        this.parsed = true;
    }

    public Address(Bytes32 hash, XdagField.FieldType type, XAmount amount, Boolean isAddress) {
        this.isAddress = isAddress;
        this.type = type;
        if(!isAddress){
            this.addressHash = hash.mutableCopy();
        }else {
            this.addressHash = MutableBytes32.create();
            this.addressHash.set(8,hash.mutableCopy().slice(8,20));
        }
        this.amount = amount;
        parsed = true;
    }

    public Bytes getData() {
        if (this.data == null) {
            this.data = MutableBytes32.create();
            if(!this.isAddress){
                this.data.set(8, this.addressHash.slice(8, 24));
            }else {
                this.data.set(8, this.addressHash.slice(8,20));
            }
            UInt64 u64v = amount.toXAmount();
            this.data.set(0, Bytes.wrap(BytesUtils.bigIntegerToBytes(u64v,8)));
        }
        return this.data;
    }

    public void parse() {
        if (!parsed) {
            if(!isAddress){
                this.addressHash = MutableBytes32.create();
                this.addressHash.set(8, this.data.slice(8, 24));
            }else {
                this.addressHash = MutableBytes32.create();
                this.addressHash.set(8,this.data.slice(8,20));
            }
            UInt64 u64v = UInt64.fromBytes(this.data.slice(0, 8));
            this.amount = XAmount.ofXAmount(u64v.toLong());
            this.parsed = true;
        }
    }

    public XAmount getAmount() {
        parse();
        return this.amount;
    }

    public MutableBytes32 getAddress() {
        parse();
        return this.addressHash;
    }

    public boolean getIsAddress() {
        parse();
        return this.isAddress;
    }

    @Override
    public String toString() {
        if(isAddress){
            return "Address [" + Base58.encodeCheck(addressHash.slice(8,20)) + "]";
        }else {
            return "Block Hash[" + addressHash.toHexString() + "]";
        }
    }
}
