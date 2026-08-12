package org.cytoscape.massql.exec;

import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.spectra.Column;
import org.cytoscape.massql.spectra.Reductions;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * Turns qualifying scans into {@link ScanInfoResult} rows — the 7 native columns plus the 5 the SDK
 * must compute itself.
 *
 * <p>Implements {@link QualifyingScanConsumer}, which is the intended shape: the executor streams and
 * this collects, so retained memory stays at one scan plus one MS1.
 *
 * <p><b>The contract is {@code docs/RESULT_SCHEMA.md}.</b> One uniform 12-key shape for both MS1DATA and
 * MS2DATA, discriminated by {@code mslevel} — so there is no shape branch here.
 *
 * <h2>Order of operations, which is load-bearing</h2>
 *
 * <b>compute → sentinel-convert → NaN-convert.</b> The precursor lookup needs the <b>raw {@code 0}</b>
 * of {@code ms1scan} to detect "no linked scan", so converting sentinels earlier changes results. That is
 * why {@link ScanView} hands out raw sentinels and this class owns the conversion.
 *
 * <h2>Why options arrive in the constructor</h2>
 *
 * {@link QualifyingScanConsumer#accept} takes {@code (view, scan, retainedMs1)} and no options — widening
 * that interface to carry {@code precursorTolPpm} would push a collation concern into the filters' API,
 * so the tolerance is injected here instead.
 */
public final class ScaninfoCollation implements QualifyingScanConsumer {

    private final double precursorTolPpm;
    private final List<ScanInfoResult> rows = new ArrayList<>();
    private int lastScanId = Integer.MIN_VALUE;

    public ScaninfoCollation(MassqlOptions opts) {
        this.precursorTolPpm = (opts == null ? MassqlOptions.defaults() : opts).precursorTolPpm();
    }

    /** The collected rows, in the order the stream produced them (scan id ascending). */
    public List<ScanInfoResult> rows() {
        return List.copyOf(rows);
    }

    @Override
    public void accept(ScanView view, SpectrumTable scan, SpectrumTable retainedMs1) {
        // ---- metadata comes from ScanView, deliberately.
        //
        // precmz/ms1scan/charge live on ScanIndex, and a single-scan table's index
        // agrees with the view. But reading them off a TABLE invites reading them off the WRONG
        // table:
        // retainedMs1.index().precmzOf(0) is the MS1 scan's precmz (0), not this scan's. ScanView
        // is the
        // reader's own metadata authority and cannot be confused with the other table.
        int scanId = view.scanId();
        int msLevel = view.msLevel();
        double rawPrecmz = view.precmz();
        int rawMs1scan = view.ms1scan();
        int rawCharge = view.charge();

        // Ordering is a promise QualifyingScanConsumer makes ("scan-id-ascending falls out of
        // document
        // order for free") and it is a property of the FIXTURES, not a guarantee: MGF scan ids come
        // from
        // SCANS= when present, and a file with non-monotonic SCANS= would break it. All current
        // fixtures
        // ascend -- PlusRise's 34,513 SCANS= are monotonic -- so assert it rather than discover
        // later
        // that the differential's row-order comparison was silently comparing misaligned rows.
        if (scanId < lastScanId) {
            throw new MassqlException(
                    "scans arrived out of order: "
                            + scanId
                            + " after "
                            + lastScanId
                            + ". Results must be scan-id ascending. An MGF "
                            + "with non-monotonic SCANS= would cause this.");
        }
        lastScanId = scanId;

        // ---- the two native computations.
        //
        // tic is a SUM over this scan's peaks -- MassQL's `i` renamed. Not the base peak:
        // `scanmaxint`
        // puts that in `i`, which is why the reference guards the rename to scaninfo queries.
        double tic = Reductions.sum(scan, 0, Column.I);

        // ---- base peaks, from THIS scan's own table, whatever its MS level.
        //
        // The reference computed these from its MS2 table and left-joined on scan, so
        // MS1DATA
        // rows missed the join and came back null. The rule is: MS1 ids join only to MS1 data. Here
        // that
        // is automatic -- `scan` IS the qualifying scan's table -- which is why the Java side never
        // had
        // the bug and only the golden needed regenerating.
        int topRow = Reductions.argmax(scan, 0, Column.I);
        // argmax returns -1 on an empty scan. The executor skips zero-peak scans so this
        // cannot
        // fire today, but Reductions' contract allows it and a NaN reaching the JSON would be a
        // null for
        // entirely the wrong reason.
        Double basePeakI = topRow < 0 ? null : scan.intensity(topRow);
        Double basePeakMz = topRow < 0 ? null : scan.mz(topRow);

        // ---- the precursor lookup, on RAW sentinels.
        PrecursorLookup.Result ms1 =
                PrecursorLookup.lookup(retainedMs1, rawMs1scan, rawPrecmz, precursorTolPpm);

        // ---- sentinel conversion, AFTER the lookup. Exactly three columns; never rt.
        Double precmz = rawPrecmz == 0.0 ? null : rawPrecmz;
        Integer ms1scan = rawMs1scan == 0 ? null : rawMs1scan;
        Integer charge = rawCharge == 0 ? null : rawCharge;

        rows.add(
                new ScanInfoResult(
                        scanId,
                        clean(precmz),
                        ms1scan,
                        // rt is NEVER null-converted: 0.0 is a genuine retention time, and it is
                        // 664 rows of
                        // the PlusRise golden. It is also never NaN-cleaned to null for the same
                        // reason -- but
                        // an infinite rt would be a reader bug, so let clean() catch that.
                        clean(view.rt()),
                        charge,
                        clean(tic),
                        msLevel,
                        clean(basePeakI),
                        clean(basePeakMz),
                        clean(ms1.ms1I()),
                        clean(ms1.ms1Precmz()),
                        clean(ms1.ms1BasePeakI())));
    }

    /**
     * NaN and ±infinity → null, so the serialized output is always valid JSON.
     *
     * <p>Mirrors the reference's NaN cleaning, which it applies
     * recursively to every float before {@code json.dump(..., allow_nan=False)}.
     */
    private static Double clean(Double v) {
        return v == null || Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
