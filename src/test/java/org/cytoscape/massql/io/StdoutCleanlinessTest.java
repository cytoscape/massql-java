package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

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
                                    v.peaks();
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
        Path mzml = Fixtures.require("data/small.mzML");
        Captured c =
                capture(
                        () -> {
                            try (SpectraStream s = SpectraFile.open(mzml)) {
                                while (s.hasNext()) {
                                    ScanView v = s.next();
                                    v.peaks();
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
                                    v.peaks();
                                }
                            }
                        });
        assertEquals("", c.out());
        assertEquals("", c.err());
    }
}
