package org.cytoscape.massql.io;

import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * A single spectrum read from a file: metadata plus its decoded peaks.
 *
 * @param scanId as MassQL derives it — mzML's last {@code scan=N} segment, mzXML's {@code num}, MGF's
 *     {@code SCANS=} or the 1-based block index
 * @param rt retention time in minutes
 * @param polarity {@code null} when the file records none
 * @param precmz {@code null} when not recorded; MS2 only
 * @param ms1scan preceding MS1 scan id by document order, {@code null} when none precedes
 * @param charge {@code null} when not recorded
 */
public record ScanView(
        int scanId,
        int msLevel,
        double rt,
        Polarity polarity,
        Double precmz,
        Integer ms1scan,
        Integer charge,
        SpectrumTable peaks) {}
