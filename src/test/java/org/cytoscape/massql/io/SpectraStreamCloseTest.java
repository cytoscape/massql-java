package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@code close()} is load-bearing, not ceremony: the cursor holds a memory mapping (mzML) or an open
 * reader (MGF) for its whole lifetime. A host's own shutdown path depends on release, and
 * the differential tests this at the integration level.
 */
class SpectraStreamCloseTest {

    @Test
    void closeIsIdempotent() {
        // mzXML added by Step 7: it memory-maps like mzML, so it carries the same release
        // obligation.
        for (String f : new String[] {"data/small.mzML", "data/PlusRise.mgf", "data/small.mzXML"}) {
            SpectraStream s = SpectraFile.open(Fixtures.require(f));
            s.close();
            assertDoesNotThrow(s::close, "second close() on " + f);
            assertDoesNotThrow(s::close, "third close() on " + f);
        }
    }

    @Test
    void twoHundredOpenCloseCyclesDoNotExhaustDescriptors() {
        // The leak this guards is real for mzML: each open maps the file. Without release, 200
        // cycles
        // exhaust descriptors or address space -- and in a long-lived host that surfaces as an
        // unrelated
        // failure much later.
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
        // Step 7 §5: MzxmlReader memory-maps via the same vendored FileMemoryMapper, so it can leak
        // the
        // mapped region exactly as mzML can. 200 cycles over a 3 MB file is ~600 MB of address
        // space if
        // nothing is released.
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
