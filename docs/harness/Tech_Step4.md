# Tech Step 4 — Grammar, typed AST, `Massql.parse()`

## Goal

`Massql.parse(String)` returns a typed, immutable AST for every supported MassQL query and throws a
`MassqlParseException` naming the offending construct for everything else — verified against the checked-in
reference parse corpus.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | Provides the Maven build with the ANTLR plugin wired, `src/main/antlr4/` in place, and `MassqlParseException` with its `construct()` contract. |
| [Step 1](Tech_Step1.md) | Provides [`msql.ebnf`](oracle/msql.ebnf) — now in-repo at `docs/harness/oracle/` (Correction **C41**), previously the oracle working directory — (the translation source, **165 lines** — confirmed) and `oracle/reference_parses/` (the conformance corpus: **46 files — 35 `scaninfo`, 11 non-`scaninfo`**. Note [`SPIKE.md`](SPIKE.md) says 47; 46 is the measured count at the pinned SHA and is authoritative). |

## Context

MassQL's entire formal language is one Lark EBNF file — [`msql.ebnf`](oracle/msql.ebnf), ~165 lines. Translation to ANTLR4 is
~90% mechanical: rules are already `lowercase: alt | alt`, keywords are inline literals, and ANTLR auto-rewrites
the direct left recursion that Lark expresses explicitly. The 10% that isn't mechanical is where the day goes,
and both parts of it are known in advance (see Known traps).

The AST — not the generated parse tree — is the tested surface and the input to [Step 9](Tech_Step9.md). Keeping
ANTLR types out of the AST means the parser can be swapped (hand-written, or the remote `/parse` escape hatch)
without touching the engine.

Governing sections: [`SPIKE.md`](SPIKE.md) §6a (parser rows), §7 Step 1, §8 (what must reject).

## Scope

**In scope**
- `Massql.g4` covering the full grammar as it exists in the pinned [`msql.ebnf`](oracle/msql.ebnf).
- A typed AST and an ANTLR-visitor facade that builds it.
- `Massql.parse(String) → MassqlQuery`.
- Clean, named rejection of everything out of scope for v1.
- The unit tests listed below.

**Out of scope**
- **Executing** anything. The AST is inert here; evaluation is [Step 9](Tech_Step9.md) and
  [Step 10](Tech_Step10.md).
- Constant folding of arithmetic expressions — the AST must *represent* arithmetic, but folding happens in
  [Step 9](Tech_Step9.md) where the numeric semantics live.
- Byte-exact JSON AST output matching MassQL's own serialization — permanently out of scope
  ([`Tech_Step_INDEX.md`](Tech_Step_INDEX.md), Out of scope).
- The remote `/parse` endpoint. Documented as an escape hatch below; not implemented.

## Deliverables

| Path | Content |
|---|---|
| `src/main/antlr4/…/massql/lang/Massql.g4` | The grammar |
| `src/main/java/…/massql/lang/ast/*.java` | Typed AST records |
| `src/main/java/…/massql/lang/AstBuilder.java` | ANTLR visitor → AST |
| `src/main/java/…/massql/lang/UnsupportedConstructs.java` | The reject list, in one place |
| `src/main/java/…/massql/Massql.java` | `parse` only at this stage |
| `src/test/resources/reference_parses/` | The corpus, copied from `oracle/reference_parses/` |
| `src/test/java/…/lang/*Test.java` | The test set below |
| [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) | Every place the ANTLR grammar deliberately diverges from the Lark source, and why. ⚠ **Moved to `docs/harness/` by Correction C41** — it is an engineering record, read by someone confirming the steps rather than by an SDK consumer. |
| **`docs/harness/`** | **All 14 harness spec files relocated into the massql-java repo — see §7** |

## Specification

### 1. Translate the grammar

Work from [`msql.ebnf`](oracle/msql.ebnf) at the pinned SHA — **not** from documentation or from the online grammar, which
may not correspond. Record the actual line count (Step 1 recorded it; [`SPIKE.md`](SPIKE.md) says 165).

Mechanical parts:
- Lark `rule: alt1 | alt2` → ANTLR `rule : alt1 | alt2 ;` directly.
- Inline string literals carry over unchanged.
- Lark's explicit left recursion in the arithmetic rules → leave as direct left recursion; ANTLR4 rewrites it
  and the resulting precedence follows alternative order, so **preserve the alternative order** from the source.
