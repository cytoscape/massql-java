package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

class FixturesContractTest {
    @Test
    void aMissingFixtureFailsRatherThanSkipping() {
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
        assertTrue(Fixtures.has("data/small.mzML"));
        assertTrue(Fixtures.has("data/small.mzXML"));
        assertTrue(Fixtures.has("data/PlusRise.mgf"));
        assertTrue(Fixtures.has("fixtures/edge/empty_msLevel_tag.mzXML"));
        assertTrue(Fixtures.has("goldens/loader-parity/small.mzML.json.gz"));
        assertFalse(Fixtures.has("data/no_such_fixture_exists.mzML"));
    }

    @Test
    void assumeTrueWouldHaveBeenSilent() {
        assertThrows(
                TestAbortedException.class,
                () ->
                        org.junit.jupiter.api.Assumptions.assumeTrue(
                                false, "this is a SKIP, not a failure"));
    }
}
