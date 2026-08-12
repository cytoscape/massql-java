package org.cytoscape.massql.io;

import javolution.context.LogContext;

/**
 * Stops javolution printing to <b>stdout</b>.
 *
 * <p><b>Why this exists.</b> {@code XMLStreamReaderImpl} calls {@code LogContext.info(...)} every time
 * it grows its internal character buffer — "Data buffer increased to 262144" and so on. Large XML text
 * nodes trigger it, and an mzML {@code <binary>} element is exactly that, so it fires on essentially
 * every real file. Measured on {@code small.mzML}: 6 lines, and they land on <b>stdout</b>.
 *
 * <p><b>Why that is a defect, stated at the right layer.</b> This class sits in the <b>SDK</b>, and the
 * rule governing the SDK is simple: <b>it logs nothing at all</b>,
 * to stdout or stderr. Diagnostics are <i>returned</i> to the caller via
 * {@link SpectraStream#diagnostics()}, so the caller decides where they go. A library printing uninvited
 * is the whole defect; which stream it chose is incidental — which is why {@code StdoutCleanlinessTest}
 * asserts <i>both</i> streams stay silent.
 *
 * <p>Secondarily, and at a <b>different layer</b>: stray stdout output would also corrupt the <b>Java
 * CLI</b>'s JSON payload in its default output mode (the differential is such a
 * consumer). Real, but not the governing rule here. See <i>Terminology</i> in
 * the SDK/CLI layer distinction — this justification was originally
 * written the other way round, which read as "the SDK treats stdout as a data pipe". It does not.
 *
 * <p>{@code LogContext.LEVEL} is a {@code Configurable}, so raising it above {@code INFO} suppresses these
 * without touching {@code System.out} — swapping the stream would be both racy and rude to the host
 * application. This is global JVM state, which is not ideal for a library, but the change is narrow: it
 * only raises a threshold, so javolution's {@code WARNING}/{@code ERROR}/{@code FATAL} still get through.
 *
 * <p>Applied once, idempotently, from the constructor of every reader that drives javolution.
 */
final class JavolutionQuiet {

    private JavolutionQuiet() {}

    private static volatile boolean applied = false;

    static synchronized void ensure() {
        if (applied) return;
        applied = true;
        try {
            LogContext.LEVEL.reconfigure(LogContext.Level.WARNING);
        } catch (RuntimeException | LinkageError e) {
            // Never let log configuration break a read. If a future javolution refuses the
            // reconfigure, the worst case is stray INFO lines -- which StdoutCleanlinessTest
            // catches
            // at the SDK layer (constraint 2), before the Java CLI's payload is ever involved.
        }
    }
}
