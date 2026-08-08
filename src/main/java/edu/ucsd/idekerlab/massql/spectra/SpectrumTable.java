package edu.ucsd.idekerlab.massql.spectra;


import java.util.Arrays;

/**
 * Columnar peak store for one MS level. Immutable once built.
 *
 * <p>This is the replacement for MassQL's pandas dataframe, and it is written rather than
 * imported because no Java dataframe library is usable here: Tablesaw pulls ~44 MB and finds
 * its I/O registry by classpath scanning (breaking {@code DEPENDENCY_POLICY.md} constraint
 * 1), and Arrow has split packages and needs {@code sun.misc.Unsafe} plus JVM flags we do not
 * control.
 *
 * <p><b>Two tables per file, not one.</b> MS1 and MS2 peaks live in separate instances,
 * mirroring MassQL's {@code ms1_df} / {@code ms2_df} split — {@code load_data()} returns
 * exactly that pair, and Tech_Step10's precursor lookup queries the MS1 table while
 * collating MS2 rows.
 *
 * <p><b>Filtering never prunes.</b> Conditions produce a {@link RowMask}; this class has no
 * "current filter" state and no method returns a smaller table. That is the seam
 * {@code OTHERSCAN} needs later — it requires a second retained index over <i>pre-filter</i>
 * MS1 data, which is free to preserve now and expensive to retrofit.
 */
public final class SpectrumTable {

    // Parallel arrays, all of length rowCount. No boxing, no per-peak objects.
    private final double[] mz;
    private final double[] i;
    private final double[] iNorm;      // i / max(i in scan)
    private final double[] iTicNorm;   // i / sum(i in scan)
    private final int[] scan;          // non-decreasing
    private final float[] rt;          // minutes; cheap row-level filtering only -- see ScanIndex
    private final byte[] polarity;
    private final byte msLevel;
    private final ScanIndex index;

