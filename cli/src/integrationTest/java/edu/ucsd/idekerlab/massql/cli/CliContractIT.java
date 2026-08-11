package edu.ucsd.idekerlab.massql.cli;

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

/**
 * Layer 4 — the CLI contract, established by forking the <b>assembled uber-jar</b> as a real process.
 *
 * <h2>Why a subprocess, when Step 11 already tests {@code Main.run} in-process</h2>
 *
 * <p>{@code Main.run(String[], PrintStream, PrintStream)} takes both streams as parameters, which is
 * what lets Step 11's {@code MainStreamDisciplineTest} capture them without {@code System.setOut}.
 * That design is also the reason an in-process test <b>cannot</b> prove the thing this class exists
 * for: passing two {@code ByteArrayOutputStream}s proves the code writes to the right
 * <i>parameter</i>, not that a real process keeps two real file descriptors apart. Only a fork can
 * establish that — and only against the shadow jar can it establish that the assembled artifact runs
 * at all.
 *
 * <p>⚠ <b>The two properties are tested separately</b>. An earlier design asserted
 * correctness by reading the payload off the pipe, which made a stream-hygiene regression present as a
 * data mismatch and vice versa. Here: correctness reads {@code --output FILE}, hygiene inspects the
 * pipes, and one test bridges them by proving the two modes emit identical bytes.
 *
 * <p>The jar path arrives as the {@code cliJar} system property, resolved by {@code cli/build.gradle}
 * from {@code shadowJar} itself. It is never reconstructed from a version string — {@code cliVersion}
 * is a Gradle property, and {@code System.getProperty("cliVersion")} is {@code null}.
 */
class CliContractIT {

    /** Resolved by the build from the shadowJar task, and {@code dependsOn} guarantees it exists. */
    private static final Path CLI_JAR = resolveCliJar();

