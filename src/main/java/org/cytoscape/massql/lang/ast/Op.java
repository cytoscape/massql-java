package org.cytoscape.massql.lang.ast;

/** Arithmetic operator. */
public enum Op {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/");

    private final String symbol;

    Op(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
