package org.cytoscape.massql.io;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates test fixtures, which live in this repository under {@code src/test/resources/}.
 *
 * <p>⛔ <b>A missing fixture throws; it never skips.</b> Gating on {@code Assumptions.assumeTrue} would
 * make the whole verification story vacuous: the oracle cross-checks, the bit-identity parity gate and
 * {@code Ms1ScanDocumentOrderIT} would all skip silently, and a skipped test still counts as one that
 * ran. A green build would prove only that the code compiled.
 *
 * <p>So: fixtures are committed here, and a missing fixture is a <b>hard failure</b>. There is no skip
 * path. If you are tempted to add one, re-read the paragraph above.
 *
 * <p>Resolution is via the classpath, matching {@code lang/Corpus}. Test resources are unpacked as real
 * files in the build output, so the returned {@link Path} can be memory-mapped — which the mzML and
 * mzXML readers require and a jar-embedded resource could not satisfy.
 *
 * <p><b>The one exception</b> is the two Ewing-lab files ({@code data/DP00570_F02.*}), gitignored because
 * ewinglab.org states no redistribution terms. {@code scripts/fetch-fixtures.sh} downloads them and CI
 * caches the result; when they are absent the failure message says exactly that, rather than letting the
 * test report success.
 */
final class Fixtures {

    private Fixtures() {}

    /** Fixtures that are fetched rather than committed — drives the failure message only. */
    private static final String FETCHED_PREFIX = "data/DP00570_F02.";

    /**
     * Resolves a fixture path, failing the test if it is absent.
     *
     * @param relative path under {@code src/test/resources}, e.g. {@code "data/small.mzML"}
     * @throws AssertionError if the fixture is missing — never a skip
     */
    static Path require(String relative) {
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

    /**
     * True if a fixture is present, without failing.
     *
     * <p>For genuinely optional data only. Do <b>not</b> use it to make a required assertion
     * conditional — that reintroduces the silent-skip failure this replaced.
     */
    static boolean has(String relative) {
        return Fixtures.class.getClassLoader().getResource(relative) != null;
    }

    private static String explainMissing(String relative) {
        if (relative.startsWith(FETCHED_PREFIX)) {
            return "fixture missing: "
                    + relative
                    + "\n"
                    + "This is one of the two Ewing-lab files, gitignored because ewinglab.org\n"
                    + "publishes no redistribution terms.\n"
                    + "Fetch them with:  bash scripts/fetch-fixtures.sh\n"
                    + "This test must NOT be skipped -- the Ewing mzXML is the only fixture that can\n"
                    + "distinguish document-order ms1scan from precursorScanNum resolution.";
        }
        return "fixture missing from src/test/resources: "
                + relative
                + "\n"
                + "It is committed to this repository. If it has been deleted or\n"
                + "moved, restore it -- do not make the test conditional on its presence.";
    }
}
