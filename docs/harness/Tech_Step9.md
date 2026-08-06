# Tech Step 9 — Condition filters

## Goal

Evaluate a parsed `WHERE`/`FILTER` clause against the loaded store, producing the set of qualifying scans —
with MassQL's tolerance, comparator and intensity semantics reproduced exactly, including the ones that look
like bugs.

## Prerequisites

| Step | Why |
|---|---|
| [Step 4](Tech_Step4.md) | Provides the typed AST and unfolded arithmetic. ⚠ **`Comparator` is `{EQ, GT, LT}` — there is no `NONE`, deliberately.** See Correction C35(a) and §3. |
| [Step 5](Tech_Step5.md) | Provides `SpectrumTable`, `mzWindow`, `RowMask`, `Reductions`, and the `iNorm`/`iTicNorm` columns. |
| [Step 8](Tech_Step8.md) | **Its gate must be green.** Filtering over data whose decode is unverified produces results that cannot be attributed. |

## Context

`scaninfo`-only fixes the *function* axis; the condition axis is a separate choice, and the settled decision is
to implement **both 9a and 9b** ([`Tech_Step_INDEX.md`](Tech_Step_INDEX.md), decision 2). 9b costs about a day
beyond 9a and needs no new machinery — every condition in it is a row mask or a per-scan reduction over the same
store 9a builds against — and RT/precursor filtering is what real metabolomics queries lean on.

The semantics in §3 are the heart of this step. Each is one line of code and each is a silent wrong-answer bug if
missed: no exception, no warning, just a different set of rows than MassQL would return. Several of them are
historical quirks rather than designed behaviour, and reproducing them faithfully — including `=` meaning `>=` —
is the requirement.

Governing sections: `SPIKE.md` §3 (exact rules), §6a (tolerance / comparator / algebra rows), §7 Step 2.

## Scope

**In scope**
- `QueryExecutor` — walks the AST, produces qualifying scans.
- `ConditionFilters` — one evaluator per condition type.
- Constant folding of AST arithmetic.
- All of 9a and 9b, enumerated in §2.
- The tolerance, comparator and intensity semantics in §3.

**Out of scope**
- Producing result rows or any of the 12 columns — [Step 10](Tech_Step10.md).
- The `precmz`-in-MS1 precursor lookup. That is a *collation* concern, not a filter, and lives in
  [Step 10](Tech_Step10.md) §3 — do not implement it here even though it also uses `mzWindow`.
