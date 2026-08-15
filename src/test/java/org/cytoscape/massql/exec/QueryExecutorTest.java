package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.junit.jupiter.api.Test;

class QueryExecutorTest {
    private static Path resource(String relative) {
        var url = QueryExecutorTest.class.getClassLoader().getResource(relative);
        if (url == null)
            throw new AssertionError("fixture missing from src/test/resources: " + relative);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    private static String query(String name) {
        try {
            return Files.readString(resource("goldens/queries/" + name)).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Run(List<Integer> scans, ExecutionSummary summary) {}

    private static Run run(String queryText, String fixture) {
        MassqlQuery q = Massql.parse(queryText);
        List<Integer> scans = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(resource(fixture))) {
            ExecutionSummary sum =
                    QueryExecutor.execute(
                            q, s, null, (view, scan, ms1) -> scans.add(view.scanId()));
            return new Run(scans, sum);
        }
    }

    @Test
    void microEdgeQueryMatchesNothingBecauseBoundsAreStrict() {
        Run r = run(query("test_micro_edge.massql"), "fixtures/micro/micro.mzML");
        assertEquals(
                List.of(),
                r.scans(),
                "a peak exactly ON the bound must NOT match; condition windows are strict, and using "
                        + "mzWindow instead of mzWindowExclusive would return [3]");
        assertEquals(0, r.summary().qualifyingScans());
        assertFalse(
                r.summary().diagnostics().isEmpty(),
                "an empty result must carry a diagnostic explaining itself (§5), not be silent");
    }

    @Test
    void microQueryMatchesTheGoldenScans() {
        Run r = run(query("test_micro.massql"), "fixtures/micro/micro.mzML");
        assertEquals(List.of(1, 3), r.scans(), "matches the committed golden's scan set");
    }

    @Test
    void resultsArriveInScanIdAscendingOrder() {
        Run r = run(query("test_micro.massql"), "fixtures/micro/micro.mzML");
        List<Integer> sorted = new ArrayList<>(r.scans());
        sorted.sort(null);
        assertEquals(sorted, r.scans());
    }

    @Test
    void aScanLevelOnlyQueryDoesNotReturnZeroPeakScans() {
        Run r = run("QUERY scaninfo(MS2DATA) WHERE POLARITY=Positive", "data/PlusRise.mgf");
        assertEquals(34_513, r.summary().scansExamined(), "the reader yields every block");
        assertEquals(
                21_942,
                r.summary().qualifyingScans(),
                "must equal MassQL's loaded scan count -- without the zero-peak guard this is 34,513, "
                        + "a third of the result set wrong, silently");
        assertTrue(
                r.summary().diagnostics().stream().anyMatch(d -> d.contains("zero-peak")),
                "the 12,571 skipped scans must be reported: " + r.summary().diagnostics());
    }

    @Test
    void aZeroPeakMs1IsNeverReturnedByAnMs1dataQuery() {
        Run r = run("QUERY scaninfo(MS1DATA) WHERE POLARITY=Positive", "fixtures/micro/micro.mzML");
        assertEquals(List.of(2), r.scans(), "scan 4 is empty and cannot qualify");
    }

    @Test
    void polarityOnAnMgfMatchesEveryLoadedScan() {
        assertEquals(
                21_942,
                run("QUERY scaninfo(MS2DATA) WHERE POLARITY=Positive", "data/PlusRise.mgf")
                        .summary()
                        .qualifyingScans());
        assertEquals(
                0,
                run("QUERY scaninfo(MS2DATA) WHERE POLARITY=Negative", "data/PlusRise.mgf")
                        .summary()
                        .qualifyingScans());
    }

    @Test
    void twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan() {
        Run r =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=100.0:TOLERANCEMZ=0.1 "
                                + "AND MS2PROD=300.0:TOLERANCEMZ=0.1",
                        "fixtures/micro/micro.mzML");
        assertTrue(
                r.scans().contains(3),
                "scan 3 has BOTH peaks, in different rows. A row-level AND implementation returns [] here");
    }

    @Test
    void conditionOrderDoesNotChangeTheAnswer() {
        String a =
                "QUERY scaninfo(MS2DATA) WHERE MS1MZ=400.0:TOLERANCEMZ=0.1 AND MS2PROD=200.0:TOLERANCEMZ=0.1";
        String b =
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.0:TOLERANCEMZ=0.1 AND MS1MZ=400.0:TOLERANCEMZ=0.1";
        Run ra = run(a, "fixtures/micro/micro_ms1var.mzML");
        Run rb = run(b, "fixtures/micro/micro_ms1var.mzML");

        assertEquals(List.of(2), ra.scans(), "MassQL returns [2] -- verified against it directly");
        assertEquals(ra.scans(), rb.scans(), "swapping the conditions must not change the answer");

        assertFalse(ra.scans().contains(4), "scan 4's MS1 (scan 3) has no 400.0 peak");
    }

    @Test
    void ms1mzOverAnMgfCannotQualifyAndSaysWhy() {
        Run r =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS1MZ=100.0:TOLERANCEMZ=0.5",
                        "fixtures/micro/micro.mgf");
        assertEquals(0, r.summary().qualifyingScans());
        assertTrue(
                r.summary().diagnostics().stream().anyMatch(d -> d.contains("MS1MZ")),
                "should explain that MGF has no MS1: " + r.summary().diagnostics());
    }

