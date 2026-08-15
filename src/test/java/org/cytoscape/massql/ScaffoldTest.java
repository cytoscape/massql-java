package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScaffoldTest {
    @Test
    void generatedParserClassesExistAndLoad() throws Exception {
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
