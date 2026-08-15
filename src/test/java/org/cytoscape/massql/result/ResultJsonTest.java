package org.cytoscape.massql.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

class ResultJsonTest {

    /** What the CLI configures: null columns must survive, since the contract has no optional key. */
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private static ScanInfoResult ms2Row() {
        return new ScanInfoResult(
                3, 810.79, 2, 0.0112, null, 586278.85, 2, 161140.86, 736.637, null, null,
                183838.72);
    }

    private static ScanInfoResult allNullable() {
        return new ScanInfoResult(1, null, null, 0.0, null, 1.0, 1, 2.0, 3.0, null, null, null);
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void aRowSurvivesSerializationAndBack() {
        ScanInfoResult before = ms2Row();
        ScanInfoResult after = GSON.fromJson(GSON.toJson(before), ScanInfoResult.class);
        assertEquals(before, after);
    }

    @Test
    void everyNullableColumnSurvivesAsNull() {
        ResultJson before = new ResultJson(List.of(allNullable()));
        ResultJson after = GSON.fromJson(GSON.toJson(before), ResultJson.class);
        assertEquals(before, after);
        assertEquals(1, after.results().size());
        ScanInfoResult r = after.results().get(0);
        assertFalse(r.precmz() != null || r.charge() != null || r.ms1I() != null, r.toString());
    }

    @Test
    void anEmptyResultSurvives() {
        ResultJson after = GSON.fromJson(GSON.toJson(new ResultJson(List.of())), ResultJson.class);
        assertEquals(List.of(), after.results());
    }

    @Test
    void everyKeyIsPresentEvenWhenNull() {
        // serializeNulls is what makes this true; without it gson drops the null columns and the
        // 12-key contract silently becomes 6 keys on an MS1 row.
        String json = GSON.toJson(new ResultJson(List.of(allNullable())));
        for (String key : keysInDeclarationOrder()) {
            assertTrue(json.contains('"' + key + '"'), key + " missing from " + json);
        }
    }

    @Test
    void keysAreSerializedInDeclarationOrder() {
        // The one assertion that must read the text: order is a property of the document, and
        // deserializing loses it. docs/RESULT_SCHEMA.md freezes it.
        String json = GSON.toJson(new ResultJson(List.of(ms2Row())));
        int at = 0;
        for (String key : keysInDeclarationOrder()) {
            int found = json.indexOf('"' + key + '"', at);
            assertTrue(found >= 0, key + " is out of order or missing in " + json);
            at = found;
        }
    }

    private static List<String> keysInDeclarationOrder() {
        return List.of(ScanInfoResult.class.getRecordComponents()).stream()
                .map(ResultJsonTest::keyOf)
                .toList();
    }

    private static String keyOf(java.lang.reflect.RecordComponent c) {
        try {
            // @SerializedName targets FIELD, so it propagates to the record's field rather than
            // staying readable on the component -- which is also where gson reads it.
            return c.getDeclaringRecord()
                    .getDeclaredField(c.getName())
                    .getAnnotation(SerializedName.class)
                    .value();
        } catch (NoSuchFieldException e) {
            throw new AssertionError(c.getName(), e);
        }
    }

    // ------------------------------------------------------------------ immutability

    @Test
    void theResultsAccessorIsImmutable() {
        ResultJson r = new ResultJson(new ArrayList<>(List.of(ms2Row())));
        assertThrows(UnsupportedOperationException.class, () -> r.results().add(ms2Row()));
        assertThrows(UnsupportedOperationException.class, () -> r.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> r.results().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> r.results().set(0, allNullable()));
    }

    @Test
    void theConstructorCopiesSoLaterMutationOfTheSourceIsInvisible() {
        List<ScanInfoResult> source = new ArrayList<>(List.of(ms2Row()));
        ResultJson r = new ResultJson(source);
        source.add(allNullable());
        assertEquals(1, r.results().size(), "the record kept a copy, not the caller's list");
    }

    @Test
    void aNullListBecomesEmptyRatherThanNull() {
        assertEquals(List.of(), new ResultJson(null).results());
    }

    @Test
    void aDeserializedInstanceIsEquallyImmutable() {
        // Gson uses the canonical constructor for records, so the defensive copy must still run --
        // otherwise mutability returns through the deserialization path alone.
        ResultJson r =
                GSON.fromJson(GSON.toJson(new ResultJson(List.of(ms2Row()))), ResultJson.class);
        assertThrows(UnsupportedOperationException.class, () -> r.results().add(allNullable()));
    }

    @Test
    void noComponentIsAMutableArrayOrCollection() {
        for (Class<?> type : List.of(ResultJson.class, ScanInfoResult.class)) {
            for (RecordComponent c : type.getRecordComponents()) {
                assertFalse(
                        c.getType().isArray(),
                        type.getSimpleName()
                                + "."
                                + c.getName()
                                + " is an array; its accessor would"
                                + " hand back the internal reference");
                if (Collection.class.isAssignableFrom(c.getType())
                        || Map.class.isAssignableFrom(c.getType())) {
                    assertEquals(
                            List.class,
                            c.getType(),
                            type.getSimpleName()
                                    + "."
                                    + c.getName()
                                    + " must be a List copied by the"
                                    + " compact constructor");
                }
            }
        }
    }
}
