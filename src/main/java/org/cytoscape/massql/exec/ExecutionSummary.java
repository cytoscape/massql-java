package org.cytoscape.massql.exec;

import java.util.List;

/** What a run produced, plus its diagnostics. */
public record ExecutionSummary(int qualifyingScans, int scansExamined, List<String> diagnostics) {
    public ExecutionSummary {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
