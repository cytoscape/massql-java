package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Proves failsafe is actually bound and running. Easy to get silently wrong: if the
 * *IT.java include pattern or the failsafe executions block is misconfigured, `mvn verify`
 * passes while running zero integration tests -- and every later gate lives in an *IT.
 */
class ScaffoldIT {

    @Test
    void failsafeIsWired() {
        assertTrue(true);
    }
}
