package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.result.ScanInfoResult;
import org.junit.jupiter.api.Test;

class CollationAnchorIT {
    private static Path resource(String relative) {
        var url = CollationAnchorIT.class.getClassLoader().getResource(relative);
        if (url == null) {
            throw new AssertionError(
                    "fixture missing from src/test/resources: "
                            + relative
                            + " -- fixtures are committed in-repo; restore it rather than skipping");
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private static List<ScanInfoResult> run(double tolPpm) {
        MassqlOptions opts = MassqlOptions.defaults().withPrecursorTolPpm(tolPpm);
        ScaninfoCollation c = new ScaninfoCollation(opts);
        String q;
        try {
            q = Files.readString(resource("goldens/queries/test_mzml.massql")).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (SpectraStream s = SpectraFile.open(resource("data/small.mzML"))) {
            QueryExecutor.execute(Massql.parse(q), s, opts, c);
        }
        return c.rows();
    }

    private static void assertTicClose(double expected, double actual) {
        assertEquals(
                expected,
                actual,
                Math.abs(expected) * 1e-6,
                "tic is a float32 accumulation on the reference side; relative 1e-6");
    }

    @Test
    void theFirstGoldenRecordIsReproducedFieldByFieldAtTheDefaultTwentyPpm() {
        ScanInfoResult r = run(20.0).get(0);

        assertEquals(3, r.scan());
        assertEquals(810.79, r.precmz());
        assertEquals(2, r.ms1scan());

        assertEquals(0.011218333333333334, r.rt());
        assertEquals(
                Double.doubleToLongBits(0.011218333333333334),
                Double.doubleToLongBits(r.rt()),
                "rt must be bit-identical, not merely close");
        assertNull(r.charge(), "small.mzML records no charge state -> 0 sentinel -> null");
        assertTicClose(586278.875, r.tic());
        assertEquals(2, r.mslevel());

        assertEquals(161140.859375, r.basePeakI());
        assertEquals(736.6370849609375, r.basePeakMz());

        assertNull(r.ms1I(), "34.8 ppm away, so no match at 20 ppm");
        assertNull(r.ms1Precmz());
        assertEquals(
                183838.71875,
                r.ms1BasePeakI(),
                "ms1_base_peak_i SURVIVES the tolerance miss (the collation) -- if this is null, the "
                        + "lookup is nulling it along with the match");
    }

    @Test
    void theSameRecordPopulatesAllThreeMs1ColumnsAtSixtyPpm() {
        ScanInfoResult r = run(60.0).get(0);

        assertEquals(3, r.scan(), "same row, same query -- only the flag differs");
        assertEquals(131528.0625, r.ms1I());
        assertEquals(
                810.8182000219822,
                r.ms1Precmz(),
                "the MEASURED centroid, ~34.8 ppm off the reported precmz of 810.79");
        assertEquals(
                183838.71875,
                r.ms1BasePeakI(),
                "unchanged by the tolerance -- it never depended on it");

        ScanInfoResult at20 = run(20.0).get(0);
        assertEquals(at20.precmz(), r.precmz());
        assertEquals(at20.basePeakI(), r.basePeakI());
        assertEquals(at20.basePeakMz(), r.basePeakMz());
        assertEquals(at20.tic(), r.tic());
        assertEquals(at20.rt(), r.rt());
    }

    @Test
    void theTwoRunsSelectTheSameSixScansBecauseTheKnobsAreSeparate() {
        List<Integer> at20 = run(20.0).stream().map(ScanInfoResult::scan).toList();
        List<Integer> at60 = run(60.0).stream().map(ScanInfoResult::scan).toList();
        assertEquals(6, at20.size(), "small_mzml_results.json has 6 records");
        assertEquals(
                at20, at60, "the precursor tolerance is a separate knob from the query tolerance");
    }

    @Test
    void fourOfTheSixRowsAreToleranceMissesAtTwentyPpmAndNoneAreAtSixty() {
        long missesAt20 = run(20.0).stream().filter(r -> r.ms1I() == null).count();
        assertEquals(4, missesAt20, "4 of 6 rows miss at 20 ppm");
        assertEquals(
                0, run(60.0).stream().filter(r -> r.ms1I() == null).count(), "none miss at 60 ppm");

        assertTrue(
                run(20.0).stream().allMatch(r -> r.ms1BasePeakI() != null),
                "ms1_base_peak_i is populated whenever the linked MS1 scan exists, miss or not");
    }

    @Test
    void resultsAreScanIdAscending() {
        List<Integer> scans = run(20.0).stream().map(ScanInfoResult::scan).toList();
        assertEquals(scans.stream().sorted().toList(), scans);
        assertEquals(List.of(3, 10, 17, 24, 37, 44), scans, "the golden's scan ids");
    }
}
