package edu.ucsd.idekerlab.massql;

import java.util.List;

import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * What {@link Massql#executeWithDiagnostics} returns: the rows, and anything the engine wants the
 * caller to know about how it got them.
 *
 * <p><b>The SDK logs nothing.</b> A valid-but-degenerate
 * query therefore returns its explanation here rather than printing it, and the caller decides what
 * to do with it: the CLI writes these to stderr, a GUI could show them in a dialog. An
 * empty result set is a legitimate answer; a <i>silent</i> empty result set is a poor one.
 *
 * <p><b>{@code diagnostics} is passed straight through</b> from {@code ExecutionSummary}
 * The SDK neither generates nor reformats diagnostic text — doing so would
 * put the same message in two places with two wordings. Its only job is to carry the list from the
 * executor to the caller.
 *
 * <p><b>Why {@code qualifyingScans} and {@code scansExamined} are not here.</b> The row count
 * already gives the first, and the second is a progress statistic with no consumer in the published
 * contract. Consumers depend on this record; widening it is a contract change,
 * so a caller wanting "examined N, matched M" should read the executor's summary rather than grow
 * this.
 *
 * @param rows the qualifying scans, ascending by scan id; empty, never null
 * @param diagnostics human-readable notes; empty, never null
 */
public record ExecutionResult(List<ScanInfoResult> rows, List<String> diagnostics) {

    /** Defensive copies, so a caller cannot mutate a result after the fact. */
    public ExecutionResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
