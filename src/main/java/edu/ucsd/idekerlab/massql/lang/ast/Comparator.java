package edu.ucsd.idekerlab.massql.lang.ast;

/**
 * Qualifier comparator.
 *
 * <p>There is deliberately <b>no {@code NONE}</b> value. Every qualifier the grammar can
 * produce in scope carries {@code =}, {@code >} or {@code <}; verified against the
 * reference corpus, the only comparator-less qualifiers are the out-of-scope ones
 * ({@code INTENSITYMATCHREFERENCE}, {@code EXCLUDED}, {@code CARDINALITY},
 * {@code MASSDEFECT}). SPIKE.md §3's "a missing comparator defaults to greater-than"
 * therefore refers to an <i>absent qualifier</i> — the implicit {@code > 0} the engine
 * applies to an unqualified intensity column — not to a qualifier that
 * parsed without one. Adding {@code NONE} here would model a state the grammar cannot
 * reach.
 *
 * <p>{@link #EQ} carries a trap the engine must honour: for intensity comparisons
 * MassQL treats {@code =} as {@code >=}, "preserving historical semantics".
 */
public enum Comparator {
    EQ,
    GT,
    LT
}