- Lark `?rule` (inline-if-single-child) has no ANTLR equivalent and needs none — collapse it in the AST builder
  instead, not in the grammar.

Every divergence goes in [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) with the reason. That file is what makes a future grammar
re-sync tractable.

### 2. Keyword casing — enumerate literally

Casing is **inconsistent in the source language**, and guessing symmetry breaks real queries users paste from
GNPS. From [`SPIKE.md`](SPIKE.md) §6a and §7 Step 1:

| Token | Accepted forms |
|---|---|
| `QUERY` | `QUERY`, `query`, `Query` |
| `WHERE` | `WHERE`, `where`, `Where` |
| `AND` | `AND`, `and`, `And` |
| `MS1DATA` | `MS1DATA`, `ms1data`, `Ms1Data` |
| `MS2DATA` | `MS2DATA`, `ms2data`, `Ms2Data` |
| `POSITIVE` | `POSITIVE`, `positive`, `Positive` |
| `NEGATIVE` | `NEGATIVE`, `negative`, `Negative` |
| `FILTER` | **`FILTER` only** — no case variants |
| `OR` | **`OR` only** — no case variants |
| condition names (`MS2PROD`, `MS2PREC`, `MS1MZ`, `MS2NL`, `RTMIN`, …) | **strictly uppercase** |
| qualifier names (`TOLERANCEMZ`, `TOLERANCEPPM`, `INTENSITYPERCENT`, …) | **strictly uppercase** |

Write these as explicit lexer alternatives (~20 lines). **Do not** use a case-insensitive lexer or a
`toUpperCase()` pre-pass — either one would accept `filter` and `or`, which must be rejected, and the case
matrix test exists precisely to catch that shortcut.

Verify the table against the pinned [`msql.ebnf`](oracle/msql.ebnf) before coding. If the source disagrees with this table, **the
source wins** — correct the table here and note it in [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md).

### 3. The typed AST

Immutable Java records, no ANTLR types anywhere in the public shape. Suggested form — adjust names to match the
grammar's own vocabulary, but keep the properties:

```java
public record MassqlQuery(QueryFunction function, DataSource source, List<Condition> conditions) { }

public enum QueryFunction { SCANINFO }          // the only supported one; see §4
public enum DataSource    { MS1DATA, MS2DATA }

public sealed interface Condition { }
public record MzCondition(ConditionType type, ValueList values, List<Qualifier> qualifiers) implements Condition { }
public record RangeCondition(ConditionType type, Comparator cmp, Expr bound) implements Condition { }
public record PolarityCondition(Polarity polarity) implements Condition { }

public enum ConditionType { MS2PROD, MS2PREC, MS1MZ, MS2NL, RTMIN, RTMAX, SCANMIN, SCANMAX,
                            CHARGE, POLARITY, MASSDEFECT, /* … as the grammar defines */ }
public enum QualifierType { TOLERANCEMZ, TOLERANCEPPM, INTENSITYVALUE, INTENSITYPERCENT,
                            INTENSITYTICPERCENT, /* … */ }

public record Qualifier(QualifierType type, Comparator cmp, Expr value) { }
public enum Comparator { NONE, GT, LT, EQ, GTE, LTE }   // NONE = comparator omitted in source
public record ValueList(List<Expr> values) { }          // OR-lists collapse to this

public sealed interface Expr { }
public record NumberLiteral(double value) implements Expr { }
public record BinaryExpr(Expr left, char op, Expr right) implements Expr { }   // + - * /
```

> ⚠ **This sketch is superseded on two counts; the implemented shape is below.** `Comparator.NONE` never
> existed (**C18**, next bullet), and the `Expr` type names here are wrong — **Correction C35(e)** found the
> phantom `NumberLiteral` / `BinaryExpr` names propagated as far as [Step 9](Tech_Step9.md) §4, whose
> constant-folder spec described folding *"`BinaryExpr` over `NumberLiteral`s"* and consequently **never
> mentioned unary negation at all**, so a folder written to it would silently leave `MS2NL=-18` unfolded.
> Nested records, not top-level ones:
>
> ```java
> public enum Comparator { EQ, GT, LT }             // no NONE -- C18
> public enum Op { ADD, SUB, MUL, DIV }             // an enum, not a char
>
> public sealed interface Expr {
>     record Literal(double value)             implements Expr { }
>     record Binary(Expr left, Op op, Expr right) implements Expr { }
>     record Unary(Op op, Expr operand)        implements Expr { }   // ADD/SUB only, enforced in the ctor
> }
> ```
>
> `Unary` rejects `MUL`/`DIV` in its own constructor, which makes the invalid state unrepresentable rather
> than merely unevaluatable — so [Step 9](Tech_Step9.md)'s folder guard for it is defensive and unreachable,
> and `ConstantFoldingTest` asserts `IllegalArgumentException` from the AST rather than `MassqlException`
> from the fold.

