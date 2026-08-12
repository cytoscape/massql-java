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
 * Public entry point for the MassQL SDK — parse a query, run it over a spectra file, get rows back.
 *
 * <p>This is the contract consumers code against, so it changes deliberately. Four entry
 * points, and no ANTLR, MSDK or vendored type appears in any signature — that is what keeps the
 * parser and readers swappable, and {@code ApiEncapsulationTest} asserts it.
 *
 * <h2>The usual shape</h2>
 *
 * <pre>{@code
 * MassqlQuery q = Massql.parse(queryText);
 * try (SpectraStream s = SpectraFile.open(path)) {
 *     List<ScanInfoResult> rows = Massql.execute(q, s, opts);
 * }
 * }</pre>
 *
 * <p>Or, for the common one-shot case, {@link #run} does all of it.
 *
 * <h2>⚠ Two rules that look contradictory and are not</h2>
 *
 * <p><b>Ownership:</b> {@code execute} never closes what it did not open. The caller decides when
 * the resource dies.
 *
 * <p><b>Reuse:</b> one stream serves <b>one</b> query. Running several queries over one <i>file</i>
 * means reopening it — {@code SpectraFile.open(path)} per query, each in its own
 * try-with-resources.
 *
 * <p>Both hold, because the distinction is <b>file</b> versus <b>stream</b>. A spent stream handed
 * to a second {@code execute} fails loudly rather than returning an empty list that reads as
 * "matched nothing" — {@link SpectraStream} is single-pass by type, not by convention. An earlier
 * whole-file design made re-querying free; this one does not.
 *
 * <h2>The SDK writes to no stream</h2>
 *
 * <p>Nothing here prints, logs, or touches {@code System.out}: an embedded library that writes to a
 * stream fights whatever its host already uses, and the host wins. Notes about a
 * valid-but-degenerate query come back through
 * {@link #executeWithDiagnostics}. Treating stdout as a data pipe is the <b>CLI's</b> contract, never
 * the SDK's.
 */
public final class Massql {

    private Massql() {}

    /**
     * Parses query text into a typed, immutable AST.
     *
     * <p>Leading and trailing whitespace is ignored, matching {@code massql_query.py}.
     *
     * @throws MassqlParseException on a syntax error, or on a construct that is valid MassQL but
     *     outside this version's {@code scaninfo} subset. The exception's
     *     {@link MassqlParseException#construct()} names the offender.
     */
    public static MassqlQuery parse(String queryText) {
        return MassqlParserFacade.parse(queryText);
    }

    /**
     * Runs a parsed query over an open stream and returns the qualifying scans.
     *
     * <p><b>Does not close {@code s}</b> — see the ownership rule above.
     *
     * @param opts may be null, meaning {@link MassqlOptions#defaults()}
     * @return rows ascending by scan id; empty and immutable when nothing matched, never null
     * @throws MassqlException if the query names a function or source this version cannot execute,
     *     or if the file's content cannot be read
     */
    public static List<ScanInfoResult> execute(MassqlQuery q, SpectraStream s, MassqlOptions opts) {
        return executeWithDiagnostics(q, s, opts).rows();
    }

    /**
     * As {@link #execute}, plus whatever the engine wants the caller to know.
     *
     * <p>This is the primitive; {@code execute} is the convenience that drops the diagnostics. They
     * are deliberately <b>not</b> two implementations — a second code path would drift, and the one
     * thing worse than a missing diagnostic is a diagnostic that disagrees with itself.
     */
    public static ExecutionResult executeWithDiagnostics(
            MassqlQuery q, SpectraStream s, MassqlOptions opts) {
        MassqlOptions effective = opts == null ? MassqlOptions.defaults() : opts;
        ScaninfoCollation collation = new ScaninfoCollation(effective);
        ExecutionSummary summary = QueryExecutor.execute(q, s, effective, collation);
        return new ExecutionResult(collation.rows(), summary.diagnostics());
    }

    /**
     * Parse, open, execute and close — the one-shot form.
     *
     * <p>Opens the file itself, so it also closes it, on <b>every</b> path including exceptions.
     * Use {@link #execute} when the caller owns the stream.
     *
     * @param opts may be null, meaning {@link MassqlOptions#defaults()}
     * @throws MassqlParseException if the query text will not parse — propagated unchanged, so the
     *     caller can read {@link MassqlParseException#construct()}
     * @throws MassqlException if the file cannot be opened or its content cannot be read
     */
    public static List<ScanInfoResult> run(String queryText, Path path, MassqlOptions opts) {
        return run(queryText, path, opts, SpectraFile::open);
    }

    /**
     * {@link #run} with the opener injected. <b>Exposed for tests</b>; production callers use the
     * three-argument form.
     *
     * <p>The seam exists for one assertion that cannot otherwise be made: <i>{@code run} closes what
     * it opened, including when the query throws</i>. That is a resource-leak guarantee a long-lived
     * {@code shutDown()} depends on, and with the opener hard-wired there is no way to observe
     * whether {@code close} ran. Counting file descriptors was the alternative and is not portable —
     * and a test that skips on some platforms proves nothing.
     *
     * <p>Package-private on purpose: it is invisible to consumers, so it widens no contract.
     */
    static List<ScanInfoResult> run(
            String queryText, Path path, MassqlOptions opts, Function<Path, SpectraStream> opener) {
        MassqlQuery q = parse(queryText);
        try (SpectraStream s = opener.apply(path)) {
            return execute(q, s, opts);
        }
    }
}
