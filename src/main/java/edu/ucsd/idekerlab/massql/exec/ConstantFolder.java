package edu.ucsd.idekerlab.massql.exec;

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.lang.ast.Expr;
import edu.ucsd.idekerlab.massql.lang.ast.Op;

/**
 * Folds an {@link Expr} tree to a single {@code double}, once, before evaluation.
 *
 * <p><b>IEEE double semantics throughout — no rational arithmetic, no rounding to a "nice" value.</b> MassQL
 * does double arithmetic and the goal is to match it bit-for-bit, so {@code 0.1 + 0.2} must produce
 * {@code 0.30000000000000004} here exactly as it does there. "Tidying" that up would put every downstream
 * tolerance window off by an ULP.
 *
 * <p><b>Division by zero yields infinity rather than throwing.</b> An infinite target simply matches nothing,
 * which is the correct outcome for a degenerate query — an exception would turn a valid-but-useless query into
 * a crash.
 *
 * <p>⚠ <b>{@link Expr.Unary} is handled</b>, which is easy to overlook. The
 * spec named "{@code BinaryExpr} over {@code NumberLiteral}s" — neither type exists — and never mentioned
 * unary negation, so a folder written to it would silently leave {@code MS2NL=-18} unfolded.
 */
public final class ConstantFolder {

    private ConstantFolder() {}

    /**
     * Folds {@code e} to its double value.
     *
     * <p>Uses {@code instanceof} binding patterns rather than a pattern {@code switch}: pattern switches are
     * a <b>preview</b> feature under {@code <release>17</release>}, which this project targets, and
     * enabling preview features in a shipping library is not on the
     * table. This matches the idiom already used in `AstBuilder`.
     */
    public static double fold(Expr e) {
        if (e == null) throw new MassqlException("cannot fold a null expression");

        if (e instanceof Expr.Literal l) return l.value();

        if (e instanceof Expr.Unary u) {
            if (u.op() == Op.ADD) return fold(u.operand()); // unary plus is identity
            if (u.op() == Op.SUB) return -fold(u.operand());
            // Unreachable: Expr.Unary's constructor already rejects anything but ADD/SUB, which
            // makes
            // the invalid state unrepresentable rather than merely unevaluatable. Kept as a
            // belt-and-braces
            // guard so a future relaxation of that constructor fails here rather than silently
            // returning 0.
            throw new MassqlException("unary " + u.op() + " is not a meaningful operator");
        }

        if (e instanceof Expr.Binary b) return apply(fold(b.left()), b.op(), fold(b.right()));

        // Expr is sealed with exactly those three records, so this is unreachable -- but a future
        // fourth variant should fail here rather than silently return 0.
        throw new MassqlException("unhandled expression type: " + e.getClass().getName());
    }

    private static double apply(double l, Op op, double r) {
        return switch (op) {
            case ADD -> l + r;
            case SUB -> l - r;
            case MUL -> l * r;
                // Deliberately NOT guarded: l/0.0 is Infinity (or NaN for 0.0/0.0), and a condition
                // built on that matches nothing. See the class note.
            case DIV -> l / r;
        };
    }
}
