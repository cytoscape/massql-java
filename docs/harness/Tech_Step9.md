# Tech Step 9 — Condition filters

## Goal

Evaluate a parsed `WHERE`/`FILTER` clause against the loaded store, producing the set of qualifying scans —
with MassQL's tolerance, comparator and intensity semantics reproduced exactly, including the ones that look
like bugs.

## Prerequisites

| Step | Why |
|---|---|
| [Step 4](Tech_Step4.md) | Provides the typed AST, including `Comparator.NONE` distinct from `EQ` and unfolded arithmetic. |
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
| `src/main/java/…/massql/exec/QueryExecutor.java` | AST → qualifying scan ordinals |
| `src/main/java/…/massql/exec/ConditionFilters.java` | Per-condition evaluators |
| `src/main/java/…/massql/exec/Tolerance.java` | Window computation, one place |
| `src/main/java/…/massql/exec/IntensityQualifiers.java` | Comparator + intensity semantics |
| `src/main/java/…/massql/exec/ConstantFolder.java` | Arithmetic folding |
| `src/test/java/…/exec/*Test.java` | The test set below |
| `docs/SEMANTICS.md` | The §3 rules, each with the source line that establishes it |

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
> ```
> SpectrumTable retainedMs1 = null;
> while (stream.next()) {
>     ScanView v = stream.current();
>     if (v.msLevel() == 1) retainedMs1 = v.materialize();
>     if (v.msLevel() != wantedLevel) continue;
>     SpectrumTable scan = v.materialize();          // single-scan table
>     if (allConditionsHold(scan, v, retainedMs1)) emit(v, scan, retainedMs1);
> }
> ```


A MassQL condition is fundamentally *"this scan contains at least one peak satisfying P"*, not *"this row
satisfies P"*. So:

```java
/** Ordinals (not scan ids) of scans satisfying every condition. */
public int[] execute(MassqlQuery q, SpectrumTable ms1, SpectrumTable ms2);
```

Per condition: build a `RowMask` over the relevant table, then reduce to the set of scans retaining at least one
row (`SpectrumTable.scansWithAnyRow`). Combine across conditions with **set intersection over scans**, not row-level
AND — two conditions may be satisfied by *different* peaks in the same scan, and requiring one row to satisfy both
would be wrong. This distinction is the most likely structural error in the step; state it in `SEMANTICS.md`.

Scan-level conditions (`RTMIN`, `SCANMIN`, `CHARGE`, `POLARITY`) filter the scan set directly, with no row mask.

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

### 3. Exact semantics — each one a silent wrong answer if missed

Put every rule in `docs/SEMANTICS.md` alongside the `msql_engine.py` line that establishes it. Where this spec and
the pinned Python source disagree, **the source wins** — correct the spec and note it.

**Tolerance**

- **`TOLERANCEPPM` wins if both are given.** Not "narrower wins", not an error — PPM takes precedence.
- **Default `0.1` Da if neither is given.**
- PPM → absolute: `tol = value * ppm / 1e6`. Window is `[value − tol, value + tol]`, **both bounds inclusive**
  (matching [Step 5](Tech_Step5.md) §4). A peak exactly at an edge matches.
- Compute the window from the **target value**, not from the observed peak.

**Comparators**

- **`=` means `>=` for intensity comparisons.** Verbatim from `SPIKE.md` §3: *"preserving historical semantics."*
  It looks like a bug; reproduce it.
- **A missing comparator defaults to greater-than.** This is why [Step 4](Tech_Step4.md) must keep
  `Comparator.NONE` distinct from `EQ` — collapsing them changes results.
- **An intensity column with no explicit qualifier gets an implicit `> 0`.** So a bare `MS2PROD=100.0` requires a
  peak with non-zero intensity, not merely a peak.

**Intensity scales** — three different denominators, and mixing them up gives plausible wrong answers:

| Qualifier | Compares against | Column |
|---|---|---|
| `INTENSITYVALUE` | absolute intensity | `i` |
| `INTENSITYPERCENT` | intensity ÷ **max in scan** | `iNorm` |
| `INTENSITYTICPERCENT` | intensity ÷ **sum in scan** | `iTicNorm` |

