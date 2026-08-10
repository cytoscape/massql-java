package edu.ucsd.idekerlab.massql.exec;

import java.util.List;

/**
 * What a run produced, plus the diagnostics Tech_Step9 §5 requires.
 *
 * <p>The SDK <b>logs nothing</b> — `DEPENDENCY_POLICY.md` constraint 2 (not 5, as Tech_Step9 §5 used to say).
 * So a valid-but-degenerate query returns its explanation here rather than printing it: Tech_Step11's CLI
 * writes these to stderr; a GUI could show them in a dialog.
 *
 * <p>An empty result set is a legitimate answer — Tech_Step12 requires an empty JSON array and exit 0 — but a
 * <i>silent</i> empty result set is a poor one. That is the whole reason this record carries diagnostics
 * rather than just a count.
 *
 * @param qualifyingScans how many scans satisfied every condition
 * @param scansExamined   how many scans the stream yielded, before any filtering
 * @param diagnostics     human-readable notes; empty, never null
 */
public record ExecutionSummary(int qualifyingScans, int scansExamined, List<String> diagnostics) {

    public ExecutionSummary {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
