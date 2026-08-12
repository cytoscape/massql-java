# Grammar notes

Every deliberate divergence between `src/main/antlr/…/Massql.g4` and the Lark source it was
translated from, plus the facts a future re-sync will need.

## Source

| | |
|---|---|
| Source | MassQL's Lark EBNF grammar, **165 lines** |
| Parser | Lark, Earley algorithm with **contextual lexing** |

Reference corpus: `src/test/resources/reference_parses/` — **46 files**. 35 are `scaninfo`
queries, 11 are not.

---

## Translation philosophy: permissive grammar, strict builder

The grammar admits the **whole** MassQL language, including everything out of scope for v1.
Scope is enforced in `AstBuilder`, not in the grammar.

This is the single most important design decision here, and it is not the obvious one. A
grammar that simply omitted `formula(...)`, `MOBILITY`, variables and the rest would be
shorter — but a user typing `MS2PROD=formula(C10)` would get *"mismatched input 'formula'"*
instead of *"`formula()` requires monoisotopic mass tables and is not supported in this
version"*. **31 of the 46 reference parses need a named rejection**, and
`MassqlParseException.construct()` is asserted by the test suite and surfaced by the CLI, so
the name is part of the contract rather than a nicety.

Consequence to be aware of when reading the grammar: rules exist for constructs that can
never reach the AST. That is intentional. `UnsupportedConstructs` is the list of what they
do instead.

---

## Divergences from the Lark source

### 1. `FLOAT` carries no sign; unary `+`/`-` is a parser rule

Source: `floating: /[-+]?([0-9]*\.[0-9]+|[0-9]+)/`

Under Earley with contextual lexing, an optional leading sign inside the number token is
harmless. Under ANTLR's **maximal-munch DFA lexer it is not**: `X+2` would tokenise as
`VARIABLE FLOAT(+2)` rather than `VARIABLE PLUS FLOAT(2)`, because the 2-character `+2`
match beats the 1-character `+`. That breaks **every additive expression in the corpus** —
`157.0857+10`, `X-1.993`, `X+2`, `MS2PROD=X+329.8`.

So `FLOAT` is unsigned and a leading sign becomes an `Expr.Unary` node. `AstShapeTest`
pins both halves: `RTMIN=-5` produces `Unary(SUB, Literal(5.0))`, and `MS2PROD=157.0857+10`
stays an unfolded `Binary`.

### 2. Compound literals are split from their leading parenthesis

Source writes `"(min="`, `"(max="`, `"(left="`, `"(right="` as single literals. Split into
`LPAREN` + `MIN_EQ` / `MAX_EQ` / `LEFT_EQ` / `RIGHT_EQ` so the grammar uses one `LPAREN`
token everywhere. Purely cosmetic; no behavioural difference.

### 3. `statement` is split into `statement: query EOF` and `query`

`EOF` on the top-level rule is mandatory — without it ANTLR parses a prefix and silently
ignores trailing garbage, so `QUERY scaninfo(MS2DATA) junk` would succeed.

But the source's subquery alternative is `conditionfields equal "(" statement ")"`, and an
`EOF`-anchored rule **cannot match inside parentheses**. Referencing it there made the
subquery alternative unmatchable, which silently downgraded its named rejection to a
generic syntax error. Found by `ParseRejectionTest.nestedSubqueryRejects`. The split keeps
the anchor at the top level and lets `query` nest.

### 4. `MS2MZ` → `MS2PROD` alias collapsed in the builder, not the grammar

Source: `ms2productcondition: "MS2PROD" | "MS2MZ"`. Both spellings stay in the grammar so
it mirrors the source; `AstBuilder.conditionType` maps them to one `ConditionType`, so the
engine never sees two names for one thing.

### 5. `formulaBody` is loose

Source distinguishes `moleculeformula: /[A-Z][A-Za-z0-9]*/` from `variable: /[XY]/` using
Earley's contextual lexing. **ANTLR's DFA lexer cannot**: inside `formula(...)`, the text
`X` matches both, and declaration order makes it a `VARIABLE`. So `formula(X)` yields
`VARIABLE` while `formula(Fe)` yields `IDENT`.

Rather than introduce lexer modes, `formulaBody` accepts `(IDENT | VARIABLE | floating)+`.
This costs nothing because all three mass-table functions are rejected by name anyway.

> **If `formula()` / `peptide()` / `aminoaciddelta()` are ever brought into scope, lexer
> modes pushed by the `formula(` / `peptide(` / `aminoaciddelta(` literals are the
> documented fix.** The literals are already single tokens, so the mode push has a clean
> trigger.

---

## Decisions that are not divergences, but are easy to get wrong

