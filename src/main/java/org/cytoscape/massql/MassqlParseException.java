package org.cytoscape.massql;

/**
 * Thrown for query text this version cannot parse or does not support. {@link #construct()} is
 * mandatory, not decoration: the rejection tests assert on it and the CLI names the offending
 * construct on stderr, where a generic "syntax error" would leave a user with nothing to act on.
 * {@code UnsupportedConstructs} is the single list it is drawn from.
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

    /** The offending construct, e.g. "scansum" or "FILTER". */
    public String construct() {
        return construct;
    }

    /** 1-based character offset in the query text, or -1 if not localizable. */
    public int position() {
        return position;
    }
}
