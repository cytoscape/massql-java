package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.cytoscape.massql.lang.ast.Comparator;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.Qualifier;
import org.cytoscape.massql.lang.ast.QualifierType;
import org.junit.jupiter.api.Test;

class ToleranceTest {
    private static Qualifier ppm(double v) {
        return new Qualifier(QualifierType.TOLERANCEPPM, Comparator.EQ, new Expr.Literal(v));
    }

    private static Qualifier da(double v) {
        return new Qualifier(QualifierType.TOLERANCEMZ, Comparator.EQ, new Expr.Literal(v));
    }

    @Test
    void ppmWinsWhenBothAreGiven() {
        double half = Tolerance.halfWidthFor(List.of(ppm(5.0), da(100.0)), 500.0);
        assertEquals(500.0 * 5.0 / 1e6, half, 0.0, "ppm must win");

        assertEquals(
                500.0 * 5.0 / 1e6,
                Tolerance.halfWidthFor(List.of(da(100.0), ppm(5.0)), 500.0),
                0.0);
    }

    @Test
    void ppmIsConvertedFromTheTargetValue() {
        assertEquals(
                0.01,
                Tolerance.halfWidthFor(List.of(ppm(20.0)), 500.0),
                1e-15,
                "20 ppm of 500 is 0.01 Da");

        assertEquals(0.02, Tolerance.halfWidthFor(List.of(ppm(20.0)), 1000.0), 1e-15);
    }

    @Test
    void aNegativePpmStillYieldsAPositiveWindow() {
        assertEquals(0.01, Tolerance.halfWidthFor(List.of(ppm(-20.0)), 500.0), 1e-15);
    }

    @Test
    void daIsUsedVerbatim() {
        assertEquals(0.5, Tolerance.halfWidthFor(List.of(da(0.5)), 200.5), 0.0);
    }

    @Test
    void theDefaultIsPointOneDaltonWhenNeitherIsGiven() {
        assertEquals(0.1, Tolerance.halfWidthFor(List.of(), 500.0), 0.0);
        assertEquals(0.1, Tolerance.halfWidthFor(null, 500.0), 0.0);
        assertEquals(0.1, Tolerance.DEFAULT_DA, 0.0);

        Qualifier ip =
                new Qualifier(QualifierType.INTENSITYPERCENT, Comparator.EQ, new Expr.Literal(5));
        assertEquals(0.1, Tolerance.halfWidthFor(List.of(ip), 500.0), 0.0);
    }

    @Test
    void boundsAreCentredOnTheTarget() {
        List<Qualifier> q = List.of(da(0.5));
        assertEquals(200.0, Tolerance.loFor(q, 200.5), 1e-15);
        assertEquals(201.0, Tolerance.hiFor(q, 200.5), 1e-15);
    }

    @Test
    void theQualifierValueIsFoldedNotAssumedLiteral() {
        Expr sum =
                new Expr.Binary(
                        new Expr.Literal(10.0),
                        org.cytoscape.massql.lang.ast.Op.ADD,
                        new Expr.Literal(10.0));
        Qualifier q = new Qualifier(QualifierType.TOLERANCEPPM, Comparator.EQ, sum);
        assertEquals(0.01, Tolerance.halfWidthFor(List.of(q), 500.0), 1e-15, "10+10 ppm of 500");
    }
}
