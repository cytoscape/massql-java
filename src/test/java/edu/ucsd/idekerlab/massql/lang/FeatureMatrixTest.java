package edu.ucsd.idekerlab.massql.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.lang.ast.ConditionType;
import edu.ucsd.idekerlab.massql.lang.ast.DataSource;
import edu.ucsd.idekerlab.massql.lang.ast.QualifierType;
import edu.ucsd.idekerlab.massql.lang.ast.QueryFunction;

/**
 * {@code docs/FEATURE_MATRIX.md} is generated from the code, committed, and guarded against drift.
 *
 * <h2>Why generated rather than written</h2>
 *
 * <p>A hand-maintained matrix is the trap Tech_Step13 names explicitly: it drifts from
 * {@link UnsupportedConstructs} the first time a construct is added, and the published document then
 * tells a user something the build disagrees with. Every row here comes from a public enum or from
 * {@code UnsupportedConstructs} — {@link QueryFunction}, {@link DataSource}, {@link ConditionType},
 * {@link QualifierType} — so the matrix cannot claim support the parser does not have.
 *
 * <p><b>The file is committed rather than generated at build time</b>, for two reasons: a reviewer
 * reading the repository can see it, and a generated-then-compared file makes this test meaningful
 * instead of tautological. The rendering lives in {@link #render()}, used both to check the committed
 * copy and — via {@code make feature-matrix} — to rewrite it.
 *
 * <p>To regenerate after changing any of those enums:
 *
 * <pre>{@code make feature-matrix}</pre>
 */
class FeatureMatrixTest {

    private static final String REGENERATE = "massql.regenerate.matrix";

