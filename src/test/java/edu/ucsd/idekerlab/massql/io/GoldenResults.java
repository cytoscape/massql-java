package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * Reads a Python-generated golden from {@code goldens/query-results/} into {@link ScanInfoResult}
 * rows.
 *
 * <h2>Why a JSON library, when the rest of this repository parses JSON with regex</h2>
 *
 * <p>Three other places ({@code ParityDump}, {@code MzmlReaderTest}, {@code MzxmlReaderTest}) hand-roll
 * their parsing, {@code ParityDump} citing {@code docs/DEPENDENCY_POLICY.md} constraint 1 — <i>"Jackson
 * discovers modules via ServiceLoader"</i>. But that constraint's stated <b>failure mechanism</b> is a
 * thread-context classloader that cannot see the caller's classes, and a test running under Gradle on a
 * flat classpath has no such split. The rule was being applied outside its own mechanism.
 * {@code gson} is {@code testImplementation} only, and
 * {@code checkBannedDependencies} inspects {@code runtimeClasspath}, so it provably cannot reach the
 * shipping closure.
 *
 * <p>⛔ <b>The decisive argument is.</b> {@code ParityDump}'s regex stopped at
 * {@code polarity} and silently dropped {@code charge}, {@code ms1scan} and {@code precmz} — which let
 * an MGF charge bug survive a <b>green</b> the parity gate gate for five steps. This file feeds the
 * differential that <i>is</i> the spike's exit criterion. A parser whose failure mode is silent
 * truncation is the wrong tool for that job.
 *
 * <h2>Strictness is the point</h2>
 *
 * <p>Every read is validated: the document must be an array, each element an object carrying
 * <b>exactly</b> the 12 frozen keys, and every value a number or {@code null}. A golden that has been
 * truncated, reordered or had a row dropped <b>fails loudly here</b> rather than quietly comparing
 * fewer rows and reporting green. {@code GoldenResultsTest} proves each of those rejections.
 *
 * <p><b>Public because it is shared across test packages</b> — the differential lives in
 * {@code …massql.exec} and the cross-format test in {@code …massql.io}. It is in the test source
 * set, so it appears in no shipped artifact and on no public API surface.
 *
 * <p>The key list is duplicated from {@code ScanInfoResult.KEYS}, which is package-private to
 * {@code …massql.result}. {@code docs/RESULT_SCHEMA.md} is the single definition and
 * {@code ResultSchemaContractTest} holds the production side to it; this copy is checked against the
 * goldens themselves on every read, so a divergence surfaces immediately.
 */
public final class GoldenResults {

    /** The frozen 12-key contract, in order — {@code docs/RESULT_SCHEMA.md}. */
    public static final List<String> KEYS =
            List.of(
                    "scan",
                    "precmz",
                    "ms1scan",
                    "rt",
                    "charge",
                    "tic",
                    "mslevel",
                    "base_peak_i",
                    "base_peak_mz",
                    "ms1_i",
                    "ms1_precmz",
                    "ms1_base_peak_i");

    private GoldenResults() {}

    /**
     * Reads the golden named by its bare stem, e.g. {@code "small_mzml_results"}.
     *
     * @throws AssertionError if the golden is missing or malformed — never a skip
     */
    public static List<ScanInfoResult> of(String name) {
        return read(Fixtures.require("goldens/query-results/" + name + ".json"));
    }

    /** Reads a golden from an explicit path, for tests that construct malformed input. */
    public static List<ScanInfoResult> read(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonSyntaxException e) {
            throw new AssertionError(
                    "golden is not valid JSON: " + path + " -- " + e.getMessage(), e);
        }
        if (!root.isJsonArray()) {
            throw new AssertionError("golden must be a JSON array, got " + root + ": " + path);
        }

        JsonArray rows = root.getAsJsonArray();
        List<ScanInfoResult> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(row(path, i, rows.get(i)));
        }
        return List.copyOf(out);
    }

    private static ScanInfoResult row(Path path, int index, JsonElement element) {
        String at = path.getFileName() + " row " + index;
        if (!element.isJsonObject()) {
            throw new AssertionError(at + " is not an object: " + element);
        }
        JsonObject o = element.getAsJsonObject();

        // Exactly the 12 keys -- no more, no fewer. A golden that lost a key would otherwise read
        // as
        // a row full of nulls and compare "successfully" against a genuinely null column.
        if (!o.keySet().equals(new java.util.LinkedHashSet<>(KEYS))) {
            throw new AssertionError(
                    at
                            + " does not carry exactly the 12 frozen keys.\n  expected: "
                            + KEYS
                            + "\n  actual:   "
                            + o.keySet()
                            + "\nSee docs/RESULT_SCHEMA.md -- the contract is one union schema with no key"
                            + " ever absent.");
        }

        return new ScanInfoResult(
                integer(at, o, "scan"),
                number(at, o, "precmz"),
                integer(at, o, "ms1scan"),
                number(at, o, "rt"),
                integer(at, o, "charge"),
                number(at, o, "tic"),
                integer(at, o, "mslevel"),
                number(at, o, "base_peak_i"),
                number(at, o, "base_peak_mz"),
                number(at, o, "ms1_i"),
                number(at, o, "ms1_precmz"),
                number(at, o, "ms1_base_peak_i"));
    }

    /** A JSON number or null. Anything else — a string, a bool, an object — is a malformed golden. */
    private static Double number(String at, JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw new AssertionError(at + ": '" + key + "' must be a number or null, got " + v);
        }
        return v.getAsDouble();
    }

    private static Integer integer(String at, JsonObject o, String key) {
        JsonElement v = o.get(key);
        if (v.isJsonNull()) return null;
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw new AssertionError(at + ": '" + key + "' must be a number or null, got " + v);
        }
        double d = v.getAsDouble();
        if (d != Math.rint(d)) {
            throw new AssertionError(at + ": '" + key + "' must be integral, got " + d);
        }
        return (int) d;
    }

    /** Asserts the golden exists, so a missing file fails here rather than as an empty comparison. */
    public static void requireExists(String name) {
        assertNotNull(
                GoldenResults.class
                        .getClassLoader()
                        .getResource("goldens/query-results/" + name + ".json"),
                "golden missing: " + name + ".json -- goldens are committed to this repository");
    }
}
