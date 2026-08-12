package org.cytoscape.massql.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The published JSON contract: key set, key order, null rendering, and round-trip bit-exactness.
 *
 * <p><b>One shape.</b> Both {@code MS1DATA} and {@code MS2DATA} emit the same 12 keys in the same order.
 * A key that does not apply to a row is <i>present</i> with the value {@code null}, never omitted.
 */
class ResultJsonTest {

    /** A fully-populated MS2 row, using the first record of {@code small_mzml_results.json}. */
    private static ScanInfoResult ms2Row() {
        return new ScanInfoResult(
                3,
                810.79,
                2,
                0.011218333333333334,
                null,
                586278.8533592224,
                2,
                161140.859375,
                736.6370849609375,
                null,
                null,
                183838.71875);
    }

    /** An MS1 row: precursor fields null, base peaks REAL. */
    private static ScanInfoResult ms1Row() {
        return new ScanInfoResult(
                1,
                null,
                null,
                0.004935,
                null,
                69381840.0,
                1,
                1471224.875,
                810.4154747204038,
                null,
                null,
                null);
    }

    /** Keys in the order they appear in the JSON text, so ORDER is pinned and not just membership. */
    private static List<String> keysInOrder(String json) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z0-9_]+)\":").matcher(json);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static final List<String> EXPECTED_12 =
            List.of(
                    "scan",
                    "precmz",
                    "ms1scan",
                    "rt",
                    "charge",
                    "tic",
                    "mslevel",
                    "base_peak_i",
                    "base_peak_mz",
                    "ms1_i",
                    "ms1_precmz",
                    "ms1_base_peak_i");

    // ---------------------------------------------------------------- shape

    @Test
    void anMs2RowEmitsExactlyTheTwelveKeysInOrder() {
        assertEquals(EXPECTED_12, keysInOrder(ResultJson.write(List.of(ms2Row()))));
    }

    @Test
    void anMs1RowEmitsTheSAMEtwelveKeysInTheSameOrder() {
        // ⛔ There is one shape: an MS1 row does not omit precmz/ms1scan/charge.
        assertEquals(
                EXPECTED_12,
                keysInOrder(ResultJson.write(List.of(ms1Row()))),
                "MS1DATA and MS2DATA emit the same 12 keys, discriminated by mslevel");
    }

    @Test
    void theTwoShapesAreLiterallyIdenticalInKeyStructure() {
        // Stated as a property rather than two separate lists, so a future edit cannot drift one
        // apart
        // from the other.
        assertEquals(
                keysInOrder(ResultJson.write(List.of(ms2Row()))),
                keysInOrder(ResultJson.write(List.of(ms1Row()))));
    }

    @Test
    void inapplicableFieldsArePRESENTwithJsonNullNotOmitted() {
        String json = ResultJson.write(List.of(ms1Row()));
        assertTrue(
                json.contains("\"precmz\":null"),
                "precmz must be present and null, not omitted: " + json);
        assertTrue(json.contains("\"ms1scan\":null"));
        assertTrue(json.contains("\"charge\":null"));
        assertTrue(json.contains("\"ms1_i\":null"));
        assertTrue(json.contains("\"ms1_precmz\":null"));
        assertTrue(json.contains("\"ms1_base_peak_i\":null"));
    }

    @Test
    void anMs1RowsBasePeaksAreRealValuesNotNull() {
        // The other half: a null here would be a join artifact, not a property of an MS1 scan.
        String json = ResultJson.write(List.of(ms1Row()));
        assertTrue(json.contains("\"base_peak_i\":1471224.875"), json);
        assertTrue(json.contains("\"base_peak_mz\":810.4154747204038"), json);
        assertFalse(
                json.contains("\"base_peak_i\":null"),
                "a survey scan has a base peak -- issue #26 marks this 'Can be null? No'");
    }

    @Test
    void mslevelIsTheDiscriminator() {
        assertTrue(ResultJson.write(List.of(ms2Row())).contains("\"mslevel\":2"));
        assertTrue(ResultJson.write(List.of(ms1Row())).contains("\"mslevel\":1"));
    }

    @Test
    void nullsRenderAsJsonNullNeverZeroOrEmptyStringOrNone() {
        String json = ResultJson.write(List.of(ms1Row()));
        assertFalse(json.contains("\"None\""), json);
        assertFalse(json.contains("\"\""), json);
        assertFalse(
                json.contains("\"precmz\":0"),
                "0 is a real m/z-adjacent value, not a stand-in for null");
    }

    // ---------------------------------------------------------------- array framing

    @Test
    void anEmptyResultIsAnEmptyArrayNotNullAndNotAnError() {
        // A query that matches nothing is a valid answer -- the deliberate empty golden
        // micro_mzml_edge_results.json is exactly this case.
        assertEquals("[]", ResultJson.write(List.of()));
        assertEquals("[]", ResultJson.write(null));
    }

    @Test
    void multipleRowsAreCommaSeparatedInsideOneArray() {
        String json = ResultJson.write(List.of(ms2Row(), ms1Row()));
        assertTrue(json.startsWith("[{"), json);
        assertTrue(json.endsWith("}]"), json);
        assertTrue(json.contains("},{"), "rows must be comma-separated: " + json);
        assertEquals(24, keysInOrder(json).size(), "two rows x 12 keys");
    }

    @Test
    void outputIsCompactWithNoIndentationOrSpaces() {
        String json = ResultJson.write(List.of(ms2Row()));
        assertFalse(
                json.contains("\n"), "the node-table cell stores this verbatim; layout is waste");
        assertFalse(json.contains(": "), json);
        assertFalse(json.contains(", "), json);
    }

    // ---------------------------------------------------------------- round-trip bit-exactness

    @Test
    void everyEmittedFloatParsesBackToIDENTICALBITS() {
        // The actual requirement -- not byte-matching the reference. Guards against a formatter
        // that
        // rounds.
        double[] awkward = {
            0.011218333333333334, // the mzML golden's rt; does NOT survive a float round-trip
            586278.8533592224, // our exact float64 tic
            736.6370849609375, // an exact float32-derived m/z
            810.4154747204038,
            1e-5,
            1e300,
            4.9e-324, // subnormal
            0.1 + 0.2, // 0.30000000000000004
        };
        for (double d : awkward) {
            ScanInfoResult r = new ScanInfoResult(1, d, 1, d, 1, d, 2, d, d, d, d, d);
            String json = ResultJson.write(List.of(r));
            Matcher m = Pattern.compile("\"tic\":([^,}]+)").matcher(json);
            assertTrue(m.find(), json);
            assertEquals(
                    Double.doubleToLongBits(d),
                    Double.doubleToLongBits(Double.parseDouble(m.group(1))),
                    "value " + d + " did not round-trip bit-exactly; emitted " + m.group(1));
        }
    }

    @Test
    void rtZeroIsEmittedAsZeroNotNull() {
        // 664 rows of plusrise_results.json have rt 0.0. An over-eager null conversion fails all of
        // them.
        ScanInfoResult r =
                new ScanInfoResult(
                        576, 161.0209, null, 0.0, 1, 1299900.0, 2, 230000.0, 162.1122, null, null,
                        null);
        String json = ResultJson.write(List.of(r));
        assertTrue(json.contains("\"rt\":0.0"), json);
        assertFalse(json.contains("\"rt\":null"), "0.0 is a genuine retention time");
    }

    // ---------------------------------------------------------------- defensive

    @Test
    void aNonFiniteValueReachingTheSerializerFailsRatherThanEmittingInvalidJson() {
        // NaN/infinity must have been converted to null upstream. `NaN` is not valid JSON and the
        // reference forbids it via allow_nan=False, so emitting it would produce a document no
        // parser
        // accepts -- worse than failing.
        ScanInfoResult bad =
                new ScanInfoResult(1, 1.0, 1, 1.0, 1, Double.NaN, 2, 1.0, 1.0, null, null, null);
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> ResultJson.write(List.of(bad)));
        assertTrue(e.getMessage().contains("non-finite"), e.getMessage());
    }

    @Test
    void theSerializersKeyListIsTheRecordsOwnSoTheyCannotDrift() {
        assertEquals(
                EXPECTED_12,
                List.of(ScanInfoResult.KEYS),
                "ScanInfoResult.KEYS is the single key list; ResultJson emits in that order");
    }
}
