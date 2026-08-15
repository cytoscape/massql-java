package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;

class ZeroPeakMs1ChainTest {
    private static Map<Integer, Integer> ms2Links(Path p) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) out.put(v.scanId(), Raw.orZero(v.ms1scan()));
            }
        }
        return out;
    }

    @Test
    void anEmptyMs1DoesNotBecomeTheLinkForTheNextMs2() {
        Path mzml = Fixtures.require("fixtures/micro/micro.mzML");
        Map<Integer, Integer> links = ms2Links(mzml);

        assertEquals(
                2,
                links.get(5),
                "scan 4 is an MS1 with zero peaks, so MassQL never assigns it to previous_ms1_scan; "
                        + "scan 5 must link back to scan 2. Got "
                        + links.get(5)
                        + " -- the reader is updating the chain on empty scans.");

        assertEquals(0, links.get(1), "scan 1 precedes any MS1 -- the raw 0 sentinel");
        assertEquals(2, links.get(3), "scan 3 follows the non-empty MS1 at scan 2");
    }

    @Test
    void theEmptyMs1IsStillYielded() {
        Path mzml = Fixtures.require("fixtures/micro/micro.mzML");
        int scans = 0;
        boolean sawEmptyMs1 = false;
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                scans++;
                if (v.scanId() == 4) {
                    sawEmptyMs1 = true;
                    assertEquals(1, v.msLevel());
                    assertEquals(0, v.peaks().rowCount(), "scan 4 is the zero-peak MS1");
                    assertEquals(
                            0,
                            v.peaks().rowCount(),
                            "materialising an empty scan yields an empty table, not an error");
                }
            }
        }
        assertEquals(5, scans, "all five micro scans must be yielded, including the empty one");
        assertTrue(sawEmptyMs1, "scan 4 was never yielded -- empty scans must not be dropped");
    }
}
