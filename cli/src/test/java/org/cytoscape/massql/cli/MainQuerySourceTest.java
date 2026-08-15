package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.invokeWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three query sources — a file, {@code -} for stdin, and {@code -q}/{@code --query} inline.
 *
 * <h2>What this class is really pinning</h2>
 *
 * <p>Three front doors onto <b>one</b> query path. The load-bearing assertion is
 * {@link #allThreeSourcesProduceIdenticalBytes()}: if the forms ever disagree, the CLI has grown three
 * query <i>paths</i> instead, and every other test here would still pass while the tool quietly behaved
 * differently depending on how you typed the query.
 *
 * <p>The rejections matter as much as the successes. Supplying two sources is a usage error rather than
 * a precedence rule, because a caller who gave two is unsure which one runs — and silently picking one
 * hides exactly the confusion they need told about.
 *
 * <p>⚠ Stdin is driven through {@code Main.run}'s {@code InputStream} parameter, never
 * {@code System.setIn}. Global mutable state would make every other test in the JVM order-dependent,
 * which is the same reason the two output streams are parameters.
 */
class MainQuerySourceTest {

    /** The query behind `small_mzml_results.json` — 6 rows against `data/small.mzML`. */
    private static final String QUERY =
            "QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0";

    private static String spectra() {
        return CliFixtures.smallMzml().toString();
    }

    private static String queryFile() {
        return CliFixtures.standardQuery().toString();
    }

    /** Counts top-level rows without a JSON parser: every row object starts with `{"scan"`. */
    private static int rows(String json) {
        return CliFixtures.parse(json).results().size();
    }

    // ------------------------------------------------------------------ each source works

    @Test
    void aQueryFileRuns() {
        CliFixtures.Invocation r = invoke(spectra(), queryFile(), "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    @Test
    void aQueryOnStdinRuns() {
        CliFixtures.Invocation r = invokeWithStdin(QUERY, spectra(), "-", "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    @Test
    void anInlineQueryRuns() {
        for (String flag : new String[] {"-q", "--query"}) {
            CliFixtures.Invocation r = invoke(spectra(), flag, QUERY, "--pretty", "false");
            assertEquals(0, r.exitCode(), flag + ": " + r.stderr());
            assertEquals(6, rows(r.stdout()), flag + ": " + r.stdout());
        }
    }

    /** A trailing newline, as a heredoc or `cat` supplies, is stripped like the file form's. */
    @Test
    void stdinIsStrippedSoAHeredocWorks() {
        CliFixtures.Invocation r =
                invokeWithStdin("\n  " + QUERY + "  \n\n", spectra(), "-", "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    /**
     * ⛔ <b>The assertion this class exists for.</b> One query, three front doors, identical bytes.
     *
     * <p>Compared as raw strings rather than by row count: a difference in ordering, formatting or the
     * trailing newline would slip past a count and is exactly the kind of drift that appears once the
     * three forms have separate code paths.
     */
    @Test
    void allThreeSourcesProduceIdenticalBytes() {
        String fromFile = invoke(spectra(), queryFile()).stdout();
        String fromStdin = invokeWithStdin(QUERY, spectra(), "-").stdout();
        String fromFlag = invoke(spectra(), "-q", QUERY).stdout();

        assertTrue(fromFile.length() > 100, "the baseline must be a real payload: " + fromFile);
        assertEquals(fromFile, fromStdin, "stdin must render exactly what the file form renders");
        assertEquals(fromFile, fromFlag, "--query must render exactly what the file form renders");
    }

    /**
     * Stdin is <b>not</b> consulted when a file or inline query was given.
     *
     * <p>Guards the property that keeps the tool from hanging: an invocation with a query file must
     * never touch stdin, so it cannot block on a terminal. Here a *different* query is placed on stdin
     * and the file's answer must win.
     */
    @Test
    void stdinIsIgnoredUnlessTheDashSelectsIt() {
        String decoy = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=9999.0:TOLERANCEMZ=0.1";

        CliFixtures.Invocation viaFile =
                invokeWithStdin(decoy, spectra(), queryFile(), "--pretty", "false");
        assertEquals(0, viaFile.exitCode(), viaFile.stderr());
        assertEquals(6, rows(viaFile.stdout()), "the FILE query ran, not the one on stdin");

        CliFixtures.Invocation viaFlag =
                invokeWithStdin(decoy, spectra(), "-q", QUERY, "--pretty", "false");
        assertEquals(6, rows(viaFlag.stdout()), "the INLINE query ran, not the one on stdin");
    }

    // ------------------------------------------------------------------ too few, too many

    @Test
    void noQuerySourceExitsTwoAndNamesTheThreeForms() {
        CliFixtures.Invocation r = invoke(spectra());
        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), "stdout must stay empty: " + r.stdout());
        assertTrue(r.stderr().contains("--query"), r.stderr());
        assertTrue(r.stderr().contains("stdin"), r.stderr());
    }

    @Test
    void aMissingSpectraFileArgumentExitsTwo() {
        CliFixtures.Invocation r = invoke();
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("<spectra-file>"), r.stderr());
    }

    /** Every pairwise combination, plus all three at once. */
    @Test
    void moreThanOneQuerySourceExitsTwo() {
        String[][] combinations = {
            {spectra(), queryFile(), "-"},
            {spectra(), queryFile(), "-q", QUERY},
            {spectra(), "-", "-q", QUERY},
            {spectra(), queryFile(), "-", "-q", QUERY},
        };

        for (String[] args : combinations) {
            CliFixtures.Invocation r = invokeWithStdin(QUERY, args);
            assertEquals(2, r.exitCode(), () -> "expected rejection for " + String.join(" ", args));
            assertTrue(r.stdoutIsEmpty(), () -> "stdout must stay empty: " + r.stdout());
            assertTrue(
                    r.stderr().contains("more than one way"),
                    () -> "the message must say the query was given twice: " + r.stderr());
        }
    }

    /** No precedence rule: two sources fails even when both carry the SAME query. */
    @Test
    void twoSourcesFailEvenWhenTheyAgree() {
        CliFixtures.Invocation r = invoke(spectra(), queryFile(), "-q", QUERY);
        assertEquals(2, r.exitCode(), r.stderr());
    }

    // ------------------------------------------------------------------ empty, per source

    @Test
    void anEmptyQueryFileExitsTwoAndNamesTheFile(@TempDir Path dir) {
        Path empty = CliFixtures.write(dir, "empty.massql", "");
        CliFixtures.Invocation r = invoke(spectra(), empty.toString());

        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stderr().contains("empty.massql"), r.stderr());
    }

    @Test
    void anEmptyStdinExitsTwoAndSaysStdin() {
        CliFixtures.Invocation r = invokeWithStdin("", spectra(), "-");

        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), r.stdout());
        assertTrue(
                r.stderr().contains("stdin"), () -> "the message must name stdin: " + r.stderr());
    }

    @Test
    void aWhitespaceOnlyStdinIsEmptyToo() {
        CliFixtures.Invocation r = invokeWithStdin("   \n\t\n  ", spectra(), "-");
        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stderr().contains("stdin"), r.stderr());
    }

    @Test
    void anEmptyOrBlankInlineQueryExitsTwoAndSaysSo() {
        for (String blank : new String[] {"", "   ", "\n\t "}) {
            CliFixtures.Invocation r = invoke(spectra(), "-q", blank);
            assertEquals(2, r.exitCode(), () -> "blank --query must be rejected: " + r.stderr());
            assertTrue(r.stdoutIsEmpty(), r.stdout());
            assertTrue(r.stderr().contains("--query"), r.stderr());
        }
    }

    @Test
    void queryFlagWithoutAValueExitsTwo() {
        CliFixtures.Invocation r = invoke(spectra(), "-q");
        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stderr().contains("requires a value"), r.stderr());
    }

    // ------------------------------------------------------------------ the dash, precisely

    /**
     * {@code -} is rejected in the <b>spectra</b> position, with a reason rather than a bare refusal.
     *
     * <p>Readers memory-map their input and the format is sniffed by reading the head first, so a
     * non-seekable stream genuinely cannot work — the message says that, because "unknown option" or a
     * file-not-found on a file named {@code -} would send the user looking in the wrong place.
     */
    @Test
    void aDashInTheSpectraPositionIsRejectedWithAReason() {
        CliFixtures.Invocation r = invokeWithStdin(QUERY, "-", queryFile());

        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), r.stdout());
        assertTrue(
                r.stderr().contains("cannot be read from stdin"),
                () -> "the message must explain why, not just refuse: " + r.stderr());
    }

    /** A repeated {@code -q} is an ordinary override, exactly like a repeated tolerance flag. */
    @Test
    void aRepeatedQueryFlagIsLastWins() {
        String decoy = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=9999.0:TOLERANCEMZ=0.1";

        CliFixtures.Invocation r = invoke(spectra(), "-q", decoy, "-q", QUERY, "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), "the LAST --query wins");

        // And the decoy really would have produced something different, or this proves nothing.
        assertNotEquals(6, rows(invoke(spectra(), "-q", decoy, "--pretty", "false").stdout()));
    }

    /** The two dash meanings are independent: `-` selects stdin, `--output -` selects stdout. */
    @Test
    void theQueryDashAndTheOutputDashDoNotInterfere() {
        CliFixtures.Invocation r =
                invokeWithStdin(QUERY, spectra(), "-", "--output", "-", "--pretty", "false");

        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    /** `--help` still short-circuits, even with a query source that would otherwise be rejected. */
    @Test
    void helpWinsOverAMissingQuery() {
        CliFixtures.Invocation r = invoke(spectra(), "--help");
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("--query"), "the usage text must document the new flag");
        assertTrue(r.stdout().contains("stdin"), "and the stdin form");
    }
}
