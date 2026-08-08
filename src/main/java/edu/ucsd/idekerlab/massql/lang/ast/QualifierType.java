package edu.ucsd.idekerlab.massql.lang.ast;

/**
 * Qualifier fields that reach the AST.
 *
 * <p>Out of scope and rejected by name: {@code INTENSITYMATCH},
 * {@code INTENSITYMATCHPERCENT}, {@code INTENSITYMATCHREFERENCE}, {@code EXCLUDED},
 * {@code CARDINALITY}/{@code MATCHCOUNT}, {@code OTHERSCAN}, and {@code MASSDEFECT}
 * (which is a qualifier taking a {@code massdefect(min=…,max=…)} call, not a plain value).
 */
public enum QualifierType {
    TOLERANCEMZ,
    TOLERANCEPPM,
    INTENSITYPERCENT,
    INTENSITYTICPERCENT,
    INTENSITYVALUE
}
