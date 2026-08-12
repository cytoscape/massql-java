package org.cytoscape.massql.spectra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Guards the differential performance criterion at the level where the cause is obvious.
 *
 * <p>If this is not at least as fast as the reference on the MGF, something is quadratic — probably a linear scan
 * where a binary search belongs. Finding that in the differential means profiling a whole pipeline;
 * finding it here points at one method.
 *
 * <p>The assertions are deliberately loose in absolute terms — this must not flake on a busy
 * machine. What it actually proves is the <b>shape</b> of the cost: windowing a table with
 * 30,000 scans is compared against windowing one with 300, and if the window methods were doing
 * linear work the ratio would track the scan size rather than staying flat.
 *
 * <p><b>Both window methods are covered</b>. {@code mzWindowExclusive} is if
 * anything the hotter of the two — every condition calls it on every scan, whereas
 * {@code mzWindow} is called once per qualifying scan by the precursor lookup — so
 * leaving it untimed would have guarded the cooler path.
 */
class StoreScaleTest {

    /** ~1M peaks across 30k scans, the order of magnitude of PlusRise.mgf's loaded table. */
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
        // Spot-check the far end: a growth bug would corrupt the tail, not the head.
        assertEquals(30_000, t.index().scanIdAt(29_999));
        assertEquals(30_000 * 0.001, t.index().rtOf(29_999), 1e-9);
        assertEquals(1.0, t.iNorm(t.rowCount() - 1), 1e-12, "last peak is its scan's base peak");
        System.out.println("  build 990k rows / 30k scans: " + buildMs + " ms");
    }

    @Test
    void mzWindowCostDoesNotGrowWithScanCount() {
        // Same peaks per scan, 100x the scans. A linear-scan implementation would show a cost
        // ratio tracking the table size; a binary search stays flat.
        SpectrumTable small = build(300, 33);
        SpectrumTable large = build(30_000, 33);

        long tSmall = timeWindows(small, 200_000, false);
        long tLarge = timeWindows(large, 200_000, false);

        System.out.println("  200k windows over 300 scans: " + tSmall + " ms");
        System.out.println("  200k windows over 30k scans: " + tLarge + " ms");

        // Generous: allows a 10x swing for JIT, cache effects and machine noise while still
        // catching the 100x that a linear scan over 100x more rows would produce.
        assertTrue(
                tLarge < Math.max(50, tSmall * 10L),
                "mzWindow looks linear in table size: " + tSmall + "ms vs " + tLarge + "ms");
    }

    @Test
    void exclusiveWindowCostAlsoDoesNotGrowWithScanCount() {
        // The engine path. Same shape assertion as above -- the exclusive variant is a
        // separate pair of binary searches with the roles swapped, so it can regress into a
        // linear scan independently of its inclusive twin.
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
            // Deterministic pseudo-random target; Math.random() would make this unreproducible.
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
        // Speed is worthless if the answer is wrong; check a few windows against hand values.
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
        // One pass over a million peaks twice; anything near a second means a nested scan.
        assertTrue(ms < 5_000, "per-scan reductions took " + ms + "ms, suspiciously slow");
    }
}
