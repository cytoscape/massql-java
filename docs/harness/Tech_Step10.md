# Tech Step 10 — `scaninfo` collation, result model, JSON

## Goal

Turn qualifying scans into the exact 12-key result contract — including the five columns MassQL does **not**
produce and the SDK must compute itself — and serialize it as the published JSON a consumer will store
verbatim.

## Prerequisites

| Step | Why |
|---|---|
| [Step 9](Tech_Step9.md) | Provides the qualifying scan set. |
| [Step 5](Tech_Step5.md) | Provides `argmax` (row index, ties → lowest), the **inclusive `mzWindow`** — *not* the exclusive variant Step 9 uses, see §3 rule 4 — `Reductions`, and the exact double `scanRt`. |

## Context

**Only 7 of the 12 result keys come from MassQL. The SDK must compute the other 5** — this is the most
easily-missed part of the whole contract. `massql_query.py` computes them in Python on top of MassQL's raw output;
here they move into the SDK so they are unit-tested directly and a consumer's own write-back stays
a dumb loop.

| Source | Columns |
|---|---|
| MassQL `scaninfo` native | `scan`, `precmz`, `ms1scan`, `rt`, `charge`, `tic` (MassQL's `i`, renamed), `mslevel` |
| **Computed here** (`massql_query.py`'s `add_precursor_intensity`, `:163-167`) | `base_peak_i`, `base_peak_mz` — per-scan argmax over MS2 peaks |
| **Computed here** | `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` — precursor lookup in the linked MS1 scan |
| **Dropped** from MassQL's raw output | `i_norm` (structurally always 1.0), `i_norm_ms1` (only null or 1.0) |

`ResultJson`'s output is a **published contract**, not an implementation detail: the app writes the string into
the node table verbatim and `MASSQL_PARSE` reads it back, so key names, key set and float formatting are all
frozen here.

Governing sections: [`SPIKE.md`](SPIKE.md) §3 (the contract, the population table, the exact rules), §4 (API rules), §6a
(precursor lookup, null/sentinel, result JSON rows). ⚠ **SPIKE.md §3's MS1DATA paragraph is corrected by C40** —
[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) is the contract.

## Scope

**In scope**
- `ScaninfoCollation` — the 7 native + 5 computed columns.
- The precursor lookup.
- The null / sentinel / NaN rules.
- `ScanInfoResult` (boxed types) and `ResultJson`.
- **The single 12-key output shape**, for both MS2DATA and MS1DATA (Correction **C40**; contract in
  [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)).

**Out of scope**
- Filtering — [Step 9](Tech_Step9.md).
- The CLI and the public `Massql.execute` entry point — [Step 11](Tech_Step11.md).
- Comparing against goldens — [Step 12](Tech_Step12.md).
- Producing the `0` sentinels. Readers ([Step 6](Tech_Step6.md), [Step 7](Tech_Step7.md)) emit raw `0`; **this
  step converts.**

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/exec/ScaninfoCollation.java` | Collation |
| `src/main/java/…/massql/exec/PrecursorLookup.java` | The §3 lookup, isolated so it is directly testable |
| `src/main/java/…/massql/result/ScanInfoResult.java` | Boxed result row |
| `src/main/java/…/massql/result/ResultJson.java` | Serializer |
| `src/test/java/…/result/*Test.java`, `…/exec/*Test.java` | The test set below |
| **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)** | The frozen key set, ordering, per-field nullability, formatting, and the population-by-format table. ⚠ **Renamed by C40**: this row used to create a second document, `RESULT_CONTRACT.md` (path deliberately not written in full here, so `spec-audit` check 5 does not read a retired name as a live reference). It would have been a *second* place defining the same contract — the duplication that caused C40. [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) already exists and is the single definition; **extend it, do not add a sibling.** |

## Specification

### 1. `ScanInfoResult`

**Boxed `Double` / `Integer` throughout**, so null is a real, testable value rather than a sentinel:

```java
public record ScanInfoResult(
    Integer scan,           // never null
    Double  precmz,         // null if the file recorded none
    Integer ms1scan,        // null if no linked MS1 survey scan
    Double  rt,             // never null — 0.0 is a real value
    Integer charge,         // null if not recorded
    Double  tic,            // never null
    Integer mslevel,        // never null: 2 for MS2DATA, 1 for MS1DATA
    Double  basePeakI,      // never null for MS2DATA
    Double  basePeakMz,     // never null for MS2DATA
    Double  ms1I,           // null: no MS1 data, or tolerance miss
    Double  ms1Precmz,      // null: same conditions as ms1I
    Double  ms1BasePeakI    // null only if no linked MS1 scan
) { }
```

> ⚠ **Correction C40 removed a 13th component, `boolean ms1DataShape`.** With one uniform 12-key shape there is
> nothing for it to select, and it was a per-**query** property stored per-**row** — 664 identical copies in a
> `plusrise` result, and part of `equals`/`hashCode` for no reason. `ResultJson` takes no shape parameter either.
>
> The `basePeakI`/`basePeakMz` comments above read *"never null for MS2DATA"*; under C40 they are **never null at
> all**, MS1DATA included. See [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md).

All the sentinel and NaN rules live **in the SDK**, not in the consuming app — that is the design rule from
[`SPIKE.md`](SPIKE.md) §4, and it is why they are unit-tested here.

### 2. The two native computations

> ⛔ **Correction C34 — our `tic` will NOT equal the golden's bit-for-bit, and that is correct.** MassQL's
> intensity column is `float32` and `tic` is a pandas `groupby.sum()` over it, so the golden carries float32
> accumulation error while our float64 sum is exact. Measured on `small.mzML`: golden `586278.875` vs our
> `586278.8533592224`, relative **3.691e-08**; all six golden rows differ.
>
> **Compare `tic` at relative 1e-6.** Do **not** "fix" our sum to match by accumulating in float — the
> reference is the less accurate side. And do not loosen the other intensity columns: `base_peak_i`,
> `ms1_i` and `ms1_base_peak_i` are `max()`/lookup *selections* with no accumulation, and are
> **bit-identical**, verified on all six rows.

- **`tic`** = **sum** of all fragment intensities in the scan. This is MassQL's `i` renamed. Note it is a *sum*
  for `scaninfo` specifically; other MassQL functions put a different quantity in `i` (`scanmaxint` puts the base
  peak there), which is why `massql_query.py`'s `rename(columns={"i": "tic"})` guards the rename to `scaninfo` queries only. We only support
  `scaninfo`, so the rename is unconditional — the reason is recorded in
  [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) so it isn't generalized incorrectly if another function is ever
  added.
- **`rt`** comes from `scanRt` at double precision. The mzML golden's `0.011218333333333334` does not survive a
  float round-trip.

### 3. The five computed columns

**`base_peak_i` / `base_peak_mz`** — per-scan argmax over that scan's MS2 peaks:

```
row = argmax(i) over the scan          // ties → lowest row index (Step 5 §6, matching pandas idxmax)
base_peak_i  = i[row]
base_peak_mz = mz[row]
```

Mirrors `massql_query.py`'s `groupby("scan")["i"].idxmax()` (`ms2_df.groupby("scan")["i"].idxmax()`). Computed from the **loaded table**,
not by re-parsing the file, so scan ids line up exactly.

**`ms1_i` / `ms1_precmz` / `ms1_base_peak_i`** — the precursor lookup, and the single most likely misreading of the
entire contract.

> ⚠ **Correction C22: the MS1 scan comes from the STREAM's retained scan, not a whole-file MS1 table.**
> By the document-order rule, `ms1scan` is always the most recent *preceding* MS1 scan — so the
> executor holds exactly that one scan as a single-scan `SpectrumTable`, and this lookup runs against
> it. Assert `retainedMs1.index().scanIdAt(0) == ms1scan`; if it ever disagrees, the document-order
> rule has been broken upstream. The rule we treated as a fidelity burden is what makes streaming
> possible at all.


```
if ms1scan is present (non-zero) and the MS1 table has that scan:
    ms1_base_peak_i = max(i) across the WHOLE MS1 scan        # independent of any match
    if precmz is present (non-zero, not NaN):
        tol  = precmz * precursorTolPpm / 1e6                 # default 20.0 ppm
        cand = MS1 peaks in that scan with mz in [precmz - tol, precmz + tol]   # INCLUSIVE, see rule 4
        if cand is non-empty:
            best      = the candidate CLOSEST IN m/z TO precmz     # <-- NOT the most intense
            ms1_i     = i[best]
            ms1_precmz = mz[best]
```

Four rules inside that, each independently testable:

1. **Closest, not most intense.** `massql_query.py`'s `.abs().argmin()` — `cand.iloc[(cand["mz"] - precmz).abs().argmin()]`.
   Picking the most intense peak in the window is the intuitive reading and it is wrong. [`SPIKE.md`](SPIKE.md) §6a calls the
   test for this *"the test that catches the most likely misreading of the whole contract."* The
   [Step 2](Tech_Step2.md) micro-fixtures were built with a window where those two differ.
2. **`ms1_base_peak_i` does not depend on the match.** It is populated whenever the linked MS1 scan exists, so a
   tolerance miss nulls **only** `ms1_i` and `ms1_precmz`. `massql_query.py`'s `ms1_base = ms1_df.groupby("scan")["i"].max()` computes it before, and
   independently of, the window search.
3. **Ties in "closest".** If two candidates are equidistant from `precmz`, pandas' `argmin` returns the **first**
   occurrence — the lower m/z, given ascending sort. Match that.
4. ⛔ **Use the INCLUSIVE `mzWindow` here. Do not carry [Step 9](Tech_Step9.md)'s choice over.** Added by
   **Correction C37**, which was discovered while implementing Step 9 and split
   [Step 5](Tech_Step5.md) §4's single method in two. `massql_query.py`'s `ms1_df["mz"] >= precmz - tol` builds this window with
   `>=`/`<=`:

   ```python
   ms1_peaks = ms1_peaks[ms1_peaks["mz"] >= precmz - tol]
   ms1_peaks = ms1_peaks[ms1_peaks["mz"] <= precmz + tol]
   ```

   whereas Step 9's condition filters use `>`/`<` (`msql_engine_filters.py:253`) and therefore
   `mzWindowExclusive`. **The two genuinely differ and must not be unified** — an implementer arriving here
   straight from Step 9 is exactly the failure mode this rule exists to stop, and it fails *silently*: the only
   symptom is a peak sitting exactly on a bound, which changes `ms1_i`/`ms1_precmz` from a value to `null`.
   Those are columns [Step 12](Tech_Step12.md) compares at **1e-9**, so it surfaces as an unexplained parity
   failure far from its cause.

   Verified by execution rather than inferred: at `--precursor-tol-ppm 7.8125` — chosen so the tolerance lands
   the window edge exactly on an MS1 peak — the reference implementation returns `ms1_i = 1000.0`. The
   exclusive variant would return `null`. **Assert this**, do not merely read it.

`ms1_base_peak_i` is the normalization reference: relative precursor abundance = `ms1_i / ms1_base_peak_i`.

The tolerance here is `MassqlOptions.precursorTolPpm` (default **20.0**), which is a *separate knob* from the
query's own `TOLERANCEPPM`/`TOLERANCEMZ` ([Step 9](Tech_Step9.md) §3). Do not conflate them — the query tolerance
selects scans; this one matches the precursor peak within an already-selected scan.

### 4. Null, sentinel and NaN rules

**`0` → null for `precmz`, `ms1scan`, `charge` — and only those three.**

> **Correction C20 — read all three off `ScanIndex`, not off a peak column.** They are per-**scan**
> metadata: MassQL's `ms2_df` carries them per peak only because pandas is a flat frame, and each has exactly
> one distinct value per scan. [Step 5](Tech_Step5.md) stores them on the scan index accordingly, which is both
> semantically right and far smaller — a 20,000-peak scan would otherwise hold 20,000 copies of its own
> precursor m/z. Two consequences for this step: they arrive carrying MassQL's raw **`0` sentinel**, so the
> conversion below is *this* step's job and nothing upstream has done it; and **`ms1_df` has no such columns at
> all**, so on an MS1 table they are `0` throughout — which is why the MS1DATA shape omits them rather than
> emitting three nulls.

| Column | Why 0 can't be real |
|---|---|
| `precmz` | An ion m/z is always > 0 |
| `ms1scan` | Scan ids are 1-based; `0` is MassQL's "no linked MS1 scan" default ([Step 6](Tech_Step6.md) §4) |
| `charge` | A real ion charge is nonzero; `0` is the standard "unknown" |

**Never convert `rt`.** `0.0` is a genuine retention time — direct-infusion data, and every MGF row without
`RTINSECONDS`. `plusrise_results.json` has `rt: 0.0` on all 664 records, so an over-eager null conversion fails
664 rows at once. `massql_query.py`'s `for col in ("precmz", "ms1scan", "charge")` converts exactly three columns and its comment says so explicitly.

**NaN / ±infinity → null**, so the output is valid JSON. `massql_query.py`'s `clean_nan` (`clean_nan`) applies this
recursively to every float. This is also where [Step 5](Tech_Step5.md)'s `NaN` for an all-zero-intensity scan's
`iNorm` gets normalized away.

Order of operations matters: **compute → sentinel-convert → NaN-convert → serialize.** Converting sentinels after
NaN handling, or before computing `ms1_base_peak_i`, changes results — the lookup needs the raw `0` to know there
is no linked scan.

### 5. `ResultJson` — the frozen contract

**⛔ The key set, key order, per-field nullability and float policy are defined in
[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md), not here.** Do not restate them in this file — that duplication
is what let four documents drift into three different answers (Correction **C40**). Read that file before
implementing; this section covers only what is specific to *building* the serializer.

> ✅ **RESOLVED — the OPEN DECISION this section carried is closed (Correction C40).** It asked whether MS1DATA
> emits SPIKE.md's **4** keys, the reference wrapper's **9**, or 9 with real base peaks. **None of those: the
> answer is the same 12 keys as MS2DATA**, because
> [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) defines one *union* schema
> discriminated by `mslevel`, and SPIKE.md had narrowed the very issue it cites.
>
> **There is one shape, so there is nothing to branch on.** For an MS1 row: `precmz`, `ms1scan`, `charge` and
> all three `ms1_*` are **`null`** (a survey scan has no precursor), while **`base_peak_i` / `base_peak_mz` are
> real values** — issue #26 marks both *"Can be null? No"*. The old golden's nulls there were a left-join
> artifact in `massql_query.py`, not semantics; `small_mzml_ms1_results.json` has been regenerated.
>
> Consequences for this step: **`ScanInfoResult` has no `ms1DataShape` field** and `ResultJson` takes no shape
> parameter. The old field was a per-*query* flag stored per-*row* — 664 copies of one boolean in a `plusrise`
> result, polluting `equals`/`hashCode` — and now has nothing to select.

Two things this step still owns:

- **Compute base peaks from the scan's own table.** `QualifyingScanConsumer` hands you `scan` (the qualifying
  scan's single-scan table) and `retainedMs1`. Base peaks come from **`scan`**, whatever the query's level. That
  is the mirror of the oracle fix, and the rule is: *MS1 ids join only to MS1 data, MS2 ids only to MS2 data.*
- **Emit every key, always.** No key is ever omitted; a Java `null` renders as JSON `null`. Do not substitute
  `0`, `""` or `"None"`, and do not omit a null key.

Output is a **JSON array** of objects; an empty result is `[]`, not `null` and not an error.

**Float formatting — the policy is `Double.toString`, compact, and it lives in
[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md).** What matters when writing the serializer: `Double.toString` is
shortest-round-trip, and **round-trip bit-exactness is the actual requirement** — not matching Python's bytes.
Java differs from Python in known ways (exponents `1.0E-5` vs `1e-05`; always emitting `.0` on integral values),
which is exactly why [Step 12](Tech_Step12.md) compares **parsed values, never text**. Emit canonical numbers;
never reformat, round or truncate — any rounding here corrupts the bit-identity Steps 8 and 12 establish.

Write the serializer by hand — no Jackson/Gson. The output shape is 12 fixed keys, the dependency budget is tight
(`DEPENDENCY_POLICY.md` constraint 6), and Jackson finds modules via `ServiceLoader`, violating constraint 1.

### 6. Population by input format

**The authoritative table is in [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)** — including the union rule and
the per-field nullability. It is reproduced here only because this is the step that implements it; if the two
ever disagree, that file wins (Correction **C40**).

| Column | MGF (MS2 only) | mzML / mzXML (MS1 + MS2) |
|---|---|---|
| `scan` | ✔ | ✔ |
| `precmz` | ✔ (from `PEPMASS=`) | ✔ |
| `ms1scan` | **null** — no survey scans exist | ✔ by **document order** ([Step 6](Tech_Step6.md) §4) |
| `rt` | **`0.0`, not null** | ✔ |
| `charge` | ⚠ **never null** — `CHARGE=` if present, else **`1`** (Correction C6; [`SPIKE.md`](SPIKE.md) §3 wrongly says null) | ✔ if recorded, else null via the `0` sentinel. ⚠ **mzXML's absent default is `0`, not MGF's `1`** (`msql_fileloading.py:451`), and `DP00570_F02.mzxml` carries **zero** `precursorCharge` attributes — so in practice *every* row from it is null. That makes `charge` a **predicted difference** in [Step 12](Tech_Step12.md)'s Pair B, not a shared column (Correction C29) |
| `tic` | ✔ sum of MS2 fragment intensities | ✔ |
| `mslevel` | `2` | `2` |
| `base_peak_i` / `base_peak_mz` | ✔ | ✔ |
| `ms1_i` | **null** | ✔ if `ms1scan` resolved **and** a peak matches within `precursorTolPpm` |
| `ms1_precmz` | **null** | ✔ same condition — the *measured* centroid, usually a few ppm off `precmz` |
| `ms1_base_peak_i` | **null** | ✔ whenever the linked MS1 scan exists — **a tolerance miss does not null it** |

When the MS1 table is empty (MGF), all three `ms1_*` are null without any lookup —
`massql_query.py`'s `len(ms1_df) == 0` branches on exactly that.

## Known traps

- **Taking the most intense peak in the precursor window instead of the closest.** The headline trap. §3.1.
- **Nulling `ms1_base_peak_i` on a tolerance miss.** It survives the miss. §3.2.
- **Null-converting `rt`.** `0.0` is real, and it is 664 rows in one golden. §4.
- **Converting `0` → null for a fourth column** because it looks empty. Exactly three.
- **Sentinel-converting before the precursor lookup runs.** The lookup needs raw `0` to detect "no linked scan".
- ⛔ **Omitting keys for MS1DATA.** ~~*"Rendering MS1DATA's precursor keys as `null` instead of omitting them —
  different contract."*~~ **Inverted by C40:** the contract is one 12-key union, so those keys are **present and
  null**. Omitting them is the bug.
- ⛔ **Nulling `base_peak_i`/`base_peak_mz` for MS1DATA** to match the old golden. A survey scan has a base peak;
  the golden's nulls were a `ms2_df` join artifact (C40) and the golden has been regenerated.
- **Rounding or reformatting floats** to make a text diff pass. Compare values, not text. §5.
- **Using a JSON library.** `ServiceLoader` + budget. §5.
- **`argmax` ties resolving to the last row.** Must be first, matching pandas `idxmax`
  ([Step 5](Tech_Step5.md) §6).
- **Conflating `precursorTolPpm` with the query's `TOLERANCEPPM`.** Two different knobs. §3.
- ⛔ **Reaching for `mzWindowExclusive` because [Step 9](Tech_Step9.md) uses it.** This step's window is
  **inclusive**. §3.4, Correction C37. Fails silently and surfaces as a Step 12 parity failure.

## Tests required

All unit (`*Test.java`), on the [Step 2](Tech_Step2.md) micro-fixtures and hand-built tables.

| Test | Pins |
|---|---|
| **`PrecursorLookupTest`** | **The most important test in this step.** A window containing two peaks where the **closer one is less intense** → `ms1_i`/`ms1_precmz` come from the **closer** peak. Also: tolerance miss → `ms1_i`/`ms1_precmz` null but `ms1_base_peak_i` **populated**; no linked MS1 scan → all three null; equidistant tie → lower m/z wins; `precmz == 0` → no lookup. **Plus the C37 bound case: a peak sitting exactly on `precmz ± tol` IS a candidate** — at `precursorTolPpm` 7.8125 the reference gives `ms1_i = 1000.0`, and this assertion is the only thing standing between a future refactor and a silent switch to `mzWindowExclusive`. |
| `BasePeakTest` | `base_peak_i`/`base_peak_mz` from argmax; a tie resolves to the **first** (lowest-m/z) row; single-peak scan; the mz read comes from the argmax row, not from a separate max. |
| `SentinelNullTest` | `precmz`/`ms1scan`/`charge` `0` → null; **`rt` `0.0` preserved** (assert `0.0`, not null, and assert it is not `null` explicitly); no other column converted. |
| `NanNullTest` | NaN → null; `+∞`/`−∞` → null; the resulting JSON parses. |
| `ResultJsonShapeTest` | ⚠ **Rewritten by C40.** **Both** MS2DATA and MS1DATA emit **exactly the same 12 keys in the same order** — assert the key *list*, not a set, so order is pinned. For an MS1 row assert `precmz`/`ms1scan`/`charge`/`ms1_*` are **present with JSON `null`** — i.e. `json.has("precmz") && json.get("precmz").isNull()`, the **opposite** of what this row used to require (`!json.has("precmz")`). Also: `base_peak_i`/`base_peak_mz` **non-null on an MS1 row**; null renders as JSON `null`; empty result → `[]`. |
| **`ResultSchemaContractTest`** | **Makes [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) executable.** Parses the key table out of that document and asserts `ResultJson` emits **exactly** those keys in **exactly** that order. This is what makes "defined once" real rather than aspirational — reordering a row in the doc fails the build. Same spirit as `VendoredProvenanceTest`. |
| `ResultJsonRoundTripTest` | Every emitted float parses back to the **identical bits** — guards against a formatter that rounds. |
| `TicIsSumTest` | `tic` is the sum of fragment intensities, not the base peak — the distinction `massql_query.py`'s `rename(columns={"i": "tic"})` guards. |
| `CollationAnchorTest` | Build a table reproducing `small.mzML`'s scan 3 and assert the full first golden record field by field. **At the default 20 ppm** (`output/small_mzml_results.json`): `scan` 3, `precmz` 810.79, `ms1scan` 2, `rt` 0.011218333333333334, `charge` null, **`tic` 586278.875 compared at relative 1e-6** (our float64 sum gives 586278.8533592224 -- the golden is a float32 accumulation, C34), `mslevel` 2, `base_peak_i` 161140.859375, `base_peak_mz` 736.6370849609375, **`ms1_i` null, `ms1_precmz` null**, `ms1_base_peak_i` **183838.71875**. That row is itself the tolerance-miss case — the nearest MS1 peak is 34.8 ppm away, so the match fails while `ms1_base_peak_i` survives. **At 60 ppm** (`output/small_mzml_tol60_results.json`) the same row has `ms1_i` 131528.0625 and `ms1_precmz` 810.8182000219822. Assert both; together they are the cleanest possible anchor for §3.2. |
| `MgfPopulationTest` | With an empty MS1 table: `ms1scan` and all three `ms1_*` null, `rt` present as `0.0`, and **`charge` = `1` when absent — never null** (Correction C6). The `plusrise_results.json` row shape: charge counts across its 664 rows are `{1: 653, 2: 10, 3: 1}` with **zero nulls**. |
| `OperationOrderTest` | Sentinel conversion happens **after** the lookup: a row with `ms1scan == 0` gets null `ms1scan` **and** null `ms1_*`, and does not throw. |

### Renamed and folded test classes

Every name this spec required, and where its coverage actually lives. Kept as redirects rather than deleted:
the original row records what was *asked for*, and losing it is how a real gap gets tidied out of sight. Read by
`make spec-audit` check 4 (Correction **C38**).

| Spec-era name | → Real home | Note |
|---|---|---|
| `PrecursorLookupTest` | → `PrecursorLookupTest` | kept as its own class — it is the most important test in the step, including the C37 on-bound case, which is **sabotage-verified**: switching to `mzWindowExclusive` fails it with `expected: <1000.0> but was: <null>` |
| `BasePeakTest` | → `ScaninfoCollationTest` | argmax row, tie → first/lowest-m/z, single-peak scan, and that the m/z is read **at** the argmax row rather than as a separate `max(mz)` |
| `TicIsSumTest` | → `ScaninfoCollationTest` | on a fixture where sum (2500.0) and base peak (1500.0) differ, so the `scanmaxint` confusion cannot pass |
| `SentinelNullTest` | → `ScaninfoCollationTest` | the three `0`→null columns, plus `rt = 0.0` asserted **present and non-null**, plus that no fourth column converts |
| `NanNullTest` | → `ScaninfoCollationTest` + `ResultJsonTest` | ±∞ and NaN → null at collation (the reachable case is a corrupt array decoding to infinity, which the sum propagates into `tic`); and the serializer **throws** if a non-finite value ever reaches it, since `NaN` is not valid JSON |
| `OperationOrderTest` | → `ScaninfoCollationTest` | `sentinelConversionHappensAFTERTheLookupSoMs1scanZeroDoesNotThrow` |
| `MgfPopulationTest` | → `ScaninfoCollationTest` | `charge = 1` never null (C6); every `ms1_*` null; and `rt` **per row** — `micro.mgf` carries `RTINSECONDS` on 2 of 3 blocks, so it exercises both the absent→`0.0` rule and the seconds÷60 conversion. ⚠ A first draft of this test asserted `rt == 0.0` for *every* row and failed against correct code; the golden was right |
| `ResultJsonShapeTest` | → `ResultJsonTest` | the 12 keys in order for **both** levels; MS1 precursor keys **present and null**; MS1 base peaks **non-null** |
| `ResultJsonRoundTripTest` | → `ResultJsonTest` | `everyEmittedFloatParsesBackToIDENTICALBITS`, over subnormals, `1e300`, `0.1+0.2` and the golden's `rt` |
| `CollationAnchorTest` | → **`CollationAnchorIT`** | promoted to an **integration test**: it reads the real `data/small.mzML` and its committed goldens, which belongs in the `integrationTest` suite, not the unit suite ([C43](Tech_Step_INDEX.md#c43) made that a separate Gradle source set, `src/integrationTest/java`). Asserts the first golden record field by field at **both** 20 and 60 ppm |
| — | **`ResultSchemaContractTest`** *(new)* | parses [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) and asserts `ResultJson`'s key order matches. **Demonstrated to fail** when two keys are swapped in the document |

## Done when

- [x] `make test` green (and `make verify` before calling the step done).
- [x] `PrecursorLookupTest` proves **closest, not most intense**, on a fixture where they differ.
- [x] `ms1_base_peak_i` survives a tolerance miss, with a test.
- [x] The lookup calls **`mzWindow`**, not `mzWindowExclusive` (C37, §3.4), and a test asserts the on-bound peak
      is a candidate.
- [x] `rt = 0.0` is preserved and asserted non-null.
- [x] **One shape, 12 keys, same order, for both MS2DATA and MS1DATA** (C40) — with an MS1 row asserting the
      precursor keys **present and null** and `base_peak_i`/`base_peak_mz` **non-null**.
- [x] `ResultSchemaContractTest` couples `ResultJson`'s key order to [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md), and is
      **demonstrated to fail** when a key is reordered in the document.
- [x] `spec-audit` check 6 passes — every non-empty golden carries exactly the 12 keys in order — and is
      demonstrated to fail against the pre-C40 9-key golden.
- [x] Every emitted float round-trips to identical bits.
- [x] `CollationAnchorTest` reproduces the first `small.mzML` golden record exactly.
- [x] [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) is the **only** document defining the key set; no spec restates it.

### Also resolved in the pre-implementation review (C40 round)

Seven smaller findings, each verified against the code or the goldens rather than reasoned:

1. **§2's C34 note omits MS1DATA `tic`.** Measured: the MS1 golden's `69381840.0` against the true sum
   `69381842.11895752` — relative **3.05e-08**, the same `float32` accumulation. The **1e-6** tolerance must
   cover MS1DATA rows too, not only the six MS2 rows C34 cites.
2. **§4's `iNorm` sentence describes nothing.** It claimed this step is "where Step 5's NaN for an all-zero
   scan's `iNorm` gets normalized away" — but `i_norm` is **dropped** from the contract and never serialized.
3. **How collation gets `MassqlOptions` was unstated.** `QualifyingScanConsumer.accept(view, scan, ms1)` has no
   `opts` parameter, so `precursorTolPpm` must be **constructor-injected** into the collation. Do **not** widen
   the Step 9 interface to carry it.
4. **C20's "read the three off `ScanIndex`" does not say *which table*.** `retainedMs1.index().precmzOf(0)` is
   the **MS1** scan's `precmz` (`0`), not the qualifying scan's — a silent wrong answer. Use **`ScanView`** as
   the single metadata source; it is the reader's own authority and cannot be confused with the other table.
5. **`Reductions` returns sentinels on an empty scan** that §3's pseudocode does not anticipate: `max` → **NaN**,
   `argmax` → **-1**. Guard both explicitly rather than letting NaN reach the JSON.
6. **Scan-ascending order is a fixture property, not a guarantee.** All four result goldens ascend and
   PlusRise's 34,513 `SCANS=` are monotonic, but a non-monotonic `SCANS=` would break the ordering
   `QualifyingScanConsumer` promises. **Assert non-decreasing scan ids while collating** — cheap, and turns a
   silent violation into a clear failure.
7. **No test covered an all-zero-intensity mzML scan reaching collation.** It is reachable — a scan-level-only
   query passes it, since only peak-level conditions apply the implicit `> 0` floor — and yields
   `base_peak_i = 0.0`, `base_peak_mz = mz[0]`, `tic = 0.0`. Add a hand-built table case.

## References

- [`SPIKE.md`](SPIKE.md) §3 (the contract, the 7/5 split, the population table, the exact rules), §4 (boxed types;
  `ResultJson` as published contract), §6a (precursor lookup, null/sentinel, result JSON rows)
- **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) — the single definition of the contract** (in-repo; the
  oracle's [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) is superseded and now points here)
- [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) — the authority behind it
- **`massql_query.py`** — `add_precursor_intensity` at **62-116** (the precursor rules), **`:101-103`** (the
  `>=`/`<=` window — the inclusive half of C37), `:51-59` (`clean_nan`),
  `:154-159` (the `i`→`tic` rename and the dropped columns), `:163-167` (base peak via `idxmax`), `:170-179` (the
  empty-MS1 branch), `:189-191` (the three sentinel columns and the comment excluding `rt`)
- `output/small_mzml_results.json`, `output/plusrise_results.json` — the anchors
- Consumers: [Step 11](Tech_Step11.md) exposes this; [Step 12](Tech_Step12.md) diffs it against the goldens
