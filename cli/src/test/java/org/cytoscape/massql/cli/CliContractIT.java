package org.cytoscape.massql.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class CliContractIT {
    private static final Path CLI_JAR = resolveCliJar();

    private static Path resolveCliJar() {
        String p = System.getProperty("cliJar");
        assertNotNull(
                p,
                "system property 'cliJar' is not set -- cli/build.gradle's integrationTest task must"
                        + " pass the resolved shadowJar path. Do NOT rebuild it from a version string:"
                        + " cliVersion is a Gradle property, not a JVM one, and yields"
                        + " massql-java-cli-null.jar.");
        Path jar = Path.of(p);
        assertTrue(
                Files.isRegularFile(jar),
                "the CLI uber-jar is missing: "
                        + jar
                        + " -- the suite declares dependsOn shadowJar, so"
                        + " this should be impossible. Never skip on it.");
        return jar;
    }

    private record Run(int exit, byte[] stdout, byte[] stderr) {
        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String err() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }

    private static Run fork(Object... args) {
        return forkWithStdin(null, args);
    }

    private static Run forkWithStdin(String stdin, Object... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-jar");
        cmd.add(CLI_JAR.toString());
        for (Object a : args) cmd.add(a.toString());

        try {
            Process p = new ProcessBuilder(cmd).start();

            try (java.io.OutputStream childIn = p.getOutputStream()) {
                if (stdin != null) childIn.write(stdin.getBytes(StandardCharsets.UTF_8));
            }

            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            if (!p.waitFor(5, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new AssertionError("the CLI did not exit within 5 minutes: " + cmd);
            }
            return new Run(p.exitValue(), out, err);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while forking the CLI", e);
        }
    }

    private static JsonArray parseArray(String json, String what) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new AssertionError(what + " is not valid JSON: " + abbreviate(json), e);
        }
        assertTrue(root.isJsonObject(), what + " must be a JSON object, got: " + abbreviate(json));
        JsonElement rows = root.getAsJsonObject().get("results");
        assertNotNull(rows, what + " has no 'results': " + abbreviate(json));
        assertTrue(rows.isJsonArray(), what + " 'results' must be an array: " + abbreviate(json));
        return rows.getAsJsonArray();
    }

    private static String abbreviate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "… (" + s.length() + " chars)";
    }

    private static int nullCount(JsonArray rows, String column) {
        int n = 0;
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            assertTrue(o.has(column), "row is missing '" + column + "': " + o);
            if (o.get(column).isJsonNull()) n++;
        }
        return n;
    }

    private static void assertRowsMatchStandardQuery(JsonArray rows) {
        int previous = 0;
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            assertEquals(
                    2, o.get("mslevel").getAsInt(), () -> "MS2DATA selected a non-MS2 scan: " + o);

            double precmz = o.get("precmz").getAsDouble();
            assertTrue(
                    Math.abs(precmz - 810.79) <= 1.0,
                    () -> "precmz " + precmz + " lies outside TOLERANCEMZ=1.0 of 810.79: " + o);

            int scan = o.get("scan").getAsInt();
            int before = previous;
            assertTrue(
                    scan > before, () -> "scan ids must ascend: " + scan + " followed " + before);
            previous = scan;
        }
    }

    @Test
    void anAbsurdlyTightToleranceNullsEveryLookupButNotTheBasePeak(@TempDir Path dir) {
        Path out = dir.resolve("tight.json");
        Run r =
                fork(
                        CliFixtures.smallMzml(),
                        CliFixtures.standardQuery(),
                        "--precursor-tol-ppm",
                        "0.001",
                        "--output",
                        out);

        assertEquals(0, r.exit(), () -> "exit " + r.exit() + ", stderr: " + r.err());

        JsonArray rows = parseArray(readString(out), "the --output payload at 0.001 ppm");
        assertRowsMatchStandardQuery(rows);
        assertEquals(
                6,
                rows.size(),
                "the query still matches 6 scans -- tolerance affects the lookup,"
                        + " not which scans qualify");
        assertEquals(6, nullCount(rows, "ms1_i"), "every ms1_i must be null at 0.001 ppm");
        assertEquals(
                6, nullCount(rows, "ms1_precmz"), "every ms1_precmz must be null at 0.001 ppm");
        assertEquals(
                0,
                nullCount(rows, "ms1_base_peak_i"),
                "⛔ ms1_base_peak_i SURVIVES a tolerance miss -- it is the MS1 scan's base peak, not a"
                        + " matched precursor peak (collation). A null here means the lookup and the"
                        + " scan-level base peak have been conflated.");
    }

    @Test
    void thePrecursorToleranceFlagIsHonouredInBothDirections(@TempDir Path dir) {
        Path def = dir.resolve("default.json");
        Run atDefault = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", def);
        assertEquals(0, atDefault.exit(), () -> atDefault.err());

        JsonArray rows20 = parseArray(readString(def), "the default-tolerance payload");
        assertRowsMatchStandardQuery(rows20);
        assertEquals(6, rows20.size());
        assertEquals(
                4, nullCount(rows20, "ms1_i"), "small_mzml_results.json: 4 of 6 miss at 20 ppm");
        assertEquals(4, nullCount(rows20, "ms1_precmz"), "and ms1_precmz misses with it");
        assertEquals(0, nullCount(rows20, "ms1_base_peak_i"), "base peak survives all four misses");

        Path wide = dir.resolve("tol60.json");
        Run at60 =
                fork(
                        CliFixtures.smallMzml(),
                        CliFixtures.standardQuery(),
                        "--precursor-tol-ppm",
                        "60",
                        "--output",
                        wide);
        assertEquals(0, at60.exit(), () -> at60.err());

        JsonArray rows60 = parseArray(readString(wide), "the 60 ppm payload");
        assertRowsMatchStandardQuery(rows60);
        assertEquals(6, rows60.size());
        assertEquals(
                0,
                nullCount(rows60, "ms1_i"),
                "small_mzml_tol60_results.json: all 6 populate at 60 ppm");
        assertEquals(0, nullCount(rows60, "ms1_precmz"));
    }

    @Test
    void theDefaultToleranceIsTwentyPpm(@TempDir Path dir) {
        Path implicit = dir.resolve("implicit.json");
        Path explicit = dir.resolve("explicit.json");

        fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", implicit);
        fork(
                CliFixtures.smallMzml(),
                CliFixtures.standardQuery(),
                "--precursor-tol-ppm",
                "20",
                "--output",
                explicit);

        assertArrayEquals(
                readBytes(implicit),
                readBytes(explicit),
                "omitting --precursor-tol-ppm must be identical to passing 20 -- the usage text"
                        + " promises '(default 20.0)'");
    }

    @Test
    void aQueryPipedIntoStdinIsEquivalentToTheFileForm(@TempDir Path dir) {
        String query = readString(CliFixtures.standardQuery());
        assertFalse(query.isBlank(), "the query fixture must have content, or this proves nothing");

        Path viaFile = dir.resolve("file.json");
        fork(
                CliFixtures.smallMzml(),
                CliFixtures.standardQuery(),
                "--pretty",
                "false",
                "--output",
                viaFile);

        Run piped = forkWithStdin(query, CliFixtures.smallMzml(), "-", "--pretty", "false");

        assertEquals(0, piped.exit(), () -> "piped query failed: " + piped.err());
        JsonArray pipedRows = parseArray(piped.out(), "the piped-query payload");
        assertEquals(6, pipedRows.size());
        assertRowsMatchStandardQuery(pipedRows);
        assertArrayEquals(
                readBytes(viaFile),
                piped.stdout(),
                "a query on stdin must produce exactly what the same query in a file produces");
    }

    @Test
    void anInlineQueryWorksThroughTheAssembledJar() {
        Run r =
                fork(
                        CliFixtures.smallMzml(),
                        "-q",
                        "QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0",
                        "--pretty",
                        "false");

        assertEquals(0, r.exit(), () -> r.err());
        JsonArray inlineRows = parseArray(r.out(), "the --query payload");
        assertEquals(6, inlineRows.size());
        assertRowsMatchStandardQuery(inlineRows);
    }

    @Test
    void aQueryMatchingNothingIsAnEmptyArrayAndExitZero(@TempDir Path dir) {
        Path out = dir.resolve("empty.json");
        Run r = fork(CliFixtures.microMzml(), CliFixtures.emptyResultQuery(), "--output", out);

        assertEquals(0, r.exit(), () -> "no matches is success, not failure. stderr: " + r.err());
        assertEquals(0, parseArray(readString(out), "the empty-result payload").size());
    }

    @Test
    void stdoutIsJsonOnlyAndDiagnosticsGoToStderr() {
        Run r = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--pretty", "false");
        assertEquals(0, r.exit(), () -> r.err());

        JsonArray rows = parseArray(r.out(), "piped stdout");
        assertRowsMatchStandardQuery(rows);
        assertEquals(6, rows.size());

        String out = r.out();
        assertFalse(out.contains("Exception"), () -> "a stack frame reached stdout:\n" + out);
        assertFalse(out.contains("\tat "), () -> "a stack frame reached stdout:\n" + out);
        assertFalse(
                out.toLowerCase(Locale.ROOT).contains("usage:"),
                () -> "usage text reached stdout on a SUCCESSFUL run:\n" + out);
    }

    @Test
    void thePipedPayloadEndsWithExactlyOneTrailingNewline() {
        Run r = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());
        String out = r.out();

        assertTrue(
                out.endsWith("\n"), "the payload must end with a newline, as the reference does");
        assertFalse(
                out.endsWith("\n\n"),
                "one trailing newline, not two -- a blank line is a diff against the reference");
    }

    @Test
    void outputToFileLeavesStdoutCompletelyEmpty(@TempDir Path dir) {
        Path out = dir.resolve("results.json");
        Run r = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", out);

        assertEquals(0, r.exit(), () -> r.err());
        assertEquals(
                0,
                r.stdout().length,
                () ->
                        "--output FILE must leave stdout empty, got "
                                + r.stdout().length
                                + " byte(s): "
                                + abbreviate(r.out()));
        assertTrue(Files.isRegularFile(out), "and the file must hold the array");
        JsonArray fileRows = parseArray(readString(out), "the --output file");
        assertEquals(6, fileRows.size());
        assertRowsMatchStandardQuery(fileRows);
    }

    @Test
    void thePipedAndFileModesAgreeByteForByte(@TempDir Path dir) {
        Path file = dir.resolve("results.json");
        fork(
                CliFixtures.smallMzml(),
                CliFixtures.standardQuery(),
                "--pretty",
                "false",
                "--output",
                file);
        Run piped = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--pretty", "false");

        assertArrayEquals(
                readBytes(file),
                piped.stdout(),
                "--output and stdout must be the same bytes -- '-' is documented as meaning stdout, so"
                        + " they are two destinations for one rendering, not two renderings");
    }

    @Test
    void outputDashMeansStdout() {
        Run dash = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", "-");
        Run omitted = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());

        assertEquals(0, dash.exit(), () -> dash.err());
        assertArrayEquals(
                omitted.stdout(), dash.stdout(), "--output - is stdout, per the usage text");
    }

    @Test
    void aMissingSpectraFileExitsTwoAndSaysSoOnStderrOnly(@TempDir Path dir) {
        Path missing = dir.resolve("nope.mzML");
        Run r = fork(missing, CliFixtures.standardQuery());

        assertEquals(2, r.exit(), () -> "missing input is a usage error. stderr: " + r.err());
        assertEquals(0, r.stdout().length, () -> "stdout must stay empty: " + abbreviate(r.out()));
        assertTrue(
                r.err().contains("nope.mzML"),
                () -> "the error must name the path it could not read:\n" + r.err());
    }

    @Test
    void anUnsupportedQueryNamesTheConstruct(@TempDir Path dir) {
        Path q = CliFixtures.write(dir, "scansum.massql", "QUERY scansum(MS2DATA)");
        Run r = fork(CliFixtures.microMzml(), q);

        assertEquals(
                2, r.exit(), () -> "an unparseable query is a usage error. stderr: " + r.err());
        assertEquals(0, r.stdout().length, () -> "stdout must stay empty: " + abbreviate(r.out()));
        assertTrue(
                r.err().contains("scansum"),
                () -> "the message must name the construct it rejected:\n" + r.err());
    }

    @Test
    void helpGoesToStdoutAndExitsZero() {
        Run r = fork("--help");

        assertEquals(
                0, r.exit(), () -> "--help was asked for, so it succeeded. stderr: " + r.err());
        assertTrue(
                r.out().contains("Usage:"), () -> "usage text belongs on stdout here:\n" + r.out());
        assertEquals(
                0,
                r.stderr().length,
                () -> "nothing on stderr for a deliberate --help: " + r.err());
    }

    @Test
    void prettyStdoutIsIndentedThroughTheRealJar() {
        Run r = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());
        assertEquals(0, r.exit(), () -> r.err());

        String out = r.out();
        assertTrue(
                out.lines().count() >= 2,
                () -> "indented output is multi-line: " + abbreviate(out));
        assertTrue(out.startsWith("{\n"), () -> abbreviate(out));
        assertTrue(out.contains("\"scan\": "), () -> "keys are indented: " + abbreviate(out));
        assertRowsMatchStandardQuery(parseArray(out, "the pretty payload"));
    }

    @Test
    void prettyToAFileMatchesStdoutByteForByte(@TempDir Path dir) {
        Path out = dir.resolve("pretty.json");
        Run toFile = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", out);
        assertEquals(0, toFile.exit(), () -> toFile.err());
        assertEquals(
                0, toFile.stdout().length, () -> "--output keeps stdout empty: " + toFile.out());

        Run toStdout = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());
        assertEquals(
                toStdout.out().strip(), readString(out).strip(), "one render, two destinations");
        assertRowsMatchStandardQuery(parseArray(readString(out), "the --output payload"));
    }

    @Test
    void prettyFalseIsASingleLineWithTheSameRows() {
        Run compact =
                fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--pretty", "false");
        assertEquals(0, compact.exit(), () -> compact.err());
        assertEquals(
                1,
                compact.out().stripTrailing().lines().count(),
                () -> "compact is one line: " + abbreviate(compact.out()));

        Run pretty = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());
        assertEquals(
                parseArray(pretty.out(), "the pretty payload"),
                parseArray(compact.out(), "the compact payload"),
                "the two renderings must parse to identical values");
    }

    private static String readString(Path p) {
        return new String(readBytes(p), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
