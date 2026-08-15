package org.cytoscape.massql.testsupport;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TestPaths {
    private static final String ROOT_MARKER = "settings.gradle";

    private TestPaths() {}

    public static Path repositoryRoot() {
        Path start = codeSourceOf(TestPaths.class);
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(ROOT_MARKER))) {
                return dir;
            }
        }
        throw new AssertionError(
                "cannot locate the repository root: no ancestor of "
                        + start
                        + " contains "
                        + ROOT_MARKER);
    }

    private static Path codeSourceOf(Class<?> type) {
        try {
            return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError("class location is not a usable file path: " + type, e);
        }
    }
}
