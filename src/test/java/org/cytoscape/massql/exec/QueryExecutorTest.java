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

/** End-to-end condition evaluation over the real fixtures. */
class QueryExecutorTest {

    // ---------------------------------------------------------------- helpers

    /** Fixtures live in this repo; a missing one FAILS rather than skipping. */
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

    // ---------------------------------------------------------------- the golden-backed shapes

    @Test
    void microEdgeQueryMatchesNothingBecauseBoundsAreStrict() {
        // ⛔ micro.mzML scan 3 has a peak at EXACTLY 201.0; this query's window
        // is
        // [201.0, 202.0], so the peak sits precisely on the lower bound.
        //
        // MassQL returns 0 rows -- verified by execution, and micro_mzml_edge_results.json is that
        // empty
        // golden. With an INCLUSIVE window this test returns [3] and the golden disagrees. It is
        // the only
        // assertion in the suite that can catch the bound error, because no other query isolates
        // that peak.
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
        // output/micro_mzml_results.json is [1, 3]. The query is MS2PROD=200.5:TOLERANCEMZ=0.5, so
        // the
        // window is [200.0, 201.0] and 200.5 is strictly interior -- both scans that carry it
        // qualify.
        Run r = run(query("test_micro.massql"), "fixtures/micro/micro.mzML");
        assertEquals(List.of(1, 3), r.scans(), "matches the committed golden's scan set");
    }

    @Test
    void resultsArriveInScanIdAscendingOrder() {
        // Step 10 and Step 12 both require it, and the streaming design gets it free from document
        // order --
        // so assert it rather than assume the reader's order never changes.
        Run r = run(query("test_micro.massql"), "fixtures/micro/micro.mzML");
        List<Integer> sorted = new ArrayList<>(r.scans());
        sorted.sort(null);
        assertEquals(sorted, r.scans());
    }

    // ---------------------------------------------------------------- zero-peak scans

    @Test
    void aScanLevelOnlyQueryDoesNotReturnZeroPeakScans() {
        // ⛔ -- THE case that needs an explicit guard.
        //
        // A peak-based condition fails an empty scan by itself. A SCAN-LEVEL condition never looks
        // at peaks,
        // so without the guard every one of PlusRise's 12,571 peak-less blocks would qualify.
        //
        // MassQL loads 21,942 of the 34,513 blocks (its dump says so), and cannot return more.
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
        // micro.mzML scan 4 is a deliberate zero-peak MS1. MassQL's ms1_df holds only scan 2.
        Run r = run("QUERY scaninfo(MS1DATA) WHERE POLARITY=Positive", "fixtures/micro/micro.mzML");
        assertEquals(List.of(2), r.scans(), "scan 4 is empty and cannot qualify");
    }

    @Test
    void polarityOnAnMgfMatchesEveryLoadedScan() {
        // asserted deliberately rather than discovered: MGF polarity is a
        // hardcoded 1
        // , so POLARITY=Positive matches everything and POLARITY=Negative nothing. This is
        // CORRECT
        // behaviour, not a broken filter -- it is in the Step 13 known-deviations list for that
        // reason.
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

    // ---------------------------------------------------------------- the structural rule

    @Test
    void twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan() {
        // THE direct test of §1, and the most likely structural error in the step. micro.mzML scan
        // 3 has
        // peaks at 100.0 and 300.0. No single peak can be both, so a row-level AND returns nothing
        // while
        // scan-level intersection returns scan 3.
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
        // proven from the source and pinned here. micro_ms1var.mzML is the ONLY
        // fixture
        // that can discriminate: MS1 scan 1 has 400.0, MS1 scan 3 does not, and both MS2 scans
        // carry 200.0.
        // small.mzML cannot -- its MS1 scans are profile-mode on an identical m/z grid.
        String a =
                "QUERY scaninfo(MS2DATA) WHERE MS1MZ=400.0:TOLERANCEMZ=0.1 AND MS2PROD=200.0:TOLERANCEMZ=0.1";
        String b =
                "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.0:TOLERANCEMZ=0.1 AND MS1MZ=400.0:TOLERANCEMZ=0.1";
        Run ra = run(a, "fixtures/micro/micro_ms1var.mzML");
        Run rb = run(b, "fixtures/micro/micro_ms1var.mzML");

        assertEquals(List.of(2), ra.scans(), "MassQL returns [2] -- verified against it directly");
        assertEquals(ra.scans(), rb.scans(), "swapping the conditions must not change the answer");
        // Not [2,4] (MS1MZ ignored) and not [] (over-filtered) -- the two ways this could go wrong.
        assertFalse(ra.scans().contains(4), "scan 4's MS1 (scan 3) has no 400.0 peak");
    }

    @Test
    void ms1mzOverAnMgfCannotQualifyAndSaysWhy() {
        // MGF has no survey scans at all, so MS1MZ is unsatisfiable there. A silent empty result
        // would be a
        // poor answer (§5); the diagnostic names the cause.
        Run r =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS1MZ=100.0:TOLERANCEMZ=0.5",
                        "fixtures/micro/micro.mgf");
        assertEquals(0, r.summary().qualifyingScans());
        assertTrue(
                r.summary().diagnostics().stream().anyMatch(d -> d.contains("MS1MZ")),
                "should explain that MGF has no MS1: " + r.summary().diagnostics());
    }

