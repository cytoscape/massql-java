package org.cytoscape.massql.io.vendor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.cytoscape.massql.TestPaths;
import org.junit.jupiter.api.Test;

/**
 * Makes the vendoring obligation a <b>build-enforced fact rather than a convention</b>.
 *
 * <p>All twelve files under {@code io/vendor/} carry their headers, so nothing is wrong today. What
 * was missing for a long time was anything that would <i>notice</i> if a thirteenth arrived without
 * one, or if someone tidied a header away — which is the whole reason this class exists.
 *
 * <p>That distinction matters more here than in most tests. MSDK is dual-licensed
 * <b>LGPL-2.1 OR EPL-1.0</b> and this project elects EPL-1.0 — an election that only has effect if
 * it is actually recorded in the file. A vendored source file with no provenance is a compliance
 * problem, not a style problem, and it is exactly the kind of thing a hurried copy-paste produces.
 *
 * <p>Deliberately asserted over the <b>source tree</b> rather than the classpath: the obligation
 * attaches to the files we redistribute, and a compiled class carries no comments at all.
 */
class VendoredProvenanceTest {

    /**
     * The genuinely-vendored {@code .java} files under {@code src/main/.../io/vendor/}.
     *
     * <p><b>Not everything in that package is upstream code</b>, and this test's first version assumed
     * it was — failing on {@code LittleEndianDataInput}, which was written here as a Guava replacement
     * and correctly carries no provenance header. It lives in {@code io/vendor/}
     * because it exists solely to serve the vendored decoder.
     *
     * <p>The exclusion is driven by an explicit <b>"Not vendored"</b> marker in the file's own javadoc
     * rather than by a filename allowlist here. That direction matters: a real vendored file cannot be
     * quietly exempted from the licence assertions by editing this test — someone would have to write
     * "Not vendored" into a file that plainly is, which is a visible lie rather than a silent omission.
     */
    private static List<Path> vendoredSources() {
        Path dir = vendorDir();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> all = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            List<Path> out = new ArrayList<>();
            for (Path p : all) {
                if (head(p).contains("Not vendored")) continue;
                out.add(p);
            }
            assertFalse(out.isEmpty(), "no vendored sources found under " + dir);
            // Guard the guard: if a refactor ever marked everything "Not vendored", the loop above
            // would pass vacuously. 11 upstream files today; the assertion is a floor, not a pin.
            assertTrue(
                    out.size() >= 10,
                    "only "
                            + out.size()
                            + " vendored file(s) detected under "
                            + dir
                            + " -- expected at least 10; has something been marked 'Not vendored' wrongly?");
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Locates the vendor source directory from this test class's own location, so the test does not
     * depend on the working directory the build happens to use.
     */
    private static Path vendorDir() {
        return projectRoot().resolve("src/main/java/org/cytoscape/massql/io/vendor");
    }

    /**
     * The project root, located from this class's own position on disk rather than from the working
     * directory — Gradle and an IDE do not agree on the latter. See {@link TestPaths}.
     */
    private static Path projectRoot() {
        Path root = TestPaths.repositoryRoot();
        assertTrue(
                Files.isDirectory(root.resolve("src/main/java")),
                "expected the project root at " + root);
        return root;
    }

    private static String head(Path p) {
        try {
            // The header is at the top; reading 4 KB avoids matching the string somewhere in the
            // body.
            String all = Files.readString(p);
            return all.length() > 4096 ? all.substring(0, 4096) : all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void everyVendoredFileDeclaresItsUpstreamOrigin() {
        for (Path p : vendoredSources()) {
            String h = head(p);
            String name = p.getFileName().toString();
            assertTrue(
                    h.contains("VENDORED from"),
                    name + " has no 'VENDORED from' provenance header");
            assertTrue(
                    h.contains("github.com/msdk/msdk"),
                    name + " does not name its upstream repository");
            assertTrue(h.contains("path:"), name + " does not record its upstream path");
        }
    }

    @Test
    void everyVendoredFileRecordsTheLicenceElection() {
        // The election is the point: MSDK offers LGPL-2.1 OR EPL-1.0, and a file that does not say
        // which one we took leaves the obligation unresolved.
        for (Path p : vendoredSources()) {
            String h = head(p);
            assertTrue(
                    h.contains("EPL-1.0"),
                    p.getFileName()
                            + " does not record the EPL-1.0 election (MSDK is dual-licensed; "
                            + "a vendored file that omits the election leaves the obligation open)");
        }
    }

    @Test
    void everyVendoredFilePinsTheUpstreamCommit() {
        // Without a commit, "byte-identical to upstream" is unverifiable and a future re-sync has
        // no
        // baseline to diff against.
        for (Path p : vendoredSources()) {
            String h = head(p);
            assertTrue(
                    h.matches("(?s).*commit:\\s*[0-9a-f]{40}.*"),
                    p.getFileName() + " does not pin a 40-character upstream commit SHA");
        }
    }

    @Test
    void everyVendoredFileStatesWhatWasModified() {
        // The whole vendoring argument (see the mzXML reader) rests on the changes being trivial
        // and
        // enumerated. An unstated modification is how a local fix silently becomes permanent.
        for (Path p : vendoredSources()) {
            String h = head(p);
            assertTrue(
                    h.contains("Modified:"), p.getFileName() + " does not state its modifications");
            assertTrue(
                    h.contains("docs/VENDORED.md"),
                    p.getFileName() + " does not point at docs/VENDORED.md for the full list");
        }
    }

    @Test
    void theProvenanceDocumentExistsAndListsEveryVendoredFile() {
        // Every vendored header says "See docs/VENDORED.md for the rationale and the full
        // modification
        // list". That document DID NOT EXIST for a long time -- it was an early deliverable that
        // later steps
        // ticked
        // "docs/VENDORED.md unchanged", and Step 13 lists it as a review artifact, while eleven
        // files
        // pointed at nothing. This assertion is why it exists now.
        Path doc = projectRoot().resolve("docs/VENDORED.md");
        assertTrue(
                Files.exists(doc),
                "docs/VENDORED.md is missing, and every vendored file's header points readers at it: "
                        + doc);

        String text = readAll(doc);
        for (Path p : vendoredSources()) {
            String stem = p.getFileName().toString().replace(".java", "");
            assertTrue(
                    text.contains(stem),
                    "docs/VENDORED.md does not mention the vendored file "
                            + p.getFileName()
                            + " -- the per-file headers and the central record have drifted apart, "
                            + "which is the own failure shape one layer down");
        }
    }

    private static String readAll(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
