package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class MzxmlDecodeTest {
    private static List<double[]> peaksOf(String fixture) {
        List<double[]> out = new ArrayList<>();
        Path p = Fixtures.require("fixtures/micro/" + fixture);
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                for (int i = 0; i < t.rowCount(); i++)
                    out.add(new double[] {t.mz(i), t.intensity(i)});
            }
        }
        return out;
    }

    private static void assertSamePeaks(String a, String b, String why) {
        List<double[]> x = peaksOf(a), y = peaksOf(b);
        assertEquals(x.size(), y.size(), why + " (peak count)");
        for (int i = 0; i < x.size(); i++) {
            assertEquals(
                    Double.doubleToLongBits(x.get(i)[0]),
                    Double.doubleToLongBits(y.get(i)[0]),
                    why + " -- m/z differs at pair " + i);
            assertEquals(
                    Double.doubleToLongBits(x.get(i)[1]),
                    Double.doubleToLongBits(y.get(i)[1]),
                    why + " -- intensity differs at pair " + i);
        }
    }

    @Test
    void zlibDecodesBitIdenticallyToUncompressed() {
        assertSamePeaks(
                "micro.mzXML",
                "micro_zlib.mzXML",
                "zlib and uncompressed hold the same 32-bit values");
    }

    @Test
    void absentCompressionTypeMeansUncompressed() {
        assertFalse(
                peaksOf("micro.mzXML").isEmpty(),
                "micro.mzXML has no compressionType and must decode");
    }

    @Test
    void thirtyTwoBitIsWidenedNotReinterpreted() {
        double mz32 = lastMz("micro.mzXML");
        double mz64 = lastMz("micro_p64.mzXML");

        assertEquals(
                Double.doubleToLongBits(123.456787109375d),
                Double.doubleToLongBits(mz32),
                "precision=\"32\" must give (double)(float)123.456789012345");
        assertEquals(
                Double.doubleToLongBits(123.456789012345d),
                Double.doubleToLongBits(mz64),
                "precision=\"64\" must give the full-precision double");

        assertNotEquals(
                Double.doubleToLongBits(mz32),
                Double.doubleToLongBits(mz64),
                "32-bit and 64-bit decodes are identical -- one of the two paths is wrong");

        assertEquals(
                Double.doubleToLongBits((double) (float) 123.456789012345d),
                Double.doubleToLongBits(mz32),
                "the 32-bit value must equal (double)(float)raw exactly");
    }

    @Test
    void sixtyFourBitWithZlibWorksToo() {
        assertSamePeaks(
                "micro_p64.mzXML",
                "micro_p64_zlib.mzXML",
                "64-bit zlib and 64-bit uncompressed hold the same values");
    }

    @Test
    void pairsAreDeInterleavedNotSplitInHalf() {
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 2) continue;
                SpectrumTable t = v.peaks();
                assertEquals(3, t.rowCount());

                for (int i = 0; i < 3; i++) {
                    assertTrue(
                            t.mz(i) > 400 && t.mz(i) < 700,
                            "m/z "
                                    + t.mz(i)
                                    + " at row "
                                    + i
                                    + " looks like an intensity -- the "
                                    + "interleaved array was split rather than de-interleaved");
                    assertTrue(
                            t.intensity(i) >= 1000,
                            "intensity " + t.intensity(i) + " at row " + i + " looks like an m/z");
                }
                assertEquals(9000.0, t.intensity(2), "the third pair's intensity");
                return;
            }
            fail("scan 2 not found");
        }
    }

    @Test
    void bigEndianIsTheDefaultAndLittleEndianWouldBeObvious() {
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                for (int i = 0; i < t.rowCount(); i++) {
                    assertTrue(
                            t.mz(i) > 1.0 && t.mz(i) < 100_000.0,
                            "m/z " + t.mz(i) + " is not a plausible mass -- byte order is wrong");
                }
            }
        }
    }

    @Test
    void theRealFixtureIsThirtyTwoBit() {
        String head = readHead(Fixtures.require("data/small.mzXML"));
        assertTrue(
                head.contains("precision=\"32\""),
                "small.mzXML is expected to be precision=32; the differential ms1_precmz tolerance depends on it");
        assertTrue(head.contains("byteOrder=\"network\""), "small.mzXML should be big-endian");
    }

    private static double lastMz(String fixture) {
        List<double[]> peaks = peaksOf(fixture);
        return peaks.get(peaks.size() - 1)[0];
    }

    private static String readHead(Path p) {
        try {
            byte[] buf = new byte[16384];
            try (var in = java.nio.file.Files.newInputStream(p)) {
                int n = in.readNBytes(buf, 0, buf.length);
                return new String(
                        buf, 0, Math.max(n, 0), java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
