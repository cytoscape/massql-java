package org.cytoscape.massql.lang.ast;

/** One qualifier on a condition, e.g. {@code TOLERANCEPPM=5} or {@code INTENSITYPERCENT>30}. */
public record Qualifier(QualifierType type, Comparator comparator, Expr value) {
    public Qualifier {
        if (type == null || comparator == null || value == null) {
            throw new IllegalArgumentException(
                    "qualifier type, comparator and value are all required");
        }
    }

    @Override
    public String toString() {
        String cmp =
                switch (comparator) {
                    case EQ -> "=";
                    case GT -> ">";
                    case LT -> "<";
                };
        return type + cmp + value;
    }
}
