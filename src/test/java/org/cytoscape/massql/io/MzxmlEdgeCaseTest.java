package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** mzXML edge cases, each derived from {@code msql_fileloading.py} rather than invented. */
class MzxmlEdgeCaseTest {

    private static List<Integer> scanIdsOf(Path p) {
        List<Integer> out = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                out.add(v.scanId());
            }
        }
        return out;
    }

    @Test
    void emptyMsLevelScansAreDropped() {
        // -- the resolution of the long-standing open item, derived from
        // source and then verified by execution rather than guessed at.
        //
        // pyteomics converts msLevel="" to None; MassQL tests `mslevel == 1` and `mslevel == 2`
        // (:434, :450), so None matches neither branch and the scan contributes ZERO rows. Not a
        // default of 1, not a diagnostic-and-keep, not a failure.
        //
        // empty_msLevel_tag.mzXML (an MSDK test resource) has 10 scans: 8 with msLevel="" and two
        // real ones -- scan 4 (MS2) and scan 8 (MS1).
        Path p = Fixtures.require("fixtures/edge/empty_msLevel_tag.mzXML");
        List<Integer> ids = scanIdsOf(p);
        assertEquals(
                List.of(4, 8),
                ids,
                "only the two scans with a real msLevel survive; the 8 with msLevel=\"\" are dropped");
    }

    @Test
    void droppedScansAreReportedNotSilent() {
        // "10 scans became 2" needs an explanation at the point of use, so the count goes through
        // diagnostics() rather than being swallowed. The SDK returns diagnostics; it never logs
        // (DEPENDENCY_POLICY constraint 2).
        Path p = Fixtures.require("fixtures/edge/empty_msLevel_tag.mzXML");
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                s.next(); /* drain */
            }
            String all = String.join("\n", s.diagnostics());
            assertTrue(
                    all.contains("msLevel"),
                    "the 8 dropped scans must be reported. diagnostics: " + all);
            assertTrue(all.contains("8"), "the count should say how many. diagnostics: " + all);
        }
    }

    @Test
    void theEdgeFixtureIsAlsoOurOnlySixtyFourBitZlibRealFile() {
        // Recorded because it is easy to lose: this fixture is precision="64" AND compressionType=
        // "zlib", unlike every other mzXML we have. Its two surviving scans therefore exercise the
        // 64-bit inflate path against a file we did not generate ourselves.
        Path p = Fixtures.require("fixtures/edge/empty_msLevel_tag.mzXML");
        String head = read(p);
        assertTrue(head.contains("precision=\"64\""), "expected a 64-bit fixture");
        assertTrue(head.contains("compressionType=\"zlib\""), "expected a zlib fixture");
        try (SpectraStream s = SpectraFile.open(p)) {
            int decoded = 0;
            while (s.hasNext()) {
                ScanView v = s.next();
                int n = v.materialize().rowCount();
                assertTrue(n > 0, "scan " + v.scanId() + " decoded to zero peaks");
                decoded++;
            }
            assertEquals(2, decoded);
        }
    }

    @Test
    void anMs2WithNoPrecursorMzGivesZeroRatherThanThrowing() {
        // and NOT parity: MassQL raises KeyError: 'precursorMz' here (verified
        // on
        // micro_noprecursor.mzXML), so no golden can exist. We give the 0 "not recorded" sentinel,
        // matching mzML's absent-MS:1000744 rule, which Step 10 converts to null. Throwing would
        // make
        // mzXML stricter than mzML for the very same missing field.
        Path p = Fixtures.require("fixtures/micro/micro_noprecursor.mzXML");
        int ms2 = 0;
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) {
                    assertEquals(
                            0.0,
                            v.precmz(),
                            "absent <precursorMz> -> the 0 sentinel (our contract, not parity)");
                    ms2++;
                }
            }
        }
        assertEquals(3, ms2, "all three MS2 scans must still be read");
    }

    @Test
    void aBarePrecursorMzWithNoAttributesIsFine() {
        // The Step 2 finding: pyteomics returns a bare STRING instead of a dict when <precursorMz>
        // carries no attributes, and MassQL then dies at :450 with "string indices must be
        // integers".
        // We read the element TEXT, so the attributes are irrelevant -- but the reader must not
        // require them either, which is what this asserts.
        Path p =
                writeMinimal(
                        "bare.mzXML",
                        "<scan num=\"1\" msLevel=\"2\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT6S\">"
                                + "<precursorMz>500.5</precursorMz>"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>");
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            assertEquals(500.5, v.precmz(), "the value is element text, not an attribute");
            assertEquals(0.1, v.rt(), 1e-12, "PT6S -> 0.1 minutes");
        }
    }

    @Test
    void peaksCountZeroYieldsAnEmptyScanAndDoesNotThrow() {
        Path p =
                writeMinimal(
                        "empty-scan.mzXML",
                        "<scan num=\"7\" msLevel=\"1\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>");
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            assertEquals(0, v.peakCount());
            assertEquals(0, v.materialize().rowCount());
            assertFalse(s.hasNext());
        }
    }

    @Test
    void aScanWithNoPeaksElementIsNotAMassSpectrum() {
        // msql_fileloading.py:424: `if not "m/z array" in spectrum: continue` -- "This is not a
        // mass
        // spectrum". So a <scan> carrying no <peaks> child at all is skipped, not an error.
        // Distinct
        // from peaksCount="0", which IS a spectrum that happens to be empty.
        Path p =
                writeMinimal(
                        "no-peaks.mzXML",
                        "<scan num=\"1\" msLevel=\"1\" polarity=\"+\" retentionTime=\"PT0S\"></scan>"
                                + "<scan num=\"2\" msLevel=\"1\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>");
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            assertEquals(2, v.scanId(), "scan 1 has no <peaks> and must be skipped");
            assertFalse(s.hasNext());
            assertTrue(
                    String.join("\n", s.diagnostics()).contains("not a mass spectrum"),
                    "the skip must be reported: " + s.diagnostics());
        }
    }

    @Test
    void msLevelAboveTwoIsSkippedAndReported() {
        Path p =
                writeMinimal(
                        "ms3.mzXML",
                        "<scan num=\"1\" msLevel=\"3\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>"
                                + "<scan num=\"2\" msLevel=\"1\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>");
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            assertEquals(2, v.scanId());
            assertFalse(s.hasNext());
            assertTrue(
                    String.join("\n", s.diagnostics()).contains("ms level > 2"),
                    "" + s.diagnostics());
        }
    }

    @Test
    void aTruncatedFileThrowsWithNoPartialResult() {
        // A reader that returns 1 of 3 scans is worse than one that fails: the shortfall surfaces
        // later as an inexplicable filtering bug. The signal is a missing </msRun>.
        Path p =
                writeRaw(
                        "truncated.mzXML",
                        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                                + "<mzXML><msRun scanCount=\"3\">\n"
                                + "<scan num=\"1\" msLevel=\"1\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>\n"
                                + "<scan num=\"2\" msLevel=\"2\" peaksCount=\"0\" polarity=\"+\" retenti");
        MassqlException e =
                assertThrows(
                        MassqlException.class,
                        () -> {
                            try (SpectraStream s = SpectraFile.open(p)) {
                                while (s.hasNext()) {
                                    s.next(); /* drain until it fails */
                                }
                            }
                        });
        assertTrue(
                e.getMessage().toLowerCase().contains("truncat")
                        || e.getMessage().toLowerCase().contains("malformed"),
                "the failure should name truncation: " + e.getMessage());
    }

    @Test
    void aScanWithNoPeaksCountDerivesItFromTheBase64Length() {
        // peaksCount is schema-required, so no real fixture omits it -- but MzxmlReader carries a
        // documented fallback for when it is absent, and the reason it exists is specific: a
        // wrongly-zero count would not merely mis-size an array, it would break the ms1scan chain,
        // which surfaces much later as a precursor-linkage bug.
        //
        // Two peaks: 4 big-endian float32s = 16 bytes, so the count is derivable exactly
        // (16 bytes / (2 values x 4 bytes) = 2).
        Path p =
                writeMinimal(
                        "no-peakscount.mzXML",
                        "<scan num=\"1\" msLevel=\"1\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\">"
                                + "QskAAER6AABDSEAARPoAAA=="
                                + "</peaks></scan>");
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            assertEquals(
                    2,
                    v.peakCount(),
                    "with peaksCount absent the count must come from the base64 length, not default to 0");
            SpectrumTable t = v.materialize();
            assertEquals(2, t.rowCount(), "the decoded peaks must agree with the derived count");
            assertEquals(100.5, t.mz(0), 1e-4);
            assertEquals(200.25, t.mz(1), 1e-4);
        }
    }

    @Test
    void aScanWithANonNumericNumIsRejected() {
        Path p =
                writeMinimal(
                        "bad-num.mzXML",
                        "<scan num=\"abc\" msLevel=\"1\" peaksCount=\"0\" polarity=\"+\" retentionTime=\"PT0S\">"
                                + "<peaks precision=\"32\" byteOrder=\"network\"></peaks></scan>");
        MassqlException e =
                assertThrows(
                        MassqlException.class,
                        () -> {
                            try (SpectraStream s = SpectraFile.open(p)) {
                                s.next();
                            }
                        });
        assertTrue(
                e.getMessage().contains("abc"),
                "the message should quote the bad value: " + e.getMessage());
    }

    // ------------------------------------------------------------------ helpers

    @TempDir Path dir;

    private Path writeMinimal(String name, String scans) {
        return writeRaw(
                name,
                "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                        + "<mzXML xmlns=\"http://sashimi.sourceforge.net/schema_revision/mzXML_2.0\">\n"
                        + "  <msRun scanCount=\"1\">\n"
                        + scans
                        + "\n  </msRun>\n</mzXML>\n");
    }

    private Path writeRaw(String name, String content) {
        try {
            Path p = dir.resolve(name);
            Files.writeString(p, content, StandardCharsets.ISO_8859_1);
            return p;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
