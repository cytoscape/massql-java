package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;

import org.cytoscape.massql.MassqlException;
import org.junit.jupiter.api.Test;

class RowMaskTest {

    @Test
    void allAndNoneAndCardinality() {
        assertEquals(10, RowMask.all(10).cardinality());
        assertEquals(0, RowMask.none(10).cardinality());
        assertTrue(RowMask.none(10).isEmpty());
        assertEquals(10, RowMask.none(10).length());
    }

    @Test
    void operationsReturnNewInstancesAndLeaveOperandsUntouched() {
        // Immutability is the point: the condition filters composes conditions, and a mask mutated
        // under
        // one condition while another holds a reference is a wrong-answer bug with no
        // exception to point at it.
        RowMask a = RowMask.all(4);
        RowMask b = RowMask.none(4);
        RowMask and = a.and(b);
        RowMask or = a.or(b);
        RowMask not = a.not();

        assertEquals(0, and.cardinality());
        assertEquals(4, or.cardinality());
        assertEquals(0, not.cardinality());
        assertEquals(4, a.cardinality(), "operand a must be unchanged");
        assertEquals(0, b.cardinality(), "operand b must be unchanged");
        assertNotSame(a, and);
        assertNotSame(a, not);
    }

    @Test
    void andOrNotSemantics() {
        RowMask x = RowMask.none(4).withRange(new IntRange(0, 2)); // rows 0,1
        RowMask y = RowMask.none(4).withRange(new IntRange(1, 3)); // rows 1,2

        RowMask and = x.and(y);
        assertFalse(and.get(0));
        assertTrue(and.get(1));
        assertFalse(and.get(2));

        RowMask or = x.or(y);
        assertTrue(or.get(0));
        assertTrue(or.get(1));
        assertTrue(or.get(2));
        assertFalse(or.get(3));

        RowMask nx = x.not();
        assertFalse(nx.get(0));
        assertFalse(nx.get(1));
        assertTrue(nx.get(2));
        assertTrue(nx.get(3));
        assertEquals(2, nx.cardinality(), "not must respect length, not flip an unbounded BitSet");
    }

    @Test
    void lengthMismatchThrows() {
        RowMask a = RowMask.all(4);
        RowMask b = RowMask.all(5);
        assertThrows(MassqlException.class, () -> a.and(b));
        assertThrows(MassqlException.class, () -> a.or(b));
    }

    @Test
    void scansWithAnyRowIsTheShapeConditionsActuallyNeed() {
        // Most MassQL conditions mean "this scan contains a peak matching X", not "this row
        // matches X" -- the condition filters intersects these scan sets.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.0, 1).addPeak(100, 1).addPeak(200, 2); // rows 0,1
        b.startScan(2, 0.1, 1).addPeak(300, 3); // row 2
        b.startScan(3, 0.2, 1).addPeak(400, 4).addPeak(500, 5); // rows 3,4
        SpectrumTable t = b.build();

        // Select row 1 (scan 1) and row 4 (scan 3); scan 2 has nothing.
        RowMask m =
                RowMask.none(t.rowCount())
                        .withRange(new IntRange(1, 2))
                        .withRange(new IntRange(4, 5));
        BitSet scans = m.scansWithAnyRow(t);
        assertTrue(scans.get(0));
        assertFalse(scans.get(1), "scan 2 retains no selected row");
        assertTrue(scans.get(2));
        assertEquals(2, scans.cardinality());
    }

    @Test
    void emptyScansAreNeverReportedAsMatching() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(1);
        b.startScan(1, 0.0, 1).addPeak(100, 1);
        b.startScan(2, 0.1, 1); // empty scan, rowStart == rowEnd
        b.startScan(3, 0.2, 1).addPeak(300, 3);
        SpectrumTable t = b.build();

        BitSet scans = RowMask.all(t.rowCount()).scansWithAnyRow(t);
        assertTrue(scans.get(0));
        assertFalse(scans.get(1), "an empty scan cannot retain a row even under an all-set mask");
        assertTrue(scans.get(2));
    }

    @Test
    void nextSetRowIteratesWithoutScanning() {
        RowMask m = RowMask.none(10).withRange(new IntRange(2, 4)).withRange(new IntRange(7, 8));
        assertEquals(2, m.nextSetRow(0));
        assertEquals(3, m.nextSetRow(3));
        assertEquals(7, m.nextSetRow(4));
        assertEquals(-1, m.nextSetRow(8));
    }
}
