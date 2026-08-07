package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The mzML retention-time conversion is <b>conditional on the declared unit</b>, and this is the test
 * that catches a silent 60× error.
 *
 * <p>{@code msql_fileloading.py:564-571} reads {@code scan start time} and divides by 60 <i>only</i>
 * when {@code unit_info == "second"}. {@code data/small.mzML} declares {@code unitName="minute"}, so a
 * blind ÷60 would still pass every MGF-only test and every mzXML-only test — mzXML converts
 * unconditionally — while quietly making every mzML retention time 60× too small.
 *
 * <p>Step 2 built two fixtures for exactly this: {@code micro.mzML} declares minutes,
 * {@code micro_rtseconds.mzML} declares seconds, and they carry the same underlying times.
 */
class MzmlRtUnitTest {

    private static double[] rtsOf(Path p) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) { ScanView v = s.next(); out.add(v.rt()); }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void unitMinutePassesThroughUnconverted() {
        // micro.mzML scan rt values are 0.0, 0.5, 1.0, 1.5, 2.0 minutes, declared as "minute".
        double[] rt = rtsOf(Fixtures.require("fixtures/micro/micro.mzML"));
        assertEquals(5, rt.length);
        assertArrayEquals(new double[]{0.0, 0.5, 1.0, 1.5, 2.0}, rt, 0.0,
                "unitName=\"minute\" must NOT be divided by 60");
    }

    @Test
    void unitSecondIsDividedBySixty() {
        // micro_rtseconds.mzML holds the same times expressed in seconds (0, 30, 60, 90, 120) and
        // declares unitName="second", so it must come back as the same minutes as above.
        double[] rt = rtsOf(Fixtures.require("fixtures/micro/micro_rtseconds.mzML"));
        assertEquals(5, rt.length);
        assertArrayEquals(new double[]{0.0, 0.5, 1.0, 1.5, 2.0}, rt, 1e-12,
                "unitName=\"second\" must be divided by 60");
    }

    @Test
    void theTwoFixturesAgreeAfterConversion() {
        // The direct statement of the rule: same times, different declared units, identical result.
        // If someone makes the conversion unconditional, this passes but the minute test fails; if
        // they remove it entirely, this passes but the second test fails. Both directions are needed.
        double[] minutes = rtsOf(Fixtures.require("fixtures/micro/micro.mzML"));
        double[] seconds = rtsOf(Fixtures.require("fixtures/micro/micro_rtseconds.mzML"));
        assertArrayEquals(minutes, seconds, 1e-12);
    }

    @Test
    void smallMzmlDeclaresMinutesSoItsGoldenRtIsUnconverted() {
        // The real fixture. Its golden rt for scan 3 is 0.011218333333333334; ÷60 would give
        // 0.000186972... and the Step 12 differential would fail on every mzML row.
        try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() == 3) {
                    assertEquals(Double.doubleToLongBits(0.011218333333333334),
                                 Double.doubleToLongBits(v.rt()));
                    return;
                }
            }
            fail("scan 3 not found");
        }
    }
}
