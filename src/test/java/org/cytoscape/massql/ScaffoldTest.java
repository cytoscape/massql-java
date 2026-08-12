package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Proves the build wiring works before the parser depends on it: {@code generateGrammarSource} ran,
 * its output compiled under Java 17, and {@code antlr4-runtime} is on the classpath.
 */
class ScaffoldTest {

    @Test
    void generatedParserClassesExistAndLoad() throws Exception {
        // If generateGrammarSource did not run, or the .g4 was not found under src/main/antlr/,
        // these are absent and the parser would start from a broken build. Note the generated
        // classes
        // only carry a package because build.gradle passes -package explicitly: Gradle's antlr
        // plugin mirrors the directory layout into the output folder but emits no package
        // declaration, unlike antlr4-maven-plugin, which derived it from the path.
        assertNotNull(Class.forName("org.cytoscape.massql.lang.MassqlParser"));
        assertNotNull(Class.forName("org.cytoscape.massql.lang.MassqlLexer"));
    }

    @Test
    void antlrRuntimeIsOnTheClasspath() throws Exception {
        assertNotNull(Class.forName("org.antlr.v4.runtime.CharStreams"));
    }

    @Test
    void compiledForJava17() {
        assertTrue(Runtime.version().feature() >= 17, "tests must run on JDK 17+");
    }
}
