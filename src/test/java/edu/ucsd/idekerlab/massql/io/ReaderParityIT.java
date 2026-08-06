package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.*;

import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ⛔ <b>THE GATE.</b> All three readers must decode <b>bit-identically</b> to MassQL's own Python loader.
 *
 * <p>If they do not, the decoder is wrong and every number produced downstream measures noise. This is the
 * cheapest place in the spike to learn that: a byte-order or float-precision mistake found here is a
 * one-line fix, while the same mistake found at Step 12 presents as a handful of mismatched result rows and
 * gets misdiagnosed as a filtering or collation bug.
 *
 * <p><b>Do not loosen a comparison to make this pass.</b> A tolerance added here converts a found bug into a
 * permanent unknown.
 *
 * <h2>How the comparison works, and the three traps it navigates</h2>
 *
 * <p><b>Digests, not multisets</b> (Correction C32d). The dumps store SHA-256 over each array packed as
 * big-endian IEEE754 doubles. That is strictly stronger than comparing a multiset, because it pins the
 * array's <b>order</b> too. {@code PeakOrderPreconditionTest} asserts the precondition that makes
 * order-sensitivity correct: no fixture has descending m/z, so {@code SpectrumTableBuilder}'s sort never
 * fires and our order is MassQL's file order.
 *
 * <p><b>Keyed by {@code (mslevel, scan)}</b> (C32a). MassQL synthesises an all-zero MS1 placeholder for MGF
 * whose scan id <b>collides with a real MS2 id</b> — {@code micro.mgf} at 3, {@code DP00570_F02.mgf} at 625.
 * Keying by scan id compares a real spectrum against a row of zeros.
 *
 * <p><b>The dumps omit zero-peak scans; our readers yield them</b> (C32c). MassQL's loaders {@code continue}
 * on an empty intensity array, so its dataframe has no rows for those scans, while our readers emit them and
 * let the engine filter (C24b, C27b). {@code PlusRise.mgf} is <b>34,513 reader scans against 21,942 dump
 * entries</b>. The count of reader-only scans is therefore <b>asserted</b>, not tolerated — otherwise a
 * reader that dropped real spectra would pass this gate silently.
 */
class ReaderParityIT {

    /**
     * Fixture → expected number of reader scans with no dump entry (all of which must have zero peaks).
     *
     * <p>Asserting this number is what stops a reader that drops real spectra from passing. The values are
     * facts about the fixtures: MassQL discards a scan whose intensity array is empty, so the delta is
     * exactly the count of empty scans.
     *
     * <p><b>Deliberately absent</b>: {@code micro_nopolarity.mzXML} and {@code micro_noprecursor.mzXML} —
     * MassQL raises {@code KeyError} on both (C27c), so no dump can exist and parity is not available. They
     * pin our own contract in {@code MzxmlPolarityTest} / {@code MzxmlEdgeCaseTest}. Also absent:
     * {@code micro_multiprec.mzML}, whose peak parity the mzXML twin already establishes.
     */
    static final Map<String, Integer> FIXTURES_WITH_DUMPS = new LinkedHashMap<>();
    static {
        // Real fixtures.
        FIXTURES_WITH_DUMPS.put("small.mzML", 0);
        FIXTURES_WITH_DUMPS.put("small.mzXML", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mzxml", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mgf", 0);
        // 12,571 of PlusRise's 34,513 blocks carry no peak lines (C24b). MassQL loads 21,942.
        FIXTURES_WITH_DUMPS.put("PlusRise.mgf", 12_571);
        // Micro fixtures: scan 4 is a zero-peak MS1, so each mzML/mzXML variant has exactly one extra.
        // micro.mgf is MS2-only, so its zero-peak MS1 never existed on our side -- hence 0, not 1.
        FIXTURES_WITH_DUMPS.put("micro.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro_rtseconds.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_nested.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_multiprec.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mgf", 0);
        // Correction C36: MGF drops zero-intensity peaks. Block 2 of this fixture is ALL zeros, so
        // MassQL emits no rows for it and it vanishes from the dataframe -- our reader still yields
        // the block, now with zero peaks, hence exactly one reader-only scan.
        FIXTURES_WITH_DUMPS.put("micro_zeroint.mgf", 1);
        // C37: two MS1 scans with DIFFERENT peaks -- the only fixture that can discriminate
        // condition ORDER. Every scan has peaks, so nothing is reader-only.
        FIXTURES_WITH_DUMPS.put("micro_ms1var.mzML", 0);
    }

    /** Where each fixture lives under {@code src/test/resources}. */
    private static Path fixturePath(String name) {
        return Fixtures.require(name.startsWith("micro") ? "fixtures/micro/" + name : "data/" + name);
    }

    static List<String> fixtures() { return List.copyOf(FIXTURES_WITH_DUMPS.keySet()); }

