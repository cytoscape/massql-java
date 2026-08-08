package edu.ucsd.idekerlab.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.MassqlException;

/** Invariants are enforced with MassqlException, not assert, so they fail in release builds too. */
class SpectrumTableBuilderTest {

    @Test
    void scanIdsMustBeNonDecreasing() {
        // Non-decreasing ids are what let the index be a range lookup rather than a hash of
        // lists. Readers stream in document order, so this costs nothing to require.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(10, 0.0, 1).addPeak(100, 1);
        MassqlException e = assertThrows(MassqlException.class, () -> b.startScan(5, 0.1, 1));
        assertTrue(e.getMessage().contains("non-decreasing"), e.getMessage());
    }

    @Test
    void duplicateScanIdsAreRejected() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1).addPeak(100, 1);
        assertThrows(MassqlException.class, () -> b.startScan(1, 0.1, 1));
    }

    @Test
    void addPeakBeforeStartScanIsRejected() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        MassqlException e = assertThrows(MassqlException.class, () -> b.addPeak(100, 1));
        assertTrue(e.getMessage().contains("startScan"), e.getMessage());
    }

    @Test
    void builderIsSingleUse() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(1, 0.0, 1).addPeak(100, 1);
        b.build();
        assertThrows(MassqlException.class, b::build);
        assertThrows(MassqlException.class, () -> b.addPeak(200, 2));
        assertThrows(MassqlException.class, () -> b.startScan(2, 0.1, 1));
    }

    @Test
    void msLevelMustBe1Or2() {
        assertThrows(MassqlException.class, () -> new SpectrumTableBuilder(0));
        assertThrows(MassqlException.class, () -> new SpectrumTableBuilder(3));
        assertDoesNotThrow(() -> new SpectrumTableBuilder(1));
        assertDoesNotThrow(() -> new SpectrumTableBuilder(2));
    }

    @Test
    void anEmptyTableIsValidAndUsable() {
        // MGF's MS1 side is an empty table rather than null, which keeps Tech_Step10 free of
        // null checks.
        SpectrumTable t = SpectrumTable.empty(1);
        assertTrue(t.isEmpty());
        assertEquals(0, t.rowCount());
        assertEquals(0, t.index().scanCount());
        assertEquals(1, t.msLevel());
        assertEquals(-1, t.index().ordinalOf(1));
        assertEquals(0, t.allRows().cardinality());
    }

    @Test
    void growthBeyondTheInitialCapacityPreservesEverything() {
        // The builder starts at 1024 peaks / 64 scans; exercise both resize paths.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        for (int s = 1; s <= 200; s++) {
            b.startScan(s, s * 0.01, 1);
            for (int p = 0; p < 30; p++) b.addPeak(100.0 + p, s * 1000.0 + p);
        }
        SpectrumTable t = b.build();
        assertEquals(200, t.index().scanCount());
        assertEquals(6000, t.rowCount());
        assertEquals(199 * 0.01, t.index().rtOf(198), 1e-12);
        assertEquals(200 * 1000.0 + 29, t.intensity(t.rowCount() - 1));
    }
}
