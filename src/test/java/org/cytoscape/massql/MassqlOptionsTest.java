package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MassqlOptionsTest {

    @Test
    void defaultPrecursorToleranceIs20Ppm() {
        // The documented default, matching the reference.
        assertEquals(20.0, MassqlOptions.defaults().precursorTolPpm());
    }

    @Test
    void withPrecursorTolPpmReturnsANewInstanceAndLeavesTheOriginalAlone() {
        MassqlOptions base = MassqlOptions.defaults();
        MassqlOptions wide = base.withPrecursorTolPpm(60.0);
        assertEquals(60.0, wide.precursorTolPpm());
        assertEquals(20.0, base.precursorTolPpm(), "options must be immutable");
        assertNotSame(base, wide);
    }

    @Test
    void rejectsNonsenseTolerances() {
        MassqlOptions o = MassqlOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> o.withPrecursorTolPpm(0.0));
        assertThrows(IllegalArgumentException.class, () -> o.withPrecursorTolPpm(-1.0));
        assertThrows(IllegalArgumentException.class, () -> o.withPrecursorTolPpm(Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> o.withPrecursorTolPpm(Double.POSITIVE_INFINITY));
    }
}
