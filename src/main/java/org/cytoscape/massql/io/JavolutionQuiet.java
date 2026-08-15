package org.cytoscape.massql.io;

import javolution.context.LogContext;

final class JavolutionQuiet {
    private JavolutionQuiet() {}

    private static volatile boolean applied = false;

    static synchronized void ensure() {
        if (applied) return;
        applied = true;
        try {
            LogContext.LEVEL.reconfigure(LogContext.Level.WARNING);
        } catch (RuntimeException | LinkageError e) {
        }
    }
}
