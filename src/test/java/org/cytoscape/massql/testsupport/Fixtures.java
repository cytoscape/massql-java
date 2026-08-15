package org.cytoscape.massql.testsupport;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Fixtures {
    private Fixtures() {}

    public static Path require(String relative) {
        URL url = Fixtures.class.getClassLoader().getResource(relative);
        if (url == null) {
            throw new AssertionError(explainMissing(relative));
        }
        Path p;
        try {
            p = Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("fixture URL is not a usable file path: " + url, e);
        }
        if (!Files.exists(p)) {
            throw new AssertionError(explainMissing(relative));
        }
        return p;
    }

    public static boolean has(String relative) {
        return Fixtures.class.getClassLoader().getResource(relative) != null;
    }

    private static String explainMissing(String relative) {
        return "fixture missing from src/test/resources: "
                + relative
                + "\n"
                + "It is committed to this repository. If it has been deleted or\n"
                + "moved, restore it -- do not make the test conditional on its presence.";
    }
}
