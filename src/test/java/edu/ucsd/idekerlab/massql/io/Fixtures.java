package edu.ucsd.idekerlab.massql.io;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates test fixtures, which live in this repository under {@code src/test/resources/}.
 *
 * <p><b>Correction C26 — this class used to make the whole verification story vacuous.</b> Fixtures
 * previously lived in a sibling {@code ../massql} oracle directory that is never shipped, and a missing
 * one was gated with {@code Assumptions.assumeTrue} — a <b>skip</b>. But {@code ci.yml} checks out only
 * {@code massql-java}, so {@code ../massql} never existed in CI: Step 6's oracle cross-check, Step 8's
 * bit-identity parity and {@code Ms1ScanDocumentOrderIT} — the assertion Step 7 exists for — all skipped
 * silently. A skipped test still counts as one that ran, so the CI test-count guard could not see it
 * either. A green build proved only that the code compiled.
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
     * @throws AssertionError if the fixture is missing — never a skip (C26)
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
     * conditional — that reintroduces the silent-skip failure C26 removed.
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
                    + "publishes no redistribution terms (Correction C26).\n"
                    + "Fetch them with:  bash scripts/fetch-fixtures.sh\n"
                    + "This test must NOT be skipped -- the Ewing mzXML is the only fixture that can\n"
                    + "distinguish document-order ms1scan from precursorScanNum resolution.";
        }
        return "fixture missing from src/test/resources: "
                + relative
                + "\n"
                + "It is committed to this repository (Correction C26). If it has been deleted or\n"
                + "moved, restore it -- do not make the test conditional on its presence.";
    }
}
