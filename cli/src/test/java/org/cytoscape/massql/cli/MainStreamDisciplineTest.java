package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainStreamDisciplineTest {
    @TempDir Path dir;

    @Test
    void stdoutIsExactlyTheJsonPayloadAndATrailingNewline() {
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "--pretty",
                        "false");

        assertEquals(0, r.exitCode(), r.stderr());
        String out = r.stdout();
        assertTrue(out.endsWith(System.lineSeparator()), "a console payload ends with a newline");

        String body = out.strip();
        assertFalse(parse(body).results().isEmpty(), "stdout carries the rows: " + preview(body));
        assertTrue(body.endsWith("}"), "and ends with the object: " + preview(body));

        assertEquals(0, body.indexOf('{'), "no preamble before the JSON");
        assertEquals(body.length() - 1, body.lastIndexOf('}'), "no epilogue after the JSON");

        assertEquals(
                1,
                out.lines().count(),
                "the payload is a single line plus its terminator: " + preview(out));
    }

    @Test
    void stdoutStaysParseableEvenWhenTheRunHasSomethingToSay() {
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.microMzml().toString(),
                        CliFixtures.emptyResultQuery().toString());

        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(
                parse(r.stdout()).results().isEmpty(),
                "stdout is still valid JSON, with or without notes");
    }

    @Test
    void everyErrorPathLeavesStdoutCompletelyEmpty() {
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
        Path target = dir.resolve("result.json");
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "--output",
                        target.toString());

        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), "--output mode writes nothing to stdout");

        String written = readString(target).strip();
        assertEquals(0, written.indexOf('{'), "the output file starts with the object");
        assertEquals(written.length() - 1, written.lastIndexOf('}'), "and ends with it");
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
