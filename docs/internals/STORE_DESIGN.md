# Store design

`SpectrumTable` and friends: the hand-written replacement for MassQL's pandas dataframe.

## Why this is written rather than imported

No Java dataframe library is usable here: Tablesaw pulls ~44 MB and finds its I/O registry by
classpath scanning; Arrow has split packages and needs `sun.misc.Unsafe` plus JVM flags an embedding
application does not control. So it is parallel primitive arrays plus per-scan reductions.

## Layout

One instance per MS level. All arrays have length `rowCount`; no boxing, no per-peak objects.

| Column | Type | Notes |
|---|---|---|
| `mz` | `double[]` | ascending within each scan (invariant 3) |
| `i` | `double[]` | |
| `iNorm` | `double[]` | `i / max(i in scan)`, computed at freeze |
| `iTicNorm` | `double[]` | `i / sum(i in scan)`, computed at freeze |
| `scan` | `int[]` | non-decreasing (invariant 2) |
| `rt` | `float[]` | **row-level filtering only** — see below |
| `polarity` | `byte[]` | 1 = positive, 2 = negative, 0 = unknown |
| `msLevel` | `byte` | one level per table |

### ⚠ `rt` is stored twice, and the reason matters

The per-peak column is `float`, but **every `rt` that reaches the result JSON comes from
`ScanIndex.rtOf`, an exact `double`.** The mzML golden's first record has
`rt = 0.011218333333333334`, which does not survive a float round-trip. Keep the cheap `float[]`
for row-level RT filtering; read `ScanIndex.rtOf` for anything reported.

### Scan-level metadata lives on `ScanIndex`, not as peak columns

MassQL's dataframe repeats `rt`, `polarity`, `precmz`, `ms1scan` and `charge` on every peak row
because pandas is a flat frame. All are constant within a scan, so this store keeps them once on
`ScanIndex`.

`precmz`, `ms1scan` and `charge` carry MassQL's raw **0 sentinel** here when the file recorded
nothing. The readers convert it to `null` at the `ScanView` boundary above this layer.

> Note `ms1_df` has no `precmz`/`ms1scan`/`charge` columns at all — only MS2 does. On an MS1
> table these fields are simply 0 throughout.

## Two tables per file, not one

MS1 and MS2 peaks live in separate instances, mirroring MassQL's `ms1_df` / `ms2_df` split:
`load_data()` returns exactly that pair, and the precursor lookup queries the MS1
table while collating MS2 rows.

For MGF, the MS1 side is an **empty table, not null** (`SpectrumTable.empty(1)`), which keeps
collation free of null checks. MassQL's own MGF `ms1_df` is a synthetic 1-row placeholder rather than
empty, so `massql_query.py`'s `len(ms1_df) == 0` branch never fires for MGF. An empty Java table
produces identical results regardless.

## Invariants

Enforced in `SpectrumTableBuilder`; violations throw **`MassqlException`, not `AssertionError`**, so
they are visible in a release build.

1. **All parallel arrays have identical length.** Structural; the builder appends in lockstep.
2. **`scan` is non-decreasing**, and each scan is started exactly once, which lets the index be a
   range lookup instead of a hash of lists.
3. **Within a scan, rows are sorted by ascending `mz`**, required for the binary-search windows.
   The builder verifies and sorts if needed; `sortedAnyScan()` reports whether it fired.

   > The reader-parity comparison is a SHA-256 over each peak array, which is order-sensitive, so
   > the order here must equal MassQL's *file* order. Measured across all 14 parity fixtures, zero
   > scans descend, so the sort never fires — if one ever did, the digest would fail looking like a
   > decode bug.
4. **`iNorm` / `iTicNorm` are computed at freeze**, never lazily, so the engine reads them as
   plain columns.
5. **The table is immutable after `build()`.** No setters, no exposed arrays; `scanIds()`
   returns a defensive copy. The builder is single-use.

### NaN on an all-zero scan is deliberate

A scan whose peaks are all zero divides by zero, so `iNorm` and `iTicNorm` are `NaN`; collation
maps NaN to JSON `null`. `sum` is genuinely `0.0` — the TIC of an all-zero scan really is zero.

## The m/z window — the performance-critical primitive

