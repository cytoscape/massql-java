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

import org.junit.jupiter.api.Test;

/**
 * Tests the parity harness itself — because a gate that cannot fail proves nothing.
 *
 * <p>This is not ceremony. A bit-comparison harness that silently coerces to {@code float}, or a digest
 * routine that hashes a decimal rendering instead of the raw bits, produces a green gate while the decoder
 * is wrong. That is the exact failure {@code ReaderParityIT} exists to prevent, so the harness gets its own
 * adversarial tests first.
 *
 * <p>The precedent: {@code ZeroPeakMs1ChainTest} was only trustworthy once the guard was removed and it
 * reported "Got 4 — expected 2". Every assertion below is the same idea — break the input on purpose and
 * require the harness to notice.
 */
class ReaderParityHarnessTest {

    // ---------------------------------------------------------------- hex parsing

    @Test
    void hexParsingRoundTripsExactly() {
        // The dumps store floats as Java/C99 hex literals precisely so no decimal rounding
        // intervenes.
        double[] awkward = {
            0.0,
            -0.0,
            1.0,
            0.023,
            0.011218333333333334, // small.mzML scan 3 rt -- does not survive a float round-trip
            123.456789012345, // the micro non-float32-exact m/z
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
        // Python's float.hex() writes "0x1.436b8f9b13166p-8"; Java's Double.toHexString writes the
        // same
        // shape. Assert against literals lifted from an actual dump, so a generator change is
        // caught here
        // rather than as 900 mysterious failures downstream.
        assertEquals(
                Double.doubleToLongBits(0.004935),
                Double.doubleToLongBits(ParityDump.parseHex("0x1.436b8f9b13166p-8")),
                "the rt_hex form from small.mzML scan 1 must parse exactly");
        assertEquals(
                0.0, ParityDump.parseHex("0x0.0p+0"), "the all-zero form used by empty MGF sums");
    }

    @Test
    void aDecimalRoundTripWouldHaveLostBitsHere() {
        // Documents WHY the dumps are hex, executably. Six significant digits is what a casual
        // "%.6f"-style dump would keep, and it is not enough: the value comes back a different
        // double.
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

    // ---------------------------------------------------------------- digests

    @Test
    void aSingleBitPerturbationChangesTheDigest() {
        // THE assertion that makes this gate meaningful. A decoder reading 8 bytes where 4 were
        // written
        // produces values that are *nearly* right; nothing but an exact comparison catches that.
        double[] values = {100.0, 200.5, 300.25, 400.125};
        String clean = ParityDump.sha256Of(values);

        double[] perturbed = values.clone();
        perturbed[2] = Math.nextUp(perturbed[2]); // the smallest possible change
        assertNotEquals(
                clean,
                ParityDump.sha256Of(perturbed),
                "a one-ULP change did not alter the digest -- the harness is not bit-exact");

        // And confirm the perturbation really is one ULP, i.e. this test is not cheating by using a
        // visibly different number.
        assertEquals(
                1,
                Math.abs(
                        Double.doubleToLongBits(perturbed[2]) - Double.doubleToLongBits(values[2])),
                "the perturbation should differ in exactly the last bit");
    }

    @Test
    void reorderingChangesTheDigest() {
        // The INVERSE of what the spec originally asked for (C32d): it wanted a multiset comparator
        // where
        // reordering compares EQUAL. The dumps store digests, which are order-sensitive, and that
        // is a
        // feature -- our array order must match MassQL's file order. PeakOrderPreconditionTest
        // asserts the
        // precondition that makes this safe.
        double[] a = {100.0, 200.0, 300.0};
        double[] b = {100.0, 300.0, 200.0};
        assertNotEquals(
                ParityDump.sha256Of(a),
                ParityDump.sha256Of(b),
                "the digest must be order-sensitive; if it were not, a reordered decode would pass");
    }

    @Test
    void signedZeroAndNaNAreDistinguished() {
        // -0.0 == 0.0 under ==, so a harness comparing with == would treat them as identical. The
        // digest
        // must not, because they are different bits and MassQL would have produced one specific
        // one.
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
        // End to end on real data: recompute a digest from OUR decode and compare to what Python
        // wrote.
        // If this fails, either the packing convention or the decode is wrong, and every other test
        // in
        // this class could still pass -- so it belongs here rather than only in ReaderParityIT.
        ParityDump dump = ParityDump.of("micro.mzXML");
        ParityDump.Scan want = dump.scans().get(new ParityDump.Key(1, 2));
        assertNotNull(want, "micro.mzXML should have an MS1 scan 2 entry");

        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 2) continue;
                var t = v.materialize();
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

    // ---------------------------------------------------------------- dump loading

    @Test
    void everyExpectedDumpLoadsAndIsNonEmpty() {
        // Folds in the old ParityCoverageTest: a dump that failed to parse would yield an
        // empty map
        // and make every downstream assertion vacuous. ParityDump.of() fails on empty; this asserts
        // the
        // whole expected set is present, so a deleted dump cannot quietly shrink the gate.
        for (String f : ParityFixtures.FIXTURES_WITH_DUMPS.keySet()) {
            ParityDump d = ParityDump.of(f);
            assertFalse(d.scans().isEmpty(), f + " parsed to zero scans");
            assertTrue(d.peakRows() > 0, f + " reports no peak rows");
        }
        assertEquals(
                16,
                ParityFixtures.FIXTURES_WITH_DUMPS.size(),
                "expected 16 fixtures with dumps; if this changed, update PARITY_REPORT.md's coverage table");
    }

    @Test
    void aMissingDumpFailsRatherThanSkipping() {
        // absence must be loud. If this ever returns instead of throwing, the gate can
        // be
        // silently disabled by deleting a file.
        assertThrows(AssertionError.class, () -> ParityDump.of("no_such_fixture.mzML"));
    }

    @Test
    void theDumpKeyIsMslevelPlusScanNotScanAlone() {
        // Asserted rather than trusted: micro.mgf's synthetic MS1 has scan id 3, which is
        // ALSO a
        // real MS2 id. Under a scan-id-only key one of the two would overwrite the other and the
        // harness
        // would compare a real spectrum against a row of zeros.
        ParityDump d = ParityDump.of("micro.mgf");
        Map<ParityDump.Key, ParityDump.Scan> scans = d.scans();

        ParityDump.Scan phantom = scans.get(new ParityDump.Key(1, 3));
        ParityDump.Scan real = scans.get(new ParityDump.Key(2, 3));
        assertNotNull(phantom, "micro.mgf's fake MS1 row (scan 3) should be present");
        assertNotNull(real, "micro.mgf's real MS2 scan 3 should be present");
        assertNotSame(phantom, real, "these must be two distinct entries sharing one scan id");

        // the fake MS1 row is NOT always an all-zero placeholder. MassQL's
        // pyteomics MGF
        // loader ends with `ms1_df = pd.DataFrame([peak_dict])` where peak_dict LEAKS from the MS2
        // peak
        // loop, so the row is a byte-for-byte DUPLICATE of the last MS2 peak. The all-zero form
        // (scan=1, mz=0, i=0) is only the `except` branch, reached when the loop never ran -- which
        // is
        // PlusRise's case via the manual-loader fallback.
        //
        // Both flavours are artifacts our reader correctly omits (MGF has no survey scans), which
        // is why
        // the gate skips MGF mslevel==1 entirely rather than trying to match them.
        assertEquals(1, phantom.peakCount(), "the fake MS1 row is always a single row");
        assertTrue(real.peakCount() >= 1, "the real MS2 scan has actual peaks");
        // micro.mgf takes the pyteomics path, so its fake row duplicates the LAST MS2 peak --
        // meaning a
        // REAL intensity, not zero. Asserting 0.0 here (as this test first did) fails on correct
        // data.
        assertNotEquals(
                0.0,
                phantom.iSum(),
                "micro.mgf's fake MS1 duplicates a real peak, so its intensity is non-zero");

        // PlusRise is the other flavour: the genuine all-zero synthetic row.
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
        // Ascending, which is what makes them a usable ordering diagnostic.
        for (int i = 1; i < mzHex.size(); i++) {
            assertTrue(
                    ParityDump.parseHex(mzHex.get(i)) >= ParityDump.parseHex(mzHex.get(i - 1)),
                    "mz_hex_first8 should be ascending at index " + i);
        }
    }
}
