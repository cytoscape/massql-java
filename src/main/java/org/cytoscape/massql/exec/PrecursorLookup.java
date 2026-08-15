package org.cytoscape.massql.exec;

import org.cytoscape.massql.spectra.Column;
import org.cytoscape.massql.spectra.IntRange;
import org.cytoscape.massql.spectra.Reductions;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * Finds the precursor peak in the linked MS1 scan — {@code ms1_i}, {@code ms1_precmz}, {@code
 * ms1_base_peak_i}.
 */
public final class PrecursorLookup {
    private PrecursorLookup() {}

    /** The three looked-up values. */
    public record Result(Double ms1I, Double ms1Precmz, Double ms1BasePeakI) {
        /** No linked MS1 scan at all — all three null. */
        static final Result NONE = new Result(null, null, null);
    }

    public static Result lookup(
            SpectrumTable retainedMs1, Integer ms1scan, Double precmz, double tolPpm) {
        if (ms1scan == null || retainedMs1 == null || retainedMs1.isEmpty()) return Result.NONE;

        int retainedId = retainedMs1.index().scanIdAt(0);
        if (retainedId != ms1scan) {
            throw new IllegalStateException(
                    "retained MS1 scan "
                            + retainedId
                            + " is not the linked ms1scan "
                            + ms1scan
                            + "; the document-order rule has been broken upstream");
        }

        int topRow = Reductions.argmax(retainedMs1, 0, Column.I);

        Double ms1BasePeakI = topRow < 0 ? null : retainedMs1.intensity(topRow);

        if (precmz == null || precmz.isNaN()) return new Result(null, null, ms1BasePeakI);

        IntRange cand = candidates(retainedMs1, precmz, tolPpm);
        if (cand.size() == 0) return new Result(null, null, ms1BasePeakI);

        int best = closestTo(retainedMs1, cand, precmz);
        return new Result(retainedMs1.intensity(best), retainedMs1.mz(best), ms1BasePeakI);
    }

    /** The candidate peaks: MS1 m/z within {@code precmz ± precmz * tolPpm / 1e6}. */
    private static IntRange candidates(SpectrumTable ms1, double precmz, double tolPpm) {
        double tol = precmz * tolPpm / 1e6;
        return ms1.mzWindow(0, precmz - tol, precmz + tol);
    }

    /** The candidate closest in m/z to {@code precmz}. */
    private static int closestTo(SpectrumTable ms1, IntRange cand, double precmz) {
        int best = cand.start();
        double bestDist = Math.abs(ms1.mz(best) - precmz);
        for (int r = cand.start() + 1; r < cand.end(); r++) {
            double d = Math.abs(ms1.mz(r) - precmz);
            if (d < bestDist) {
                best = r;
                bestDist = d;
            }
        }
        return best;
    }
}
