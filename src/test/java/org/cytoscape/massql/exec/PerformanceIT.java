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

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerformanceIT {
    private static final long CEILING_MILLIS = 120_000;

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

        System.out.println(sb);
    }

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

    private static Path resource(String relative) {
        URL url = PerformanceIT.class.getClassLoader().getResource(relative);
        if (url == null) {
            throw new AssertionError(
                    "fixture missing from src/test/resources: "
                            + relative
                            + " -- every fixture is committed in-repo. Restore it; never skip.");
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
