package edu.ucsd.idekerlab.massql.exec;

import edu.ucsd.idekerlab.massql.spectra.Column;
import edu.ucsd.idekerlab.massql.spectra.IntRange;
import edu.ucsd.idekerlab.massql.spectra.Reductions;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

/**
 * Finds the precursor peak in the linked MS1 scan — {@code ms1_i}, {@code ms1_precmz},
 * {@code ms1_base_peak_i}.
 *
 * <p>Isolated from {@link ScaninfoCollation} because {@code SPIKE.md} §6a calls the test for its first
 * rule <i>"the test that catches the most likely misreading of the whole contract"</i>, and a rule that
 * important deserves to be directly testable without building a whole result row.
 *
 * <p>Mirrors {@code massql_query.py:62-116}. Four rules, each independently wrong-able:
 *
 * <ol>
 *   <li><b>Closest in m/z to {@code precmz}, NOT the most intense.</b> The intuitive reading is wrong.</li>
 *   <li><b>{@code ms1_base_peak_i} does not depend on the match.</b> It is populated whenever the linked
 *       MS1 scan exists, so a tolerance miss nulls only the other two.</li>
 *   <li><b>Ties resolve to the LOWER m/z</b>, matching pandas {@code argmin}'s first-occurrence.</li>
 *   <li><b>The window is INCLUSIVE.</b> See {@link #candidates}.</li>
 * </ol>
 */
public final class PrecursorLookup {

    private PrecursorLookup() {}

    /** The three looked-up values. Any may be null; see the class notes. */
    public record Result(Double ms1I, Double ms1Precmz, Double ms1BasePeakI) {

        /** No linked MS1 scan at all — all three null. */
        static final Result NONE = new Result(null, null, null);
    }

    /**
     * @param retainedMs1 the linked MS1 scan as a single-scan table, or null if none precedes this scan
     * @param ms1scan     the scan's {@code ms1scan} metadata, carrying MassQL's raw {@code 0} sentinel
     * @param precmz      the scan's {@code precmz}, carrying the raw {@code 0} sentinel
     * @param tolPpm      {@code MassqlOptions.precursorTolPpm} — <b>not</b> the query's own TOLERANCEPPM
     */
    public static Result lookup(
            SpectrumTable retainedMs1, int ms1scan, double precmz, double tolPpm) {
        // The raw 0 sentinel is what tells us there is no linked scan, which is why the collation
        // converts sentinels to null only AFTER this runs. Converting earlier loses the signal.
        if (ms1scan == 0 || retainedMs1 == null || retainedMs1.isEmpty()) return Result.NONE;

        // The stream retains exactly the linked MS1 scan, because the
        // document-order rule guarantees ms1scan is always the most recent PRECEDING MS1. If this
        // ever
        // disagrees, the rule has been broken upstream and the lookup would silently read a
        // different
        // scan's peaks -- so assert rather than trust.
        int retainedId = retainedMs1.index().scanIdAt(0);
        if (retainedId != ms1scan) {
            throw new IllegalStateException(
                    "retained MS1 scan "
                            + retainedId
                            + " is not the linked ms1scan "
                            + ms1scan
                            + "; the document-order rule has been broken upstream");
        }

        // Rule 2: computed across the WHOLE scan, before and independently of any window search, so
        // a
        // tolerance miss below leaves it populated.
        int topRow = Reductions.argmax(retainedMs1, 0, Column.I);
        // argmax returns -1 on an empty scan; guarded above, but Reductions' contract allows it and
        // a
        // NaN from Reductions.max would otherwise reach the JSON as a null for the wrong reason.
        Double ms1BasePeakI = topRow < 0 ? null : retainedMs1.intensity(topRow);

        // A precmz of 0 is "not recorded", and NaN cannot be matched against.
        if (precmz == 0.0 || Double.isNaN(precmz)) return new Result(null, null, ms1BasePeakI);

        IntRange cand = candidates(retainedMs1, precmz, tolPpm);
        if (cand.size() == 0) return new Result(null, null, ms1BasePeakI);

        int best = closestTo(retainedMs1, cand, precmz);
        return new Result(retainedMs1.intensity(best), retainedMs1.mz(best), ms1BasePeakI);
    }

    /**
     * The candidate peaks: MS1 m/z within {@code precmz ± precmz * tolPpm / 1e6}.
     *
     * <p>⛔ <b>INCLUSIVE bounds — {@code mzWindow}, never {@code mzWindowExclusive}</b>.
     * {@code massql_query.py:101-103} filters with {@code >=} / {@code <=}, whereas the condition
     * filters use {@code >} / {@code <} and therefore the exclusive variant. <b>The two genuinely differ
     * and must not be unified.</b>
     *
     * <p>The choice is asserted against the reference at two layers: {@code PrecursorLookupTest}
     * exercises an on-bound peak directly on the store, and {@code DifferentialIT}'s
     * {@code micro_onbound.mzML} pair exercises it end to end against a Python-generated golden whose
     * {@code ms1_i} is 7000.0. The exclusive variant returns null for that peak and fails both.
     */
    private static IntRange candidates(SpectrumTable ms1, double precmz, double tolPpm) {
        double tol = precmz * tolPpm / 1e6;
        return ms1.mzWindow(0, precmz - tol, precmz + tol);
    }

    /**
     * The candidate closest in m/z to {@code precmz}.
     *
     * <p>Rules 1 and 3 together. Intensity is <b>not consulted</b>: picking the most intense peak in the
     * window is the intuitive reading and it is wrong ({@code massql_query.py:104} —
     * {@code cand.iloc[(cand["mz"] - precmz).abs().argmin()]}).
     *
     * <p>Strict {@code <} on the distance comparison is what makes a tie resolve to the <b>first</b> row,
     * i.e. the lower m/z given the store's ascending-m/z invariant — matching pandas {@code argmin}.
     */
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
