package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StoreScaleTest {
    private static SpectrumTable build(int scans, int peaksPerScan) {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        for (int s = 1; s <= scans; s++) {
            b.startScan(s, s * 0.001, 1, 500.0, s, 1);
            for (int p = 0; p < peaksPerScan; p++) {
                b.addPeak(100.0 + p * 0.01, 1000.0 + p);
            }
        }
        return b.build();
    }

    @Test
    void buildsAMillionRowsAndKeepsItsInvariants() {
        long t0 = System.nanoTime();
        SpectrumTable t = build(30_000, 33);
        long buildMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(30_000, t.index().scanCount());
        assertEquals(990_000, t.rowCount());

        assertEquals(30_000, t.index().scanIdAt(29_999));
        assertEquals(30_000 * 0.001, t.index().rtOf(29_999), 1e-9);
        assertEquals(1.0, t.iNorm(t.rowCount() - 1), 1e-12, "last peak is its scan's base peak");
        System.out.println("  build 990k rows / 30k scans: " + buildMs + " ms");
    }

    @Test
    void mzWindowCostDoesNotGrowWithScanCount() {
        SpectrumTable small = build(300, 33);
        SpectrumTable large = build(30_000, 33);

        long tSmall = timeWindows(small, 200_000, false);
        long tLarge = timeWindows(large, 200_000, false);

        System.out.println("  200k windows over 300 scans: " + tSmall + " ms");
        System.out.println("  200k windows over 30k scans: " + tLarge + " ms");

        assertTrue(
                tLarge < Math.max(50, tSmall * 10L),
                "mzWindow looks linear in table size: " + tSmall + "ms vs " + tLarge + "ms");
    }

    @Test
    void exclusiveWindowCostAlsoDoesNotGrowWithScanCount() {
        SpectrumTable small = build(300, 33);
        SpectrumTable large = build(30_000, 33);

        long tSmall = timeWindows(small, 200_000, true);
        long tLarge = timeWindows(large, 200_000, true);

        System.out.println("  200k exclusive windows over 300 scans: " + tSmall + " ms");
        System.out.println("  200k exclusive windows over 30k scans: " + tLarge + " ms");

        assertTrue(
                tLarge < Math.max(50, tSmall * 10L),
                "mzWindowExclusive looks linear in table size: "
                        + tSmall
                        + "ms vs "
                        + tLarge
                        + "ms");
    }

    private static long timeWindows(SpectrumTable t, int iterations, boolean exclusive) {
        int scans = t.index().scanCount();
        long t0 = System.nanoTime();
        long hits = 0;
        for (int k = 0; k < iterations; k++) {
            int ordinal = k % scans;

            double centre = 100.0 + ((k * 7919L) % 33) * 0.01;
            IntRange r =
                    exclusive
                            ? t.mzWindowExclusive(ordinal, centre - 0.005, centre + 0.005)
                            : t.mzWindow(ordinal, centre - 0.005, centre + 0.005);
            hits += r.size();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(hits > 0, "the timing loop must actually be matching peaks");
        return ms;
    }

    @Test
    void windowsRemainCorrectAtScale() {
        SpectrumTable t = build(1_000, 33);
        for (int ordinal : new int[] {0, 1, 500, 999}) {
            IntRange all = t.mzWindow(ordinal, 100.0, 100.0 + 32 * 0.01);
            assertEquals(33, all.size(), "whole-scan window at ordinal " + ordinal);
            assertEquals(t.index().rowStart(ordinal), all.start());
            assertEquals(t.index().rowEnd(ordinal), all.end());

            IntRange one = t.mzWindow(ordinal, 100.0, 100.0);
            assertEquals(1, one.size());
            assertEquals(100.0, t.mz(one.start()));
        }
    }

    @Test
    void perScanReductionsStayLinearInPeaksNotInTableSize() {
        SpectrumTable t = build(20_000, 50);
        long t0 = System.nanoTime();
        double total = 0;
        for (int s = 0; s < t.index().scanCount(); s++) {
            total += Reductions.sum(t, s, Column.I);
            Reductions.argmax(t, s, Column.I);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("  sum+argmax over 20k scans (1M peaks): " + ms + " ms");
        assertTrue(total > 0);

        assertTrue(ms < 5_000, "per-scan reductions took " + ms + "ms, suspiciously slow");
    }
}