```java
IntRange mzWindow(int scanOrdinal, double lo, double hi)            // [lo, hi] INCLUSIVE both ends
IntRange mzWindowExclusive(int scanOrdinal, double lo, double hi)   // (lo, hi) STRICT both ends
```

Two binary searches bounded to the scan's own slice: O(log n), not O(n).

**There are two methods because MassQL has two rules:**

| Caller | Bound | Method | Source |
|---|---|---|---|
| Condition windows (`MS2PROD`, `MS2PREC`, `MS1MZ`, `MS2NL`) | **STRICT** | `mzWindowExclusive` | `msql_engine_filters.py:253` + three siblings, `>`/`<` |
| Precursor lookup | **INCLUSIVE** | `mzWindow` | `massql_query.py`'s `ms1_df["mz"] >= precmz - tol`, `>=`/`<=` |

**Do not unify them.** Each caller's parity depends on its own bound. The exclusive variant is the
same two searches with the roles swapped: `upperBound(lo)` skips every row equal to `lo`,
`lowerBound(hi)` stops before every row equal to `hi`.

Three further details, each a silent wrong answer if changed:

- **Bounds are exact in both directions.** A peak whose m/z equals `hi` to the bit **is** in the
  inclusive window and **is not** in the exclusive one. No rounding at the boundary.
- **No epsilon is applied, ever.** A "helpful" epsilon at this level would widen every
  tolerance invisibly. Bounds arrive exact from the caller, which computes them from a tolerance.
- **`Arrays.binarySearch` is NOT used.** Its behaviour on **duplicate keys is unspecified** —
  it may return any matching index — and duplicate m/z does occur in real centroided data. The
  hand-rolled `lowerBound`/`upperBound` return the true first and last positions, so a
  duplicate run at either boundary is included whole by `mzWindow` and dropped whole by
  `mzWindowExclusive`.

The returned `IntRange` is **half-open** `[start, end)`, matching Java array conventions, so
`for (int r = range.start(); r < range.end(); r++)` is the natural loop. Do not confuse that
with the inclusive *value* bounds.

## Reductions

`sum`, `max`, `min`, `first`, `count`, `argmax`, each with an optional `RowMask` variant.

**`argmax` returns a ROW INDEX, not a value**, so the caller can read a different column at that
row — which is what `base_peak_mz` needs: argmax over intensity, then read m/z.

**Ties in `argmax` resolve to the lowest row index**, matching pandas `idxmax`. Implemented with a
strict `>`; `>=` would break it.

**Empty-scan behaviour**, and it is not uniform on purpose:

| | empty scan (or fully masked out) |
|---|---|
| `sum` | `0.0` — the TIC of an empty spectrum really is zero |
| `max`, `min`, `first` | `NaN` — no value to report |
| `argmax` | `-1` |
| `count` | `0` |

Nothing throws: `scaninfo(MS1DATA)` can legitimately report a scan with no peaks.

## `RowMask`, and the `OTHERSCAN` seam

`RowMask` is `BitSet`-backed and **immutable**: `and`/`or`/`not` return new instances. Length
mismatch throws.

`scansWithAnyRow(table)` returns the ordinals of scans retaining at least one selected row, because
MassQL conditions mean *"this scan contains a peak matching X"*. The engine intersects these scan
sets: two conditions may be satisfied by **different peaks in the same scan**, which a row-level AND
would wrongly reject.

### The seam, stated as a constraint

**Filtering produces a mask; it never prunes.** No method returns a smaller table. `OTHERSCAN` will
need a second retained index over **pre-filter** MS1 data, so destructive pruning would foreclose
it.

## Measured baselines

From `StoreScaleTest` on an Apple M2:

| Operation | Scale | Time |
|---|---|---|
| Build | 990,000 peaks / 30,000 scans | **38 ms** |
| 200,000 `mzWindow` calls | over 300 scans | 18 ms |
| 200,000 `mzWindow` calls | over 30,000 scans | **13 ms** |
| 200,000 `mzWindowExclusive` calls | over 300 scans | 22 ms |
| 200,000 `mzWindowExclusive` calls | over 30,000 scans | **7 ms** |
| `sum` + `argmax` per scan | 20,000 scans / 1,000,000 peaks | **16 ms** |

Window cost is **flat** at 100× the scans, which is what a binary search looks like; a linear scan
would track table size. The tests assert the ratio rather than an absolute time.
