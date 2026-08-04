# Tech Step 10 — `scaninfo` collation, result model, JSON

## Goal

Turn qualifying scans into the exact 12-key result contract — including the five columns MassQL does **not**
produce and the SDK must compute itself — and serialize it as the published JSON that `massql-app` will store
verbatim.

## Prerequisites

| Step | Why |
|---|---|
| [Step 9](Tech_Step9.md) | Provides the qualifying scan set. |
| [Step 5](Tech_Step5.md) | Provides `argmax` (row index, ties → lowest), `mzWindow`, `Reductions`, and the exact double `scanRt`. |

## Context

**Only 7 of the 12 result keys come from MassQL. The SDK must compute the other 5** — this is the most
easily-missed part of the whole contract. `massql_query.py` computes them in Python on top of MassQL's raw output;
here they move into the SDK so they are unit-tested outside OSGi and the Phase-2 app's node-table write-back stays
a dumb loop.

| Source | Columns |
|---|---|
| MassQL `scaninfo` native | `scan`, `precmz`, `ms1scan`, `rt`, `charge`, `tic` (MassQL's `i`, renamed), `mslevel` |
| **Computed here** (`massql_query.py:62-116`, `:163-167`) | `base_peak_i`, `base_peak_mz` — per-scan argmax over MS2 peaks |
| **Computed here** | `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` — precursor lookup in the linked MS1 scan |
| **Dropped** from MassQL's raw output | `i_norm` (structurally always 1.0), `i_norm_ms1` (only null or 1.0) |

`ResultJson`'s output is a **published contract**, not an implementation detail: the app writes the string into
the node table verbatim and `MASSQL_PARSE` reads it back, so key names, key set and float formatting are all
frozen here.

Governing sections: `SPIKE.md` §3 (the contract, the population table, the exact rules), §4 (API rules), §6a
(precursor lookup, null/sentinel, result JSON rows); `RESULT_SCHEMA.md`.

## Scope

**In scope**
- `ScaninfoCollation` — the 7 native + 5 computed columns.
- The precursor lookup.
- The null / sentinel / NaN rules.
- `ScanInfoResult` (boxed types) and `ResultJson`.
- Both output shapes: 12-key MS2DATA and 4-key MS1DATA.

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
| `docs/RESULT_CONTRACT.md` | The frozen key set, ordering, formatting, and the population-by-format table |

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
    Double  ms1BasePeakI,   // null only if no linked MS1 scan
    boolean ms1DataShape    // true → serialize the 4-key form
) { }
```

All the sentinel and NaN rules live **in the SDK**, not in the consuming app — that is the design rule from
`SPIKE.md` §4, and it is why they are unit-tested here.

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
  peak there), which is why `massql_query.py:154` guards the rename to `scaninfo` queries only. We only support
  `scaninfo`, so the rename is unconditional — but record the reason in `RESULT_CONTRACT.md` so it isn't
  generalized incorrectly if another function is ever added.
- **`rt`** comes from `scanRt` at double precision. The mzML golden's `0.011218333333333334` does not survive a
  float round-trip.

### 3. The five computed columns

**`base_peak_i` / `base_peak_mz`** — per-scan argmax over that scan's MS2 peaks:

```
row = argmax(i) over the scan          // ties → lowest row index (Step 5 §6, matching pandas idxmax)
base_peak_i  = i[row]
base_peak_mz = mz[row]
```

Mirrors `massql_query.py:163-167` (`ms2_df.groupby("scan")["i"].idxmax()`). Computed from the **loaded table**,
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
        cand = MS1 peaks in that scan with mz in [precmz - tol, precmz + tol]
        if cand is non-empty:
            best      = the candidate CLOSEST IN m/z TO precmz     # <-- NOT the most intense
            ms1_i     = i[best]
            ms1_precmz = mz[best]
```

Three rules inside that, each independently testable:

1. **Closest, not most intense.** `massql_query.py:104` — `cand.iloc[(cand["mz"] - precmz).abs().argmin()]`.
   Picking the most intense peak in the window is the intuitive reading and it is wrong. `SPIKE.md` §6a calls the
   test for this *"the test that catches the most likely misreading of the whole contract."* The
   [Step 2](Tech_Step2.md) micro-fixtures were built with a window where those two differ.
