package org.cytoscape.massql.spectra;

import java.util.BitSet;

import org.cytoscape.massql.MassqlException;

/** An immutable set of selected rows. */
public final class RowMask {
    private final BitSet bits;
    private final int length;

    private RowMask(BitSet bits, int length) {
        this.bits = bits;
        this.length = length;
    }

    public static RowMask all(int length) {
        BitSet b = new BitSet(length);
        b.set(0, length);
        return new RowMask(b, length);
    }

    public static RowMask none(int length) {
        return new RowMask(new BitSet(length), length);
    }

    public int length() {
        return length;
    }

    public boolean get(int row) {
        return bits.get(row);
    }

    public int cardinality() {
        return bits.cardinality();
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public RowMask and(RowMask other) {
        BitSet b = copyBits();
        b.and(requireSameLength(other).bits);
        return new RowMask(b, length);
    }

    public RowMask or(RowMask other) {
        BitSet b = copyBits();
        b.or(requireSameLength(other).bits);
        return new RowMask(b, length);
    }

    public RowMask not() {
        BitSet b = copyBits();
        b.flip(0, length);
        return new RowMask(b, length);
    }

    /** Set a range, returning a new mask. */
    public RowMask withRange(IntRange range) {
        if (range.isEmpty()) return this;
        BitSet b = copyBits();
        b.set(range.start(), range.end());
        return new RowMask(b, length);
    }

    /** Next set row at or after {@code from}, or {@code -1}. */
    public int nextSetRow(int from) {
        return bits.nextSetBit(from);
    }

    /** Ordinals of scans retaining at least one selected row. */
    public java.util.BitSet scansWithAnyRow(SpectrumTable table) {
        ScanIndex idx = table.index();
        BitSet scans = new BitSet(idx.scanCount());
        for (int s = 0; s < idx.scanCount(); s++) {
            int from = idx.rowStart(s), to = idx.rowEnd(s);
            int hit = bits.nextSetBit(from);
            if (hit >= 0 && hit < to) scans.set(s);
        }
        return scans;
    }

    private BitSet copyBits() {
        return (BitSet) bits.clone();
    }

    private RowMask requireSameLength(RowMask other) {
        if (other.length != length) {
            throw new MassqlException("mask length mismatch: " + length + " vs " + other.length);
        }
        return other;
    }

    @Override
    public String toString() {
        return "RowMask[" + cardinality() + "/" + length + "]";
    }
}
