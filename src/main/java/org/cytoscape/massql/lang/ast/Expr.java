package org.cytoscape.massql.lang.ast;

/**
 * Arithmetic expression tree.
 *
 * <p><b>Deliberately unfolded.</b> {@code 157.0857+10} stays a {@link Binary} rather than
 * becoming {@code 167.0857}: constant folding is the engine's job, because that is where
 * the numeric semantics live and folding with the wrong rounding would silently change
 * query results. The parser only has to represent the shape faithfully.
 */
public sealed interface Expr {

    /** A numeric literal. The grammar's {@code FLOAT} carries no sign; see {@link Unary}. */
    record Literal(double value) implements Expr {
        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    /** Binary arithmetic. Precedence is already resolved by the parser. */
    record Binary(Expr left, Op op, Expr right) implements Expr {
        public Binary {
            if (left == null || op == null || right == null) {
                throw new IllegalArgumentException("Binary operands and operator are required");
            }
        }

        @Override
        public String toString() {
            return "(" + left + " " + op.symbol() + " " + right + ")";
        }
    }

    /**
     * Unary {@code +} or {@code -}.
     *
     * <p>Exists because this grammar's {@code FLOAT} token is unsigned, unlike Lark's
     * {@code floating: /[-+]?(...)/}. ANTLR's maximal-munch lexer would otherwise read
     * {@code X+2} as {@code VARIABLE FLOAT(+2)} and break every additive expression in
     * the corpus. See docs/internals/GRAMMAR_NOTES.md.
     */
    record Unary(Op op, Expr operand) implements Expr {
        public Unary {
            if (op != Op.ADD && op != Op.SUB) {
                throw new IllegalArgumentException("unary operator must be ADD or SUB, got " + op);
            }
            if (operand == null) throw new IllegalArgumentException("operand is required");
        }

        @Override
        public String toString() {
            return "(" + op.symbol() + operand + ")";
        }
    }
}