- Anything in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)'s out-of-scope list. [Step 4](Tech_Step4.md) already
  rejects those at parse time, so this step will never see them; do not add defensive handling.

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/exec/QueryExecutor.java` | Streams, evaluates per scan, invokes the consumer (§1) |
| `src/main/java/…/massql/exec/QualifyingScanConsumer.java` | The callback the executor hands each qualifying scan to |
| `src/main/java/…/massql/exec/ExecutionSummary.java` | `(qualifyingScans, diagnostics)` — §5's diagnostics in the shape [Step 11](Tech_Step11.md) needs |
| `src/main/java/…/massql/exec/ConditionFilters.java` | Per-condition evaluators |
| `src/main/java/…/massql/exec/Tolerance.java` | Window computation, one place |
| `src/main/java/…/massql/exec/IntensityQualifiers.java` | Comparator + intensity semantics |
| `src/main/java/…/massql/exec/ConstantFolder.java` | Arithmetic folding, incl. **`Expr.Unary`** (C35e) |
| `src/test/java/…/exec/*Test.java` | The test set below |
| `docs/SEMANTICS.md` | The §3 rules, each with the source line that establishes it |

⚠ **"AST → qualifying scan ordinals" was the old description of `QueryExecutor`** and is wrong under streaming
(C35b): there are no whole-file ordinals, and it returns a summary rather than an array.

## Specification

### 1. Execution model

> ⚠ **Correction C22: evaluation is PER SCAN, over a stream.** This section was written against a
> whole-file table with masks spanning it. There is no such table — the executor advances a
> `SpectraStream` cursor and evaluates each scan as it arrives, retaining only the most recent MS1
> scan for [Step 10](Tech_Step10.md)'s precursor lookup. The insight below is **unchanged and still
> the crux**, and it gets *easier* to express per-scan: two conditions may be satisfied by different
> peaks in the same scan, so a condition means "this scan contains a peak matching X". `RowMask` is
> still useful *within* a scan; what disappears is the cross-scan intersection, which becomes a
> simple per-scan boolean AND.
>
> Skeleton:
> ```java
> SpectrumTable retainedMs1 = null;
> while (stream.next()) {
>     ScanView v = stream.current();
>
>     // ⛔ C35(c): a ZERO-PEAK scan is invisible to MassQL and must be skipped here.
>     // Its loaders `continue` on an empty intensity array, so its dataframes hold no rows
>     // for such scans. Measured: PlusRise's dump reports 21,942 MS2 scans where our reader
>     // yields 34,513, and micro.mzML's ms1_df is [2] with the zero-peak scan 4 absent.
>     // This guard sits BEFORE the MS1 retention, because an empty MS1 must also not become
>     // an ms1scan link (C27b) -- the same rule, one layer up.
>     if (v.peakCount() == 0) continue;
>
>     if (v.msLevel() == 1) retainedMs1 = v.materialize();
>     if (v.msLevel() != wantedLevel) continue;
>
>     // Scan-level conditions FIRST, before materialize(), so a rejected scan never pays
>     // base64-decode + inflate + double[] allocation. That is the whole payoff of C22's
>     // deferred decoding.
>     if (!scanLevelConditionsHold(v)) continue;
>
>     SpectrumTable scan = v.materialize();          // single-scan table; its only ordinal is 0
>     if (peakConditionsHold(scan, v, retainedMs1)) emit(v, scan, retainedMs1);
> }
> ```
>
> **Why the zero-peak guard is not optional.** A *peak-based* condition fails an empty scan naturally — there
> is no peak to match. But a **scan-level** condition (`POLARITY`, `RTMIN`, `SCANMIN`, `CHARGE`) never looks at
> peaks, so without this guard a scan-level-only query returns **34,513 scans where MassQL returns 21,942**.
> That is not a rounding difference; it is a third of the result set.

A MassQL condition is fundamentally *"this scan contains at least one peak satisfying P"*, not *"this row
satisfies P"*.

> ⛔ **Correction C35(b) — the signature that used to sit here contradicted the C22 note above it.** It read
> `public int[] execute(MassqlQuery q, SpectrumTable ms1, SpectrumTable ms2)` returning *"ordinals (not scan
> ids)"* — taking exactly the whole-file tables C22 abolished, eight lines after saying they do not exist.
> Under streaming there is no whole-file ordinal space at all: a single-scan table's only ordinal is **0**.

**The interface is a per-scan callback:**

```java
/** One qualifying scan, handed over while the stream is still positioned on it. */
public interface QualifyingScanConsumer {
    void accept(ScanView view, SpectrumTable scan, SpectrumTable retainedMs1);
}

/** Streams, filters, and hands each qualifying scan to `out`. */
public ExecutionSummary execute(MassqlQuery q, SpectraStream stream,
                                MassqlOptions opts, QualifyingScanConsumer out);

