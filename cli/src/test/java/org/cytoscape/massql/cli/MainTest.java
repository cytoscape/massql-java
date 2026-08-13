package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Argument parsing, and the {@code --help} contract.
 *
 * <p>Every rejection here is exit <b>2</b>, and the rule that decides it is: <i>could the user have
 * known from the command line alone?</i> Nothing in this class opens a spectra file to find out.
 */
class MainTest {

    @TempDir Path dir;

    // ------------------------------------------------------------------ positional args

    @Test
    void argumentOrderMatchesThePythonReference() {
        // spectra file first, query file second. Swapped, the differential in the differential
        // would
        // compare two programs that were never given the same input.
        CliFixtures.Invocation ok =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString());
        assertEquals(0, ok.exitCode(), "stderr: " + ok.stderr());

        // Swapped, the "query file" is mzML, which will not parse as MassQL. That is exit 2, not 1:
        // the query is read and parsed BEFORE the spectra file is opened, and a user who pointed at
        // the wrong file could have known it from the command line.
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

    // ------------------------------------------------------------------ --precursor-tol-ppm

    @Test
    void precursorTolPpmDefaultsToTwentyAndIsHonouredWhenGiven() {
        // 20.0 is the reference's default; 60 is the tolerance behind the *_tol60_* goldens. If the
        // flag were ignored, both runs would agree and this test would be the only thing to notice.
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
        // Negative is not a narrower window, it is nonsense -- and left unchecked it would match
        // nothing and read as a data problem rather than a typo.
        assertEquals(2, invoke(spectra, query, "--precursor-tol-ppm", "-5").exitCode());
        assertEquals(2, invoke(spectra, query, "--precursor-tol-ppm", "NaN").exitCode());
        assertEquals(
                2, invoke(spectra, query, "--precursor-tol-ppm").exitCode(), "flag with no value");
    }

    // ------------------------------------------------------------------ file gates

    @Test
    void aMissingOrEmptyFileExitsTwoNotOne() {
        // These are knowable from the command line, so they are USAGE errors -- even
        // though SpectraFile.open throws the same exception type for them as for unreadable
        // content.
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

        // Non-empty on disk but empty after the .strip() the reference applies.
        Path blank = CliFixtures.write(dir, "blank.massql", "   \n\t\n");
        CliFixtures.Invocation r = invoke(spectra, blank.toString());
        assertEquals(2, r.exitCode());
        assertTrue(r.stderr().contains("empty"), r.stderr());
    }

    @Test
    void aQueryFileIsStrippedBeforeParsing() {
        // The reference strips the query text, and the committed .massql files end with a
        // newline.
        Path padded =
                CliFixtures.write(
                        dir,
                        "padded.massql",
                        "\n\n  QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0  \n\n");
        CliFixtures.Invocation r = invoke(CliFixtures.smallMzml().toString(), padded.toString());
        assertEquals(0, r.exitCode(), "stderr: " + r.stderr());
        assertTrue(r.stdout().startsWith("["), r.stdout());
    }

    @Test
    void anArgumentThatIsNotAUsablePathExitsTwo() {
        // A NUL byte cannot appear in a path on any supported platform, so Paths.get rejects it
        // before the filesystem is ever consulted. Worth covering because it is the one route into
        // the CLI where an argument fails to become a Path at all -- and an unhandled
        // InvalidPathException would surface as a stack trace rather than a usage message.
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

    // ------------------------------------------------------------------ --help

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
        // `--help` with nothing else is the commonest way anyone discovers a CLI. Reporting
        // "missing
        // arguments" instead of printing help would be actively unhelpful.
        CliFixtures.Invocation r = invoke("--help", "and", "some", "junk");
        assertEquals(0, r.exitCode());
        assertTrue(r.stdout().contains("Usage:"));
    }

    @Test
    void usageOnAnErrorGoesToStderrLeavingStdoutEmpty() {
        // ⛔ The rule that keeps --help from breaking the stdout contract: usage text is the
        // program's OUTPUT when requested, but a DIAGNOSTIC when it accompanies a failure -- and
        // exit
        // 2 must never put non-JSON on stdout, or a piped consumer parses the help screen.
        CliFixtures.Invocation r = invoke();
        assertEquals(2, r.exitCode());
        assertTrue(r.stdoutIsEmpty(), "stdout must stay clean on a usage error: " + r.stdout());
        assertTrue(r.stderr().contains("Usage:"), r.stderr());
    }

    // ------------------------------------------------------------------ --pretty

    @Test
    void prettyIsTheDefaultAndColoursStdout() {
        // The human case is the default: someone exploring in a terminal should not have to
        // discover a
        // flag to get readable output.
        for (String[] args :
                new String[][] {
                    {}, {"--pretty", "true"},
                }) {
            CliFixtures.Invocation r = run(args);
            assertEquals(0, r.exitCode(), r.stderr());
            assertTrue(
                    r.stdout().lines().count() > 2,
                    "indented output spans many lines: " + r.stdout());
            assertTrue(
                    r.stdout().contains("\u001B"), "stdout is a terminal sink, so it is coloured");
            // Escapes stripped first: a colour code sits between the indent and the key, so the
            // indentation is only visible in the uncolourised text.
            assertTrue(
                    r.stdout().replaceAll("\u001B\\[[0-9]+m", "").contains("\n    \"scan\": "),
                    "two-space indent per level: " + r.stdout());
        }
    }

    @Test
    void prettyFalseIsTheMachineForm() {
        // ⛔ This is the flag a caller piping into jq needs, since the default emits escape codes.
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
    void aFileSinkIsIndentedButNeverColoured() throws Exception {
        // Escape codes in a file would break every parser that later reads it, so --output is
        // uncolourised even though --pretty is on.
        Path out = dir.resolve("pretty.json");
        CliFixtures.Invocation r = run("--pretty", "true", "--output", out.toString());
        assertEquals(0, r.exitCode(), r.stderr());
        assertTrue(r.stdoutIsEmpty(), "--output keeps stdout empty: " + r.stdout());

        String json = java.nio.file.Files.readString(out);
        assertTrue(json.contains("\n    \"scan\": "), "still indented: " + json);
        assertFalse(json.contains("\u001B"), "a file is not a terminal: " + json);
    }

    @Test
    void aNonBooleanPrettyValueIsRejectedRatherThanTreatedAsFalse() {
        // Boolean.parseBoolean maps every unrecognised string to false, which would silently turn
        // formatting off for a typo. Both of these must say so instead.
        CliFixtures.Invocation typo = run("--pretty", "maybe");
        assertEquals(2, typo.exitCode(), "stdout: " + typo.stdout());
        assertTrue(typo.stderr().contains("--pretty"), typo.stderr());

        CliFixtures.Invocation missing = run("--pretty");
        assertEquals(2, missing.exitCode(), "stdout: " + missing.stdout());
        assertTrue(missing.stdoutIsEmpty(), "no JSON on a usage error: " + missing.stdout());
    }

    /** The standard mzML + query pair, plus whatever flags a {@code --pretty} case is exercising. */
    private static CliFixtures.Invocation run(String... flags) {
        String[] args = new String[flags.length + 2];
        args[0] = CliFixtures.smallMzml().toString();
        args[1] = CliFixtures.standardQuery().toString();
        System.arraycopy(flags, 0, args, 2, flags.length);
        return invoke(args);
    }
}
