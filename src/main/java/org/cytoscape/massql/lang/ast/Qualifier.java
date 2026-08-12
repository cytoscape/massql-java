package org.cytoscape.massql.lang.ast;

/**
 * One qualifier on a condition, e.g. {@code TOLERANCEPPM=5} or {@code INTENSITYPERCENT>30}.
 *
 * <p>The value stays an unfolded {@link Expr} for the same reason as everywhere else in
 * this AST — the engine owns the arithmetic.
 */
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