Three requirements on the AST that later steps depend on:

- ⚠ **`Comparator.NONE` was REMOVED — Correction C18.** This spec required it "distinct from `EQ`". Verified
  against the corpus: every in-scope qualifier carries `=`, `>` or `<`, and the only comparator-less
  qualifiers are out-of-scope ones. SPIKE.md §3's "missing comparator defaults to greater-than" refers to an
  **absent qualifier** — the implicit `> 0` on an unqualified intensity column ([Step 9](Tech_Step9.md) §3) —
  not to a comparator-less qualifier. `Comparator` is `{EQ, GT, LT}`. `=` still means `>=` for intensity in
  Step 9; that rule is untouched.
- **`MS2MZ` is an alias for `MS2PROD`.** Resolve the alias in the AST builder — one `ConditionType` value, not
  two — and note it in [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md).
- **Arithmetic stays a tree.** Do not evaluate `Expr.Literal` arithmetic here; [Step 9](Tech_Step9.md) folds it —
  including `Expr.Unary`, which is the part C35(e) found missing from that spec.

**Canonical equality.** Give the AST value-based `equals`/`hashCode` (records do this) plus a `canonical()`
string form used by the conformance test. Ordering matters: if the grammar treats `AND` conditions as an
unordered set, sort them in `canonical()` so an equivalent parse compares equal; if order is semantically
significant, preserve it and say so in [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md). Decide this from the source, and write down which.

### 4. Rejection — the reject list in one place

`UnsupportedConstructs.java` holds the full list, so [Step 13](Tech_Step13.md)'s feature matrix can be generated
from code rather than hand-maintained.

Two distinct rejection categories, and they behave differently:

**(a) Syntax errors — malformed input.** Attach an ANTLR error listener that throws `MassqlParseException`
rather than writing to stderr and continuing. ANTLR's default `ConsoleErrorListener` recovers and returns a
partial tree; that would let malformed queries through as silently-wrong ASTs. Remove it explicitly:

```java
parser.removeErrorListeners();
parser.addErrorListener(throwingListener);
```

Must reject as syntax errors:
- **`QUERY scaninfo WHERE …`** — the function-call form `scaninfo(MS2DATA)` is required. This reads perfectly
  legal and is the most likely real-world trap; it must fail with a message that *explains* the function-call
  form, not just "syntax error at 'WHERE'".
- Lowercase `filter` / `or` — see §2.
- Exponent-notation floats (`1e5`).
- Multi-char variables (`XY`).

**(b) Parses fine, not supported in v1.** These are grammatically valid MassQL that this version declines. The
AST builder detects them and throws with `construct()` set to the exact token:
- The five other functions: `scansum`, `scannum`, `scanmaxint`, `scanmz`, `scanrangesum`.
- `X` / `Y` variables.
- `INTENSITYMATCH`, `REFERENCE`, `PERCENT`, `INTENSITYMATCHPERCENT`.
- `MOBILITY`, `OTHERSCAN`, `CARDINALITY`, `EXCLUDED`.
- Nested sub-queries.
- `formula(...)`, `aminoaciddelta(...)`, `peptide(...)`.

Message form, for every rejection: *"`<construct>` is not supported in this version (scaninfo subset). See the
feature matrix in the README."* Never a bare stack trace, never a wrong answer, and **never a crash** — an
`ArrayIndexOutOfBoundsException` from the AST builder is a bug even on garbage input.

### 5. Handling `formula()` / `peptide()` — the lexing decision

Lark uses Earley with **contextual lexing**, so it distinguishes the variable `X` from a character `X` inside
`formula(...)`. ANTLR's maximal-munch DFA lexer cannot do this.