    SpectrumTable(double[] mz, double[] i, double[] iNorm, double[] iTicNorm,
                  int[] scan, float[] rt, byte[] polarity, byte msLevel, ScanIndex index) {
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

    /** An empty table. Used for MGF's MS1 side, which keeps Tech_Step10 free of null checks. */
    public static SpectrumTable empty(int msLevel) {
        return new SpectrumTableBuilder(msLevel).build();
    }

    public int rowCount() { return mz.length; }
    public byte msLevel() { return msLevel; }
    public ScanIndex index() { return index; }
    public boolean isEmpty() { return mz.length == 0; }

    public double mz(int row)        { return mz[row]; }
    public double intensity(int row) { return i[row]; }
    public double iNorm(int row)     { return iNorm[row]; }
    public double iTicNorm(int row)  { return iTicNorm[row]; }
    public int scanId(int row)       { return scan[row]; }
    public byte polarity(int row)    { return polarity[row]; }

    /**
     * Row-level retention time, at <b>float</b> precision.
     *
     * <p>For cheap RT filtering only. Anything that reaches the result JSON must use
     * {@link ScanIndex#rtOf}, which is an exact double — see that class's note.
     */
    public float rtOfRow(int row) { return rt[row]; }

    public double value(int row, Column c) {
        return switch (c) {
            case MZ -> mz[row];
            case I -> i[row];
            case I_NORM -> iNorm[row];
            case I_TIC_NORM -> iTicNorm[row];
        };
    }

    /**
     * Rows within one scan whose m/z lies in {@code [lo, hi]} — <b>both bounds
     * inclusive</b>, exactly.
     *
     * <p><b>Which of the two window methods you want depends on the caller</b> (Correction C37), and the
     * distinction is not cosmetic — MassQL genuinely differs between them, both verified by execution:
     *
     * <table border="1">
     *   <caption>Bound semantics by caller</caption>
     *   <tr><th>Caller</th><th>Bound</th><th>Method</th></tr>
     *   <tr><td>Tech_Step10 precursor lookup (`massql_query.py:101-103`, {@code >=}/{@code <=})</td>
     *       <td><b>inclusive</b></td><td><b>this method</b></td></tr>
     *   <tr><td>Tech_Step9 condition windows (`msql_engine_filters.py:253` etc., {@code >}/{@code <})</td>
     *       <td><b>strict</b></td><td>{@link #mzWindowExclusive}</td></tr>
     * </table>
     *
     * <p>Two binary searches bounded to the scan's own slice, so this is O(log n) not O(n).
     * If the MGF fixture is ever slower than pandas, this method is the first place to look
     * (Tech_Step12 §5).
     *
     * <p><b>No epsilon is applied here, ever.</b> The caller computes {@code lo}/{@code hi}
     * from a tolerance; a "helpful" epsilon at this level would silently widen every tolerance
     * in the system.
     *
     * @return a half-open row range; {@link IntRange#EMPTY} if nothing matches
     */
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

    /**
     * Rows within one scan whose m/z lies in {@code (lo, hi)} — <b>both bounds STRICT</b>. A peak exactly
     * on either bound is <b>excluded</b>.
     *
     * <p>This is what Tech_Step9's condition windows require. MassQL filters with
     * {@code (df["mz"] > mz_min) & (df["mz"] < mz_max)} in all four condition functions
     * (`msql_engine_filters.py:253`, `:410`, `:493`, `:607`), and it was confirmed by execution rather than
     * inferred: {@code micro.mzML} scan 3 has a peak at exactly {@code 201.0}, and
     * {@code MS2PROD=201.5:TOLERANCEMZ=0.5} — window {@code [201.0, 202.0]} — returns <b>0 rows</b>.
     * {@code test_micro_edge.massql} pins that with an empty golden.
     *
     * <p><b>Do not "unify" this with {@link #mzWindow}.</b> Correction C37 exists because the spec assumed
     * one rule served both callers; collapsing them would silently change Tech_Step10's {@code ms1_i} and
     * {@code ms1_precmz}, which Tech_Step12 compares at 1e-9.
     *
     * <p>Implemented by shifting the inclusive bounds off the exact values: {@code upperBound(lo)} skips
     * every row equal to {@code lo}, and {@code lowerBound(hi)} stops before every row equal to {@code hi}.
     * That is exact — no epsilon, and correct in the presence of duplicate m/z.
     *
     * @return a half-open row range; {@link IntRange#EMPTY} if nothing matches
     */
    public IntRange mzWindowExclusive(int scanOrdinal, double lo, double hi) {
        if (hi <= lo) return IntRange.EMPTY;
        int from = index.rowStart(scanOrdinal);
        int to = index.rowEnd(scanOrdinal);
        if (from == to) return IntRange.EMPTY;

        // upperBound(lo) = first row with mz > lo  -> excludes rows equal to lo
        int start = upperBound(from, to, lo);
        if (start == to) return IntRange.EMPTY;
        // lowerBound(hi) = first row with mz >= hi -> excludes rows equal to hi
        int end = lowerBound(from, to, hi);
        return start >= end ? IntRange.EMPTY : new IntRange(start, end);
    }

    /**
     * First index in {@code [from,to)} with {@code mz >= key}.
     *
     * <p>Hand-rolled rather than {@link Arrays#binarySearch} because that method's behaviour
     * on <b>duplicate keys is unspecified</b> — it may return any matching index. Duplicate
     * m/z values do occur in real centroided data, and a window that starts at an arbitrary
     * one of them would silently drop peaks.
     */
    private int lowerBound(int from, int to, double key) {
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (mz[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** First index in {@code [from,to)} with {@code mz > key}; the exclusive window end. */
    private int upperBound(int from, int to, double key) {
        int lo = from, hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (mz[mid] <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Every row, as a fresh all-set mask. */
    public RowMask allRows() { return RowMask.all(rowCount()); }

    @Override public String toString() {
        return "SpectrumTable[msLevel=" + msLevel + ", scans=" + index.scanCount()
                + ", peaks=" + mz.length + "]";
    }
}
