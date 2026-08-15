package org.cytoscape.massql.exec;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.Op;

/** Folds an {@link Expr} tree to a single {@code double}, once, before evaluation. */
public final class ConstantFolder {
    private ConstantFolder() {}

    /** Folds {@code e} to its double value. */
    public static double fold(Expr e) {
        if (e == null) throw new MassqlException("cannot fold a null expression");

        if (e instanceof Expr.Literal l) return l.value();

        if (e instanceof Expr.Unary u) {
            if (u.op() == Op.ADD) return fold(u.operand());
            if (u.op() == Op.SUB) return -fold(u.operand());

            throw new MassqlException("unary " + u.op() + " is not a meaningful operator");
        }

        if (e instanceof Expr.Binary b) return apply(fold(b.left()), b.op(), fold(b.right()));

        throw new MassqlException("unhandled expression type: " + e.getClass().getName());
    }

    private static double apply(double l, Op op, double r) {
        return switch (op) {
            case ADD -> l + r;
            case SUB -> l - r;
            case MUL -> l * r;

            case DIV -> l / r;
        };
    }
}
