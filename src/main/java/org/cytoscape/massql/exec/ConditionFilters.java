package org.cytoscape.massql.exec;

import java.util.List;

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.lang.ast.Condition;
import org.cytoscape.massql.lang.ast.ConditionType;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.spectra.IntRange;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * One evaluator per condition, each answering a single question about one scan. <h2>The structural
 * rule, and the most likely error in the step</h2> A MassQL condition means "this scan contains at
 * least one peak satisfying P", not "this row satisfies P".
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

    /** Evaluates a scan-level condition against metadata only. */
    /**
     * The reference loads 0 where a value is absent and matches conditions against that, so a
     * query for {@code CHARGE=0} selects the scans with no declared charge.
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
            case RTMIN -> anyValue(cv.values(), x -> v.rt() > x);
            case RTMAX -> anyValue(cv.values(), x -> v.rt() < x);
            case SCANMIN -> anyValue(cv.values(), x -> v.scanId() >= (int) x);
            case SCANMAX -> anyValue(cv.values(), x -> v.scanId() <= (int) x);
            case CHARGE -> anyValue(cv.values(), x -> raw(v.charge()) == (int) x);
            case MS2PREC -> anyValue(
                    cv.values(),
                    target -> {
                        double lo = Tolerance.loFor(cv.qualifiers(), target);
                        double hi = Tolerance.hiFor(cv.qualifiers(), target);
                        return raw(v.precmz()) > lo && raw(v.precmz()) < hi;
                    });
            case MS2PROD, MS2NL, MS1MZ -> throw new IllegalStateException(
                    cv.type() + " is peak-level; call peakLevelHolds");
        };
    }

    /** Evaluates a peak-level condition: does this scan contain at least one peak satisfying it? */
    public static boolean peakLevelHolds(
            Condition c, SpectrumTable scan, ScanView v, SpectrumTable retainedMs1) {
        Condition.Value cv = (Condition.Value) c;
        return switch (cv.type()) {
            case MS2PROD -> anyValue(cv.values(), target -> matchesInTable(scan, cv, target));

            case MS2NL -> {
                if (v.precmz() == null) yield false;

                yield anyValue(
                        cv.values(),
                        target -> {
                            double half = Tolerance.halfWidthFor(cv.qualifiers(), target);
                            double centre = v.precmz() - target;
                            return matchesWindow(scan, cv, centre - half, centre + half);
                        });
            }

            case MS1MZ -> {
                if (retainedMs1 == null || retainedMs1.isEmpty()) yield false;
                yield anyValue(cv.values(), target -> matchesInTable(retainedMs1, cv, target));
            }

            case RTMIN, RTMAX, SCANMIN, SCANMAX, CHARGE, MS2PREC -> throw new IllegalStateException(
                    cv.type() + " is scan-level; call scanLevelHolds");
        };
    }

    /**
     * Any peak in a STRICT window around {@code target} that also passes the intensity qualifiers.
     */
    private static boolean matchesInTable(SpectrumTable t, Condition.Value cv, double target) {
        return matchesWindow(
                t,
                cv,
                Tolerance.loFor(cv.qualifiers(), target),
                Tolerance.hiFor(cv.qualifiers(), target));
    }

    private static boolean matchesWindow(
            SpectrumTable t, Condition.Value cv, double lo, double hi) {
        IntRange r = t.mzWindowExclusive(0, lo, hi);
        for (int row = r.start(); row < r.end(); row++) {
            if (IntensityQualifiers.rowQualifies(t, row, cv.qualifiers())) return true;
        }
        return false;
    }

    /** An OR value list is satisfied by any value in it. */
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