**The chosen resolution: do not admit those functions into the grammar at all.** Both are out of scope for v1,
so a token-level rejection is sufficient and avoids lexer modes entirely. Add `FORMULA : 'formula'` and
`PEPTIDE : 'peptide'` (plus `aminoaciddelta`) as tokens that the parser accepts *only* in order to produce a
named rejection.

Record in [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) that **lexer modes pushed by those literals are the documented fallback** if these
functions are ever brought in scope. Do not build the modes speculatively.

### 6. `Massql.parse`

```java
public static MassqlQuery parse(String queryText) throws MassqlParseException;
```

Trim surrounding whitespace and newlines before parsing — `massql_query.py` does (`load_query` calls `.strip()`)
and the `.massql` files on disk end in a newline. Reject empty/blank input with a clear message.

### 7. Relocate the harness specs into this repo

Move `Tech_Step_INDEX.md` and `Tech_Step1.md`–`Tech_Step13.md` from the oracle working directory into
`massql-java/docs/harness/`, so the specs travel with the code they describe and a reviewer cloning the repo
gets the execution record with it.

**Also copy [`SPIKE.md`](SPIKE.md)** alongside them. It is not a spec, but every spec cites it by section as the source of
record for rationale, and a `docs/harness/` that cannot resolve its own primary reference is broken.

**Fix the outbound links** — there are only five, all pointing at artifacts that stay behind in the oracle
working directory:

| Link | Occurrences | Fix |
|---|---|---|
| [`SPIKE.md`](SPIKE.md) | 1 | resolves locally once copied |
| [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) | 4 | rewrite to name the oracle directory explicitly |

**Add `docs/harness/README.md`** orienting a reader to the two-location split, because it is not obvious and
the specs reference both sides constantly:

- **This repo (`massql-java`)** — the only deliverable. Production code, tests, and now these specs.
- **The oracle working directory** (`../massql` relative to this repo) — the pinned Python MassQL install, the
  fixtures, the goldens, and `massql_query.py`. **Never shipped**; it exists to define the behavioural contract
  and generate the goldens the Java test suite diffs against. Not a git repo. Referenced from these specs as
  `oracle/…`, `data/…` and `fixtures/…`.

Keep the filenames unchanged so every existing cross-reference between specs keeps working, and re-run the
link audit afterwards:

```
cd docs/harness
grep -oh '(Tech_Step[A-Za-z0-9_]*\.md)' *.md | tr -d '()' | sort -u | while read t; do [ -f "$t" ] || echo "MISSING: $t"; done
```

Note this is documentation relocation, not a content change: **do not edit spec content while moving it**, apart
from the five link fixes above. Mixing a move with edits makes the diff unreviewable.

## Known traps

- **ANTLR's default error listener recovers silently.** Without `removeErrorListeners()`, malformed queries
  produce a partial parse tree and your rejection tests pass for the wrong reason (or fail confusingly). This is
  the single easiest way to ship a parser that accepts nonsense.
- **A case-insensitive lexer is the wrong shortcut.** It accepts `filter` and `or`, which must reject. The
  asymmetry is real, not an oversight in MassQL.
- **Comparing goldens as JSON text.** The conformance test must compare **canonical ASTs**. MassQL's JSON AST
  serialization includes ordering and defaulting details that are explicitly out of scope, so a text diff would
  fail on differences that don't matter and pass on some that do.
- ⚠ ~~**Normalizing `Comparator.NONE` to `EQ` or `GT` at parse time.**~~ **Retired by C18** — this warned
  against normalizing a state that cannot exist. Every in-scope qualifier carries `=`, `>` or `<`, so
  `Comparator` is `{EQ, GT, LT}` and there is nothing to normalize. The *real* rule this trap was groping
  toward survives and is stronger: **keep the source's shape and interpret it downstream** — `=` means `>=`
  for intensity qualifiers, and that reinterpretation belongs in [Step 9](Tech_Step9.md), not here.
- **Interpreting `=` as equality in the parser.** The above, stated positively. `EQ` records what the source
  said; [Step 9](Tech_Step9.md) §3 turns it into `>=` for intensity columns.
- **Left-recursion alternative order.** ANTLR derives operator precedence from the order of alternatives.
  Reordering them while "cleaning up" the grammar silently changes arithmetic precedence.

