package org.cytoscape.massql.exec;

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.spectra.SpectrumTable;

/** Receives one qualifying scan, while the stream is still positioned on it. */
@FunctionalInterface
public interface QualifyingScanConsumer {
    void accept(ScanView view, SpectrumTable scan, SpectrumTable retainedMs1);
}
