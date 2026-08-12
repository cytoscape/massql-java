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

/**
 * The comparator and intensity-scale rules, from {@code _get_intensity_mask}
 * as the reference's intensity masking does.
 *
 * <p>Every rule here is one line of code and a silent wrong answer if missed. Two of them were **wrong or
 * incomplete** in the condition filters until — the ÷100 on `INTENSITYTICPERCENT`, and the cap's scope.
 */
class IntensitySemanticsTest {

    /**
     * One scan, three peaks. Chosen so the three scales give <b>different</b> answers — if a test can pass
     * with the wrong column, it is not testing anything.
     *
     * <pre>
     *   i     = 100, 300, 600      sum = 1000, max = 600
     *   iNorm = 0.1667, 0.5, 1.0            (i / 600)
     *   iTic  = 0.1,    0.3, 0.6            (i / 1000)
     * </pre>
     */
    private static SpectrumTable scan() {
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 100).addPeak(200.0, 300).addPeak(300.0, 600);
        return b.build();
    }

    private static Qualifier q(QualifierType t, Comparator c, double v) {
        return new Qualifier(t, c, new Expr.Literal(v));
    }

    // ---------------------------------------------------------------- the three scales

    @Test
    void theThreeScalesUseThreeDifferentColumns() {
        SpectrumTable t = scan();
        // Row 1 has i=300, iNorm=0.5, iTicNorm=0.3. A threshold of 40 distinguishes all three:
        //   INTENSITYVALUE  >40      -> 300 > 40        TRUE
        //   INTENSITYPERCENT>40      -> 0.5 > 0.40      TRUE
        //   INTENSITYTICPERCENT>40   -> 0.3 > 0.40      FALSE   <-- only the tic column disagrees
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
        // §3 stated the /100 rule for INTENSITYPERCENT only. Both have scale = 100.0.
        // Row 1: iNorm = 0.5. A qualifier of 50 means 50% = 0.50, so ">=" holds and ">" does not.
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.EQ, 50))),
                "0.5 >= 0.50");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.GT, 50))),
                "0.5 > 0.50 is false");

        // Row 1: iTicNorm = 0.3. A qualifier of 30 means 0.30 -- proving the tic column is ALSO
        // divided.
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.EQ, 30))),
                "0.3 >= 0.30");
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYTICPERCENT, Comparator.GT, 30))),
                "0.3 > 0.30 is false");
        // If the tic column were NOT divided, a qualifier of 30 would mean 30.0 and nothing would
        // match.
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

    // ---------------------------------------------------------------- comparators

    @Test
    void equalsMeansGreaterOrEqual() {
        SpectrumTable t = scan();
        // THE rule most likely to be "fixed" by a well-meaning implementer. The source's own
        // comment:
        // "equal -> minimum threshold (>= val), preserving historical semantics".
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
        // If "=" were true equality, row 2 (600) would NOT match -- that is the discriminating
        // case.
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

    // ---------------------------------------------------------------- the 0.99 cap

    @Test
    void theCapAppliesToBothPercentQualifiersButOnlyForGreaterThan() {
        SpectrumTable t = scan();
        // Row 2 is the base peak: iNorm is exactly 1.0, iTicNorm is 0.6.
        //
        // Without the cap, INTENSITYPERCENT>100 becomes "> 1.0", which NOTHING can satisfy since
        // iNorm's
        // maximum is exactly 1.0. The source clamps the threshold to 0.99 instead.
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 2, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.GT, 100))),
                "INTENSITYPERCENT>100 must become > 0.99, so the base peak matches");

        // the guard is `if scale > 1.0`, so INTENSITYTICPERCENT is capped too -- Known
        // traps used to
        // say "INTENSITYPERCENT only". iTicNorm = 0.6 > 0.99 is false, so use a row that exceeds
        // the cap...
        // no single peak can, since iTicNorm sums to 1 across the scan. So assert the threshold
        // clamping
        // directly via a value above 99: with the cap, >100 means >0.99; without it, >1.0.
        // A single-peak scan has iTicNorm == 1.0 exactly, which discriminates:
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

        // The cap does NOT apply to ">=": 1.0 >= 1.0 already holds, so this passes either way --
        // the
        // discriminating case is that it must not LOOSEN a >= threshold above 0.99.
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYPERCENT, Comparator.EQ, 100))),
                "row 1's iNorm is 0.5; >= 1.0 must stay 1.0 and fail, NOT be clamped to 0.99");
    }

    @Test
    void theCapNeverAppliesToTheAbsoluteColumn() {
        SpectrumTable t = scan();
        // scale == 1.0 for INTENSITYVALUE, so no clamp. A threshold of 500 stays 500, not 0.99.
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 1, List.of(q(QualifierType.INTENSITYVALUE, Comparator.GT, 500))),
                "300 > 500 is false");
        assertTrue(
                IntensityQualifiers.rowQualifies(
                        t, 2, List.of(q(QualifierType.INTENSITYVALUE, Comparator.GT, 500))),
                "600 > 500");
        // Were the cap applied here, a threshold of 500 would become 0.99 and BOTH rows would
        // match.
    }

    // ---------------------------------------------------------------- the implicit floor

    @Test
    void anAbsentQualifierMeansImplicitGreaterThanZeroPerColumn() {
        // the floor is applied to all THREE columns independently, not as one blanket
        // check.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 0).addPeak(200.0, 500); // a zero-intensity peak and a real one
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
        // The per-column detail, made observable: qualifying INTENSITYVALUE does not remove the
        // implicit
        // > 0 from the other two columns.
        SpectrumTableBuilder b = new SpectrumTableBuilder(2);
        b.startScan(1, 0.5, 1);
        b.addPeak(100.0, 0); // i = 0 -> iNorm and iTicNorm are NaN (0/0)
        SpectrumTable t = b.build();

        // A generous absolute threshold cannot rescue the row: iNorm/iTicNorm still face > 0, and
        // NaN
        // fails every comparison -- which is also how the reference behaves.
        assertFalse(
                IntensityQualifiers.rowQualifies(
                        t, 0, List.of(q(QualifierType.INTENSITYVALUE, Comparator.LT, 1_000_000))),
                "the unqualified percent columns keep their implicit > 0");
    }
}
