package org.cytoscape.massql.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.lang.ast.Comparator;
import org.cytoscape.massql.lang.ast.Condition;
import org.cytoscape.massql.lang.ast.ConditionType;
import org.cytoscape.massql.lang.ast.DataSource;
import org.cytoscape.massql.lang.ast.Expr;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.cytoscape.massql.lang.ast.Op;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.lang.ast.QualifierType;
import org.junit.jupiter.api.Test;

class AstShapeTest {
    private static Condition.Value firstValue(String q) {
        return (Condition.Value) Massql.parse(q).where().get(0);
    }

    @Test
    void ms2mzIsCollapsedToMs2prod() {
        assertEquals(
                ConditionType.MS2PROD,
                firstValue("QUERY scaninfo(MS2DATA) WHERE MS2MZ=100").type());
        assertEquals(
                ConditionType.MS2PROD,
                firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=100").type());
        assertEquals(
                Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2MZ=100").canonical(),
                Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=100").canonical());
    }

    @Test
    void arithmeticStaysUnfolded() {
        Expr e = firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=157.0857+10").values().get(0);
        assertInstanceOf(Expr.Binary.class, e, "expected an unfolded Binary, got " + e);
        Expr.Binary b = (Expr.Binary) e;
        assertEquals(Op.ADD, b.op());
        assertEquals(new Expr.Literal(157.0857), b.left());
        assertEquals(new Expr.Literal(10.0), b.right());
    }

    @Test
    void multiplyBindsTighterThanAdd() {
        Expr e = firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=2+3*4").values().get(0);
        Expr.Binary top = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Op.ADD, top.op(), "the root must be the ADD, i.e. 2 + (3*4)");
        assertInstanceOf(Expr.Binary.class, top.right());
        assertEquals(Op.MUL, ((Expr.Binary) top.right()).op());
    }

    @Test
    void parenthesesOverridePrecedence() {
        Expr e = firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=(2+3)*4").values().get(0);
        Expr.Binary top = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Op.MUL, top.op());
        assertEquals(Op.ADD, ((Expr.Binary) top.left()).op());
    }

    @Test
    void unaryMinusIsAnExpressionNotPartOfTheLiteral() {
        Expr e = firstValue("QUERY scaninfo(MS1DATA) WHERE RTMIN=-5").values().get(0);
        Expr.Unary u = assertInstanceOf(Expr.Unary.class, e);
        assertEquals(Op.SUB, u.op());
        assertEquals(new Expr.Literal(5.0), u.operand());
    }

    @Test
    void leadingDotFloatsParse() {
        Expr e = firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=.000002").values().get(0);
        assertEquals(new Expr.Literal(0.000002), e);
    }

    @Test
    void orListCollapsesToASingleConditionWithManyValues() {
        Condition.Value v =
                firstValue(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=(58.06513 OR 60.04439 OR 70.06513)");
        assertEquals(3, v.values().size());
        assertEquals(new Expr.Literal(58.06513), v.values().get(0));
        assertEquals(new Expr.Literal(70.06513), v.values().get(2));
    }

    @Test
    void aPlainValueConditionIsASingleElementList() {
        assertEquals(1, firstValue("QUERY scaninfo(MS2DATA) WHERE MS2PROD=226.18").values().size());
    }

    @Test
    void qualifiersKeepTheirComparator() {
        Condition.Value v =
                firstValue(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=184.0739:TOLERANCEMZ=0.01:INTENSITYPERCENT>30");
        assertEquals(2, v.qualifiers().size());
        assertEquals(QualifierType.TOLERANCEMZ, v.qualifiers().get(0).type());
        assertEquals(Comparator.EQ, v.qualifiers().get(0).comparator());
        assertEquals(QualifierType.INTENSITYPERCENT, v.qualifiers().get(1).type());
        assertEquals(
                Comparator.GT,
                v.qualifiers().get(1).comparator(),
                "'>' must survive as GT; the condition filters treats '=' and '>' differently");
    }

    @Test
    void polarityIsItsOwnConditionShape() {
        Condition c =
                Massql.parse("QUERY scaninfo(MS1DATA) WHERE POLARITY=Negative").where().get(0);
        Condition.PolarityIs p =
                assertInstanceOf(
                        Condition.PolarityIs.class,
                        c,
                        "polarity carries an enum, not a numeric expression");
        assertEquals(Polarity.NEGATIVE, p.polarity());
    }

    @Test
    void whereAndFilterAreKeptSeparate() {
        MassqlQuery q =
                Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=100 FILTER MS2PREC=200");
        assertEquals(1, q.where().size());
        assertEquals(1, q.filter().size());
        assertEquals(2, q.allConditions().size());
        assertTrue(q.canonical().contains("WHERE"));
        assertTrue(q.canonical().contains("FILTER"));
    }

    @Test
    void multipleAndConditionsPreserveSourceOrder() {
        MassqlQuery q =
                Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=660.2 AND MS2PROD=468.2");
        assertEquals(2, q.where().size());
        assertEquals(new Expr.Literal(660.2), ((Condition.Value) q.where().get(0)).values().get(0));
        assertEquals(new Expr.Literal(468.2), ((Condition.Value) q.where().get(1)).values().get(0));
    }

    @Test
    void dataSourceIsNormalisedAcrossCaseVariants() {
        for (String v : new String[] {"MS1DATA", "ms1data", "Ms1Data"}) {
            assertEquals(
                    DataSource.MS1DATA,
                    Massql.parse("QUERY scaninfo(" + v + ") WHERE MS1MZ=1").source());
        }
        for (String v : new String[] {"MS2DATA", "ms2data", "Ms2Data"}) {
            assertEquals(
                    DataSource.MS2DATA,
                    Massql.parse("QUERY scaninfo(" + v + ") WHERE MS2PROD=1").source());
        }
    }

    @Test
    void astIsImmutable() {
        MassqlQuery q = Massql.parse("QUERY scaninfo(MS2DATA) WHERE MS2PROD=(1 OR 2)");
        assertThrows(UnsupportedOperationException.class, () -> q.where().add(null));
        Condition.Value v = (Condition.Value) q.where().get(0);
        assertThrows(
                UnsupportedOperationException.class, () -> v.values().add(new Expr.Literal(3)));
        assertThrows(UnsupportedOperationException.class, () -> v.qualifiers().add(null));
    }

    @Test
    void aQueryWithNoConditionsIsValid() {
        MassqlQuery q = Massql.parse("QUERY scaninfo(MS1DATA)");
        assertTrue(q.where().isEmpty());
        assertTrue(q.filter().isEmpty());
        assertEquals("SCANINFO(MS1DATA)", q.canonical());
    }

    @Test
    void comparatorHasExactlyThreeConstantsAndNoNONE() {
        assertEquals(
                3,
                Comparator.values().length,
                "expected exactly {EQ, GT, LT} but found " + Arrays.toString(Comparator.values()));
        assertThrows(
                IllegalArgumentException.class,
                () -> Comparator.valueOf("NONE"),
                "NONE models a state the grammar cannot produce");
    }
}
