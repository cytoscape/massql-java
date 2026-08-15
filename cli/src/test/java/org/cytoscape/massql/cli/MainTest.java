package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir Path dir;

    @Test
    void argumentOrderMatchesThePythonReference() {
        CliFixtures.Invocation ok =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString());
        assertEquals(0, ok.exitCode(), "stderr: " + ok.stderr());

        CliFixtures.Invocation swapped =
                invoke(CliFixtures.standardQuery().toString(), CliFixtures.smallMzml().toString());
        assertEquals(2, swapped.exitCode(), "stderr: " + swapped.stderr());
        assertTrue(swapped.stdoutIsEmpty(), "no JSON on stdout when the query is nonsense");
    }

    @Test
    void missingArgumentsExitTwo() {
        assertEquals(2, invoke().exitCode());
        assertEquals(2, invoke(CliFixtures.smallMzml().toString()).exitCode());
    }

    @Test
    void anExtraPositionalArgumentExitsTwo() {
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "surplus");
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("surplus"), r.stderr());
    }

    @Test
    void anUnknownOptionExitsTwo() {
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "--nope");
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("unknown option"), r.stderr());
    }

    @Test
    void precursorTolPpmDefaultsToTwentyAndIsHonouredWhenGiven() {
        String spectra = CliFixtures.smallMzml().toString();
        String query = CliFixtures.standardQuery().toString();

        String atDefault = invoke(spectra, query).stdout();
        String at20 = invoke(spectra, query, "--precursor-tol-ppm", "20.0").stdout();
        String at60 = invoke(spectra, query, "--precursor-tol-ppm", "60").stdout();

        assertEquals(at20, atDefault, "omitting the flag must equal passing 20.0");
        assertFalse(at60.equals(atDefault), "60 ppm must not produce the 20 ppm answer");
    }

    @Test
    void aNonNumericOrNegativeToleranceExitsTwo() {
        String spectra = CliFixtures.smallMzml().toString();
        String query = CliFixtures.standardQuery().toString();

        assertEquals(2, invoke(spectra, query, "--precursor-tol-ppm", "abc").exitCode());

        assertEquals(2, invoke(spectra, query, "--precursor-tol-ppm", "-5").exitCode());
        assertEquals(2, invoke(spectra, query, "--precursor-tol-ppm", "NaN").exitCode());
        assertEquals(
                2, invoke(spectra, query, "--precursor-tol-ppm").exitCode(), "flag with no value");
    }

    @Test
    void aMissingOrEmptyFileExitsTwoNotOne() {
        Path missing = dir.resolve("nope.mzML");
        CliFixtures.Invocation r1 =
                invoke(missing.toString(), CliFixtures.standardQuery().toString());
        assertEquals(2, r1.exitCode());
        assertTrue(r1.stderr().contains("no such"), r1.stderr());

        Path empty = CliFixtures.write(dir, "empty.mzML", "");
        assertEquals(
                2, invoke(empty.toString(), CliFixtures.standardQuery().toString()).exitCode());

        CliFixtures.Invocation dirAsFile =
                invoke(dir.toString(), CliFixtures.standardQuery().toString());
        assertEquals(2, dirAsFile.exitCode());
        assertTrue(dirAsFile.stderr().contains("not a regular file"), dirAsFile.stderr());
    }

    @Test
    void anEmptyOrWhitespaceOnlyQueryFileExitsTwo() {
        String spectra = CliFixtures.smallMzml().toString();

        Path empty = CliFixtures.write(dir, "empty.massql", "");
        assertEquals(2, invoke(spectra, empty.toString()).exitCode());

        Path blank = CliFixtures.write(dir, "blank.massql", "   \n\t\n");
        CliFixtures.Invocation r = invoke(spectra, blank.toString());
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("empty"), r.stderr());
    }

    @Test
    void aQueryFileIsStrippedBeforeParsing() {
        Path padded =
                CliFixtures.write(
                        dir,
                        "padded.massql",
                        "\n\n  QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0  \n\n");
        CliFixtures.Invocation r = invoke(CliFixtures.smallMzml().toString(), padded.toString());
        assertEquals(0, r.exitCode(), "stderr: " + r.stderr());
        assertFalse(parse(r.stdout()).results().isEmpty(), r.stdout());
    }

    @Test
    void anArgumentThatIsNotAUsablePathExitsTwo() {
        String nul = "x" + (char) 0 + "y";
        String query = CliFixtures.standardQuery().toString();

        CliFixtures.Invocation asSpectra = invoke(nul, query);
        assertEquals(2, asSpectra.exitCode());
        assertTrue(asSpectra.stderr().contains("<spectra-file>"), asSpectra.stderr());

        CliFixtures.Invocation asQuery = invoke(CliFixtures.smallMzml().toString(), nul);
        assertEquals(2, asQuery.exitCode());
        assertTrue(asQuery.stderr().contains("<query-file>"), asQuery.stderr());

        CliFixtures.Invocation asOutput =
                invoke(CliFixtures.smallMzml().toString(), query, "--output", nul);
        assertEquals(2, asOutput.exitCode());
        assertTrue(asOutput.stderr().contains("--output"), asOutput.stderr());
    }

    @Test
    void helpGoesToStdoutAndExitsZero() {
        for (String flag : new String[] {"-h", "--help"}) {
            CliFixtures.Invocation r = invoke(flag);
            assertEquals(0, r.exitCode(), flag + " was asked for, so it is not an error");
            assertTrue(r.stdout().contains("Usage:"), flag + " -> " + r.stdout());
            assertTrue(r.stdout().contains("--precursor-tol-ppm"), "every flag must be listed");
            assertTrue(r.stdout().contains("--output"), "every flag must be listed");
            assertTrue(r.stdout().contains("--pretty"), "every flag must be listed");
            assertTrue(r.stderr().isEmpty(), "nothing belongs on stderr when help was requested");
        }
    }

    @Test
    void helpWinsOverAnOtherwiseInvalidCommandLine() {
        CliFixtures.Invocation r = invoke("--help", "and", "some", "junk");
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("Usage:"));
    }

    @Test
    void usageOnAnErrorGoesToStderrLeavingStdoutEmpty() {
        CliFixtures.Invocation r = invoke();
        assertEquals(2, r.exitCode());
        assertTrue(r.stdoutIsEmpty(), "stdout must stay clean on a usage error: " + r.stdout());
        assertTrue(r.stderr().contains("Usage:"), r.stderr());
    }

    @Test
    void prettyIsTheDefault() {
        for (String[] args :
                new String[][] {
                    {}, {"--pretty", "true"},
                }) {
            CliFixtures.Invocation r = run(args);
            assertEquals(0, r.exitCode(), r.stderr());
            assertTrue(
                    r.stdout().lines().count() > 2,
                    "indented output spans many lines: " + r.stdout());
            assertTrue(r.stdout().contains("\"scan\": "), "keys are indented: " + r.stdout());
        }
    }

    @Test
    void prettyFalseIsTheMachineForm() {
        CliFixtures.Invocation r = run("--pretty", "false");
        assertEquals(0, r.exitCode(), r.stderr());
        assertEquals(
                1,
                r.stdout().stripTrailing().lines().count(),
                "compact is one line: " + r.stdout());
        assertFalse(r.stdout().contains("\u001B"), "no colour when the output is machine-readable");
        assertTrue(r.stdout().contains("{\"scan\":"), r.stdout());
    }

    @Test
    void aFileSinkIsByteIdenticalToStdout() throws Exception {
        Path out = dir.resolve("pretty.json");
        CliFixtures.Invocation toFile = run("--pretty", "true", "--output", out.toString());
        assertEquals(0, toFile.exitCode(), toFile.stderr());
        assertTrue(toFile.stdoutIsEmpty(), "--output keeps stdout empty: " + toFile.stdout());

        CliFixtures.Invocation toStdout = run("--pretty", "true");
        assertEquals(
                toStdout.stdout().strip(),
                java.nio.file.Files.readString(out).strip(),
                "one render, two destinations");
    }

    void aNonBooleanPrettyValueIsRejectedRatherThanTreatedAsFalse() {
        CliFixtures.Invocation typo = run("--pretty", "maybe");
        assertEquals(2, typo.exitCode(), "stdout: " + typo.stdout());
        assertTrue(typo.stderr().contains("--pretty"), typo.stderr());

        CliFixtures.Invocation missing = run("--pretty");
        assertEquals(2, missing.exitCode(), "stdout: " + missing.stdout());
        assertTrue(missing.stdoutIsEmpty(), "no JSON on a usage error: " + missing.stdout());
    }

    private static CliFixtures.Invocation run(String... flags) {
        String[] args = new String[flags.length + 2];
        args[0] = CliFixtures.smallMzml().toString();
        args[1] = CliFixtures.standardQuery().toString();
        System.arraycopy(flags, 0, args, 2, flags.length);
        return invoke(args);
    }
}
