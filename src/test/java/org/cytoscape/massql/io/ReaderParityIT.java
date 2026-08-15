package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.ParityDump;
import org.cytoscape.massql.testsupport.ParityFixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ReaderParityIT {
    static List<String> fixtures() {
        return ParityFixtures.fixtures();
    }

    private static final double SUM_TOL = 1e-6;

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void decodesBitIdenticallyToThePythonLoader(String fixture) {
        ParityDump dump = ParityDump.of(fixture);
        boolean isMgf = fixture.toLowerCase().endsWith(".mgf");

        Set<ParityDump.Key> expected = new LinkedHashSet<>();
        for (ParityDump.Key k : dump.scans().keySet()) {
            if (isMgf && k.mslevel() == 1) continue;
            expected.add(k);
        }
        if (isMgf) {
            assertEquals(
                    1,
                    dump.ms1ScanCount(),
                    fixture + ": the dump should report exactly one (synthetic) MS1 scan");
        }

        Set<ParityDump.Key> seen = new LinkedHashSet<>();
        int readerOnly = 0, ms1 = 0, ms2 = 0;

        int matchedMs1 = 0, matchedMs2 = 0;
        long peaks = 0;

        try (SpectraStream s = SpectraFile.open(ParityFixtures.fixturePath(fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) ms1++;
                else ms2++;

                SpectrumTable t = v.peaks();
                peaks += t.rowCount();

                ParityDump.Key key = new ParityDump.Key(v.msLevel(), v.scanId());
                ParityDump.Scan want = dump.scans().get(key);

                if (want == null || (isMgf && key.mslevel() == 1)) {
                    assertEquals(
                            0,
                            t.rowCount(),
                            fixture
                                    + ": "
                                    + key
                                    + " is absent from the dump but decoded "
                                    + t.rowCount()
                                    + " peaks. MassQL only drops EMPTY scans, so this is "
                                    + "either a phantom scan or a scan-id derivation error");
                    readerOnly++;
                    continue;
                }

                assertTrue(seen.add(key), fixture + ": " + key + " was yielded twice");
                if (v.msLevel() == 1) matchedMs1++;
                else matchedMs2++;
                assertScanMatches(fixture, key, want, v, t);
            }
        }

        Set<ParityDump.Key> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seen);
        assertTrue(
                missing.isEmpty(),
                fixture
                        + ": the reader never produced "
                        + missing.size()
                        + " scan(s) the dump contains: "
                        + (missing.size() > 12
                                ? missing.stream().limit(12).toList() + " …"
                                : missing));

        assertEquals(expected.size(), seen.size(), fixture + ": scan-set size");

        assertEquals(dump.ms2ScanCount(), matchedMs2, fixture + ": MS2 scan count (dump-matched)");
        if (isMgf) {
            assertEquals(
                    0,
                    ms1,
                    fixture
                            + ": MGF has no survey scans, so our reader must yield ZERO MS1 scans -- the "
                            + "dump's single MS1 entry is MassQL's fake row");
            assertEquals(0, matchedMs1, fixture + ": no MGF MS1 entry should ever be matched");
        } else {
            assertEquals(
                    dump.ms1ScanCount(), matchedMs1, fixture + ": MS1 scan count (dump-matched)");

            assertEquals(
                    ms1 + ms2,
                    matchedMs1 + matchedMs2 + readerOnly,
                    fixture
                            + ": every yielded scan must be either dump-matched or a counted zero-peak extra");
        }

        assertEquals(
                ParityFixtures.FIXTURES_WITH_DUMPS.get(fixture).intValue(),
                readerOnly,
                fixture
                        + ": expected exactly "
                        + ParityFixtures.FIXTURES_WITH_DUMPS.get(fixture)
                        + " reader-only (zero-peak) scan(s), saw "
                        + readerOnly
                        + ". This count is ASSERTED, not tolerated: a reader that dropped real spectra "
                        + "would otherwise pass this gate silently");

        System.out.printf(
                "  %-24s %4d scans (%3d MS1 / %5d MS2) | %,10d peaks | %,6d reader-only%n",
                fixture, ms1 + ms2, ms1, ms2, peaks, readerOnly);
    }

    private static void assertScanMatches(
            String fixture, ParityDump.Key key, ParityDump.Scan want, ScanView v, SpectrumTable t) {
        String at = fixture + " " + key;

        assertEquals(want.peakCount(), t.rowCount(), at + ": peak count");
        assertEquals(want.polarity(), Raw.polarity(v.polarity()), at + ": polarity");

        if (key.mslevel() == 2) {
            assertEquals(want.charge().intValue(), Raw.orZero(v.charge()), at + ": charge");
            assertEquals(want.ms1scan().intValue(), Raw.orZero(v.ms1scan()), at + ": ms1scan");
            assertEquals(
                    Double.doubleToLongBits(want.precmz()),
                    Double.doubleToLongBits(Raw.orZero(v.precmz())),
                    at
                            + ": precmz must be bit-identical (want "
                            + want.precmz()
                            + ", got "
                            + v.precmz()
                            + ")");
        }

        assertEquals(
                Double.doubleToLongBits(want.rt()),
                Double.doubleToLongBits(v.rt()),
                at + ": rt must be bit-identical (want " + want.rt() + ", got " + v.rt() + ")");

        double[] mz = new double[t.rowCount()];
        double[] in = new double[t.rowCount()];
        for (int i = 0; i < t.rowCount(); i++) {
            mz[i] = t.mz(i);
            in[i] = t.intensity(i);
        }

        assertDigest(at, "m/z", want.mzSha256(), mz, want.mzHexFirst8());
        assertDigest(at, "intensity", want.iSha256(), in, want.iHexFirst8());

        double sum = 0.0;
        for (double x : in) sum += x;
        double wantSum = want.iSum();
        if (wantSum != 0.0) {
            assertTrue(
                    Math.abs(sum - wantSum) / Math.abs(wantSum) < SUM_TOL,
                    at
                            + ": intensity sum "
                            + sum
                            + " vs "
                            + wantSum
                            + " exceeds the "
                            + SUM_TOL
                            + " accumulation-order tolerance");
        } else {
            assertEquals(0.0, sum, at + ": intensity sum should be zero");
        }
    }

    private static void assertDigest(
            String at, String what, String wantHex, double[] actual, List<String> wantFirst8) {
        String gotHex = ParityDump.sha256Of(actual);
        if (gotHex.equals(wantHex)) return;

        int n = Math.min(wantFirst8.size(), actual.length);
        int firstBadIndex = -1;
        for (int i = 0; i < n; i++) {
            if (Double.doubleToLongBits(ParityDump.parseHex(wantFirst8.get(i)))
                    != Double.doubleToLongBits(actual[i])) {
                firstBadIndex = i;
                break;
            }
        }

        StringBuilder msg = new StringBuilder(at + ": " + what + " array is NOT bit-identical.\n");
        if (firstBadIndex < 0) {
            msg.append("  The leading ")
                    .append(n)
                    .append(" values MATCH, so the values are right at the ")
                    .append(
                            "head -- suspect ORDERING, not decoding (see PeakOrderPreconditionTest).\n");
        } else {
            msg.append("  First divergence at index ")
                    .append(firstBadIndex)
                    .append(": want ")
                    .append(ParityDump.parseHex(wantFirst8.get(firstBadIndex)))
                    .append(" (")
                    .append(wantFirst8.get(firstBadIndex))
                    .append(")")
                    .append(", got ")
                    .append(actual[firstBadIndex])
                    .append(" (")
                    .append(Double.toHexString(actual[firstBadIndex]))
                    .append(").\n")
                    .append(
                            "  A value that is NEARLY right is the signature of reading 8 bytes where 4 were ")
                    .append(
                            "written, or decoding a 32-bit array straight to double instead of widening.\n");
        }
        msg.append("  want digest ").append(wantHex).append("\n  got  digest ").append(gotHex);
        fail(msg.toString());
    }
}