/** What the run produced, plus §5's diagnostics. */
public record ExecutionSummary(int qualifyingScans, List<String> diagnostics) { }
```

**Why a callback rather than a returned collection.** Retained memory stays at **one scan + one MS1**,
preserving the C22 property proven under `-Xmx48m`; it needs only **one pass**, so peaks are decoded once; and
scan-id-ascending order — which [Step 10](Tech_Step10.md) and [Step 12](Tech_Step12.md) both require — falls out
of document order for free. Returning `List<QualifyingScan>` would make memory proportional to *matches*, which
a permissive query over a 500 MB file would blow. [Step 10](Tech_Step10.md)'s collation **is** the consumer.

Per condition, *within* one scan: build a `RowMask` over that scan's rows, then ask whether any row survives —
`RowMask.scansWithAnyRow(SpectrumTable)` returns a `BitSet`, and for a single-scan table the only question is
whether bit 0 is set. ⚠ It is a method on **`RowMask`**, not on `SpectrumTable` as this section previously said
(C35d). Combine across conditions with a per-scan boolean **AND over "did this condition find any peak"** — never
a row-level AND, because two conditions may be satisfied by *different* peaks in the same scan. That distinction
is the most likely structural error in the step; state it in `SEMANTICS.md`.

Scan-level conditions (`RTMIN`, `RTMAX`, `SCANMIN`, `SCANMAX`, `CHARGE`, `POLARITY`, `MS2PREC`) read `ScanView`
directly and filter the scan with no row mask and **no materialisation**.

Keep filtering **non-destructive**: produce masks, never a pruned table ([Step 5](Tech_Step5.md) §8, the
`OTHERSCAN` seam).

### 2. The condition set

**9a — required.** Executes `test.massql` and `test_mzml.massql`.

| Condition | Meaning |
|---|---|
| `MS2PROD` (alias **`MS2MZ`**) | An MS2 fragment peak within tolerance of the value |
| `MS2PREC` | The scan's precursor m/z within tolerance of the value |
| `TOLERANCEMZ` / `TOLERANCEPPM` | Qualifiers on the above |
| `INTENSITYPERCENT` | Relative-intensity qualifier |
| `AND` | Conjunction of conditions |

**9b — also in scope.**

| Condition | Meaning |
|---|---|
| `MS1MZ` | An MS1 peak within tolerance |
| `MS2NL` | Neutral loss: `precmz − value`, matched against MS2 fragments within tolerance |
| `RTMIN` / `RTMAX` | RT bounds, **strict** |
| `SCANMIN` / `SCANMAX` | Scan-id bounds, **inclusive ints** |
| `CHARGE` | Precursor charge equality |
| `POLARITY` | 1 = positive, 2 = negative |
| `INTENSITYVALUE` | Absolute-intensity qualifier |
| `INTENSITYTICPERCENT` | TIC-relative qualifier (`iTicNorm`) |
| `FILTER` | Same evaluation as `WHERE`; note **no lowercase form exists** ([Step 4](Tech_Step4.md) §2) |
| ~~`MASSDEFECT`~~ | ⚠ **Not a condition — Correction C19.** `msql.ebnf` defines it as a *qualifier* taking `massdefect(min=…,max=…)`, alongside `TOLERANCEMZ`. It is out of scope and [Step 4](Tech_Step4.md) rejects it by name, so it never reaches the engine. |
| `OR` value lists | A condition satisfied by **any** value in the list |
| Arithmetic literals | Folded before evaluation (§4) |

`MS2NL` computes against the scan's own `precmz`; if `precmz` is the `0` sentinel, the scan cannot satisfy an
`MS2NL` condition — exclude it rather than matching against a nonsense target.

> ⚠ **C37(f), two notes from the source.** It matches **per peak** on `(precmz − mz)` against
> `(value − tol, value + tol)` rather than building one target — algebraically the same window, so either
> implementation is faithful. And the `precmz == 0` exclusion happens **naturally** (`0 − mz` is negative, the
> window positive), so no explicit guard is required, though one is harmless.
>
> The source also carries `#TODO: This is incorrect logic if it comes to PPM accuracy` on its tolerance line:
> with `TOLERANCEPPM`, the tolerance is derived from the **neutral-loss value**, not from an m/z.
> Chemically questionable, but **reproduce it** — bug-for-bug fidelity is the requirement.

### 3. Exact semantics — each one a silent wrong answer if missed

Put every rule in `docs/SEMANTICS.md` alongside the source line that establishes it. Where this spec and the
pinned Python source disagree, **the source wins** — correct the spec and note it.

> ⚠ **Correction C37 — the authority is `msql_engine_filters.py`, not `msql_engine.py`.** This section used to
> cite only the latter. The tolerance computation (`_get_mz_tolerance`), the intensity comparators
> (`_get_intensity_mask`) and **all four condition functions** live in `msql_engine_filters.py`. Only the
> scan-level conditions (`RTMIN`/`RTMAX`/`SCANMIN`/`SCANMAX`/`CHARGE`/`POLARITY`) are in `msql_engine.py`.
> Two rules below were wrong, and mis-citing the file is the likeliest reason nobody checked.

**Tolerance**