    @Test
    void rtBoundsAreStrictWhileScanBoundsAreInclusive() {
        assertEquals(
                List.of(3, 5),
                run("QUERY scaninfo(MS2DATA) WHERE SCANMIN=3", "fixtures/micro/micro.mzML")
                        .scans());

        assertEquals(
                List.of(1, 3),
                run("QUERY scaninfo(MS2DATA) WHERE SCANMAX=3", "fixtures/micro/micro.mzML")
                        .scans());

        assertEquals(
                List.of(5),
                run("QUERY scaninfo(MS2DATA) WHERE RTMIN=1.0", "fixtures/micro/micro.mzML").scans(),
                "rt exactly at the bound must NOT qualify; unifying with SCANMIN would return [3, 5]");

        assertEquals(
                List.of(1),
                run("QUERY scaninfo(MS2DATA) WHERE RTMAX=1.0", "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void chargeMatchesTheDeclaredChargeAndNothingElse() {
        assertEquals(
                List.of(5),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=2", "fixtures/micro/micro.mzML").scans(),
                "only scan 5 declares charge 2");

        assertEquals(
                List.of(),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=3", "fixtures/micro/micro.mzML").scans());

        assertEquals(
                List.of(1, 3),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=0", "fixtures/micro/micro.mzML").scans(),
                "the 0 sentinel is matchable as itself; collation is what converts it to null");
    }

    @Test
    void ms2precFiltersOnTheScansOwnPrecursorWithStrictBounds() {
        assertEquals(
                List.of(3, 5),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=500.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());
        assertEquals(
                List.of(1),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=250.25:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());

        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=999.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());

        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=500.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "500.0 is a precursor m/z, not a fragment -- this is the contrast that proves MS2PREC "
                        + "reads scan metadata rather than the peak array");
    }

    @Test
    void ms2precBoundsAreStrictNotInclusive() {
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=500.5:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "precmz exactly on the bound must NOT qualify; an inclusive reading returns [3, 5]");

        assertEquals(
                List.of(3, 5),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=500.4:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void ms2nlIsComputedFromEachScansOwnPrecursor() {
        assertEquals(
                List.of(3),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=200.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());

        assertEquals(
                List.of(1),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=49.75:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "scan 1 and scan 3 BOTH hold a 200.5 peak; only their differing precursors separate them");

        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=1.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void ms2nlBoundsAreStrict() {
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=199.5:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "a peak exactly on the derived window's bound must not qualify");

        assertEquals(
                List.of(3),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=200.0:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void anMs2ScanWithNoRecordedPrecursorCannotSatisfyMs2nl() {
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=18.0:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro_noprecursor.mzXML")
                        .scans(),
                "a scan with no recorded precursor must be excluded, not matched against a precmz of 0");

        assertFalse(
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro_noprecursor.mzXML")
                        .scans()
                        .isEmpty(),
                "if this is empty too, the test above proves nothing about MS2NL");
    }

    @Test
    void anOrValueListIsSatisfiedByAnyValue() {
        Run r =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100.0 OR 123.456789012345):TOLERANCEMZ=0.01",
                        "fixtures/micro/micro.mzML");
        assertEquals(List.of(1, 3, 5), r.scans(), "any value in the list qualifies the scan");
    }
}
