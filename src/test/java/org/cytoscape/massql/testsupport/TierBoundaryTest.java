package org.cytoscape.massql.testsupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class TierBoundaryTest {
    private static final Set<String> ALLOWED = Set.of("CliFixtures");

    private static final Pattern DECLARATION =
            Pattern.compile(
                    "^\\s*(?:public\\s+|final\\s+|abstract\\s+|static\\s+)*"
                            + "(?:class|record|enum|interface)\\s+(\\w+)",
                    Pattern.MULTILINE);

    @Test
    void anIntegrationTestReachesOnlyIntoTestsupport() {
        Map<String, Path> declaredBy = declarations();
        List<String> violations = new ArrayList<>();

        for (Path it : sources()) {
            if (!isIntegrationTier(it)) continue;
            String code = codeOnly(read(it));
            declaredBy.forEach(
                    (name, source) -> {
                        if (source.equals(it) || isTestsupport(source) || ALLOWED.contains(name)) {
                            return;
                        }
                        if (references(code, name)) {
                            violations.add(
                                    it.getFileName() + " -> " + name + ", declared in " + source);
                        }
                    });
        }

        assertTrue(
                violations.isEmpty(),
                "an integration test may use testsupport and production code only: " + violations);
    }

    @Test
    void aUnitTestNeverReachesIntoAnIntegrationTest() {
        Map<String, Path> declaredBy = declarations();
        List<String> violations = new ArrayList<>();

        for (Path unit : sources()) {
            if (isIntegrationTier(unit)) continue;
            String code = codeOnly(read(unit));
            declaredBy.forEach(
                    (name, source) -> {
                        if (isIntegrationTier(source) && references(code, name)) {
                            violations.add(
                                    unit.getFileName() + " -> " + name + ", declared in " + source);
                        }
                    });
        }

        assertTrue(
                violations.isEmpty(),
                "the unit tier must not depend on the IT tier: " + violations);
    }

    @Test
    void everyTestFileIsNamedForTheTierItBelongsIn() {
        List<String> misnamed = new ArrayList<>();
        for (Path p : sources()) {
            String name = p.getFileName().toString();
            boolean hasTests = codeOnly(read(p)).contains("@Test");
            if (hasTests && !name.endsWith("IT.java") && !name.endsWith("Test.java")) {
                misnamed.add(
                        name
                                + " has @Test but ends in neither IT.java nor Test.java, so it "
                                + "runs in NEITHER tier");
            }
        }
        assertTrue(misnamed.isEmpty(), String.join("; ", misnamed));
    }

    private static boolean isIntegrationTier(Path p) {
        return p.getFileName().toString().endsWith("IT.java");
    }

    private static boolean isTestsupport(Path p) {
        return p.getParent().getFileName().toString().equals("testsupport");
    }

    private static boolean references(String code, String name) {
        return Pattern.compile("(?<![.\\w])" + Pattern.quote(name) + "\\b").matcher(code).find();
    }

    private static String codeOnly(String src) {
        String s = src.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"");
        s = s.replaceAll("//[^\n]*", " ");
        return s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static Map<String, Path> declarations() {
        Map<String, Path> declaredBy = new HashMap<>();
        for (Path p : sources()) {
            Matcher m = DECLARATION.matcher(read(p));
            while (m.find()) {
                declaredBy.putIfAbsent(m.group(1), p);
            }
        }
        return declaredBy;
    }

    private static List<Path> sources() {
        Path root = TestPaths.repositoryRoot();
        List<Path> all = new ArrayList<>();
        for (String tree : List.of("src/test/java", "cli/src/test/java")) {
            try (Stream<Path> walk = Files.walk(root.resolve(tree))) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(all::add);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot walk " + tree, e);
            }
        }

        assertFalse(all.isEmpty(), "no test sources found under " + root);
        return all;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }
}
