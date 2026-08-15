package org.cytoscape.massql.testsupport;

import org.cytoscape.massql.lang.ast.Polarity;

/**
 * Maps a {@link org.cytoscape.massql.io.ScanView}'s typed values back to the reference's raw
 * encoding, which is what the loader-parity dumps and goldens record.
 */
public final class Raw {

    private Raw() {}

    public static int polarity(Polarity p) {
        return p == Polarity.POSITIVE ? 1 : p == Polarity.NEGATIVE ? 2 : 0;
    }

    public static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    public static double orZero(Double v) {
        return v == null ? 0.0 : v;
    }
}
