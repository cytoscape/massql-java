package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.testsupport.GoldenResults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ⛔ <b>Tests the test.</b> A golden reader that silently returns fewer rows turns the differential
 * differential into a green light that proves nothing.
 *
 * <p>This is not a hypothetical failure mode in this repository. {@code ParityDump}'s hand-rolled
 * regex stopped at {@code polarity} and silently dropped {@code charge}, {@code ms1scan} and
 * {@code precmz}; three columns went uncompared, and an MGF charge bug survived a <b>green</b>
 * the parity gate gate for five steps before surfacing at the CLI. Every rejection below
 * is one that regex-shaped parsing would have let through.
 */
class GoldenResultsTest {

    @TempDir Path dir;

    private static Path write(Path dir, String name, String content) {
        try {
            Path p = dir.resolve(name);
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One well-formed row, as the generator emits it. */
    private static String row(int scan) {
        return "{\"scan\": "
                + scan
                + ", \"precmz\": 810.79, \"ms1scan\": 2, \"rt\": 0.5, \"charge\": null,"
                + " \"tic\": 1000.0, \"mslevel\": 2, \"base_peak_i\": 500.0,"
                + " \"base_peak_mz\": 200.5, \"ms1_i\": 42.0, \"ms1_precmz\": 810.7,"
                + " \"ms1_base_peak_i\": 99.0}";
    }

    // ------------------------------------------------------------------ it reads real goldens

    @Test
    void readsACommittedGoldenFaithfully() {
        List<ScanInfoResult> rows = GoldenResults.of("small_mzml_results");
        assertEquals(6, rows.size());

        ScanInfoResult first = rows.get(0);
        assertEquals(3, first.scan());
        assertEquals(2, first.mslevel());
        assertEquals(
                586278.875, first.tic(), 0.0, "the golden's float32-accumulated tic, verbatim");
        assertNull(first.ms1I(), "this row is a tolerance miss at 20 ppm");
        assertNotNull(first.ms1BasePeakI(), "but the scan-level base peak survives the miss");
    }

    @Test
    void anEmptyGoldenIsARealResultNotAFailure() {
        // Two goldens are deliberately `[]` -- the strict-window evidence and the empty-result
        // case. Reading them must succeed and yield zero rows, because the differential asserts
        // against
        // them.
        assertTrue(GoldenResults.of("micro_mzml_edge_results").isEmpty());
        assertTrue(GoldenResults.of("dp00570_mzxml_empty_results").isEmpty());
    }

    @Test
    void everyCommittedGoldenParses() {
        // If a golden is ever regenerated into a shape this reader cannot handle, that must surface
        // here rather than as a confusing mismatch inside the differential.
        for (String name :
                List.of(
                        "small_mzml_results",
                        "small_mzml_tol60_results",
                        "small_mzxml_results",
                        "small_mzxml_tol60_results",
                        "small_mzml_ms1_results",
                        "plusrise_results",
                        "dp00570_mzxml_results",
                        "dp00570_mgf_results",
                        "dp00570_mzxml_empty_results",
                        "micro_mgf_results",
                        "micro_mzml_results",
                        "micro_mzxml_results",
                        "micro_mzml_rtseconds_results",
                        "micro_mzml_edge_results",
                        "micro_ms1var_results",
                        "micro_onbound_results")) {
            GoldenResults.requireExists(name);
            assertNotNull(GoldenResults.of(name), name);
        }
    }

    @Test
    void theMs1dataGoldenCarriesTheSameTwelveKeys() {
        // one union schema discriminated by mslevel, no key ever absent. The precursor columns
        // are PRESENT and null on an MS1 row, while base_peak_* carry real values.
        List<ScanInfoResult> rows = GoldenResults.of("small_mzml_ms1_results");
        assertEquals(14, rows.size());
        ScanInfoResult r = rows.get(0);
        assertEquals(1, r.mslevel());
        assertNull(r.precmz());
        assertNull(r.ms1scan());
        assertNull(r.charge());
        assertNotNull(r.basePeakI(), "an MS1 survey scan plainly has a base peak");
        assertNotNull(r.basePeakMz());
    }

    // ------------------------------------------------------------------ it rejects damage

    @Test
    void aTruncatedGoldenFails() {
        // The ParityDump failure mode, exactly: a lenient parser reads what it can and reports
        // success on a partial document.
        Path p = write(dir, "truncated.json", "[\n  " + row(3) + ",\n  {\"scan\": 10, \"precmz\":");
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenResults.read(p));
        assertTrue(e.getMessage().contains("not valid JSON"), e.getMessage());
    }

    @Test
    void aGoldenMissingAKeyFails() {
        // Silently reading a dropped key as null would compare "null vs null" against a genuinely
        // null column and pass -- which is how three columns went unchecked for five steps.
        String missing = row(3).replace(", \"charge\": null", "");
        Path p = write(dir, "missing-key.json", "[" + missing + "]");
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenResults.read(p));
        assertTrue(e.getMessage().contains("12 frozen keys"), e.getMessage());
        assertTrue(e.getMessage().contains("charge"), "the message must name what is missing");
    }

    @Test
    void aGoldenWithAnExtraKeyFails() {
        String extra = row(3).replace("{\"scan\"", "{\"surprise\": 1, \"scan\"");
        Path p = write(dir, "extra-key.json", "[" + extra + "]");
        assertThrows(AssertionError.class, () -> GoldenResults.read(p));
    }

    @Test
    void aNonNumericValueFails() {
        // Guards the null-vs-value policy: "null" as a STRING is not null, and must not be read as
        // one.
        Path p =
                write(
                        dir,
                        "stringy.json",
                        "[" + row(3).replace("\"tic\": 1000.0", "\"tic\": \"null\"") + "]");
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenResults.read(p));
        assertTrue(e.getMessage().contains("tic"), e.getMessage());
    }

    @Test
    void aDocumentThatIsNotAnArrayFails() {
        Path p = write(dir, "object.json", row(3));
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenResults.read(p));
        assertTrue(e.getMessage().contains("must be a JSON array"), e.getMessage());
    }

    @Test
    void aDroppedRowChangesTheCount() {
        // The reader cannot know a row is missing -- that is the differential's job -- but it must
        // report the count faithfully so the differential can. This asserts it does not, say,
        // deduplicate or skip.
        Path two = write(dir, "two.json", "[" + row(3) + "," + row(10) + "]");
        Path one = write(dir, "one.json", "[" + row(3) + "]");
        assertEquals(2, GoldenResults.read(two).size());
        assertEquals(1, GoldenResults.read(one).size());
    }

    @Test
    void aNonIntegralScanIdFails() {
        // scan/ms1scan/charge/mslevel are compared EXACTLY, so reading 3.5 as 3 would make an exact
        // comparison silently approximate.
        Path p =
                write(
                        dir,
                        "fractional.json",
                        "[" + row(3).replace("\"scan\": 3", "\"scan\": 3.5") + "]");
        AssertionError e = assertThrows(AssertionError.class, () -> GoldenResults.read(p));
        assertTrue(e.getMessage().contains("integral"), e.getMessage());
    }
}
