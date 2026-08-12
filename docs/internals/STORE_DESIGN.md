# Store design

`SpectrumTable` and friends: the hand-written replacement for MassQL's pandas dataframe.

## Why this is written rather than imported

No Java dataframe library is usable here. Tablesaw pulls ~44 MB and finds its I/O registry by
**classpath scanning**, which this project does not permit. Arrow has split packages and needs
`sun.misc.Unsafe` plus JVM flags an embedding application does not control. So the dataframe is
hand-written: parallel primitive arrays plus per-scan reductions. It is ordinary Java, and it is the
bulk of the production LOC.

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

The per-peak column is `float`. But **every `rt` that reaches the result JSON
comes from `ScanIndex.rtOf`, which is an exact `double`.**

The mzML golden's first record has `rt = 0.011218333333333334`. That value does **not** survive
a float round-trip — `ScanIndexTest` asserts exactly that, so the requirement is pinned rather
than remembered. A float-only design would fail the differential with a tiny,
confusing delta that looks like a reader bug.

So: keep the cheap `float[]` for row-level RT filtering, and read `ScanIndex.rtOf` for anything
that is reported.

### Scan-level metadata lives on `ScanIndex`, not as peak columns

MassQL's dataframe repeats `rt`, `polarity`, `precmz`, `ms1scan` and `charge` on every peak row
because pandas is a flat frame. **Verified against the loader: all of them are constant within
a scan** — exactly one distinct value each. Storing them once per scan is both semantically
right and much smaller; a 20,000-peak MS1 scan would otherwise carry 20,000 copies of its
retention time.

`precmz`, `ms1scan` and `charge` carry MassQL's raw **0 sentinel** when the file recorded
nothing. **The 0-to-null conversion belongs to collation, not this layer** — converting early
would destroy the distinction between "absent" and "already converted", and `ms1scan == 0` is
specifically what the precursor lookup checks for.

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

Enforced in `SpectrumTableBuilder`, and violations throw **`MassqlException`, not
`AssertionError`** — they indicate a reader bug and must be visible in a release build, not
only when assertions happen to be enabled.

1. **All parallel arrays have identical length.** Structural; the builder appends in lockstep.
2. **`scan` is non-decreasing**, and each scan is started exactly once. This is what lets the
   index be a range lookup instead of a hash of lists, and it costs nothing because readers
   stream in document order anyway.
3. **Within a scan, rows are sorted by ascending `mz`.** Required for the binary-search
   windows. The builder **verifies and sorts if needed** rather than assuming — a reader for a
   nonconforming file would otherwise produce silently wrong window results.
   `sortedAnyScan()` reports whether any sorting was necessary; record it if a real fixture
   ever trips it.

   > ⚠ **This sort is load-bearing for the parity gate, in a non-obvious way.**
   > `ReaderParityIT` compares **SHA-256 over each peak array**, which is order-sensitive — so the
   > array order here must equal MassQL's *file* order. If a fixture ever arrives with descending
   > m/z, this sort silently reorders it and the digest fails, looking exactly like a decode bug.
   > Measured across all 14 parity fixtures: **zero scans descend**, so the sort never fires.
   > `PeakOrderPreconditionTest` asserts that from both sides — this store's order *and* MassQL's own,
   > via the dumps' `mz_hex_first8` — and fails with the right explanation if it ever changes.
   > Checking only one side would be circular, since the builder sorts.
4. **`iNorm` / `iTicNorm` are computed at freeze**, never lazily, so the engine reads them as
   plain columns.
5. **The table is immutable after `build()`.** No setters, no exposed arrays; `scanIds()`
   returns a defensive copy. The builder is single-use.

### NaN on an all-zero scan is deliberate

A scan whose peaks are all zero divides by zero, so `iNorm` and `iTicNorm` are `NaN`. That is
the correct in-band "undefined", and collation maps NaN to JSON `null`. Substituting `0`
would report a real value where there is none; substituting `1` would claim every peak is the
base peak. Meanwhile `sum` is genuinely `0.0` — the TIC of an all-zero scan really is zero.

## The m/z window — the performance-critical primitive

