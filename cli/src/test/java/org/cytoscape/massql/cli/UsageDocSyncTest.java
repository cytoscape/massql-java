package org.cytoscape.massql.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@code docs/CLI.md} documents every option the CLI actually has.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code docs/CLI.md} reproduces the usage block by hand. Nothing but habit kept the two in step, and
 * the failure mode is silent and permanent: a flag is added, the docs keep describing the older tool, and
 * the drift is only found by a user who tried the documented command. This repository already runs
 * {@code spec-audit} for precisely this class of rot; this is the same idea, scoped to one file.
 *
 * <p>⚠ The comparison is against {@code --help}'s <b>actual output</b> rather than {@code Main.USAGE},
 * deliberately. Reading the field by reflection would assert that the docs match a string; running
 * {@code --help} asserts they match what a user is told. If those two ever diverge, the second is the one
 * worth pinning.
 */
class UsageDocSyncTest {

    /** Lines of `--help` that describe a flag or the `-` positional. */
    private static List<String> optionLines() {
        CliFixtures.Invocation help = CliFixtures.invoke("--help");
        assertTrue(help.exitCode() == 0, () -> "--help must succeed: " + help.stderr());

        List<String> out = new ArrayList<>();
        for (String raw : help.stdout().split("\\R")) {
            String line = raw.strip();
            // An option line starts with a dash; the "Usage:" synopsis and prose lines do not.
            if (line.startsWith("-")) out.add(line);
        }
        return out;
    }

    /**
     * Walks up for the repo root.
     *
     * <p>⚠ Not {@code TestPaths.repositoryRoot()} — that lives in the <b>SDK's</b> test source set, and
     * {@code :cli} deliberately takes only the SDK's test <i>resources</i>, not its classes.
     */
    private static Path cliDoc() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/CLI.md");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError(
                "docs/CLI.md not found walking up from "
                        + Paths.get("").toAbsolutePath()
                        + " -- it is committed to this repository, so this is a real failure rather"
                        + " than a reason to skip.");
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyOptionInHelpIsDocumentedInCliMd() {
        List<String> options = optionLines();
        assertFalse(options.isEmpty(), "no option lines parsed out of --help; the parser is wrong");

        String doc = read(cliDoc());
        List<String> missing = new ArrayList<>();
        for (String line : options) {
            if (!doc.contains(line)) missing.add(line);
        }

        assertTrue(
                missing.isEmpty(),
                () ->
                        "docs/CLI.md is out of step with `--help`. Missing verbatim:\n  "
                                + String.join("\n  ", missing)
                                + "\n\nUpdate docs/CLI.md's usage block to match the CLI's own output. The docs"
                                + " reproduce that block by hand, so nothing else keeps them in step.");
    }

    /** The synopsis line too — it is where the positional grammar is stated. */
    @Test
    void theSynopsisLineIsDocumented() {
        String synopsis = null;
        for (String raw : CliFixtures.invoke("--help").stdout().split("\\R")) {
            if (raw.strip().startsWith("Usage:")) {
                synopsis = raw.strip().substring("Usage:".length()).strip();
                break;
            }
        }
        assertTrue(synopsis != null, "`--help` no longer prints a Usage: line");

        String doc = read(cliDoc());
        String expected = synopsis;
        assertTrue(
                doc.contains(expected),
                () -> "docs/CLI.md does not contain the current synopsis:\n  " + expected);
    }

    /** Guards the guard: a flag that does not exist must NOT be found, or the check proves nothing. */
    @Test
    void theCheckWouldNoticeAnUndocumentedFlag() {
        assertFalse(
                read(cliDoc()).contains("--no-such-flag-exists"),
                "sanity: docs/CLI.md must not contain a flag the CLI has never had");
    }
}
