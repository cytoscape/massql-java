package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MassqlParseExceptionTest {
    @Test
    void constructIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new MassqlParseException(null, "m"));
        assertThrows(IllegalArgumentException.class, () -> new MassqlParseException("  ", "m"));
    }

    @Test
    void carriesConstructAndPosition() {
        MassqlParseException e = new MassqlParseException("scansum", "not supported", 7, null);
        assertEquals("scansum", e.construct());
        assertEquals(7, e.position());
        assertInstanceOf(MassqlException.class, e, "must be catchable as MassqlException");
    }

    @Test
    void positionDefaultsToUnknown() {
        assertEquals(-1, new MassqlParseException("FILTER", "not supported").position());
    }
}
