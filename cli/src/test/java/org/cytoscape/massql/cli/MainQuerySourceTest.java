package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.invokeWithStdin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainQuerySourceTest {
    private static final String QUERY =
            "QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0";

    private static String spectra() {
        return CliFixtures.smallMzml().toString();
    }

    private static String queryFile() {
        return CliFixtures.standardQuery().toString();
    }

    private static int rows(String json) {
        return CliFixtures.parse(json).results().size();
    }

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

    @Test
    void stdinIsStrippedSoAHeredocWorks() {
        CliFixtures.Invocation r =
                invokeWithStdin("\n  " + QUERY + "  \n\n", spectra(), "-", "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    @Test
    void allThreeSourcesProduceIdenticalBytes() {
        String fromFile = invoke(spectra(), queryFile()).stdout();
        String fromStdin = invokeWithStdin(QUERY, spectra(), "-").stdout();
        String fromFlag = invoke(spectra(), "-q", QUERY).stdout();

        assertTrue(fromFile.length() > 100, "the baseline must be a real payload: " + fromFile);
        assertEquals(fromFile, fromStdin, "stdin must render exactly what the file form renders");
        assertEquals(fromFile, fromFlag, "--query must render exactly what the file form renders");
    }

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

    @Test
    void twoSourcesFailEvenWhenTheyAgree() {
        CliFixtures.Invocation r = invoke(spectra(), queryFile(), "-q", QUERY);
        assertEquals(2, r.exitCode(), r.stderr());
    }

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

    @Test
    void aDashInTheSpectraPositionIsRejectedWithAReason() {
        CliFixtures.Invocation r = invokeWithStdin(QUERY, "-", queryFile());

        assertEquals(2, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), r.stdout());
        assertTrue(
                r.stderr().contains("cannot be read from stdin"),
                () -> "the message must explain why, not just refuse: " + r.stderr());
    }

    @Test
    void aRepeatedQueryFlagIsLastWins() {
        String decoy = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=9999.0:TOLERANCEMZ=0.1";

        CliFixtures.Invocation r = invoke(spectra(), "-q", decoy, "-q", QUERY, "--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), "the LAST --query wins");

        assertNotEquals(6, rows(invoke(spectra(), "-q", decoy, "--pretty", "false").stdout()));
    }

    @Test
    void theQueryDashAndTheOutputDashDoNotInterfere() {
        CliFixtures.Invocation r =
                invokeWithStdin(QUERY, spectra(), "-", "--output", "-", "--pretty", "false");

        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(6, rows(r.stdout()), r.stdout());
    }

    @Test
    void helpWinsOverAMissingQuery() {
        CliFixtures.Invocation r = invoke(spectra(), "--help");
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdout().contains("--query"), "the usage text must document the new flag");
        assertTrue(r.stdout().contains("stdin"), "and the stdin form");
    }
}
