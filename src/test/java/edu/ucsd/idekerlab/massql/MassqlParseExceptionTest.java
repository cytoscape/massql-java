package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MassqlParseExceptionTest {

    @Test
    void constructIsMandatory() {
        // the rejection tests and the CLI contract both assert on
        // construct(), so an instance without one is useless and must not be constructible.
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
