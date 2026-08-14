package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Proves an {@code *IT} lands in the integration tier rather than the fast one.
 *
 * <p>The tier is selected by a filename glob, so a misnamed file runs in the wrong tier or in
 * neither. This class covers only the first half of that: a test cannot detect its own
 * non-execution, so the {@code integrationTest} task itself fails the build when it matches zero
 * tests, and {@code TierBoundaryTest} checks the filenames.
 *
 * <p>Under Maven this asserted the same thing about the failsafe plugin. The mechanism changed
 * twice; the failure it guards against did not.
 */
class CanaryIT {

    @Test
    void thisRunsInTheIntegrationTier() {
        assertEquals(
                "integrationTest",
                System.getProperty("massql.tier"),
                "an *IT ran outside the integration tier -- check the include/exclude filters");
    }
}
