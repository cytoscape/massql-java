package edu.ucsd.idekerlab.massql.cli;

import static edu.ucsd.idekerlab.massql.cli.CliFixtures.invoke;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * All four exit codes, each demonstrated rather than assumed.
 *
 * <p>This class exists because of the seam Correction <b>C42</b> added: {@code Main.run} returns the
 * code and only {@code main} calls {@code System.exit}. Without that split none of these assertions
 * could be made at all — a test cannot observe an exit code from a method that terminates the JVM.
 *
 * <p>The pairing that matters most is <b>0-with-{@code []}</b>. A query that matched nothing is a
 * successful run with an empty answer, and scripts branch on the exit code: reporting failure there
 * would make "no matches" indistinguishable from "the tool broke".
 */
class MainExitCodeTest {

    @TempDir Path dir;

    @Test
    void zeroOnSuccess() {
        CliFixtures.Invocation r =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString());
        assertEquals(0, r.exitCode(), "stderr: " + r.stderr());
        assertTrue(r.stdout().startsWith("["), "stdout carries the JSON array");
    }

    @Test
    void zeroWithAnEmptyArrayWhenNothingMatched() {
        // micro.mzML + test_micro_edge.massql is the committed evidence for C37's strict window: a
        // peak sits exactly on the bound and is therefore excluded. The golden for this pair is
        // deliberately `[]`, and Tech_Step12 compares against it.
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.microMzml().toString(),
                        CliFixtures.emptyResultQuery().toString());
        assertEquals(0, r.exitCode(), "an empty result is a valid answer, not a failure");
        assertEquals("[]", r.stdout().strip(), "and the answer is an empty JSON array");
    }

    @Test
    void oneWhenTheContentWillNotParse() {
        // Readable, non-empty, and complete nonsense -- so it clears the usage gate and fails on
        // CONTENT. This is the half of the C42 split that must not be reported as a usage error.
        Path junk = CliFixtures.write(dir, "junk.mzML", "<notSpectra><at/></notSpectra>\n");
        CliFixtures.Invocation r = invoke(junk.toString(), CliFixtures.standardQuery().toString());
        assertEquals(1, r.exitCode(), "stderr: " + r.stderr());
        assertTrue(r.stdoutIsEmpty(), "nothing on stdout when there is no result");
    }

    @Test
    void twoOnAnUnsupportedQueryWithTheConstructNamedOnStderr() {
        // Tech_Step12 asserts the construct name appears, so a generic "syntax error" is not
        // enough:
        // the whole reason the grammar admits out-of-scope constructs is to be able to say WHICH
        // one.
        Path q =
                CliFixtures.write(
                        dir,
                        "unsupported.massql",
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=formula(C10)\n");
        CliFixtures.Invocation r = invoke(CliFixtures.smallMzml().toString(), q.toString());

        assertEquals(2, r.exitCode());
        assertTrue(
                r.stderr().contains("formula()"),
                "the offending construct must be named, got: " + r.stderr());
        assertTrue(r.stdoutIsEmpty(), "stdout stays clean on a rejected query");
    }

    @Test
    void twoOnAUsageError() {
        assertEquals(2, invoke().exitCode(), "no arguments at all");
    }

    @Test
    void theFourCodesAreDistinct() {
        // Guards against a refactor that collapses 1 and 2 back together -- which is easy, because
        // the exception type does not distinguish them and never did.
        Path junk = CliFixtures.write(dir, "junk.mzML", "<notSpectra/>\n");
        Path bad =
                CliFixtures.write(dir, "bad.massql", "QUERY scaninfo(MS2DATA) WHERE MOBILITY=1\n");

        int success =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString())
                        .exitCode();
        int content = invoke(junk.toString(), CliFixtures.standardQuery().toString()).exitCode();
        int usage = invoke(CliFixtures.smallMzml().toString(), bad.toString()).exitCode();

        assertEquals(0, success);
        assertEquals(1, content);
        assertEquals(2, usage);
    }
}
