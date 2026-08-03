package edu.ucsd.idekerlab.massql.spectra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The m/z window is the performance-critical primitive and the one whose edge behaviour the
 * tolerance semantics rest on. Both bounds are inclusive, exactly, with no epsilon.
 */
class MzWindowTest {

    /** Peaks at 100, 200, 300, 400 in one scan. */
    private static SpectrumTable simple() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 10).addPeak(200.0, 20).addPeak(300.0, 30).addPeak(400.0, 40);
        return b.build();
    }

    @Test
    void bothBoundsAreInclusive() {
        SpectrumTable t = simple();
        // A peak exactly ON either bound is IN the window. Tech_Step9 computes the bounds from
        // a tolerance, so an exclusive bound here would silently narrow every tolerance.
        assertEquals(new IntRange(1, 3), t.mzWindow(0, 200.0, 300.0));
        assertEquals(new IntRange(0, 4), t.mzWindow(0, 100.0, 400.0));
        assertEquals(new IntRange(1, 2), t.mzWindow(0, 200.0, 200.0), "a zero-width window still matches");
    }

    @Test
    void oneUlpOutsideIsExcluded() {
        SpectrumTable t = simple();
        double justAbove200 = Math.nextUp(200.0);
        double justBelow300 = Math.nextDown(300.0);
        // This is the assertion that proves no epsilon is being applied.
        assertEquals(new IntRange(2, 3), t.mzWindow(0, justAbove200, 300.0));
        assertEquals(new IntRange(1, 2), t.mzWindow(0, 200.0, justBelow300));
    }

    @Test
    void duplicateMzValuesAtABoundaryAreAllIncluded() {
        // Arrays.binarySearch's choice among equal elements is UNSPECIFIED, so a window that
        // used its return value directly would include an arbitrary subset of a duplicate run.
        // Duplicate m/z does occur in real centroided data.
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
        // The same m/z in a neighbouring scan must not leak in.
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
        b.startScan(1, 0.0, 1);                       // empty
        b.startScan(2, 0.1, 1).addPeak(150.0, 5);     // single peak
        SpectrumTable t = b.build();

        assertTrue(t.mzWindow(0, 0.0, 1000.0).isEmpty());
        assertEquals(new IntRange(0, 1), t.mzWindow(1, 150.0, 150.0));
        assertTrue(t.mzWindow(1, 151.0, 200.0).isEmpty());
    }

    @Test
    void unsortedInputIsSortedAtFreezeSoWindowsStayCorrect() {
        // Do not assume sortedness -- verify it. A reader for a nonconforming file would
        // otherwise produce silently wrong window results.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1);
        b.addPeak(300.0, 30).addPeak(100.0, 10).addPeak(200.0, 20);
        SpectrumTable t = b.build();

        assertTrue(b.sortedAnyScan(), "builder should report that it had to sort");
        assertEquals(100.0, t.mz(0));
        assertEquals(200.0, t.mz(1));
        assertEquals(300.0, t.mz(2));
        // Intensities must travel with their m/z through the sort.
        assertEquals(10.0, t.intensity(0));
        assertEquals(20.0, t.intensity(1));
        assertEquals(30.0, t.intensity(2));
        assertEquals(new IntRange(1, 3), t.mzWindow(0, 200.0, 300.0));
    }
}
