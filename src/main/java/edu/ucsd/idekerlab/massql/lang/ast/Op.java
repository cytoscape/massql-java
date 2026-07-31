package edu.ucsd.idekerlab.massql.lang.ast;

/** Arithmetic operator. Folding happens in Tech_Step9, not here. */
public enum Op {
    ADD("+"), SUB("-"), MUL("*"), DIV("/");

    private final String symbol;
    Op(String symbol) { this.symbol = symbol; }
    public String symbol() { return symbol; }
}
