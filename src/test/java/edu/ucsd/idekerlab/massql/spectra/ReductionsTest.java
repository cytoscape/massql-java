package edu.ucsd.idekerlab.massql.spectra;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReductionsTest {

    /** scan 1: four peaks; scan 2: empty; scan 3: one peak. */
    private static SpectrumTable table() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 250.0).addPeak(200.0, 1500.0).addPeak(300.0, 750.0).addPeak(400.0, 100.0);
        b.startScan(2, 0.6, 1);
        b.startScan(3, 0.7, 1).addPeak(150.0, 42.0);
        return b.build();
    }

    @Test
    void basicReductions() {
        SpectrumTable t = table();
        assertEquals(2600.0, Reductions.sum(t, 0, Column.I));
        assertEquals(1500.0, Reductions.max(t, 0, Column.I));
        assertEquals(100.0, Reductions.min(t, 0, Column.I));
        assertEquals(4, Reductions.count(t, 0));
        assertEquals(250.0, Reductions.first(t, 0, Column.I));
        assertEquals(100.0, Reductions.first(t, 0, Column.MZ), "first is lowest m/z, per the sort invariant");
    }

    @Test
    void argmaxReturnsARowIndexSoTheCallerCanReadAnotherColumn() {
        // This is exactly what base_peak_mz needs (Tech_Step10 §3): argmax over intensity,
        // then read m/z at that row. A value-returning max() could not express it.
        SpectrumTable t = table();
        int row = Reductions.argmax(t, 0, Column.I);
        assertEquals(1, row);
        assertEquals(1500.0, t.intensity(row));
        assertEquals(200.0, t.mz(row), "the m/z of the base peak, read at the argmax row");
    }

    @Test
    void argmaxTiesResolveToTheLOWESTRowIndex() {
        // Matches pandas idxmax, which returns the FIRST occurrence, and
        // massql_query.py:163 uses idxmax. A last-wins implementation would disagree with the
        // goldens on any spectrum with two equal-intensity peaks.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1);
        b.addPeak(100.0, 500.0).addPeak(200.0, 999.0).addPeak(300.0, 999.0).addPeak(400.0, 100.0);
        SpectrumTable t = b.build();

        int row = Reductions.argmax(t, 0, Column.I);
        assertEquals(1, row, "tie must pick the first (lowest m/z) occurrence, not the last");
        assertEquals(200.0, t.mz(row));
    }

    @Test
    void emptyScanBehaviourIsDeliberate() {
        SpectrumTable t = table();
        // sum is 0.0 because the TIC of an empty spectrum really is zero; the others have no
        // value to report. Nothing throws: scaninfo(MS1DATA) can report a peakless scan.
        assertEquals(0.0, Reductions.sum(t, 1, Column.I));
        assertTrue(Double.isNaN(Reductions.max(t, 1, Column.I)));
        assertTrue(Double.isNaN(Reductions.min(t, 1, Column.I)));
        assertTrue(Double.isNaN(Reductions.first(t, 1, Column.I)));
        assertEquals(-1, Reductions.argmax(t, 1, Column.I));
        assertEquals(0, Reductions.count(t, 1));
    }

    @Test
    void singlePeakScan() {
        SpectrumTable t = table();
        assertEquals(42.0, Reductions.sum(t, 2, Column.I));
        assertEquals(42.0, Reductions.max(t, 2, Column.I));
        assertEquals(42.0, Reductions.min(t, 2, Column.I));
        assertEquals(1, Reductions.count(t, 2));

        int row = Reductions.argmax(t, 2, Column.I);
        assertEquals(t.index().rowStart(2), row, "the only row in scan 3");
        assertEquals(150.0, t.mz(row));
    }

    @Test
    void maskedReductionsOnlySeeSelectedRows() {
        SpectrumTable t = table();
        // Select only the 200.0 and 300.0 peaks of scan 1.
        RowMask m = RowMask.none(t.rowCount()).withRange(new IntRange(1, 3));
        assertEquals(2250.0, Reductions.sum(t, 0, Column.I, m));
        assertEquals(1500.0, Reductions.max(t, 0, Column.I, m));
        assertEquals(750.0, Reductions.min(t, 0, Column.I, m));
        assertEquals(2, Reductions.count(t, 0, m));
        assertEquals(1, Reductions.argmax(t, 0, Column.I, m));
        assertEquals(1500.0, Reductions.first(t, 0, Column.I, m), "first SELECTED row");
    }

    @Test
    void aFullyMaskedOutScanBehavesLikeAnEmptyOne() {
        SpectrumTable t = table();
        RowMask none = RowMask.none(t.rowCount());
        assertEquals(0.0, Reductions.sum(t, 0, Column.I, none));
        assertTrue(Double.isNaN(Reductions.max(t, 0, Column.I, none)));
        assertEquals(-1, Reductions.argmax(t, 0, Column.I, none));
        assertEquals(0, Reductions.count(t, 0, none));
    }

    @Test
    void reductionsWorkOnEveryColumn() {
        SpectrumTable t = table();
        assertEquals(1000.0, Reductions.sum(t, 0, Column.MZ));
        assertEquals(400.0, Reductions.max(t, 0, Column.MZ));
        // iNorm/iTicNorm are pre-computed columns, so reductions apply to them too.
        assertEquals(1.0, Reductions.max(t, 0, Column.I_NORM));
        assertEquals(1.0, Reductions.sum(t, 0, Column.I_TIC_NORM), 1e-12);
    }
}
