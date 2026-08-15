package org.cytoscape.massql;

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

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.result.ScanInfoResult;
import org.junit.jupiter.api.Test;

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

    @Test
    void executeDoesNotCloseTheCallersStream() {
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

    @Test
    void nullOptionsMeanDefaults() {
        List<ScanInfoResult> withNull = Massql.run(Q, smallMzml(), null);
        List<ScanInfoResult> withDefaults = Massql.run(Q, smallMzml(), MassqlOptions.defaults());
        assertEquals(withDefaults.size(), withNull.size());
        assertEquals(withDefaults, withNull, "null opts must behave exactly as defaults()");
    }

    @Test
    void anEmptyResultIsAnEmptyImmutableListNeverNull() {
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

        assertEquals(6, Massql.run(Q, smallMzml(), null).size());
    }

    @Test
    void aSpentStreamFailsLoudlyRatherThanReturningNothing() {
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
