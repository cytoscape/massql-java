package org.cytoscape.massql.testsupport;

import org.cytoscape.massql.lang.ast.Polarity;

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
