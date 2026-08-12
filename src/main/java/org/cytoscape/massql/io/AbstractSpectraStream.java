package org.cytoscape.massql.io;

import java.nio.file.Path;
import java.util.NoSuchElementException;

import org.cytoscape.massql.MassqlException;

/**
 * The cursor mechanics every reader shares: the {@code hasNext()}/{@code next()} peek machine, the
 * closed flag, and idempotent {@link #close()}.
 *
 * <p><b>Why a base class rather than three copies.</b> {@code MgfReader}, {@code MzmlReader} and
 * {@code MzxmlReader} differ entirely in how they *parse* — a line reader, and two different XML walks —
 * but not at all in how they *iterate*. Written out per reader, the peek machine was ~25 identical lines
 * three times, which is three places for the same subtle bug: a {@code hasNext()} that consumes.
 *
 * <p>Subclasses supply two things and inherit the rest:
 *
 * <ul>
 *   <li>{@link #advance()} — parse forward to the next spectrum, or report end of input.</li>
 *   <li>{@link #view()} — the {@link ScanView} that {@code advance()} just populated.</li>
 * </ul>
 *
 * <p>{@link #hasNext()} and {@link #next()} are {@code final}: the contract they implement — repeatable
 * peeking, permanent exhaustion, {@code NoSuchElementException} past the end — is the part that must not
 * vary between readers, and it is the part {@code SpectraStreamContractTest} asserts identically for all
 * three.
 */
abstract class AbstractSpectraStream implements SpectraStream {

    /** The file being read. Held for error messages, which name it. */
    protected final Path path;

    /** {@code advance()} has run and produced a spectrum that {@code next()} has not yet handed out. */
    private boolean peeked;

    /** End of input reached. Latches: once true it never goes back, so a spent stream cannot revive. */
    private boolean drained;

    private boolean closed;

    protected AbstractSpectraStream(Path path) {
        this.path = path;
    }

    /**
     * Parses forward to the next spectrum, leaving it available from {@link #view()}.
     *
     * <p>Called at most once per spectrum — the base class buffers the outcome, so implementations need
     * no peek logic of their own and must not second-guess whether a caller "already" advanced.
     *
     * @return {@code false} at end of input
     * @throws MassqlException on malformed content — never a partial result
     */
    protected abstract boolean advance();

    /**
     * The spectrum the most recent successful {@link #advance()} populated.
     *
     * <p>Implementations return their single reused instance, never a fresh object; that is what bounds
     * retained memory to one scan.
     */
    protected abstract ScanView view();

    /** Releases this reader's resources. Called at most once — the base class guarantees that. */
    protected abstract void releaseResources();

    @Override
    public final boolean hasNext() {
        ensureOpen();
        if (drained) return false;
        if (peeked) return true;
        if (advance()) {
            peeked = true;
            return true;
        }
        drained = true;
        return false;
    }

    @Override
    public final ScanView next() {
        if (!hasNext()) {
            throw new NoSuchElementException(
                    "no more spectra in "
                            + path
                            + "; a stream is single-pass -- reopen the file to query it again");
        }
        peeked = false;
        return view();
    }

    /**
     * Idempotent, as {@link SpectraStream#close()} requires — the guard lives here so each reader does
     * not repeat it, and so try-with-resources can coexist with an explicit close.
     */
    @Override
    public final void close() {
        if (closed) return;
        closed = true;
        releaseResources();
    }

    /**
     * A closed stream must fail rather than report "no more spectra", which a caller would read as an
     * empty file.
     */
    protected final void ensureOpen() {
        if (closed) throw new MassqlException("stream is closed: " + path);
    }
}
