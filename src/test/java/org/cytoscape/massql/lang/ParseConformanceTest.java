package org.cytoscape.massql.lang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlParseException;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ParseConformanceTest {
    static List<Corpus.Entry> corpus() {
        return Corpus.load();
    }

    @Test
    void corpusIsPresentAndTheExpectedSize() {
        List<Corpus.Entry> all = Corpus.load();

        assertEquals(
                Corpus.EXPECTED_SIZE,
                all.size(),
                "reference corpus size changed; the pinned SHA has 46 files");
        assertEquals(Corpus.EXPECTED_PARSE, all.stream().filter(Corpus.Entry::shouldParse).count());
        assertEquals(Corpus.EXPECTED_REJECT, all.stream().filter(e -> !e.shouldParse()).count());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    void everyGoldenEitherParsesOrRejectsCleanly(Corpus.Entry e) {
        if (e.shouldParse()) {
            MassqlQuery q =
                    assertDoesNotThrow(
                            () -> Massql.parse(e.query()), () -> "expected to parse: " + e.query());
            assertNotNull(q.canonical());
            assertFalse(q.canonical().isBlank());
        } else {
            MassqlParseException ex =
                    assertThrows(
                            MassqlParseException.class,
                            () -> Massql.parse(e.query()),
                            () -> "expected rejection of " + e.candidates() + " in: " + e.query());

            assertTrue(
                    e.candidates().contains(ex.construct()),
                    () ->
                            "reported construct "
                                    + ex.construct()
                                    + " is not among the "
                                    + "unsupported constructs present "
                                    + e.candidates()
                                    + " in: "
                                    + e.query());
            assertTrue(
                    ex.getMessage().contains(ex.construct()),
                    () -> "message must name the construct, got: " + ex.getMessage());
            assertTrue(
                    UnsupportedConstructs.isUnsupported(ex.construct()),
                    () -> ex.construct() + " must be listed in UnsupportedConstructs");
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    void parsingIsDeterministicAndCanonicalFormIsStable(Corpus.Entry e) {
        if (!e.shouldParse()) return;

        MassqlQuery a = Massql.parse(e.query());
        MassqlQuery b = Massql.parse(e.query());
        assertEquals(a.canonical(), b.canonical());
        assertEquals(a, b, "AST records must be value-equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void noGoldenCausesAnUnexpectedExceptionType() {
        for (Corpus.Entry e : Corpus.load()) {
            try {
                Massql.parse(e.query());
            } catch (MassqlParseException ok) {
            } catch (RuntimeException bad) {
                fail(
                        "non-MassqlParseException "
                                + bad.getClass().getName()
                                + " for: "
                                + e.query(),
                        bad);
            }
        }
    }
}
