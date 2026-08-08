package edu.ucsd.idekerlab.massql;

/**
 * Thrown for query text this version cannot parse or does not support.
 *
 * <p>{@link #construct()} is mandatory, not decoration. Tech_Step4's rejection tests
 * assert on it, and Tech_Step12 requires the CLI to name the offending construct on
 * stderr -- a generic "syntax error" would fail both. The reject list is also published
 * as the feature matrix in Tech_Step13, generated from code rather than hand-maintained.
 */
public class MassqlParseException extends MassqlException {
    private static final long serialVersionUID = 1L;

    private final String construct;
    private final int position;

    public MassqlParseException(String construct, String message) {
        this(construct, message, -1, null);
    }

    public MassqlParseException(String construct, String message, int position, Throwable cause) {
        super(message, cause);
        if (construct == null || construct.isBlank()) {
            throw new IllegalArgumentException(
                    "construct is mandatory: see MassqlParseException javadoc");
        }
        this.construct = construct;
        this.position = position;
    }

    /** The offending construct, e.g. "scansum" or "FILTER". Never null or blank. */
    public String construct() {
        return construct;
    }

    /** 1-based character offset in the query text, or -1 if not localizable. */
    public int position() {
        return position;
    }
}
