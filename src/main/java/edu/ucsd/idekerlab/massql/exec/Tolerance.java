package edu.ucsd.idekerlab.massql.exec;

import edu.ucsd.idekerlab.massql.lang.ast.Qualifier;
import edu.ucsd.idekerlab.massql.lang.ast.QualifierType;

import java.util.List;

/**
 * The m/z tolerance window, computed in exactly one place.
 *
 * <p>Mirrors {@code _get_mz_tolerance} (`msql_engine_filters.py:5-17`) — which is the real authority for this
 * rule, not `msql_engine.py` as Tech_Step9 §3 used to say (Correction C37).
 *
 * <p><b>The three rules, in the source's own order:</b>
 * <ol>
 *   <li><b>{@code TOLERANCEPPM} wins if both are given.</b> Not "narrower wins", not an error — the source
 *       checks ppm first and returns. So {@code TOLERANCEPPM=5:TOLERANCEMZ=100} is a 5 ppm window even
 *       though the Da value is far wider.</li>
 *   <li>PPM → absolute: {@code tol = abs(ppm * mz / 1e6)}. The {@code abs} is the source's, and it means a
 *       negative ppm still yields a positive window rather than an inverted one.</li>
 *   <li><b>Default {@code 0.1} Da</b> when neither qualifier is present.</li>
 * </ol>
 *
 * <p>⛔ <b>The window is STRICT at both ends</b> — {@code (target - tol, target + tol)}. Callers must use
 * {@link edu.ucsd.idekerlab.massql.spectra.SpectrumTable#mzWindowExclusive}, never {@code mzWindow}. See
 * Correction C37(a): verified by execution, a peak exactly on the bound does not match. The inclusive
 * {@code mzWindow} exists for Tech_Step10's precursor lookup, which genuinely differs.
 *
 * <p><b>Computed from the TARGET value, never from the observed peak.</b> Deriving the width from each peak
 * would make the window vary across a scan.
 */
public final class Tolerance {

    /** The source's default when no tolerance qualifier is present (`:7`, `:16`). */
    public static final double DEFAULT_DA = 0.1;

    private Tolerance() { }

    /**
     * Half-width of the window around {@code target}, in Da.
     *
     * @param qualifiers the condition's qualifiers; may be empty
     * @param target     the m/z the condition names — the window is centred here
     */
    public static double halfWidthFor(List<Qualifier> qualifiers, double target) {
        if (qualifiers != null) {
            // PPM first, and RETURN -- that is what makes ppm win over Da when both are present.
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
