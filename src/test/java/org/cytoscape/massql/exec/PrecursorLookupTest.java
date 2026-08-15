package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;
import org.junit.jupiter.api.Test;

class PrecursorLookupTest {
    private static SpectrumTable ms1(int scanId, double[] mz, double[] i) {
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(scanId, 0.5, 1);
        for (int k = 0; k < mz.length; k++) b.addPeak(mz[k], i[k]);
        return b.build();
    }

    private static SpectrumTable microMs1() {
        return ms1(
                2,
                new double[] {499.99609375, 500.0078125, 600.0},
                new double[] {1000.0, 5000.0, 9000.0});
    }

    @Test
    void theCLOSESTpeakWinsEvenWhenAnotherInTheWindowIsFiveTimesMoreIntense() {
        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 20.0);
        assertEquals(
                1000.0,
                r.ms1I(),
                "picked the most intense candidate instead of the closest -- the headline misreading");
        assertEquals(499.99609375, r.ms1Precmz());
    }

    @Test
    void bothCandidatesReallyAreInTheWindowSoTheTestAboveIsNotVacuous() {
        double tol = 500.0 * 20.0 / 1e6;
        assertEquals(
                2,
                microMs1().mzWindow(0, 500.0 - tol, 500.0 + tol).size(),
                "the discriminating test needs BOTH peaks in the window");
    }

    @Test
    void aToleranceMissNullsTheMatchButNOTMs1BasePeakI() {
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
        var r = PrecursorLookup.lookup(microMs1(), 2, 500.0, 20.0);
        assertEquals(9000.0, r.ms1BasePeakI());
        assertNotEquals(5000.0, r.ms1BasePeakI(), "that would be the max WITHIN the window");
    }

    @Test
    void equidistantCandidatesResolveToTheLOWERMz() {
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
                "a tie must resolve to the lower m/z (first row), matching the reference");
        assertEquals(
                100.0,
                r.ms1I(),
                "and intensity must not break the tie -- the higher-m/z peak is 100x more intense");
    }

    @Test
    void aPeakExactlyONTheBoundISAcandidate() {
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

    @Test
    void noLinkedMs1ScanGivesAllThreeNull() {
        var r = PrecursorLookup.lookup(null, 0, 500.0, 20.0);
        assertNull(r.ms1I());
        assertNull(r.ms1Precmz());
        assertNull(r.ms1BasePeakI());
    }

    @Test
    void theZeroSentinelOnMs1scanSuppressesTheLookupEvenIfATableIsPresent() {
        var r = PrecursorLookup.lookup(microMs1(), null, 500.0, 20.0);
        assertNull(
                r.ms1BasePeakI(), "ms1scan == 0 means 'no linked scan', so nothing is looked up");
    }

    @Test
    void precmzZeroMeansNoLookupButTheBasePeakStillPopulates() {
        var r = PrecursorLookup.lookup(microMs1(), 2, null, 20.0);
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

    @Test
    void aRetainedScanThatIsNotTheLinkedScanIsAnErrorNotASilentWrongAnswer() {
        SpectrumTable wrongScan = microMs1();
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> PrecursorLookup.lookup(wrongScan, 99, 500.0, 20.0));
        assertTrue(
                e.getMessage().contains("document-order"),
                "the message should name the broken rule: " + e.getMessage());
    }
}
