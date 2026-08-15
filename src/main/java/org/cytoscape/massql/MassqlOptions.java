package org.cytoscape.massql;

/**
 * Immutable execution options. {@code precursorTolPpm} is a SEPARATE knob from a query's own
 * {@code TOLERANCEPPM}/{@code TOLERANCEMZ}: the query tolerance selects scans, this one matches
 * the precursor peak within an already-selected MS1 scan.
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

    public double precursorTolPpm() {
        return precursorTolPpm;
    }

    public MassqlOptions withPrecursorTolPpm(double ppm) {
        return new MassqlOptions(ppm);
    }

    @Override
    public String toString() {
        return "MassqlOptions[precursorTolPpm=" + precursorTolPpm + "]";
    }
}
