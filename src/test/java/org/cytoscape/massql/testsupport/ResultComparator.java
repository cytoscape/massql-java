package org.cytoscape.massql.testsupport;

import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.result.ScanInfoResult;

public final class ResultComparator {
    private static final double MZ_TOL = 1e-9;

    private static final double MZ_TOL_FLOAT32 = 1e-7;

    private static final double TIC_TOL = 1e-6;

    private ResultComparator() {}

    public static List<String> compare(
            String label,
            List<ScanInfoResult> golden,
            List<ScanInfoResult> actual,
            boolean float32Mz) {
        List<String> diffs = new ArrayList<>();

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
            return diffs;
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
