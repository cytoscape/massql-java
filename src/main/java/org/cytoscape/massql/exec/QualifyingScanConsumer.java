package org.cytoscape.massql.exec;

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.spectra.SpectrumTable;

/**
 * Receives one qualifying scan, while the stream is still positioned on it.
 *
 * <p><b>Why a callback rather than a returned collection</b>. Retained memory stays at
 * <b>one scan plus one MS1</b>, preserving the streaming property proven under {@code -Xmx48m}; only
 * one pass is needed, so peaks are decoded once; and scan-id-ascending order — which the collation and
 * the differential both require — falls out of document order for free. Returning a list would make memory
 * proportional to the number of <i>matches</i>, which a permissive query over a 500 MB file would blow.
 *
 * <p>The scaninfo collation is the intended implementation.
 *
 * <p><b>The arguments are valid only for the duration of the call.</b> {@code view} is the reader's reused
 * cursor and {@code scan} is this scan's single-scan table; retaining either past the call is a bug. Copy
 * what you need.
 */
@FunctionalInterface
public interface QualifyingScanConsumer {

    /**
     * @param view        metadata for the qualifying scan
     * @param scan        its peaks, as a single-scan table whose only ordinal is {@code 0}
     * @param retainedMs1 the linked MS1 scan by the document-order rule, or {@code null} if none precedes
     *                    it — which is where the {@code ms1scan} 0-sentinel comes from
     */
    void accept(ScanView view, SpectrumTable scan, SpectrumTable retainedMs1);
}
