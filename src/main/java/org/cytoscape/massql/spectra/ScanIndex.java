package org.cytoscape.massql.spectra;

import java.util.Arrays;

import org.cytoscape.massql.MassqlException;

/** Maps scan ids to row ranges, and holds the per-scan metadata. */
public final class ScanIndex {
    private final int[] scanIds;
    private final int[] rowStart;
    private final int[] rowEnd;
    private final double[] rt;
    private final byte[] polarity;
    private final double[] precmz;
    private final int[] ms1scan;
    private final int[] charge;

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

    /** Exact retention time in minutes. */
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

    /** Ordinal for a scan id, or {@code -1} if absent. */
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
