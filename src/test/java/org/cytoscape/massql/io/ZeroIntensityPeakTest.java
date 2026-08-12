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
import org.junit.jupiter.api.Test;

/**
 * <b>MGF drops zero-intensity peaks; mzML and mzXML keep them</b>.
 *
 * <p>{@code _load_data_mgf_pyteomics} opens its peak loop with {@code if intensity == 0: continue}, so a
 * zero-intensity peak never becomes a row: MassQL cannot match it, count it or sum it. The mzML and mzXML
 * loaders have <b>no such guard</b>, and that asymmetry is real rather than an oversight — {@code small.mzML}'s
 * parity dump carries <b>eight leading {@code 0x0.0p+0} intensities</b>, retained on both sides.
 *
 * <p><b>Why this was latent.</b> Our {@code MgfReader} kept zero-intensity peaks, and not one of the three MGF
 * fixtures contained a single one — measured. So the Step 8 parity gate passed while being structurally unable
 * to detect the divergence. Exactly the recurring shape: a rule with no fixture that can
 * discriminate. {@code micro_zeroint.mgf} exists to close it.
 *
 * <p>Both directions are asserted <b>in the same class</b> so the asymmetry is visible rather than folklore. A
 * future engineer "tidying up" by applying the skip to all three readers would break every mzML fixture in the
 * parity gate; a test file per format would not make that obvious.
 */
class ZeroIntensityPeakTest {

    private static Map<Integer, double[][]> peaksByScan(String fixture) {
        Map<Integer, double[][]> out = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(Fixtures.require(fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.materialize();
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

    // ---------------------------------------------------------------- MGF: dropped

    @Test
    void mgfDropsZeroIntensityPeaksKeepingTheRestIntact() {
        // micro_zeroint.mgf block 1 is: 100/250, 150/0, 200/1500, 250/0, 300/750.
        // MassQL's loader yields three rows; verified against it directly before this was written.
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

        // A zero BETWEEN two real peaks and a zero TRAILING them are dropped differently by an
        // off-by-one, which is why the fixture has one of each.
        assertEquals(3, scan1[0].length, "3 of 5 peaks survive");
    }

    @Test
    void anAllZeroBlockBecomesAZeroPeakScanRatherThanVanishing() {
        // Block 2's every peak is zero-intensity, so MassQL emits no rows and the scan is absent
        // from its
        // dataframe entirely (verified: its ms2_df holds scans 1 and 3 only).
        //
        // Our reader still YIELDS the block, now with zero peaks -- consistent with the reader
        // rules,
        // where the
        // reader stays faithful to the file and the engine filters. the zero-peak guard
        // is
        // what then makes the two agree.
        Map<Integer, double[][]> got = peaksByScan("fixtures/micro/micro_zeroint.mgf");

        double[][] scan2 = got.get(2);
        assertNotNull(scan2, "the all-zero block must still be yielded, not dropped by the reader");
        assertEquals(0, scan2[0].length, "every peak was zero-intensity, so the scan has no peaks");

        assertEquals(3, got.size(), "all three blocks are yielded; only their PEAKS differ");
        assertEquals(1, got.get(3)[0].length, "the control block is untouched");
    }

    @Test
    void theSurvivingPeaksTotalFourNotSix() {
        // The single number that would have caught the old behaviour: 6 peak lines across the file,
        // of
        // which 4 have non-zero intensity. The parity dump agrees at 4.
        long peaks = 0;
        try (SpectraStream s =
                SpectraFile.open(Fixtures.require("fixtures/micro/micro_zeroint.mgf"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                peaks += v.materialize().rowCount();
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
        // MassQL computes i_max/i_sum from the FULL array *before* the skip, and a zero alters
        // neither a
        // max nor a sum -- so the denominators are identical either way and no Step 5 change was
        // needed.
        // Assert that rather than trusting the arithmetic: block 1's sum is 250+1500+750 = 2500,
        // max 1500.
        try (SpectraStream s =
                SpectraFile.open(Fixtures.require("fixtures/micro/micro_zeroint.mgf"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 1) continue;
                SpectrumTable t = v.materialize();
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

    // ---------------------------------------------------------------- mzML / mzXML: retained

    @Test
    void mzmlRetainsZeroIntensityPeaks() {
        // The contrast, and the reason the MGF skip must NOT be generalised. small.mzML's MS1 scan
        // 1 opens
        // with eight zero-intensity peaks -- its parity dump records i_hex_first8 as eight
        // `0x0.0p+0`
        // entries, and the gate compares that digest bit-for-bit. Dropping them here would fail
        // Step 8 on
        // every mzML fixture.
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
                SpectrumTable t = v.materialize();
                for (int i = 0; i < 8; i++) {
                    assertEquals(
                            0.0,
                            t.intensity(i),
                            0.0,
                            "mzML must RETAIN zero-intensity peaks; row "
                                    + i
                                    + " should be 0.0. The MGF "
                                    + "skip is MGF-only -- generalising it breaks the Step 8 gate");
                }
                assertEquals(want.peakCount(), t.rowCount(), "peak count includes the zeros");
                return;
            }
            fail("scan 1 not found");
        }
    }

    @Test
    void mzxmlRetainsZeroIntensityPeaksToo() {
        // Same rule, other format. micro.mzXML has no zero-intensity peak of its own, so assert the
        // structural fact instead: our mzXML peak count equals the dump's, which was produced by a
        // loader
        // with no zero-intensity guard at all.
        for (String f : new String[] {"micro.mzXML", "small.mzXML"}) {
            ParityDump dump = ParityDump.of(f);
            String path = f.startsWith("micro") ? "fixtures/micro/" + f : "data/" + f;
            try (SpectraStream s = SpectraFile.open(Fixtures.require(path))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    ParityDump.Scan want =
                            dump.scans().get(new ParityDump.Key(v.msLevel(), v.scanId()));
                    if (want == null) continue; // zero-peak scan, absent from the dump
                    assertEquals(
                            want.peakCount(),
                            v.materialize().rowCount(),
                            f + " scan " + v.scanId() + ": mzXML applies no intensity filter");
                }
            }
        }
    }
}
