package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The public {@code Massql.parse} contract: whitespace tolerance and blank-input handling. */
class ParseEntryPointTest {

    private static final String Q = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=226.18:TOLERANCEPPM=5";

    @Test
    void surroundingWhitespaceAndTrailingNewlinesAreIgnored() {
        // massql_query.py strips the query file (load_query -> read().strip()), and the
        // .massql files on disk end with a newline, so this is the normal case, not an edge.
        String canonical = Massql.parse(Q).canonical();
        for (String variant :
                new String[] {
                    Q + "\n", "\n" + Q, "  " + Q + "  ", "\t" + Q + "\n\n", "\r\n" + Q + "\r\n"
                }) {
            assertEquals(
                    canonical,
                    Massql.parse(variant).canonical(),
                    "whitespace variant changed the parse: "
                            + variant.replace("\n", "\\n").replace("\r", "\\r"));
        }
    }

    @Test
    void internalWhitespaceIsFlexible() {
        // The corpus contains "MS2PROD=X :TOLERANCEMZ=0.1" with a space before the colon.
        assertEquals(
                Massql.parse(Q).canonical(),
                Massql.parse(
                                "QUERY  scaninfo( MS2DATA )  WHERE  MS2PROD = 226.18 : TOLERANCEPPM = 5")
                        .canonical());
    }

    @Test
    void blankInputIsRejectedClearly() {
        for (String blank : new String[] {"", "   ", "\n", "\t", "  \n  "}) {
            MassqlParseException e =
                    assertThrows(MassqlParseException.class, () -> Massql.parse(blank));
            assertEquals("<empty>", e.construct());
            assertTrue(e.getMessage().toLowerCase().contains("empty"), e.getMessage());
        }
    }

    @Test
    void nullInputIsRejectedClearlyRatherThanNpe() {
        MassqlParseException e = assertThrows(MassqlParseException.class, () -> Massql.parse(null));
        assertEquals("<empty>", e.construct());
    }

    @Test
    void positionIsReportedOneBasedWhenKnown() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=X"));
        assertTrue(
                e.position() >= 1, "position should be 1-based and positive, got " + e.position());
    }
}
