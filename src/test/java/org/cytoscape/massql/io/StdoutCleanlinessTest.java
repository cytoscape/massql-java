package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Nothing the SDK does may write to <b>any</b> stream.
 *
 * <p><b>This is a regression test for a real trap.</b> javolution's {@code XMLStreamReaderImpl} calls
 * {@code LogContext.info(...)} every time it grows its character buffer — "Data buffer increased to
 * 262144". Large XML text nodes trigger it, and an mzML {@code <binary>} element is exactly that, so it
 * fires on essentially every real file. Measured on {@code small.mzML} before the fix: 6 lines, on
 * <b>stdout</b>.
 *
 * <p><b>The SDK logs nothing at all</b>, to stdout or stderr. That is why there are two tests below
 * rather than one: "logs only to stderr" would not satisfy the rule. Diagnostics are returned via
 * {@link SpectraStream#diagnostics()} for the caller to route.
 *
 * <p>Stray stdout output would <i>also</i> corrupt the CLI's JSON payload in its default output mode,
 * but that is a different layer, and a consequence rather than the reason.
 *
 * <p>{@code JavolutionQuiet} suppresses it by raising {@code LogContext.LEVEL} above {@code INFO}.
 * Nothing else in the test suite would notice if that stopped working.
 */
class StdoutCleanlinessTest {

    private record Captured(String out, String err) {}

    private static Captured capture(Runnable r) {
        PrintStream realOut = System.out, realErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            r.run();
        } finally {
            System.setOut(realOut);
            System.setErr(realErr);
        }
        return new Captured(
                out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void readingAnMzmlWritesNothingToStdout() {
        Path mzml = Fixtures.require("data/small.mzML");
        Captured c =
                capture(
                        () -> {
                            try (SpectraStream s = SpectraFile.open(mzml)) {
                                while (s.hasNext()) {
                                    ScanView v = s.next();
                                    v.materialize();
                                }
                            }
                        });
        assertEquals(
                "",
                c.out(),
                "the SDK must write nothing to stdout; it returns diagnostics instead. Got:\n"
                        + c.out());
        assertFalse(
                c.out().contains("Data buffer"),
                "javolution's buffer-growth logging is back; see JavolutionQuiet");
    }

    @Test
    void readingAnMzmlWritesNothingToStderrEither() {
        // The rule is "the SDK logs nothing", not "logs only to stderr". Diagnostics are
        // RETURNED via SpectraStream.diagnostics() so the caller decides where they go.
        Path mzml = Fixtures.require("data/small.mzML");
        Captured c =
                capture(
                        () -> {
                            try (SpectraStream s = SpectraFile.open(mzml)) {
                                while (s.hasNext()) {
                                    ScanView v = s.next();
                                    v.materialize();
                                }
                            }
                        });
        assertEquals("", c.err(), "the SDK wrote to stderr:\n" + c.err());
    }

    @Test
    void readingAnMgfIsSilentToo() {
        Path mgf = Fixtures.require("fixtures/micro/micro.mgf");
        Captured c =
                capture(
                        () -> {
                            try (SpectraStream s = SpectraFile.open(mgf)) {
                                while (s.hasNext()) {
                                    ScanView v = s.next();
                                    v.materialize();
                                }
                            }
                        });
        assertEquals("", c.out());
        assertEquals("", c.err());
    }
}
