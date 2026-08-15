package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ResultComparator;
import org.junit.jupiter.api.Test;

class CrossFormatEquivalenceIT {
    @Test
    void pairAProducesIdenticalRowsAcrossFormats() {
        for (double tolPpm : new double[] {60.0, 20.0}) {
            List<ScanInfoResult> mzml = run("data/small.mzML", "test_mzml", tolPpm);
            List<ScanInfoResult> mzxml = run("data/small.mzXML", "test_mzml", tolPpm);

            assertEquals(6, mzml.size(), "small.mzML at " + tolPpm + " ppm");
            assertEquals(6, mzxml.size(), "small.mzXML at " + tolPpm + " ppm");

            List<String> diffs =
                    ResultComparator.compare(
                            "small.mzML vs small.mzXML @" + tolPpm + "ppm", mzml, mzxml, true);

            assertTrue(
                    diffs.isEmpty(),
                    () ->
                            "⛔ CROSS-FORMAT DIVERGENCE at "
                                    + tolPpm
                                    + " ppm -- the same data read through"
                                    + " two readers disagrees:\n  "
                                    + String.join("\n  ", diffs)
                                    + "\n\nNo golden can catch this: both goldens came through the same"
                                    + " loader. Suspect the readers, not the tolerances -- unless the mzXML"
                                    + " fixture itself was altered, in which case the cause is the fixture"
                                    + " rather than the code.");
        }
    }

    @Test
    void pairAsMs1ColumnsArePopulatedSoTheComparisonIsNotVacuous() {
        for (String fixture : List.of("data/small.mzML", "data/small.mzXML")) {
            List<ScanInfoResult> rows = run(fixture, "test_mzml", 60.0);
            for (ScanInfoResult r : rows) {
                assertNotNull(r.ms1I(), fixture + " scan " + r.scan() + ": ms1_i at 60 ppm");
                assertNotNull(
                        r.ms1Precmz(), fixture + " scan " + r.scan() + ": ms1_precmz at 60 ppm");
                assertNotNull(
                        r.ms1BasePeakI(), fixture + " scan " + r.scan() + ": ms1_base_peak_i");
            }
        }
    }

    @Test
    void pairAsPrecursorLinkSurvivedTheConversion() {
        for (String fixture : List.of("data/small.mzML", "data/small.mzXML")) {
            for (ScanInfoResult r : run(fixture, "test_mzml", 60.0)) {
                assertNotNull(
                        r.ms1scan(),
                        fixture
                                + " scan "
                                + r.scan()
                                + ": ms1scan is null. If this is the mzXML, a fixture missing"
                                + " precursorScanNum degrades this pair to the non-ms1 columns, and that"
                                + " is a fixture property, not a reader bug.");
            }
        }
    }

    @Test
    void pairBScanIdsAreDisjointSoNoJoinIsPossible() {
        Set<Integer> mzxml = scanIds(run("data/DP00570_F02.mzxml", "test_dp00570", 20.0));
        Set<Integer> mgf = scanIds(run("data/DP00570_F02.mgf", "test_dp00570", 20.0));

        assertEquals(Set.of(2, 556, 871), mzxml, "mzXML scan ids");
        assertEquals(Set.of(370, 598), mgf, "MGF scan ids -- block index, not a scan number");

        Set<Integer> intersection = new TreeSet<>(mzxml);
        intersection.retainAll(mgf);
        assertTrue(
                intersection.isEmpty(),
                () ->
                        "scan ids overlap "
                                + intersection
                                + " -- if these files ever DO share ids, this"
                                + " test's no-join premise needs revisiting, not deleting.");
    }

    @Test
    void pairBsMzxmlPopulatesEveryPrecursorColumn() {
        List<ScanInfoResult> rows = run("data/DP00570_F02.mzxml", "test_dp00570", 20.0);
        assertEquals(3, rows.size());
        for (ScanInfoResult r : rows) {
            String at = "mzXML scan " + r.scan() + ": ";
            assertNotNull(
                    r.ms1scan(),
                    at
                            + "ms1scan -- document-order linkage (the mzXML reader), zero"
                            + " precursorScanNum attributes in this file");
            assertNotNull(r.ms1I(), at + "ms1_i");
            assertNotNull(r.ms1Precmz(), at + "ms1_precmz");
            assertNotNull(r.ms1BasePeakI(), at + "ms1_base_peak_i");
            assertTrue(r.ms1scan() < r.scan(), at + "the linked MS1 must precede its MS2");
        }
    }

    @Test
    void pairBsMgfNullsEveryPrecursorColumn() {
        List<ScanInfoResult> rows = run("data/DP00570_F02.mgf", "test_dp00570", 20.0);
        assertEquals(2, rows.size());
        for (ScanInfoResult r : rows) {
            String at = "MGF scan " + r.scan() + ": ";
            assertNull(r.ms1scan(), at + "MGF has no MS1 scans to link to");
            assertNull(r.ms1I(), at + "ms1_i");
            assertNull(r.ms1Precmz(), at + "ms1_precmz");
            assertNull(r.ms1BasePeakI(), at + "ms1_base_peak_i");
            assertEquals(0.0, r.rt(), 0.0, at + "rt is a defaulted 0.0, not a null");
        }
    }

    @Test
    void pairBsChargeDiffersExactlyAsPredicted() {
        for (ScanInfoResult r : run("data/DP00570_F02.mzxml", "test_dp00570", 20.0)) {
            assertNull(
                    r.charge(),
                    "mzXML scan "
                            + r.scan()
                            + ": charge must be null -- this file has zero"
                            + " precursorCharge attributes, and raw 0 maps to null");
        }
        for (ScanInfoResult r : run("data/DP00570_F02.mgf", "test_dp00570", 20.0)) {
            assertNotNull(
                    r.charge(),
                    "MGF scan "
                            + r.scan()
                            + ": charge is never null in MGF -- an absent CHARGE= is 1,"
                            + " not 0");
            assertTrue(
                    r.charge() == 1 || r.charge() == 2,
                    "MGF scan " + r.scan() + ": charge was " + r.charge() + ", expected 1 or 2");
        }
    }

    @Test
    void pairBsSharedColumnsArePopulatedOnBothSides() {
        for (String fixture : List.of("data/DP00570_F02.mzxml", "data/DP00570_F02.mgf")) {
            for (ScanInfoResult r : run(fixture, "test_dp00570", 20.0)) {
                String at = fixture + " scan " + r.scan() + ": ";
                assertNotNull(r.precmz(), at + "precmz");
                assertNotNull(r.rt(), at + "rt");
                assertNotNull(r.tic(), at + "tic");
                assertNotNull(r.basePeakI(), at + "base_peak_i");
                assertNotNull(r.basePeakMz(), at + "base_peak_mz");
                assertEquals(2, r.mslevel(), at + "this query selects MS2DATA");
            }
        }
    }

    private static Set<Integer> scanIds(List<ScanInfoResult> rows) {
        Set<Integer> out = new LinkedHashSet<>();
        for (ScanInfoResult r : rows) out.add(r.scan());
        return out;
    }

    private static List<ScanInfoResult> run(String fixture, String query, double tolPpm) {
        return Massql.run(
                queryText(query),
                Fixtures.require(fixture),
                MassqlOptions.defaults().withPrecursorTolPpm(tolPpm));
    }

    private static String queryText(String name) {
        Path p = Fixtures.require("goldens/queries/" + name + ".massql");
        try {
            return Files.readString(p).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
