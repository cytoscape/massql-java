package edu.ucsd.idekerlab.massql.spectra;

import edu.ucsd.idekerlab.massql.MassqlException;

import java.util.BitSet;

/**
 * An immutable set of selected rows.
 *
 * <p><b>Immutable on purpose.</b> {@link #and}, {@link #or} and {@link #not} return new
 * instances rather than mutating in place. Tech_Step9 composes several conditions, and a mask
 * mutated under one condition while another still holds a reference is a subtle
 * wrong-answer bug with no exception to point at it.
 *
 * <p>Producing masks rather than pruned tables is also what preserves the {@code OTHERSCAN}
 * seam — see {@link SpectrumTable}.
 */
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

    /** A mask with exactly the given half-open range set. */
    public static RowMask ofRange(int length, IntRange range) {
        BitSet b = new BitSet(length);
        if (!range.isEmpty()) b.set(range.start(), range.end());
        return new RowMask(b, length);
    }

    public int length() { return length; }
    public boolean get(int row) { return bits.get(row); }
    public int cardinality() { return bits.cardinality(); }
    public boolean isEmpty() { return bits.isEmpty(); }

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

    /** Set a range, returning a new mask. Convenience for accumulating window hits. */
    public RowMask withRange(IntRange range) {
        if (range.isEmpty()) return this;
        BitSet b = copyBits();
        b.set(range.start(), range.end());
        return new RowMask(b, length);
    }

    /** Next set row at or after {@code from}, or {@code -1}. Lets callers iterate without scanning. */
    public int nextSetRow(int from) {
        return bits.nextSetBit(from);
    }

    /**
     * Ordinals of scans retaining at least one selected row.
     *
     * <p>This is the shape most MassQL conditions actually need: they mean "this scan contains
     * a peak matching X", not "this row matches X". Tech_Step9 §1 combines conditions by
     * intersecting these scan sets, because two conditions may be satisfied by <i>different</i>
     * peaks in the same scan and a row-level AND would wrongly reject it.
     */
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

    private BitSet copyBits() { return (BitSet) bits.clone(); }

    private RowMask requireSameLength(RowMask other) {
        if (other.length != length) {
            throw new MassqlException("mask length mismatch: " + length + " vs " + other.length);
        }
        return other;
    }

    @Override public String toString() {
        return "RowMask[" + cardinality() + "/" + length + "]";
    }
}