2. **`ms1_base_peak_i` does not depend on the match.** It is populated whenever the linked MS1 scan exists, so a
   tolerance miss nulls **only** `ms1_i` and `ms1_precmz`. `massql_query.py:88-93` computes it before, and
   independently of, the window search.
3. **Ties in "closest".** If two candidates are equidistant from `precmz`, pandas' `argmin` returns the **first**
   occurrence — the lower m/z, given ascending sort. Match that.

`ms1_base_peak_i` is the normalization reference: relative precursor abundance = `ms1_i / ms1_base_peak_i`.

The tolerance here is `MassqlOptions.precursorTolPpm` (default **20.0**), which is a *separate knob* from the
query's own `TOLERANCEPPM`/`TOLERANCEMZ` ([Step 9](Tech_Step9.md) §3). Do not conflate them — the query tolerance
selects scans; this one matches the precursor peak within an already-selected scan.

### 4. Null, sentinel and NaN rules

**`0` → null for `precmz`, `ms1scan`, `charge` — and only those three.**

| Column | Why 0 can't be real |
|---|---|
| `precmz` | An ion m/z is always > 0 |
| `ms1scan` | Scan ids are 1-based; `0` is MassQL's "no linked MS1 scan" default ([Step 6](Tech_Step6.md) §4) |
| `charge` | A real ion charge is nonzero; `0` is the standard "unknown" |

**Never convert `rt`.** `0.0` is a genuine retention time — direct-infusion data, and every MGF row without
`RTINSECONDS`. `plusrise_results.json` has `rt: 0.0` on all 664 records, so an over-eager null conversion fails
664 rows at once. `massql_query.py:189-191` converts exactly three columns and its comment says so explicitly.

**NaN / ±infinity → null**, so the output is valid JSON. `massql_query.py:51-59` (`clean_nan`) applies this
recursively to every float. This is also where [Step 5](Tech_Step5.md)'s `NaN` for an all-zero-intensity scan's
`iNorm` gets normalized away.

Order of operations matters: **compute → sentinel-convert → NaN-convert → serialize.** Converting sentinels after
NaN handling, or before computing `ms1_base_peak_i`, changes results — the lookup needs the raw `0` to know there
is no linked scan.

### 5. `ResultJson` — the frozen contract

**MS2DATA — exactly these 12 keys, in this order:**

```
scan, precmz, ms1scan, rt, charge, tic, mslevel, base_peak_i, base_peak_mz, ms1_i, ms1_precmz, ms1_base_peak_i
```

**MS1DATA — ⚠ OPEN DECISION, resolve before implementing.** `SPIKE.md` §3 specifies 4 keys:

```
scan, rt, tic, mslevel
```

But the reference wrapper actually emits **9** (measured in Step 2, Correction C15):

```
scan, rt, mslevel, tic, base_peak_i, base_peak_mz, ms1_i, ms1_precmz, ms1_base_peak_i
```

`precmz`/`ms1scan`/`charge` **are** absent as documented — that part holds. But `massql_query.py:161-179` adds
the five computed columns unconditionally, and for MS1DATA they are all **null**, because the base peaks are
computed from `ms2_df`, which contains no MS1 scan ids. Three candidate behaviours:

1. **Match the reference (9 keys, five nulls)** — the differential passes as-is against
   `output/small_mzml_ms1_results.json`.
2. **Emit SPIKE.md's 4 keys** — cleaner contract; requires regenerating that golden.
3. **Emit 9 keys but compute `base_peak_i`/`base_peak_mz` from the MS1 scan itself** (non-null) — arguably the
   only *useful* answer, and diverges from both of the above.

Pick one deliberately and record it in `RESULT_CONTRACT.md`; do not let it be decided by whichever the code
happens to do first.

The absent-vs-null distinction is a contract difference, not a formatting choice; `MASSQL_PARSE` in Phase 2 will
distinguish them. Test both shapes explicitly.

Output is a **JSON array** of objects; an empty result is `[]`, not `null` and not an error.

Java `null` renders as JSON `null`. Do not omit null keys in the MS2DATA shape and do not substitute `0`, `""` or
`"None"`.

