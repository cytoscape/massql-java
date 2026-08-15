package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class StreamingMemoryTest {
    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long settledHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return usedHeap();
    }

    @Test
    void streamingALargeFileRetainsNothingAcrossScans() {
        Path mgf = Fixtures.require("data/PlusRise.mgf");

        long baseline = settledHeap();
        long peakDuring = baseline;
        int scans = 0;
        long peaks = 0;

        try (SpectraStream s = SpectraFile.open(mgf)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                peaks += t.rowCount();
                scans++;

                if ((scans & 0x3FF) == 0) peakDuring = Math.max(peakDuring, usedHeap());
            }
        }

        assertEquals(34_513, scans, "every block must be yielded, including the 12,571 empty ones");

        assertEquals(
                758_544L,
                peaks,
                "real MS2 peak rows (dump total minus MassQL's synthetic MS1 row)");

        long after = settledHeap();
        long retained = after - baseline;

        System.out.printf(
                "  streamed %,d scans / %,d peaks | baseline %,d KB, peak %,d KB, retained %,d KB%n",
                scans, peaks, baseline / 1024, peakDuring / 1024, retained / 1024);

        assertTrue(
                retained < 24L * 1024 * 1024,
                "after streaming, "
                        + (retained / 1024 / 1024)
                        + " MB is still retained; "
                        + "the reader appears to be accumulating scans rather than streaming");
    }

    @Test
    void streamsWithinAConstrainedHeap() throws Exception {
        Path mgf = Fixtures.require("data/PlusRise.mgf");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-Xmx48m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        StreamHarness.class.getName(),
                        mgf.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output =
                new String(
                                proc.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .trim();
        int exit = proc.waitFor();

        System.out.println("  48 MB heap subprocess: exit=" + exit + " | " + output);
        assertEquals(
                0,
                exit,
                "streaming PlusRise.mgf inside a 48 MB heap failed -- memory is no longer bounded by "
                        + "scan size. Output:\n"
                        + output);
        assertTrue(output.contains("34513 scans"), "unexpected subprocess output: " + output);
        assertTrue(output.contains("758544 peaks"), "unexpected subprocess output: " + output);
    }

    public static final class StreamHarness {
        public static void main(String[] args) {
            int scans = 0;
            long peaks = 0;
            try (SpectraStream s = SpectraFile.open(Path.of(args[0]))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    peaks += v.peaks().rowCount();
                    scans++;
                }
            }
            System.out.println(scans + " scans " + peaks + " peaks");
        }
    }

    @Test
    void mzxmlStreamsWithinAConstrainedHeap() throws Exception {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb =
                new ProcessBuilder(
                        javaBin,
                        "-Xmx48m",
                        "-cp",
                        System.getProperty("java.class.path"),
                        StreamHarness.class.getName(),
                        mzxml.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output =
                new String(
                                proc.getInputStream().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)
                        .trim();
        int exit = proc.waitFor();

        System.out.println("  48 MB heap subprocess (mzXML): exit=" + exit + " | " + output);
        assertEquals(
                0,
                exit,
                "streaming DP00570_F02.mzxml inside a 48 MB heap failed -- the mzXML reader is "
                        + "accumulating scans rather than streaming. Output:\n"
                        + output);
        assertTrue(output.contains("916 scans"), "unexpected subprocess output: " + output);
    }

    @Test
    void mzxmlRetainsNothingAcrossScans() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        long baseline = settledHeap();
        int scans = 0;
        long peaks = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                peaks += v.peaks().rowCount();
                scans++;
            }
        }
        long retained = settledHeap() - baseline;

        assertEquals(916, scans);
        System.out.printf(
                "  mzXML: streamed %d scans / %,d peaks | retained %,d KB%n",
                scans, peaks, retained / 1024);
        assertTrue(
                retained < 24L * 1024 * 1024,
                "after streaming, "
                        + (retained / 1024 / 1024)
                        + " MB is still retained; the mzXML "
                        + "reader appears to be accumulating scans");
    }
}
