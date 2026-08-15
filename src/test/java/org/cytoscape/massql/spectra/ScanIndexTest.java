package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.cytoscape.massql.MassqlException;
import org.junit.jupiter.api.Test;

class ScanIndexTest {
    private static SpectrumTable sparse() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(3, 0.011218333333333334, 1, 810.79, 2, 0);
        b.addPeak(100.0, 1).addPeak(200.0, 2);
        b.startScan(10, 0.2, 1, 810.75, 9, 2).addPeak(300.0, 3);
        b.startScan(44, 0.3, 2, 0.0, 0, 0).addPeak(400.0, 4).addPeak(500.0, 5);
        return b.build();
    }

    @Test
    void rowRangesPartitionTheTableExactly() {
        SpectrumTable t = sparse();
        ScanIndex x = t.index();
        assertEquals(3, x.scanCount());
        assertEquals(0, x.rowStart(0));
        assertEquals(2, x.rowEnd(0));
        assertEquals(2, x.rowStart(1));
        assertEquals(3, x.rowEnd(1));
        assertEquals(3, x.rowStart(2));
        assertEquals(5, x.rowEnd(2));
        assertEquals(
                t.rowCount(), x.rowEnd(x.scanCount() - 1), "the last range must reach the end");
    }

    @Test
    void ordinalLookupHandlesSparseNon1BasedIds() {
        ScanIndex x = sparse().index();
        assertEquals(0, x.ordinalOf(3));
        assertEquals(1, x.ordinalOf(10));
        assertEquals(2, x.ordinalOf(44));
        assertEquals(-1, x.ordinalOf(1), "absent id returns -1, it does not throw");
        assertEquals(-1, x.ordinalOf(11));
        assertEquals(-1, x.ordinalOf(0));
        assertEquals(-1, x.ordinalOf(999));
    }

    @Test
    void retentionTimeIsExactAtDoublePrecision() {
        double golden = 0.011218333333333334;
        ScanIndex x = sparse().index();
        assertEquals(golden, x.rtOf(0), "must be bit-exact");
        assertEquals(Double.doubleToLongBits(golden), Double.doubleToLongBits(x.rtOf(0)));
        assertNotEquals(
                golden, (double) (float) golden, "the value genuinely does not survive float");
    }

    @Test
    void perScanMetadataIsCarriedIncludingTheZeroSentinels() {
        ScanIndex x = sparse().index();
        assertEquals(810.79, x.precmzOf(0));
        assertEquals(2, x.ms1scanOf(0));
        assertEquals(0, x.chargeOf(0), "0 = not recorded; NOT converted to null here");
        assertEquals(2, x.chargeOf(1));
        assertEquals(0.0, x.precmzOf(2), "0.0 = not recorded");
        assertEquals(0, x.ms1scanOf(2), "0 = no linked MS1 scan, the sentinel's origin");
        assertEquals(2, x.polarityOf(2), "2 = negative");
    }

    @Test
    void internalArraysDoNotEscape() {
        ScanIndex x = sparse().index();
        int[] ids = x.scanIds();
        ids[0] = 999;
        assertEquals(3, x.scanIdAt(0), "mutating the returned array must not affect the index");
    }

    @Test
    void outOfRangeOrdinalThrowsMassqlException() {
        ScanIndex x = sparse().index();
        assertThrows(MassqlException.class, () -> x.rowStart(-1));
        assertThrows(MassqlException.class, () -> x.rowStart(3));
        assertThrows(MassqlException.class, () -> x.rtOf(99));
    }
}
