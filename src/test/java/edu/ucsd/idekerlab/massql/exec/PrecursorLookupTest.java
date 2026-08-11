package edu.ucsd.idekerlab.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTableBuilder;

/**
 * The most important test in the collation.
 *
 * <p>{@code SPIKE.md} §6a calls rule 1 <i>"the test that catches the most likely misreading of the whole
 * contract"</i>: the matched precursor peak is the one <b>closest in m/z</b> to {@code precmz}, not the
 * most intense one in the window. Every case below is built so that a plausible-but-wrong implementation
 * gives a different answer.
 */
class PrecursorLookupTest {

    /** A single-scan MS1 table, which is what the streaming executor retains. */
    private static SpectrumTable ms1(int scanId, double[] mz, double[] i) {
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(scanId, 0.5, 1);
        for (int k = 0; k < mz.length; k++) b.addPeak(mz[k], i[k]);
        return b.build();
    }

    /** The micro-fixture's MS1 scan 2 — built so closest and most-intense DISAGREE (EXPECTED.md). */
    private static SpectrumTable microMs1() {
        return ms1(
                2,
                new double[] {499.99609375, 500.0078125, 600.0},
                new double[] {1000.0, 5000.0, 9000.0});
    }

    // ---------------------------------------------------------------- rule 1: closest, not most
    // intense

