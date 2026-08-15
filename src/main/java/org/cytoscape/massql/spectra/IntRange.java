package org.cytoscape.massql.spectra;

/** A half-open row range {@code [start, end)}. */
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