**Float formatting.** The differential test in [Step 12](Tech_Step12.md) compares against `massql_query.py`'s
output, which uses `json.dump(..., indent=2, allow_nan=False)` with Python's `repr` float formatting — the
shortest string that round-trips. Java's `Double.toString` is also shortest-round-trip but **differs from Python
in known cases**, notably exponent formatting (`1.0E-5` vs `1e-05`) and always emitting `.0` on integral values.

Decide and document the policy in `docs/RESULT_CONTRACT.md`, and pick **value comparison over text comparison** in
Step 12 rather than trying to byte-match Python's formatter. Emit canonical, round-trip-exact JSON numbers; do not
reformat, round or truncate. Any rounding here would corrupt the bit-identity that Steps 8 and 12 establish.

Write the serializer by hand — no Jackson/Gson. The output shape is 12 fixed keys, the dependency budget is tight
(`DEPENDENCY_POLICY.md` constraint 6), and Jackson finds modules via `ServiceLoader`, violating constraint 1.

### 6. Population by input format

The expected per-format outcome, from `SPIKE.md` §3. `RESULT_CONTRACT.md` must carry this table so
[Step 12](Tech_Step12.md) can assert against it without cross-referencing.

| Column | MGF (MS2 only) | mzML / mzXML (MS1 + MS2) |
|---|---|---|
| `scan` | ✔ | ✔ |
| `precmz` | ✔ (from `PEPMASS=`) | ✔ |
| `ms1scan` | **null** — no survey scans exist | ✔ by **document order** ([Step 6](Tech_Step6.md) §4) |
| `rt` | **`0.0`, not null** | ✔ |
| `charge` | ⚠ **never null** — `CHARGE=` if present, else **`1`** (Correction C6; `SPIKE.md` §3 wrongly says null) | ✔ if recorded, else null via the `0` sentinel. ⚠ **mzXML's absent default is `0`, not MGF's `1`** (`msql_fileloading.py:451`), and `DP00570_F02.mzxml` carries **zero** `precursorCharge` attributes — so in practice *every* row from it is null. That makes `charge` a **predicted difference** in [Step 12](Tech_Step12.md)'s Pair B, not a shared column (Correction C29) |
| `tic` | ✔ sum of MS2 fragment intensities | ✔ |
| `mslevel` | `2` | `2` |
| `base_peak_i` / `base_peak_mz` | ✔ | ✔ |
| `ms1_i` | **null** | ✔ if `ms1scan` resolved **and** a peak matches within `precursorTolPpm` |
| `ms1_precmz` | **null** | ✔ same condition — the *measured* centroid, usually a few ppm off `precmz` |
| `ms1_base_peak_i` | **null** | ✔ whenever the linked MS1 scan exists — **a tolerance miss does not null it** |

When the MS1 table is empty (MGF), all three `ms1_*` are null without any lookup —
`massql_query.py:170` branches on exactly that.

## Known traps

- **Taking the most intense peak in the precursor window instead of the closest.** The headline trap. §3.1.
- **Nulling `ms1_base_peak_i` on a tolerance miss.** It survives the miss. §3.2.
- **Null-converting `rt`.** `0.0` is real, and it is 664 rows in one golden. §4.
- **Converting `0` → null for a fourth column** because it looks empty. Exactly three.
- **Sentinel-converting before the precursor lookup runs.** The lookup needs raw `0` to detect "no linked scan".
- **Rendering MS1DATA's precursor keys as `null` instead of omitting them.** Different contract.
- **Rounding or reformatting floats** to make a text diff pass. Compare values, not text. §5.
- **Using a JSON library.** `ServiceLoader` + budget. §5.
- **`argmax` ties resolving to the last row.** Must be first, matching pandas `idxmax`
  ([Step 5](Tech_Step5.md) §6).
- **Conflating `precursorTolPpm` with the query's `TOLERANCEPPM`.** Two different knobs. §3.

## Tests required

All unit (`*Test.java`), on the [Step 2](Tech_Step2.md) micro-fixtures and hand-built tables.