    /**
     * Relative tolerance for the intensity <b>sum</b> only.
     *
     * <p><b>1e-6, and the reason is dtype rather than ordering.</b> Tech_Step8 §1 attributed the sum
     * exception to numpy's pairwise accumulation and proposed 1e-15. Measured, that is far too tight:
     * MassQL's intensity column is <b>float32</b>, and {@code dump_loader_parity.py:81} records
     * {@code g["i"].sum()} — a <b>float32 accumulation</b>. On {@code small.mzML} MS1 scan 1 that gives
     * {@code 69381840.0} where the true sum is {@code 69381842.11895752}, a relative error of
     * <b>3.05e-08</b>.
     *
     * <p>Our Java sum is the <i>more accurate</i> one: accumulating the same values in float64 reproduces
     * it <b>exactly</b> (measured difference 0.000e+00). So this tolerance absorbs the dump's float32
     * epsilon (~1.2e-7), nothing of ours. The per-value <b>digests</b> are what establish bit-identity —
     * they pass with no tolerance at all, which is why loosening this one costs the gate nothing.
     */
    private static final double SUM_TOL = 1e-6;

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void decodesBitIdenticallyToThePythonLoader(String fixture) {
        ParityDump dump = ParityDump.of(fixture);
        boolean isMgf = fixture.toLowerCase().endsWith(".mgf");

        // MGF: the dump's single MS1 entry is MassQL's synthetic all-zero placeholder, which our reader
        // correctly omits (C32b). Drop it from the expectation rather than asserting a count of 1.
        Set<ParityDump.Key> expected = new LinkedHashSet<>();
        for (ParityDump.Key k : dump.scans().keySet()) {
            if (isMgf && k.mslevel() == 1) continue;
            expected.add(k);
        }
        if (isMgf) {
            assertEquals(1, dump.ms1ScanCount(),
                    fixture + ": the dump should report exactly one (synthetic) MS1 scan");
        }

        Set<ParityDump.Key> seen = new LinkedHashSet<>();
        int readerOnly = 0, ms1 = 0, ms2 = 0;
        // Counted over scans that MATCHED a dump entry. The raw reader totals cannot be compared to the
        // dump's counts directly: the dump omits zero-peak scans, so e.g. micro.mzML has 2 MS1 scans on
        // our side but ms1_scan_count == 1 in the dump (its scan-4 MS1 is empty). C32c.
        int matchedMs1 = 0, matchedMs2 = 0;
        long peaks = 0;

        try (SpectraStream s = SpectraFile.open(fixturePath(fixture))) {
            while (s.next()) {
                ScanView v = s.current();
                if (v.msLevel() == 1) ms1++; else ms2++;

                SpectrumTable t = v.materialize();
                peaks += t.rowCount();

                ParityDump.Key key = new ParityDump.Key(v.msLevel(), v.scanId());
                ParityDump.Scan want = dump.scans().get(key);

                if (want == null || (isMgf && key.mslevel() == 1)) {
                    // Not in the dump => MassQL dropped it => it must be empty. A non-empty scan absent
                    // from the dump means we decoded something MassQL did not see at all.
                    assertEquals(0, t.rowCount(),
                            fixture + ": " + key + " is absent from the dump but decoded "
                                    + t.rowCount() + " peaks. MassQL only drops EMPTY scans, so this is "
                                    + "either a phantom scan or a scan-id derivation error");
                    readerOnly++;
                    continue;
                }

                assertTrue(seen.add(key), fixture + ": " + key + " was yielded twice");
                if (v.msLevel() == 1) matchedMs1++; else matchedMs2++;
                assertScanMatches(fixture, key, want, v, t);
            }
        }

        // Every dump entry accounted for. Report the missing keys, not just a count -- a bare
        // "expected 48 got 47" sends you hunting.
        Set<ParityDump.Key> missing = new LinkedHashSet<>(expected);
        missing.removeAll(seen);
        assertTrue(missing.isEmpty(),
                fixture + ": the reader never produced " + missing.size() + " scan(s) the dump contains: "
                        + (missing.size() > 12 ? missing.stream().limit(12).toList() + " …" : missing));

        assertEquals(expected.size(), seen.size(), fixture + ": scan-set size");

        // Compare the dump's counts against MATCHED scans, not raw reader totals -- the dump omits
        // zero-peak scans (C32c), so the raw totals legitimately exceed it.
        assertEquals(dump.ms2ScanCount(), matchedMs2, fixture + ": MS2 scan count (dump-matched)");
        if (isMgf) {
            assertEquals(0, ms1,
                    fixture + ": MGF has no survey scans, so our reader must yield ZERO MS1 scans -- the "
                            + "dump's single MS1 entry is MassQL's fake row (C32b/C33)");
            assertEquals(0, matchedMs1, fixture + ": no MGF MS1 entry should ever be matched");
        } else {
            assertEquals(dump.ms1ScanCount(), matchedMs1, fixture + ": MS1 scan count (dump-matched)");
            // And the raw total must exceed the matched count by exactly the reader-only scans, all of
            // which are empty. This is what ties the two accountings together rather than leaving the
            // discrepancy unexplained.
            assertEquals(ms1 + ms2, matchedMs1 + matchedMs2 + readerOnly,
                    fixture + ": every yielded scan must be either dump-matched or a counted zero-peak extra");
        }

        assertEquals(FIXTURES_WITH_DUMPS.get(fixture).intValue(), readerOnly,
                fixture + ": expected exactly " + FIXTURES_WITH_DUMPS.get(fixture)
                        + " reader-only (zero-peak) scan(s), saw " + readerOnly
                        + ". This count is ASSERTED, not tolerated: a reader that dropped real spectra "
                        + "would otherwise pass this gate silently (C32c)");

        System.out.printf("  %-24s %4d scans (%3d MS1 / %5d MS2) | %,10d peaks | %,6d reader-only%n",
                fixture, ms1 + ms2, ms1, ms2, peaks, readerOnly);
    }

