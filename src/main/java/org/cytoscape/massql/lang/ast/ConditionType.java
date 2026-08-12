package org.cytoscape.massql.lang.ast;

/**
 * Condition fields that reach the AST.
 *
 * <p>Note what is <i>absent</i> and why:
 * <ul>
 *   <li>{@code MS2MZ} — an alias for {@link #MS2PROD}, collapsed by {@code AstBuilder}
 *       so the engine never sees two spellings of one thing.</li>
 *   <li>{@code POLARITY} — carries an enum, not a numeric expression, so it is a
 *       separate {@link Condition} implementation rather than a value here.</li>
 *   <li>{@code MOBILITY} and the {@code X}/{@code Y} range conditions — out of scope,
 *       rejected by name.</li>
 *   <li>{@code MASSDEFECT} — <b>a qualifier in the grammar, not a condition.</b>
 *       See {@code QualifierType}.</li>
 * </ul>
 */
public enum ConditionType {
    MS2PROD,
    MS2PREC,
    MS2NL,
    MS1MZ,
    RTMIN,
    RTMAX,
    SCANMIN,
    SCANMAX,
    CHARGE
}
