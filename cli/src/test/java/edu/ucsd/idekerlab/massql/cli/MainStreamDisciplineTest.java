package edu.ucsd.idekerlab.massql.cli;

import static edu.ucsd.idekerlab.massql.cli.CliFixtures.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The payload-shape half of stream hygiene: stdout carries the JSON array and nothing else.
 *
 * <p>⚠ <b>This is a complement to Tech_Step12 §3(b), not a delegation from it</b> (Correction C42).
 * What is asserted here is the <i>shape</i> of what each stream receives, which an in-process
 * capture can establish perfectly. What it cannot establish is that real file descriptors keep the
 * two apart under a real fork — an in-process test would pass even if the CLI wrote both streams to
 * the same place, because it is handed two distinct objects by construction. Step 12 forks a
 * subprocess for exactly that reason.
 *
 * <p>Both streams are captured through {@code Main.run}'s {@code PrintStream} parameters.
 * <b>Never {@code System.setOut}</b>: that is global mutable state, and a test that mutates it makes
 * every other test in the JVM order-dependent — including ones in other classes that never asked to
 * be involved.
 */
class MainStreamDisciplineTest {

    @TempDir Path dir;

    @Test
    void stdoutIsExactlyTheJsonArrayAndATrailingNewline() {
        CliFixtures.Invocation r =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString());

        assertEquals(0, r.exitCode(), r.stderr());
        String out = r.stdout();
        assertTrue(out.endsWith(System.lineSeparator()), "a console payload ends with a newline");

        String body = out.strip();
        assertTrue(body.startsWith("["), "stdout begins with the array: " + preview(body));
        assertTrue(body.endsWith("]"), "and ends with it: " + preview(body));

        // Nothing before or after the array. A single stray progress line here would corrupt the
        // payload for every consumer piping to jq -- the exact failure C24's JavolutionQuiet fix
        // and
        // StdoutCleanlinessTest exist to prevent, now asserted at the CLI layer too.
        assertEquals(0, body.indexOf('['), "no preamble before the JSON");
        assertEquals(body.length() - 1, body.lastIndexOf(']'), "no epilogue after the JSON");

        // And the whole thing is one document: exactly one newline, at the very end.
        assertEquals(
                1,
                out.lines().count(),
                "the payload is a single line plus its terminator: " + preview(out));
    }

    @Test
    void stdoutStaysParseableEvenWhenTheRunHasSomethingToSay() {
        // The point of routing diagnostics to stderr: whatever the engine wants to report, the data
        // channel stays machine-readable.
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.microMzml().toString(),
                        CliFixtures.emptyResultQuery().toString());

        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals("[]", r.stdout().strip(), "stdout is still valid JSON, with or without notes");
    }

    @Test
    void everyErrorPathLeavesStdoutCompletelyEmpty() {
        // If ANY failure mode writes to stdout, a consumer reading the pipe gets a message where it
        // expected JSON. Each case below is a different route to a non-zero exit.
        Path junk = CliFixtures.write(dir, "junk.mzML", "<notSpectra/>\n");
        Path unsupported =
                CliFixtures.write(dir, "u.massql", "QUERY scaninfo(MS2DATA) WHERE MOBILITY=1\n");
        Path emptyQuery = CliFixtures.write(dir, "empty.massql", "");

        List<CliFixtures.Invocation> failures =
                List.of(
                        invoke(),
                        invoke("only-one-arg"),
                        invoke(
                                dir.resolve("missing.mzML").toString(),
                                CliFixtures.standardQuery().toString()),
                        invoke(CliFixtures.smallMzml().toString(), emptyQuery.toString()),
                        invoke(CliFixtures.smallMzml().toString(), unsupported.toString()),
                        invoke(junk.toString(), CliFixtures.standardQuery().toString()),
                        invoke(
                                CliFixtures.smallMzml().toString(),
                                CliFixtures.standardQuery().toString(),
                                "--precursor-tol-ppm",
                                "nonsense"));

        for (CliFixtures.Invocation r : failures) {
            assertTrue(r.exitCode() != 0, "this case was supposed to fail");
            assertTrue(r.stdoutIsEmpty(), "an error path wrote to stdout: " + preview(r.stdout()));
            assertTrue(!r.stderr().isEmpty(), "and it must say why, on stderr");
        }
    }

    @Test
    void diagnosticsAndErrorsGoToStderrOnEveryOutputMode() {
        // Including --output mode: routing diagnostics into the FILE would corrupt the result
        // document, and dropping them entirely would lose the SDK's only reporting channel.
        Path target = dir.resolve("result.json");
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "--output",
                        target.toString());

        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), "--output mode writes nothing to stdout");

        // The file holds the JSON document and nothing else -- no diagnostics leaked into it.
        String written = readString(target).strip();
        assertEquals(0, written.indexOf('['), "the output file starts with the array");
        assertEquals(written.length() - 1, written.lastIndexOf(']'), "and ends with it");
    }

    private static String readString(Path p) {
        try {
            return java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String preview(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
