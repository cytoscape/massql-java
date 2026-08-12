package org.cytoscape.massql.lang.ast;

/**
 * The query function. Only {@code scaninfo} is supported in v1.
 *
 * <p>The grammar admits all six of MassQL's functions so that {@code AstBuilder} can
 * reject the other five by name; they never reach the AST. A query with <b>no</b>
 * function at all ({@code QUERY MS2DATA WHERE …}) is legal MassQL but also out of scope,
 * and is likewise rejected — 3 of the 46 reference parses are that form.
 */
public enum QueryFunction {
    SCANINFO
}
