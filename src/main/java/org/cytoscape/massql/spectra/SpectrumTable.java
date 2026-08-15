package org.cytoscape.massql.spectra;

/** Columnar peak store for one MS level. */
public final class SpectrumTable {
    private final double[] mz;
    private final double[] i;
    private final double[] iNorm;
    private final double[] iTicNorm;
    private final int[] scan;
    private final float[] rt;
    private final byte[] polarity;
    private final byte msLevel;
    private final ScanIndex index;

    SpectrumTable(
            double[] mz,
            double[] i,
            double[] iNorm,
            double[] iTicNorm,
            int[] scan,
            float[] rt,
            byte[] polarity,
            byte msLevel,
            ScanIndex index) {
        this.mz = mz;
        this.i = i;
        this.iNorm = iNorm;
        this.iTicNorm = iTicNorm;
        this.scan = scan;
        this.rt = rt;
        this.polarity = polarity;
        this.msLevel = msLevel;
        this.index = index;
    }

    /** An empty table. */
    public static SpectrumTable empty(int msLevel) {
        return new SpectrumTableBuilder(msLevel).build();
    }

    public int rowCount() {
        return mz.length;
    }

    public byte msLevel() {
        return msLevel;
    }

    public ScanIndex index() {
        return index;
    }

    public boolean isEmpty() {
        return mz.length == 0;
    }

    public double mz(int row) {
        return mz[row];
    }

    public double intensity(int row) {
        return i[row];
    }

    public double iNorm(int row) {
        return iNorm[row];
    }

    public double iTicNorm(int row) {
        return iTicNorm[row];
    }

    public int scanId(int row) {
        return scan[row];
    }

    public byte polarity(int row) {
        return polarity[row];
    }

    /** Row-level retention time, at float precision. */
    public float rtOfRow(int row) {
        return rt[row];
    }

    public double value(int row, Column c) {
        return switch (c) {
            case MZ -> mz[row];
            case I -> i[row];
            case I_NORM -> iNorm[row];
            case I_TIC_NORM -> iTicNorm[row];
        };
    }

    /** Rows within one scan whose m/z lies in {@code [lo, hi]} — both bounds inclusive, exactly. */
    public IntRange mzWindow(int scanOrdinal, double lo, double hi) {
        if (hi < lo) return IntRange.EMPTY;
        int from = index.rowStart(scanOrdinal);
        int to = index.rowEnd(scanOrdinal);
        if (from == to) return IntRange.EMPTY;

        int start = lowerBound(from, to, lo);
        if (start == to) return IntRange.EMPTY;
        int end = upperBound(from, to, hi);
        return start >= end ? IntRange.EMPTY : new IntRange(start, end);
    }

    /** Rows within one scan whose m/z lies in {@code (lo, hi)} — both bounds STRICT. */
    public IntRange mzWindowExclusive(int scanOrdinal, double lo, double hi) {
        if (hi <= lo) return IntRange.EMPTY;
        int from = index.rowStart(scanOrdinal);
        int to = index.rowEnd(scanOrdinal);
        if (from == to) return IntRange.EMPTY;

        int start = upperBound(from, to, lo);
        if (start == to) return IntRange.EMPTY;

        int end = lowerBound(from, to, hi);
        return start >= end ? IntRange.EMPTY : new IntRange(start, end);
    }

    /** First index in {@code [from,to)} with {@code mz >= key}. */
    private int lowerBound(int from, int to, double key) {
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (mz[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** First index in {@code [from,to)} with {@code mz > key}; the exclusive window end. */
    private int upperBound(int from, int to, double key) {
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (mz[mid] <= key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Every row, as a fresh all-set mask. */
    public RowMask allRows() {
        return RowMask.all(rowCount());
    }

    @Override
    public String toString() {
        return "SpectrumTable[msLevel="
                + msLevel
                + ", scans="
                + index.scanCount()
                + ", peaks="
                + mz.length
                + "]";
    }
}