    // ---------------------------------------------------------------- bounds asymmetry

    @Test
    void rtBoundsAreStrictWhileScanBoundsAreInclusive() {
        // The asymmetry is real and deliberate -- RTMIN/RTMAX use >/< while SCANMIN/SCANMAX use
        // >=/<=.
        // micro.mzML MS2 scans are 1 (rt 0.0), 3 (rt 1.0), 5 (rt 2.0).

        // SCANMIN=3 is INCLUSIVE -> scans 3 and 5.
        assertEquals(
                List.of(3, 5),
                run("QUERY scaninfo(MS2DATA) WHERE SCANMIN=3", "fixtures/micro/micro.mzML")
                        .scans());
        // SCANMAX=3 is INCLUSIVE -> scans 1 and 3.
        assertEquals(
                List.of(1, 3),
                run("QUERY scaninfo(MS2DATA) WHERE SCANMAX=3", "fixtures/micro/micro.mzML")
                        .scans());

        // RTMIN=1.0 is STRICT -> scan 3 (rt exactly 1.0) is EXCLUDED, leaving 5.
        assertEquals(
                List.of(5),
                run("QUERY scaninfo(MS2DATA) WHERE RTMIN=1.0", "fixtures/micro/micro.mzML").scans(),
                "rt exactly at the bound must NOT qualify; unifying with SCANMIN would return [3, 5]");
        // RTMAX=1.0 is STRICT -> scan 3 excluded, leaving 1.
        assertEquals(
                List.of(1),
                run("QUERY scaninfo(MS2DATA) WHERE RTMAX=1.0", "fixtures/micro/micro.mzML")
                        .scans());
    }

    // ------------------------------------------------------- the two conditions that had NO filter
    // test
    //
    // CHARGE and MS2PREC are implemented in ConditionFilters (:71-72) and were exercised only by
    // PARSE
    // tests -- AstShapeTest, KeywordCaseMatrixTest, ParseRejectionTest -- plus the two .massql
    // fixtures
    // that Step 12's differential will eventually run. So the exit criterion "every 9a and
    // 9b
    // condition has a positive and a negative test" was checked while 2 of the 10 conditions had
    // neither, and nothing could tell: the whole filter could have been inverted and the suite
    // stayed
    // green. Same recurring shape -- a rule with nothing able to falsify it.

    @Test
    void chargeMatchesTheDeclaredChargeAndNothingElse() {
        // micro.mzML declares `charge state` on scan 5 ONLY (value 2); scans 1 and 3 omit it, so
        // the
        // reader gives them the 0 sentinel (EXPECTED.md: charge None for 1 and 3, 2 for 5).
        assertEquals(
                List.of(5),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=2", "fixtures/micro/micro.mzML").scans(),
                "only scan 5 declares charge 2");

        // Negative: a charge no scan declares.
        assertEquals(
                List.of(),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=3", "fixtures/micro/micro.mzML").scans());

        // CHARGE is exact equality, NOT a tolerance window -- it is an integer count, so 0 must not
        // sweep up the two scans whose charge is the "absent" sentinel by some >= reading.
        assertEquals(
                List.of(1, 3),
                run("QUERY scaninfo(MS2DATA) WHERE CHARGE=0", "fixtures/micro/micro.mzML").scans(),
                "the 0 sentinel is matchable as itself; Step 10 is what converts it to null");
    }

    @Test
    void ms2precFiltersOnTheScansOwnPrecursorWithStrictBounds() {
        // precmz per EXPECTED.md: scan 1 -> 250.25, scans 3 and 5 -> 500.0.
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

        // Negative: no scan carries this precursor.
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=999.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());

        // MS2PREC reads the scan's OWN precmz -- it must not be confused with MS2PROD, which
        // searches
        // the peak array. 500.0 is a precursor and NOT a peak in any MS2 scan, so if this condition
        // were wired to the peaks it would return nothing at all.
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
        // The window rule applied to a SCAN-level condition, where there is no SpectrumTable and so
        // no
        // mzWindowExclusive to carry it -- ConditionFilters:76 spells the > / < out by hand, which
        // means it can drift independently of the store. A tolerance placing the window edge
        // exactly
        // on scan 3's precmz of 500.0 must therefore EXCLUDE it.
        //
        // TOLERANCEMZ=0.5 around 500.5 gives (500.0, 501.0): scan 3's 500.0 sits exactly on `lo`.
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=500.5:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "precmz exactly on the bound must NOT qualify; an inclusive reading returns [3, 5]");

