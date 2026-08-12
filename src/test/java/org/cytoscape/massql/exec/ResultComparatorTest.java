package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.cytoscape.massql.result.ScanInfoResult;
import org.junit.jupiter.api.Test;

/**
 * ⛔ <b>Tests the test.</b> {@link ResultComparator} is the differential gate; a comparator that
 * always passes yields a meaningless exit criterion.
 *
 * <p>Every case below perturbs exactly one thing and asserts the comparator <i>notices</i>. The
 * inverse — that it stays quiet on a legitimate difference — matters just as much, because a
 * comparator that fires on the reference's own float32 error would be "fixed" by loosening it
 * everywhere.
 */
class ResultComparatorTest {

    /** A complete, well-formed row; each test perturbs one column of a copy. */
    private static ScanInfoResult row() {
        return new ScanInfoResult(
                3,
                810.79,
                2,
                0.5,
                null,
                586278.8533592224,
                2,
                2126843.0,
                1031.6499,
                42.0,
                810.7,
                99.0);
    }

    private static List<String> compare(ScanInfoResult golden, ScanInfoResult actual) {
        return ResultComparator.compare("t", List.of(golden), List.of(actual), false);
    }

    private static List<String> compareFloat32(ScanInfoResult golden, ScanInfoResult actual) {
        return ResultComparator.compare("t", List.of(golden), List.of(actual), true);
    }

    @Test
    void identicalRowsProduceNoDifferences() {
        assertTrue(compare(row(), row()).isEmpty());
    }

    // ------------------------------------------------------------------ it catches real damage

