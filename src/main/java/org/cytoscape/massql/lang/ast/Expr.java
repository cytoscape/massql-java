package org.cytoscape.massql.lang.ast;

/** Arithmetic expression tree. */
public sealed interface Expr {
    /** A numeric literal. */
    record Literal(double value) implements Expr {
        @Override
        public String toString() {
            return Double.toString(value);
        }
    }

    /** Binary arithmetic. */
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

    /** Unary {@code +} or {@code -}. */
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
