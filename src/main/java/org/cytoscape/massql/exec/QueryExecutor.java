package org.cytoscape.massql.exec;

import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.lang.ast.Condition;
import org.cytoscape.massql.lang.ast.DataSource;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * Streams a file once, evaluates every condition per scan, and hands each qualifying scan to a
 * consumer. <h2>Why per-scan rather than whole-file</h2> There is no whole-file table.
 */
public final class QueryExecutor {
    private QueryExecutor() {}

    /**
     * Runs {@code q} over {@code stream}, invoking {@code out} for each qualifying scan in
     * document order.
     */
    public static ExecutionSummary execute(
            MassqlQuery q, SpectraStream stream, MassqlOptions opts, QualifyingScanConsumer out) {
        if (q == null) throw new MassqlException("query is required");
        if (stream == null) throw new MassqlException("stream is required");
        if (out == null) throw new MassqlException("consumer is required");
        if (opts == null) opts = MassqlOptions.defaults();

        int wantedLevel = q.source() == DataSource.MS1DATA ? 1 : 2;

        List<Condition> conditions = q.allConditions();
        List<Condition> scanLevel = new ArrayList<>();
        List<Condition> peakLevel = new ArrayList<>();
        for (Condition c : conditions) {
            if (ConditionFilters.isScanLevel(c)) scanLevel.add(c);
            else peakLevel.add(c);
        }

        List<String> diagnostics = new ArrayList<>();
        SpectrumTable retainedMs1 = null;
        int qualifying = 0;
        int examined = 0;
        int skippedEmpty = 0;
        int missingMs1 = 0;

        while (stream.hasNext()) {
            ScanView v = stream.next();
            examined++;

            if (v.peaks().isEmpty()) {
                skippedEmpty++;
                continue;
            }

            if (v.msLevel() == 1) retainedMs1 = v.peaks();

            if (v.msLevel() != wantedLevel) continue;

            boolean rejected = false;
            for (Condition c : scanLevel) {
                if (!ConditionFilters.scanLevelHolds(c, v)) {
                    rejected = true;
                    break;
                }
            }
            if (rejected) continue;

            if (retainedMs1 == null && hasMs1Condition(peakLevel)) {
                missingMs1++;
                continue;
            }

            SpectrumTable scan =
                    (wantedLevel == 1 && retainedMs1 != null && v.msLevel() == 1)
                            ? retainedMs1
                            : v.peaks();

            for (Condition c : peakLevel) {
                if (!ConditionFilters.peakLevelHolds(c, scan, v, retainedMs1)) {
                    rejected = true;
                    break;
                }
            }
            if (rejected) continue;

            qualifying++;
            out.accept(v, scan, retainedMs1);
        }

        diagnostics.addAll(stream.diagnostics());
        if (skippedEmpty > 0) {
            diagnostics.add(
                    "skipped "
                            + skippedEmpty
                            + " zero-peak scan(s); MassQL's loaders drop these, "
                            + "so they cannot qualify");
        }
        if (missingMs1 > 0) {
            diagnostics.add(
                    missingMs1
                            + " scan(s) had an MS1MZ condition but no preceding MS1 scan, so they "
                            + "could not qualify. MGF has no survey scans at all, which makes MS1MZ unsatisfiable there");
        }
        if (qualifying == 0 && examined > 0) {
            diagnostics.add(
                    "no scans matched: "
                            + examined
                            + " scan(s) examined, "
                            + conditions.size()
                            + " condition(s) applied");
        }
        return new ExecutionSummary(qualifying, examined, diagnostics);
    }

    private static boolean hasMs1Condition(List<Condition> peakLevel) {
        for (Condition c : peakLevel) {
            if (c instanceof Condition.Value cv
                    && cv.type() == org.cytoscape.massql.lang.ast.ConditionType.MS1MZ) {
                return true;
            }
        }
        return false;
    }
}