    @Test
    void aSingleBitIntensityDifferenceFails() {
        // THE assertion this class exists for. base_peak_i is a max() -- a selected value with no
        // accumulation -- so the tic tolerance does not reach it and one ULP must fail.
        ScanInfoResult perturbed =
                new ScanInfoResult(
                        3,
                        810.79,
                        2,
                        0.5,
                        null,
                        586278.8533592224,
                        2,
                        Math.nextUp(2126843.0),
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        List<String> diffs = compare(row(), perturbed);
        assertEquals(1, diffs.size(), () -> "expected exactly one difference, got " + diffs);
        assertTrue(diffs.get(0).contains("base_peak_i"), diffs.get(0));
        assertTrue(diffs.get(0).contains("BIT-identical"), diffs.get(0));
    }

    @Test
    void aSingleBitRtDifferenceFails() {
        // rt is bit-identical precisely so a float-precision scanRt cannot slip through. A float
        // would pass Step 8's parity gate and fail here.
        ScanInfoResult perturbed =
                new ScanInfoResult(
                        3,
                        810.79,
                        2,
                        Math.nextUp(0.5),
                        null,
                        586278.8533592224,
                        2,
                        2126843.0,
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        assertTrue(compare(row(), perturbed).get(0).contains("rt"));
    }

    @Test
    void nullVersusZeroFails() {
        // Not the same answer. 0.0 is a measurement; null means "no precursor peak matched". A
        // comparator that treated them alike would hide the window bug entirely.
        ScanInfoResult goldenNull =
                new ScanInfoResult(3, 810.79, 2, 0.5, null, 1.0, 2, 1.0, 1.0, null, 810.7, 99.0);
        ScanInfoResult actualZero =
                new ScanInfoResult(3, 810.79, 2, 0.5, null, 1.0, 2, 1.0, 1.0, 0.0, 810.7, 99.0);

        List<String> diffs = compare(goldenNull, actualZero);
        assertEquals(1, diffs.size(), () -> diffs.toString());
        assertTrue(diffs.get(0).contains("ms1_i"), diffs.get(0));
        assertTrue(diffs.get(0).contains("null-vs-value"), diffs.get(0));

        // And symmetrically -- a null where the golden has a value is the symptom.
        assertTrue(compare(actualZero, goldenNull).get(0).contains("null-vs-value"));
    }

    @Test
    void anExactColumnRejectsAnyDifference() {
        for (ScanInfoResult perturbed :
                List.of(
                        new ScanInfoResult(
                                4,
                                810.79,
                                2,
                                0.5,
                                null,
                                586278.8533592224,
                                2,
                                2126843.0,
                                1031.6499,
                                42.0,
                                810.7,
                                99.0),
                        new ScanInfoResult(
                                3,
                                810.79,
                                3,
                                0.5,
                                null,
                                586278.8533592224,
                                2,
                                2126843.0,
                                1031.6499,
                                42.0,
                                810.7,
                                99.0),
                        new ScanInfoResult(
                                3,
                                810.79,
                                2,
                                0.5,
                                2,
                                586278.8533592224,
                                2,
                                2126843.0,
                                1031.6499,
                                42.0,
                                810.7,
                                99.0),
                        new ScanInfoResult(
                                3,
                                810.79,
                                2,
                                0.5,
                                null,
                                586278.8533592224,
                                1,
                                2126843.0,
                                1031.6499,
                                42.0,
                                810.7,
                                99.0))) {
            assertEquals(
                    1, compare(row(), perturbed).size(), "one column changed, one diff expected");
        }
    }

    @Test
    void aRowCountMismatchReportsCountsAndStopsThere() {
        // Field-level diffs on misaligned rows are noise. Report the shape problem and stop.
        List<String> diffs =
                ResultComparator.compare("t", List.of(row(), row()), List.of(row()), false);
        assertEquals(1, diffs.size());
        assertTrue(diffs.get(0).contains("row count"), diffs.get(0));
        assertTrue(diffs.get(0).contains("expected 2, got 1"), diffs.get(0));
    }

    @Test
    void rowsOutOfScanOrderFail() {
        ScanInfoResult later =
                new ScanInfoResult(10, 810.79, 2, 0.5, null, 1.0, 2, 1.0, 1.0, 42.0, 810.7, 99.0);
        List<String> diffs =
                ResultComparator.compare("t", List.of(later, row()), List.of(later, row()), false);
        assertTrue(
                diffs.stream().anyMatch(d -> d.contains("ascending by scan id")),
                () -> diffs.toString());
    }

    // ------------------------------------------------------------------ the tolerance boundaries

    @Test
    void precmzPassesAtOneETenAndFailsAtOneEEight() {
        // ⚠ Scoped to precmz deliberately. ms1_precmz permits 1e-7 on a 32-bit mzXML, so the same
        // comparator must ACCEPT 1e-8 in that mode -- see the test below.
        assertTrue(
                compare(row(), withPrecmz(810.79 * (1 + 1e-10))).isEmpty(), "1e-10 is within 1e-9");

        List<String> diffs = compare(row(), withPrecmz(810.79 * (1 + 1e-8)));
        assertEquals(1, diffs.size(), () -> diffs.toString());
        assertTrue(diffs.get(0).contains("precmz"), diffs.get(0));
    }

    @Test
    void ms1PrecmzTakesTheFloat32AllowanceOnlyInFloat32Mode() {
        // The real measured divergence between small.mzML and small.mzXML is 2.9e-8: the
        // mzXML
        // stores m/z at precision="32", so the same peak's MEASURED centroid differs. That must
        // pass
        // for an mzXML fixture and fail for an mzML one -- otherwise the allowance is either
        // useless
        // or a blanket loosening.
        ScanInfoResult drifted = withMs1Precmz(810.7 * (1 + 2.9e-8));

        assertTrue(
                compareFloat32(row(), drifted).isEmpty(),
                "2.9e-8 is within the 1e-7 mzXML allowance");

        List<String> strict = compare(row(), drifted);
        assertEquals(1, strict.size(), () -> strict.toString());
        assertTrue(strict.get(0).contains("ms1_precmz"), strict.get(0));
    }

    @Test
    void theFloat32AllowanceDoesNotLeakToOtherColumns() {
        // Guards against "relax it everywhere" -- the failure mode the spec's traps warn about.
        // Even
        // in float32 mode, precmz and the intensities stay strict.
        assertEquals(1, compareFloat32(row(), withPrecmz(810.79 * (1 + 1e-8))).size());

        ScanInfoResult bumped =
                new ScanInfoResult(
                        3,
                        810.79,
                        2,
                        0.5,
                        null,
                        586278.8533592224,
                        2,
                        Math.nextUp(2126843.0),
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        assertEquals(1, compareFloat32(row(), bumped).size(), "intensities stay bit-identical");
    }

    @Test
    void ticAbsorbsTheReferencesFloat32AccumulationButNotMore() {
        // The measured worst case is 3.691e-08 on small.mzML -- the error is in the
        // REFERENCE,
        // not in us. 1e-6 must absorb that and nothing like a real bug.
        ScanInfoResult goldenTic =
                new ScanInfoResult(
                        3,
                        810.79,
                        2,
                        0.5,
                        null,
                        586278.875,
                        2,
                        2126843.0,
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        assertTrue(
                compare(goldenTic, row()).isEmpty(), "3.69e-8 is the documented reference error");

        ScanInfoResult wayOff =
                new ScanInfoResult(
                        3,
                        810.79,
                        2,
                        0.5,
                        null,
                        586278.875 * 1.001,
                        2,
                        2126843.0,
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        assertTrue(compare(goldenTic, wayOff).get(0).contains("tic"), "0.1% is a bug, not dtype");
    }

    @Test
    void allDifferencesAreReportedNotJustTheFirst() {
        // One run should say everything that moved. Reporting only the first turns a systematic
        // divergence into a game of whack-a-mole.
        ScanInfoResult perturbed =
                new ScanInfoResult(
                        99,
                        810.79,
                        2,
                        Math.nextUp(0.5),
                        null,
                        586278.8533592224,
                        2,
                        Math.nextUp(2126843.0),
                        1031.6499,
                        42.0,
                        810.7,
                        99.0);
        List<String> diffs = compare(row(), perturbed);
        assertEquals(3, diffs.size(), () -> "expected scan, rt and base_peak_i: " + diffs);
    }

    private static ScanInfoResult withPrecmz(double v) {
        return new ScanInfoResult(
                3, v, 2, 0.5, null, 586278.8533592224, 2, 2126843.0, 1031.6499, 42.0, 810.7, 99.0);
    }

    private static ScanInfoResult withMs1Precmz(double v) {
        return new ScanInfoResult(
                3, 810.79, 2, 0.5, null, 586278.8533592224, 2, 2126843.0, 1031.6499, 42.0, v, 99.0);
    }
}
