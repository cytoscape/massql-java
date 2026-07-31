package edu.ucsd.idekerlab.massql;

import edu.ucsd.idekerlab.massql.lang.MassqlParserFacade;
import edu.ucsd.idekerlab.massql.lang.ast.MassqlQuery;

/**
 * Public entry point for the MassQL SDK.
 *
 * <p>This is the contract {@code massql-app} codes against, so it changes deliberately.
 * At Tech_Step4 only {@link #parse} exists; {@code execute} and {@code run} arrive in
 * Tech_Step11.
 *
 * <p>No ANTLR, MSDK or vendored type appears in any signature here — that is what keeps
 * the parser and readers swappable.
 */
public final class Massql {

    private Massql() { }

    /**
     * Parses query text into a typed, immutable AST.
     *
     * <p>Leading and trailing whitespace is ignored, matching {@code massql_query.py}.
     *
     * @throws MassqlParseException on a syntax error, or on a construct that is valid
     *         MassQL but outside this version's {@code scaninfo} subset. The exception's
     *         {@link MassqlParseException#construct()} names the offender.
     */
    public static MassqlQuery parse(String queryText) {
        return MassqlParserFacade.parse(queryText);
    }
}
