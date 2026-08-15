package org.cytoscape.massql.cli;

import static org.cytoscape.massql.cli.CliFixtures.invoke;
import static org.cytoscape.massql.cli.CliFixtures.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainExitCodeTest {
    @TempDir Path dir;

    @Test
    void zeroOnSuccess() {
        CliFixtures.Invocation r =
                invoke(CliFixtures.smallMzml().toString(), CliFixtures.standardQuery().toString());
        assertEquals(0, r.exitCode(), "stderr: " + r.stderr());
        assertFalse(parse(r.stdout()).results().isEmpty(), "stdout carries the rows");
    }

    @Test
    void zeroWithAnEmptyArrayWhenNothingMatched() {
        CliFixtures.Invocation r =
                invoke(
                        CliFixtures.microMzml().toString(),
                        CliFixtures.emptyResultQuery().toString());
        assertEquals(0, r.exitCode(), "an empty result is a valid answer, not a failure");
        assertTrue(parse(r.stdout()).results().isEmpty(), "and the answer is an empty result");
    }

    @Test
    void oneWhenTheContentWillNotParse() {
        Path junk = CliFixtures.write(dir, "junk.mzML", "<notSpectra><at/></notSpectra>\n");
        CliFixtures.Invocation r = invoke(junk.toString(), CliFixtures.standardQuery().toString());
        assertEquals(1, r.exitCode(), "stderr: " + r.stderr());
        assertTrue(r.stdoutIsEmpty(), "nothing on stdout when there is no result");
    }

    @Test
    void twoOnAnUnsupportedQueryWithTheConstructNamedOnStderr() {
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
