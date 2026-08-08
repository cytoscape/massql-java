package edu.ucsd.idekerlab.massql.cli;

import static edu.ucsd.idekerlab.massql.cli.CliFixtures.invoke;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No failure mode prints a Java stack trace to stdout.
 *
 * <p>Separate from {@code MainStreamDisciplineTest} because the failure is different in kind. That
 * class asserts stdout is <b>empty</b> on error paths; this one asserts that <i>if</i> anything ever
 * does reach stdout, it is not a stack trace — which is the specific shape that silently corrupts a
 * JSON payload while looking, in a terminal, like the tool merely complained.
 *
 * <p>It is also the harder failure to notice: the happy path is unaffected, so a stack trace on
 * stdout survives every test that only exercises success, and only shows up when a downstream
 * consumer fails to parse something it was told was JSON.
 *
 * <p>Stack traces on <b>stderr</b> are a separate question and are not asserted against here — but
 * the messages this CLI produces are deliberately plain text, so the check applies to both streams
 * and would catch a stray {@code printStackTrace()} wherever it landed.
 */
class MainNoStackTraceOnStdoutTest {

    @TempDir Path dir;

    /** Every distinct route to a non-zero exit, named so a failure says which one broke. */
    private Map<String, CliFixtures.Invocation> everyFailureMode() {
        Path junk = CliFixtures.write(dir, "junk.mzML", "<notSpectra><a/></notSpectra>\n");
        Path truncated =
                CliFixtures.write(
                        dir, "truncated.mzXML", "<mzXML><msRun scanCount=\"3\"><scan num=");
        Path unsupported =
                CliFixtures.write(dir, "u.massql", "QUERY scaninfo(MS2DATA) WHERE MOBILITY=1\n");
        Path syntax = CliFixtures.write(dir, "s.massql", "SELECT * FROM scans\n");
        Path emptyQuery = CliFixtures.write(dir, "e.massql", "   \n");
        Path occupied = dir.resolve("occupied");
        try {
            java.nio.file.Files.createDirectory(occupied);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        String spectra = CliFixtures.smallMzml().toString();
        String query = CliFixtures.standardQuery().toString();

        Map<String, CliFixtures.Invocation> modes = new LinkedHashMap<>();
        modes.put("no arguments", invoke());
        modes.put("one argument", invoke(spectra));
        modes.put("extra argument", invoke(spectra, query, "extra"));
        modes.put("unknown option", invoke(spectra, query, "--bogus"));
        modes.put("bad tolerance", invoke(spectra, query, "--precursor-tol-ppm", "xyz"));
        modes.put("missing spectra file", invoke(dir.resolve("gone.mzML").toString(), query));
        modes.put("empty query file", invoke(spectra, emptyQuery.toString()));
        modes.put("unsupported construct", invoke(spectra, unsupported.toString()));
        modes.put("syntax error", invoke(spectra, syntax.toString()));
        modes.put("unparseable content", invoke(junk.toString(), query));
        modes.put("truncated content", invoke(truncated.toString(), query));
        modes.put("unwritable --output", invoke(spectra, query, "--output", occupied.toString()));
        return modes;
    }

    @Test
    void noFailureModePutsAStackTraceOnStdout() {
        everyFailureMode()
                .forEach(
                        (name, r) -> {
                            assertTrue(r.exitCode() != 0, name + " was supposed to fail");
                            assertNoStackTrace(name + " (stdout)", r.stdout());
                        });
    }

    @Test
    void noFailureModePutsAStackTraceOnStderrEither() {
        // An unhandled exception reaching the top would print here. The CLI's job is to explain
        // what
        // went wrong in a sentence -- a 40-frame trace is not an explanation, and it buries the one
        // line that was.
        everyFailureMode().forEach((name, r) -> assertNoStackTrace(name + " (stderr)", r.stderr()));
    }

    @Test
    void everyFailureStillExplainsItself() {
        // The complement to the above: suppressing traces must not mean saying nothing. A silent
        // non-zero exit is its own kind of unhelpful.
        everyFailureMode()
                .forEach(
                        (name, r) ->
                                assertFalse(
                                        r.stderr().isBlank(),
                                        name + " failed without telling the user why"));
    }

    private static void assertNoStackTrace(String where, String text) {
        for (String line : text.split("\\R")) {
            assertFalse(
                    line.stripLeading().startsWith("at ") && line.contains("("),
                    where + " contains a stack frame: " + line);
        }
        assertFalse(
                text.contains("Exception") || text.contains("Throwable"),
                where + " names an exception type; report the problem, not the plumbing:\n" + text);
    }
}
