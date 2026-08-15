package org.cytoscape.massql.spectra;

import java.util.Arrays;

import org.cytoscape.massql.MassqlException;

/** Append-then-freeze construction of a {@link SpectrumTable}. */
public final class SpectrumTableBuilder {
    /** Default peak capacity. */
    private static final int DEFAULT_PEAK_CAPACITY = 32;

    private final byte msLevel;

    private double[] mz;
    private double[] in;
    private int[] scan;
    private int rows = 0;

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

    /** Begin a scan. */
    public SpectrumTableBuilder startScan(
            int scanId, double rtMinutes, int polarity, double precmz, int ms1scan, int charge) {
        ensureNotBuilt();

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

    /** Number of scans started so far. */
    public int scanCount() {
        return scans;
    }

    public int peakCount() {
        return rows;
    }

    /** True if any scan's peaks needed sorting during {@link #build}. */
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

    /** Insertion sort on the (mz, intensity) pair. */
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
