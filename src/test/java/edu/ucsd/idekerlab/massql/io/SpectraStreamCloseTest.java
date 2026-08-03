package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * {@code close()} is load-bearing, not ceremony: the cursor holds a memory mapping (mzML) or an open
 * reader (MGF) for its whole lifetime. Phase 2's {@code shutDown()} depends on release, and
 * Tech_Step12 tests this at the integration level.
 */
class SpectraStreamCloseTest {

    @Test
    void closeIsIdempotent() {
        for (String f : new String[]{"data/small.mzML", "data/PlusRise.mgf"}) {
            SpectraStream s = SpectraFile.open(Fixtures.require(f));
            s.close();
            assertDoesNotThrow(s::close, "second close() on " + f);
            assertDoesNotThrow(s::close, "third close() on " + f);
        }
    }

    @Test
    void twoHundredOpenCloseCyclesDoNotExhaustDescriptors() {
        // The leak this guards is real for mzML: each open maps the file. Without release, 200 cycles
        // exhaust descriptors or address space -- and inside Cytoscape that surfaces as an unrelated
        // failure much later.
        Path mzml = Fixtures.require("data/small.mzML");
        for (int i = 0; i < 200; i++) {
            try (SpectraStream s = SpectraFile.open(mzml)) {
                assertTrue(s.next(), "cycle " + i + " produced no scans");
                assertEquals(1, s.current().scanId());
            }
        }
    }

    @Test
    void twoHundredCyclesOnAnMgfToo() {
        Path mgf = Fixtures.require("fixtures/micro/micro.mgf");
        for (int i = 0; i < 200; i++) {
            try (SpectraStream s = SpectraFile.open(mgf)) {
                assertTrue(s.next());
            }
        }
    }

    @Test
    void closingMidStreamIsFineAndTheStreamIsThenUnusable() {
        SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"));
        assertTrue(s.next());
        assertTrue(s.next());
        s.close();
        assertThrows(RuntimeException.class, s::next, "a closed stream must refuse to advance");
    }
}
