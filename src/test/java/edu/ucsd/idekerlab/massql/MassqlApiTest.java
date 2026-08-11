package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.io.ScanView;
import edu.ucsd.idekerlab.massql.io.SpectraFile;
import edu.ucsd.idekerlab.massql.io.SpectraStream;
import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * The four public entry points, and the resource rules a caller will otherwise get wrong.
 *
 * <p>Two of these assertions are about <b>ownership</b> rather than results, and they matter more
 * than they look: {@code execute} closing the caller's stream would break the multi-query-per-file
 * pattern a long-lived host needs, and {@code run} leaking on the exception path would exhaust file
 * handles in a long-running session — which surfaces nowhere near the cause.
 */
class MassqlApiTest {

    private static final String Q = "QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0";

    private static Path fixture(String relative) {
        URL url = MassqlApiTest.class.getClassLoader().getResource(relative);
        assertNotNull(url, "fixture missing from src/test/resources: " + relative);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private static Path smallMzml() {
        return fixture("data/small.mzML");
    }

    /** Records whether {@code close} ran, so ownership can be asserted rather than assumed. */
    private static final class CloseSpy implements SpectraStream {
        private final SpectraStream delegate;
        private int closes;

        CloseSpy(SpectraStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public ScanView next() {
            return delegate.next();
        }

        @Override
        public List<String> diagnostics() {
            return delegate.diagnostics();
        }

        @Override
        public void close() {
            closes++;
            delegate.close();
        }
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void executeDoesNotCloseTheCallersStream() {
        // The multi-query-per-file pattern depends on this: the caller opened it, the caller closes
        // it. A library that closes what it did not open makes try-with-resources a lie.
        try (CloseSpy spy = new CloseSpy(SpectraFile.open(smallMzml()))) {
            List<ScanInfoResult> rows = Massql.execute(Massql.parse(Q), spy, null);
            assertEquals(6, rows.size(), "sanity: this query matches 6 scans in small.mzML");
            assertEquals(0, spy.closes, "execute must NOT close a stream it did not open");
        }
    }

    @Test
    void runClosesWhatItOpened() {
        CloseSpy[] opened = new CloseSpy[1];
        List<ScanInfoResult> rows =
                Massql.run(
                        Q, smallMzml(), null, p -> opened[0] = new CloseSpy(SpectraFile.open(p)));

        assertEquals(6, rows.size());
        assertEquals(1, opened[0].closes, "run opened the stream, so run must close it");
    }

    @Test
    void runClosesWhatItOpenedEvenWhenExecutionThrows() {
        // The path that actually leaks in practice. try-with-resources gives this for free -- which
        // is the point: this test fails the moment someone "simplifies" it to an explicit close.
        CloseSpy[] opened = new CloseSpy[1];

        assertThrows(
                IllegalStateException.class,
                () ->
                        Massql.run(
                                Q,
                                smallMzml(),
                                null,
                                p -> {
                                    opened[0] =
                                            new CloseSpy(new ThrowingStream(SpectraFile.open(p)));
                                    return opened[0];
                                }));

        assertEquals(1, opened[0].closes, "run must close on the exception path too");
    }

    /** Fails partway through iteration, standing in for a reader that hits corrupt content. */
    private static final class ThrowingStream implements SpectraStream {
        private final SpectraStream delegate;
        private int served;

        ThrowingStream(SpectraStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public ScanView next() {
            if (served++ == 2) throw new IllegalStateException("injected failure mid-stream");
            return delegate.next();
        }

        @Override
        public List<String> diagnostics() {
            return delegate.diagnostics();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // ------------------------------------------------------------------ contract

    @Test
    void nullOptionsMeanDefaults() {
        // 20.0 ppm is the documented default and what massql_query.py uses, so passing null must
        // not
        // silently produce a zero tolerance -- which would match nothing and look like a data bug.
        List<ScanInfoResult> withNull = Massql.run(Q, smallMzml(), null);
        List<ScanInfoResult> withDefaults = Massql.run(Q, smallMzml(), MassqlOptions.defaults());
        assertEquals(withDefaults.size(), withNull.size());
        assertEquals(withDefaults, withNull, "null opts must behave exactly as defaults()");
    }

    @Test
    void anEmptyResultIsAnEmptyImmutableListNeverNull() {
        // the strict-window evidence: this query's window excludes a peak sitting exactly on the
        // bound, so it legitimately matches nothing. An empty answer is a valid answer.
        Path micro = fixture("fixtures/micro/micro.mzML");
        List<ScanInfoResult> rows =
                Massql.run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=201.5:TOLERANCEMZ=0.5", micro, null);

        assertNotNull(rows, "an empty result must be an empty list, never null");
        assertTrue(rows.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> rows.add(null),
                "results are immutable -- a caller must not be able to edit the answer");
    }

    @Test
    void rowsAreAscendingByScanId() {
        // the differential compares position by position, so ordering is part of the contract
        // rather
        // than an accident of iteration.
        List<ScanInfoResult> rows = Massql.run(Q, smallMzml(), null);
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(
                    rows.get(i).scan() > rows.get(i - 1).scan(),
                    "rows must ascend by scan id: "
                            + rows.get(i - 1).scan()
                            + " then "
                            + rows.get(i).scan());
        }
    }

    @Test
    void executeWithDiagnosticsCarriesTheSameRowsPlusTheNotes() {
        try (SpectraStream s = SpectraFile.open(smallMzml())) {
            ExecutionResult r = Massql.executeWithDiagnostics(Massql.parse(Q), s, null);
            assertEquals(6, r.rows().size());
            assertNotNull(r.diagnostics(), "diagnostics are empty, never null");
        }
        // execute is the same call with the notes dropped -- not a second implementation.
        assertEquals(6, Massql.run(Q, smallMzml(), null).size());
    }

    @Test
    void aSpentStreamFailsLoudlyRatherThanReturningNothing() {
        // the reshape made single-pass a property of the TYPE. Before it, a second execute
        // returned an empty list that read as "matched nothing" -- a wrong answer that looked like
        // a
        // right one. Several queries over one file means reopening the file.
        try (SpectraStream s = SpectraFile.open(smallMzml())) {
            assertEquals(6, Massql.execute(Massql.parse(Q), s, null).size());
            assertFalse(s.hasNext(), "the stream is drained after one execute");
            assertThrows(
                    NoSuchElementException.class,
                    s::next,
                    "a spent stream must throw, not quietly yield nothing");
        }
    }

    @Test
    void parseFailurePropagatesOutOfRunWithTheConstructNamed() {
        // run() does not swallow it: only the CLI maps this to an exit code, and it needs the
        // construct name to tell the user what to change.
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () ->
                                Massql.run(
                                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=formula(C10)",
                                        smallMzml(),
                                        null));
        assertEquals("formula()", e.construct());
    }

    @Test
    void theOneShotFormAgreesWithTheExplicitOne() {
        // run is a convenience over parse + open + execute, so it must not drift from them.
        List<ScanInfoResult> viaRun = Massql.run(Q, smallMzml(), null);
        List<ScanInfoResult> viaExecute;
        try (SpectraStream s = SpectraFile.open(smallMzml())) {
            viaExecute = Massql.execute(Massql.parse(Q), s, null);
        }
        assertEquals(viaExecute, viaRun);
    }

    @Test
    void executionResultDefensivelyCopies() {
        ScanInfoResult[] none = new ScanInfoResult[0];
        ExecutionResult r = new ExecutionResult(List.of(none), List.of("a note"));
        assertThrows(UnsupportedOperationException.class, () -> r.rows().add(null));
        assertThrows(UnsupportedOperationException.class, () -> r.diagnostics().add("b"));

        ExecutionResult nulls = new ExecutionResult(null, null);
        assertSame(List.of(), nulls.rows(), "null rows normalise to empty, never null");
        assertSame(List.of(), nulls.diagnostics());
    }
}