    /** Every per-scan field, bit-exact where the spec demands it. */
    private static void assertScanMatches(String fixture, ParityDump.Key key, ParityDump.Scan want,
                                          ScanView v, SpectrumTable t) {
        String at = fixture + " " + key;

        assertEquals(want.peakCount(), t.rowCount(), at + ": peak count");
        assertEquals(want.polarity(), v.polarity(), at + ": polarity");

        // rt BIT-identical. This single assertion covers all three RT-unit rules -- mzML's conditional
        // conversion, mzXML's unconditional one, MGF's RTINSECONDS/60 -- and requires the double-precision
        // scanRt from Step 5 §1. A float comparison passes here and fails the Step 12 differential.
        assertEquals(Double.doubleToLongBits(want.rt()), Double.doubleToLongBits(v.rt()),
                at + ": rt must be bit-identical (want " + want.rt() + ", got " + v.rt() + ")");

        double[] mz = new double[t.rowCount()];
        double[] in = new double[t.rowCount()];
        for (int i = 0; i < t.rowCount(); i++) { mz[i] = t.mz(i); in[i] = t.intensity(i); }

        assertDigest(at, "m/z", want.mzSha256(), mz, want.mzHexFirst8());
        assertDigest(at, "intensity", want.iSha256(), in, want.iHexFirst8());

        // Secondary signal only: numpy may pairwise-accumulate where we go left to right, so the last bits
        // can differ from identical inputs. The digests above are what establish bit-identity.
        double sum = 0.0;
        for (double x : in) sum += x;
        double wantSum = want.iSum();
        if (wantSum != 0.0) {
            assertTrue(Math.abs(sum - wantSum) / Math.abs(wantSum) < SUM_TOL,
                    at + ": intensity sum " + sum + " vs " + wantSum + " exceeds the "
                            + SUM_TOL + " accumulation-order tolerance");
        } else {
            assertEquals(0.0, sum, at + ": intensity sum should be zero");
        }
    }

    /**
     * Compares a digest, and makes a failure <b>actionable</b>.
     *
     * <p>A mismatched 64-character hash says nothing on its own. When it differs, check the dump's leading
     * eight values: if those still match, the values are right at the head and the fault is <b>ordering</b>
     * rather than decoding. That is exactly what the {@code *_hex_first8} fields are in the dump for.
     */
    private static void assertDigest(String at, String what, String wantHex, double[] actual,
                                     List<String> wantFirst8) {
        String gotHex = ParityDump.sha256Of(actual);
        if (gotHex.equals(wantHex)) return;

        int n = Math.min(wantFirst8.size(), actual.length);
        int firstBadIndex = -1;
        for (int i = 0; i < n; i++) {
            if (Double.doubleToLongBits(ParityDump.parseHex(wantFirst8.get(i)))
                    != Double.doubleToLongBits(actual[i])) { firstBadIndex = i; break; }
        }

        StringBuilder msg = new StringBuilder(at + ": " + what + " array is NOT bit-identical.\n");
        if (firstBadIndex < 0) {
            msg.append("  The leading ").append(n).append(" values MATCH, so the values are right at the ")
               .append("head -- suspect ORDERING, not decoding (see PeakOrderPreconditionTest).\n");
        } else {
            msg.append("  First divergence at index ").append(firstBadIndex)
               .append(": want ").append(ParityDump.parseHex(wantFirst8.get(firstBadIndex)))
               .append(" (").append(wantFirst8.get(firstBadIndex)).append(")")
               .append(", got ").append(actual[firstBadIndex])
               .append(" (").append(Double.toHexString(actual[firstBadIndex])).append(").\n")
               .append("  A value that is NEARLY right is the signature of reading 8 bytes where 4 were ")
               .append("written, or decoding a 32-bit array straight to double instead of widening.\n");
        }
        msg.append("  want digest ").append(wantHex).append("\n  got  digest ").append(gotHex);
        fail(msg.toString());
    }
}
