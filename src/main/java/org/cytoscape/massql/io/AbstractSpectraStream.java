package org.cytoscape.massql.io;

import java.nio.file.Path;
import java.util.NoSuchElementException;

import org.cytoscape.massql.MassqlException;

abstract class AbstractSpectraStream implements SpectraStream {
    protected final Path path;

    private boolean peeked;

    private boolean drained;

    private boolean closed;

    protected AbstractSpectraStream(Path path) {
        this.path = path;
    }

    protected abstract boolean advance();

    protected abstract ScanView view();

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

    @Override
    public final void close() {
        if (closed) return;
        closed = true;
        releaseResources();
    }

    protected final void ensureOpen() {
        if (closed) throw new MassqlException("stream is closed: " + path);
    }
}
