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

import lombok.Value;
import org.apache.tuweni.bytes.Bytes32;

import java.io.Serializable;

/**
 * Immutable representation of a link between blocks in the DAG
 *
 * BlockLink represents a directed edge in the XDAG DAG structure.
 * Each link has a type (INPUT, OUTPUT, or REFERENCE) and optionally carries an amount.
 *
 * This is a key component that makes XDAG a DAG-based blockchain.
 */
@Value
public class BlockLink implements Serializable {

    /**
     * Hash of the target block this link points to
     */
    Bytes32 targetHash;

    /**
     * Type of the link (INPUT/OUTPUT/REFERENCE)
     */
    LinkType linkType;

    /**
     * Amount transferred (for INPUT/OUTPUT links), null for REFERENCE links
     */
    XAmount amount;

    /**
     * Remark/memo for this link (optional)
     */
    String remark;

    /**
     * Create a simple reference link (no amount)
     */
    public static BlockLink reference(Bytes32 targetHash) {
        return new BlockLink(targetHash, LinkType.REFERENCE, null, null);
    }

    /**
     * Create an input link with amount
     */
    public static BlockLink input(Bytes32 targetHash, XAmount amount) {
        return new BlockLink(targetHash, LinkType.INPUT, amount, null);
    }

    /**
     * Create an input link with amount and remark
     */
    public static BlockLink input(Bytes32 targetHash, XAmount amount, String remark) {
        return new BlockLink(targetHash, LinkType.INPUT, amount, remark);
    }

    /**
     * Create an output link with amount
     */
    public static BlockLink output(Bytes32 targetHash, XAmount amount) {
        return new BlockLink(targetHash, LinkType.OUTPUT, amount, null);
    }

    /**
     * Create an output link with amount and remark
     */
    public static BlockLink output(Bytes32 targetHash, XAmount amount, String remark) {
        return new BlockLink(targetHash, LinkType.OUTPUT, amount, remark);
    }

    /**
     * Check if this link carries an amount
     */
    public boolean hasAmount() {
        return amount != null && !amount.isZero();
    }

    /**
     * Check if this link has a remark
     */
    public boolean hasRemark() {
        return remark != null && !remark.isEmpty();
    }

    /**
     * Check if this is an input link
     */
    public boolean isInput() {
        return linkType == LinkType.INPUT;
    }

    /**
     * Check if this is an output link
     */
    public boolean isOutput() {
        return linkType == LinkType.OUTPUT;
    }

    /**
     * Check if this is a reference link
     */
    public boolean isReference() {
        return linkType == LinkType.REFERENCE;
    }

    /**
     * Get the direction of this link (-1 for input, +1 for output, 0 for reference)
     */
    public int getDirection() {
        return switch (linkType) {
            case INPUT -> -1;
            case OUTPUT -> 1;
            case REFERENCE -> 0;
        };
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("BlockLink[");
        sb.append("type=").append(linkType);
        sb.append(", target=").append(targetHash.toHexString(), 0, 16).append("...");
        if (hasAmount()) {
            sb.append(", amount=").append(amount.toDecimal(9, XUnit.XDAG).toPlainString());
        }
        if (hasRemark()) {
            sb.append(", remark='").append(remark).append("'");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Link type enumeration
     */
    public enum LinkType {
        /**
         * Input link - receiving funds/reference from another block
         */
        INPUT,

        /**
         * Output link - sending funds to another block
         */
        OUTPUT,

        /**
         * Reference link - DAG structure reference (no amount transfer)
         */
        REFERENCE;

        /**
         * Convert to byte representation (for serialization)
         */
        public byte toByte() {
            return (byte) ordinal();
        }

        /**
         * Create from byte representation (for deserialization)
         */
        public static LinkType fromByte(byte b) {
            return switch (b) {
                case 0 -> INPUT;
                case 1 -> OUTPUT;
                case 2 -> REFERENCE;
                default -> throw new IllegalArgumentException("Invalid LinkType byte: " + b);
            };
        }
    }
}