- **`INTENSITYPERCENT` divides by 100** (a value of `5` means 5% → `0.05`).
- **With `>`, `INTENSITYPERCENT` is capped at 0.99.** So `INTENSITYPERCENT>100` becomes `> 0.99`, not `> 1.0`
  (which nothing could satisfy, `iNorm`'s maximum being exactly 1.0). Apply the cap **only** for `>`.
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

**`MASSDEFECT`:** fractional part of the mass. Derive the exact definition (which mass, and whether the bound is
inclusive) from the pinned Python source and record it; do not infer it from the name.

### 4. Constant folding

`ConstantFolder` walks `Expr` and reduces `BinaryExpr` over `NumberLiteral`s to a single `NumberLiteral`.
`+ - * /` with IEEE double semantics — no rational arithmetic, no rounding to a "nice" value, because MassQL is
doing double arithmetic and matching it bit-for-bit is the goal. Division by zero yields infinity rather than
throwing; the condition then matches nothing, which is the correct outcome.

Fold once, before evaluation, not per row.

### 5. Diagnostics

The SDK logs nothing (`DEPENDENCY_POLICY.md` constraint 5). Where a query is valid but degenerate — a tolerance so
tight nothing can match, an `MS2NL` against a `0` precursor, an RT window outside the file's range — return the
information as a **diagnostic on the result**, so [Step 11](Tech_Step11.md)'s CLI can print it to stderr and the
Phase-2 app can surface it. An empty result set is a legitimate answer ([Step 12](Tech_Step12.md) requires empty
JSON array, exit 0), but a *silent* empty result set is a poor one.

## Known traps

- **Row-level AND instead of scan-level intersection.** Two conditions may be satisfied by different peaks in the
  same scan. §1.
- **Normalizing `=` to equality.** It means `>=`. This is the rule most likely to be "fixed" by a well-meaning
  implementer.
- **Treating a missing comparator as `=`.** It is greater-than.
- **Forgetting the implicit `> 0`** on unqualified intensity columns.
- **Applying the 0.99 cap to `>=`** or to the other intensity qualifiers. It is `>` and `INTENSITYPERCENT` only.
- **Unifying strict `RTMIN/RTMAX` with inclusive `SCANMIN/SCANMAX`.** They genuinely differ.
- **Confusing `iNorm` (÷max) with `iTicNorm` (÷sum).** Both are "percent"; different denominators.
- **Choosing "narrower tolerance wins" when both are given.** PPM wins regardless of which is narrower.
- **Building the window from the observed peak rather than the target value.**
- **Adding an epsilon to window bounds.** [Step 5](Tech_Step5.md) §4 deliberately takes exact bounds; adding slack
  here widens every tolerance in the system.

## Tests required

All unit (`*Test.java`), on the [Step 2](Tech_Step2.md) micro-fixtures and hand-built tables.

| Test | Pins |
|---|---|
| `ToleranceTest` | PPM wins when both present; default 0.1 Da when neither; PPM→Da arithmetic; a peak **exactly** at each edge matches; one ULP outside does not. |
| `ComparatorSemanticsTest` | `=` behaves as `>=` (a value exactly equal matches); `Comparator.NONE` behaves as `>`; unqualified intensity column gets implicit `> 0` — a zero-intensity peak fails it. |
| `IntensityScaleTest` | `INTENSITYVALUE` vs `INTENSITYPERCENT` vs `INTENSITYTICPERCENT` on one scan where all three give **different** answers; `INTENSITYPERCENT` ÷100; the `>` cap at 0.99 applies to `>` and **not** to `>=`. |
| `IntensityAlgebraTest` | The property tests ported from `oracle/test_query_py_reference.py`: `>` / `<` **disjointness**, **monotonicity** (tightening a threshold never adds scans), **tripartite partition** (`<` ∪ `=` ∪ `>` covers everything exactly once). **These need no reference data — pure profit.** |
| `BoundsTest` | `RTMIN`/`RTMAX` **strict** at the bound; `SCANMIN`/`SCANMAX` **inclusive** at the bound; both in one test so the asymmetry is visible. |
| `ScanLevelConjunctionTest` | Two conditions satisfied by **different peaks in the same scan** → the scan qualifies. The direct test of §1; a row-level-AND implementation fails it. |
| `ConditionCoverageTest` | One test per 9a and 9b condition, each with a positive and a negative case. Table-driven; a condition with no row is a gap. |
| `OrValueListTest` | Any value in the list qualifies; none does not. |
| `ConstantFoldingTest` | `+ - * /` folding with IEEE semantics; precedence preserved from the AST; division by zero → infinity, matches nothing, no throw. |
| `Ms2NlTest` | Neutral loss computed from the scan's own `precmz`; a scan with `precmz == 0` is **excluded**, not matched against 0. |
| `RealQueryShapeTest` | The exact condition set of `test.massql` (three `MS2PROD` + `TOLERANCEPPM=20` + `INTENSITYPERCENT` at 1/5/1) and of `test_mzml.massql` (`MS2PREC=810.79:TOLERANCEMZ=1.0`) evaluate on the micro-fixtures with hand-computed expected scan sets. |
| `DegenerateQueryDiagnosticTest` | An impossible tolerance returns an **empty** set plus a diagnostic, not an exception. |

## Done when

- [ ] `mvn test` green.
- [ ] Every 9a and 9b condition has a positive and a negative test.
- [ ] All ten §3 rules have a dedicated assertion, and `docs/SEMANTICS.md` cites the Python source line for each.
- [ ] `ScanLevelConjunctionTest` passes — proving scan-level intersection, not row-level AND.
- [ ] The ported property tests pass.
- [ ] `test.massql`'s and `test_mzml.massql`'s condition sets evaluate to hand-verified scan sets on the
      micro-fixtures.

## References

- `SPIKE.md` §3 (*"Exact rules — each is one line, and each is a silent wrong-answer bug if missed"*), §6a
  (tolerance math, intensity comparators, intensity algebra rows), §7 Step 2 (the 2a/2b split, here 9a/9b)
- `oracle/test_query_py_reference.py` — the property tests to port
- `massql/msql_engine.py` @ pinned SHA — the authority for every rule in §3
- [Step 4](Tech_Step4.md) §3 — why `Comparator.NONE` is preserved
- [Step 5](Tech_Step5.md) §4 (exact window bounds), §8 (non-destructive filtering)
- Consumer: [Step 10](Tech_Step10.md) collates the qualifying scans into result rows