    private static Path resolveCliJar() {
        String p = System.getProperty("cliJar");
        assertNotNull(
                p,
                "system property 'cliJar' is not set -- cli/build.gradle's integrationTest suite must"
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

    // ------------------------------------------------------------------ forking

    /** One completed subprocess run. */
    private record Run(int exit, byte[] stdout, byte[] stderr) {

        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String err() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }

    /**
     * Forks the uber-jar with {@code java.home}'s JVM — the one running this test, not whatever a bare
     * {@code java} would resolve to on {@code PATH}.
     */
    private static Run fork(Object... args) {
        return forkWithStdin(null, args);
    }

    /**
     * Forks with {@code stdin} written to the child's standard input, then closed.
     *
     * <p>Written before the pipes are drained, which is safe only because a query is small enough to
     * fit the pipe buffer; the payload coming back is the large direction and is read below.
     */
    private static Run forkWithStdin(String stdin, Object... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-jar");
        cmd.add(CLI_JAR.toString());
        for (Object a : args) cmd.add(a.toString());

        try {
            Process p = new ProcessBuilder(cmd).start();

            // Always close the child's stdin. Left open, an invocation that reads stdin would wait
            // for EOF forever -- which is correct CLI behaviour and a hung test.
            try (java.io.OutputStream childIn = p.getOutputStream()) {
                if (stdin != null) childIn.write(stdin.getBytes(StandardCharsets.UTF_8));
            }

            // Read both pipes fully BEFORE waitFor: a process that fills a pipe buffer blocks
            // forever otherwise, and this CLI can emit a multi-megabyte array.
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
        assertTrue(root.isJsonArray(), what + " must be a JSON array, got: " + abbreviate(json));
        return root.getAsJsonArray();
    }

    private static String abbreviate(String s) {
        return s.length() <= 400 ? s : s.substring(0, 400) + "… (" + s.length() + " chars)";
    }

    /** Counts rows whose {@code column} is JSON null. */
    private static int nullCount(JsonArray rows, String column) {
        int n = 0;
        for (JsonElement e : rows) {
            JsonObject o = e.getAsJsonObject();
            assertTrue(o.has(column), "row is missing '" + column + "': " + o);
            if (o.get(column).isJsonNull()) n++;
        }
        return n;
    }

    // ============================================ (a) functional correctness, read from --output

    /**
     * ⛔ The tightest tolerance case, and the one to read first.
     *
     * <p>At 0.001 ppm no MS1 peak can match, so every {@code ms1_i}/{@code ms1_precmz} is null — while
     * every {@code ms1_base_peak_i} <b>survives</b>, because the scan-level base peak is not a lookup
     * and does not depend on the tolerance at all (Step 10 §3.2).
     *
     * <p>This is the only place that rule is observable from <i>outside</i> the SDK, which is why the
     * spec asks for it explicitly. A CLI that silently ignored {@code --precursor-tol-ppm} would still
     * produce 6 rows here; only the null pattern reveals it.
     */
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
                        + " matched precursor peak (Step 10 §3.2). A null here means the lookup and the"
                        + " scan-level base peak have been conflated.");
    }

    /**
     * The flag is honoured in both directions, against the two real goldens.
     *
     * <p>Same file, same query, differing only in {@code --precursor-tol-ppm}: at the default 20 ppm
     * <b>4 of 6</b> rows miss, at 60 ppm <b>none</b> do. Asserting both directions is what makes this a
     * test of the flag rather than of one arbitrary number.
     *
     * <p>Row <i>values</i> are {@code DifferentialIT}'s job; what the CLI layer owns is that the flag
     * reaches the engine at all.
     */
    @Test
    void thePrecursorToleranceFlagIsHonouredInBothDirections(@TempDir Path dir) {
        Path def = dir.resolve("default.json");
        Run atDefault = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", def);
        assertEquals(0, atDefault.exit(), () -> atDefault.err());

        JsonArray rows20 = parseArray(readString(def), "the default-tolerance payload");
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
        assertEquals(6, rows60.size());
        assertEquals(
                0,
                nullCount(rows60, "ms1_i"),
                "small_mzml_tol60_results.json: all 6 populate at 60 ppm");
        assertEquals(0, nullCount(rows60, "ms1_precmz"));
    }

    /**
     * Omitting the flag is <b>exactly</b> {@code --precursor-tol-ppm 20}, proven byte-for-byte.
     *
     * <p>Comparing the two runs' bytes is a stronger statement than checking either against a golden:
     * it cannot pass because both drifted the same way.
     */
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

    /**
     * ⛔ A query arriving over a <b>real pipe</b>, which is the one thing only a fork can establish.
     *
     * <p>{@code MainQuerySourceTest} drives the {@code InputStream} parameter directly and covers every
     * rule about the three sources. What it cannot prove is that {@code main} actually hands
     * {@code System.in} to {@code run} — a one-line wiring mistake that would leave every in-process
     * test green while `cat q.massql | massql-java-cli …` hung or read nothing.
     *
     * <p>The result is compared byte-for-byte against the same query supplied as a file, so this asserts
     * the pipe is genuinely equivalent rather than merely non-empty.
     */
    @Test
    void aQueryPipedIntoStdinIsEquivalentToTheFileForm(@TempDir Path dir) {
        String query = readString(CliFixtures.standardQuery());
        assertFalse(query.isBlank(), "the query fixture must have content, or this proves nothing");

        Path viaFile = dir.resolve("file.json");
        fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", viaFile);

        Run piped = forkWithStdin(query, CliFixtures.smallMzml(), "-");

        assertEquals(0, piped.exit(), () -> "piped query failed: " + piped.err());
        assertEquals(6, parseArray(piped.out(), "the piped-query payload").size());
        assertArrayEquals(
                readBytes(viaFile),
                piped.stdout(),
                "a query on stdin must produce exactly what the same query in a file produces");
    }

    /** An inline {@code --query} survives the jar boundary too — quoting is the only risk here. */
    @Test
    void anInlineQueryWorksThroughTheAssembledJar() {
        Run r =
                fork(
                        CliFixtures.smallMzml(),
                        "-q",
                        "QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0");

        assertEquals(0, r.exit(), () -> r.err());
        assertEquals(6, parseArray(r.out(), "the --query payload").size());
    }

    /** A query that matches nothing is an empty array and exit <b>0</b> — not a crash, not exit 1. */
    @Test
    void aQueryMatchingNothingIsAnEmptyArrayAndExitZero(@TempDir Path dir) {
        Path out = dir.resolve("empty.json");
        Run r = fork(CliFixtures.microMzml(), CliFixtures.emptyResultQuery(), "--output", out);

        assertEquals(0, r.exit(), () -> "no matches is success, not failure. stderr: " + r.err());
        assertEquals(0, parseArray(readString(out), "the empty-result payload").size());
    }

    // =========================================== (b) stream hygiene, which only a fork can prove

    /**
     * stdout carries the JSON array and <b>nothing else</b>; diagnostics go to stderr.
     *
     * <p>The payload is parsed rather than pattern-matched: a diagnostic line mixed into stdout makes
     * the document invalid JSON, which is a sharper failure than any substring check. The explicit
     * checks below then rule out the subtler case of text that happens to parse.
     */
    @Test
    void stdoutIsJsonOnlyAndDiagnosticsGoToStderr() {
        Run r = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());
        assertEquals(0, r.exit(), () -> r.err());

        JsonArray rows = parseArray(r.out(), "piped stdout");
        assertEquals(6, rows.size());

        String out = r.out();
        assertFalse(out.contains("Exception"), () -> "a stack frame reached stdout:\n" + out);
        assertFalse(out.contains("\tat "), () -> "a stack frame reached stdout:\n" + out);
        assertFalse(
                out.toLowerCase(Locale.ROOT).contains("usage:"),
                () -> "usage text reached stdout on a SUCCESSFUL run:\n" + out);
    }