| Test | Pins |
|---|---|
| **`PrecursorLookupTest`** | **The most important test in this step.** A window containing two peaks where the **closer one is less intense** → `ms1_i`/`ms1_precmz` come from the **closer** peak. Also: tolerance miss → `ms1_i`/`ms1_precmz` null but `ms1_base_peak_i` **populated**; no linked MS1 scan → all three null; equidistant tie → lower m/z wins; `precmz == 0` → no lookup. |
| `BasePeakTest` | `base_peak_i`/`base_peak_mz` from argmax; a tie resolves to the **first** (lowest-m/z) row; single-peak scan; the mz read comes from the argmax row, not from a separate max. |
| `SentinelNullTest` | `precmz`/`ms1scan`/`charge` `0` → null; **`rt` `0.0` preserved** (assert `0.0`, not null, and assert it is not `null` explicitly); no other column converted. |
| `NanNullTest` | NaN → null; `+∞`/`−∞` → null; the resulting JSON parses. |
| `ResultJsonShapeTest` | MS2DATA emits **exactly** the 12 keys in order; MS1DATA emits **exactly** 4 with precursor keys **absent** (assert `!json.has("precmz")`, not `json.get("precmz") == null`); null renders as JSON `null`; empty result → `[]`. |
| `ResultJsonRoundTripTest` | Every emitted float parses back to the **identical bits** — guards against a formatter that rounds. |
| `TicIsSumTest` | `tic` is the sum of fragment intensities, not the base peak — the distinction `massql_query.py:154` guards. |
| `CollationAnchorTest` | Build a table reproducing `small.mzML`'s scan 3 and assert the full first golden record field by field. **At the default 20 ppm** (`output/small_mzml_results.json`): `scan` 3, `precmz` 810.79, `ms1scan` 2, `rt` 0.011218333333333334, `charge` null, **`tic` 586278.875 compared at relative 1e-6** (our float64 sum gives 586278.8533592224 -- the golden is a float32 accumulation, C34), `mslevel` 2, `base_peak_i` 161140.859375, `base_peak_mz` 736.6370849609375, **`ms1_i` null, `ms1_precmz` null**, `ms1_base_peak_i` **183838.71875**. That row is itself the tolerance-miss case — the nearest MS1 peak is 34.8 ppm away, so the match fails while `ms1_base_peak_i` survives. **At 60 ppm** (`output/small_mzml_tol60_results.json`) the same row has `ms1_i` 131528.0625 and `ms1_precmz` 810.8182000219822. Assert both; together they are the cleanest possible anchor for §3.2. |
| `MgfPopulationTest` | With an empty MS1 table: `ms1scan` and all three `ms1_*` null, `rt` present as `0.0`, and **`charge` = `1` when absent — never null** (Correction C6). The `plusrise_results.json` row shape: charge counts across its 664 rows are `{1: 653, 2: 10, 3: 1}` with **zero nulls**. |
| `OperationOrderTest` | Sentinel conversion happens **after** the lookup: a row with `ms1scan == 0` gets null `ms1scan` **and** null `ms1_*`, and does not throw. |

## Done when

- [ ] `mvn test` green.
- [ ] `PrecursorLookupTest` proves **closest, not most intense**, on a fixture where they differ.
- [ ] `ms1_base_peak_i` survives a tolerance miss, with a test.
- [ ] `rt = 0.0` is preserved and asserted non-null.
- [ ] Both JSON shapes exact: 12 keys ordered for MS2DATA, 4 keys with precursor keys **absent** for MS1DATA.
- [ ] Every emitted float round-trips to identical bits.
- [ ] `CollationAnchorTest` reproduces the first `small.mzML` golden record exactly.
- [ ] `docs/RESULT_CONTRACT.md` records the frozen key sets, key order, the float-formatting policy, and the
      population-by-format table.

## References

- `SPIKE.md` §3 (the contract, the 7/5 split, the population table, the exact rules), §4 (boxed types;
  `ResultJson` as published contract), §6a (precursor lookup, null/sentinel, result JSON rows)
- `RESULT_SCHEMA.md` — the per-column prose contract
- **`massql_query.py`** — `add_precursor_intensity` at **62-116** (the precursor rules), `:51-59` (`clean_nan`),
  `:154-159` (the `i`→`tic` rename and the dropped columns), `:163-167` (base peak via `idxmax`), `:170-179` (the
  empty-MS1 branch), `:189-191` (the three sentinel columns and the comment excluding `rt`)
- `output/small_mzml_results.json`, `output/plusrise_results.json` — the anchors
- Consumers: [Step 11](Tech_Step11.md) exposes this; [Step 12](Tech_Step12.md) diffs it against the goldens
