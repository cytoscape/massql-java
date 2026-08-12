package edu.ucsd.idekerlab.massql.result;

import java.util.List;

/**
 * Serializes {@link ScanInfoResult} rows to the published JSON contract.
 *
 * <p><b>This output is a published contract, not an implementation detail.</b> A consumer may write the
 * string into a table column verbatim and read it back later, so the key names,
 * the key set and the key order are frozen. They are defined in {@code docs/RESULT_SCHEMA.md} and pinned
 * by {@code ResultSchemaContractTest}.
 *
 * <p><b>One shape, always</b>: every row carries the same 12 keys in the same order,
 * whether the query was {@code MS1DATA} or {@code MS2DATA}. No key is ever omitted; an inapplicable field
 * is present with the value {@code null}. Hence no shape parameter: there is no second shape for one to
 * select.
 *
 * <h2>Written by hand — no Jackson, no Gson</h2>
 *
 * The output is 12 fixed keys. Jackson discovers modules through {@code ServiceLoader}, which
 * this project forbids outright (provider lookup is unreliable whenever the
 * thread-context classloader cannot see the caller's classes, which is any non-flat classpath), and
 * constraint 6 caps the dependency budget that a JSON library would consume for no benefit here.
 *
 * <h2>Number formatting</h2>
 *
 * {@code Double.toString} / {@code Integer.toString} — shortest round-trip-exact form, compact, no
 * indentation. <b>Round-trip bit-exactness is the requirement</b>, not byte-matching Python: Java differs
 * from Python's {@code repr} in known ways (exponents {@code 1.0E-5} vs {@code 1e-05}; always emitting
 * {@code .0} on integral values), which is exactly why the differential compares <b>parsed values, never
 * text</b>. Never round, truncate or reformat — that would destroy the bit-identity Steps 8 and 12
 * establish.
 */
public final class ResultJson {

    private ResultJson() {}

    /**
     * Renders {@code rows} as a JSON array.
     *
     * @return {@code "[]"} for an empty or null list — never {@code null}, never an error. A query that
     *         matches nothing is a valid answer.
     */
    public static String write(List<ScanInfoResult> rows) {
        if (rows == null || rows.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder(rows.size() * 220);
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            writeRow(sb, rows.get(i));
        }
        return sb.append(']').toString();
    }

    private static void writeRow(StringBuilder sb, ScanInfoResult r) {
        // Values are emitted in ScanInfoResult.KEYS order -- the same array the contract test
        // checks
        // against docs/RESULT_SCHEMA.md, so there is one key list in the codebase rather than two
        // that
        // can drift.
        Object[] values = {
            r.scan(),
            r.precmz(),
            r.ms1scan(),
            r.rt(),
            r.charge(),
            r.tic(),
            r.mslevel(),
            r.basePeakI(),
            r.basePeakMz(),
            r.ms1I(),
            r.ms1Precmz(),
            r.ms1BasePeakI()
        };
        if (values.length != ScanInfoResult.KEYS.length) {
            // Unreachable unless someone adds a field to the record without adding it here -- which
            // is
            // exactly the mistake worth failing loudly on, since the alternative is a silently
            // truncated
            // published contract.
            throw new IllegalStateException(
                    "value count " + values.length + " != key count " + ScanInfoResult.KEYS.length);
        }

        sb.append('{');
        for (int k = 0; k < values.length; k++) {
            if (k > 0) sb.append(',');
            sb.append('"').append(ScanInfoResult.KEYS[k]).append("\":");
            append(sb, values[k]);
        }
        sb.append('}');
    }

    /**
     * A Java {@code null} renders as JSON {@code null} — never {@code 0}, {@code ""} or {@code "None"}.
     *
     * <p>No string values occur in this contract, so no escaping is needed: every key is a fixed literal
     * and every value is a number or null. A future string-valued column would need escaping added here,
     * hence the explicit rejection rather than a silent {@code toString}.
     */
    private static void append(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Integer n) {
            sb.append(n.intValue());
        } else if (v instanceof Double d) {
            // Non-finite values must already have been converted to null by ScaninfoCollation; if
            // one
            // reaches here it would emit `NaN`, which is not valid JSON and which the reference
            // forbids
            // via allow_nan=False. Fail rather than write a document no parser accepts.
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalStateException(
                        "non-finite value "
                                + d
                                + " reached the serializer; ScaninfoCollation must "
                                + "convert NaN and infinity to null first");
            }
            sb.append(d.doubleValue());
        } else {
            throw new IllegalStateException(
                    "unsupported value type "
                            + v.getClass().getName()
                            + "; this contract is numbers and nulls only, and a string column would need escaping");
        }
    }
}
