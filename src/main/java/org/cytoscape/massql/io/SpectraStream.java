package org.cytoscape.massql.io;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * A forward-only cursor over the spectra of one file, in <b>document order</b>.
 *
 * <p>Document order is not an incidental detail: MassQL derives {@code ms1scan} from it (the most
 * recent preceding MS1 spectrum), which is also what lets the engine retain exactly one MS1 scan
 * instead of the whole file.
 *
 * <p><b>Why a cursor rather than whole-file tables.</b> Materialising every peak costs 41 bytes each,
 * so a 500 MB input projects to 1.0–1.9 GB of heap — an OOM in any host that did not budget for it.
 * Streaming bounds
 * retained memory by the largest single scan (measured: 33,335 peaks, 2.6 MB across all fixtures).
 * Every v1 condition is a per-scan computation, so nothing is lost.
 *
 * <p><b>Single-pass.</b> A stream is consumed once; there is no rewind. Callers wanting two queries
 * over one file reopen it. Once drained, {@link #hasNext()} returns {@code false} permanently and
 * {@link #next()} throws — so reusing a spent stream fails loudly rather than looking like a query
 * that matched nothing.
 *
 * <p><b>Deliberately NOT an {@link java.util.Iterator}</b>, despite the method names.
 *
 * <p><b>{@code close()} is load-bearing</b>, not ceremony: the cursor holds a memory mapping (mzML,
 * mzXML) or an open reader (MGF) for its whole lifetime. It must be idempotent.
 *
 * <p>The canonical loop:
 *
 * <pre>{@code
 * try (SpectraStream s = SpectraFile.open(path)) {
 *     while (s.hasNext()) {
 *         ScanView v = s.next();
 *         // ... valid until the following next()
 *     }
 * }
 * }</pre>
 */
public interface SpectraStream extends AutoCloseable {

    /**
     * Is another spectrum available?
     *
     * <p><b>Repeatable and free of caller-visible side effects.</b> Calling it twice between advances
     * returns the same answer and skips nothing — implementations read ahead internally and buffer the
     * result. That guarantee is what makes the canonical {@code while (hasNext())} loop correct, and
     * getting it wrong is the classic peek-state-machine bug: a second {@code hasNext()} silently
     * swallowing a scan.
     *
     * <p>Once the stream is drained this returns {@code false} <b>permanently</b>.
     *
     * @throws org.cytoscape.massql.MassqlException on malformed input — never a partial result
     */
    boolean hasNext();

    /**
     * Advances to the next spectrum and returns it.
     *
     * @throws NoSuchElementException if the stream is drained — including when a spent stream is
     *         handed to a second query, which is a bug worth a stack trace rather than an empty result
     * @throws org.cytoscape.massql.MassqlException on malformed input — never a partial result
     */
    ScanView next();

    /**
     * Non-fatal notes accumulated so far, e.g. "skipped 3 spectra with ms level > 2".
     *
     * <p>The SDK logs nothing, so diagnostics are returned
     * and the caller decides: the CLI prints them to stderr, a GUI could surface them in a
     * dialog. Accumulates as the stream advances, so read it <b>after</b> iterating.
     */
    List<String> diagnostics();

    /**
     * Releases the memory mapping (mzML, mzXML) or open reader (MGF) this cursor holds.
     *
     * <p><b>Idempotent</b> — calling it twice is harmless, which is what lets try-with-resources and an
     * explicit close coexist. Not overriding {@code AutoCloseable}'s {@code throws Exception} is
     * deliberate: a close failure here is not something a caller can act on, and declaring it would
     * force every call site into a catch block.
     */
    @Override
    void close();
}
