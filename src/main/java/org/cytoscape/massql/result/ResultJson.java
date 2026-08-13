package org.cytoscape.massql.result;

import java.util.List;

/**
 * Serializes {@link ScanInfoResult} rows to the published JSON contract.
 *
 * <p><b>This output is a published contract, not an implementation detail.</b> A consumer may write the
 * string into a table column verbatim and read it back later, so the key names,
 * the key set and the key order are frozen. {@code ScanInfoResult.KEYS} defines them and
 * {@code ResultJsonTest} pins them.
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
 * the closure-size budget leaves no room for a JSON library that buys nothing here.
 *
 * <h2>Two renderings, identical values</h2>
 *
 * <ul>
 *   <li>{@link #write(List)} — <b>compact</b>, one line, no whitespace between tokens. The machine
 *       form, and the default for SDK callers.</li>
 *   <li>{@link #writePretty(List)} / {@link #writePretty(List, boolean)} — <b>2-space indented</b> with
 *       a line break per key, optionally with ANSI colour. The human form, used by the CLI.</li>
 * </ul>
 *
 * Both emit the same keys in the same order with the same number formatting, so they parse to identical
 * values. ⛔ Only the whitespace and the escape codes differ; a caller comparing <i>text</i> across the
 * two is comparing the wrong thing.
 *
 * <h2>Number formatting</h2>
 *
 * {@code Double.toString} / {@code Integer.toString} — shortest round-trip-exact form. <b>Round-trip
 * bit-exactness is the requirement</b>, not byte-matching the reference's text: the two differ in known
 * ways (exponents {@code 1.0E-5} vs {@code 1e-05}; always emitting {@code .0} on integral values), which
 * is exactly why the differential compares <b>parsed values, never text</b>. Never round, truncate or
 * reformat — that would destroy the bit-identity the parity gate and the differential establish.
 */
public final class ResultJson {

    private ResultJson() {}

    /** Two spaces per level, matching the reference implementation's {@code indent=2}. */
    private static final String ROW_INDENT = "  ";

    private static final String KEY_INDENT = "    ";

    // ANSI SGR codes. They live here rather than in the CLI because this is the only place that
    // knows a
    // token is a KEY rather than a number -- colourising finished JSON by pattern would be the same
    // fragile approach that once silently truncated a parity dump.
    private static final String CYAN = "[36m";

    private static final String DIM = "[2m";

    private static final String RESET = "[0m";

    /**
     * Renders {@code rows} as a <b>compact</b> single-line JSON array.
     *
     * @return {@code "[]"} for an empty or null list — never {@code null}, never an error. A query that
     *         matches nothing is a valid answer.
     */
    public static String write(List<ScanInfoResult> rows) {
        return render(rows, false, false);
    }

    /**
     * Renders {@code rows} indented two spaces per level, with a line break per key and no colour.
     *
     * @return {@code "[]"} for an empty or null list — an empty array gains nothing from indentation,
     *         and this matches what the reference produces at {@code indent=2}.
     */
    public static String writePretty(List<ScanInfoResult> rows) {
        return render(rows, true, false);
    }

    /**
     * Indented as {@link #writePretty(List)}, optionally with ANSI colour on keys and {@code null}.
     *
     * <p>⛔ <b>Only ever colour a terminal.</b> Escape codes are not valid JSON, so a colourised payload
     * written to a pipe or a file breaks every downstream parser. The caller decides; this method only
     * renders what it is asked for.
     *
     * @param colour {@code true} to emit SGR codes
     */
    public static String writePretty(List<ScanInfoResult> rows, boolean colour) {
        return render(rows, true, colour);
    }

    private static String render(List<ScanInfoResult> rows, boolean pretty, boolean colour) {
        if (rows == null || rows.isEmpty()) return "[]";

        StringBuilder sb = new StringBuilder(rows.size() * (pretty ? 440 : 220));
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(',');
            if (pretty) sb.append('\n').append(ROW_INDENT);
            writeRow(sb, rows.get(i), pretty, colour);
        }
        if (pretty) sb.append('\n');
        return sb.append(']').toString();
    }

    private static void writeRow(
            StringBuilder sb, ScanInfoResult r, boolean pretty, boolean colour) {
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
            if (pretty) sb.append('\n').append(KEY_INDENT);
            if (colour) sb.append(CYAN);
            sb.append('"').append(ScanInfoResult.KEYS[k]).append('"');
            if (colour) sb.append(RESET);
            sb.append(':');
            if (pretty) sb.append(' ');
            append(sb, values[k], colour);
        }
        if (pretty) sb.append('\n').append(ROW_INDENT);
        sb.append('}');
    }

    /**
     * A Java {@code null} renders as JSON {@code null} — never {@code 0}, {@code ""} or {@code "None"}.
     *
     * <p>No string values occur in this contract, so no escaping is needed: every key is a fixed literal
     * and every value is a number or null. A future string-valued column would need escaping added here,
     * hence the explicit rejection rather than a silent {@code toString}.
     */
    private static void append(StringBuilder sb, Object v, boolean colour) {
        if (v == null) {
            if (colour) sb.append(DIM);
            sb.append("null");
            if (colour) sb.append(RESET);
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
