package org.cytoscape.massql.lang.ast;

import java.util.List;

/** One condition in a {@code WHERE} or {@code FILTER} clause, with its qualifiers. */
public sealed interface Condition {
    /** The qualifiers attached to this condition via {@code :}. */
    List<Qualifier> qualifiers();

    /**
     * A field compared against one or more values. {@code values} has more than one element only
     * for an {@code OR} list — {@code MS2PROD=(58.06 OR 60.04)} — which the engine treats as "any
     * of these".
     */
    record Value(ConditionType type, List<Expr> values, List<Qualifier> qualifiers)
            implements Condition {
        public Value {
            if (type == null) throw new IllegalArgumentException("condition type is required");
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("a value condition needs at least one value");
            }
            values = List.copyOf(values);
            qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder(type.toString()).append('=');
            b.append(
                    values.size() == 1
                            ? values.get(0).toString()
                            : values.stream()
                                    .map(Object::toString)
                                    .reduce((x, y) -> x + " OR " + y)
                                    .orElse(""));
            qualifiers.forEach(q -> b.append(':').append(q));
            return b.toString();
        }
    }

    /** {@code POLARITY=Positive} / {@code POLARITY=Negative}. */
    record PolarityIs(Polarity polarity, List<Qualifier> qualifiers) implements Condition {
        public PolarityIs {
            if (polarity == null) throw new IllegalArgumentException("polarity is required");
            qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder("POLARITY=").append(polarity);
            qualifiers.forEach(q -> b.append(':').append(q));
            return b.toString();
        }
    }
}
