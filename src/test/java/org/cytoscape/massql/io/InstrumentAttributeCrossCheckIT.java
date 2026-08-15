package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class InstrumentAttributeCrossCheckIT {
    private static final double TOL = 1e-5;

    private record Declared(int num, double tic, double basePeakI, double basePeakMz) {}

    private static List<Declared> declaredAttributes(Path p) {
        String raw;
        try {
            raw = Files.readString(p, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Declared> out = new ArrayList<>();
        Matcher m = Pattern.compile("<scan\\s([^>]*)>").matcher(raw);
        while (m.find()) {
            String a = m.group(1);
            String tic = attr(a, "totIonCurrent");
            String bpi = attr(a, "basePeakIntensity");
            String bpm = attr(a, "basePeakMz");
            if (tic == null || bpi == null || bpm == null) continue;
            out.add(
                    new Declared(
                            Integer.parseInt(attr(a, "num")),
                            Double.parseDouble(tic),
                            Double.parseDouble(bpi),
                            Double.parseDouble(bpm)));
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static double rel(double got, double want) {
        if (want == 0.0) return got == 0.0 ? 0.0 : Double.POSITIVE_INFINITY;
        return Math.abs(got - want) / Math.abs(want);
    }

    @Test
    void ourDecodeReconcilesWithTheInstrumentsOwnSummaryValues() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        List<Declared> declared = declaredAttributes(mzxml);
        assertEquals(
                916,
                declared.size(),
                "every scan should declare all three attributes; if not, this check is weaker than it looks");
        Map<Integer, Declared> byNum = new LinkedHashMap<>();
        declared.forEach(d -> byNum.put(d.num(), d));

        double worstTic = 0, worstBpi = 0, worstBpm = 0;
        int worstTicScan = 0, worstBpiScan = 0, worstBpmScan = 0;
        int signedTicHigh = 0, signedTicLow = 0;
        int compared = 0;

        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                Declared d = byNum.get(v.scanId());
                assertNotNull(d, "scan " + v.scanId() + " has no declared attributes");

                SpectrumTable t = v.peaks();
                if (t.rowCount() == 0) continue;

                double tic = 0, maxI = Double.NEGATIVE_INFINITY, bpMz = Double.NaN;
                for (int i = 0; i < t.rowCount(); i++) {
                    tic += t.intensity(i);
                    if (t.intensity(i) > maxI) {
                        maxI = t.intensity(i);
                        bpMz = t.mz(i);
                    }
                }

                double rTic = rel(tic, d.tic()),
                        rBpi = rel(maxI, d.basePeakI()),
                        rBpm = rel(bpMz, d.basePeakMz());
                if (rTic > worstTic) {
                    worstTic = rTic;
                    worstTicScan = v.scanId();
                }
                if (rBpi > worstBpi) {
                    worstBpi = rBpi;
                    worstBpiScan = v.scanId();
                }
                if (rBpm > worstBpm) {
                    worstBpm = rBpm;
                    worstBpmScan = v.scanId();
                }
                if (tic > d.tic()) signedTicHigh++;
                else if (tic < d.tic()) signedTicLow++;
                compared++;
            }
        }

        System.out.printf(
                "  instrument cross-check over %d scans: worst rel delta"
                        + " tic=%.3e (scan %d), basePeakI=%.3e (scan %d), basePeakMz=%.3e (scan %d)%n",
                compared, worstTic, worstTicScan, worstBpi, worstBpiScan, worstBpm, worstBpmScan);

        assertEquals(916, compared, "every scan should have been compared");
        assertTrue(
                worstTic < TOL,
                "totIonCurrent drifted "
                        + worstTic
                        + " at scan "
                        + worstTicScan
                        + " -- beyond float noise, this is a decode bug");
        assertTrue(
                worstBpi < TOL,
                "basePeakIntensity drifted " + worstBpi + " at scan " + worstBpiScan);
        assertTrue(
                worstBpm < TOL,
                "basePeakMz drifted "
                        + worstBpm
                        + " at scan "
                        + worstBpmScan
                        + " -- a wrong argmax picks a different peak entirely, so this would be large, not small");

        int minSide = Math.min(signedTicHigh, signedTicLow);
        assertTrue(
                minSide > compared / 20,
                "tic differences are one-sided ("
                        + signedTicHigh
                        + " high vs "
                        + signedTicLow
                        + " low) -- that is systematic bias, not rounding noise");
    }

    @Test
    void aWrongArgmaxWouldBeCaughtLoudly() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");
        int checked = 0;
        double smallestGap = Double.POSITIVE_INFINITY;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext() && checked < 50) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                if (t.rowCount() < 2) continue;
                int best = 0, second = -1;
                for (int i = 1; i < t.rowCount(); i++)
                    if (t.intensity(i) > t.intensity(best)) best = i;
                for (int i = 0; i < t.rowCount(); i++) {
                    if (i == best) continue;
                    if (second < 0 || t.intensity(i) > t.intensity(second)) second = i;
                }
                double gap = rel(t.mz(second), t.mz(best));
                smallestGap = Math.min(smallestGap, gap);
                checked++;
            }
        }
        assertTrue(checked > 0, "no multi-peak scans examined");
        assertTrue(
                smallestGap > TOL * 10,
                "the runner-up peak's m/z is within "
                        + smallestGap
                        + " of the base peak's, so a wrong "
                        + "argmax could hide inside the tolerance; this check is weaker than assumed");
    }
}
