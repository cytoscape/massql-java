package edu.ucsd.idekerlab.massql.exec;

import java.util.List;

import edu.ucsd.idekerlab.massql.lang.ast.Qualifier;
import edu.ucsd.idekerlab.massql.lang.ast.QualifierType;
import edu.ucsd.idekerlab.massql.spectra.Column;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

/**
 * The intensity predicate: three scales, three comparators, and an implicit floor.
 *
 * <p>Mirrors {@code _get_intensity_mask} (`msql_engine_filters.py:65-96`) — the real authority, which
 * are easy to overlook.
 *
 * <h2>The rules, each a silent wrong answer if missed</h2>
 *
 * <p><b>Three scales, three different denominators.</b> Mixing them up gives plausible wrong answers rather
 * than errors:
 *
 * <table border="1">
 *   <caption>Qualifier to column</caption>
 *   <tr><th>Qualifier</th><th>Column</th><th>Divisor</th></tr>
 *   <tr><td>{@code INTENSITYVALUE}</td><td>{@code i} (absolute)</td><td>1</td></tr>
 *   <tr><td>{@code INTENSITYPERCENT}</td><td>{@code iNorm} (÷ max in scan)</td><td><b>100</b></td></tr>
 *   <tr><td>{@code INTENSITYTICPERCENT}</td><td>{@code iTicNorm} (÷ sum in scan)</td><td><b>100</b></td></tr>
 * </table>
 *
 * <p>⚠ <b>{@code INTENSITYTICPERCENT} also divides by 100</b> — it is natural to state the ÷100 rule for
 * {@code INTENSITYPERCENT} only. Both carry {@code scale = 100.0} in the source.
 *
 * <p><b>{@code =} means {@code >=}.</b> Verbatim from the source's own comment: *"equal → minimum threshold
 * (&gt;= val), preserving historical semantics"*. It looks like a bug; reproduce it. This is the rule most
 * likely to be "fixed" by a well-meaning implementer.
 *
 * <p><b>The 0.99 cap applies for {@code >} only, and to BOTH percent qualifiers.</b> `iNorm`'s maximum is
 * exactly 1.0, so {@code INTENSITYPERCENT>100} would match nothing; the source clamps the threshold to 0.99
 * instead. The guard is {@code if scale > 1.0}, so it covers {@code INTENSITYTICPERCENT} as well — ⚠
 * one and forget the other. It never applies to
 * {@code INTENSITYVALUE}, and never to {@code >=} or {@code <}.
 *
 * <p><b>An absent qualifier means an implicit {@code > 0} — per column, on all three.</b> So a bare
 * {@code MS2PROD=100.0} requires {@code i > 0 && iNorm > 0 && iTicNorm > 0}, not merely a peak, and not one
 * blanket check. This is also the whole content of the "missing comparator defaults to
 * greater-than" rule: it is about an absent <i>qualifier</i>, not a qualifier that parsed without a
 * comparator — {@code Comparator} has no {@code NONE} and {@code Qualifier} rejects a null one.
 */
public final class IntensityQualifiers {

    /** The clamp the source applies to a {@code >} threshold on either percent column. */
    static final double PERCENT_CAP = 0.99;

    private IntensityQualifiers() {}

    /** Does this row satisfy every intensity qualifier, including the implicit floors? */
    public static boolean rowQualifies(SpectrumTable t, int row, List<Qualifier> qualifiers) {
        // Evaluated per column, in the source's order, so the AND is over three independent
        // predicates.
        return columnQualifies(t, row, Column.I, QualifierType.INTENSITYVALUE, 1.0, qualifiers)
                && columnQualifies(
                        t, row, Column.I_NORM, QualifierType.INTENSITYPERCENT, 100.0, qualifiers)
                && columnQualifies(
                        t,
                        row,
                        Column.I_TIC_NORM,
                        QualifierType.INTENSITYTICPERCENT,
                        100.0,
                        qualifiers);
    }

    private static boolean columnQualifies(
            SpectrumTable t,
            int row,
            Column col,
            QualifierType type,
            double scale,
            List<Qualifier> qualifiers) {
        double observed = t.value(row, col);
        Qualifier q = find(qualifiers, type);

        // Absent qualifier -> the implicit floor. NOT "> 0 somewhere"; this column specifically.
        if (q == null) return observed > 0.0;

        double threshold = ConstantFolder.fold(q.value()) / scale;
        return switch (q.comparator()) {
                // The cap is inside the greaterthan branch in the source, and keyed on scale > 1.0
                // --
                // i.e. both percent columns, never the absolute one.
            case GT -> observed > (scale > 1.0 ? Math.min(threshold, PERCENT_CAP) : threshold);
            case LT -> observed < threshold;
                // "=" is >=, preserving historical semantics. No cap.
            case EQ -> observed >= threshold;
        };
    }

    private static Qualifier find(List<Qualifier> qualifiers, QualifierType type) {
        if (qualifiers == null) return null;
        for (Qualifier q : qualifiers) {
            if (q.type() == type) return q;
        }
        return null;
    }
}
