package org.cytoscape.massql;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the repository root for tests that read committed files rather than classpath resources — the
 * vendored sources under {@code io/vendor/}.
 *
 * <p><b>Why this exists rather than a relative path.</b> The working directory differs between Gradle and
 * an IDE run configuration, so a test cannot trust {@code user.dir}. Deriving the root from the class
 * file with a fixed number of {@code getParent()} calls is equally unsafe, since the depth of the output
 * directory is a build-layout detail.
 *
 * <p>So the depth is not hardcoded at all: this walks <i>up</i> until it finds the directory holding
 * {@code settings.gradle}. That survives any future layout change, and it is why the marker is the
 * settings file specifically — {@code cli/} has its own {@code build.gradle}, so a
 * {@code build.gradle} marker would stop at the subproject and resolve the wrong root.
 */
public final class TestPaths {

    /** Unique to the true root; {@code build.gradle} is not — see the class javadoc. */
    private static final String ROOT_MARKER = "settings.gradle";

    private TestPaths() {}

    /**
     * The repository root, derived from the calling test's own location on disk.
     *
     * @throws AssertionError if no ancestor directory contains {@value #ROOT_MARKER}, which means
     *     the tests are running from somewhere this helper cannot reason about — a hard failure
     *     rather than a wrong path that surfaces later as a confusing missing-file error
     */
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
