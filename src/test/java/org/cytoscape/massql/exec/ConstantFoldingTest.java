package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.Op;
import org.junit.jupiter.api.Test;

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

    @Test
    void unaryNegationFolds() {
        assertEquals(-18.0, ConstantFolder.fold(new Expr.Unary(Op.SUB, lit(18.0))));
        assertEquals(
                18.0,
                ConstantFolder.fold(new Expr.Unary(Op.ADD, lit(18.0))),
                "unary plus is identity");
    }

    @Test
    void unaryNestsAndCombinesWithBinary() {
        Expr e = new Expr.Unary(Op.SUB, new Expr.Binary(lit(2.0), Op.MUL, lit(3.0)));
        assertEquals(-6.0, ConstantFolder.fold(e));

        Expr f = new Expr.Binary(lit(500.0), Op.SUB, new Expr.Unary(Op.SUB, lit(18.0)));
        assertEquals(518.0, ConstantFolder.fold(f));

        assertEquals(
                18.0,
                ConstantFolder.fold(new Expr.Unary(Op.SUB, new Expr.Unary(Op.SUB, lit(18.0)))));
    }

    @Test
    void aUnaryMultiplyOrDivideCannotEvenBeCONSTRUCTED() {
        assertThrows(IllegalArgumentException.class, () -> new Expr.Unary(Op.MUL, lit(2.0)));
        assertThrows(IllegalArgumentException.class, () -> new Expr.Unary(Op.DIV, lit(2.0)));
    }

    @Test
    void ieeeSemanticsArePreservedNotTidiedUp() {
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
        assertEquals(Double.POSITIVE_INFINITY, ConstantFolder.fold(bin(1.0, Op.DIV, 0.0)));
        assertEquals(Double.NEGATIVE_INFINITY, ConstantFolder.fold(bin(-1.0, Op.DIV, 0.0)));
        assertTrue(Double.isNaN(ConstantFolder.fold(bin(0.0, Op.DIV, 0.0))), "0/0 is NaN");
    }

    @Test
    void precedenceComesFromTheAstNotFromReparsing() {
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
