package edu.ucsd.idekerlab.massql.result;

import static org.junit.jupiter.api.Assertions.*;

import edu.ucsd.idekerlab.massql.TestPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Makes {@code docs/RESULT_SCHEMA.md} <b>executable</b> rather than decorative.
 *
 * <p>Correction C40 happened because the result contract was <i>specifiable in four places</i> — SPIKE.md,
 * the oracle's `RESULT_SCHEMA.md`, Tech_Step10 and the goldens — and so it drifted into three different
 * answers. The fix was to define it once. This test is what makes "once" hold: it parses the key order out
 * of that document and asserts the serializer emits exactly those keys, in exactly that order.
 *
 * <p><b>Reordering a row in the document fails the build.</b> That is the point — the document is the
 * contract, so it must be able to break the code. Same spirit as {@code VendoredProvenanceTest}, which
 * asserts the vendoring rules rather than trusting them.
 *
 * <p>Deliberately parses the <b>document</b> and not the other way round: a test that read the code and
 * wrote the doc would keep them consistent while letting both drift from the published schema at
 * <a href="https://github.com/cytoscape/cytoscape/issues/26">cytoscape/cytoscape#26</a>, which is the
 * authority the document records.
 */
class ResultSchemaContractTest {

    private static Path projectRoot() {
        Path root = TestPaths.repositoryRoot();
        assertTrue(Files.isDirectory(root.resolve("docs")), "expected the project root at " + root);
        return root;
    }

    private static String schemaDoc() {
        Path doc = projectRoot().resolve("docs/RESULT_SCHEMA.md");
        assertTrue(Files.exists(doc),
                "docs/RESULT_SCHEMA.md is the single definition of the result contract and is missing: " + doc);
        try {
            return Files.readString(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The ordered key list the document declares — the single comma-separated line inside the fenced block
     * that follows "exactly these 12 keys, in this order".
     */
    private static List<String> declaredKeyOrder(String doc) {
        Matcher m = Pattern.compile("in this order\\*\\*:\\s*\\n```\\n([^`]+)\\n```").matcher(doc);
        assertTrue(m.find(),
                "could not find the frozen key-order block in docs/RESULT_SCHEMA.md. It must read "
                        + "'**exactly these 12 keys, in this order**:' followed by a fenced block of "
                        + "comma-separated keys -- that block IS the contract this test enforces.");
        List<String> keys = new ArrayList<>();
        for (String k : m.group(1).trim().split(",")) keys.add(k.trim());
        return keys;
    }

    /** The keys named in the "Field definitions" table's first column, in document order. */
    private static List<String> fieldTableKeys(String doc) {
        int from = doc.indexOf("## Field definitions");
        assertTrue(from > 0, "docs/RESULT_SCHEMA.md has no '## Field definitions' section");
        int to = doc.indexOf("\n## ", from + 1);
        String section = to > 0 ? doc.substring(from, to) : doc.substring(from);

        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\| `([a-z0-9_]+)` \\|").matcher(section);
        while (m.find()) keys.add(m.group(1));
        return keys;
    }

    // ---------------------------------------------------------------- the coupling

    @Test
    void theSerializerEmitsExactlyTheKeysTheDocumentDeclaresInThatOrder() {
        List<String> declared = declaredKeyOrder(schemaDoc());
        assertEquals(declared, List.of(ScanInfoResult.KEYS),
                "docs/RESULT_SCHEMA.md and ScanInfoResult.KEYS disagree. The DOCUMENT is the contract "
                        + "(C40) -- if the change was intentional, it belongs in the document first, and "
                        + "the goldens and Phase-2 app consumers change with it.");
    }

    @Test
    void aSerializedRowCarriesThoseKeysInThatOrder() {
        // End to end through the serializer, so this pins the emitted TEXT and not only the constant.
        ScanInfoResult r = new ScanInfoResult(3, 810.79, 2, 0.011218333333333334, null,
                586278.8533592224, 2, 161140.859375, 736.6370849609375, null, null, 183838.71875);

        List<String> emitted = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z0-9_]+)\":").matcher(ResultJson.write(List.of(r)));
        while (m.find()) emitted.add(m.group(1));

        assertEquals(declaredKeyOrder(schemaDoc()), emitted);
    }

    @Test
    void theDocumentDeclaresTwelveKeys() {
        assertEquals(12, declaredKeyOrder(schemaDoc()).size(),
                "the contract is 12 keys; a change in arity is a breaking change for the Phase-2 app");
    }

    // ---------------------------------------------------------------- the document's internal consistency

    @Test
    void theFieldDefinitionsTableCoversExactlyTheDeclaredKeys() {
        // Catches a doc that adds a key to the order line but never describes it, or describes one it
        // does not emit -- the document drifting from itself, which is how C40 started.
        Set<String> declared = new LinkedHashSet<>(declaredKeyOrder(schemaDoc()));
        Set<String> described = new LinkedHashSet<>(fieldTableKeys(schemaDoc()));
        assertEquals(declared, described,
                "the field-definitions table and the frozen key order disagree in docs/RESULT_SCHEMA.md");
    }

    @Test
    void theFieldTableIsInTheSameOrderAsTheContract() {
        // Not strictly required for correctness, but a table in a different order than the emitted JSON
        // is a reliable source of misreadings for anyone implementing a consumer.
        assertEquals(declaredKeyOrder(schemaDoc()), fieldTableKeys(schemaDoc()));
    }

    @Test
    void theDocumentStatesTheUnionRuleSoTheOneShapeCannotBeQuietlyForgotten() {
        // The specific thing C40 corrected. If someone reintroduces a second shape, they have to delete
        // this sentence to make the test pass -- which is a visible act rather than an omission.
        String doc = schemaDoc();
        assertTrue(doc.contains("SAME 12 keys") || doc.contains("same 12 keys"),
                "docs/RESULT_SCHEMA.md must state that MS1DATA and MS2DATA emit the same 12 keys (C40)");
        assertTrue(doc.contains("mslevel"), "and that mslevel is the discriminator");
    }

    @Test
    void theDocumentRecordsThatBasePeaksAreNeverNull() {
        // The half of C40 that was a join artifact. A future reader tempted to "restore" the old nulls
        // has to contradict the document to do it.
        String doc = schemaDoc();
        int i = doc.indexOf("| `base_peak_i` |");
        assertTrue(i > 0, "no base_peak_i row in the field table");
        String row = doc.substring(i, doc.indexOf('\n', i));
        assertTrue(row.contains("**No**"),
                "base_peak_i must be documented as never null, including for MS1DATA (C40): " + row);
    }
}
