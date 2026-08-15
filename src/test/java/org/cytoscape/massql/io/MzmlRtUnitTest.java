package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class MzmlRtUnitTest {
    private static double[] rtsOf(Path p) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                out.add(v.rt());
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void unitMinutePassesThroughUnconverted() {
        double[] rt = rtsOf(Fixtures.require("fixtures/micro/micro.mzML"));
        assertEquals(5, rt.length);
        assertArrayEquals(
                new double[] {0.0, 0.5, 1.0, 1.5, 2.0},
                rt,
                0.0,
                "unitName=\"minute\" must NOT be divided by 60");
    }

    @Test
    void unitSecondIsDividedBySixty() {
        double[] rt = rtsOf(Fixtures.require("fixtures/micro/micro_rtseconds.mzML"));
        assertEquals(5, rt.length);
        assertArrayEquals(
                new double[] {0.0, 0.5, 1.0, 1.5, 2.0},
                rt,
                1e-12,
                "unitName=\"second\" must be divided by 60");
    }

    @Test
    void theTwoFixturesAgreeAfterConversion() {
        double[] minutes = rtsOf(Fixtures.require("fixtures/micro/micro.mzML"));
        double[] seconds = rtsOf(Fixtures.require("fixtures/micro/micro_rtseconds.mzML"));
        assertArrayEquals(minutes, seconds, 1e-12);
    }

    @Test
    void smallMzmlDeclaresMinutesSoItsGoldenRtIsUnconverted() {
        try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() == 3) {
                    assertEquals(
                            Double.doubleToLongBits(0.011218333333333334),
                            Double.doubleToLongBits(v.rt()));
                    return;
                }
            }
            fail("scan 3 not found");
        }
    }
}
