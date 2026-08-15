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

final class CliFixtures {
    private CliFixtures() {}

    static Path smallMzml() {
        return require("data/small.mzML");
    }

    static Path microMzml() {
        return require("fixtures/micro/micro.mzML");
    }

    static Path standardQuery() {
        return require("goldens/queries/test_mzml.massql");
    }

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

    static Path write(Path dir, String name, String content) {
        try {
            Path p = dir.resolve(name);
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    record Invocation(int exitCode, String stdout, String stderr) {
        boolean stdoutIsEmpty() {
            return stdout.isEmpty();
        }
    }

    static ResultJson parse(String json) {
        ResultJson r = new Gson().fromJson(json, ResultJson.class);
        assertNotNull(r, "payload did not deserialize: " + json);
        return r;
    }

    static Invocation invoke(String... args) {
        return invokeWithStdin("", args);
    }

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
