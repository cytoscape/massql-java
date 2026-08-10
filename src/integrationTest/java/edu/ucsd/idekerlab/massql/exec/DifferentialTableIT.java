package edu.ucsd.idekerlab.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The review artifact can go red — {@code scripts/differential-table.sh} is not decoration.
 *
 * <h2>Why this tests the script and not `make verify`</h2>
 *
 * <p>⛔ It must <b>never</b> invoke {@code make verify}. {@code check} depends on
 * {@code integrationTest}, so an IT that ran it would re-enter itself forever. Guarding the script
 * alone loses nothing: the script is the only part of {@code make verify} this step adds, and the
 * rest of the pipeline is already gated by its own tests.
 *
 * <p>Every input here is <b>synthesized into a temp directory</b>, never a committed sample. A
 * checked-in XML snapshot would have to be regenerated whenever {@code DifferentialIT} changed —
 * precisely the brittleness that made rendering the table from a live run the right choice in the
 * first place.
 *
 * <p>The three cases are the three ways a review artifact lies: it says green when a pair failed, it
 * says green having parsed nothing, and it says green having parsed only some of the pairs.
 */
class DifferentialTableIT {

    /** The 16 pairs Tech_Step12 §1 asserts. Names must match real goldens: the script reads them. */
    private static final List<String> GOLDENS =
            List.of(
                    "small_mzml_results",
                    "small_mzml_tol60_results",
                    "small_mzxml_results",
                    "small_mzxml_tol60_results",
                    "small_mzml_ms1_results",
                    "plusrise_results",
                    "dp00570_mzxml_results",
                    "dp00570_mgf_results",
                    "dp00570_mzxml_empty_results",
                    "micro_mgf_results",
                    "micro_mzml_results",
                    "micro_mzxml_results",
                    "micro_mzml_rtseconds_results",
                    "micro_mzml_edge_results",
                    "micro_ms1var_results",
                    "micro_onbound_results");

    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))) return dir;
        }
        throw new AssertionError("repo root not found from " + Paths.get("").toAbsolutePath());
    }

    /** Writes a JUnit XML for {@code DifferentialIT} naming each golden; one may carry a failure. */
    private static Path writeResults(Path dir, List<String> goldens, String failing) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<testsuite name=\"edu.ucsd.idekerlab.massql.exec.DifferentialIT\" tests=\"")
                .append(goldens.size())
                .append("\">\n");
        for (String g : goldens) {
            if (g.equals(failing)) {
                sb.append("  <testcase name=\"").append(g).append("\" classname=\"x\">\n");
                sb.append("    <failure message=\"injected\">row 0: tic mismatch</failure>\n");
                sb.append("  </testcase>\n");
            } else {
                sb.append("  <testcase name=\"").append(g).append("\" classname=\"x\"/>\n");
            }
        }
        sb.append("</testsuite>\n");

        Path f = dir.resolve("TEST-edu.ucsd.idekerlab.massql.exec.DifferentialIT.xml");
        try {
            Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return f;
    }

    private record Run(int exit, String output) {}

    private static Run runTable(Path resultsDir) {
        Path root = repoRoot();
        try {
            Process p =
                    new ProcessBuilder(
                                    "bash",
                                    root.resolve("scripts/differential-table.sh").toString(),
                                    resultsDir.toString())
                            .directory(root.toFile())
                            .redirectErrorStream(true)
                            .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new AssertionError("differential-table.sh did not finish");
            }
            return new Run(p.exitValue(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }

    @Test
    void anAllPassingRunRendersTheTableAndExitsZero(@TempDir Path dir) {
        writeResults(dir, GOLDENS, null);
        Run r = runTable(dir);

        assertEquals(0, r.exit(), () -> "a fully passing run must exit 0:\n" + r.output());
        assertTrue(r.output().contains("VERDICT: GREEN"), r.output());
        assertTrue(r.output().contains("16 pairs compared"), r.output());
        // The population rules are visible in the table itself, not only in prose.
        assertTrue(
                r.output().contains("n/a"),
                () -> "n/a must appear for MGF's MS1 columns:\n" + r.output());
    }

    /** ⛔ The assertion this class exists for: one failed pair must turn the artifact red. */
    @Test
    void oneFailedPairMakesTheTableExitNonZero(@TempDir Path dir) {
        writeResults(dir, GOLDENS, "plusrise_results");
        Run r = runTable(dir);

        assertNotEquals(0, r.exit(), () -> "a failed pair must exit non-zero:\n" + r.output());
        assertTrue(r.output().contains("FAIL"), r.output());
        assertTrue(
                r.output().contains("PAIR(S) FAILED"),
                () -> "the summary must say what happened:\n" + r.output());
        assertTrue(
                !r.output().contains("VERDICT: GREEN"),
                () -> "a run with a failure must not also claim green:\n" + r.output());
    }

    /**
     * A short parse fails rather than printing fewer rows.
     *
     * <p>This is the failure mode that matters most: a renamed test or moved directory silently
     * yielding a table of twelve confident rows reads as a clean bill of health for sixteen pairs.
     */
    @Test
    void aShortParseFailsRatherThanPrintingAPartialTable(@TempDir Path dir) {
        writeResults(dir, GOLDENS.subList(0, 12), null);
        Run r = runTable(dir);

        assertNotEquals(
                0, r.exit(), () -> "12 of 16 pairs must fail, not print 12 rows:\n" + r.output());
        assertTrue(
                r.output().contains("expected at least 16"),
                () -> "the message must say how many were expected:\n" + r.output());
    }

    /** No XML at all — the empty parse. Must name the cause rather than print an empty table. */
    @Test
    void aMissingResultsFileFailsAndExplainsWhy(@TempDir Path dir) {
        Run r = runTable(dir);

        assertNotEquals(0, r.exit(), () -> "a missing results file must fail:\n" + r.output());
        assertTrue(
                r.output().contains("not found"),
                () -> "the message must name the missing file:\n" + r.output());
        assertTrue(
                r.output().contains("make verify"),
                () -> "and say how to produce it:\n" + r.output());
    }
}
