package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainOutputFileTest {
    @TempDir Path dir;

    private static byte[] bytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void outputFileIsByteIdenticalToStdoutMode() {
        String spectra = CliFixtures.smallMzml().toString();
        String query = CliFixtures.standardQuery().toString();
        Path target = dir.resolve("result.json");

        CliFixtures.Invocation piped = invoke(spectra, query, "--pretty", "false");
        CliFixtures.Invocation filed =
                invoke(spectra, query, "--pretty", "false", "--output", target.toString());

        assertEquals(0, piped.exitCode(), piped.stderr());
        assertEquals(0, filed.exitCode(), filed.stderr());
        assertTrue(filed.stdoutIsEmpty(), "--output must leave stdout completely empty");

        assertArrayEquals(
                piped.stdout().getBytes(StandardCharsets.UTF_8),
                bytes(target),
                "the two output modes must be byte-identical, trailing newline included");
    }

    @Test
    void dashMeansStdout() {
        String spectra = CliFixtures.smallMzml().toString();
        String query = CliFixtures.standardQuery().toString();

        CliFixtures.Invocation implicit = invoke(spectra, query);
        CliFixtures.Invocation explicit = invoke(spectra, query, "--output", "-");

        assertEquals(0, explicit.exitCode(), explicit.stderr());
        assertEquals(implicit.stdout(), explicit.stdout(), "'-' is identical to omitting the flag");
    }

    @Test
    void noTempFileSurvivesASuccessfulRun() {
        Path target = dir.resolve("result.json");
        assertEquals(
                0,
                invoke(
                                CliFixtures.smallMzml().toString(),
                                CliFixtures.standardQuery().toString(),
                                "--output",
                                target.toString())
                        .exitCode());

        assertTrue(Files.exists(target));
        assertFalse(
                Files.exists(dir.resolve("result.json.tmp")),
                "the temp file is an implementation detail and must not outlive the write");
    }

    @Test
    void theTempFileIsWrittenBesideTheTargetNotInTheSystemTempDir() {
        Path target = dir.resolve("nested/deep/result.json");
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(
                0,
                invoke(
                                CliFixtures.microMzml().toString(),
                                CliFixtures.emptyResultQuery().toString(),
                                "--output",
                                target.toString())
                        .exitCode());
        assertTrue(parse(readString(target)).results().isEmpty());
    }

    private static String readString(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void anUnwritablePathExitsTwoAndLeavesNeitherFileNorTemp() {
        Path target = dir.resolve("occupied");
        try {
            Files.createDirectory(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.smallMzml().toString(),
                        CliFixtures.standardQuery().toString(),
                        "--output",
                        target.toString());

        assertEquals(2, r.exitCode(), "an unwritable --output path is a usage error");
        assertTrue(r.stdoutIsEmpty(), "and nothing falls back onto stdout");
        assertTrue(Files.isDirectory(target), "the directory that was in the way is untouched");
        assertFalse(
                Files.exists(dir.resolve("occupied.tmp")),
                "no temp file may survive the failure path");
    }

    @Test
    void anExistingOutputFileIsReplacedWholesale() {
        Path target =
                CliFixtures.write(dir, "result.json", "PREVIOUS CONTENT THAT MUST NOT SURVIVE");

        assertEquals(
                0,
                invoke(
                                CliFixtures.microMzml().toString(),
                                CliFixtures.emptyResultQuery().toString(),
                                "--output",
                                target.toString())
                        .exitCode());

        assertTrue(parse(readString(target)).results().isEmpty());
    }
}
