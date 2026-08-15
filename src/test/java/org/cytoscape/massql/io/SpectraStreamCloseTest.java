package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class SpectraStreamCloseTest {
    @Test
    void closeIsIdempotent() {
        for (String f : new String[] {"data/small.mzML", "data/PlusRise.mgf", "data/small.mzXML"}) {
            SpectraStream s = SpectraFile.open(Fixtures.require(f));
            s.close();
            assertDoesNotThrow(s::close, "second close() on " + f);
            assertDoesNotThrow(s::close, "third close() on " + f);
        }
    }

    @Test
    void twoHundredOpenCloseCyclesDoNotExhaustDescriptors() {
        Path mzml = Fixtures.require("data/small.mzML");
        for (int i = 0; i < 200; i++) {
            try (SpectraStream s = SpectraFile.open(mzml)) {
                assertTrue(s.hasNext(), "cycle " + i + " produced no scans");
                ScanView v = s.next();
                assertEquals(1, v.scanId());
            }
        }
    }

    @Test
    void twoHundredOpenCloseCyclesOnAnMzxml() {
        Path mzxml = Fixtures.require("data/small.mzXML");
        for (int i = 0; i < 200; i++) {
            try (SpectraStream s = SpectraFile.open(mzxml)) {
                assertTrue(s.hasNext(), "cycle " + i + " produced no scans");
                ScanView v = s.next();
                assertEquals(1, v.scanId());
            }
        }
    }

    @Test
    void twoHundredCyclesOnAnMgfToo() {
        Path mgf = Fixtures.require("fixtures/micro/micro.mgf");
        for (int i = 0; i < 200; i++) {
            try (SpectraStream s = SpectraFile.open(mgf)) {
                assertTrue(s.hasNext());
            }
        }
    }

    @Test
    void closingMidStreamIsFineAndTheStreamIsThenUnusable() {
        SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"));
        assertTrue(s.hasNext());
        assertTrue(s.hasNext());
        s.close();
        assertThrows(RuntimeException.class, s::next, "a closed stream must refuse to advance");
    }
}
