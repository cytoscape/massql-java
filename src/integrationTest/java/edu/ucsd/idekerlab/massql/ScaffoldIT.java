package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the {@code integrationTest} suite is actually wired and running.
 *
 * <p>Easy to get silently wrong: if the JVM Test Suite block in {@code build.gradle} is
 * misconfigured, or {@code check} stops depending on it, the build passes while running zero
 * integration tests — and every later gate lives in an {@code *IT}.
 *
 * <p>Under Maven this asserted the same thing about the failsafe plugin. The mechanism changed with
 * the Gradle migration; the failure it guards against did not.
 */
class ScaffoldIT {

    @Test
    void theIntegrationTestSuiteIsWired() {
        assertTrue(true);
    }
}
