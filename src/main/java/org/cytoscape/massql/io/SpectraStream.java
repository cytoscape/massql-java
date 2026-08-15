package org.cytoscape.massql.io;

import java.util.List;

/** A forward-only cursor over the spectra of one file, in document order. */
public interface SpectraStream extends AutoCloseable {
    /** Is another spectrum available? Repeatable and free of caller-visible side effects. */
    boolean hasNext();

    /** Advances to the next spectrum and returns it. */
    ScanView next();

    /** Non-fatal notes accumulated so far, e.g. "skipped 3 spectra with ms level > 2". */
    List<String> diagnostics();

    /** Releases the memory mapping (mzML, mzXML) or open reader (MGF) this cursor holds. */
    @Override
    void close();
}
