# Condition semantics

Every rule the engine implements, with the source line that establishes it. **Where this document and
the cited source lines disagree, the source lines win.**

> **The authority is `msql_engine_filters.py`, not `msql_engine.py`.** Tolerance, the intensity
> comparators and all four condition functions live there; only scan-level conditions are in
> `msql_engine.py`.

---

## 1. Tolerance

| Rule | Source | Test |
|---|---|---|
| **`TOLERANCEPPM` wins if both are given** — not "narrower wins", not an error | `_get_mz_tolerance:9-12` checks ppm first and *returns* | `ToleranceTest.ppmWinsWhenBothAreGiven` |
| PPM → Da: `tol = abs(ppm * target / 1e6)` | `:11` | `ppmIsConvertedFromTheTargetValue` |
| **Default `0.1` Da** when neither is given | `:7`, `:16` | `theDefaultIsPointOneDaltonWhenNeitherIsGiven` |
| Window centred on the **target**, never the observed peak | — | `boundsAreCentredOnTheTarget` |

### ⛔ The window is STRICT at both ends

`(target − tol, target + tol)`. **A peak exactly on a bound does NOT match.**

All four condition functions use `>` and `<`: `:253` (MS2PROD), `:410` (MS2PREC), `:493`/`:519` (MS1MZ),
`:607` (`ms1_filter`).

`micro.mzML` scan 3 carries a peak at exactly `201.0`; `MS2PROD=201.5:TOLERANCEMZ=0.5` gives the window
`[201.0, 202.0]`, placing it on the lower bound — and MassQL returns 0 rows.

**Use `SpectrumTable.mzWindowExclusive`, never `mzWindow`.** The two callers differ:

| Caller | Bound | Evidence |
|---|---|---|
| Condition windows | **strict** | the empty golden above |
| Precursor lookup | **inclusive** | `massql_query.py`'s `ms1_df["mz"] >= precmz - tol` uses `>=`/`<=`; at `--precursor-tol-ppm 7.8125` an exactly-on-bound peak **does** populate `ms1_i` |

Unifying them would diverge on `ms1_i`/`ms1_precmz`.

## 2. Comparators

| Rule | Source | Test |
|---|---|---|
| **`=` means `>=`** for intensity comparisons | `_get_intensity_mask:89` — *"equal → minimum threshold (>= val), preserving historical semantics"* | `IntensitySemanticsTest.equalsMeansGreaterOrEqual` |
| `<` is strict | `:87` | `lessThanIsStrict` |
| **An absent qualifier means an implicit `> 0` — per column, on all three** | `:71` and the `else` at `:91` | `anAbsentQualifierMeansImplicitGreaterThanZeroPerColumn` |

`=` → `>=` is **the rule most likely to be "fixed"** by a well-meaning implementer. The discriminating case:
with a threshold of 300, a peak of 600 must match. Under true equality it would not.

### There is no `Comparator.NONE`

`Comparator` is `{EQ, GT, LT}` and `Qualifier` **rejects a null
comparator**. Verified against the reference corpus: every in-scope qualifier carries `=`, `>` or `<`; the only
comparator-less ones are out-of-scope and rejected at parse. So *"a missing comparator defaults to
greater-than"* is about an **absent qualifier** — the implicit `> 0` above — not a qualifier that parsed
without one. The engine never has to ask "what if the comparator is missing", because it
cannot be.

## 3. Intensity scales — three different denominators

| Qualifier | Column | Divisor | Source |
|---|---|---|---|
| `INTENSITYVALUE` | `i` (absolute) | 1 | `:76` |
| `INTENSITYPERCENT` | `iNorm` (÷ **max** in scan) | **100** | `:77` |
| `INTENSITYTICPERCENT` | `iTicNorm` (÷ **sum** in scan) | **100** | `:78` |

Both "percent" qualifiers divide by 100. Confusing `iNorm` with `iTicNorm` gives a *plausible wrong answer* rather than an error,
which is why `IntensitySemanticsTest` uses a scan where all three scales disagree.

### The 0.99 cap

For `>` **only**, on **both** percent qualifiers: the threshold is clamped to `0.99`.

`iNorm`'s maximum is exactly 1.0, so `INTENSITYPERCENT>100` would otherwise match nothing — the source's
comment: *"if people set it to 100, then they won't get anything"*. The guard is `if scale > 1.0` (`:82-83`),
so it covers `INTENSITYTICPERCENT` too. It never applies to `INTENSITYVALUE`, and never to `>=` or `<`.

**Consequence worth knowing:** no `>` threshold on a percent column can be made unsatisfiable by raising it —
monotonicity *flattens* above 99. `INTENSITYVALUE` has no cap and does eventually match nothing.

## 4. Bounds — the asymmetry is deliberate

| Condition | Bound | Source |
|---|---|---|
| `RTMIN` / `RTMAX` | **STRICT** (`>` / `<`) | `msql_engine.py:425`, `:432` |
| `SCANMIN` / `SCANMAX` | **INCLUSIVE** (`>=` / `<=`) | `:452`, `:459` |

**Do not unify them.** `BoundsTest`/`QueryExecutorTest.rtBoundsAreStrictWhileScanBoundsAreInclusive` asserts
both in one place so the asymmetry is visible: on `micro.mzML`, `SCANMIN=3` gives `[3, 5]` while `RTMIN=1.0`
gives `[5]` — scan 3 sits exactly on the RT bound and is excluded.

RT is in **minutes**, from `scanRt` at double precision.

## 5. Polarity and charge

`POLARITY` → `polarity == 1` (positive) / `== 2` (negative); `0` (unknown) matches neither
(`msql_engine.py:440-444`). `CHARGE` → equality (`:466`).

