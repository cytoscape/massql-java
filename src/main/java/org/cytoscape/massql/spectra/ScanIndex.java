package org.cytoscape.massql.spectra;

import java.util.Arrays;

import org.cytoscape.massql.MassqlException;

/**
 * Maps scan ids to row ranges, and holds the per-scan metadata.
 *
 * <p><b>Why scan-level metadata lives here rather than as per-peak columns.</b> MassQL's
 * dataframe repeats {@code rt}, {@code polarity}, {@code precmz}, {@code ms1scan} and
 * {@code charge} on every peak row because pandas is a flat frame. Verified against the
 * loader: all of them are constant within a scan (exactly one distinct value each). Storing
 * them once per scan is both semantically right and much smaller — a 20,000-peak MS1 scan
 * would otherwise carry 20,000 copies of its retention time.
 *
 * <p><b>{@code rt} is a double here, deliberately.</b> {@link SpectrumTable} also keeps a
 * {@code float} rt per peak for cheap row-level filtering, but every {@code rt} that reaches
 * the result JSON comes from {@link #rtOf}. The mzML golden's
 * {@code rt = 0.011218333333333334} does not survive a float round-trip, so a float-only
 * design fails the differential with a tiny, confusing delta.
 *
 * <p>{@code precmz}, {@code ms1scan} and {@code charge} carry MassQL's raw <b>0 sentinel</b>
 * when the file recorded nothing. The 0-to-null conversion is the collation's job, not this
 * layer's — converting early would lose the ability to tell "absent" from "converted".
 */
public final class ScanIndex {

    private final int[] scanIds; // ascending, distinct
    private final int[] rowStart; // inclusive
    private final int[] rowEnd; // exclusive
    private final double[] rt; // minutes, EXACT
    private final byte[] polarity; // 1 = positive, 2 = negative, 0 = unknown
    private final double[] precmz; // 0 = not recorded (MS2 only; all 0 for MS1)
    private final int[] ms1scan; // 0 = no linked MS1 scan
    private final int[] charge; // 0 = not recorded

    ScanIndex(
            int[] scanIds,
            int[] rowStart,
            int[] rowEnd,
            double[] rt,
            byte[] polarity,
            double[] precmz,
            int[] ms1scan,
            int[] charge) {
        this.scanIds = scanIds;
        this.rowStart = rowStart;
        this.rowEnd = rowEnd;
        this.rt = rt;
        this.polarity = polarity;
        this.precmz = precmz;
        this.ms1scan = ms1scan;
        this.charge = charge;
    }

    public int scanCount() {
        return scanIds.length;
    }

    /** Defensive copy: the internal array must never escape. */
    public int[] scanIds() {
        return scanIds.clone();
    }

    public int scanIdAt(int ordinal) {
        return scanIds[check(ordinal)];
    }

    public int rowStart(int ordinal) {
        return rowStart[check(ordinal)];
    }

    public int rowEnd(int ordinal) {
        return rowEnd[check(ordinal)];
    }

    public int peakCount(int ordinal) {
        return rowEnd[check(ordinal)] - rowStart[ordinal];
    }

    /** Exact retention time in minutes. This is the value that reaches the result JSON. */
    public double rtOf(int ordinal) {
        return rt[check(ordinal)];
    }

    public byte polarityOf(int ordinal) {
        return polarity[check(ordinal)];
    }

    /** Raw value including MassQL's 0 sentinel; the collation converts 0 to null. */
    public double precmzOf(int ordinal) {
        return precmz[check(ordinal)];
    }

    public int ms1scanOf(int ordinal) {
        return ms1scan[check(ordinal)];
    }

    public int chargeOf(int ordinal) {
        return charge[check(ordinal)];
    }

    /**
     * Ordinal for a scan id, or {@code -1} if absent.
     *
     * <p>Always go through this. Scan ids in real files are neither dense nor guaranteed
     * 1-based, so using one as an array index is a bug waiting to happen.
     */
    public int ordinalOf(int scanId) {
        int i = Arrays.binarySearch(scanIds, scanId);
        return i < 0 ? -1 : i;
    }

    private int check(int ordinal) {
        if (ordinal < 0 || ordinal >= scanIds.length) {
            throw new MassqlException(
                    "scan ordinal " + ordinal + " out of range [0," + scanIds.length + ")");
        }
        return ordinal;
    }
}
