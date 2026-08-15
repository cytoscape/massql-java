package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.MassqlParseException;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ErrorPathIT {
    private static String microQuery() {
        return queryText("test_micro");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void aTruncatedFileThrowsRatherThanReturningWhatItManagedToRead(
            String name, @TempDir Path dir) {
        Path intact = Fixtures.require("fixtures/micro/" + name);
        byte[] all = readBytes(intact);
        Path cut = dir.resolve(name);
        writeBytes(cut, java.util.Arrays.copyOf(all, (int) (all.length * 0.6)));

        assertEquals(2, Massql.run(microQuery(), intact, null).size(), name + " intact");

        Throwable t =
                assertThrows(
                        Throwable.class,
                        () -> Massql.run(microQuery(), cut, null),
                        name
                                + " truncated at 60% returned normally -- a reader that yields the rows it"
                                + " parsed before the damage hands the caller a short answer with no"
                                + " signal anything was lost");

        assertTrue(
                t instanceof MassqlException,
                () ->
                        name
                                + " threw "
                                + t.getClass().getName()
                                + " ("
                                + t.getMessage()
                                + ") rather"
                                + " than MassqlException. A caller can only handle what the API declares;"
                                + " wrap it at the reader boundary.");
    }

    @Test
    void anEmptyFileIsAnErrorNotAnEmptyResult(@TempDir Path dir) {
        Path empty = dir.resolve("empty.mzML");
        writeBytes(empty, new byte[0]);

        MassqlException e =
                assertThrows(MassqlException.class, () -> Massql.run(microQuery(), empty, null));
        assertTrue(
                e.getMessage().contains("empty.mzML"),
                () -> "the message must name the file: " + e.getMessage());
    }

    @Test
    void unknownMarkupFailsEvenWithAPlausibleExtension(@TempDir Path dir) {
        Path fake = dir.resolve("looks-real.mzML");
        writeString(fake, "<html><body>not a spectra file</body></html>\n");

        MassqlException e =
                assertThrows(MassqlException.class, () -> Massql.run(microQuery(), fake, null));
        assertTrue(
                e.getMessage().contains("looks-real.mzML"),
                () -> "the message must name the file it could not identify: " + e.getMessage());
    }

    @Test
    void textWithNoMarkupIsAnEmptyPeakListNotAnError(@TempDir Path dir) {
        Path plain = dir.resolve("notes.mzML");
        writeString(
                plain, "this file has no angle brackets, so it is a peak list with no blocks\n");

        assertTrue(
                Massql.run(microQuery(), plain, null).isEmpty(),
                "the sniff rule: no '<' means MGF, and an MGF with no BEGIN IONS has no spectra");
    }

    @Test
    void aMissingFileNamesThePath(@TempDir Path dir) {
        Path missing = dir.resolve("no-such-file.mzML");
        MassqlException e =
                assertThrows(MassqlException.class, () -> Massql.run(microQuery(), missing, null));
        assertTrue(
                e.getMessage().contains("no-such-file.mzML"),
                () -> "the message must name the path: " + e.getMessage());
    }

    @Test
    void aDirectoryIsNotAFile(@TempDir Path dir) {
        assertThrows(
                MassqlException.class,
                () -> Massql.run(microQuery(), dir, null),
                "a directory must fail as clearly as a missing file, not as an obscure IO error");
    }

    @Test
    void anUnsupportedFunctionNamesTheOffendingConstruct() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class, () -> Massql.parse("QUERY scansum(MS2DATA)"));
        assertTrue(
                e.getMessage().contains("scansum"),
                () -> "the message must name what it rejected: " + e.getMessage());
    }

    @Test
    void scaninfoWithoutItsArgumentListExplainsTheFunctionCallForm() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class,
                        () -> Massql.parse("QUERY scaninfo WHERE MS2PROD=200.0"));
        String m = e.getMessage();
        assertTrue(
                m.contains("scaninfo(")
                        || m.toLowerCase(java.util.Locale.ROOT).contains("function"),
                () ->
                        "the message must explain the scaninfo(MS2DATA) form, not just name a bad token: "
                                + m);
    }

    @Test
    void aParseFailureIsAlsoAMassqlException() {
        assertThrows(MassqlException.class, () -> Massql.parse("QUERY scansum(MS2DATA)"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void aQueryThatMatchesNothingReturnsAnEmptyList(String name) {
        List<ScanInfoResult> rows =
                Massql.run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=9999.0",
                        Fixtures.require("fixtures/micro/" + name),
                        null);
        assertTrue(rows.isEmpty(), () -> name + ": expected no matches, got " + rows.size());
    }

    @Test
    void anEmptyMsLevelTagDropsTheScanWithoutFailing() {
        Path p = Fixtures.require("fixtures/edge/empty_msLevel_tag.mzXML");

        List<ScanInfoResult> rows = Massql.run("QUERY scaninfo(MS2DATA)", p, null);
        assertEquals(
                1,
                rows.size(),
                "only scan 4 has a usable msLevel of 2; the 8 empty-msLevel scans contribute zero rows"
                        + "");

        List<ScanInfoResult> ms1 = Massql.run("QUERY scaninfo(MS1DATA)", p, null);
        assertEquals(1, ms1.size(), "and only scan 8 survives as MS1");
    }

    @Test
    void aFailedReadDoesNotPoisonTheNextOne(@TempDir Path dir) {
        Path bad = dir.resolve("bad.mzML");
        writeString(bad, "<mzML><garbage></mzML>");

        for (int i = 0; i < 50; i++) {
            assertThrows(MassqlException.class, () -> Massql.run(microQuery(), bad, null));
        }

        assertEquals(
                2,
                Massql.run(microQuery(), Fixtures.require("fixtures/micro/micro.mzML"), null)
                        .size(),
                "50 failed reads must leave the reader able to read a good file");
    }

    @Test
    void theSdkPrintsNothingOnAnyErrorPath(@TempDir Path dir) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        java.io.PrintStream originalErr = System.err;

        try {
            System.setOut(new java.io.PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));

            Path missing = dir.resolve("gone.mzML");
            assertThrows(MassqlException.class, () -> Massql.run(microQuery(), missing, null));
            assertThrows(MassqlParseException.class, () -> Massql.parse("QUERY scansum(MS2DATA)"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(0, out.size(), () -> "the SDK wrote to stdout: " + out);
        assertFalse(
                err.toString(StandardCharsets.UTF_8).contains("massql"),
                () -> "the SDK wrote a diagnostic to stderr: " + err);
    }

    private static String queryText(String name) {
        try {
            return Files.readString(Fixtures.require("goldens/queries/" + name + ".massql"))
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytes(Path p, byte[] b) {
        try {
            Files.write(p, b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeString(Path p, String s) {
        writeBytes(p, s.getBytes(StandardCharsets.UTF_8));
    }
}
