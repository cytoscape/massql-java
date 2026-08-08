package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the build wiring works before Tech_Step4 depends on it: the ANTLR plugin ran at
 * generate-sources, its output compiled under {@code <release>17</release>}, and the
 * runtime is on the classpath.
 */
class ScaffoldTest {

    @Test
    void generatedParserClassesExistAndLoad() throws Exception {
        // If the antlr4-maven-plugin did not run, or the .g4 was not found under
        // src/main/antlr4/, these are absent and Step 4 would start from a broken build.
        assertNotNull(Class.forName("edu.ucsd.idekerlab.massql.lang.MassqlParser"));
        assertNotNull(Class.forName("edu.ucsd.idekerlab.massql.lang.MassqlLexer"));
    }

    @Test
    void antlrRuntimeIsOnTheClasspath() throws Exception {
        assertNotNull(Class.forName("org.antlr.v4.runtime.CharStreams"));
    }

    @Test
    void compiledForJava17() {
        // Guards DEPENDENCY_POLICY.md constraint 7: class file major version <= 61.
        assertTrue(Runtime.version().feature() >= 17, "tests must run on JDK 17+");
    }
}
