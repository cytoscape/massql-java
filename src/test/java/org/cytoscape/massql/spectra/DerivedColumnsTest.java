package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@code i_norm = i / max(i in scan)} and {@code i_tic_norm = i / sum(i in scan)}. */
class DerivedColumnsTest {

    @Test
    void handComputedValues() {
        // Peaks 100/200/400 with intensities 250/1500/250. max = 1500, sum = 2000.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1);
        b.addPeak(100.0, 250.0).addPeak(200.0, 1500.0).addPeak(400.0, 250.0);
        SpectrumTable t = b.build();

        assertEquals(250.0 / 1500.0, t.iNorm(0));
        assertEquals(1.0, t.iNorm(1), "the base peak normalises to exactly 1.0");
        assertEquals(250.0 / 1500.0, t.iNorm(2));

        assertEquals(250.0 / 2000.0, t.iTicNorm(0));
        assertEquals(1500.0 / 2000.0, t.iTicNorm(1));
        assertEquals(0.125, t.iTicNorm(2));
    }

    @Test
    void singlePeakScanNormalisesToExactlyOne() {
        // Exact equality, not approximate: this is why i_norm is "structurally always 1.0"
        // for base-peak queries, and why massql_query.py drops that column entirely.
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(1, 0.0, 1).addPeak(123.456, 987.0);
        SpectrumTable t = b.build();

        assertEquals(1.0, t.iNorm(0));
        assertEquals(1.0, t.iTicNorm(0));
    }

    @Test
    void allZeroIntensityScanYieldsNaNRatherThanZero() {
        // Division by zero. NaN is the correct in-band "undefined" and the collation maps it to
        // JSON null; substituting 0 would report a real value where there is none, and
        // substituting 1 would claim every peak is the base peak.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1).addPeak(100.0, 0.0).addPeak(200.0, 0.0);
        SpectrumTable t = assertDoesNotThrow(b::build);

        assertTrue(Double.isNaN(t.iNorm(0)), "0/0 must be NaN, not 0 and not 1");
        assertTrue(Double.isNaN(t.iTicNorm(0)));
        assertEquals(
                0.0, Reductions.sum(t, 0, Column.I), "the TIC of an all-zero scan is genuinely 0");
    }

    @Test
    void derivedColumnsAreScopedPerScanNotGlobal() {
        // A loud peak in scan 2 must not change scan 1's normalisation.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1).addPeak(100.0, 10.0).addPeak(200.0, 20.0);
        b.startScan(2, 0.1, 1).addPeak(100.0, 1000.0);
        SpectrumTable t = b.build();

        assertEquals(0.5, t.iNorm(0), "10/20 within scan 1, not 10/1000 across the file");
        assertEquals(1.0, t.iNorm(1));
        assertEquals(1.0, t.iNorm(2));
    }

    @Test
    void anEmptyScanContributesNoRowsButStillExists() {
        // scaninfo(MS1DATA) can report a scan with no peaks, so it must keep its index entry
        // with rowStart == rowEnd -- the collation still needs its rt and tic.
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(7, 1.25, 2);
        SpectrumTable t = b.build();

        assertEquals(0, t.rowCount());
        assertEquals(1, t.index().scanCount());
        assertEquals(7, t.index().scanIdAt(0));
        assertEquals(0, t.index().peakCount(0));
        assertEquals(t.index().rowStart(0), t.index().rowEnd(0));
        assertEquals(1.25, t.index().rtOf(0), "rt survives on a peakless scan");
        assertEquals(2, t.index().polarityOf(0));
    }
}
