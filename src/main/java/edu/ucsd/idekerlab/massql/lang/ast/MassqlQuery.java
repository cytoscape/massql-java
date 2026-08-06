package edu.ucsd.idekerlab.massql.lang.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A parsed, validated MassQL query. Immutable.
 *
 * <p>Everything here is in scope for v1 by construction: {@code AstBuilder} rejects every
 * unsupported construct before an instance can be built, so the engine (Tech_Step9) never
 * needs defensive handling for {@code MOBILITY}, variables, {@code formula()} and the rest.
 */
public record MassqlQuery(QueryFunction function,
                          DataSource source,
                          List<Condition> where,
                          List<Condition> filter) {

    public MassqlQuery {
        if (function == null) throw new IllegalArgumentException("function is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        where = where == null ? List.of() : List.copyOf(where);
        filter = filter == null ? List.of() : List.copyOf(filter);
    }

    /** Every condition from both clauses, in order. Convenience for the engine. */
    public List<Condition> allConditions() {
        List<Condition> all = new ArrayList<>(where.size() + filter.size());
        all.addAll(where);
        all.addAll(filter);
        return List.copyOf(all);
    }

    /**
     * Stable textual form used by the conformance test to compare two ASTs.
     *
     * <p>Condition order is <b>preserved, not sorted</b>. The grammar's {@code AND} is a
     * flat sequence and MassQL evaluates conditions as a conjunction, so order carries no
     * semantics — but preserving it means this form round-trips the source query and a
     * reordering bug in {@code AstBuilder} stays visible instead of being normalised away.
     * See docs/harness/GRAMMAR_NOTES.md.
     */
    public String canonical() {
        StringBuilder b = new StringBuilder();
        b.append(function).append('(').append(source).append(')');
        if (!where.isEmpty()) {
            b.append(" WHERE ");
            for (int i = 0; i < where.size(); i++) {
                if (i > 0) b.append(" AND ");
                b.append(where.get(i));
            }
        }
        if (!filter.isEmpty()) {
            b.append(" FILTER ");
            for (int i = 0; i < filter.size(); i++) {
                if (i > 0) b.append(" AND ");
                b.append(filter.get(i));
            }
        }
        return b.toString();
    }

    @Override public String toString() { return canonical(); }
}
