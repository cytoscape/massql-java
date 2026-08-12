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

/**
 * The parser conformance suite: every reference parse either builds an AST or rejects with
 * a named construct.
 *
 * <p>⚠ an earlier draft of the parser said "every {@code scaninfo} golden
 * must parse to a canonical-equal AST". That is wrong — 20 of the 35 {@code scaninfo}
 * goldens contain out-of-scope constructs (variables, {@code MOBILITY}, {@code formula()},
 * the intensity-match family) and must reject. The real split is 15 parse / 31 reject, and
 * it is stated in the checked-in {@code corpus-manifest.tsv} so the scope decisions are
 * reviewable in one place rather than buried here.
 */
class ParseConformanceTest {

    static List<Corpus.Entry> corpus() {
        return Corpus.load();
    }

    @Test
    void corpusIsPresentAndTheExpectedSize() {
        List<Corpus.Entry> all = Corpus.load();
        // A missing or truncated corpus must FAIL, not vacuously pass with zero cases.
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
            // The parser names the FIRST out-of-scope construct in source order. Asserting a
            // specific one would pin traversal order, which has no user-visible meaning --
            // what matters is that the construct named is genuinely present and unsupported.
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
        // Two parses of the same text must agree, and canonical() must be a pure function
        // of the AST -- that is what makes it usable as the comparison key.
        MassqlQuery a = Massql.parse(e.query());
        MassqlQuery b = Massql.parse(e.query());
        assertEquals(a.canonical(), b.canonical());
        assertEquals(a, b, "AST records must be value-equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void noGoldenCausesAnUnexpectedExceptionType() {
        // "Never a crash" is a the parser requirement: every failure must be a
        // MassqlParseException, never an NPE, ISE or ArrayIndexOutOfBounds.
        for (Corpus.Entry e : Corpus.load()) {
            try {
                Massql.parse(e.query());
            } catch (MassqlParseException ok) {
                // expected for the reject set
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
