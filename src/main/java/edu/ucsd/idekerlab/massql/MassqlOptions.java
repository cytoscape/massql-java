package edu.ucsd.idekerlab.massql;

/**
 * Immutable execution options.
 *
 * <p>{@code precursorTolPpm} is a SEPARATE knob from a query's own
 * {@code TOLERANCEPPM}/{@code TOLERANCEMZ}: the query tolerance selects scans, this one
 * matches the precursor peak within an already-selected MS1 scan (Tech_Step10 §3).
 * Conflating them is a silent wrong-answer bug.
 *
 * <p>The default of 20.0 is the documented default in SPIKE.md §4, RESULT_SCHEMA.md and
 * massql_query.py. Tech_Step1 §3a found the original mzML golden had been generated at an
 * unrecorded ~60 ppm instead, so goldens now record their flags explicitly.
 */
public final class MassqlOptions {

    public static final double DEFAULT_PRECURSOR_TOL_PPM = 20.0;

    private final double precursorTolPpm;

    private MassqlOptions(double precursorTolPpm) {
        if (!(precursorTolPpm > 0.0) || !Double.isFinite(precursorTolPpm)) {
            throw new IllegalArgumentException(
                "precursorTolPpm must be finite and > 0, got " + precursorTolPpm);
        }
        this.precursorTolPpm = precursorTolPpm;
    }

    public static MassqlOptions defaults() {
        return new MassqlOptions(DEFAULT_PRECURSOR_TOL_PPM);
    }

    public double precursorTolPpm() { return precursorTolPpm; }

    public MassqlOptions withPrecursorTolPpm(double ppm) {
        return new MassqlOptions(ppm);
    }

    @Override public String toString() {
        return "MassqlOptions[precursorTolPpm=" + precursorTolPpm + "]";
    }
}
