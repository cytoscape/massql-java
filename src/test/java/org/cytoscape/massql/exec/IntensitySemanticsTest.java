package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.cytoscape.massql.lang.ast.Comparator;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.Qualifier;
import org.cytoscape.massql.lang.ast.QualifierType;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;
import org.junit.jupiter.api.Test;

class IntensitySemanticsTest {
    private static SpectrumTable scan() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 100).addPeak(200.0, 300).addPeak(300.0, 600);
        return b.build();
    }

    private static Qualifier q(QualifierType t, Comparator c, double v) {
        return new Qualifier(t, c, new Expr.Literal(v));
    }

    @Test
    void theThreeScalesUseThreeDifferentColumns() {
        SpectrumTable t = scan();

        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.GT, 40))));
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.GT, 40))));
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.GT, 40))),
                "iTicNorm divides by the SUM (0.3), not the max -- confusing the two is a plausible "
                        + "wrong answer rather than an error");
    }

    @Test
    void bothPercentQualifiersDivideByOneHundred() {
        SpectrumTable t = scan();

        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.EQ, 50))),
                "0.5 >= 0.50");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.GT, 50))),
                "0.5 > 0.50 is false");

        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.EQ, 30))),
                "0.3 >= 0.30");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.GT, 30))),
                "0.3 > 0.30 is false");
    }

    @Test
    void intensityValueIsNotDivided() {
        SpectrumTable t = scan();
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.EQ, 300))),
                "300 >= 300 absolute");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.EQ, 301))));
    }

    @Test
    void equalsMeansGreaterOrEqual() {
        SpectrumTable t = scan();

        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.EQ, 300))),
                "exactly equal matches");
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 2, List.of(q(QualifierType.INTENSITYVALUE, Comparator.EQ, 300))),
                "600 >= 300 also matches");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 0, List.of(q(QualifierType.INTENSITYVALUE, Comparator.EQ, 300))),
                "100 >= 300 does not");
    }

    @Test
    void lessThanIsStrict() {
        SpectrumTable t = scan();
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 0, List.of(q(QualifierType.INTENSITYVALUE, Comparator.LT, 300))),
                "100 < 300");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.LT, 300))),
                "300 < 300 is false");
    }

    @Test
    void theCapAppliesToBothPercentQualifiersButOnlyForGreaterThan() {
        SpectrumTable t = scan();

        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 2, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.GT, 100))),
                "INTENSITYPERCENT>100 must become > 0.99, so the base peak matches");

        SpectrumTableBuilder sb = new SpectrumTableBuilder(2);
        sb.startScan(1, 0.5, 1);
        sb.addPeak(100.0, 500);
        SpectrumTable single = sb.build();
        assertEquals(1.0, single.iTicNorm(0), 0.0, "a single peak is 100% of the TIC");
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        single,
                        0,
                        List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.GT, 100))),
                "INTENSITYTICPERCENT>100 must ALSO be capped to > 0.99 (C37d)");

        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.EQ, 100))),
                "row 1's iNorm is 0.5; >= 1.0 must stay 1.0 and fail, NOT be clamped to 0.99");
    }

    @Test
    void theCapNeverAppliesToTheAbsoluteColumn() {
        SpectrumTable t = scan();

        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.GT, 500))),
                "300 > 500 is false");
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 2, List.of(q(QualifierType.INTENSITYVALUE, Comparator.GT, 500))),
                "600 > 500");
    }

    @Test
    void anAbsentQualifierMeansImplicitGreaterThanZeroPerColumn() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 0).addPeak(200.0, 500);
        SpectrumTable t = b.build();

        assertFalse(
                IntensityQualifiers.rowQualifies(t, 0, List.of()),
                "a zero-intensity peak fails the implicit > 0, so a bare MS2PROD needs a real peak");
        assertTrue(IntensityQualifiers.rowQualifies(t, 1, List.of()));
        assertFalse(
                IntensityQualifiers.rowQualifies(t, 0, null), "null qualifiers behave as absent");
    }

    @Test
    void anUnqualifiedColumnKeepsItsFloorEvenWhenAnotherIsQualified() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 0);
        SpectrumTable t = b.build();

        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 0, List.of(q(QualifierType.INTENSITYVALUE, Comparator.LT, 1_000_000))),
                "the unqualified percent columns keep their implicit > 0");
    }
}
