package edu.ucsd.idekerlab.massql.result;

/**
 * One row of {@code scaninfo} output — the frozen 12-key contract.
 *
 * <p><b>The contract is defined in {@code docs/RESULT_SCHEMA.md}, not here.</b> That file carries the
 * key order, the per-field nullability, the population-by-format table and the float policy, and
 * {@code ResultSchemaContractTest} asserts this class's serialized key order against it. Do not
 * restate the contract in javadoc — four documents restating it is how they came to disagree.
 *
 * <p><b>Boxed {@code Integer}/{@code Double} throughout, deliberately.</b> Null is a real, testable
 * value rather than a sentinel, which is the whole point: MassQL's raw output uses {@code 0} to mean
 * "not recorded" for three fields, and collapsing that into a primitive would make "absent" and
 * "genuinely zero" indistinguishable. {@code rt} is the case that proves it — {@code 0.0} is a
 * genuine retention time and appears on all 664 rows of the PlusRise golden.
 *
 * <p><b>There is exactly ONE shape</b>. {@code scaninfo(MS1DATA)} and
 * {@code scaninfo(MS2DATA)} emit the same 12 keys, discriminated by the {@link #mslevel} value; no key
 * is ever omitted. This record therefore has <b>no</b> {@code ms1DataShape} flag — an earlier design
 * originally specified one, but it was a per-<i>query</i> property stored per-<i>row</i> (664 identical
 * copies in a PlusRise result, and part of {@code equals}) and with one shape it selects nothing.
 *
 * <p>Which fields are null for an MS1 row is a property of the <i>data</i>, not of a shape flag: a
 * survey scan has no precursor, so {@code precmz}, {@code ms1scan}, {@code charge} and all three
 * {@code ms1*} fields are null — while {@code basePeakI}/{@code basePeakMz} are <b>real values</b>,
 * because a survey scan plainly has a base peak.
 */
public record ScanInfoResult(
        Integer scan, // never null
        Double precmz, // null: no precursor recorded, or an MS1 row
        Integer ms1scan, // null: no linked MS1 survey scan, or an MS1 row
        Double rt, // never null -- 0.0 is a real retention time
        Integer charge, // null: not recorded, or an MS1 row
        Double tic, // never null
        Integer mslevel, // never null: 1 or 2. THE discriminator.
        Double basePeakI, // never null -- including on an MS1 row
        Double basePeakMz, // never null -- including on an MS1 row
        Double ms1I, // null: no MS1 data, tolerance miss, or an MS1 row
        Double ms1Precmz, // null: same conditions as ms1I
        Double ms1BasePeakI // null: no linked MS1 scan, or an MS1 row
        ) {

    /**
     * The frozen key order, as serialized. Package-visible so {@link ResultJson} and the contract test
     * share one definition rather than two lists that can drift.
     *
     * <p>Kept in sync with {@code docs/RESULT_SCHEMA.md} by {@code ResultSchemaContractTest}, which
     * parses that document's table and compares. That is what makes the document authoritative rather
     * than decorative.
     */
    static final String[] KEYS = {
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
        "ms1_base_peak_i"
    };
}
