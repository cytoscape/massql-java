package edu.ucsd.idekerlab.massql.exec;

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.MassqlOptions;
import edu.ucsd.idekerlab.massql.io.ScanView;
import edu.ucsd.idekerlab.massql.io.SpectraStream;
import edu.ucsd.idekerlab.massql.lang.ast.Condition;
import edu.ucsd.idekerlab.massql.lang.ast.DataSource;
import edu.ucsd.idekerlab.massql.lang.ast.MassqlQuery;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

import java.util.ArrayList;
import java.util.List;

/**
 * Streams a file once, evaluates every condition per scan, and hands each qualifying scan to a consumer.
 *
 * <h2>Why per-scan rather than whole-file</h2>
 *
 * <p>Correction C22: there is no whole-file table. A 500 MB input projects to 1.0–1.9 GB of heap if
 * materialised, which OOMs inside Cytoscape. The executor advances a {@link SpectraStream} cursor and retains
 * exactly <b>one</b> MS1 scan — which the document-order {@code ms1scan} rule makes sufficient, since the
 * linked MS1 is always the most recent preceding one.
 *
 * <p>Tech_Step9 §1 originally declared {@code execute(MassqlQuery, SpectrumTable ms1, SpectrumTable ms2)}
 * returning "ordinals", eight lines below the note saying those tables do not exist (Correction C35b). Under
 * streaming there is no whole-file ordinal space at all: a single-scan table's only ordinal is {@code 0}.
 *
 * <h2>Evaluation order, and why it is not arbitrary</h2>
 *
 * <ol>
 *   <li><b>Zero-peak scans are skipped first.</b> MassQL's loaders {@code continue} on an empty intensity
 *       array, so its dataframes hold no rows for them. Without this guard a <i>scan-level-only</i> query
 *       would return 34,513 scans on {@code PlusRise.mgf} where MassQL returns <b>21,942</b> — peak-based
 *       conditions fail an empty scan by themselves, but a scan-level condition never looks at peaks
 *       (Correction C35c).</li>
 *   <li><b>The MS1 retention comes after that guard</b>, because an empty MS1 must not become an
 *       {@code ms1scan} link either (C27b) — the same rule one layer up.</li>
 *   <li><b>Scan-level conditions before {@code materialize()}.</b> A scan rejected on {@code RTMIN},
 *       {@code SCANMIN}, {@code POLARITY}, {@code CHARGE} or {@code MS2PREC} never pays base64-decode,
 *       inflate or the {@code double[]} allocation. That is the entire payoff of deferred decoding.</li>
 *   <li><b>Peak-level conditions last</b>, on the materialised single-scan table.</li>
 * </ol>
 *
 * <p>Conditions combine as a per-scan AND over "did this condition find any peak" — never a row-level AND.
 * Condition order is provably irrelevant for the in-scope set (C37g), so evaluating cheap ones first is a
 * pure optimisation with no semantic effect.
 */
public final class QueryExecutor {

    private QueryExecutor() { }

    /**
     * Runs {@code q} over {@code stream}, invoking {@code out} for each qualifying scan in document order.
     *
     * @param opts may be null, meaning {@link MassqlOptions#defaults()}
     * @throws MassqlException if the query names a function or source this step cannot execute
     */
    public static ExecutionSummary execute(MassqlQuery q, SpectraStream stream, MassqlOptions opts,
                                           QualifyingScanConsumer out) {
        if (q == null) throw new MassqlException("query is required");
        if (stream == null) throw new MassqlException("stream is required");
        if (out == null) throw new MassqlException("consumer is required");
        if (opts == null) opts = MassqlOptions.defaults();

        // MS1DATA selects MS1 scans, MS2DATA selects MS2 scans.
        int wantedLevel = q.source() == DataSource.MS1DATA ? 1 : 2;

        List<Condition> conditions = q.allConditions();
        List<Condition> scanLevel = new ArrayList<>();
        List<Condition> peakLevel = new ArrayList<>();
        for (Condition c : conditions) {
            if (ConditionFilters.isScanLevel(c)) scanLevel.add(c); else peakLevel.add(c);
        }

        List<String> diagnostics = new ArrayList<>();
        SpectrumTable retainedMs1 = null;
        int qualifying = 0;
        int examined = 0;
        int skippedEmpty = 0;
        int missingMs1 = 0;

        while (stream.next()) {
            ScanView v = stream.current();
            examined++;

            // (1) Zero-peak scans are invisible to MassQL -- see the class note. Before the MS1
            // retention, so an empty MS1 cannot become an ms1scan link either.
            if (v.peakCount() == 0) {
                skippedEmpty++;
                continue;
            }

            // (2) Retain the most recent non-empty MS1, for MS1MZ here and the precursor lookup in Step 10.
            if (v.msLevel() == 1) retainedMs1 = v.materialize();

            if (v.msLevel() != wantedLevel) continue;

            // (3) Metadata-only conditions, before paying for peaks.
            boolean rejected = false;
            for (Condition c : scanLevel) {
                if (!ConditionFilters.scanLevelHolds(c, v)) { rejected = true; break; }
            }
            if (rejected) continue;

            // An MS1MZ condition needs the linked MS1. If none precedes this scan, it cannot hold --
            // worth counting, because "0 results" on an MS1MZ query over an MGF has this exact cause.
            if (retainedMs1 == null && hasMs1Condition(peakLevel)) {
                missingMs1++;
                continue;
            }

            // (4) Peak-level conditions on the materialised scan.
            SpectrumTable scan = (wantedLevel == 1 && retainedMs1 != null && v.msLevel() == 1)
                    ? retainedMs1               // already materialised at (2); do not decode twice
                    : v.materialize();

            for (Condition c : peakLevel) {
                if (!ConditionFilters.peakLevelHolds(c, scan, v, retainedMs1)) { rejected = true; break; }
            }
            if (rejected) continue;

            qualifying++;
            out.accept(v, scan, retainedMs1);
        }

        // Diagnostics: an empty result is a valid answer, but a SILENT empty result is a poor one (§5).
        diagnostics.addAll(stream.diagnostics());
        if (skippedEmpty > 0) {
            diagnostics.add("skipped " + skippedEmpty + " zero-peak scan(s); MassQL's loaders drop these, "
                    + "so they cannot qualify (Correction C35c)");
        }
        if (missingMs1 > 0) {
            diagnostics.add(missingMs1 + " scan(s) had an MS1MZ condition but no preceding MS1 scan, so they "
                    + "could not qualify. MGF has no survey scans at all, which makes MS1MZ unsatisfiable there");
        }
        if (qualifying == 0 && examined > 0) {
            diagnostics.add("no scans matched: " + examined + " scan(s) examined, "
                    + conditions.size() + " condition(s) applied");
        }
        return new ExecutionSummary(qualifying, examined, diagnostics);
    }

    private static boolean hasMs1Condition(List<Condition> peakLevel) {
        for (Condition c : peakLevel) {
            if (c instanceof Condition.Value cv
                    && cv.type() == edu.ucsd.idekerlab.massql.lang.ast.ConditionType.MS1MZ) {
                return true;
            }
        }
        return false;
    }
}
