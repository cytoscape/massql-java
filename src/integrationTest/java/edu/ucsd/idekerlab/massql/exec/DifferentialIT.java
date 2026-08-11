package edu.ucsd.idekerlab.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import edu.ucsd.idekerlab.massql.Massql;
import edu.ucsd.idekerlab.massql.MassqlOptions;
import edu.ucsd.idekerlab.massql.io.GoldenResults;
import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * ⛔ <b>THE GATE.</b> Every fixture/golden pair, compared column by column against the answers
 * MassQL's own Python implementation produced.
 *
 * <p>This table <i>is</i> the spike's exit criterion. Green means the SDK reproduces MassQL on real
 * files in all three formats; anything less is a finding to report, not a threshold to adjust.
 *
 * <h2>What makes this different from the parity gate</h2>
 *
 * <p>Step 8 proves the three <b>readers</b> decode bit-identically. This proves the whole
 * <b>pipeline</b> — parse, filter, collate, precursor lookup — arrives at the same rows. A decode
 * error surfaces at Step 8, where it is a one-line fix; a filtering or collation error surfaces
 * here. Keeping them apart is what makes a failure here attributable.
 *
 * <p>⚠ The comparison policy lives in {@link ResultComparator}, deliberately in one place, and
 * {@code ResultComparatorTest} proves it detects a single-bit difference. <b>Do not loosen a
 * tolerance to reach green</b> — the one documented exception is {@code tic}, and it absorbs error in
 * the <i>reference</i> rather than in us.
 *
 * <p>Goldens are read by {@code GoldenResults}, which rejects a truncated or short file rather than
 * comparing fewer rows. Both matter: a lenient reader and a lenient comparator fail the
 * same way, by reporting green.
 */
class DifferentialIT {

    /**
     * One fixture/golden pair.
     *
     * @param float32Mz true for mzXML, whose {@code precision="32"} truncates a measured
     *     {@code ms1_precmz} — the only column that allowance touches
     */
    private record Pair(
            String fixture,
            String query,
            String golden,
            int rows,
            double tolPpm,
            boolean float32Mz) {

        Pair(String fixture, String query, String golden, int rows) {
            this(
                    fixture,
                    query,
                    golden,
                    rows,
                    MassqlOptions.DEFAULT_PRECURSOR_TOL_PPM,
                    fixture.toLowerCase().endsWith("mzxml"));
        }

        @Override
        public String toString() {
            return golden
                    + (tolPpm == MassqlOptions.DEFAULT_PRECURSOR_TOL_PPM
                            ? ""
                            : " @" + tolPpm + "ppm");
        }
    }

