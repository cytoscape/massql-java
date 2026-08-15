package org.cytoscape.massql.lang.ast;

import java.util.ArrayList;
import java.util.List;

/** A parsed, validated MassQL query. */
public record MassqlQuery(
        QueryFunction function, DataSource source, List<Condition> where, List<Condition> filter) {
    public MassqlQuery {
        if (function == null) throw new IllegalArgumentException("function is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        where = where == null ? List.of() : List.copyOf(where);
        filter = filter == null ? List.of() : List.copyOf(filter);
    }

    /** Every condition from both clauses, in order. */
    public List<Condition> allConditions() {
        List<Condition> all = new ArrayList<>(where.size() + filter.size());
        all.addAll(where);
        all.addAll(filter);
        return List.copyOf(all);
    }

    /** Stable textual form used by the conformance test to compare two ASTs. */
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

    @Override
    public String toString() {
        return canonical();
    }
}
