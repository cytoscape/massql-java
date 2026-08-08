package edu.ucsd.idekerlab.massql.spectra;

/**
 * Per-scan reductions over a {@link SpectrumTable}.
 *
 * <p>Empty-scan behaviour is deliberate and load-bearing:
 * {@code sum} is {@code 0.0} (the TIC of an empty spectrum really is zero),
 * while {@code max}/{@code min}/{@code first} are {@code NaN} and {@code argmax} is
 * {@code -1} (there is no value to report). Nothing throws — {@code scaninfo(MS1DATA)} can
 * legitimately report a scan with no peaks.
 */
public final class Reductions {

    private Reductions() {}

    public static double sum(SpectrumTable t, int scanOrdinal, Column c) {
        return sum(t, scanOrdinal, c, null);
    }

    public static double sum(SpectrumTable t, int scanOrdinal, Column c, RowMask mask) {
        ScanIndex idx = t.index();
        double s = 0.0;
        for (int r = idx.rowStart(scanOrdinal); r < idx.rowEnd(scanOrdinal); r++) {
            if (mask == null || mask.get(r)) s += t.value(r, c);
        }
        return s;
    }

    public static double max(SpectrumTable t, int scanOrdinal, Column c) {
        return max(t, scanOrdinal, c, null);
    }

    public static double max(SpectrumTable t, int scanOrdinal, Column c, RowMask mask) {
        int row = argmax(t, scanOrdinal, c, mask);
        return row < 0 ? Double.NaN : t.value(row, c);
    }

    public static double min(SpectrumTable t, int scanOrdinal, Column c) {
        return min(t, scanOrdinal, c, null);
    }

    public static double min(SpectrumTable t, int scanOrdinal, Column c, RowMask mask) {
        ScanIndex idx = t.index();
        double m = Double.NaN;
        boolean any = false;
        for (int r = idx.rowStart(scanOrdinal); r < idx.rowEnd(scanOrdinal); r++) {
            if (mask != null && !mask.get(r)) continue;
            double v = t.value(r, c);
            if (!any || v < m) {
                m = v;
                any = true;
            }
        }
        return any ? m : Double.NaN;
    }

    /**
     * Row index of the maximum, or {@code -1} if the scan (or masked subset) is empty.
     *
     * <p><b>Returns a ROW INDEX, not a value</b>, so the caller can read a <i>different</i>
     * column at that row. That is exactly what {@code base_peak_mz} needs: argmax over
     * intensity, then read m/z (Tech_Step10 §3).
     *
     * <p><b>Ties resolve to the LOWEST row index</b> — i.e. the lowest m/z, given the store's
     * ascending-m/z invariant. This matches pandas {@code idxmax}, which returns the first
     * occurrence, and {@code massql_query.py:163} uses {@code idxmax}. A last-wins
     * implementation would disagree with the goldens on any spectrum containing two
     * equal-intensity peaks.
     */
    public static int argmax(SpectrumTable t, int scanOrdinal, Column c) {
        return argmax(t, scanOrdinal, c, null);
    }

    public static int argmax(SpectrumTable t, int scanOrdinal, Column c, RowMask mask) {
        ScanIndex idx = t.index();
        int best = -1;
        double bestV = Double.NEGATIVE_INFINITY;
        for (int r = idx.rowStart(scanOrdinal); r < idx.rowEnd(scanOrdinal); r++) {
            if (mask != null && !mask.get(r)) continue;
            double v = t.value(r, c);
            // Strict '>' is what makes ties resolve to the lowest row index.
            if (best < 0 || v > bestV) {
                best = r;
                bestV = v;
            }
        }
        return best;
    }

    /** First value in the scan (lowest m/z, given the sort invariant), or NaN if empty. */
    public static double first(SpectrumTable t, int scanOrdinal, Column c) {
        return first(t, scanOrdinal, c, null);
    }

    public static double first(SpectrumTable t, int scanOrdinal, Column c, RowMask mask) {
        ScanIndex idx = t.index();
        for (int r = idx.rowStart(scanOrdinal); r < idx.rowEnd(scanOrdinal); r++) {
            if (mask == null || mask.get(r)) return t.value(r, c);
        }
        return Double.NaN;
    }

    public static int count(SpectrumTable t, int scanOrdinal) {
        return count(t, scanOrdinal, null);
    }

    public static int count(SpectrumTable t, int scanOrdinal, RowMask mask) {
        ScanIndex idx = t.index();
        if (mask == null) return idx.peakCount(scanOrdinal);
        int n = 0;
        for (int r = idx.rowStart(scanOrdinal); r < idx.rowEnd(scanOrdinal); r++) {
            if (mask.get(r)) n++;
        }
        return n;
    }
}
