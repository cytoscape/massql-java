/**
 * The typed, immutable MassQL AST — the tested surface of the parser and the input to
 * the query engine.
 *
 * <p><b>No ANTLR type appears anywhere in this package.</b> That is what keeps the parser
 * swappable (hand-written, or the remote {@code /parse} escape hatch) without touching
 * the engine, and it is asserted by {@code AstEncapsulationTest}.
 *
 */
package edu.ucsd.idekerlab.massql.lang.ast;
