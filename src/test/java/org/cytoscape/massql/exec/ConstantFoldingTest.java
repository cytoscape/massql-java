package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.Op;
import org.junit.jupiter.api.Test;

/**
 * Arithmetic folding, with IEEE double semantics preserved exactly.
 *
 * <p>⚠ <b>{@code Expr.Unary} is covered here and the condition filters omitted it entirely</b> (e). The
 * spec said the folder reduces "{@code BinaryExpr} over {@code NumberLiteral}s" — neither type exists; the AST
 * is {@code Expr.Literal} / {@code Expr.Binary} / {@code Expr.Unary}. A folder written to that spec would
 * silently leave a negated literal such as {@code MS2NL=-18} unfolded.
 */
class ConstantFoldingTest {

    private static Expr lit(double v) {
        return new Expr.Literal(v);
    }

    private static Expr bin(double l, Op op, double r) {
        return new Expr.Binary(lit(l), op, lit(r));
    }

    @Test
    void theFourOperators() {
        assertEquals(3.0, ConstantFolder.fold(bin(1.0, Op.ADD, 2.0)));
        assertEquals(-1.0, ConstantFolder.fold(bin(1.0, Op.SUB, 2.0)));
        assertEquals(6.0, ConstantFolder.fold(bin(2.0, Op.MUL, 3.0)));
        assertEquals(0.5, ConstantFolder.fold(bin(1.0, Op.DIV, 2.0)));
    }

    @Test
    void aBareLiteralFoldsToItself() {
        assertEquals(226.18, ConstantFolder.fold(lit(226.18)));
    }

    // ---------------------------------------------------------------- Expr.Unary (C35e)

    @Test
    void unaryNegationFolds() {
        // The case the spec omitted. MS2NL=-18 parses to Unary(SUB, Literal(18)).
        assertEquals(-18.0, ConstantFolder.fold(new Expr.Unary(Op.SUB, lit(18.0))));
        assertEquals(
                18.0,
                ConstantFolder.fold(new Expr.Unary(Op.ADD, lit(18.0))),
                "unary plus is identity");
    }

    @Test
    void unaryNestsAndCombinesWithBinary() {
        // -(2 * 3) = -6
        Expr e = new Expr.Unary(Op.SUB, new Expr.Binary(lit(2.0), Op.MUL, lit(3.0)));
        assertEquals(-6.0, ConstantFolder.fold(e));
        // 500 - (-18) = 518, i.e. a negated literal inside a binary
        Expr f = new Expr.Binary(lit(500.0), Op.SUB, new Expr.Unary(Op.SUB, lit(18.0)));
        assertEquals(518.0, ConstantFolder.fold(f));
        // Double negation.
        assertEquals(
                18.0,
                ConstantFolder.fold(new Expr.Unary(Op.SUB, new Expr.Unary(Op.SUB, lit(18.0)))));
    }

    @Test
    void aUnaryMultiplyOrDivideCannotEvenBeCONSTRUCTED() {
        // Validation lives in Expr.Unary's own constructor -- a better place than the folder, since
        // it makes
        // the invalid state unrepresentable rather than merely unevaluatable. The folder's own
        // guard is
        // therefore defensive and unreachable, which is why this asserts IllegalArgumentException
        // from the
        // AST rather than MassqlException from the fold.
        assertThrows(IllegalArgumentException.class, () -> new Expr.Unary(Op.MUL, lit(2.0)));
        assertThrows(IllegalArgumentException.class, () -> new Expr.Unary(Op.DIV, lit(2.0)));
    }

    // ---------------------------------------------------------------- IEEE semantics

    @Test
    void ieeeSemanticsArePreservedNotTidiedUp() {
        // MassQL does double arithmetic; matching it bit-for-bit is the goal. Rounding 0.1+0.2 to a
        // "nice"
        // 0.3 would put every downstream tolerance window off by an ULP.
        assertEquals(
                Double.doubleToLongBits(0.1 + 0.2),
                Double.doubleToLongBits(ConstantFolder.fold(bin(0.1, Op.ADD, 0.2))));
        assertNotEquals(
                Double.doubleToLongBits(0.3),
                Double.doubleToLongBits(ConstantFolder.fold(bin(0.1, Op.ADD, 0.2))),
                "if this ever passes, someone has 'helpfully' rounded and the tolerances have shifted");
    }

    @Test
    void divisionByZeroYieldsInfinityRatherThanThrowing() {
        // An infinite target simply matches nothing, which is the right outcome for a degenerate
        // query.
        // Throwing would turn a valid-but-useless query into a crash.
        assertEquals(Double.POSITIVE_INFINITY, ConstantFolder.fold(bin(1.0, Op.DIV, 0.0)));
        assertEquals(Double.NEGATIVE_INFINITY, ConstantFolder.fold(bin(-1.0, Op.DIV, 0.0)));
        assertTrue(Double.isNaN(ConstantFolder.fold(bin(0.0, Op.DIV, 0.0))), "0/0 is NaN");
    }

    @Test
    void precedenceComesFromTheAstNotFromReparsing() {
        // The folder walks whatever tree it is given; grouping is the parser's job. 1 + (2 * 3) =
        // 7,
        // while (1 + 2) * 3 = 9 -- same tokens, different trees, and the folder must not "fix"
        // either.
        Expr rightHeavy =
                new Expr.Binary(lit(1.0), Op.ADD, new Expr.Binary(lit(2.0), Op.MUL, lit(3.0)));
        Expr leftHeavy =
                new Expr.Binary(new Expr.Binary(lit(1.0), Op.ADD, lit(2.0)), Op.MUL, lit(3.0));
        assertEquals(7.0, ConstantFolder.fold(rightHeavy));
        assertEquals(9.0, ConstantFolder.fold(leftHeavy));
    }

    @Test
    void aNullExpressionIsRefused() {
        assertThrows(MassqlException.class, () -> ConstantFolder.fold(null));
    }
}