    @Test
    void theCLOSESTpeakWinsEvenWhenAnotherInTheWindowIsFiveTimesMoreIntense() {
        // precmz 500.0 at 20 ppm -> window +/- 0.01, so both 499.99609375 (d=0.0039) and
        // 500.0078125 (d=0.0078) are candidates. The FARTHER one is 5x more intense.
        //
        // Correct: ms1_i = 1000.0 / ms1_precmz = 499.99609375  (the closer, weaker peak)
        // Wrong  : ms1_i = 5000.0 / ms1_precmz = 500.0078125   (the intuitive "most intense"
        // reading)
        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 20.0);
        assertEquals(
                1000.0,
                r.ms1I(),
                "picked the most intense candidate instead of the closest -- the headline misreading");
        assertEquals(499.99609375, r.ms1Precmz());
    }

    @Test
    void bothCandidatesReallyAreInTheWindowSoTheTestAboveIsNotVacuous() {
        // If only one peak were in the window, the assertion above would pass for the wrong reason.
        double tol = 500.0 * 20.0 / 1e6;
        assertEquals(
                2,
                microMs1().mzWindow(0, 500.0 - tol, 500.0 + tol).size(),
                "the discriminating test needs BOTH peaks in the window");
    }

    // ---------------------------------------------------------------- rule 2: base peak survives a
    // miss

    @Test
    void aToleranceMissNullsTheMatchButNOTMs1BasePeakI() {
        // 1 ppm around 500.0 is +/- 0.0005; the nearest peak is 0.0039 away, so nothing matches.
        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 1.0);
        assertNull(r.ms1I(), "no peak within 1 ppm");
        assertNull(r.ms1Precmz());
        assertEquals(
                9000.0,
                r.ms1BasePeakI(),
                "ms1_base_peak_i is the max over the WHOLE scan and does not depend on the match "
                        + "(the collation) -- note 9000.0 is the 600.0 peak, far outside any window");
    }

    @Test
    void ms1BasePeakIIsTheWholeScanMaxNotTheWindowMax() {
        // The complement of the above: even on a SUCCESSFUL match, the base peak comes from outside
        // the
        // window. A window-scoped max would return 5000.0 here.
        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 20.0);
        assertEquals(9000.0, r.ms1BasePeakI());
        assertNotEquals(5000.0, r.ms1BasePeakI(), "that would be the max WITHIN the window");
    }

    // ---------------------------------------------------------------- rule 3: ties -> lower m/z

    @Test
    void equidistantCandidatesResolveToTheLOWERMz() {
        // 499.99 and 500.01 are both exactly 0.01 from 500.0. pandas' argmin returns the FIRST
        // occurrence, which given ascending m/z is the lower one.
        //
        // ⚠ 40 ppm, NOT 20, and that matters. At 20 ppm tol is exactly 0.01, so both peaks land
        // exactly
        // ON the window bounds and this test would also be exercising rule 4 -- it failed alongside
        // the
        // on-bound test during a sabotage run, which is how the overlap surfaced. At 40 ppm tol is
        // 0.02
        // and
        // both peaks are strictly interior, so this isolates the tie rule.
        SpectrumTable t = ms1(2, new double[] {499.99, 500.01}, new double[] {100.0, 9999.0});
        double tol = 500.0 * 40.0 / 1e6;
        assertEquals(
                2,
                t.mzWindowExclusive(0, 500.0 - tol, 500.0 + tol).size(),
                "both peaks must be STRICTLY interior, so this tests ties and not bounds");

        var r = PrecursorLookup.lookup(t, 2, 500.0, 40.0);
        assertEquals(
                499.99,
                r.ms1Precmz(),
                "a tie must resolve to the lower m/z (first row), matching pandas argmin");
        assertEquals(
                100.0,
                r.ms1I(),
                "and intensity must not break the tie -- the higher-m/z peak is 100x more intense");
    }

    // ---------------------------------------------------------------- rule 4: INCLUSIVE bounds
    //

    @Test
    void aPeakExactlyONTheBoundISAcandidate() {
        // ⛔ at the unit layer; DifferentialIT's micro_onbound.mzML pair asserts the
        // same property end to end. the collation records this as verified by EXECUTION: at
        // --precursor-tol-ppm 7.8125 the reference returns ms1_i = 1000.0.
        //
        // Why 7.8125: 500.0 * 7.8125 / 1e6 = 0.00390625, and 500.0 - 0.00390625 = 499.99609375
        // EXACTLY
        // -- the m/z of the first peak. So that peak sits precisely on the window's lower bound.
        double tol = 500.0 * 7.8125 / 1e6;
        assertEquals(
                499.99609375,
                500.0 - tol,
                "the bound must land exactly on the peak, or this test proves nothing");

        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 7.8125);
        assertEquals(
                1000.0,
                r.ms1I(),
                "an on-bound peak IS a candidate: this step uses the INCLUSIVE mzWindow. If this fails, "
                        + "someone switched to mzWindowExclusive -- which is correct for the "
                        + "conditions and wrong here");
        assertEquals(499.99609375, r.ms1Precmz());
    }

    @Test
    void theExclusiveVariantWouldHaveRejectedThatPeakWhichIsWhyTheChoiceMatters() {
        // Demonstrates the divergence directly on the store, so the distinction is visible here
        // and
        // not only in the MzWindowTest.
        double tol = 500.0 * 7.8125 / 1e6;
        SpectrumTable t = microMs1();
        assertEquals(
                1,
                t.mzWindow(0, 500.0 - tol, 500.0 + tol).size(),
                "inclusive: the on-bound peak is in");
        assertEquals(
                0,
                t.mzWindowExclusive(0, 500.0 - tol, 500.0 + tol).size(),
                "exclusive: the same peak is out -- a silent null in ms1_i if wired here");
    }

    // ---------------------------------------------------------------- absent-data paths

    @Test
    void noLinkedMs1ScanGivesAllThreeNull() {
        var r = PrecursorLookup.lookup(null, 0, 500.0, 20.0);
        assertNull(r.ms1I());
        assertNull(r.ms1Precmz());
        assertNull(r.ms1BasePeakI());
    }

    @Test
    void theZeroSentinelOnMs1scanSuppressesTheLookupEvenIfATableIsPresent() {
        // This is why the collation converts sentinels AFTER the lookup: ms1scan == 0 is the
        // signal.
        var r = PrecursorLookup.lookup(microMs1(), 0, 500.0, 20.0);
        assertNull(
                r.ms1BasePeakI(), "ms1scan == 0 means 'no linked scan', so nothing is looked up");
    }

    @Test
    void precmzZeroMeansNoLookupButTheBasePeakStillPopulates() {
        // precmz == 0 is "not recorded" -- it must NOT be matched against as if it were an m/z of
        // zero.
        var r = PrecursorLookup.lookup(microMs1(), 2, 0.0, 20.0);
        assertNull(r.ms1I());
        assertNull(r.ms1Precmz());
        assertEquals(9000.0, r.ms1BasePeakI(), "the linked scan exists, so the base peak is known");
    }

    @Test
    void aNaNPrecmzIsRefusedRatherThanMatched() {
        var r = PrecursorLookup.lookup(microMs1(), 2, Double.NaN, 20.0);
        assertNull(r.ms1I());
        assertEquals(9000.0, r.ms1BasePeakI());
    }

    // ---------------------------------------------------------------- the invariant

    @Test
    void aRetainedScanThatIsNotTheLinkedScanIsAnErrorNotASilentWrongAnswer() {
        // the collation's invariant: the document-order rule guarantees the retained MS1 IS
        // ms1scan.
        // If that is ever broken upstream, reading the wrong scan's peaks would produce a plausible
        // number, so this fails loudly instead.
        SpectrumTable wrongScan = microMs1(); // holds scan 2
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> PrecursorLookup.lookup(wrongScan, 99, 500.0, 20.0));
        assertTrue(
                e.getMessage().contains("document-order"),
                "the message should name the broken rule: " + e.getMessage());
    }
}
