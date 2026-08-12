package org.cytoscape.massql.exec;

import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.result.ScanInfoResult;

/**
 * The per-column comparison policy of the differential, in one place.
 *
 * <p>One class rather than assertions scattered across four ITs, because the policy <b>is</b> the
 * gate: every tolerance here is a claim about where the two implementations may legitimately differ,
 * and a tolerance quietly widened in one test while the others stay strict is a finding converted
 * into a permanent unknown.
 *
 * <h2>The policy, and why each row is what it is</h2>
 *
 * <table border="1">
 *   <caption>Per-column comparison rules</caption>
 *   <tr><th>Column</th><th>Rule</th><th>Why</th></tr>
 *   <tr><td>{@code scan}, {@code ms1scan}, {@code charge}, {@code mslevel}</td>
 *       <td><b>Exact</b></td><td>identifiers and counts; there is no "close"</td></tr>
 *   <tr><td>{@code precmz}, {@code base_peak_mz}</td><td>relative <b>1e-9</b></td>
 *       <td>m/z read from the same bytes on both sides</td></tr>
 *   <tr><td>{@code ms1_precmz}</td><td>relative 1e-9, or <b>1e-7</b> on a 32-bit mzXML</td>
 *       <td>a <i>measured</i> centroid from the MS1 array; mzXML's {@code precision="32"} truncates
 *           it, so the same peak reads 4.9e-9 to 2.9e-8 apart from its 64-bit mzML twin</td></tr>
 *   <tr><td>{@code tic}</td><td>relative <b>1e-6</b></td>
 *       <td>⛔ NOT bit-identical. MassQL's intensity column is {@code float32} and {@code tic} is a
 *           float32 sum over it, so the <i>reference</i> carries accumulation error this float64 sum
 *           does not — measured worst case 3.69e-8</td></tr>
 *   <tr><td>{@code base_peak_i}, {@code ms1_i}, {@code ms1_base_peak_i}</td><td><b>bit-identical</b></td>
 *       <td>maxima and lookups — <i>selected</i> values with no accumulation, so that error does not reach
 *           them. This is a split, not a blanket loosening</td></tr>
 *   <tr><td>{@code rt}</td><td><b>bit-identical</b></td>
 *       <td>requires the double-precision {@code scanRt}; a float would pass the parity gate and fail here</td></tr>
 *   <tr><td><i>every</i> column</td><td><b>exact null-vs-value</b></td>
 *       <td>a null where the golden has a value is a failure regardless of any tolerance — it is how
 *           a wrong m/z-window choice surfaces</td></tr>
 * </table>
 *
 * <p><b>Row count and order are compared first</b>, so a missing row reports "expected 664, got 663"
 * rather than a field-level diff on rows that no longer line up.
 *
 * <p><b>Public because it is shared across test packages</b> — layer 2 lives in {@code …massql.exec}
 * and layer 3 in {@code …massql.io}. Test source set only.
 *
 * <p>Failures accumulate rather than throwing at the first difference: one run should tell you every
 * column that moved, not the alphabetically first.
 */
public final class ResultComparator {

    /** Relative tolerance for m/z columns read identically on both sides. */
    private static final double MZ_TOL = 1e-9;

    /** {@code ms1_precmz} against a 32-bit mzXML fixture. */
    private static final double MZ_TOL_FLOAT32 = 1e-7;

    /** {@code tic} only — the reference's float32 accumulation. */
    private static final double TIC_TOL = 1e-6;

    private ResultComparator() {}