    /** Matches {@code massql_query.py}'s {@code sys.stdout.write("\n")}. */
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

    /** With {@code --output}, stdout is <b>empty</b>: the file is the payload's only destination. */
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
        assertEquals(6, parseArray(readString(out), "the --output file").size());
    }

    /**
     * ⛔ The bridge between (a) and (b): both modes emit <b>identical bytes</b>.
     *
     * <p>This is what makes every file-based assertion above a valid statement about the piped payload
     * too. Without it, the CLI could render one thing to a file and another to the pipe and every other
     * test here would still pass.
     */
    @Test
    void thePipedAndFileModesAgreeByteForByte(@TempDir Path dir) {
        Path file = dir.resolve("results.json");
        fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", file);
        Run piped = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());

        assertArrayEquals(
                readBytes(file),
                piped.stdout(),
                "--output and stdout must be the same bytes -- '-' is documented as meaning stdout, so"
                        + " they are two destinations for one rendering, not two renderings");
    }

    /** {@code --output -} is documented as meaning stdout, so it must behave exactly like omitting it. */
    @Test
    void outputDashMeansStdout() {
        Run dash = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery(), "--output", "-");
        Run omitted = fork(CliFixtures.smallMzml(), CliFixtures.standardQuery());

        assertEquals(0, dash.exit(), () -> dash.err());
        assertArrayEquals(
                omitted.stdout(), dash.stdout(), "--output - is stdout, per the usage text");
    }

    // =================================================== exit codes, through a real process

    /**
     * A missing file is a <b>usage</b> error: exit 2, message names the path, stdout untouched.
     *
     * <p>Step 11's {@code MainExitCodeTest} owns the code mapping in-process. What is added here is that
     * the failing path writes <b>nothing</b> to a real stdout — a partial array followed by an error is
     * the failure mode that would corrupt a consumer pipeline.
     */
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

    /** An unsupported query names the offending construct, exits 2, and leaves stdout empty. */
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

    /** {@code --help} is asked for, so it is the program's output: stdout, exit 0. */
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

    // ------------------------------------------------------------------ file helpers

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
