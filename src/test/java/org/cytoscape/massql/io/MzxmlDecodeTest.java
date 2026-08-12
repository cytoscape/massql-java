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
import org.junit.jupiter.api.Test;

/**
 * The mzXML binary decode path: big-endian, <b>interleaved pairs</b>, one {@code precision} attribute,
 * zlib or nothing — every one of them different from mzML.
 *
 * <p><b> exists partly because this test was unsatisfiable as specified.</b> the mzXML reader
 * required assertions on {@code precision="64"} and zlib, but {@code micro.mzXML}, {@code small.mzXML}
 * and the Ewing file are <i>all</i> {@code precision="32"} / uncompressed / {@code network} — the same
 * configuration — while the spec claimed the fixtures "cover every decode path". The variants used here
 * (`micro_p64`, `micro_zlib`, `micro_p64_zlib`) were generated to close that gap, one variable each.
 */
class MzxmlDecodeTest {

    /** Every (mz, intensity) pair in document order, so de-interleaving errors surface immediately. */
    private static List<double[]> peaksOf(String fixture) {
        List<double[]> out = new ArrayList<>();
        Path p = Fixtures.require("fixtures/micro/" + fixture);
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.materialize();
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
        // Same values, same precision, only compressionType differs. Verified against MassQL's own
        // loader before being asserted here: its mz and intensity arrays compare equal.
        assertSamePeaks(
                "micro.mzXML",
                "micro_zlib.mzXML",
                "zlib and uncompressed hold the same 32-bit values");
    }

    @Test
    void absentCompressionTypeMeansUncompressed() {
        // Upstream's check is `!= null && != "none"`, so an ABSENT attribute is uncompressed --
        // which
        // all three primary fixtures rely on. micro.mzXML omits it entirely and must still decode.
        assertFalse(
                peaksOf("micro.mzXML").isEmpty(),
                "micro.mzXML has no compressionType and must decode");
    }

    @Test
    void thirtyTwoBitIsWidenedNotReinterpreted() {
        // THE bit-identity trap. The reference decodes at 32-bit and widens to double, so
        // the
        // golden value is (double)(float)raw -- NOT the full-precision double. The micro table's
        // last
        // peak carries an m/z chosen to be inexact in float32 for exactly this assertion.
        //
        // Asserted on RAW BITS: a near-miss here is what makes the parity gate confusing.
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

        // The companion assertion, so neither of the above can pass vacuously: the two decodes must
        // genuinely differ. If a future change made both paths read 8 bytes, this is what catches
        // it.
        assertNotEquals(
                Double.doubleToLongBits(mz32),
                Double.doubleToLongBits(mz64),
                "32-bit and 64-bit decodes are identical -- one of the two paths is wrong");

        // And state the rule directly rather than only via the literal.
        assertEquals(
                Double.doubleToLongBits((double) (float) 123.456789012345d),
                Double.doubleToLongBits(mz32),
                "the 32-bit value must equal (double)(float)raw exactly");
    }

    @Test
    void sixtyFourBitWithZlibWorksToo() {
        // Both variables at once, so a bug in their interaction cannot hide behind either alone --
        // e.g. inflating correctly but then reading the inflated buffer at the wrong width.
        assertSamePeaks(
                "micro_p64.mzXML",
                "micro_p64_zlib.mzXML",
                "64-bit zlib and 64-bit uncompressed hold the same values");
    }

    @Test
    void pairsAreDeInterleavedNotSplitInHalf() {
        // mzXML stores ONE array of m/z,intensity,m/z,intensity... A reader that split the array
        // down
        // the middle (the mzML layout) would produce ascending-then-huge nonsense rather than
        // pairs.
        // Scan 2 of the micro table is the discriminating case: 3 peaks whose m/z are ~500-600 and
        // whose intensities are 1000/5000/9000, so a halved split is unmistakable.
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 2) continue;
                SpectrumTable t = v.materialize();
                assertEquals(3, t.rowCount());
                // m/z all in the 400-700 band, intensities all >= 1000: only correct
                // de-interleaving
                // gives that shape.
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
        // byteOrder="network" is big-endian. Rather than build a little-endian fixture nothing
        // produces, assert positively that the big-endian read gives sane values -- a byte-swapped
        // 32-bit float of 500.0 is ~1.1e-38, so "in a plausible m/z range" is a real discriminator.
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.materialize();
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
        // Guards the premise of the widening assertion above: if small.mzXML were ever regenerated
        // at
        // 64-bit, the parity comparison's 1e-7 mzXML tolerance (the differential) would stop being
        // justified.
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