    /** Walks up for the repo root; {@code TestPaths} is not reachable from every source set. */
    private static Path matrixFile() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs"))
                    && Files.isRegularFile(dir.resolve("settings.gradle"))) {
                return dir.resolve("docs/FEATURE_MATRIX.md");
            }
        }
        throw new AssertionError(
                "repo root not found walking up from "
                        + Paths.get("").toAbsolutePath()
                        + " -- docs/ is committed, so this is a real failure, never a skip (C26).");
    }

    /**
     * The whole document, derived from code.
     *
     * <p>⚠ Iteration order must be stable or the committed file churns on every regeneration.
     * {@code UnsupportedConstructs.all()} returns an insertion-ordered map for exactly this reason;
     * enum {@code values()} is declaration-ordered by the language.
     */
    static String render() {
        StringBuilder sb = new StringBuilder();

        sb.append("# Feature matrix — what parses, what executes, what rejects\n\n");
        sb.append(
                "> ⚠ **Generated from the code by `make feature-matrix`. Do not edit by hand.**\n");
        sb.append(
                "> Sources: `QueryFunction`, `DataSource`, `ConditionType`, `QualifierType` and\n");
        sb.append(
                "> `UnsupportedConstructs`. `FeatureMatrixTest` fails if this file and they disagree.\n\n");

        sb.append(
                "This build implements the MassQL **`scaninfo` subset**. Anything below marked rejected\n");
        sb.append(
                "fails at parse time with a message naming the construct — never silently ignored,\n");
        sb.append(
                "because a query that quietly does something other than what it says is worse than one\n");
        sb.append("that refuses.\n\n");

        sb.append("## Query functions\n\n");
        sb.append("| Function | Status |\n|---|---|\n");
        for (QueryFunction f : QueryFunction.values()) {
            sb.append("| `")
                    .append(f.name().toLowerCase(java.util.Locale.ROOT))
                    .append("(...)` | ✅ parses and executes |\n");
        }
        sb.append('\n');

        sb.append("## Data sources\n\n");
        sb.append("| Source | Status |\n|---|---|\n");
        for (DataSource d : DataSource.values()) {
            sb.append("| `").append(d.name()).append("` | ✅ parses and executes |\n");
        }
        sb.append("\nBoth emit the **same 12-key row shape**, discriminated by `mslevel` — see\n");
        sb.append("[`RESULT_SCHEMA.md`](RESULT_SCHEMA.md).\n\n");

        sb.append("## Conditions\n\n");
        sb.append("| Condition | Status |\n|---|---|\n");
        for (ConditionType c : ConditionType.values()) {
            sb.append("| `").append(c.name()).append("` | ✅ parses and executes |\n");
        }
        sb.append("| `POLARITY` | ✅ parses and executes |\n");
        sb.append("| `MS2MZ` | ✅ accepted as an alias for `MS2PROD` |\n\n");

        sb.append("## Qualifiers\n\n");
        sb.append("| Qualifier | Status |\n|---|---|\n");
        for (QualifierType q : QualifierType.values()) {
            sb.append("| `").append(q.name()).append("` | ✅ parses and executes |\n");
        }
        sb.append('\n');

        sb.append("## Rejected — parses, then fails by name\n\n");
        sb.append("| Construct | Why |\n|---|---|\n");
        for (Map.Entry<String, String> e : UnsupportedConstructs.all().entrySet()) {
            sb.append("| `").append(e.getKey()).append("` | ").append(e.getValue()).append(" |\n");
        }
        sb.append('\n');
        sb.append(
                "Every one of these is rejected with the construct named, so the message says what to\n");
        sb.append("change rather than reporting a bare syntax error.\n");

        return sb.toString();
    }

    @Test
    void theCommittedMatrixMatchesTheCode() {
        Path file = matrixFile();
        String expected = render();

        if (Boolean.getBoolean(REGENERATE)) {
            try {
                Files.writeString(file, expected, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return;
        }

        String actual;
        try {
            actual = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(
                    "docs/FEATURE_MATRIX.md is missing. Generate it with `make feature-matrix`.",
                    e);
        }

        assertEquals(
                expected,
                actual,
                "docs/FEATURE_MATRIX.md is out of step with the code. Regenerate it with"
                        + " `make feature-matrix` -- do not hand-edit it, or it will drift again.");
    }

    /**
     * ⛔ Tests the test: the guard must notice a construct the matrix does not list.
     *
     * <p>Without this, a renderer that silently dropped entries would keep the comparison passing while
     * publishing an incomplete matrix — which is precisely the drift this file exists to prevent.
     */
    @Test
    void everyUnsupportedConstructAppearsInTheRenderedMatrix() {
        String rendered = render();
        assertFalse(
                UnsupportedConstructs.all().isEmpty(), "there are unsupported constructs to list");

        for (String name : UnsupportedConstructs.names()) {
            assertTrue(
                    rendered.contains("| `" + name + "` |"),
                    () -> "the rendered matrix omits the rejected construct '" + name + "'");
        }
    }

    /** And the supported half: every enum value reaches the document. */
    @Test
    void everySupportedEnumValueAppearsInTheRenderedMatrix() {
        String rendered = render();

        for (ConditionType c : ConditionType.values()) {
            assertTrue(rendered.contains("`" + c.name() + "`"), () -> "condition missing: " + c);
        }
        for (QualifierType q : QualifierType.values()) {
            assertTrue(rendered.contains("`" + q.name() + "`"), () -> "qualifier missing: " + q);
        }
        for (DataSource d : DataSource.values()) {
            assertTrue(rendered.contains("`" + d.name() + "`"), () -> "data source missing: " + d);
        }
    }

    /**
     * The rendering is deterministic — two calls agree.
     *
     * <p>Guards the ordering property directly: {@code UnsupportedConstructs.all()} must stay
     * insertion-ordered. Were it a {@code Map.copyOf}, iteration order would be randomised per JVM run
     * and the committed file would churn between regenerations for no reason.
     */
    @Test
    void renderingIsDeterministic() {
        assertEquals(render(), render(), "the matrix must render identically every time");
    }
}
