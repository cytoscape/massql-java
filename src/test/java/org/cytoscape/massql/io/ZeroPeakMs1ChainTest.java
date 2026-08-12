package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A zero-peak MS1 scan must <b>not</b> become an {@code ms1scan} link.
 *
 * <p><b>The rule, and where it comes from.</b> Both MassQL loaders open with
 * skipping any spectrum with an empty intensity array — for mzML
 * {@code :421} for mzXML — and that {@code continue} happens <b>before</b> {@code previous_ms1_scan} is
 * assigned. An empty MS1 is therefore invisible to the chain, and the next MS2 links to the MS1
 * <i>before</i> it. Stating the document-order rule unconditionally, and implementing it that way, is
 * the easy mistake here.
 *
 * <p><b>Verified against the oracle, not assumed.</b> Running MassQL's own loader over
 * {@code micro.mzML} gives {@code scan 5 -> ms1scan 2}, skipping the empty MS1 at scan 4:
 *
 * <pre>
 *   scan 1  -> ms1scan 0     (precedes any MS1 -- the sentinel)
 *   scan 3  -> ms1scan 2
 *   scan 5  -> ms1scan 2     &lt;-- NOT 4, even though scan 4 is an MS1 sitting between them
 * </pre>
 *
 * <p><b>Why this was invisible.</b> Neither real mzXML fixture contains a single zero-peak scan, so
 * nothing in the suite could catch the unconditional version. {@code micro.mzML} does contain the case
 * ({@code SCANS[3]} is an MS1 with {@code peaks=[]}) but the micro golden only covers scans 1 and 3 —
 * scan 5 does not match {@code test_micro.massql} — so the golden could not catch it either. This test
 * exists because the fixture already had the case and nothing was looking at it.
 */
class ZeroPeakMs1ChainTest {

    /** scan id -> ms1scan, for every MS2 scan the reader yields. */
    private static Map<Integer, Integer> ms2Links(Path p) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) out.put(v.scanId(), v.ms1scan());
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

        // The other two links, so a reader that returned a constant 2 cannot pass vacuously.
        assertEquals(0, links.get(1), "scan 1 precedes any MS1 -- the raw 0 sentinel");
        assertEquals(2, links.get(3), "scan 3 follows the non-empty MS1 at scan 2");
    }

    @Test
    void theEmptyMs1IsStillYielded() {
        // The scan is not dropped, only excluded from the linkage. This mirrors MGF, where all
        // 34,513 blocks including the 12,571 empty ones are yielded and the engine filters.
        // A reader that skipped empty scans outright would also make the test above pass, so assert
        // the distinction explicitly.
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
                    assertEquals(0, v.peakCount(), "scan 4 is the zero-peak MS1");
                    assertEquals(
                            0,
                            v.materialize().rowCount(),
                            "materialising an empty scan yields an empty table, not an error");
                }
            }
        }
        assertEquals(5, scans, "all five micro scans must be yielded, including the empty one");
        assertTrue(sawEmptyMs1, "scan 4 was never yielded -- empty scans must not be dropped");
    }
}