- **`TOLERANCEPPM` wins if both are given.** Not "narrower wins", not an error — PPM takes precedence.
- **Default `0.1` Da if neither is given.**
- PPM → absolute: `tol = value * ppm / 1e6`.
- Compute the window from the **target value**, not from the observed peak.
- ⛔ **The window is `(value − tol, value + tol)` — BOTH BOUNDS STRICT.** A peak exactly at an edge does
  **NOT** match.

> ⛔ **Correction C37(a) — this rule said "both bounds inclusive… a peak exactly at an edge matches". It is
> the opposite.** All four condition functions use `>` and `<`: `:253` (MS2PROD), `:410` (MS2PREC),
> `:493`/`:519` (MS1MZ), `:607` (`ms1_filter`).
>
> **Verified by execution, not just by reading.** `micro.mzML` scan 3 carries a peak at exactly `201.0`. The
> query `MS2PROD=201.5:TOLERANCEMZ=0.5` gives the window `[201.0, 202.0]`, placing that peak precisely on the
> lower bound — and **MassQL returns 0 rows**. `test_micro_edge.massql` now pins this with an empty golden;
> no prior query isolated that peak, which is why the error survived.
>
> **Use `SpectrumTable.mzWindowExclusive`, NOT `mzWindow`.** The two differ deliberately, because the two
> callers differ: [Step 10](Tech_Step10.md)'s precursor lookup is **inclusive** (`massql_query.py:101-103`
> uses `>=`/`<=`, also verified by execution — at `--precursor-tol-ppm 7.8125` an exactly-on-the-bound peak
> **does** populate `ms1_i`). Unifying them would introduce a fresh divergence in the columns Step 12 checks
> at 1e-9 while fixing this one.

**Comparators**

- **`=` means `>=` for intensity comparisons.** Verbatim from `SPIKE.md` §3: *"preserving historical semantics."*
  It looks like a bug; reproduce it.
- **An intensity column with no explicit qualifier gets an implicit `> 0` — PER COLUMN, on all three.** So a
  bare `MS2PROD=100.0` requires a peak satisfying `i > 0 AND i_norm > 0 AND i_tic_norm > 0`, not merely a
  peak, and not one blanket check (⚠ C37e — the per-column detail was missing). With no qualifier at all the
  source returns exactly `(df["i"] > 0) & (df["i_norm"] > 0) & (df["i_tic_norm"] > 0)`.

> ⛔ **Correction C18 — this section used to say "a missing comparator defaults to greater-than. This is
> why [Step 4](Tech_Step4.md) must keep `Comparator.NONE` distinct from `EQ`." There is no
> `Comparator.NONE`, and adding one would undo a deliberate decision.**
>
> **Recorded as C18 when Step 4 completed, and this paragraph is why the ledger cites C18 rather than
> C35(a).** C18 named *this section* as affected and this section was never edited, so five steps later the
> identical finding was rediscovered from scratch and filed again as C35(a) — since retired into C18 as a
> duplicate. `make spec-audit` check 3 exists because of this exact pair, and it now fails the build if a
> correction names a step the step does not cite back.
>
> `Comparator` is `{EQ, GT, LT}`, and `Qualifier` **rejects a null comparator** outright. `Comparator.java`
> records the reasoning, verified against the reference corpus: *every* qualifier the grammar can produce in
> scope carries `=`, `>` or `<`; the only comparator-less qualifiers are the out-of-scope ones
> (`INTENSITYMATCHREFERENCE`, `EXCLUDED`, `CARDINALITY`, `MASSDEFECT`), which [Step 4](Tech_Step4.md) rejects
> by name. Its javadoc puts it directly:
>
> > *"'a missing comparator defaults to greater-than' therefore refers to an **absent qualifier** — the
> > implicit `> 0` the engine applies to an unqualified intensity column — not to a qualifier that parsed
> > without one. Adding `NONE` here would model a state the grammar cannot reach."*
>
> **So the rule is real but it is about absence of a QUALIFIER, not absence of a comparator** — and it is the
> `> 0` bullet immediately above, which is where it belongs. Do not add `NONE`; do not treat this as a gap in
> Step 4. `ConditionFilters` never has to ask "what if the comparator is missing", because it cannot be.

**Intensity scales** — three different denominators, and mixing them up gives plausible wrong answers:

