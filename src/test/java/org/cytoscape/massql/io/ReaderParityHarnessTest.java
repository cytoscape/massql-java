package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;

import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ParityDump;
import org.cytoscape.massql.testsupport.ParityFixtures;
import org.junit.jupiter.api.Test;

class ReaderParityHarnessTest {
    @Test
    void hexParsingRoundTripsExactly() {
        double[] awkward = {
            0.0,
            -0.0,
            1.0,
            0.023,
            0.011218333333333334,
            123.456789012345,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            1.0 / 3.0,
        };
        for (double v : awkward) {
            String hex = Double.toHexString(v);
            assertEquals(
                    Double.doubleToLongBits(v),
                    Double.doubleToLongBits(ParityDump.parseHex(hex)),
                    "hex round-trip lost bits for " + v + " (" + hex + ")");
        }
    }

    @Test
    void hexParsingReadsThePythonEmittedForm() {
        assertEquals(
                Double.doubleToLongBits(0.004935),
                Double.doubleToLongBits(ParityDump.parseHex("0x1.436b8f9b13166p-8")),
                "the rt_hex form from small.mzML scan 1 must parse exactly");
        assertEquals(
                0.0, ParityDump.parseHex("0x0.0p+0"), "the all-zero form used by empty MGF sums");
    }

    @Test
    void aDecimalRoundTripWouldHaveLostBitsHere() {
        double v = 0.011218333333333334;
        double viaDecimal = Double.parseDouble(String.format("%.6f", v));
        assertNotEquals(
                Double.doubleToLongBits(v),
                Double.doubleToLongBits(viaDecimal),
                "if this ever passes, the chosen decimal precision is lossless and the point is moot");
        assertEquals(
                Double.doubleToLongBits(v),
                Double.doubleToLongBits(ParityDump.parseHex(Double.toHexString(v))),
                "the hex path must be the lossless one");
    }

    @Test
    void aSingleBitPerturbationChangesTheDigest() {
        double[] values = {100.0, 200.5, 300.25, 400.125};
        String clean = ParityDump.sha256Of(values);

        double[] perturbed = values.clone();
        perturbed[2] = Math.nextUp(perturbed[2]);
        assertNotEquals(
                clean,
                ParityDump.sha256Of(perturbed),
                "a one-ULP change did not alter the digest -- the harness is not bit-exact");

        assertEquals(
                1,
                Math.abs(
                        Double.doubleToLongBits(perturbed[2]) - Double.doubleToLongBits(values[2])),
                "the perturbation should differ in exactly the last bit");
    }

    @Test
    void reorderingChangesTheDigest() {
        double[] a = {100.0, 200.0, 300.0};
        double[] b = {100.0, 300.0, 200.0};
        assertNotEquals(
                ParityDump.sha256Of(a),
                ParityDump.sha256Of(b),
                "the digest must be order-sensitive; if it were not, a reordered decode would pass");
    }

    @Test
    void signedZeroAndNaNAreDistinguished() {
        assertNotEquals(
                ParityDump.sha256Of(new double[] {0.0}),
                ParityDump.sha256Of(new double[] {-0.0}),
                "0.0 and -0.0 are different bits and must digest differently");
        assertEquals(
                ParityDump.sha256Of(new double[] {Double.NaN}),
                ParityDump.sha256Of(new double[] {Double.NaN}),
                "the canonical NaN must be stable across calls");
    }

    @Test
    void theDigestMatchesTheDumpForARealScan() {
        ParityDump dump = ParityDump.of("micro.mzXML");
        ParityDump.Scan want = dump.scans().get(new ParityDump.Key(1, 2));
        assertNotNull(want, "micro.mzXML should have an MS1 scan 2 entry");

        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 2) continue;
                var t = v.peaks();
                double[] mz = new double[t.rowCount()];
                double[] in = new double[t.rowCount()];
                for (int i = 0; i < t.rowCount(); i++) {
                    mz[i] = t.mz(i);
                    in[i] = t.intensity(i);
                }
                assertEquals(want.mzSha256(), ParityDump.sha256Of(mz), "m/z digest");
                assertEquals(want.iSha256(), ParityDump.sha256Of(in), "intensity digest");
                return;
            }
            fail("scan 2 not found");
        }
    }

    @Test
    void everyExpectedDumpLoadsAndIsNonEmpty() {
        for (String f : ParityFixtures.FIXTURES_WITH_DUMPS.keySet()) {
            ParityDump d = ParityDump.of(f);
            assertFalse(d.scans().isEmpty(), f + " parsed to zero scans");
            assertTrue(d.peakRows() > 0, f + " reports no peak rows");
        }
        assertEquals(
                16, ParityFixtures.FIXTURES_WITH_DUMPS.size(), "expected 16 fixtures with dumps");
    }

    @Test
    void aMissingDumpFailsRatherThanSkipping() {
        assertThrows(AssertionError.class, () -> ParityDump.of("no_such_fixture.mzML"));
    }

    @Test
    void theDumpKeyIsMslevelPlusScanNotScanAlone() {
        ParityDump d = ParityDump.of("micro.mgf");
        Map<ParityDump.Key, ParityDump.Scan> scans = d.scans();

        ParityDump.Scan phantom = scans.get(new ParityDump.Key(1, 3));
        ParityDump.Scan real = scans.get(new ParityDump.Key(2, 3));
        assertNotNull(phantom, "micro.mgf's fake MS1 row (scan 3) should be present");
        assertNotNull(real, "micro.mgf's real MS2 scan 3 should be present");
        assertNotSame(phantom, real, "these must be two distinct entries sharing one scan id");

        assertEquals(1, phantom.peakCount(), "the fake MS1 row is always a single row");
        assertTrue(real.peakCount() >= 1, "the real MS2 scan has actual peaks");

        assertNotEquals(
                0.0,
                phantom.iSum(),
                "micro.mgf's fake MS1 duplicates a real peak, so its intensity is non-zero");

        ParityDump.Scan plusRiseFake =
                ParityDump.of("PlusRise.mgf").scans().get(new ParityDump.Key(1, 1));
        assertNotNull(plusRiseFake, "PlusRise's fake MS1 sits at scan 1");
        assertEquals(
                0.0,
                plusRiseFake.iSum(),
                "PlusRise takes the manual-loader path, whose fake MS1 IS all zeros");
    }

    @Test
    void firstEightValuesParseAndAreUsableForDiagnosis() {
        ParityDump d = ParityDump.of("small.mzML");
        ParityDump.Scan s = d.scans().get(new ParityDump.Key(2, 3));
        assertNotNull(s);
        List<String> mzHex = s.mzHexFirst8();
        assertEquals(8, mzHex.size(), "the dump should carry 8 leading m/z values");
        double first = ParityDump.parseHex(mzHex.get(0));
        assertTrue(
                first > 0.0 && first < 100_000.0,
                "leading m/z should be a plausible mass, got " + first);

        for (int i = 1; i < mzHex.size(); i++) {
            assertTrue(
                    ParityDump.parseHex(mzHex.get(i)) >= ParityDump.parseHex(mzHex.get(i - 1)),
                    "mz_hex_first8 should be ascending at index " + i);
        }
    }
}
