package org.cytoscape.massql.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Every out-of-scope construct rejects with its name, and every syntax trap fails clearly. */
class ParseRejectionTest {

    /**
     * THE trap: this reads as perfectly legal MassQL. {@code querytype} requires either a bare
     * MS1DATA/MS2DATA or {@code function(DATATYPE)}, so a bare function name is a syntax error --
     * and the message must explain the function-call form rather than just saying
     * "mismatched input 'WHERE'".
     */
    @Test
    void bareFunctionNameWithoutParensIsASyntaxErrorThatExplainsItself() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scaninfo WHERE MS2PROD=100"));
        assertTrue(
                e.getMessage().contains("scaninfo(MS2DATA)"),
                "message must show the required function-call form, got: " + e.getMessage());
    }

    @Test
    void bareDataTypeWithoutAFunctionIsRejectedByName() {
        // Legal MassQL (3 reference parses use it) but out of scope: a function is required.
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY MS2DATA WHERE MS2PROD=226.18"));
        assertEquals("<no function>", e.construct());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "QUERY scansum(MS2DATA)      | scansum",
                "QUERY scannum(MS2DATA)      | scannum",
                "QUERY scanmz(MS2DATA)       | scanmz",
                "QUERY scanmaxint(MS2DATA)   | scanmaxint",
                "QUERY scanrangesum(MS2DATA) | scanrangesum",
            })
    void theOtherFiveFunctionsRejectByName(String query, String construct) {
        MassqlParseException e =
                assertThrows(MassqlParseException.class, () -> Massql.parse(query));
        assertEquals(construct, e.construct());
        assertTrue(
                e.getMessage().contains("scaninfo"), "message should point at what IS supported");
    }

    @Test
    void scanrangesumMentionsItsOwnToleranceBug() {
        // Worth a specific message: implementing scanrangesum "correctly" would DISAGREE
        // with MassQL, whose engine ignores its own TOLERANCE parameter and hardcodes bins.
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scanrangesum(MS1DATA, TOLERANCE=0.1)"));
        assertEquals("scanrangesum", e.construct());
        assertTrue(e.getMessage().contains("0.1 m/z bins"), e.getMessage());
    }

    // Pipe-delimited: several of these queries contain commas inside range(min=..., max=...),
    // which the default comma delimiter would split into extra arguments.
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=X                                            | X",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=formula(C10)                            | formula()",
                "QUERY scaninfo(MS2DATA) WHERE MOBILITY=range(min=100, max=500)                     | MOBILITY",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=226.18:TOLERANCEPPM=5:EXCLUDED               | EXCLUDED",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:INTENSITYMATCHREFERENCE                  | INTENSITYMATCHREFERENCE",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:OTHERSCAN=rtrange(left=1, right=2)       | OTHERSCAN",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:MASSDEFECT=massdefect(min=0.9, max=0.99) | MASSDEFECT",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=ANY                                          | ANY",
            })
    void outOfScopeConstructsRejectByName(String query, String construct) {
        MassqlParseException e =
                assertThrows(MassqlParseException.class, () -> Massql.parse(query));
        assertEquals(construct, e.construct(), "for: " + query);
        assertTrue(UnsupportedConstructs.isUnsupported(e.construct()));
    }

    @Test
    void cardinalityRejectsUnderBothSpellings() {
        for (String kw : new String[] {"CARDINALITY", "MATCHCOUNT"}) {
            MassqlParseException e =
                    assertThrows(
                            MassqlParseException.class,
                            () ->
                                    Massql.parse(
                                            "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 OR 200):"
                                                    + kw
                                                    + "=range(min=2,max=5)"));
            assertEquals(kw, e.construct());
        }
    }

    @Test
    void nestedSubqueryRejects() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () ->
                                Massql.parse(
                                        "QUERY scaninfo(MS2DATA) WHERE MS2PREC=(QUERY scaninfo(MS1DATA) WHERE MS1MZ=100)"));
        assertEquals("nested subquery", e.construct());
    }

    /** Syntax-level rejections: the grammar itself must refuse these. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "QUERY scaninfo(MS2DATA) where MS2PROD=100 filter MS2PROD=200", // 'filter' has no
                // lowercase form
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 or 200)", // 'or' has no lowercase form
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=1e5", // no exponent form in the source regex
                "QUERY scaninfo(MS2DATA) WHERE XY=range(min=1, max=2)", // variables are single-char
                "QUERY scaninfo(MS2DATA) WHERE ms2prod=100", // condition names strictly uppercase
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:toleranceppm=5", // qualifier names
                // likewise
                "QUERY scaninfo(MS2DATA) junk", // trailing garbage (EOF anchor)
                "SELECT * FROM scans",
            })
    void syntaxErrorsRejectAsMassqlParseException(String query) {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse(query),
                        "should not parse: " + query);
        assertNotNull(e.construct());
        assertFalse(e.construct().isBlank());
    }

    /**
     * The traps that read as legal MassQL must be <b>explained</b>, not merely rejected.
     *
     * <p>{@code MassqlParserFacade.explain} adds guidance for exactly these, because ANTLR's own
     * "mismatched input 'WHERE'" tells a user nothing about what to change. The rejections are
     * covered above — <b>the guidance was not</b>, so those hints could have been deleted or broken
     * with every test still green.
     */
    @Test
    void theTrapsThatLookLikeLegalMassqlExplainThemselves() {
        // Lowercase `filter`.
        MassqlParseException lowerFilter =
                assertThrows(
                        MassqlParseException.class,
                        () ->
                                Massql.parse(
                                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 filter MS2PROD=200"));
        assertTrue(
                lowerFilter.getMessage().contains("FILTER has no lowercase form"),
                "lowercase 'filter' should say so: " + lowerFilter.getMessage());

        // Lowercase `or`.
        MassqlParseException lowerOr =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 or 200)"));
        assertTrue(
                lowerOr.getMessage().contains("OR has no lowercase form"),
                "lowercase 'or' should say so: " + lowerOr.getMessage());
    }

    @Test
    void garbageInputNeverCrashes() {
        // "Never a crash" is a hard requirement: an NPE or AIOOBE here is a bug even on junk.
        String[] junk = {
            "",
            "   ",
            "\n",
            "(((",
            ":::",
            "=",
            "QUERY",
            "QUERY scaninfo(",
            "QUERY scaninfo(MS2DATA) WHERE",
            "\t\t",
            "0",
            "()"
        };
        for (String j : junk) {
            assertThrows(
                    MassqlParseException.class,
                    () -> Massql.parse(j),
                    "expected MassqlParseException for input: " + j.replace("\n", "\\n"));
        }
        assertThrows(MassqlParseException.class, () -> Massql.parse(null));
    }
}
