package org.cytoscape.massql;

import java.util.List;

import org.cytoscape.massql.result.ScanInfoResult;

/**
 * What {@link Massql#executeWithDiagnostics} returns: the rows, and anything the engine wants the
 * caller to know about how it got them.
 */
public record ExecutionResult(List<ScanInfoResult> rows, List<String> diagnostics) {
    /** Defensive copies, so a caller cannot mutate a result after the fact. */
    public ExecutionResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