| Qualifier | Compares against | Column |
|---|---|---|
| `INTENSITYVALUE` | absolute intensity | `i` |
| `INTENSITYPERCENT` | intensity ÷ **max in scan** | `iNorm` |
| `INTENSITYTICPERCENT` | intensity ÷ **sum in scan** | `iTicNorm` |

- **BOTH percent qualifiers divide by 100** — `INTENSITYPERCENT` **and** `INTENSITYTICPERCENT` (a value of
  `5` means 5% → `0.05`). ⚠ C37(c): this rule previously named `INTENSITYPERCENT` only; the source gives both
  `scale = 100.0`. `INTENSITYVALUE` has `scale = 1.0` and is not divided.
- **With `>`, both percent qualifiers are capped at 0.99.** So `INTENSITYPERCENT>100` becomes `> 0.99`, not
  `> 1.0` (which nothing could satisfy, `iNorm`'s maximum being exactly 1.0). Apply the cap **only** for `>` —
  that half was right — but ⚠ C37(d): the guard is `if scale > 1.0`, so it covers **`INTENSITYTICPERCENT`
  too**, not `INTENSITYPERCENT` only as Known traps used to say.
- `test.massql` uses `INTENSITYPERCENT=1` and `=5`, so via the `=`→`>=` rule these are `>= 0.01` and `>= 0.05`.
  The cap does not apply. Getting either rule wrong changes the 664-record count, which makes this pair of rules
  directly observable in [Step 12](Tech_Step12.md).

**Bounds**

- **`RTMIN` / `RTMAX` are strict** (`>` and `<`). A scan exactly at the bound does **not** qualify.
- **`SCANMIN` / `SCANMAX` are inclusive integers.** A scan exactly at the bound **does** qualify.
  Strict-vs-inclusive differing between the two families is deliberate; do not unify them.
- RT is in **minutes**, and comes from `scanRt` at double precision ([Step 5](Tech_Step5.md) §1).

**Polarity:** 1 = positive, 2 = negative. `0` (unknown) matches neither.

> ⚠ **Correction C34(b) — `POLARITY` cannot filter an MGF, and it now matches EVERYTHING there rather than
> nothing.** MGF polarity is a hardcoded constant **1** (C33a corrected C8's `0`), so on any MGF fixture
> `POLARITY=Positive` matches **every** scan and `POLARITY=Negative` matches **none** — regardless of what the
> spectra actually are. Under the old, wrong value of `0` both matched nothing, which at least *looked* like
> "no polarity information". The sentence above still describes mzML and mzXML correctly, where `0` means
> genuinely unrecorded.
>
> Test this deliberately rather than discovering it: a `POLARITY=Positive` query over `PlusRise.mgf` returning
> all 21,942 loaded scans is **correct behaviour**, not a broken filter. Note it in the Step 13 README as a
> known deviation — a user filtering an MGF by polarity is filtering on a constant, and has no way to tell
> from the output.

> ⛔ **Correction C35(d) — a `MASSDEFECT` paragraph used to sit here telling you to "derive the exact
> definition from the pinned Python source and record it". Delete that instinct: it is out of scope.** §2
> already marks it so (Correction C19 — `msql.ebnf` defines it as a *qualifier*, not a condition), and
> `UnsupportedConstructs:59` rejects it **by name at parse time**, so it can never reach the engine. Researching
> its semantics would be work spent on a construct this SDK refuses.

### 4. Constant folding

`ConstantFolder` walks `Expr` and reduces it to a single `Expr.Literal`.

> ⚠ **Correction C35(e) — the type names here were wrong and one type was missing entirely.** The AST
> ([Step 4](Tech_Step4.md)) is `sealed interface Expr` with **three** records: `Expr.Literal(double)`,
> `Expr.Binary(Expr, Op, Expr)` and **`Expr.Unary(Op, Expr)`**. This section said "`BinaryExpr` over
> `NumberLiteral`s" — neither name exists — and never mentioned `Unary` at all, so a folder written to this spec
> would silently fail to fold a negated literal such as `MS2NL=-18`. `Op` is `{ADD, SUB, MUL, DIV}`.

`+ - * /` with IEEE double semantics — no rational arithmetic, no rounding to a "nice" value, because MassQL is
doing double arithmetic and matching it bit-for-bit is the goal. Division by zero yields infinity rather than
throwing; the condition then matches nothing, which is the correct outcome.

Fold once, before evaluation, not per row.

### 5. Diagnostics

The SDK logs nothing (`DEPENDENCY_POLICY.md` **constraint 2**). Where a query is valid but degenerate — a tolerance so
tight nothing can match, an `MS2NL` against a `0` precursor, an RT window outside the file's range — return the
information as a **diagnostic on the result**, so [Step 11](Tech_Step11.md)'s CLI can print it to stderr and the
Phase-2 app can surface it. An empty result set is a legitimate answer ([Step 12](Tech_Step12.md) requires empty
JSON array, exit 0), but a *silent* empty result set is a poor one.

## Known traps

- **Row-level AND instead of scan-level intersection.** Two conditions may be satisfied by different peaks in the
  same scan. §1.
- **Normalizing `=` to equality.** It means `>=`. This is the rule most likely to be "fixed" by a well-meaning
  implementer.
- **Adding `Comparator.NONE`, or "fixing" Step 4 to distinguish a missing comparator.** There is no such
  state — the grammar cannot produce it (C35a). The rule you are reaching for is the implicit `> 0` on an
  **unqualified** intensity column, one bullet up.
- **Forgetting the implicit `> 0`** on unqualified intensity columns.
- **Applying the 0.99 cap to `>=`.** It is `>` only. ⚠ But it applies to **both** percent qualifiers, not
  `INTENSITYPERCENT` alone — the guard is `if scale > 1.0` (C37d). It never applies to `INTENSITYVALUE`.
- **Unifying strict `RTMIN/RTMAX` with inclusive `SCANMIN/SCANMAX`.** They genuinely differ.
- **Confusing `iNorm` (÷max) with `iTicNorm` (÷sum).** Both are "percent"; different denominators.
- **Choosing "narrower tolerance wins" when both are given.** PPM wins regardless of which is narrower.
- **Building the window from the observed peak rather than the target value.**
- ⛔ **Using `mzWindow` (inclusive) for a condition window.** Conditions are **strict** (C37a); use
  `mzWindowExclusive`. `mzWindow` stays inclusive because [Step 10](Tech_Step10.md)'s precursor lookup
  genuinely is — both verified by execution. Picking the wrong one silently widens or narrows every
  tolerance in the system by one edge case, and no pre-C37 fixture could detect it.
- **Adding an epsilon to window bounds.** Take the exact bounds; slack here widens every tolerance.

## Tests required

All unit (`*Test.java`), on the [Step 2](Tech_Step2.md) micro-fixtures and hand-built tables.

> ⛔ **Correction C38 — this table named 10 classes that were never written, and one of its exit criteria was
> checked while false.** The coverage was consolidated into five classes during implementation, which is fine;
> what is not fine is that the table kept listing the original names, so *"does `ConditionCoverageTest` exist?"*
> had no answer and nobody asked. Under that cover, **three of the ten 9a/9b conditions — `MS2PREC`, `CHARGE`
> and `MS2NL` — had no execution test at all**, while *"every 9a and 9b condition has a positive and a negative
> test"* sat ticked in Done-when. All three are implemented in `ConditionFilters`; they were exercised only by
> **parse** tests, so the filter could have been inverted and the suite stayed green. Same shape as C36 and C37:
> a rule with nothing able to falsify it.
>
> Now closed by `QueryExecutorTest.chargeMatchesTheDeclaredChargeAndNothingElse`,
> `ms2precFiltersOnTheScansOwnPrecursorWithStrictBounds`, `ms2precBoundsAreStrictNotInclusive`,
> `ms2nlIsComputedFromEachScansOwnPrecursor`, `ms2nlBoundsAreStrict` and
> `anMs2ScanWithNoRecordedPrecursorCannotSatisfyMs2nl`. The MS2PREC strict bound was **sabotage-verified**:
> flipping `>`/`<` to `>=`/`<=` in `ConditionFilters:76` fails with `expected: <[]> but was: <[3, 5]>`.
>
> **`make spec-audit` check 4 now fails the build when a spec names a test class that does not exist**, which
> is the mechanical version of the question nobody thought to ask. The table below names real classes; where a
> spec-era name was folded into another class, the row says so rather than being deleted, so the original
> intent stays reviewable.

| Test | Pins |
|---|---|
| `ToleranceTest` | PPM wins when both present; default 0.1 Da when neither; PPM→Da arithmetic; a peak **exactly** at each edge matches; one ULP outside does not. |
| `IntensitySemanticsTest` **(was `ComparatorSemanticsTest` + `IntensityScaleTest`)** | `=` behaves as `>=` (a value exactly equal matches); `<` is strict; an **absent** intensity qualifier gets the implicit `> 0` **per column**, and an unqualified column keeps its floor even when another is qualified. `INTENSITYVALUE` vs `INTENSITYPERCENT` vs `INTENSITYTICPERCENT` read three different columns; both percent qualifiers ÷100 while the absolute one does not; the 0.99 cap applies to **both** percent columns and **only** for `>`, never to the absolute column. ⚠ **Not** "`Comparator.NONE` behaves as `>`" ([C18](Tech_Step_INDEX.md)): that constant does not exist and `Qualifier` rejects a null comparator, so there is nothing to assert — the absent-**qualifier** path is what carries the rule. |
| `IntensityAlgebraTest` | The property tests ported from `oracle/test_query_py_reference.py`: `>` / `<` **disjointness**, **monotonicity** (tightening a threshold never adds scans), **tripartite partition** (`<` ∪ `=` ∪ `>` covers everything exactly once). **These need no reference data — pure profit.** |
| **`QueryExecutorTest`** — end-to-end condition evaluation, and the home of five spec-era classes (`BoundsTest`, `ScanLevelConjunctionTest`, `ConditionCoverageTest`, `OrValueListTest`, `ZeroPeakScanExclusionTest`) | `rtBoundsAreStrictWhileScanBoundsAreInclusive`: `RTMIN`/`RTMAX` **strict** at the bound, `SCANMIN`/`SCANMAX` **inclusive**, in one method so the asymmetry is visible. `twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan`: the direct test of §1 — a row-level-AND implementation fails it. `anOrValueListIsSatisfiedByAnyValue`. `conditionOrderDoesNotChangeTheAnswer` (C37g). `aScanLevelOnlyQueryDoesNotReturnZeroPeakScans` and `polarityOnAnMgfMatchesEveryLoadedScan`. `microQueryMatchesTheGoldenScans` → `[1, 3]`, and `microEdgeQueryMatchesNothingBecauseBoundsAreStrict` → `[]`. **Plus the three conditions C38 found untested:** `CHARGE` (exact equality, not a window — the `0` sentinel is matchable as itself), `MS2PREC` (reads the scan's own `precmz`, **not** the peak array, with strict bounds) and `MS2NL` (loss derived from *each scan's own* precursor — two scans share a 200.5 peak and only their differing precursors separate them; strict bounds; and `precmz == 0` **excludes** rather than matching against zero, C37f). |
| **Condition coverage is now a closed set**, asserted rather than asserted-about: all ten 9a/9b conditions — `MS2PROD`, `MS2PREC`, `MS1MZ`, `MS2NL`, `RTMIN`, `RTMAX`, `SCANMIN`, `SCANMAX`, `CHARGE`, `POLARITY` — have at least one positive **and** one negative executed query. `MS2MZ` needs none of its own: [Step 4](Tech_Step4.md) resolves it to `MS2PROD` in the AST, so it is the same `ConditionType`. |
| `ConstantFoldingTest` | `+ - * /` folding with IEEE semantics; **`Expr.Unary` negation folds** (C35e — a spec that named only `Binary` would leave `MS2NL=-18` unfolded); precedence preserved from the AST; division by zero → infinity, matches nothing, no throw. |
| `ZeroIntensityPeakTest` | **C36**, MGF only: a zero-intensity peak is not present to be matched, and the mzML/mzXML contrast — where both sides *retain* zero-intensity peaks (`small.mzML`'s dump carries eight leading `0x0.0p+0` values) — is asserted in the same test so the asymmetry is visible rather than folklore. |

### Renamed and folded test classes

Every name this spec originally required, and where its coverage actually lives. Kept as redirects
rather than deleted: the original row is the record of what was *asked for*, and losing it is how a real
gap gets tidied out of sight. `make spec-audit` check 4 reads this table — an unlisted name that has no
file fails the build.

| Spec-era name | → Real home | Note |
|---|---|---|
| `ComparatorSemanticsTest` | → `IntensitySemanticsTest` | merged with `IntensityScaleTest`; comparator and scale semantics are one subject |
| `IntensityScaleTest` | → `IntensitySemanticsTest` | as above |
| `BoundsTest` | → `QueryExecutorTest` | `rtBoundsAreStrictWhileScanBoundsAreInclusive` |
| `ScanLevelConjunctionTest` | → `QueryExecutorTest` | `twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan` |
| `OrValueListTest` | → `QueryExecutorTest` | `anOrValueListIsSatisfiedByAnyValue` |
| `ZeroPeakScanExclusionTest` | → `QueryExecutorTest` | `aScanLevelOnlyQueryDoesNotReturnZeroPeakScans`. ⛔ **C35(c)** — a scan-level-only query over `PlusRise.mgf` must qualify **21,942** scans, not the 34,513 our reader yields. Sabotage-verified: removing the guard fails with `expected: <21942> but was: <34513>` |
| `Ms2NlTest` | → `QueryExecutorTest` | three methods. **The one that had no implementation at all** until C38 |
| `ConditionCoverageTest` | → `QueryExecutorTest` | not table-driven in the end, but the closed set above is asserted. **C38: this row is precisely where the gap hid** — a class that was never written could not report that `MS2PREC`, `CHARGE` and `MS2NL` were uncovered |
| `DegenerateQueryDiagnosticTest` | → `IntensityAlgebraTest` | `aWindowThatContainsNoPeakIsEmptyAndDiagnosed`, paired with `aTightToleranceAroundAnExactPeakStillMatches` so it cannot pass for the wrong reason |
| `RealQueryShapeTest` | → **deferred to [Step 12](Tech_Step12.md)** | The full condition sets of `test.massql` / `test_mzml.massql` need the real fixtures *and* their result goldens, which is the differential rather than a unit test. The micro shapes are covered here by `microQueryMatchesTheGoldenScans`. Deferred, not dropped |

## Done when

- [x] `make verify` green — **430 unit + 25 IT = 455, 0 skipped**; closure unchanged at 0.749 MB. (Was 428 + 25
      at Step 9's close; the spec-propagation round that followed added two tests — a `Comparator` arity
      assertion and a scale guard for `mzWindowExclusive`, each closing a spec claim that no test backed.)
- [x] Every 9a and 9b condition has a positive and a negative test.
- [x] **Every** rule in §3 has a dedicated assertion, and `docs/SEMANTICS.md` cites the source line for each —
      pointing at **`msql_engine_filters.py`**, the real authority (C37).
- [x] Scan-level intersection proven, not row-level AND
      (`QueryExecutorTest.twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan`).
- [x] The property tests pass, **with their preconditions asserted** rather than assumed (C37h).
- [x] `test_micro.massql` evaluates to the golden's `[1, 3]`; `test_micro_edge.massql` to the empty golden.
- [x] **Both critical guards proven to have teeth**: swapping `mzWindowExclusive` → `mzWindow` fails with
      `expected: <[]> but was: <[3]>`, and removing the zero-peak guard fails with
      `expected: <21942> but was: <34513>`.
- [x] Condition order proven irrelevant, by proof from the source **and** on the only fixture that can
      discriminate (C37g).

## References

- `SPIKE.md` §3 (*"Exact rules — each is one line, and each is a silent wrong-answer bug if missed"*), §6a
  (tolerance math, intensity comparators, intensity algebra rows), §7 Step 2 (the 2a/2b split, here 9a/9b)
- `oracle/test_query_py_reference.py` — the property tests to port
- `massql/msql_engine.py` @ pinned SHA — the authority for every rule in §3
- [Step 4](Tech_Step4.md) §3 and `Comparator.java`'s javadoc — why there is **no** `Comparator.NONE` (C35a)
- [Step 5](Tech_Step5.md) §4 (exact window bounds), §8 (non-destructive filtering)
- Consumer: [Step 10](Tech_Step10.md) collates the qualifying scans into result rows
