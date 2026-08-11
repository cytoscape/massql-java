package edu.ucsd.idekerlab.massql.io;

import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

/**
 * A single spectrum, as seen through a {@link SpectraStream} cursor.
 *
 * <p><b>Valid only until the next {@link SpectraStream#next()} call.</b> To retain a scan — which the
 * engine does for exactly one MS1 scan, to serve the precursor lookup — call {@link #materialize()}.
 *
 * <p>Metadata is available <i>without</i> decoding the peaks. That is deliberate and is where the
 * streaming design pays: a query with {@code RTMIN}, {@code SCANMIN}, {@code POLARITY}, {@code CHARGE}
 * or {@code MS2PREC} rejects most scans on metadata alone and never touches their binary arrays.
 *
 * <p>{@code precmz}, {@code ms1scan} and {@code charge} carry MassQL's raw <b>0 sentinel</b> when the
 * file recorded nothing. Converting 0 to null is the collation's job — doing it here would destroy the
 * distinction between "absent" and "already converted", and {@code ms1scan == 0} is specifically what
 * the precursor lookup tests for.
 */
public interface ScanView {

    /**
     * The scan's identifier, as MassQL derives it — which is <b>not</b> always its position in the file.
     *
     * <p>mzML parses the last {@code scan=N} segment of the spectrum id; mzXML uses {@code num}; MGF uses
     * {@code SCANS=} when present and otherwise the 1-based block index. This is the value
     * that reaches the result JSON's {@code scan} key, so the goldens are keyed on it.
     */
    int scanId();

    /** 1 or 2. Levels above 2 are skipped by the reader and reported via {@link SpectraStream#diagnostics()}. */
    int msLevel();

    /** Retention time in <b>minutes</b>, at exact double precision. This is what reaches the result JSON. */
    double rt();

    /** 1 = positive, 2 = negative, 0 = unknown. */
    int polarity();

    /** Precursor m/z, or {@code 0} if not recorded. MS2 only. */
    double precmz();

    /** Linked MS1 scan id <b>by document order</b>, or {@code 0} if none precedes this scan. */
    int ms1scan();

    /** Precursor charge, or {@code 0} if not recorded. */
    int charge();

    /**
     * How many peaks this scan holds, <b>without decoding them</b>.
     *
     * <p>Read from metadata — mzML's {@code defaultArrayLength}, mzXML's {@code peaksCount}, MGF's line
     * count — so it costs no base64 decode, inflate or array allocation. That is what lets the executor
     * skip zero-peak scans before paying for them, and why this is a separate accessor
     * rather than {@code materialize().rowCount()}.
     *
     * <p>{@code 0} is a real, expected value: PlusRise.mgf has 12,571 peak-less blocks, and mzML files
     * carry empty MS1 scans.
     */
    int peakCount();

    /**
     * Decodes this scan's peaks into a fresh single-scan {@link SpectrumTable}.
     *
     * <p>This is the hinge of the streaming design: every Step 5 primitive — {@code Reductions.sum},
     * {@code mzWindow}, {@code argmax}, the derived columns — works unchanged on a one-scan table, so
     * the store needed no modification to support streaming.
     *
     * <p>Safe to call more than once; each call returns an independent table. Not calling it at all is
     * the fast path.
     */
    SpectrumTable materialize();
}