        // Complement, so the assertion above cannot pass for the wrong reason: nudge the window so
        // 500.0 is strictly interior and both scans qualify.
        assertEquals(
                List.of(3, 5),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2PREC=500.4:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    // ------------------------------------------------------------------------------ MS2NL
    //
    // The third condition that had no execution test, and the one with the most to get wrong: it is
    // the only condition whose match target is DERIVED (precmz - loss) rather than given, so a
    // wrong
    // sign or a peak-vs-precursor mix-up still produces plausible scan sets. the condition filters
    // named an
    // `Ms2NlTest` for it; the class was never written.
    //
    // micro.mzML, from the parity dump:
    //   scan 1  precmz 250.25  peaks [100.0, 200.5]
    //   scan 3  precmz 500.0   peaks [100.0, 200.5, 201.0, 300.0]
    //   scan 5  precmz 500.0   peaks [123.456789012345]

    @Test
    void ms2nlIsComputedFromEachScansOwnPrecursor() {
        // 500.0 - 200.0 = 300.0, a peak in scan 3 only.
        assertEquals(
                List.of(3),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=200.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());

        // THE test that the precursor is per-scan rather than global: 250.25 - 49.75 = 200.5, which
        // is
        // a peak in scan 1. Scan 3 also has a 200.5 peak, but its precmz is 500.0, so the same loss
        // points at 450.25 where it has nothing. A shared or last-seen precmz returns the wrong
        // set.
        assertEquals(
                List.of(1),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=49.75:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "scan 1 and scan 3 BOTH hold a 200.5 peak; only their differing precursors separate them");

        // Negative: a loss no scan can produce.
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=1.0:TOLERANCEMZ=0.1",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void ms2nlBoundsAreStrict() {
        // Rearranged, the window on mz is (precmz - loss - tol, precmz - loss + tol). Choosing
        // loss 199.5 with tol 0.5 puts scan 3's 300.0 peak exactly on the LOWER bound:
        //   centre = 500.0 - 199.5 = 300.5,  window (300.0, 301.0)
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=199.5:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans(),
                "a peak exactly on the derived window's bound must not qualify");

        // Complement: centre 300.0, window (299.5, 300.5) -- the same peak, now strictly interior.
        assertEquals(
                List.of(3),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=200.0:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro.mzML")
                        .scans());
    }

    @Test
    void anMs2ScanWithNoRecordedPrecursorCannotSatisfyMs2nl() {
        // precmz == 0 is the "not recorded" sentinel, NOT a precursor at m/z zero. The
        // reference excludes such a scan only incidentally -- (0 - mz) is negative while the window
        // is
        // positive -- and ConditionFilters makes it an explicit guard instead of trusting that
        // arithmetic. micro_noprecursor.mzXML is the fixture with a bare MS2 and no <precursorMz>.
        //
        // Note MassQL cannot load this file at all (KeyError: 'precursorMz'), so this pins
        // OUR
        // contract rather than parity -- which is exactly why it needs stating in a test.
        assertEquals(
                List.of(),
                run(
                                "QUERY scaninfo(MS2DATA) WHERE MS2NL=18.0:TOLERANCEMZ=0.5",
                                "fixtures/micro/micro_noprecursor.mzXML")
                        .scans(),
                "a scan with no recorded precursor must be excluded, not matched against a precmz of 0");

        // And it is not vacuous -- the same fixture DOES qualify on a peak-level condition that
        // needs
        // no precursor, so the emptiness above is the MS2NL rule and not an unreadable file.
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
        // Union, matching pd.concat over the per-value frames. 100.0 is in scans 1 and 3; 123.45…
        // only in 5.
        Run r =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(100.0 OR 123.456789012345):TOLERANCEMZ=0.01",
                        "fixtures/micro/micro.mzML");
        assertEquals(List.of(1, 3, 5), r.scans(), "any value in the list qualifies the scan");
    }
}