```java
IntRange mzWindow(int scanOrdinal, double lo, double hi)            // [lo, hi] INCLUSIVE both ends
IntRange mzWindowExclusive(int scanOrdinal, double lo, double hi)   // (lo, hi) STRICT both ends
```

Two binary searches bounded to the scan's own slice: O(log n), not O(n). If the MGF fixture is
ever slower than pandas, this is the first place to look: something quadratic, probably a linear scan
where a binary search belongs.

**There are two methods because MassQL genuinely has two rules.** The split, both halves verified by
execution:

| Caller | Bound | Method | Source |
|---|---|---|---|
| Condition windows (`MS2PROD`, `MS2PREC`, `MS1MZ`, `MS2NL`) | **STRICT** | `mzWindowExclusive` | `msql_engine_filters.py:253` + three siblings, `>`/`<` |
| Precursor lookup | **INCLUSIVE** | `mzWindow` | `massql_query.py`'s `ms1_df["mz"] >= precmz - tol`, `>=`/`<=` |

**Do not unify them to remove the apparent duplication.** Each caller's parity depends on
getting its own bound, so collapsing to one rule trades one silent divergence for another. The
exclusive variant is the same two searches with the roles swapped — `upperBound(lo)` skips every
row equal to `lo`, `lowerBound(hi)` stops before every row equal to `hi` — so it is exact and
stays correct across duplicate m/z.

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

**`argmax` returns a ROW INDEX, not a value**, so the caller can read a *different* column at
that row. That is exactly what `base_peak_mz` needs: argmax over intensity, then read m/z
A value-returning `max` cannot express it.

**Ties in `argmax` resolve to the lowest row index** — i.e. the lowest m/z, given invariant 3.
This matches pandas `idxmax`, which returns the first occurrence, and `massql_query.py`'s `groupby("scan")["i"].idxmax()`
uses `idxmax`. A last-wins implementation would disagree with the goldens on any spectrum
containing two equal-intensity peaks. Implemented with a strict `>` comparison; `>=` would
break it.

**Empty-scan behaviour**, and it is not uniform on purpose:

| | empty scan (or fully masked out) |
|---|---|
| `sum` | `0.0` — the TIC of an empty spectrum really is zero |
| `max`, `min`, `first` | `NaN` — no value to report |
| `argmax` | `-1` |
| `count` | `0` |

Nothing throws: `scaninfo(MS1DATA)` can legitimately report a scan with no peaks, and
Collation still needs its `rt` and `tic`.

## `RowMask`, and the `OTHERSCAN` seam

`RowMask` is `BitSet`-backed and **immutable**: `and`/`or`/`not` return new instances. The engine
composes several conditions, and a mask mutated in place while another condition holds a
reference is a wrong-answer bug with no exception to point at it. Length mismatch throws.

`scansWithAnyRow(table)` returns the ordinals of scans retaining at least one selected row.
This is the shape most MassQL conditions actually need — they mean *"this scan contains a peak
matching X"*, not *"this row matches X"*. The engine intersects these scan sets, because two
conditions may be satisfied by **different peaks in the same scan** and a row-level AND would
wrongly reject it.

### The seam, stated as a constraint

**Filtering produces a mask; it never prunes.** This class has no "current filter" state, and no
method returns a smaller table.

That is what `OTHERSCAN` will need later: a second retained index over **pre-filter** MS1 data.
It is free to preserve now and expensive to retrofit. **Do not "optimise" filtering into
destructive pruning** — it would look like a clean win and would quietly foreclose that
feature.

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

The window figures are the meaningful ones: cost is **flat** — in fact slightly *faster* at 100×
the scans, since the per-scan slice is unchanged and the warmed JIT dominates — which is what a
binary search looks like. A linear scan would track table size. The tests assert the shape (ratio
bounded) rather than an absolute time, so they do not flake on a busy machine.

Both methods are timed separately. `mzWindowExclusive` is the busier of the two in practice —
The engine calls it per condition per scan, while `mzWindow` runs once per *qualifying* scan in the
precursor lookup — so timing only the inclusive one would guard the cooler path.
