package org.cytoscape.massql.exec;

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

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.testsupport.GoldenResults;
import org.cytoscape.massql.testsupport.ResultComparator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DifferentialIT {
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
                new Pair("data/DP00570_F02.mzxml", "test", "dp00570_mzxml_empty_results", 0),
                new Pair("fixtures/micro/micro.mgf", "test_micro", "micro_mgf_results", 2),
                new Pair("fixtures/micro/micro.mzML", "test_micro", "micro_mzml_results", 2),
                new Pair("fixtures/micro/micro.mzXML", "test_micro", "micro_mzxml_results", 2),
                new Pair(
                        "fixtures/micro/micro_rtseconds.mzML",
                        "test_micro",
                        "micro_mzml_rtseconds_results",
                        2),
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
                                + " difference(s) vs the golden:\n  "
                                + String.join("\n  ", diffs.subList(0, Math.min(diffs.size(), 20)))
                                + (diffs.size() > 20
                                        ? "\n  … and " + (diffs.size() - 20) + " more"
                                        : "")
                                + "\n\nDo NOT loosen a tolerance to make this pass. If ms1_i or ms1_precmz"
                                + " is null where the golden has a value, check the m/z window method"
                                + " first: the precursor lookup must use the INCLUSIVE mzWindow.");
    }

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

    private static Path resource(String relative) {
        URL url = DifferentialIT.class.getClassLoader().getResource(relative);
        assertNotNull(
                url,
                "fixture missing from src/test/resources: "
                        + relative
                        + " -- every fixture is committed in-repo. Restore it; never skip.");
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
