package edu.ucsd.idekerlab.massql.io;

import java.util.List;

/**
 * A forward-only cursor over the spectra of one file, in <b>document order</b>.
 *
 * <p>Document order is not an incidental detail: MassQL derives {@code ms1scan} from it (the most
 * recent preceding MS1 spectrum), which is also what lets the engine retain exactly one MS1 scan
 * instead of the whole file.
 *
 * <p><b>Why a cursor rather than whole-file tables.</b> Materialising every peak costs 41 bytes each,
 * so a 500 MB input projects to 1.0–1.9 GB of heap — an OOM inside Cytoscape. Streaming bounds
 * retained memory by the largest single scan (measured: 33,335 peaks, 2.6 MB across all fixtures).
 * Every v1 condition is a per-scan computation, so nothing is lost.
 *
 * <p><b>Single-pass.</b> A stream is consumed once; there is no rewind. Callers wanting two queries
 * over one file reopen it.
 *
 * <p><b>{@code close()} is load-bearing</b>, not ceremony: the cursor holds a memory mapping (mzML,
 * mzXML) or an open reader (MGF) for its whole lifetime. It must be idempotent.
 */
public interface SpectraStream extends AutoCloseable {

    /**
     * Advances to the next spectrum.
     *
     * @return false at end of file
     * @throws edu.ucsd.idekerlab.massql.MassqlException on malformed input — never a partial result
     */
    boolean next();

    /** The current spectrum. Valid only until the next {@link #next()}. */
    ScanView current();

    Format format();

    /**
     * Non-fatal notes accumulated so far, e.g. "skipped 3 spectra with ms level > 2".
     *
     * <p>The SDK logs nothing (`DEPENDENCY_POLICY.md` constraint 2), so diagnostics are returned and
     * the caller decides: the CLI prints them to stderr, the Cytoscape app can surface them in a
     * dialog.
     */
    List<String> diagnostics();

    @Override
    void close();
}
