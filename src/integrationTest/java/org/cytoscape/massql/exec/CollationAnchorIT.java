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

/**
 * The anchor: {@code small.mzML}'s first golden record, reproduced field by field at <b>both</b>
 * tolerances.
 *
 * <p>An IT rather than a unit test because it reads the real {@code data/small.mzML} and its committed
 * goldens. A missing fixture <b>fails</b> — there is no skip path.
 *
 * <p><b>Why both tolerances.</b> Scan 3 is itself the tolerance-miss case: its nearest MS1 peak is
 * <b>34.8 ppm</b> away, so at the documented 20 ppm default {@code ms1_i}/{@code ms1_precmz} are null
 * while {@code ms1_base_peak_i} survives — and at 60 ppm all three populate. The pair is the cleanest
 * available proof of the collation, because the *only* thing that differs between the two runs is one
 * flag.
 */
class CollationAnchorIT {

    /**
     * A missing fixture FAILS — there is no skip path. {@code io.Fixtures} is
     * package-private to that package, so this mirrors the helper the sibling {@code exec} tests use.
     */
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

    /**
     * {@code tic} is compared at RELATIVE 1e-6, not bit-identically.
     *
     * <p>MassQL's intensity column is {@code float32} and {@code tic} is a pandas {@code groupby.sum()}
     * over it, so the golden carries float32 accumulation error while our float64 sum is <b>exact</b>. The
     * error is in the reference, not in us: golden {@code 586278.875} vs our {@code 586278.8533592224},
     * relative <b>3.691e-08</b>. All six golden rows differ.
     *
     * <p>Do not "fix" our sum by accumulating in float, and do not extend this tolerance to the other
     * intensity columns — {@code base_peak_i}, {@code ms1_i} and {@code ms1_base_peak_i} are
     * {@code max()}/lookup <i>selections</i> with no accumulation, and are bit-identical.
     */
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
        // Bit-identical, and it CANNOT survive a float round-trip -- this is why ScanIndex.rtOf is
        // a
        // double rather than the per-peak float column.
        assertEquals(0.011218333333333334, r.rt());
        assertEquals(
                Double.doubleToLongBits(0.011218333333333334),
                Double.doubleToLongBits(r.rt()),
                "rt must be bit-identical, not merely close");
        assertNull(r.charge(), "small.mzML records no charge state -> 0 sentinel -> null");
        assertTicClose(586278.875, r.tic());
        assertEquals(2, r.mslevel());

        // Bit-identical: selections, not accumulations.
        assertEquals(161140.859375, r.basePeakI());
        assertEquals(736.6370849609375, r.basePeakMz());

        // THE tolerance-miss case: the nearest MS1 peak is 34.8 ppm from precmz, so at 20 ppm the
        // match
        // fails -- but the base peak of the linked MS1 scan is known regardless.
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

        // And the columns the tolerance must NOT touch are identical across the two runs.
        ScanInfoResult at20 = run(20.0).get(0);
        assertEquals(at20.precmz(), r.precmz());
        assertEquals(at20.basePeakI(), r.basePeakI());
        assertEquals(at20.basePeakMz(), r.basePeakMz());
        assertEquals(at20.tic(), r.tic());
        assertEquals(at20.rt(), r.rt());
    }

    @Test
    void theTwoRunsSelectTheSameSixScansBecauseTheKnobsAreSeparate() {
        // precursorTolPpm matches a peak WITHIN an already-selected scan; it must not change WHICH
        // scans
        // qualify. Conflating it with the query's own TOLERANCEPPM would change the row count here.
        List<Integer> at20 = run(20.0).stream().map(ScanInfoResult::scan).toList();
        List<Integer> at60 = run(60.0).stream().map(ScanInfoResult::scan).toList();
        assertEquals(6, at20.size(), "small_mzml_results.json has 6 records");
        assertEquals(
                at20, at60, "the precursor tolerance is a separate knob from the query tolerance");
    }

    @Test
    void fourOfTheSixRowsAreToleranceMissesAtTwentyPpmAndNoneAreAtSixty() {
        // The distribution the golden records, asserted as a shape rather than row by row -- and
        // the
        // reason the 20 ppm golden exists at all: it supplies the only golden coverage of the
        // "a miss nulls ms1_i but not ms1_base_peak_i" rule.
        long missesAt20 = run(20.0).stream().filter(r -> r.ms1I() == null).count();
        assertEquals(4, missesAt20, "4 of 6 rows miss at 20 ppm");
        assertEquals(
                0, run(60.0).stream().filter(r -> r.ms1I() == null).count(), "none miss at 60 ppm");

        // Every row keeps its ms1_base_peak_i either way -- that is the invariant under test.
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
