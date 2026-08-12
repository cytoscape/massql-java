package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.result.ScanInfoResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Wall-clock and peak heap per fixture — <b>recorded, not gated</b> (the differential).
 *
 * <h2>Why the assertions are deliberately loose</h2>
 *
 * <p>A timing threshold tight enough to be meaningful is also tight enough to fail on a loaded CI box,
 * and a test that fails for reasons unrelated to the code gets disabled — taking its real coverage with
 * it. So the only assertions here are absurdity ceilings: they catch an accidental quadratic, not a
 * regression of 20%. The <i>numbers</i> are the deliverable, written to the report fragment below.
 *
 * <h2>The host spec is captured, never transcribed</h2>
 *
 * <p>⚠ A machine spec typed into a review document by hand is a number that is wrong later. This class
 * writes {@code build/reports/performance/measurements.txt} with the processor count, heap ceiling, OS
 * and JVM it actually ran on, and the report quotes that file. Any cross-implementation comparison is a
 * dated historical datapoint from a different machine, and is labelled as one — the two are not
 * measured under the same conditions and must not be presented as if they were.
 *
 * <p>The MGF is the fixture that matters: if this is not at least as fast as the reference on it, something is
 * quadratic — probably a linear scan where a binary search belongs. If that ever fires, look at {@code mzWindowExclusive} first — it is the hotter of the two window
 * methods, being called per condition per scan.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceIT {

    /** No fixture in this suite is anywhere near this; it catches an accidental quadratic, nothing finer. */
    private static final long CEILING_MILLIS = 120_000;

    /** One fixture's measurement. */
    private record Measurement(
            String fixture, String query, int rows, long millis, long peakHeapBytes) {

        String line() {
            return String.format(
                    Locale.ROOT,
                    "%-28s %-18s %6d rows %8d ms %10.1f MB peak heap",
                    fixture,
                    query,
                    rows,
                    millis,
                    peakHeapBytes / (1024.0 * 1024.0));
        }
    }

    private final List<Measurement> measurements = new ArrayList<>();

    /**
     * ⛔ The one that matters — 34,513 spectra of MGF.
     *
     * <p>Run first and reported first, because it is the fixture whose timing carries information.
     */
    @Test
    void plusRiseMgfIsTheFixtureThatMatters() {
        measure("data/PlusRise.mgf", "test");
    }

    @Test
    void theEwingMzxml() {
        measure("data/DP00570_F02.mzxml", "test_dp00570");
    }

    @Test
    void theEwingMgf() {
        measure("data/DP00570_F02.mgf", "test_dp00570");
    }

    @Test
    void theConvertedPair() {
        measure("data/small.mzML", "test_mzml");
        measure("data/small.mzXML", "test_mzml");
    }

    /**
     * Times one run and records its peak heap.
     *
     * <p>Peak usage is reset immediately before the run, so the figure is this query's, not whatever the
     * suite happened to accumulate earlier. A GC is requested first for the same reason — it is a hint,
     * not a guarantee, which is another reason these numbers are reported rather than asserted on.
     */
    private void measure(String fixture, String query) {
        Path path = resource(fixture);
        String q = queryText(query);

        System.gc();
        resetPeakUsage();

        long start = System.nanoTime();
        List<ScanInfoResult> rows = Massql.run(q, path, null);
        long millis = (System.nanoTime() - start) / 1_000_000;

        Measurement m =
                new Measurement(basename(fixture), query, rows.size(), millis, peakHeapBytes());
        measurements.add(m);

        assertTrue(
                millis < CEILING_MILLIS,
                () ->
                        m.fixture()
                                + " took "
                                + millis
                                + " ms, over the "
                                + CEILING_MILLIS
                                + " ms"
                                + " absurdity ceiling. This is not a performance regression threshold -- at this"
                                + " magnitude, suspect an accidental quadratic. Check mzWindowExclusive first:"
                                + " it runs per condition per scan (the store).");
    }

    /** Writes the measurements and the host they were taken on. */
    @AfterAll
    void writeTheReportFragment() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();

        sb.append("MassQL-java performance measurements\n");
        sb.append("====================================\n\n");
        sb.append("Host (captured programmatically, not transcribed):\n");
        sb.append("  availableProcessors : ").append(rt.availableProcessors()).append('\n');
        sb.append("  maxMemory (heap)    : ")
                .append(
                        String.format(
                                Locale.ROOT, "%.1f GB", rt.maxMemory() / (1024.0 * 1024 * 1024)))
                .append('\n');
        sb.append("  os                  : ")
                .append(System.getProperty("os.name"))
                .append(' ')
                .append(System.getProperty("os.version"))
                .append(' ')
                .append(System.getProperty("os.arch"))
                .append('\n');
        sb.append("  jvm                 : ")
                .append(System.getProperty("java.vm.name"))
                .append(' ')
                .append(System.getProperty("java.version"))
                .append("\n\n");
        sb.append("Measurements:\n");
        for (Measurement m : measurements) {
            sb.append("  ").append(m.line()).append('\n');
        }
        sb.append(
                "\nWall-clock is a single in-process run per fixture, so it includes JIT warm-up.\n");
        sb.append("Peak heap is the JVM's own peak across heap pools, reset before each run.\n");

        Path out = Paths.get("build", "reports", "performance", "measurements.txt");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Tests may print; the SDK may not. This is what makes `make it` output usable
        // directly.
        System.out.println(sb);
    }

    // ------------------------------------------------------------------ measurement plumbing

    private static void resetPeakUsage() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        }
    }

    private static long peakHeapBytes() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) total += pool.getPeakUsage().getUsed();
        }
        return total;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // Reimplemented rather than reusing io/Fixtures, which is package-private to ...massql.io.
    // DifferentialIT and CollationAnchorIT hit the same wall; this is the documented shape of it.
    private static Path resource(String relative) {
        URL url = PerformanceIT.class.getClassLoader().getResource(relative);
        if (url == null) {
            throw new AssertionError(
                    "fixture missing from src/test/resources: "
                            + relative
                            + " -- fixtures are committed"
                            + " in-repo. Run `make fixtures` for the two Ewing files. Never skip.");
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("fixture URL is not a usable file path: " + url, e);
        }
    }

    private static String queryText(String name) {
        try {
            return Files.readString(resource("goldens/queries/" + name + ".massql")).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
