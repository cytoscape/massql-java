package org.cytoscape.massql.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cytoscape.massql.result.ResultJson;
import org.cytoscape.massql.result.ScanInfoResult;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * Reads a reference-generated golden from {@code goldens/query-results/} into {@link ScanInfoResult}
 * rows.
 *
 * <p>Gson maps the values; the structural checks here are what gson does not do. A golden that lost a
 * key would otherwise deserialize as a row of nulls and compare "successfully" against a genuinely
 * null column, so the key set and the value types are asserted before mapping.
 */
public final class GoldenResults {

    /** The frozen key order, read from the record itself rather than restated. */
    public static final List<String> KEYS =
            Arrays.stream(ScanInfoResult.class.getRecordComponents())
                    .map(GoldenResults::keyOf)
                    .toList();

    private static String keyOf(RecordComponent c) {
        try {
            // @SerializedName targets FIELD, so it propagates to the record's field rather than
            // staying readable on the component -- which is also where gson reads it.
            return c.getDeclaringRecord()
                    .getDeclaredField(c.getName())
                    .getAnnotation(SerializedName.class)
                    .value();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(c.getName(), e);
        }
    }

    /** Key -> component type, so the integral check below follows the record rather than a list. */
    private static final Map<String, Class<?>> TYPES =
            Arrays.stream(ScanInfoResult.class.getRecordComponents())
                    .collect(
                            Collectors.toMap(
                                    GoldenResults::keyOf,
                                    RecordComponent::getType,
                                    (a, b) -> a,
                                    LinkedHashMap::new));

    private static final Gson GSON = new Gson();

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
        if (!root.isJsonObject() || !root.getAsJsonObject().has("results")) {
            throw new AssertionError("golden must be an object with a 'results' array: " + path);
        }
        JsonElement results = root.getAsJsonObject().get("results");
        if (!results.isJsonArray()) {
            throw new AssertionError("'results' must be an array, got " + results + ": " + path);
        }

        List<JsonElement> rows = new ArrayList<>();
        results.getAsJsonArray().forEach(rows::add);
        for (int i = 0; i < rows.size(); i++) {
            validate(path, i, rows.get(i));
        }
        ResultJson parsed = GSON.fromJson(root, ResultJson.class);
        return parsed.results();
    }

    private static void validate(Path path, int index, JsonElement element) {
        String at = path.getFileName() + " row " + index;
        if (!element.isJsonObject()) {
            throw new AssertionError(at + " is not an object: " + element);
        }
        JsonObject o = element.getAsJsonObject();

        if (!o.keySet().equals(new LinkedHashSet<>(KEYS))) {
            throw new AssertionError(
                    at
                            + " does not carry exactly the 12 frozen keys.\n  expected: "
                            + KEYS
                            + "\n  actual:   "
                            + o.keySet()
                            + "\nSee docs/RESULT_SCHEMA.md -- the contract is one union schema with no key"
                            + " ever absent.");
        }
        for (String key : KEYS) {
            JsonElement v = o.get(key);
            if (v.isJsonNull()) continue;
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw new AssertionError(at + ": '" + key + "' must be a number or null, got " + v);
            }
            // Gson would truncate 3.5 to 3 for an Integer column, making an exact comparison
            // silently approximate.
            if (TYPES.get(key) == Integer.class && v.getAsDouble() % 1 != 0) {
                throw new AssertionError(at + ": '" + key + "' must be integral, got " + v);
            }
        }
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