## Tests required

All unit (`*Test.java`), all offline.

| Test | Pins |
|---|---|
| `ParseConformanceTest` | ⚠ **Corrected by C17.** `@ParameterizedTest` over all 46 files. The split is **15 parse / 31 reject**, not "every `scaninfo` golden parses" — 20 of the 35 `scaninfo` goldens contain out-of-scope constructs. Expected dispositions live in the checked-in `corpus-manifest.tsv`. For the reject set, assert the reported construct is **one of** the unsupported constructs the query contains (asserting a specific one would pin traversal order, which has no user-visible meaning). Assert the corpus count is 46 so a missing corpus fails loudly instead of vacuously passing. |
| `ParseRejectionTest` | One case per §4 item. Assert the exception **type**, that `construct()` equals the expected token, and that the message names it. Explicitly include `QUERY scaninfo WHERE MS2PROD=100` and assert the message mentions the function-call form. |
| `KeywordCaseMatrixTest` | Every accepted form in the §2 table parses; `filter`, `or`, and lowercase condition/qualifier names reject. Table-driven, one row per cell. |
| `AstShapeTest` | `MS2MZ` and `MS2PROD` produce the same `ConditionType`; arithmetic remains an unfolded **`Expr.Binary`**; `OR` value lists collapse to `ValueList`. ⚠ **This row used to require *"`Comparator.NONE` survives when the source omits a comparator"* — retired by C18**, which found no in-scope qualifier omits its comparator, so the requirement contradicted both the enum and the passing test. Assert instead that **`Comparator` has exactly three constants** (`EQ`, `GT`, `LT`): that is a real check, and it fails if someone reintroduces `NONE`. |
| `ParseEntryPointTest` | Leading/trailing whitespace and trailing newline tolerated; empty and blank input rejected with a clear message. |

An empty or unreadable `reference_parses/` directory must **fail** `ParseConformanceTest`, not skip it.

## Done when

- [x] `mvn test` green — **183 tests**.
- [x] All 46 goldens accounted for: **15 parse to a canonical-stable AST, 31 reject by name**; the count is
      asserted so a missing corpus fails loudly.
- [x] Every out-of-scope construct rejects with the right `construct()` and a message naming it.
- [x] The full §2 case matrix passes in both directions (30 cells).
- [x] No third-party type appears in any public signature — `AstEncapsulationTest` reflects over the API and
      bans `org.antlr.*`, `io.github.msdk.*`, `io.vendor.*`, `com.google.common.*`, `org.slf4j.*`,
      `org.cytoscape.*`. This is what keeps the parser swappable.
- [x] [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) (196 lines) records: the line count (**165**, confirmed), **all 5** deliberate
      divergences, the `MS2MZ` alias resolution, the canonical-order-preserving decision, and the lexer-modes
      fallback for `formula()`/`peptide()`/`aminoaciddelta()` if they are ever brought in scope.
- [x] `docs/harness/` contains all 14 spec files plus [`SPIKE.md`](SPIKE.md) and a `README.md` explaining the two-location
      split; the inter-spec link audit passes and the outbound links were rewritten (§7).

**✅ STEP 4 COMPLETE — 2026-07-30.** See Corrections **C17–C19** in
[`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) and [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) for every divergence
from the Lark source.

## Escape hatch — documented, not implemented

`https://massql.gnps2.org/parse?query=<urlencoded>` returns the canonical AST as JSON (verified live; the
documented `msql.ucsd.edu/parse` now serves a deprecation redirect). Parsing remotely and executing locally
would remove all parser risk, but it puts a network dependency in an SDK that must work offline and makes the
test suite non-hermetic, violating `DEPENDENCY_POLICY.md` constraint 9. **Price it only if the grammar fights
back** — and if it is ever taken, it is a change to this spec, not a quiet implementation choice.

## References

- [`SPIKE.md`](SPIKE.md) §6a (parser conformance / rejection / case-matrix rows), §7 Step 1, §8 (out of scope)
- [`msql.ebnf`](oracle/msql.ebnf) and `oracle/reference_parses/` @ pinned SHA `dad2a28c…`
- [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) — Out of scope list, spec conventions
- Consumer: [Step 9](Tech_Step9.md) evaluates this AST
