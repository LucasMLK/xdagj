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
package io.xdag.net.message.consensus;

import io.xdag.core.ChainStats;
import io.xdag.net.message.Message;
import io.xdag.net.message.MessageCode;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.Numeric;
import io.xdag.utils.SimpleDecoder;
import io.xdag.utils.SimpleEncoder;
import java.math.BigInteger;
import lombok.Getter;
import lombok.Setter;
import org.apache.tuweni.bytes.Bytes32;

@Getter
@Setter
public abstract class XdagMessage extends Message  {

    protected long starttime;

    protected long endtime;

    protected long random;

    protected Bytes32 hash;

    protected ChainStats chainStats;

    public XdagMessage(MessageCode code, Class<?> responseMessageClass, byte[] body) {
        super(code, responseMessageClass);
        this.body = body;
        decode();
    }

    public XdagMessage(MessageCode code, Class<?> responseMessageClass, long starttime, long endtime, long random, ChainStats chainStats) {
        super(code, responseMessageClass);

        this.starttime = starttime;
        this.endtime = endtime;
        this.random = random;
        this.chainStats = chainStats;

        this.hash = Bytes32.ZERO;
        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    public XdagMessage(MessageCode code, Class<?> responseMessageClass, long starttime, long endtime, Bytes32 hash, ChainStats chainStats) {
        super(code, responseMessageClass);

        this.starttime = starttime;
        this.endtime = endtime;
        this.hash = hash;
        this.chainStats = chainStats;

        SimpleEncoder enc = encode();
        this.body = enc.toBytes();
    }

    protected SimpleEncoder encode() {
        SimpleEncoder enc = new SimpleEncoder();

        enc.writeLong(starttime);
        enc.writeLong(endtime);
        enc.writeLong(random);
        enc.writeBytes(hash.toArray());

        // Encode ChainStats to network format (Phase 7.3: XdagStats deleted)
        enc.writeBytes(BytesUtils.bigIntegerToBytes(chainStats.getMaxDifficulty().toBigInteger(), 16, false));

        enc.writeLong(chainStats.getTotalBlockCount());
        enc.writeLong(Math.max(chainStats.getTotalMainBlockCount(), chainStats.getMainBlockCount()));
        enc.writeInt(chainStats.getTotalHostCount());
        enc.writeLong(0);  // maintime - deleted in Phase 7.3, write 0 for network compatibility
        return enc;
    }

    protected SimpleDecoder decode() {
        SimpleDecoder dec = new SimpleDecoder(this.body);

        this.starttime = dec.readLong();
        this.endtime = dec.readLong();
        this.random = dec.readLong();
        this.hash = Bytes32.wrap(dec.readBytes());

        // Decode network format to ChainStats (Phase 7.3: XdagStats deleted)
        BigInteger maxdifficulty = Numeric.toBigInt(dec.readBytes());
        long totalnblocks = dec.readLong();
        long totalnmains = dec.readLong();
        int totalnhosts = dec.readInt();
        long maintime = dec.readLong();  // Read but discard (deleted in Phase 7.3)

        // Create ChainStats from network data
        // Note: We only have network-wide stats from peers, so set local stats to same values
        chainStats = ChainStats.builder()
                .difficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(maxdifficulty))
                .maxDifficulty(org.apache.tuweni.units.bigints.UInt256.valueOf(maxdifficulty))
                .mainBlockCount(totalnmains)
                .totalMainBlockCount(totalnmains)
                .totalBlockCount(totalnblocks)
                .totalHostCount(totalnhosts)
                .waitingSyncCount(0)  // Not transmitted in network protocol
                .noRefCount(0)        // Not transmitted in network protocol
                .extraCount(0)        // Not transmitted in network protocol
                .balance(io.xdag.core.XAmount.ZERO)  // Not transmitted in network protocol
                .build();
        return dec;
    }

}
