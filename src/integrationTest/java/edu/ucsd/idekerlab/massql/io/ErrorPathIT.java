package edu.ucsd.idekerlab.massql.io;

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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import edu.ucsd.idekerlab.massql.Massql;
import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.MassqlParseException;
import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * Every documented error path, per format — the differential
 *
 * <h2>What "fails cleanly" has to mean</h2>
 *
 * <p>Two properties, and the second is the one that bites:
 *
 * <ul>
 *   <li>The failure is a {@link MassqlException} — <b>not</b> a leaked {@code XMLStreamException},
 *       {@code NumberFormatException} or {@code NullPointerException}. A caller can only handle what
 *       the API declares. In an embedding application an unexpected runtime exception surfaces as a
 *       broken feature rather than as a bad input file.
 *   <li><b>No partial results.</b> A reader that returns the rows it managed to parse before hitting
 *       damage is worse than one that throws: the caller gets a plausible short answer with no
 *       indication anything was lost. That is the failure shape where a parser stopped
 *       early and stayed quiet.
 * </ul>
 */
class ErrorPathIT {

    /** The standard micro query, which matches 2 of micro's scans when the file is intact. */
    private static String microQuery() {
        return queryText("test_micro");
    }

    // ------------------------------------------------------------------ truncated / malformed

    /**
     * A file cut mid-spectrum throws, in all three formats.
     *
     * <p>Each fixture is truncated to 60% of its bytes, which lands inside a spectrum rather than on a
     * record boundary — the case a reader is most likely to paper over.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void aTruncatedFileThrowsRatherThanReturningWhatItManagedToRead(
            String name, @TempDir Path dir) {
        Path intact = Fixtures.require("fixtures/micro/" + name);
        byte[] all = readBytes(intact);
        Path cut = dir.resolve(name);
        writeBytes(cut, java.util.Arrays.copyOf(all, (int) (all.length * 0.6)));

        // Establish the baseline first: if the intact file did not produce rows, "no rows" below
        // would prove nothing.
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

    /** An empty file names the path and fails as a {@code MassqlException}, not as an empty result. */
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

    /**
     * Markup whose root is neither mzML nor mzXML fails, and the {@code .mzML} name does not save it.
     *
     * <p>Format is sniffed from <b>content</b>, never the extension (Step 6): the fixtures disagree on
     * case — {@code small.mzXML} from msconvert, {@code DP00570_F02.mzxml} from Ewing — so trusting the
     * suffix was ruled out from the start.
     */
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

    /**
     * ⚠ Text with <b>no</b> markup is a peak list by definition, and yields zero rows rather than an
     * error — the documented rule, not an oversight.
     *
     * <p>Step 6: <i>"First non-blank line begins with {@code BEGIN IONS}, or the file contains no
     * {@code <}, → MGF."</i> MGF has no magic header, so anything unmarked is treated as one; an MGF
     * with no {@code BEGIN IONS} block simply has no spectra. {@code FormatSniffTest} pins the sniff
     * itself, and this pins what the whole pipeline does with the result.
     *
     * <p>Asserted deliberately so the leniency stays a decision. If it is ever revisited, this test
     * fails and names the rule, rather than the change slipping through as "garbage now errors".
     */
    @Test
    void textWithNoMarkupIsAnEmptyPeakListNotAnError(@TempDir Path dir) {
        Path plain = dir.resolve("notes.mzML");
        writeString(
                plain, "this file has no angle brackets, so it is a peak list with no blocks\n");

        assertTrue(
                Massql.run(microQuery(), plain, null).isEmpty(),
                "Step 6's rule: no '<' means MGF, and an MGF with no BEGIN IONS has no spectra");
    }

    // ------------------------------------------------------------------ missing paths

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

    // ------------------------------------------------------------------ query errors

    /**
     * An unsupported function names the construct it rejected.
     *
     * <p>Naming it is the whole point: {@code scaninfo} is the only supported function in this spike,
     * so a user who writes {@code scansum} needs the message to say {@code scansum} rather than
     * "syntax error at line 1".
     */
    @Test
    void anUnsupportedFunctionNamesTheOffendingConstruct() {
        MassqlParseException e =
                assertThrows(
                        MassqlParseException.class, () -> Massql.parse("QUERY scansum(MS2DATA)"));
        assertTrue(
                e.getMessage().contains("scansum"),
                () -> "the message must name what it rejected: " + e.getMessage());
    }

    /**
     * {@code QUERY scaninfo WHERE …} — the function-call form is required, and the message says so.
     *
     * <p>Step 4 §4: this is the most likely thing a user carries over from prose descriptions of MassQL,
     * so the parse error explains the form rather than pointing at a token.
     */
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

    /** {@link MassqlParseException} is a {@link MassqlException}, so one catch handles both. */
    @Test
    void aParseFailureIsAlsoAMassqlException() {
        assertThrows(MassqlException.class, () -> Massql.parse("QUERY scansum(MS2DATA)"));
    }

    // ------------------------------------------------------------------ non-errors

    /**
     * A query that matches nothing is an <b>empty list</b>, not a failure.
     *
     * <p>Runs on all three formats: "no matches" must not be a format-specific behaviour.
     */
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

    /**
     * An empty {@code msLevel} tag drops the scan silently — the reference's behaviour, not a failure.
     *
     * <p>verified against MassQL: pyteomics converts {@code msLevel=""} to
     * {@code None}, and MassQL tests {@code mslevel == 1} / {@code == 2}, so {@code None} matches
     * neither branch and the scan contributes nothing. Of this file's 10 scans, 8 are {@code msLevel=""}
     * and only scans 4 (MS2) and 8 (MS1) survive.
     *
     * <p>⚠ Not a default of 1, not a diagnostic, not an error. This is in the error-path suite precisely
     * because the intuitive handling — defaulting, or raising — would both be wrong.
     */
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

    /**
     * A failed read leaves nothing half-open — the next read of a good file still works.
     *
     * <p>Cheap to assert and easy to get wrong: a reader that throws after mapping a file but before
     * registering it for close leaks a descriptor per failure, which only shows up much later as
     * "too many open files" somewhere unrelated. {@code ResourceLeakIT} covers the volume case; this
     * covers the error case.
     */
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

    /** No error path may write to a stream — the SDK is silent, and the CLI owns all output. */
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

    // ------------------------------------------------------------------ helpers

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
