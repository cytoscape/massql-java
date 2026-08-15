package org.cytoscape.massql.exec;

import java.util.List;

import org.cytoscape.massql.lang.ast.Qualifier;
import org.cytoscape.massql.lang.ast.QualifierType;
import org.cytoscape.massql.spectra.Column;
import org.cytoscape.massql.spectra.SpectrumTable;

/** The intensity predicate: three scales, three comparators, and an implicit floor. */
public final class IntensityQualifiers {
    /** The clamp the source applies to a {@code >} threshold on either percent column. */
    static final double PERCENT_CAP = 0.99;

    private IntensityQualifiers() {}

    /** Does this row satisfy every intensity qualifier, including the implicit floors? */
    public static boolean rowQualifies(SpectrumTable t, int row, List<Qualifier> qualifiers) {
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

        if (q == null) return observed > 0.0;

        double threshold = ConstantFolder.fold(q.value()) / scale;
        return switch (q.comparator()) {
            case GT -> observed > (scale > 1.0 ? Math.min(threshold, PERCENT_CAP) : threshold);
            case LT -> observed < threshold;

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
