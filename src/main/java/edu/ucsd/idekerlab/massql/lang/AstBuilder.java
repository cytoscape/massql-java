package edu.ucsd.idekerlab.massql.lang;

import edu.ucsd.idekerlab.massql.MassqlParseException;
import edu.ucsd.idekerlab.massql.lang.ast.*;
import edu.ucsd.idekerlab.massql.lang.ast.Comparator;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Walks the ANTLR parse tree and builds the typed AST, rejecting every construct that is
 * out of scope for v1.
 *
 * <p><b>The grammar is permissive; this class is where scope is enforced.</b> That split is
 * deliberate: admitting the whole language means a rejection can name the offending
 * construct ({@code MassqlParseException.construct()}) instead of producing
 * "syntax error at 'formula'". 31 of the 46 reference parses need a named rejection.
 *
 * <p>This is the only class that touches both ANTLR types and AST types. Nothing ANTLR
 * escapes past it — asserted by {@code AstEncapsulationTest}.
 */
final class AstBuilder extends MassqlBaseVisitor<Object> {

    /** Rejects with the construct name and the standard message from the reject list. */
    private static MassqlParseException reject(String construct, ParserRuleContext where) {
        int pos = -1;
        if (where != null && where.getStart() != null) {
            // ANTLR columns are 0-based; MassqlParseException.position() is documented 1-based.
            pos = where.getStart().getCharPositionInLine() + 1;
        }
        return new MassqlParseException(construct, UnsupportedConstructs.message(construct), pos, null);
    }

    private static MassqlParseException reject(String construct, Token where) {
        int pos = where == null ? -1 : where.getCharPositionInLine() + 1;
        return new MassqlParseException(construct, UnsupportedConstructs.message(construct), pos, null);
    }

    // ---------------------------------------------------------------- statement

    MassqlQuery build(MassqlParser.StatementContext stmt) {
        MassqlParser.QueryContext ctx = stmt.query();
        QueryTypeInfo qt = queryType(ctx.queryType());

        List<Condition> where = ctx.whereClause() == null
                ? List.of()
                : conditions(ctx.whereClause().whereConditionList().fullCondition());

        // FILTER evaluates exactly like WHERE (Tech_Step9 §2); it is kept as a separate
        // list only so the AST round-trips the source query.
        List<Condition> filter = ctx.filterClause() == null
                ? List.of()
                : conditions(ctx.filterClause().filterConditionList().fullCondition());

        return new MassqlQuery(qt.function(), qt.source(), where, filter);
    }

    private record QueryTypeInfo(QueryFunction function, DataSource source) { }

    private QueryTypeInfo queryType(MassqlParser.QueryTypeContext ctx) {
        if (ctx instanceof MassqlParser.BareDataTypeContext bare) {
            // Legal MassQL, out of scope: a function is required.
            throw reject("<no function>", bare);
        }
        if (ctx instanceof MassqlParser.FunctionDataTypeWithToleranceContext tol) {
            // Only scanrangesum takes the TOLERANCE param, and it is out of scope anyway;
            // report the function so the message is about the real problem.
            throw reject(functionName(tol.function()), tol);
        }
        MassqlParser.FunctionDataTypeContext fd = (MassqlParser.FunctionDataTypeContext) ctx;
        String fn = functionName(fd.function());
        if (!"scaninfo".equals(fn)) {
            throw reject(fn, fd.function());
        }
        return new QueryTypeInfo(QueryFunction.SCANINFO, dataSource(fd.dataType()));
    }

    private static String functionName(MassqlParser.FunctionContext ctx) {
        return ctx.getText();
    }

    private static DataSource dataSource(MassqlParser.DataTypeContext ctx) {
        // Case variants are all accepted by the lexer; normalise here.
        String t = ctx.getText().toUpperCase(java.util.Locale.ROOT);
        return t.equals("MS1DATA") ? DataSource.MS1DATA : DataSource.MS2DATA;
    }

    // --------------------------------------------------------------- conditions

    private List<Condition> conditions(List<MassqlParser.FullConditionContext> ctxs) {
        List<Condition> out = new ArrayList<>(ctxs.size());
        for (MassqlParser.FullConditionContext fc : ctxs) {
            out.add(fullCondition(fc));
        }
        return out;
    }

    private Condition fullCondition(MassqlParser.FullConditionContext ctx) {
        // Validate the condition BEFORE its qualifiers so that when a query contains
        // several unsupported constructs, the one reported is the one that appears FIRST
        // IN THE SOURCE TEXT. For `MS1MZ=X-2:INTENSITYMATCH=Y` that is X, which is what a
        // user reading the error alongside their query expects.
        Condition base = condition(ctx.condition(), List.of());
        List<Qualifier> quals = new ArrayList<>();
        for (MassqlParser.QualifierContext q : ctx.qualifier()) {
            quals.add(qualifier(q));
        }
        return withQualifiers(base, quals);
    }

    private static Condition withQualifiers(Condition base, List<Qualifier> quals) {
        if (quals.isEmpty()) return base;
        if (base instanceof Condition.Value v) {
            return new Condition.Value(v.type(), v.values(), quals);
        }
        Condition.PolarityIs p = (Condition.PolarityIs) base;
        return new Condition.PolarityIs(p.polarity(), quals);
    }

