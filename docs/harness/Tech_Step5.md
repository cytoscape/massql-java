# Tech Step 5 — Columnar store and per-scan reductions

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

## Goal

A primitive-array columnar peak store with the per-scan reductions and windowed lookups the query engine needs —
the replacement for pandas/numpy, and the single largest chunk of production code in the spike.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | Provides the build (Gradle since [C43](Tech_Step_INDEX.md#c43)), the Java 17 target, JUnit wiring, and `MassqlException`. |

## Context

MassQL's Python engine is pandas end to end: peaks are a long-format DataFrame (`scan, mz, i, i_norm,
i_tic_norm, rt, polarity`) and every query condition is a boolean mask or a `groupby` reduction. No Java
dataframe library is usable here — Tablesaw pulls ~44 MB and finds its I/O registry by classpath scanning
(violating `DEPENDENCY_POLICY.md`), and Arrow has split packages and needs `sun.misc.Unsafe` plus JVM flags we
don't control. **So we write the dataframe.** It is ordinary Java, and it is where the LOC budget goes.

Building this before the readers means the readers have somewhere to write, and the reductions get unit-tested
against hand-computable data before any file parsing is in the picture.

Governing sections: [`SPIKE.md`](SPIKE.md) §2 (why no dataframe library), §4 (the `spectra/` package), §6a (store-reduction
row).

## Scope

**In scope**
- `SpectrumTable`: the columnar store, its builder, and its accessors.
- Per-scan reductions: sum, max, min, first, argmax, count.
- Derived columns `iNorm` and `iTicNorm`.
- Scan indexing and m/z-window lookup by binary search.
- Row masks and their combination.
- A documented seam for a second retained index over pre-filter MS1 data.

**Out of scope**
- Reading any file — Steps [6](Tech_Step6.md) and [7](Tech_Step7.md) populate this store.
- Interpreting MassQL semantics — tolerance precedence, comparator defaults, the `0.99` cap and every other rule
  live in [Step 9](Tech_Step9.md). This step provides *mechanisms*, not *policy*: it answers "which rows are in
  `[lo, hi]`", never "what window does `TOLERANCEPPM=20` imply".
- The 5 computed result columns — [Step 10](Tech_Step10.md), which uses `argmax` and the window lookup from here.
- `OTHERSCAN` itself — permanently out of scope for v1; only its *seam* is in scope.

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/spectra/SpectrumTable.java` | The store |
| `src/main/java/…/massql/spectra/SpectrumTableBuilder.java` | Append-then-freeze construction |
| `src/main/java/…/massql/spectra/ScanIndex.java` | Scan-id → row-range index |
| `src/main/java/…/massql/spectra/RowMask.java` | Bitset-backed mask with AND/OR/NOT |
| `src/main/java/…/massql/spectra/Reductions.java` | Per-scan reductions |
| `src/test/java/…/spectra/*Test.java` | The test set below |
| [`STORE_DESIGN.md`](STORE_DESIGN.md) | Layout rationale, the invariants, and the `OTHERSCAN` seam. ⚠ **Moved to `docs/harness/` by Correction C41** — it is an engineering record, read by someone confirming the steps rather than by an SDK consumer. |

## Specification

### 1. Layout

Long format, one row per peak, parallel primitive arrays — no boxing, no per-peak objects:

```java
public final class SpectrumTable {
    // parallel arrays, all length == rowCount
    private final double[] mz;
    private final double[] i;
    private final double[] iNorm;        // i / max(i in scan)
    private final double[] iTicNorm;     // i / sum(i in scan)
    private final int[]    scan;         // scan id, non-decreasing (see invariants)
    private final float[]  rt;           // minutes
    private final byte[]   polarity;     // 1 = positive, 2 = negative, 0 = unknown
    private final byte      msLevel;     // this table holds exactly one MS level
    private final ScanIndex index;
}
```

> ⚠ **Correction C20: this list is incomplete.** It omits `precmz`, `ms1scan` and `charge`,
> which [Step 10](Tech_Step10.md) needs. Verified against the loader: each has **exactly one
> distinct value per scan**, so they are per-SCAN metadata (flattened per-peak only because
> pandas is a flat frame) and they live on `ScanIndex` alongside the exact `rt`. They carry
> MassQL's raw **0 sentinel**; the 0-to-null conversion stays in Step 10.

`rt` is `float` and `polarity`/`msLevel` are `byte` per [`SPIKE.md`](SPIKE.md) §4. Note the consequence and record it in
[`STORE_DESIGN.md`](STORE_DESIGN.md): **`rt` is stored at float precision but the result contract reports it as a double.** The
mzML golden carries `rt` = `0.011218333333333334`, which does not survive a float round-trip. Therefore:

> **`rt` must be carried per *scan* at full double precision, not per *peak* at float precision.**

Keep the `float[] rt` peak column for cheap row-level RT filtering, and additionally store an exact
`double[] scanRt` on the scan index (§3), which is what [Step 10](Tech_Step10.md) reports. Any RT value that
reaches the result JSON comes from `scanRt`. This is a real, easily-missed divergence from a literal reading of
[`SPIKE.md`](SPIKE.md) §4 — flag it in [`STORE_DESIGN.md`](STORE_DESIGN.md).

> ✅ **Correction C22 — lifetime, not layout.** Execution is streaming: a table normally holds **one
> scan**, built per scan as the cursor advances, and the whole-file table is never materialised. **No
> code change was needed here** and all 44 tests survive unchanged — every invariant holds for a
> single-scan table. This makes the `OTHERSCAN` seam below *more* apt, not less: the seam is exactly
> the ability to retain pre-filter MS1 data, which streaming already does for one scan.

**Two tables per file, not one.** MS1 and MS2 peaks live in separate `SpectrumTable` instances, mirroring
MassQL's `ms1_df` / `ms2_df` split (`msql_fileloading.load_data()` returns exactly that pair, and
`massql_query.py` feeds both). [Step 10](Tech_Step10.md)'s precursor lookup queries the MS1 table while
collating MS2 rows.

### 2. Invariants

State these in [`STORE_DESIGN.md`](STORE_DESIGN.md) and assert them in `SpectrumTableBuilder.build()`:

1. All parallel arrays have identical length.
2. `scan` is **non-decreasing** — rows are grouped by scan, in the order encountered while streaming the file.
   This is what makes the scan index a range lookup rather than a hash of lists, and it costs nothing because
   readers stream in document order anyway.
3. Within a scan, rows are sorted by **ascending `mz`**. Required for binary-search windows (§4). If a source
   file is not m/z-sorted within a spectrum, the builder sorts that scan's slice — do not assume sortedness,
   verify it, and sort if needed. Record whether any real fixture actually needed sorting.
4. `iNorm` and `iTicNorm` are computed at freeze time (§5), never lazily — the engine reads them as plain
   columns.
5. The table is **immutable after `build()`**. No setters, no exposed arrays. Accessors return values or copies.

Violations throw `MassqlException`, not `AssertionError` — they indicate a reader bug and must be visible in a
release build.

### 3. Scan index

```java
public final class ScanIndex {
    public int    scanCount();
    public int[]  scanIds();                 // ascending, distinct
    public int    rowStart(int scanOrdinal);  // inclusive
    public int    rowEnd(int scanOrdinal);    // exclusive
    public int    ordinalOf(int scanId);      // -1 if absent
    public double rtOf(int scanOrdinal);      // exact, double precision — see §1
    public byte   polarityOf(int scanOrdinal);
}
```

Built once at freeze time by walking the non-decreasing `scan` column: `O(rowCount)`, no map allocation.
`ordinalOf` is `Arrays.binarySearch` over `scanIds()`.

Scan ids in these files are not guaranteed dense or 1-based, so **never use a scan id as an array index.** Go
through `ordinalOf`.

### 4. m/z window lookup — the performance-critical primitive

```java
/** Row range within one scan whose mz lies in [lo, hi], both INCLUSIVE. */
public IntRange mzWindow(int scanOrdinal, double lo, double hi);

/** Row range whose mz lies in (lo, hi), both STRICT — added by Correction C37. */
public IntRange mzWindowExclusive(int scanOrdinal, double lo, double hi);
```

> ⛔ **Correction C37 — there are TWO window methods, and the choice is load-bearing.** This section described
> one inclusive method and attributed it to [Step 9](Tech_Step9.md): *"Step 9 is responsible for computing
> `lo`/`hi` from a tolerance."* **That attribution is backwards.** MassQL genuinely differs by caller, and both
> halves were verified by execution rather than inferred:
>
> | Caller | Bound | Method | Evidence |
> |---|---|---|---|
> | [Step 9](Tech_Step9.md) condition windows | **STRICT** | `mzWindowExclusive` | `msql_engine_filters.py:253` and three siblings use `>`/`<`. `micro.mzML` scan 3 has a peak at exactly `201.0`; `MS2PROD=201.5:TOLERANCEMZ=0.5` gives the window `[201.0, 202.0]` and MassQL returns **0 rows** |
> | [Step 10](Tech_Step10.md) precursor lookup | **INCLUSIVE** | `mzWindow` | `massql_query.py`'s `ms1_df["mz"] >= precmz - tol` uses `>=`/`<=`. At `--precursor-tol-ppm 7.8125` an exactly-on-bound peak **does** populate `ms1_i` (`1000.0`) |
>
> **Do not unify them.** Collapsing to one rule would fix one divergence by creating another in
> `ms1_i`/`ms1_precmz` — the columns [Step 12](Tech_Step12.md) compares at **1e-9**. `MzWindowTest` asserts both
> in one class so the difference stays visible.

Implementation: two binary searches bounded to `[rowStart, rowEnd)` of that scan. Because
`Arrays.binarySearch`'s behaviour on duplicate keys is unspecified, hand-roll `lowerBound`/`upperBound` rather
than using its return value — duplicate m/z values do occur in real centroided data. The exclusive variant is
the same two searches with the roles swapped (`upperBound(lo)` skips every row equal to `lo`,
`lowerBound(hi)` stops before every row equal to `hi`), which is exact and stays correct with duplicates.

**Neither method applies an epsilon, ever.** The caller computes `lo`/`hi` from a tolerance; a helpful epsilon
here would silently widen every tolerance in the system. Edge behaviour is exact in both: a peak whose m/z
equals `hi` to the bit **is** in the inclusive window and **is not** in the exclusive one.

[`SPIKE.md`](SPIKE.md) §7 Step 2's performance note applies to this method specifically: if the MGF fixture is slower than
pandas, the likely cause is a linear scan where this binary search belongs.

### 5. Derived columns

Computed once, at freeze:

- `iNorm[r] = i[r] / max(i in scan(r))`
- `iTicNorm[r] = i[r] / sum(i in scan(r))`

Edge cases, and each needs a test:
- **Empty scan** (zero rows): no rows to divide, nothing to do — but the scan must still appear in the index
  with `rowStart == rowEnd`, because `scaninfo(MS1DATA)` can report a scan with no peaks and
  [Step 10](Tech_Step10.md) needs its `rt`/`tic`.
- **Scan whose max intensity is 0** (all-zero peaks): division by zero. Produce `Double.NaN`, do **not** throw
  and do **not** substitute 0. [Step 10](Tech_Step10.md) converts NaN → null on output, so NaN is the correct
  in-band representation of "undefined". Record this in [`STORE_DESIGN.md`](STORE_DESIGN.md).
- **Single-peak scan:** `iNorm` is exactly `1.0` and `iTicNorm` is exactly `1.0`. Assert exact equality, not
  approximate — this is why `i_norm` is "structurally always 1.0" for base-peak queries.

Compute the max and sum in a single pass per scan.

### 6. Reductions

```java
public final class Reductions {
    public static double sum(SpectrumTable t, int scanOrdinal, Column c);
    public static double max(SpectrumTable t, int scanOrdinal, Column c);
    public static double min(SpectrumTable t, int scanOrdinal, Column c);
    public static int    argmax(SpectrumTable t, int scanOrdinal, Column c);  // row index, -1 if empty
    public static int    count(SpectrumTable t, int scanOrdinal);
    public static double first(SpectrumTable t, int scanOrdinal, Column c);
}
```

Rules that matter downstream:
- **`argmax` returns a row index, not a value**, so the caller can read a *different* column at that row. That
  is exactly what `base_peak_mz` needs ([Step 10](Tech_Step10.md)): argmax over intensity, then read m/z.
- **Ties in `argmax` resolve to the lowest row index** (i.e. lowest m/z, given invariant 3). Pandas'
  `idxmax` does the same — it returns the first occurrence — and `massql_query.py`'s `groupby("scan")["i"].idxmax()` uses `idxmax`. Pin this
  with a test on a deliberate tie; a last-wins implementation would disagree with the goldens on any spectrum
  with two equal-intensity peaks.
- **Empty scan:** `sum` = `0.0`; `max`/`min`/`first` = `Double.NaN`; `argmax` = `-1`; `count` = `0`. Never throw.
  `sum` of an empty scan being `0.0` rather than NaN is deliberate — it is the `tic` of an empty spectrum.

Also provide masked variants, `sum(t, scanOrdinal, c, RowMask m)` etc., since [Step 9](Tech_Step9.md) reduces
over filtered rows.

### 7. Row masks

```java
public final class RowMask {
    public static RowMask all(int rowCount);
    public static RowMask none(int rowCount);
    public RowMask and(RowMask other);      // returns a new mask
    public RowMask or(RowMask other);
    public RowMask not();
    public boolean get(int row);
    public int cardinality();
}
```

`java.util.BitSet`-backed. Immutable — `and`/`or`/`not` return new instances rather than mutating, because
[Step 9](Tech_Step9.md) composes conditions and reusing a mutated mask is a subtle wrong-answer bug. Length
mismatch throws `MassqlException`.

Also useful, and cheap: `scansWithAnyRow(RowMask)` → the set of scan ordinals retaining at least one row, since
most MassQL conditions are "this scan contains a peak matching X" rather than a row-level predicate.

### 8. The `OTHERSCAN` seam

`OTHERSCAN` is out of scope for v1 but needs a **second retained index over pre-filter MS1 data**. Retrofitting
that later is expensive; anticipating it is free.

Concretely: `SpectraFile` ([Step 6](Tech_Step6.md)) must be able to hand out the **unfiltered** MS1 table
independently of whatever filtering a query applies, and nothing in this store may assume it holds post-filter
data. Practically that means:
- filtering produces a `RowMask`, never a new pruned `SpectrumTable`;
- the store has no "current filter" state.

Document this in [`STORE_DESIGN.md`](STORE_DESIGN.md) as an explicit design constraint with the reason, so a future contributor
doesn't "optimize" filtering into destructive pruning.

## Known traps

- **Storing `rt` only as `float`.** The golden `rt` `0.011218333333333334` does not survive a float round-trip,
  so a float-only design fails [Step 12](Tech_Step12.md)'s differential with a tiny, confusing delta. See §1.
- **Using a scan id as an array index.** Scan ids are neither dense nor guaranteed 1-based.
- **Trusting `Arrays.binarySearch` on duplicate keys.** Its choice among equal elements is unspecified; walk
  outward from the hit.
- **An epsilon inside either window method.** Widens every tolerance in the system invisibly. Bounds arrive exact.
- **Unifying `mzWindow` and `mzWindowExclusive`** because two near-identical methods look redundant. They are
  not (Correction C37): dropping either one silently breaks the other's caller. See §4.
- **`argmax` ties resolving to the last index.** Disagrees with pandas `idxmax` and therefore with the goldens.
- **Mutating a `RowMask` in place** while composing conditions.
- **Substituting 0 for NaN** in `iNorm` on an all-zero scan. NaN is the correct "undefined"; Step 10 maps it to
  JSON null.

## Tests required

All unit (`*Test.java`), all on hand-constructed tables — this step needs **no** fixture files, which is why it
can run before the readers exist.

| Test | Pins |
|---|---|
| `SpectrumTableBuilderTest` | Invariant enforcement: array-length mismatch, non-monotonic `scan`, unsorted m/z within a scan (auto-sorted), immutability after `build()`. |
| `ScanIndexTest` | `rowStart`/`rowEnd` ranges; `ordinalOf` for present and absent ids; sparse and non-1-based scan ids; exact double `rtOf`. |
| `MzWindowTest` | **Both methods, in one class, so the C37 difference stays visible.** Inclusive: peak exactly at `lo` and exactly at `hi` included; one ULP outside excluded; **duplicate m/z** at a boundary all included; empty result; whole-scan window; single-peak scan; window scoped to its own scan. Exclusive: a peak on **either** bound rejected; strictly-interior peaks kept; empty when only the bounds would match; **duplicate m/z on a bound** — every copy dropped, which is the case a `binarySearch` return value would get wrong. |
| `ReductionsTest` | sum/max/min/first/count/argmax on multi-peak, single-peak and **empty** scans; **argmax tie → lowest row index**; `argmax` used to read a different column; masked variants. |
| `DerivedColumnsTest` | `iNorm`/`iTicNorm` on a known scan computed by hand; single-peak scan gives **exactly** `1.0` for both; all-zero scan gives `NaN`, no throw. |
| `RowMaskTest` | and/or/not; immutability of operands; length-mismatch throws; `cardinality`; `scansWithAnyRow`. |
| `StoreScaleTest` | Build ~1M rows across ~30k scans; assert **both** window methods are not doing linear work — e.g. total time for 100k random windows stays within a generous bound. Guards the [`SPIKE.md`](SPIKE.md) §7 performance criterion at the unit level, where the cause is obvious. |

## Done when

- [x] `mvn test` green — **44 store tests** (7 window, 8 reductions, 5 derived, 6 index, 7 builder, 7 mask,
      4 scale), 227 in the suite overall.
- [x] [`STORE_DESIGN.md`](STORE_DESIGN.md) documents the layout, all five invariants, the **`rt` double-precision
      decision**, the NaN-on-zero-max decision, the argmax tie rule, the no-epsilon/no-`Arrays.binarySearch`
      window rules, and the `OTHERSCAN` seam stated as a constraint.
- [x] No public method exposes a mutable internal array — `ScanIndex.scanIds()` returns a clone, asserted by
      `ScanIndexTest.internalArraysDoNotEscape`.
- [x] `StoreScaleTest` passes; measured baselines recorded in [`STORE_DESIGN.md`](STORE_DESIGN.md). Window cost is **flat** as the
      table grows 100x (10 ms over 30k scans vs 18 ms over 300), which is what a binary search looks like.

**✅ STEP 5 COMPLETE — 2026-07-30.** See Correction **C20** in
[`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) and [`STORE_DESIGN.md`](STORE_DESIGN.md).

## References

- [`SPIKE.md`](SPIKE.md) §2 (why the dataframe is written, not imported), §4 (`spectra/` sketch), §6a (store reductions),
  §7 Step 2 item 1 (order and the `OTHERSCAN` seam), §8 (`OTHERSCAN` out of scope)
- `massql_query.py`'s `groupby("scan")["i"].idxmax()` — `ms2_df.groupby("scan")["i"].idxmax()`, the pandas behaviour `argmax` must match
- `msql_engine_filters.py:253` — the strict `>`/`<` bounds behind `mzWindowExclusive` (Correction C37)
- Consumers: [Step 6](Tech_Step6.md) and [Step 7](Tech_Step7.md) populate it; [Step 9](Tech_Step9.md) masks and
  reduces via **`mzWindowExclusive`**; [Step 10](Tech_Step10.md) uses `argmax` and the **inclusive** `mzWindow`
