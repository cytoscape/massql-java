package edu.ucsd.idekerlab.massql.spectra;

/**
 * A half-open row range {@code [start, end)}.
 *
 * <p>Half-open because that is what Java's own array conventions use, so
 * {@code for (int r = range.start(); r < range.end(); r++)} is the natural loop and there is
 * no off-by-one to get wrong at the call site. Note this is distinct from the m/z window
 * itself, whose <i>value</i> bounds are inclusive on both sides
 * ({@link SpectrumTable#mzWindow}).
 */
public record IntRange(int start, int end) {

    public static final IntRange EMPTY = new IntRange(0, 0);

    public IntRange {
        if (end < start) throw new IllegalArgumentException("end " + end + " < start " + start);
    }

    public int size() {
        return end - start;
    }

    public boolean isEmpty() {
        return end == start;
    }

    @Override
    public String toString() {
        return "[" + start + "," + end + ")";
    }
}
