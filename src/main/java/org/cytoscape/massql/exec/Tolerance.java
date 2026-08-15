package org.cytoscape.massql.exec;

import java.util.List;

import org.cytoscape.massql.lang.ast.Qualifier;
import org.cytoscape.massql.lang.ast.QualifierType;

/** The m/z tolerance window, computed in exactly one place. */
public final class Tolerance {
    /** The source's default when no tolerance qualifier is present (`:7`, `:16`). */
    public static final double DEFAULT_DA = 0.1;

    private Tolerance() {}

    /** Half-width of the window around {@code target}, in Da. */
    public static double halfWidthFor(List<Qualifier> qualifiers, double target) {
        if (qualifiers != null) {
            for (Qualifier q : qualifiers) {
                if (q.type() == QualifierType.TOLERANCEPPM) {
                    double ppm = ConstantFolder.fold(q.value());
                    return Math.abs(ppm * target / 1_000_000.0);
                }
            }
            for (Qualifier q : qualifiers) {
                if (q.type() == QualifierType.TOLERANCEMZ) {
                    return ConstantFolder.fold(q.value());
                }
            }
        }
        return DEFAULT_DA;
    }

    /** Lower bound, exclusive. */
    public static double loFor(List<Qualifier> qualifiers, double target) {
        return target - halfWidthFor(qualifiers, target);
    }

    /** Upper bound, exclusive. */
    public static double hiFor(List<Qualifier> qualifiers, double target) {
        return target + halfWidthFor(qualifiers, target);
    }
}