### Keyword casing is asymmetric — enumerate it, never fold case

| Token | Accepted |
|---|---|
| `QUERY`, `WHERE`, `AND` | three forms each: upper, lower, Capitalised |
| `MS1DATA`, `MS2DATA` | `MS1DATA` / `ms1data` / `Ms1Data` |
| `POSITIVE`, `NEGATIVE` | three forms each |
| **`FILTER`** | **uppercase only** |
| **`OR`** | **uppercase only** |
| condition names (`MS2PROD`, `RTMIN`, …) | **strictly uppercase** |
| qualifier names (`TOLERANCEMZ`, …) | **strictly uppercase** |
| function names (`scaninfo`, …) | **strictly lowercase** |

A case-insensitive lexer or a `toUpperCase()` pre-pass would accept `filter` and `or`,
which must reject. `KeywordCaseMatrixTest` asserts all 30 cells in both directions.

### Operator precedence comes from alternative ORDER

`numericalExpression` uses direct left recursion, as the source does. ANTLR4 rewrites it and
derives precedence from the order the alternatives are written: `mulDiv` precedes `addSub`,
so multiplication binds tighter. **Reordering those alternatives while tidying would
silently change arithmetic.** `AstShapeTest.multiplyBindsTighterThanAdd` is the guard.

### Conditions may be separated by `AND` *or* by nothing

Source writes `wherefullcondition+`, so whitespace-separated conditions are legal alongside
`AND`-separated ones. Both forms appear in the corpus.

### `ANY`, `MATCHCOUNT` and the querytype asymmetries

Three things in the source that are easy to miss:

- **`wildcard: "ANY"`** — `MS2PROD=ANY` is legal MassQL. Out of scope, rejected by name.
- **`qualifiercardinality: "CARDINALITY" | "MATCHCOUNT"`** — two spellings. Both rejected.
- **`querytype`** has a **bare** `MS1DATA`/`MS2DATA` alternative with no function at all.
  Legal MassQL — 3 reference parses use it — and out of scope, rejected as `<no function>`.
- The `TOLERANCE` parameter alternative accepts **only `MS1DATA`**, not `MS2DATA`. An
  asymmetry in the source, faithfully reproduced.

### `MASSDEFECT` is a qualifier, not a condition

`qualifiermassdefect equal xdefect "(min=" … "," "max=" …)`. It sits alongside
`TOLERANCEMZ` in the qualifier list, not alongside `MS2PROD` in the condition list. Worth
stating because it reads like a condition and an earlier draft of the specs listed it as one.

### `Comparator` has no `NONE`

Every qualifier the grammar can produce **in scope** carries `=`, `>` or `<`. Verified
against the corpus: the only comparator-less qualifiers are the out-of-scope ones
(`INTENSITYMATCHREFERENCE`, `EXCLUDED`, `CARDINALITY`, `MASSDEFECT`).

*"A missing comparator defaults to greater-than"* refers to an **absent qualifier** — the implicit `> 0` the engine applies to an unqualified intensity column —
not to a qualifier that parsed without one. Adding `NONE` would model an unreachable state.
Only `equal` and `greaterthan` actually occur in the corpus, though the grammar permits `<`.

### Canonical form preserves condition order

`MassqlQuery.canonical()` does **not** sort conditions. `AND` is a flat conjunction so order
carries no semantics — but preserving it means the canonical form round-trips the source
query, and a reordering bug in `AstBuilder` stays visible instead of being normalised away.

---

## Rejection mechanics

Two distinct paths, and the difference matters:

**Syntax errors** — the grammar refuses the input. `ThrowingErrorListener` replaces ANTLR's
default `ConsoleErrorListener`, which writes to stderr and then **recovers**, returning a
partial parse tree. Without that replacement, malformed queries produce a half-built AST and
the rejection tests pass for the wrong reason. This is the easiest way to ship a parser that
silently accepts nonsense. Reported with `construct() == "<syntax>"`.

The listener adds guidance for the traps that read as legal MassQL:
- `QUERY scaninfo WHERE …` → the message names the required function-call form.
- lowercase `filter` / `or` → the message says the keyword has no lowercase form.

**Valid MassQL, out of scope** — parses fine, rejected by `AstBuilder` with the construct's
own name from `UnsupportedConstructs`.

When a query contains **several** unsupported constructs, the one reported is the first in
**source order** — `AstBuilder.fullCondition` validates the condition before its qualifiers
for exactly this reason, so `MS1MZ=X-2:INTENSITYMATCH=Y` names `X`. The conformance test
asserts the reported construct is *one of* those present rather than a specific one:
pinning which would pin traversal order, which carries no user-visible meaning.

