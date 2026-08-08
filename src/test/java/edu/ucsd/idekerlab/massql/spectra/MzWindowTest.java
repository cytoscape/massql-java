package edu.ucsd.idekerlab.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The m/z window is the performance-critical primitive and the one whose edge behaviour the
 * tolerance semantics rest on. No epsilon, ever.
 *
 * <p><b>There are TWO windows, deliberately</b> (Correction C37), because MassQL genuinely differs by caller
 * — both verified by execution, not inference:
 *
 * <ul>
 *   <li>{@code mzWindow} — <b>inclusive</b>. Tech_Step10's precursor lookup (`massql_query.py:101-103`,
 *       {@code >=}/{@code <=}): at {@code --precursor-tol-ppm 7.8125} a peak exactly on the bound
 *       <b>does</b> populate {@code ms1_i}.</li>
 *   <li>{@code mzWindowExclusive} — <b>strict</b>. Tech_Step9's condition windows
 *       (`msql_engine_filters.py:253` and three siblings, {@code >}/{@code <}): {@code micro.mzML} scan 3 has
 *       a peak at exactly {@code 201.0}, and {@code MS2PROD=201.5:TOLERANCEMZ=0.5} returns <b>0 rows</b>.</li>
 * </ul>
 *
 * <p>The spec originally assumed one rule served both. Collapsing them again would silently change
 * {@code ms1_i}/{@code ms1_precmz}, which Tech_Step12 compares at 1e-9.
 */
class MzWindowTest {

    /** Peaks at 100, 200, 300, 400 in one scan. */
    private static SpectrumTable simple() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 10).addPeak(200.0, 20).addPeak(300.0, 30).addPeak(400.0, 40);
        return b.build();
    }

    // ---------------------------------------------------------------- the EXCLUSIVE variant (C37)

    @Test
    void exclusiveWindowRejectsPeaksExactlyOnEitherBound() {
        SpectrumTable t = simple();
        // THE assertion that distinguishes the two methods. Rows are 100, 200, 300, 400.
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
        // Duplicate m/z occur in real centroided data. If lo equals a duplicated value, EVERY copy
        // must be
        // excluded -- which is why this uses upperBound/lowerBound rather than shifting an index by
        // one.
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
        // A peak exactly ON either bound is IN the window.
        //
        // ⚠ Correction C37: the justification here USED to read "Tech_Step9 computes the bounds
        // from a
        // tolerance, so an exclusive bound here would silently narrow every tolerance." That
        // reasoning is
        // backwards -- Tech_Step9's condition windows are STRICT in MassQL, verified by execution.
        // This
        // inclusive method exists for Tech_Step10's PRECURSOR LOOKUP, which really is inclusive
        // (massql_query.py:101-103 uses >=/<=, also verified). Step 9 uses mzWindowExclusive.
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
        b.startScan(1, 0.0, 1); // empty
        b.startScan(2, 0.1, 1).addPeak(150.0, 5); // single peak
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
