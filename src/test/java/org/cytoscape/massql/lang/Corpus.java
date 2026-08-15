package org.cytoscape.massql.lang;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Corpus {
    static final int EXPECTED_SIZE = 46;

    static final int EXPECTED_PARSE = 15;
    static final int EXPECTED_REJECT = 31;

    record Entry(String file, String query, boolean shouldParse, java.util.Set<String> candidates) {
        @Override
        public String toString() {
            return file;
        }
    }

    private Corpus() {}

    private static Path dir() {
        try {
            return Paths.get(
                    Objects.requireNonNull(
                                    Corpus.class.getClassLoader().getResource("reference_parses"),
                                    "reference_parses/ missing from test resources")
                            .toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Pattern QUERY =
            Pattern.compile("\"query\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    static List<Entry> load() {
        Path base = dir();
        List<Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(base.resolve("corpus-manifest.tsv"))) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                String json = Files.readString(base.resolve(f[0]));
                Matcher m = QUERY.matcher(json);
                if (!m.find()) throw new IllegalStateException("no query field in " + f[0]);
                String q = m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
                java.util.Set<String> cands =
                        "-".equals(f[2])
                                ? java.util.Set.of()
                                : new java.util.LinkedHashSet<>(
                                        java.util.Arrays.asList(f[2].split(";")));
                out.add(new Entry(f[0], q, "parse".equals(f[1]), cands));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
