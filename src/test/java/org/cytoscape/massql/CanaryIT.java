package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CanaryIT {
    @Test
    void thisRunsInTheIntegrationTier() {
        assertEquals(
                "integrationTest",
                System.getProperty("massql.tier"),
                "an *IT ran outside the integration tier -- check the include/exclude filters");
    }
}
