package org.cytoscape.massql.lang;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.cytoscape.massql.MassqlParseException;
import org.cytoscape.massql.lang.ast.MassqlQuery;

/**
 * Turns query text into a typed AST. The only place ANTLR is driven.
 *
 * <p>Package-private on purpose: {@code Massql.parse} is the public entry point, so the
 * choice of parser stays an implementation detail and could be swapped for a hand-written
 * one without touching callers.
 */
public final class MassqlParserFacade {

    private MassqlParserFacade() {}

    /**
     * Error listener that THROWS.
     *
     * <p>ANTLR's default {@code ConsoleErrorListener} writes to stderr and then <b>recovers</b>,
     * returning a partial parse tree. That is the single easiest way to ship a parser that
     * silently accepts nonsense: without replacing it, malformed queries produce a
     * half-built AST and the rejection tests pass for the wrong reason.
     */
    private static final class ThrowingErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(
                Recognizer<?, ?> recognizer,
                Object offendingSymbol,
                int line,
                int charPositionInLine,
                String msg,
                RecognitionException e) {
            throw new MassqlParseException(
                    "<syntax>",
                    explain(msg, offendingSymbol)
                            + " (at position "
                            + (charPositionInLine + 1)
                            + ")",
                    charPositionInLine + 1,
                    e);
        }

        /**
         * Adds guidance for the traps that read as legal MassQL, because "mismatched input
         * 'WHERE'" tells a user nothing about what to do.
         */
        private static String explain(String msg, Object offendingSymbol) {
            String tok = String.valueOf(offendingSymbol);
            if (tok.contains("WHERE") || tok.contains("where") || tok.contains("Where")) {
                return msg
                        + ". Note the function-call form is required: "
                        + "`QUERY scaninfo(MS2DATA) WHERE ...`, not `QUERY scaninfo WHERE ...`";
            }
            if (tok.contains("filter")) {
                return msg + ". Note FILTER has no lowercase form in MassQL";
            }
            if (tok.contains("or'") || tok.equals("'or'")) {
                return msg + ". Note OR has no lowercase form in MassQL";
            }
            return msg;
        }
    }

    /** @throws MassqlParseException on a syntax error or an unsupported construct. */
    public static MassqlQuery parse(String queryText) {
        if (queryText == null) {
            throw new MassqlParseException("<empty>", "query text is null");
        }
        // massql_query.py strips the query file (load_query -> .read().strip()), and the
        // .massql files on disk end with a newline.
        String text = queryText.strip();
        if (text.isEmpty()) {
            throw new MassqlParseException("<empty>", "query text is empty");
        }

        ThrowingErrorListener listener = new ThrowingErrorListener();

        MassqlLexer lexer = new MassqlLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);

        MassqlParser parser = new MassqlParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(listener);

        MassqlParser.StatementContext tree;
        try {
            tree = parser.statement();
        } catch (MassqlParseException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never let an ANTLR internal failure surface as something other than our type;
            // "never a crash" is a requirement even on garbage input.
            throw new MassqlParseException(
                    "<syntax>", "could not parse query: " + e.getMessage(), -1, e);
        }

        try {
            return new AstBuilder().build(tree);
        } catch (MassqlParseException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MassqlParseException(
                    "<internal>", "could not build AST: " + e.getMessage(), -1, e);
        }
    }
}
