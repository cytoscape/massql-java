package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MzWindowTest {
    private static SpectrumTable simple() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 10).addPeak(200.0, 20).addPeak(300.0, 30).addPeak(400.0, 40);
        return b.build();
    }

    @Test
    void exclusiveWindowRejectsPeaksExactlyOnEitherBound() {
        SpectrumTable t = simple();

        assertEquals(
                new IntRange(2, 3),
                t.mzWindowExclusive(0, 200.0, 400.0),
                "200.0 and 400.0 sit exactly on the bounds and must BOTH be excluded, leaving only 300.0");
        assertEquals(
                new IntRange(1, 4),
                t.mzWindow(0, 200.0, 400.0),
                "the inclusive method keeps them -- this pair is what makes the difference visible");
    }

    @Test
    void exclusiveWindowKeepsStrictlyInteriorPeaks() {
        SpectrumTable t = simple();
        assertEquals(
                new IntRange(1, 3),
                t.mzWindowExclusive(0, 150.0, 350.0),
                "200 and 300 are interior");
        assertEquals(
                new IntRange(0, 4), t.mzWindowExclusive(0, 99.0, 401.0), "all four are interior");
    }

    @Test
    void exclusiveWindowIsEmptyWhenOnlyTheBoundsWouldMatch() {
        SpectrumTable t = simple();
        assertEquals(
                IntRange.EMPTY,
                t.mzWindowExclusive(0, 200.0, 300.0),
                "nothing lies strictly between adjacent peaks 200 and 300");
        assertEquals(
                IntRange.EMPTY, t.mzWindowExclusive(0, 200.0, 200.0), "lo == hi contains nothing");
        assertEquals(IntRange.EMPTY, t.mzWindowExclusive(0, 300.0, 200.0), "hi < lo");
    }

    @Test
    void exclusiveWindowHandlesDuplicateMzOnABound() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 1).addPeak(200.0, 2).addPeak(200.0, 3).addPeak(200.0, 4).addPeak(300.0, 5);
        SpectrumTable t = b.build();

        assertEquals(
                IntRange.EMPTY,
                t.mzWindowExclusive(0, 200.0, 300.0),
                "all three copies of 200.0 sit on the lower bound and must be excluded");
        assertEquals(
                new IntRange(1, 4),
                t.mzWindow(0, 200.0, 200.0),
                "the inclusive method returns all three copies -- the contrast");
        assertEquals(
                new IntRange(1, 4),
                t.mzWindowExclusive(0, 150.0, 250.0),
                "when 200.0 is strictly interior, all three copies are kept");
    }

    @Test
    void bothBoundsAreInclusive() {
        SpectrumTable t = simple();

        assertEquals(new IntRange(1, 3), t.mzWindow(0, 200.0, 300.0));
        assertEquals(new IntRange(0, 4), t.mzWindow(0, 100.0, 400.0));
        assertEquals(
                new IntRange(1, 2),
                t.mzWindow(0, 200.0, 200.0),
                "a zero-width window still matches");
    }

    @Test
    void oneUlpOutsideIsExcluded() {
        SpectrumTable t = simple();
        double justAbove200 = Math.nextUp(200.0);
        double justBelow300 = Math.nextDown(300.0);

        assertEquals(new IntRange(2, 3), t.mzWindow(0, justAbove200, 300.0));
        assertEquals(new IntRange(1, 2), t.mzWindow(0, 200.0, justBelow300));
    }

    @Test
    void duplicateMzValuesAtABoundaryAreAllIncluded() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1);
        b.addPeak(100.0, 1).addPeak(200.0, 2).addPeak(200.0, 3).addPeak(200.0, 4).addPeak(300.0, 5);
        SpectrumTable t = b.build();

        assertEquals(new IntRange(1, 4), t.mzWindow(0, 200.0, 200.0), "all three duplicates");
        assertEquals(new IntRange(1, 4), t.mzWindow(0, 150.0, 250.0));
        assertEquals(new IntRange(0, 4), t.mzWindow(0, 100.0, 200.0), "run at the upper bound");
        assertEquals(new IntRange(1, 5), t.mzWindow(0, 200.0, 300.0), "run at the lower bound");
    }

    @Test
    void windowsBelowAboveAndOutsideTheScanRangeAreEmpty() {
        SpectrumTable t = simple();
        assertTrue(t.mzWindow(0, 0.0, 99.0).isEmpty());
        assertTrue(t.mzWindow(0, 401.0, 500.0).isEmpty());
        assertTrue(t.mzWindow(0, 201.0, 299.0).isEmpty(), "gap between peaks");
        assertTrue(t.mzWindow(0, 300.0, 200.0).isEmpty(), "inverted bounds");
    }

    @Test
    void windowIsScopedToItsOwnScan() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1).addPeak(100.0, 1).addPeak(200.0, 2);
        b.startScan(2, 0.1, 1).addPeak(100.0, 3).addPeak(200.0, 4);
        SpectrumTable t = b.build();

        assertEquals(new IntRange(0, 2), t.mzWindow(0, 50.0, 250.0));
        assertEquals(new IntRange(2, 4), t.mzWindow(1, 50.0, 250.0));
        assertEquals(new IntRange(0, 1), t.mzWindow(0, 100.0, 100.0));
        assertEquals(new IntRange(2, 3), t.mzWindow(1, 100.0, 100.0));
    }

    @Test
    void emptyAndSinglePeakScans() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(1, 0.0, 1);
        b.startScan(2, 0.1, 1).addPeak(150.0, 5);
        SpectrumTable t = b.build();

        assertTrue(t.mzWindow(0, 0.0, 1000.0).isEmpty());
        assertEquals(new IntRange(0, 1), t.mzWindow(1, 150.0, 150.0));
        assertTrue(t.mzWindow(1, 151.0, 200.0).isEmpty());
    }

    @Test
    void unsortedInputIsSortedAtFreezeSoWindowsStayCorrect() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1);
        b.addPeak(300.0, 30).addPeak(100.0, 10).addPeak(200.0, 20);
        SpectrumTable t = b.build();

        assertTrue(b.sortedAnyScan(), "builder should report that it had to sort");
        assertEquals(100.0, t.mz(0));
        assertEquals(200.0, t.mz(1));
        assertEquals(300.0, t.mz(2));

        assertEquals(10.0, t.intensity(0));
        assertEquals(20.0, t.intensity(1));
        assertEquals(30.0, t.intensity(2));
        assertEquals(new IntRange(1, 3), t.mzWindow(0, 200.0, 300.0));
    }
}
