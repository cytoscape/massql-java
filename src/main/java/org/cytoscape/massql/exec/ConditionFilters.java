package org.cytoscape.massql.exec;

import java.util.List;

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.lang.ast.Condition;
import org.cytoscape.massql.lang.ast.ConditionType;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.spectra.IntRange;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * One evaluator per condition, each answering a single question about <b>one scan</b>.
 *
 * <h2>The structural rule, and the most likely error in the step</h2>
 *
 * <p>A MassQL condition means <i>"this scan contains at least one peak satisfying P"</i>, <b>not</b> "this row
 * satisfies P". So conditions combine as a per-scan boolean AND over "did this condition find any peak" —
 * never as a row-level AND, because two conditions may be satisfied by <b>different peaks in the same
 * scan</b>.
 *
 * <p>That is not an interpretation; it is what the source does. Every condition function reduces its matches
 * to a <i>scan set</i> and then re-admits <b>all rows of those scans</b>
 * so the next condition sees every peak of each surviving
 * scan. A consequence worth knowing: because no predicate ever sees a reduced peak list, each condition is a
 * pure set intersection and **condition order is irrelevant** — proven, and pinned by
 * {@code micro_ms1var.mzML}, the only fixture that can discriminate.
 *
 * <h2>Scan-level vs peak-level</h2>
 *
 * <p>{@code RTMIN}, {@code RTMAX}, {@code SCANMIN}, {@code SCANMAX}, {@code CHARGE}, {@code POLARITY} and
 * {@code MS2PREC} read {@link ScanView} alone and need <b>no materialisation</b>. Evaluating them first is
 * what makes deferred decoding pay: a rejected scan never pays base64-decode, inflate or the
 * {@code double[]} allocation.
 */
public final class ConditionFilters {

    private ConditionFilters() {}

    /** True if this condition can be decided from scan metadata alone, with no peaks. */
    public static boolean isScanLevel(Condition c) {
        if (c instanceof Condition.PolarityIs) return true;
        ConditionType t = ((Condition.Value) c).type();
        return switch (t) {
            case RTMIN, RTMAX, SCANMIN, SCANMAX, CHARGE, MS2PREC -> true;
            case MS2PROD, MS2NL, MS1MZ -> false;
        };
    }

    /**
     * Evaluates a scan-level condition against metadata only.
     *
     * <p>Bound strictness is deliberately <b>asymmetric</b> and must not be unified:
     * {@code RTMIN}/{@code RTMAX} are <b>strict</b> ({@code >} / {@code <}), while
     * {@code SCANMIN}/{@code SCANMAX} are <b>inclusive</b> ({@code >=} / {@code <=}, `:452,459`). Verified;
     * the difference is real.
     */
    /**
     * The reference loads 0 where a value is absent and matches conditions against that, so a query
     * for {@code CHARGE=0} selects the scans with no declared charge.
     */
    private static int raw(Integer v) {
        return v == null ? 0 : v;
    }

    private static double raw(Double v) {
        return v == null ? 0.0 : v;
    }

    public static boolean scanLevelHolds(Condition c, ScanView v) {
        if (c instanceof Condition.PolarityIs p) {
            return v.polarity() == p.polarity();
        }
        Condition.Value cv = (Condition.Value) c;
        return switch (cv.type()) {
            case RTMIN -> anyValue(cv.values(), x -> v.rt() > x); // STRICT
            case RTMAX -> anyValue(cv.values(), x -> v.rt() < x); // STRICT
            case SCANMIN -> anyValue(cv.values(), x -> v.scanId() >= (int) x); // INCLUSIVE
            case SCANMAX -> anyValue(cv.values(), x -> v.scanId() <= (int) x); // INCLUSIVE
            case CHARGE -> anyValue(cv.values(), x -> raw(v.charge()) == (int) x);
            case MS2PREC -> anyValue(
                    cv.values(),
                    target -> {
                        // The scan's own precursor m/z, in a STRICT window
                        // for the precursor condition.
                        double lo = Tolerance.loFor(cv.qualifiers(), target);
                        double hi = Tolerance.hiFor(cv.qualifiers(), target);
                        return raw(v.precmz()) > lo && raw(v.precmz()) < hi;
                    });
            case MS2PROD, MS2NL, MS1MZ -> throw new IllegalStateException(
                    cv.type() + " is peak-level; call peakLevelHolds");
        };
    }

