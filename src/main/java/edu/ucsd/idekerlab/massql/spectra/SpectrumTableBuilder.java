package edu.ucsd.idekerlab.massql.spectra;

import java.util.Arrays;

import edu.ucsd.idekerlab.massql.MassqlException;

/**
 * Append-then-freeze construction of a {@link SpectrumTable}.
 *
 * <p>Usage: {@link #startScan} once per spectrum, then {@link #addPeak} per peak, then
 * {@link #build}. Readers stream files in document order, which is exactly the order this
 * expects, so nothing needs buffering or sorting at the reader level.
 *
 * <p>The derived columns ({@code iNorm}, {@code iTicNorm}) and the scan index are computed in
 * {@link #build}, in a single pass per scan.
 *
 * <p><b>Invariants are checked, not assumed</b>, and violations throw {@link MassqlException}
 * rather than {@code AssertionError} — they indicate a reader bug and must be visible in a
 * release build, not only when assertions happen to be enabled.
 */
public final class SpectrumTableBuilder {

    /**
     * Default peak capacity.
     *
     * <p>Deliberately small. Under the streaming design (Correction C22) this builder runs once
     * <b>per scan</b>, not once per file, so an over-large default is pure allocation churn:
     * at 1024 it cost 16 KB of arrays for a 22-peak MGF spectrum, which measured as ~190 MB of
     * garbage across PlusRise.mgf's 34,513 scans. Readers that know the peak count up front should
     * use {@link #SpectrumTableBuilder(int, int)} and avoid growth entirely.
     */
    private static final int DEFAULT_PEAK_CAPACITY = 32;

    private final byte msLevel;

    private double[] mz;
    private double[] in;
    private int[] scan;
    private int rows = 0;

    // Scan-level metadata, one entry per startScan() call.
    // Sized for the streaming case: usually exactly one scan per builder. Grows for bulk use.
    private int[] sIds = new int[4];
    private int[] sStart = new int[4];
    private double[] sRt = new double[4];
    private byte[] sPol = new byte[4];
    private double[] sPrecmz = new double[4];
    private int[] sMs1scan = new int[4];
    private int[] sCharge = new int[4];
    private int scans = 0;

    private boolean built = false;

    public SpectrumTableBuilder(int msLevel) {
        this(msLevel, DEFAULT_PEAK_CAPACITY);
    }

    /**
     * @param expectedPeaks capacity hint. Sizing it exactly — mzML's {@code defaultArrayLength}, or
     *                      MGF's counted peaks — means one allocation per array and no copy on
     *                      {@code build()}. A wrong hint is only a performance matter; the builder
     *                      still grows as needed.
     */
    public SpectrumTableBuilder(int msLevel, int expectedPeaks) {
        if (msLevel != 1 && msLevel != 2) {
            throw new MassqlException("msLevel must be 1 or 2, got " + msLevel);
        }
        this.msLevel = (byte) msLevel;
        int cap = Math.max(1, expectedPeaks);
        this.mz = new double[cap];
        this.in = new double[cap];
        this.scan = new int[cap];
    }

    /** Begin an MS1 scan (no precursor metadata). */
    public SpectrumTableBuilder startScan(int scanId, double rtMinutes, int polarity) {
        return startScan(scanId, rtMinutes, polarity, 0.0, 0, 0);
    }

    /**
     * Begin a scan.
     *
     * @param rtMinutes exact retention time in minutes; stored as a double on the scan index
     * @param precmz    precursor m/z, or {@code 0} for MassQL's "not recorded" sentinel
     * @param ms1scan   linked MS1 scan id by document order, or {@code 0} for none
     * @param charge    precursor charge, or {@code 0} for "not recorded"
     */
    public SpectrumTableBuilder startScan(
            int scanId, double rtMinutes, int polarity, double precmz, int ms1scan, int charge) {
        ensureNotBuilt();
        // Non-decreasing scan ids are what let the index be a range lookup instead of a hash
        // of lists. Readers stream in document order, so this costs nothing to require.
        if (scans > 0 && scanId < sIds[scans - 1]) {
            throw new MassqlException(
                    "scan ids must be non-decreasing; got " + scanId + " after " + sIds[scans - 1]);
        }
        if (scans > 0 && scanId == sIds[scans - 1]) {
            throw new MassqlException(
                    "duplicate scan id " + scanId + "; each scan must be started exactly once");
        }
        if (scans == sIds.length) {
            int n = scans * 2;
            sIds = Arrays.copyOf(sIds, n);
            sStart = Arrays.copyOf(sStart, n);
            sRt = Arrays.copyOf(sRt, n);
            sPol = Arrays.copyOf(sPol, n);
            sPrecmz = Arrays.copyOf(sPrecmz, n);
            sMs1scan = Arrays.copyOf(sMs1scan, n);
            sCharge = Arrays.copyOf(sCharge, n);
        }
        sIds[scans] = scanId;
        sStart[scans] = rows;
        sRt[scans] = rtMinutes;
        sPol[scans] = (byte) polarity;
        sPrecmz[scans] = precmz;
        sMs1scan[scans] = ms1scan;
        sCharge[scans] = charge;
        scans++;
        return this;
    }

