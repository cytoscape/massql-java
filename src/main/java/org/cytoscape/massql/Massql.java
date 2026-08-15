package org.cytoscape.massql;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import org.cytoscape.massql.exec.ExecutionSummary;
import org.cytoscape.massql.exec.QueryExecutor;
import org.cytoscape.massql.exec.ScaninfoCollation;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.lang.MassqlParserFacade;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.cytoscape.massql.result.ScanInfoResult;

/**
 * Public entry point for the MassQL SDK — parse a query, run it over a spectra file, get rows
 * back.
 */
public final class Massql {
    private Massql() {}

    /** Parses query text into a typed, immutable AST. */
    public static MassqlQuery parse(String queryText) {
        return MassqlParserFacade.parse(queryText);
    }

    /** Runs a parsed query over an open stream and returns the qualifying scans. */
    public static List<ScanInfoResult> execute(MassqlQuery q, SpectraStream s, MassqlOptions opts) {
        return executeWithDiagnostics(q, s, opts).rows();
    }

    /** As {@link #execute}, plus whatever the engine wants the caller to know. */
    public static ExecutionResult executeWithDiagnostics(
            MassqlQuery q, SpectraStream s, MassqlOptions opts) {
        MassqlOptions effective = opts == null ? MassqlOptions.defaults() : opts;
        ScaninfoCollation collation = new ScaninfoCollation(effective);
        ExecutionSummary summary = QueryExecutor.execute(q, s, effective, collation);
        return new ExecutionResult(collation.rows(), summary.diagnostics());
    }

    /** Parse, open, execute and close — the one-shot form. */
    public static List<ScanInfoResult> run(String queryText, Path path, MassqlOptions opts) {
        return run(queryText, path, opts, SpectraFile::open);
    }

    /** {@link #run} with the opener injected. */
    static List<ScanInfoResult> run(
            String queryText, Path path, MassqlOptions opts, Function<Path, SpectraStream> opener) {
        MassqlQuery q = parse(queryText);
        try (SpectraStream s = opener.apply(path)) {
            return execute(q, s, opts);
        }
    }
}