    private Condition condition(MassqlParser.ConditionContext ctx, List<Qualifier> quals) {
        if (ctx instanceof MassqlParser.ValueConditionContext v) {
            return new Condition.Value(conditionType(v.conditionField()),
                                       List.of(expr(v.numericalExpression())), quals);
        }
        if (ctx instanceof MassqlParser.OrListConditionContext or) {
            List<Expr> values = new ArrayList<>();
            for (MassqlParser.NumericalExpressionContext e : or.numericalExpressionWithOr().numericalExpression()) {
                values.add(expr(e));
            }
            return new Condition.Value(conditionType(or.conditionField()), values, quals);
        }
        if (ctx instanceof MassqlParser.PolarityCondContext p) {
            String t = p.polarity().getText().toUpperCase(java.util.Locale.ROOT);
            return new Condition.PolarityIs(
                    t.equals("POSITIVE") ? Polarity.POSITIVE : Polarity.NEGATIVE, quals);
        }
        if (ctx instanceof MassqlParser.SubqueryConditionContext sq) {
            throw reject("nested subquery", sq);
        }
        if (ctx instanceof MassqlParser.WildcardConditionContext w) {
            throw reject("ANY", w);
        }
        if (ctx instanceof MassqlParser.VariableRangeConditionContext vr) {
            throw reject(vr.VARIABLE().getText(), vr);   // X=range(...) / X=massdefect(...)
        }
        if (ctx instanceof MassqlParser.MobilityCondContext mob) {
            throw reject("MOBILITY", mob);
        }
        throw new IllegalStateException("unhandled condition alternative: " + ctx.getClass().getSimpleName());
    }

    private ConditionType conditionType(MassqlParser.ConditionFieldContext ctx) {
        String t = ctx.getText();
        // MS2MZ is an alias for MS2PROD. Collapsed here rather than in the grammar so the
        // grammar stays a faithful mirror of msql.ebnf and the engine sees one spelling.
        if (t.equals("MS2MZ")) return ConditionType.MS2PROD;
        return ConditionType.valueOf(t);
    }

    // --------------------------------------------------------------- qualifiers

    private Qualifier qualifier(MassqlParser.QualifierContext ctx) {
        if (ctx instanceof MassqlParser.QualifierEqContext q) {
            return new Qualifier(qualifierType(q.qualifierField()), Comparator.EQ, expr(q.numericalExpression()));
        }
        if (ctx instanceof MassqlParser.QualifierGtContext q) {
            return new Qualifier(qualifierType(q.qualifierField()), Comparator.GT, expr(q.numericalExpression()));
        }
        if (ctx instanceof MassqlParser.QualifierLtContext q) {
            return new Qualifier(qualifierType(q.qualifierField()), Comparator.LT, expr(q.numericalExpression()));
        }
        if (ctx instanceof MassqlParser.QualifierIntensityMatchReferenceContext r) {
            throw reject("INTENSITYMATCHREFERENCE", r);
        }
        if (ctx instanceof MassqlParser.QualifierExcludedContext e) {
            throw reject("EXCLUDED", e);
        }
        if (ctx instanceof MassqlParser.QualifierMassDefectContext m) {
            throw reject("MASSDEFECT", m);
        }
        if (ctx instanceof MassqlParser.QualifierCardinalityContext c) {
            throw reject(c.cardinality().getText(), c);   // CARDINALITY or MATCHCOUNT
        }
        if (ctx instanceof MassqlParser.QualifierOtherScanContext o) {
            throw reject("OTHERSCAN", o);
        }
        throw new IllegalStateException("unhandled qualifier alternative: " + ctx.getClass().getSimpleName());
    }

    private QualifierType qualifierType(MassqlParser.QualifierFieldContext ctx) {
        String t = ctx.getText();
        // The intensity-match family parses as a qualifierField but is out of scope.
        if (t.startsWith("INTENSITYMATCH")) {
            throw reject(t, ctx);
        }
        return QualifierType.valueOf(t);
    }

    // -------------------------------------------------------------- expressions

    private Expr expr(MassqlParser.NumericalExpressionContext ctx) {
        if (ctx instanceof MassqlParser.LiteralContext lit) {
            return new Expr.Literal(Double.parseDouble(lit.floating().getText()));
        }
        if (ctx instanceof MassqlParser.ParenContext p) {
            return expr(p.numericalExpression());
        }
        if (ctx instanceof MassqlParser.MulDivContext md) {
            return new Expr.Binary(expr(md.numericalExpression(0)),
                                   md.MULTIPLY() != null ? Op.MUL : Op.DIV,
                                   expr(md.numericalExpression(1)));
        }
        if (ctx instanceof MassqlParser.AddSubContext as) {
            return new Expr.Binary(expr(as.numericalExpression(0)),
                                   as.PLUS() != null ? Op.ADD : Op.SUB,
                                   expr(as.numericalExpression(1)));
        }
        if (ctx instanceof MassqlParser.UnaryContext u) {
            return new Expr.Unary(u.PLUS() != null ? Op.ADD : Op.SUB, expr(u.numericalExpression()));
        }
        if (ctx instanceof MassqlParser.VariableRefContext v) {
            throw reject(v.VARIABLE().getText(), v);
        }
        if (ctx instanceof MassqlParser.FormulaCallContext f) {
            throw reject("formula()", f);
        }
        if (ctx instanceof MassqlParser.AminoAcidDeltaCallContext a) {
            throw reject("aminoaciddelta()", a);
        }
        if (ctx instanceof MassqlParser.PeptideCallContext p) {
            throw reject("peptide()", p);
        }
        throw new IllegalStateException("unhandled expression alternative: " + ctx.getClass().getSimpleName());
    }

    /** Unused: every node is reached through the typed methods above. */
    @Override protected Object aggregateResult(Object aggregate, Object nextResult) {
        return nextResult;
    }

    @Override public Object visitChildren(org.antlr.v4.runtime.tree.RuleNode node) {
        Object r = null;
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree c = node.getChild(i);
            r = c.accept(this);
        }
        return r;
    }
}