    /** Append a peak to the current scan. */
    public SpectrumTableBuilder addPeak(double mzValue, double intensity) {
        ensureNotBuilt();
        if (scans == 0) {
            throw new MassqlException("addPeak called before startScan");
        }
        if (rows == mz.length) {
            int n = rows * 2;
            mz = Arrays.copyOf(mz, n);
            in = Arrays.copyOf(in, n);
            scan = Arrays.copyOf(scan, n);
        }
        mz[rows] = mzValue;
        in[rows] = intensity;
        scan[rows] = sIds[scans - 1];
        rows++;
        return this;
    }

    /** Number of scans started so far. Lets a reader report progress without building. */
    public int scanCount() {
        return scans;
    }

    public int peakCount() {
        return rows;
    }

    /** True if any scan's peaks needed sorting during {@link #build}. Diagnostic only. */
    private boolean sortedAnyScan = false;

    public boolean sortedAnyScan() {
        return sortedAnyScan;
    }

    public SpectrumTable build() {
        ensureNotBuilt();
        built = true;

        int[] scanIds = Arrays.copyOf(sIds, scans);
        int[] rowStart = Arrays.copyOf(sStart, scans);
        int[] rowEnd = new int[scans];
        for (int s = 0; s < scans; s++) {
            rowEnd[s] = (s + 1 < scans) ? sStart[s + 1] : rows;
        }

        double[] mzOut = Arrays.copyOf(mz, rows);
        double[] iOut = Arrays.copyOf(in, rows);
        double[] iNorm = new double[rows];
        double[] iTicNorm = new double[rows];
        int[] scanOut = Arrays.copyOf(scan, rows);
        float[] rtOut = new float[rows];
        byte[] polOut = new byte[rows];

        for (int s = 0; s < scans; s++) {
            int from = rowStart[s], to = rowEnd[s];

            // Sort by ascending m/z within the scan if needed. Required for the binary-search
            // windows; do not assume sortedness, verify it -- a reader for a nonconforming
            // file would otherwise produce silently wrong window results.
            if (!isSorted(mzOut, from, to)) {
                sortByMz(mzOut, iOut, from, to);
                sortedAnyScan = true;
            }

            double max = Double.NEGATIVE_INFINITY;
            double sum = 0.0;
            for (int r = from; r < to; r++) {
                double v = iOut[r];
                if (v > max) max = v;
                sum += v;
            }
            for (int r = from; r < to; r++) {
                // An all-zero scan divides by zero and yields NaN. That is deliberate: NaN is
                // the correct in-band "undefined", and Tech_Step10 maps NaN to JSON null.
                // Substituting 0 here would report a real value where there is none.
                iNorm[r] = iOut[r] / max;
                iTicNorm[r] = iOut[r] / sum;
                rtOut[r] = (float) sRt[s];
                polOut[r] = sPol[s];
                scanOut[r] = scanIds[s];
            }
        }

        ScanIndex index =
                new ScanIndex(
                        scanIds,
                        rowStart,
                        rowEnd,
                        Arrays.copyOf(sRt, scans),
                        Arrays.copyOf(sPol, scans),
                        Arrays.copyOf(sPrecmz, scans),
                        Arrays.copyOf(sMs1scan, scans),
                        Arrays.copyOf(sCharge, scans));

        return new SpectrumTable(
                mzOut, iOut, iNorm, iTicNorm, scanOut, rtOut, polOut, msLevel, index);
    }

    private static boolean isSorted(double[] a, int from, int to) {
        for (int r = from + 1; r < to; r++) {
            if (a[r] < a[r - 1]) return false;
        }
        return true;
    }

    /** Insertion sort on the (mz, intensity) pair. Scans are small and near-sorted already. */
    private static void sortByMz(double[] mz, double[] in, int from, int to) {
        for (int r = from + 1; r < to; r++) {
            double m = mz[r], v = in[r];
            int j = r - 1;
            while (j >= from && mz[j] > m) {
                mz[j + 1] = mz[j];
                in[j + 1] = in[j];
                j--;
            }
            mz[j + 1] = m;
            in[j + 1] = v;
        }
    }

    private void ensureNotBuilt() {
        if (built)
            throw new MassqlException("this builder has already been built; it is single-use");
    }
}