    /**
     * All 16 pairs from the differential, with the row count the spec states.
     *
     * <p>The counts are asserted, not derived from the golden — otherwise a golden regenerated to
     * zero rows would agree with a broken engine that also returns none.
     */
    private static List<Pair> pairs() {
        return List.of(
                new Pair("data/small.mzML", "test_mzml", "small_mzml_results", 6),
                new Pair(
                        "data/small.mzML", "test_mzml", "small_mzml_tol60_results", 6, 60.0, false),
                new Pair("data/small.mzXML", "test_mzml", "small_mzxml_results", 6),
                new Pair(
                        "data/small.mzXML",
                        "test_mzml",
                        "small_mzxml_tol60_results",
                        6,
                        60.0,
                        true),
                new Pair("data/small.mzML", "test_ms1", "small_mzml_ms1_results", 14),
                new Pair("data/PlusRise.mgf", "test", "plusrise_results", 664),
                new Pair("data/DP00570_F02.mzxml", "test_dp00570", "dp00570_mzxml_results", 3),
                new Pair("data/DP00570_F02.mgf", "test_dp00570", "dp00570_mgf_results", 2),
                // Deliberately empty: test.massql is the metabolomics query and matches nothing in
                // this proteomics file. An empty golden is a real assertion, not a missing one.
                new Pair("data/DP00570_F02.mzxml", "test", "dp00570_mzxml_empty_results", 0),
                new Pair("fixtures/micro/micro.mgf", "test_micro", "micro_mgf_results", 2),
                new Pair("fixtures/micro/micro.mzML", "test_micro", "micro_mzml_results", 2),
                new Pair("fixtures/micro/micro.mzXML", "test_micro", "micro_mzxml_results", 2),
                new Pair(
                        "fixtures/micro/micro_rtseconds.mzML",
                        "test_micro",
                        "micro_mzml_rtseconds_results",
                        2),
                // The STRICT half of the condition window's bound sits exactly on scan 3's
                // 201.0
                // peak and MassQL excludes it. Inclusive bounds here would return 1 row, not 0.
                new Pair(
                        "fixtures/micro/micro.mzML",
                        "test_micro_edge",
                        "micro_mzml_edge_results",
                        0),
                new Pair(
                        "fixtures/micro/micro_ms1var.mzML",
                        "test_micro_ms1var",
                        "micro_ms1var_results",
                        1),
                // The INCLUSIVE half of the window rule, at the lookup rather than the condition.
                // Its MS1 peak
                // sits at 499.99 -- exactly the 20 ppm lower bound for precmz 500.0, and the same
                // bits on both sides. The reference's `>=` matches it, so ms1_i is 7000.0; the
                // exclusive variant yields null, which the null-vs-value rule reports. Its
                // ms1_base_peak_i is a different peak (9000.0), so the two cannot be conflated.
                new Pair(
                        "fixtures/micro/micro_onbound.mzML",
                        "test_micro_onbound",
                        "micro_onbound_results",
                        1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void reproducesThePythonGolden(Pair pair) {
        List<ScanInfoResult> golden = GoldenResults.of(pair.golden());
        assertEquals(
                pair.rows(),
                golden.size(),
                pair.golden()
                        + ": the golden itself changed shape -- the differential states "
                        + pair.rows()
                        + " rows. Regenerate the spec or the golden, deliberately.");

        List<ScanInfoResult> actual =
                Massql.run(
                        queryText(pair.query()),
                        fixture(pair.fixture()),
                        MassqlOptions.defaults().withPrecursorTolPpm(pair.tolPpm()));

        List<String> diffs =
                ResultComparator.compare(pair.toString(), golden, actual, pair.float32Mz());

        assertTrue(
                diffs.isEmpty(),
                () ->
                        "⛔ DIFFERENTIAL FAILURE -- "
                                + diffs.size()
                                + " difference(s) vs the Python golden:\n  "
                                + String.join("\n  ", diffs.subList(0, Math.min(diffs.size(), 20)))
                                + (diffs.size() > 20
                                        ? "\n  … and " + (diffs.size() - 20) + " more"
                                        : "")
                                + "\n\nDo NOT loosen a tolerance to make this pass. If ms1_i or ms1_precmz"
                                + " is null where the golden has a value, check the m/z window method"
                                + " first: Step 10's lookup must use the INCLUSIVE mzWindow.");
    }

    /** The two empty goldens deserve their own assertion, so `[]` can never read as "not checked". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("emptyPairs")
    void aQueryThatMatchesNothingReturnsNoRows(Pair pair) {
        List<ScanInfoResult> actual =
                Massql.run(queryText(pair.query()), fixture(pair.fixture()), null);
        assertTrue(
                actual.isEmpty(),
                () ->
                        pair.golden()
                                + ": expected no matches, got "
                                + actual.size()
                                + " row(s). For micro_mzml_edge this means condition windows became"
                                + " INCLUSIVE -- the exact regression this pair guards.");
        assertTrue(GoldenResults.of(pair.golden()).isEmpty(), "and the golden agrees");
    }

    private static List<Pair> emptyPairs() {
        return pairs().stream().filter(p -> p.rows() == 0).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pairs")
    void everyRowCarriesTheTwelveKeyShape(Pair pair) {
        // one union schema discriminated by mslevel, no key ever absent. The MS1DATA pair is
        // the one that used to disagree -- precursor columns present and null, base_peak_* real.
        for (ScanInfoResult r : GoldenResults.of(pair.golden())) {
            assertNotNull(r.scan(), "scan is never null");
            assertNotNull(r.rt(), "rt is never null -- 0.0 is a real retention time");
            assertNotNull(r.tic(), "tic is never null");
            assertNotNull(r.mslevel(), "mslevel is the discriminator");
            assertNotNull(r.basePeakI(), "base_peak_i is non-null even on an MS1 row");
            assertNotNull(r.basePeakMz(), "base_peak_mz likewise");
            if (r.mslevel() == 1) {
                assertNull(r.precmz(), "an MS1 survey scan has no precursor");
                assertNull(r.ms1scan());
                assertNull(r.charge());
            }
        }
    }

    // ------------------------------------------------------------------ fixture lookup

    // Reimplemented rather than reusing io/Fixtures, which is package-private to ...massql.io.
    // CollationAnchorIT hit the same wall; this is the documented shape of that workaround.
    private static Path resource(String relative) {
        URL url = DifferentialIT.class.getClassLoader().getResource(relative);
        assertNotNull(
                url,
                "fixture missing from src/test/resources: "
                        + relative
                        + " -- fixtures are committed in-repo. Restore it, or run"
                        + " `make fixtures` for the two Ewing files. Never skip.");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("fixture URL is not a usable file path: " + url, e);
        }
    }

    private static Path fixture(String relative) {
        return resource(relative);
    }

    private static String queryText(String name) {
        try {
            return Files.readString(resource("goldens/queries/" + name + ".massql")).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
