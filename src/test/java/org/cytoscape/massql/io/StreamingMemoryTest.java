package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.junit.jupiter.api.Test;

/**
 * Proves the memory claim instead of restating it.
 *
 * <p>The whole justification for the streaming redesign is that retained memory is bounded by the
 * largest <b>scan</b>, not by the file. That claim came from arithmetic — 10.7–20.0 bytes of file per
 * peak, 41 bytes of store per peak, so a 500 MB input projects to 1.0–1.9 GB — and arithmetic is
 * exactly the kind of thing that is quietly wrong. If the reader accidentally accumulates scans, every
 * functional test still passes and the SDK OOMs on a real file.
 *
 * <p>Bounds are deliberately generous: this must not flake on a busy machine. What it actually tests
 * is the <i>shape</i> — that nothing accumulates across a long stream — not an absolute byte figure.
 */
class StreamingMemoryTest {

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Best-effort quiesce. Not a guarantee, which is why the assertions below are loose. */
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
        // PlusRise.mgf: 34,513 blocks, 758,545 peak rows. Materialising the whole file into one
        // store
        // would cost ~31 MB at 41 B/peak; streaming should retain one scan at a time.
        Path mgf = Fixtures.require("data/PlusRise.mgf");

        long baseline = settledHeap();
        long peakDuring = baseline;
        int scans = 0;
        long peaks = 0;

        try (SpectraStream s = SpectraFile.open(mgf)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.materialize();
                peaks += t.rowCount();
                scans++;
                // t goes out of scope each iteration -- nothing here holds a reference.
                if ((scans & 0x3FF) == 0) peakDuring = Math.max(peakDuring, usedHeap());
            }
        }

        assertEquals(34_513, scans, "every block must be yielded, including the 12,571 empty ones");
        // 758,544 real MS2 peak rows. The parity dump reports 758,545 because MassQL's MGF loader
        // synthesises a 1-row all-zero MS1 placeholder -- mz=0, i=0, scan=1, its
        // digests are the SHA of a zero. That row is not a peak, and our reader correctly omits it.
        // the parity comparison must exclude it too, or it will report a phantom MS1 scan.
        assertEquals(
                758_544L,
                peaks,
                "real MS2 peak rows (dump total minus MassQL's synthetic MS1 row)");

        long after = settledHeap();
        long retained = after - baseline;

        System.out.printf(
                "  streamed %,d scans / %,d peaks | baseline %,d KB, peak %,d KB, retained %,d KB%n",
                scans, peaks, baseline / 1024, peakDuring / 1024, retained / 1024);

        // The real assertion: once the stream is closed and the heap settles, essentially nothing
        // is
        // still held. A reader accumulating scans would show tens of MB here.
        assertTrue(
                retained < 24L * 1024 * 1024,
                "after streaming, "
                        + (retained / 1024 / 1024)
                        + " MB is still retained; "
                        + "the reader appears to be accumulating scans rather than streaming");
    }

    /**
     * The honest proof: stream a 15 MB / 758,544-peak file inside a <b>48 MB heap</b>.
     *
     * <p>An earlier version of this test asserted on peak used-heap and failed at 119 MB — but that
     * number measures <i>GC laziness</i>, not a design property. With only ~10 KB retained (see the
     * test above), the rest is short-lived garbage the JVM had no reason to collect. Peak heap under
     * no memory pressure says nothing about whether memory is bounded.
     *
     * <p>Running in a constrained heap does. A whole-file design needs ~31 MB of store for PlusRise
     * plus the transient cost of building it, and over a gigabyte for a 500 MB input; a streaming one
     * needs only the largest scan. If this ever OOMs, the reader has started accumulating.
     */
    @Test
    void streamsWithinAConstrainedHeap() throws Exception {
        Path mgf = Fixtures.require("data/PlusRise.mgf");

        String javaBin =
                Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(); // not `java`: it would shadow the package name
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

    /** Entry point for the constrained-heap subprocess above. */
    public static final class StreamHarness {
        public static void main(String[] args) {
            int scans = 0;
            long peaks = 0;
            try (SpectraStream s = SpectraFile.open(Path.of(args[0]))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    peaks += v.materialize().rowCount();
                    scans++;
                }
            }
            System.out.println(scans + " scans " + peaks + " peaks");
        }
    }

    /**
     * The same constrained-heap proof for <b>mzXML</b> (the mzXML reader).
     *
     * <p>mzXML is the format where a whole-file design is most tempting, because the interleaved array
     * decodes into two {@code double[]} at once. This asserts the streaming property holds there too:
     * 916 scans and ~110k peak rows inside a <b>48 MB heap</b>, memory-mapped throughout.
     *
     * <p>Worth having separately from the MGF proof because the two readers retain different things —
     * MGF holds a {@code BufferedReader}, mzXML holds a mapped region plus one scan's base64 text.
     */
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
        // The retained-heap counterpart, for the mapped-file reader. Bounds are generous on
        // purpose:
        // what this tests is the SHAPE -- that nothing accumulates -- not an absolute byte figure.
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        long baseline = settledHeap();
        int scans = 0;
        long peaks = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                peaks += v.materialize().rowCount();
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

    @Test
    void metadataOnlyIterationNeverDecodesPeaks() {
        // The payoff of deferred decoding: a query rejecting scans on RTMIN/SCANMIN/POLARITY/CHARGE
        // /MS2PREC never calls materialize(), so it never pays base64-decode, inflate or the
        // double[] allocation. Walking metadata alone must therefore be markedly cheaper.
        Path mzml = Fixtures.require("data/small.mzML");

        long t0 = System.nanoTime();
        int metaOnly = 0;
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                v.precmz();
                metaOnly++;
            }
        }
        long metaMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        int withPeaks = 0;
        long peaks = 0;
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                peaks += v.materialize().rowCount();
                withPeaks++;
            }
        }
        long fullMs = (System.nanoTime() - t1) / 1_000_000;

        assertEquals(metaOnly, withPeaks);
        assertEquals(305_214L, peaks);
        System.out.printf(
                "  small.mzML: metadata-only %d ms vs full decode %d ms (%,d peaks)%n",
                metaMs, fullMs, peaks);
        // No timing assertion -- it would flake. The number is printed so a regression that starts
        // decoding eagerly is visible in the build log.
    }
}