    /**
     * Evaluates a peak-level condition: does this scan contain <b>at least one</b> peak satisfying it?
     *
     * @param scan       the MS2 (or MS1DATA) scan under test
     * @param v          its metadata — {@code MS2NL} needs {@code precmz}
     * @param retainedMs1 the linked MS1 scan for {@code MS1MZ}; may be null
     */
    public static boolean peakLevelHolds(
            Condition c, SpectrumTable scan, ScanView v, SpectrumTable retainedMs1) {
        Condition.Value cv = (Condition.Value) c;
        return switch (cv.type()) {
            case MS2PROD -> anyValue(cv.values(), target -> matchesInTable(scan, cv, target));

            case MS2NL -> {
                // The neutral loss is computed from the scan's OWN precursor.
                //
                // A scan with no recorded precursor cannot satisfy an MS2NL condition.
                if (v.precmz() == null) yield false;
                // Source form: (precmz - mz) strictly inside (value - tol, value + tol). Rearranged
                // to a
                // window on mz, which is the same set and lets the binary search do the work:
                //   value - tol < precmz - mz < value + tol
                //   <=>  precmz - value - tol < mz < precmz - value + tol
                yield anyValue(
                        cv.values(),
                        target -> {
                            double half = Tolerance.halfWidthFor(cv.qualifiers(), target);
                            double centre = v.precmz() - target;
                            return matchesWindow(scan, cv, centre - half, centre + half);
                        });
            }

            case MS1MZ -> {
                // MassQL keeps MS2 scans whose ms1scan matched (`:557-562`). Under the
                // document-order rule
                // the retained MS1 IS that scan, so evaluating here is equivalent -- and because
                // condition
                // order is irrelevant (C37g), doing it per-scan loses nothing.
                if (retainedMs1 == null || retainedMs1.isEmpty()) yield false;
                yield anyValue(cv.values(), target -> matchesInTable(retainedMs1, cv, target));
            }

            case RTMIN, RTMAX, SCANMIN, SCANMAX, CHARGE, MS2PREC -> throw new IllegalStateException(
                    cv.type() + " is scan-level; call scanLevelHolds");
        };
    }

    /** Any peak in a STRICT window around {@code target} that also passes the intensity qualifiers. */
    private static boolean matchesInTable(SpectrumTable t, Condition.Value cv, double target) {
        return matchesWindow(
                t,
                cv,
                Tolerance.loFor(cv.qualifiers(), target),
                Tolerance.hiFor(cv.qualifiers(), target));
    }

    private static boolean matchesWindow(
            SpectrumTable t, Condition.Value cv, double lo, double hi) {
        // mzWindowExclusive, NOT mzWindow: condition windows are strict. A single-scan
        // table's only
        // ordinal is 0.
        IntRange r = t.mzWindowExclusive(0, lo, hi);
        for (int row = r.start(); row < r.end(); row++) {
            if (IntensityQualifiers.rowQualifies(t, row, cv.qualifiers())) return true;
        }
        return false;
    }

    /**
     * An OR value list is satisfied by <b>any</b> value in it.
     *
     * <p>The source builds one filtered frame per value and {@code pd.concat}s them
     * (`_merge_filter_cardinality` without a {@code CARDINALITY} qualifier), i.e. a union. A plain
     * single-valued condition is the one-element case, so there is one code path rather than two.
     */
    private static boolean anyValue(List<Expr> values, DoublePredicate p) {
        for (Expr e : values) {
            if (p.test(ConstantFolder.fold(e))) return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface DoublePredicate {
        boolean test(double value);
    }
}
