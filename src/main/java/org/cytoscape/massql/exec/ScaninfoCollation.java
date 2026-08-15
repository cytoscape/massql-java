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
 * Turns qualifying scans into {@link ScanInfoResult} rows — the 7 native columns plus the 5 the
 * SDK must compute itself.
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
        int scanId = view.scanId();
        int msLevel = view.msLevel();
        Double precmz = view.precmz();
        Integer ms1scan = view.ms1scan();
        Integer charge = view.charge();

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

        double tic = Reductions.sum(scan, 0, Column.I);

        int topRow = Reductions.argmax(scan, 0, Column.I);

        Double basePeakI = topRow < 0 ? null : scan.intensity(topRow);
        Double basePeakMz = topRow < 0 ? null : scan.mz(topRow);

        PrecursorLookup.Result ms1 =
                PrecursorLookup.lookup(retainedMs1, ms1scan, precmz, precursorTolPpm);

        rows.add(
                new ScanInfoResult(
                        scanId,
                        clean(precmz),
                        ms1scan,
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

    /** NaN and ±infinity → null, so the serialized output is always valid JSON. */
    private static Double clean(Double v) {
        return v == null || Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
