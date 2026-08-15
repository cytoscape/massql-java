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

class ParseRejectionTest {
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
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scanrangesum(MS1DATA, TOLERANCE=0.1)"));
        assertEquals("scanrangesum", e.construct());
        assertTrue(e.getMessage().contains("0.1 m/z bins"), e.getMessage());
    }

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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "QUERY scaninfo(MS2DATA) where MS2PROD=100 filter MS2PROD=200",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100 or 200)",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=1e5",
                "QUERY scaninfo(MS2DATA) WHERE XY=range(min=1, max=2)",
                "QUERY scaninfo(MS2DATA) WHERE ms2prod=100",
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100:toleranceppm=5",
                "QUERY scaninfo(MS2DATA) junk",
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

    @Test
    void theTrapsThatLookLikeLegalMassqlExplainThemselves() {
        MassqlParseException lowerFilter =
                assertThrows(
                        MassqlParseException.class,
                        () ->
                                Massql.parse(
                                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 filter MS2PROD=200"));
        assertTrue(
                lowerFilter.getMessage().contains("FILTER has no lowercase form"),
                "lowercase 'filter' should say so: " + lowerFilter.getMessage());

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
