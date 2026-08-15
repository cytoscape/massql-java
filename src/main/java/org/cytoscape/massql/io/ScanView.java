package org.cytoscape.massql.io;

import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTable;

/** A single spectrum read from a file: metadata plus its decoded peaks. */
public record ScanView(
        int scanId,
        int msLevel,
        double rt,
        Polarity polarity,
        Double precmz,
        Integer ms1scan,
        Integer charge,
        SpectrumTable peaks) {}
