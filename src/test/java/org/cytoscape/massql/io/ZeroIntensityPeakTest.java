package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ParityDump;
import org.junit.jupiter.api.Test;

class ZeroIntensityPeakTest {
    private static Map<Integer, double[][]> peaksByScan(String fixture) {
        Map<Integer, double[][]> out = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(Fixtures.require(fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                double[] mz = new double[t.rowCount()];
                double[] in = new double[t.rowCount()];
                for (int i = 0; i < t.rowCount(); i++) {
                    mz[i] = t.mz(i);
                    in[i] = t.intensity(i);
                }
                out.put(v.scanId(), new double[][] {mz, in});
            }
        }
        return out;
    }

    @Test
    void mgfDropsZeroIntensityPeaksKeepingTheRestIntact() {
        Map<Integer, double[][]> got = peaksByScan("fixtures/micro/micro_zeroint.mgf");

        double[][] scan1 = got.get(1);
        assertNotNull(scan1, "block 1 should be scan 1 (block index, no SCANS=)");
        assertArrayEquals(
                new double[] {100.0, 200.0, 300.0},
                scan1[0],
                0.0,
                "the two zero-intensity peaks (150.0 and 250.0) must be absent");
        assertArrayEquals(
                new double[] {250.0, 1500.0, 750.0},
                scan1[1],
                0.0,
                "the surviving peaks' intensities must be untouched");

        assertEquals(3, scan1[0].length, "3 of 5 peaks survive");
    }

    @Test
    void anAllZeroBlockBecomesAZeroPeakScanRatherThanVanishing() {
        Map<Integer, double[][]> got = peaksByScan("fixtures/micro/micro_zeroint.mgf");

        double[][] scan2 = got.get(2);
        assertNotNull(scan2, "the all-zero block must still be yielded, not dropped by the reader");
        assertEquals(0, scan2[0].length, "every peak was zero-intensity, so the scan has no peaks");

        assertEquals(3, got.size(), "all three blocks are yielded; only their PEAKS differ");
        assertEquals(1, got.get(3)[0].length, "the control block is untouched");
    }

    @Test
    void theSurvivingPeaksTotalFourNotSix() {
        long peaks = 0;
        try (SpectraStream s =
                SpectraFile.open(Fixtures.require("fixtures/micro/micro_zeroint.mgf"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                peaks += v.peaks().rowCount();
            }
        }
        assertEquals(
                4L,
                peaks,
                "6 peak lines minus 2 zero-intensity ones. Before the fix this was 6 and the parity gate "
                        + "could not tell, because no MGF fixture had a zero-intensity peak");
    }

    @Test
    void normalisedColumnsAreUnaffectedByTheDrop() {
        try (SpectraStream s =
                SpectraFile.open(Fixtures.require("fixtures/micro/micro_zeroint.mgf"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 1) continue;
                SpectrumTable t = v.peaks();
                assertEquals(
                        1500.0 / 1500.0, t.iNorm(1), 0.0, "the base peak's iNorm is exactly 1.0");
                assertEquals(
                        250.0 / 2500.0,
                        t.iTicNorm(0),
                        1e-15,
                        "iTicNorm uses the sum of NON-zero peaks");
                assertEquals(750.0 / 2500.0, t.iTicNorm(2), 1e-15);
                return;
            }
            fail("scan 1 not found");
        }
    }

    @Test
    void mzmlRetainsZeroIntensityPeaks() {
        ParityDump dump = ParityDump.of("small.mzML");
        ParityDump.Scan want = dump.scans().get(new ParityDump.Key(1, 1));
        assertNotNull(want, "small.mzML MS1 scan 1");

        List<Double> dumpFirst8 = new ArrayList<>();
        for (String hex : want.iHexFirst8()) dumpFirst8.add(ParityDump.parseHex(hex));
        assertTrue(
                dumpFirst8.stream().allMatch(v -> v == 0.0),
                "premise: MassQL itself retains those zeros. If this fails the fixture changed and the "
                        + "contrast below is no longer being tested. Got: "
                        + dumpFirst8);

        try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 1) continue;
                SpectrumTable t = v.peaks();
                for (int i = 0; i < 8; i++) {
                    assertEquals(
                            0.0,
                            t.intensity(i),
                            0.0,
                            "mzML must RETAIN zero-intensity peaks; row "
                                    + i
                                    + " should be 0.0. The MGF "
                                    + "skip is MGF-only -- generalising it breaks the parity gate");
                }
                assertEquals(want.peakCount(), t.rowCount(), "peak count includes the zeros");
                return;
            }
            fail("scan 1 not found");
        }
    }

    @Test
    void mzxmlRetainsZeroIntensityPeaksToo() {
        for (String f : new String[] {"micro.mzXML", "small.mzXML"}) {
            ParityDump dump = ParityDump.of(f);
            String path = f.startsWith("micro") ? "fixtures/micro/" + f : "data/" + f;
            try (SpectraStream s = SpectraFile.open(Fixtures.require(path))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    ParityDump.Scan want =
                            dump.scans().get(new ParityDump.Key(v.msLevel(), v.scanId()));
                    if (want == null) continue;
                    assertEquals(
                            want.peakCount(),
                            v.peaks().rowCount(),
                            f + " scan " + v.scanId() + ": mzXML applies no intensity filter");
                }
            }
        }
    }
}
