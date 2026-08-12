package org.cytoscape.massql;

/** Base class for every failure this SDK raises. Unchecked: callers handle a query
 *  failing the way they handle bad input, not with mandatory try/catch. */
public class MassqlException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MassqlException(String message) {
        super(message);
    }

    public MassqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
