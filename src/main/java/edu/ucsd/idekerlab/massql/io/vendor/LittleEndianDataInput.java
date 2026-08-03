package edu.ucsd.idekerlab.massql.io.vendor;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Little-endian primitive reads over an {@link InputStream}.
 *
 * <p><b>Not vendored — written for this project</b> as a drop-in replacement for Guava's
 * {@code com.google.common.io.LittleEndianDataInputStream}, which {@code MzMLPeaksDecoder}
 * used. Guava cannot be a dependency here (Correction C16: it is unavoidable via
 * {@code msdk-datamodel}, costs 2.85 MB, and Cytoscape exports version 9.0.0 against MSDK's
 * 27.1, so an embedded copy makes bnd emit an {@code Import-Package} Felix cannot satisfy).
 *
 * <p>Only the four methods {@code MzMLPeaksDecoder} actually calls are implemented —
 * {@link #readInt}, {@link #readLong}, {@link #readFloat}, {@link #readDouble}. Anything else
 * would be untested code.
 *
 * <p>Extends {@link FilterInputStream} so {@code readAllBytes()} comes for free; that is what
 * replaces the {@code commons-io} {@code IOUtils.toByteArray(dis)} calls in the decoder.
 *
 * <p><b>Little-endian is not a detail here.</b> mzML binary arrays are little-endian while
 * mzXML's are big-endian ("network"), which is why the two readers must never share a
 * configured buffer (Tech_Step6 §5).
 */
public final class LittleEndianDataInput extends FilterInputStream {

    public LittleEndianDataInput(InputStream in) {
        super(new DataInputStream(in));
    }

    /** Reads exactly {@code n} bytes or throws, mirroring {@code DataInput} semantics. */
    private void readFully(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new EOFException("expected " + b.length + " bytes, got " + off);
            off += n;
        }
    }

    private final byte[] buf8 = new byte[8];

    public int readInt() throws IOException {
        readFully4();
        return (buf8[0] & 0xFF)
                | ((buf8[1] & 0xFF) << 8)
                | ((buf8[2] & 0xFF) << 16)
                | ((buf8[3] & 0xFF) << 24);
    }

    public long readLong() throws IOException {
        readFully(buf8);
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (buf8[i] & 0xFFL);
        }
        return v;
    }

    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    private void readFully4() throws IOException {
        int off = 0;
        while (off < 4) {
            int n = in.read(buf8, off, 4 - off);
            if (n < 0) throw new EOFException("expected 4 bytes, got " + off);
            off += n;
        }
    }
}