    /**
     * Compares actual rows against a golden.
     *
     * @param float32Mz true when the fixture stores m/z at {@code precision="32"} (any mzXML), which
     *     relaxes {@code ms1_precmz} alone
     * @return every difference found; empty means identical under the policy
     */
    public static List<String> compare(
            String label,
            List<ScanInfoResult> golden,
            List<ScanInfoResult> actual,
            boolean float32Mz) {
        List<String> diffs = new ArrayList<>();

        // Count and order FIRST. A field diff on misaligned rows is unreadable.
        if (golden.size() != actual.size()) {
            diffs.add(
                    label
                            + ": row count -- expected "
                            + golden.size()
                            + ", got "
                            + actual.size()
                            + " (golden scans "
                            + ids(golden)
                            + ", ours "
                            + ids(actual)
                            + ")");
            return diffs; // Comparing fields now would produce noise, not information.
        }
        for (int i = 1; i < actual.size(); i++) {
            Integer prev = actual.get(i - 1).scan();
            Integer cur = actual.get(i).scan();
            if (prev != null && cur != null && cur <= prev) {
                diffs.add(
                        label
                                + ": rows must be ascending by scan id, got "
                                + prev
                                + " then "
                                + cur);
            }
        }

        for (int i = 0; i < golden.size(); i++) {
            ScanInfoResult g = golden.get(i);
            ScanInfoResult a = actual.get(i);
            String at = label + " row " + i + " (scan " + g.scan() + ")";

            exact(diffs, at, "scan", g.scan(), a.scan());
            exact(diffs, at, "ms1scan", g.ms1scan(), a.ms1scan());
            exact(diffs, at, "charge", g.charge(), a.charge());
            exact(diffs, at, "mslevel", g.mslevel(), a.mslevel());

            relative(diffs, at, "precmz", g.precmz(), a.precmz(), MZ_TOL);
            relative(diffs, at, "base_peak_mz", g.basePeakMz(), a.basePeakMz(), MZ_TOL);
            relative(
                    diffs,
                    at,
                    "ms1_precmz",
                    g.ms1Precmz(),
                    a.ms1Precmz(),
                    float32Mz ? MZ_TOL_FLOAT32 : MZ_TOL);
            relative(diffs, at, "tic", g.tic(), a.tic(), TIC_TOL);

            bitIdentical(diffs, at, "rt", g.rt(), a.rt());
            bitIdentical(diffs, at, "base_peak_i", g.basePeakI(), a.basePeakI());
            bitIdentical(diffs, at, "ms1_i", g.ms1I(), a.ms1I());
            bitIdentical(diffs, at, "ms1_base_peak_i", g.ms1BasePeakI(), a.ms1BasePeakI());
        }
        return diffs;
    }

    private static void exact(List<String> out, String at, String col, Integer g, Integer a) {
        if (!java.util.Objects.equals(g, a)) {
            out.add(at + ": " + col + " -- expected " + g + ", got " + a);
        }
    }

    /**
     * Relative comparison that treats null as a value, not as a wildcard.
     *
     * <p>The null check runs before the tolerance: a null where the golden has a number is a
     * <b>failure</b>, not a difference of zero. That is the assertion that catches a wrong
     * m/z-window choice in the precursor lookup, which no tolerance would ever see.
     */
    private static void relative(
            List<String> out, String at, String col, Double g, Double a, double tol) {
        if (nullMismatch(out, at, col, g, a) || g == null) return;
        if (g.doubleValue() == a.doubleValue()) return;
        double rel = g == 0.0 ? Math.abs(a) : Math.abs(a - g) / Math.abs(g);
        if (rel > tol) {
            out.add(
                    at
                            + ": "
                            + col
                            + " -- expected "
                            + g
                            + ", got "
                            + a
                            + " (relative "
                            + String.format("%.3e", rel)
                            + " > "
                            + tol
                            + ")");
        }
    }

    private static void bitIdentical(List<String> out, String at, String col, Double g, Double a) {
        if (nullMismatch(out, at, col, g, a) || g == null) return;
        if (Double.doubleToLongBits(g) != Double.doubleToLongBits(a)) {
            out.add(
                    at
                            + ": "
                            + col
                            + " -- expected "
                            + g
                            + ", got "
                            + a
                            + " (must be BIT-identical; no tolerance applies to this column)");
        }
    }

    /** @return true if one side is null and the other is not — a failure in its own right */
    private static boolean nullMismatch(
            List<String> out, String at, String col, Double g, Double a) {
        if ((g == null) != (a == null)) {
            out.add(
                    at
                            + ": "
                            + col
                            + " -- expected "
                            + (g == null ? "null" : g)
                            + ", got "
                            + (a == null ? "null" : a)
                            + " (null-vs-value is exact; check the m/z window method before the"
                            + " decoder)");
            return true;
        }
        return false;
    }

    private static List<Integer> ids(List<ScanInfoResult> rows) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 8); i++) out.add(rows.get(i).scan());
        return out;
    }
}