> ⚠ **`POLARITY` cannot filter an MGF, and matches EVERYTHING there.** MGF polarity is a hardcoded `1`
>, so `POLARITY=Positive` matches
> every MGF scan and `POLARITY=Negative` none, whatever the spectra are. A `POLARITY=Positive` query over
> `PlusRise.mgf` returning all **21,942** loaded scans is **correct**, and there is no way to tell from the
> output.

## 6. Structure — scan-level, not row-level

A condition means **"this scan contains at least one peak satisfying P"**, not "this row satisfies P". So
conditions combine as a per-scan boolean AND over *"did this condition find any peak"* — **never** a row-level
AND, because two conditions may be satisfied by **different peaks in the same scan**.

That is what the source does, not an interpretation: every condition function reduces its matches to a *scan
set* and then re-admits **all rows of those scans** (`:283-288`, `:557-562`), so the next condition sees every
peak of each surviving scan.

`QueryExecutorTest.twoConditionsMayBeSatisfiedByDifferentPeaksInTheSameScan` is the direct test — a row-level
implementation returns `[]` where the correct answer is `[3]`.

### Condition order is provably irrelevant

Because no predicate ever sees a *reduced* peak list, each condition is a pure intersection `S ← S ∩ P` with
`P` fixed by the file — and intersection commutes. The constructs that **do** read filtered state
(`OTHERSCAN`, `INTENSITYMATCH*`, `CARDINALITY`, `EXCLUDED`) are all **rejected at parse**, which is what makes
this airtight rather than merely plausible.

Empirical backing needed a new fixture: `small.mzML`'s MS1 scans are profile-mode on an **identical m/z grid**
(19,800 peaks each), so no `MS1MZ` value distinguishes them, and `micro.mzML` has one usable MS1 scan.
`micro_ms1var.mzML` has two MS1 scans with *different* peaks; the mixed query returns `[2]` in **either**
order.

## 7. Zero-peak scans are skipped

MassQL's loaders `continue` on an empty intensity array, so its dataframes hold **no rows** for such scans.

A peak-based condition fails an empty scan by itself — but a **scan-level** condition never looks at peaks. So
without an explicit guard a scan-level-only query returns **34,513** scans on `PlusRise.mgf` where MassQL
returns **21,942**: a third of the result set, silently.

The guard sits **before** the MS1 retention, because an empty MS1 must not become an `ms1scan` link either
 — the same rule one layer up.

## 8. `MS2NL` — neutral loss

The source matches **per peak** on `(precmz − mz)` against `(value − tol, value + tol)`, rather than building
one target (`:311-315`). Algebraically the same window, so either form is faithful; this SDK rearranges to a
window on `mz` so the binary search does the work.

- A `precmz == 0` scan is excluded — **naturally**, since `0 − mz` is negative while the window is positive.
  The explicit guard states the rule rather than relying on the arithmetic.
- ⚠ The source carries `#TODO: This is incorrect logic if it comes to PPM accuracy`: with `TOLERANCEPPM` the
  tolerance is derived from the **neutral-loss value**, not from an m/z. Chemically questionable —
  **reproduce it**. Bug-for-bug fidelity is the requirement.

## 9. `OR` value lists

Satisfied by **any** value. The source builds one filtered frame per value and `pd.concat`s them
(`_merge_filter_cardinality` with no `CARDINALITY` qualifier) — a union. A single-valued condition is the
one-element case, so there is one code path rather than two.

## 10. Constant folding

`+ - * /` with **IEEE double semantics**, folded once before evaluation. MassQL does double arithmetic and
matching it bit-for-bit is the goal, so `0.1 + 0.2` must stay `0.30000000000000004`; rounding to a "nice"
`0.3` would shift every downstream tolerance window by an ULP.

Division by zero yields **infinity, not an exception** — an infinite target matches nothing, which is the right
outcome for a degenerate query.

⚠ The AST is `Expr.Literal` / `Expr.Binary` / **`Expr.Unary`**. A folder that omits `Unary` silently leaves
`MS2NL=-18` unfolded.

## 11. Diagnostics

The SDK **logs nothing**, to either stream. A valid-but-degenerate query returns its explanation in
`ExecutionSummary.diagnostics()` instead: the CLI writes them to stderr; a GUI could show them in a
dialog.

An empty result set is a legitimate answer. A **silent** empty result set is a poor one.

---

## The properties, and their preconditions

Ported from MassQL's own `tests/test_query.py`. The properties are self-referential, but the tests as
written need two fixtures this project does not have (`featurelist_pos.mgf`, `GNPS00002_A3_p.mzML` —
MassQL's own test data), so they are reconstructed on the fixtures here.

| Property | General? |
|---|---|
| Raising a `>` threshold never adds scans | ✅ yes |
| Lowering a `<` cap never adds scans | ✅ yes |
| `=` (i.e. `>=`) is a **superset** of `>` | ✅ yes — and it directly encodes the `=` → `>=` rule |
| `>` and `<` at the same threshold are **disjoint** | ⚠ **only** when at most one peak per scan falls in the window |

**Disjointness is not general.** Under scan-level semantics a scan may hold one peak above the threshold and
another below it, placing it in *both* sets — correctly. The reference test avoids this with
`TOLERANCEMZ=0.01`; `IntensityAlgebraTest` constructs that precondition and **asserts it** rather than
assuming it.

**`<` ∪ `=` ∪ `>` is not a partition.** `=` means `>=`, which *contains* `>` by construction. The reference
test asserts the real relationship, `>` ⊆ `=`, and so does this SDK.
