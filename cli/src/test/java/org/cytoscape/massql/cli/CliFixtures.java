package org.cytoscape.massql.cli;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cytoscape.massql.result.ResultJson;

import com.google.gson.Gson;

/**
 * Fixture lookup and stream capture for the CLI tests.
 *
 * <p>Resolves through the classpath, the same way the SDK's {@code Fixtures} does, against the
 * spectra files and {@code .massql} queries the goldens were generated from — {@code cli}'s test
 * runtime classpath carries the SDK's test <i>resources</i> (see {@code cli/build.gradle}). Using
 * the real fixtures rather than synthetic ones is what makes {@code MainOutputFileTest}'s
 * byte-identity check meaningful: it compares two renderings of an actual 6-row result.
 *
 * <p><b>A missing fixture is a hard failure, never a skip</b>. There is no skip
 * path here, and adding one would recreate the failure where CI was green for four steps while
 * proving only that the code compiled.
 */
final class CliFixtures {

    private CliFixtures() {}

    /** {@code data/small.mzML} — 6 rows for the standard query, matching the committed golden. */
    static Path smallMzml() {
        return require("data/small.mzML");
    }

    /** {@code fixtures/micro/micro.mzML} — tiny, for tests that only need a valid file. */
    static Path microMzml() {
        return require("fixtures/micro/micro.mzML");
    }

    /** The query behind {@code small_mzml_results.json}. */
    static Path standardQuery() {
        return require("goldens/queries/test_mzml.massql");
    }

    /** A query whose strict window matches nothing — the `[]` + exit 0 case. */
    static Path emptyResultQuery() {
        return require("goldens/queries/test_micro_edge.massql");
    }

    static Path require(String relative) {
        URL url = CliFixtures.class.getClassLoader().getResource(relative);
        assertNotNull(
                url,
                "fixture missing from the CLI test classpath: "
                        + relative
                        + "\nThe cli project gets the SDK's test resources via testRuntimeOnly in "
                        + "cli/build.gradle; if that wiring was removed, restore it rather than "
                        + "making this test conditional.");
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("fixture URL is not a usable file path: " + url, e);
        }
    }

    /** Writes {@code content} to a file in {@code dir} and returns it. */
    static Path write(Path dir, String name, String content) {
        try {
            Path p = dir.resolve(name);
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One invocation's captured result.
     *
     * <p>Streams are captured through {@code Main.run}'s {@code PrintStream} parameters, <b>never</b>
     * {@code System.setOut} — that is global mutable state, and a test that mutates it makes every
     * other test in the JVM order-dependent.
     */
    record Invocation(int exitCode, String stdout, String stderr) {

        boolean stdoutIsEmpty() {
            return stdout.isEmpty();
        }
    }

    /**
     * Runs the CLI in-process with <b>empty</b> stdin and captures both streams.
     *
     * <p>Empty rather than {@code System.in}: a test that accidentally selected the stdin source would
     * otherwise block on the terminal forever instead of failing.
     */

    /** The CLI's payload, deserialized. Tests assert on the object graph, not on the text. */
    static ResultJson parse(String json) {
        ResultJson r = new Gson().fromJson(json, ResultJson.class);
        assertNotNull(r, "payload did not deserialize: " + json);
        return r;
    }

    static Invocation invoke(String... args) {
        return invokeWithStdin("", args);
    }

    /** Runs the CLI in-process with {@code stdin} as its standard input. */
    static Invocation invokeWithStdin(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        int code;
        try (PrintStream o = new PrintStream(out, true, StandardCharsets.UTF_8);
                PrintStream e = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = Main.run(args, in, o, e);
        }
        return new Invocation(
                code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
