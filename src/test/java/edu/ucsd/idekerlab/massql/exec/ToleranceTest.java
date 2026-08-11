package edu.ucsd.idekerlab.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.lang.ast.Comparator;
import edu.ucsd.idekerlab.massql.lang.ast.Expr;
import edu.ucsd.idekerlab.massql.lang.ast.Qualifier;
import edu.ucsd.idekerlab.massql.lang.ast.QualifierType;

/**
 * The tolerance rules, from {@code _get_mz_tolerance} (`msql_engine_filters.py:5-17`).
 *
 * <p>That file — not `msql_engine.py` — is the authority for this rule. Getting any of these wrong is a
 * silent wrong answer: no exception, just a different set of
 * rows than MassQL returns.
 */
class ToleranceTest {

    private static Qualifier ppm(double v) {
        return new Qualifier(QualifierType.TOLERANCEPPM, Comparator.EQ, new Expr.Literal(v));
    }

    private static Qualifier da(double v) {
        return new Qualifier(QualifierType.TOLERANCEMZ, Comparator.EQ, new Expr.Literal(v));
    }

    @Test
    void ppmWinsWhenBothAreGiven() {
        // NOT "narrower wins", NOT an error. The source checks ppm first and returns, so a 5 ppm
        // qualifier
        // beats a 100 Da one even though the Da window is vastly wider. Choosing "narrower" would
        // happen to
        // agree here and disagree whenever the Da value is the tighter of the two.
        double half = Tolerance.halfWidthFor(List.of(ppm(5.0), da(100.0)), 500.0);
        assertEquals(500.0 * 5.0 / 1e6, half, 0.0, "ppm must win");

        // And in the other declaration order -- the rule is about which TYPE wins, not which comes
        // first.
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
        // Computed from the TARGET, so the width scales with the target rather than being fixed.
        assertEquals(0.02, Tolerance.halfWidthFor(List.of(ppm(20.0)), 1000.0), 1e-15);
    }

    @Test
    void aNegativePpmStillYieldsAPositiveWindow() {
        // The source wraps the conversion in abs(), so a negative ppm does not invert the window.
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
        // An intensity qualifier is not a tolerance qualifier -- the default still applies.
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
        // Tolerances can be arithmetic expressions; folding happens once, here.
        Expr sum =
                new Expr.Binary(
                        new Expr.Literal(10.0),
                        edu.ucsd.idekerlab.massql.lang.ast.Op.ADD,
                        new Expr.Literal(10.0));
        Qualifier q = new Qualifier(QualifierType.TOLERANCEPPM, Comparator.EQ, sum);
        assertEquals(0.01, Tolerance.halfWidthFor(List.of(q), 500.0), 1e-15, "10+10 ppm of 500");
    }
}
