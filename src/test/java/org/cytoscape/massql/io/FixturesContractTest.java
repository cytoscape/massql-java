package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Guards: a missing fixture must <b>fail</b>, never skip.
 *
 * <p><b>Why this test exists at all.</b> If {@code Fixtures} gated on {@code Assumptions.assumeTrue},
 * every fixture-dependent test — the oracle cross-checks, the parity assertions,
 * {@code Ms1ScanDocumentOrderIT} — would <b>skip</b> rather than fail when a fixture was absent. A
 * skipped test still counts as one that ran, so a green build would prove only that the code compiled.
 *
 * <p>The fix is easy to undo by accident: one {@code assumeTrue} added to "make CI pass" restores the
 * silent hole. So the contract is asserted here rather than left to a comment. CI additionally asserts
 * the skipped-test count is 0, which catches the same regression from the other side.
 */
class FixturesContractTest {

    @Test
    void aMissingFixtureFailsRatherThanSkipping() {
        // TestAbortedException is what assumeTrue throws -- if it ever comes back, this catches it
        // specifically rather than letting the test report a misleading pass.
        assertThrows(
                AssertionError.class,
                () -> Fixtures.require("data/no_such_fixture_exists.mzML"),
                "a missing fixture must raise AssertionError; if this threw TestAbortedException "
                        + "instead, someone reintroduced assumeTrue and the silent-skip hole is back");
    }

    @Test
    void theMissingFixtureMessageIsActionable() {
        AssertionError e =
                assertThrows(
                        AssertionError.class,
                        () -> Fixtures.require("data/no_such_fixture_exists.mzML"));
        assertTrue(
                e.getMessage().contains("src/test/resources"),
                "the message must say where the fixture belongs. Got: " + e.getMessage());
    }

    @Test
    void committedFixturesResolveOnTheClasspath() {
        // The complement: the happy path genuinely finds the committed fixtures, so the assertions
        // above cannot pass merely because resolution is broken for everything.
        assertTrue(Fixtures.has("data/small.mzML"));
        assertTrue(Fixtures.has("data/small.mzXML"));
        assertTrue(Fixtures.has("data/PlusRise.mgf"));
        assertTrue(Fixtures.has("fixtures/edge/empty_msLevel_tag.mzXML"));
        assertTrue(Fixtures.has("goldens/loader-parity/small.mzML.json.gz"));
        assertFalse(Fixtures.has("data/no_such_fixture_exists.mzML"));
    }

    @Test
    void assumeTrueWouldHaveBeenSilent() {
        // Documents, executably, why the AssertionError above matters: this is what the old code
        // did.
        // JUnit reports a TestAbortedException as "skipped", not "failed" -- which is precisely how
        // an empty verification suite reported success for four steps.
        assertThrows(
                TestAbortedException.class,
                () ->
                        org.junit.jupiter.api.Assumptions.assumeTrue(
                                false, "this is a SKIP, not a failure"));
    }
}
