# Differential report — integration layers 2–4 (Tech_Step12, the gate)

**Verdict: GREEN.** The SDK reproduces MassQL's own Python implementation on **717 rows across 16
fixture/golden pairs** in all three formats. **11 of the 12 result columns are bit-identical**; the sole
divergence is `tic`, worst case **4.700e-8 relative**, and that error is in the *reference* rather than in
us — MassQL's intensity column is `float32` and `tic` is a pandas sum over it.

No tolerance was loosened to reach green. The `ms1_precmz` float32 allowance (C11) exists for the
*cross-format* comparison and is **not exercised by the differential at all**, where every `ms1_precmz`
matches bit-for-bit.

**Java is 6.8× faster than the pandas path and uses 2.3× less memory**, measured process-to-process on one
host: 0.42 s / 288.5 MB RSS against 2.84 s / 669.8 MB, both returning the same 664 rows from
`PlusRise.mgf` (34,513 spectra).

---

## Scope, and what green here does and does not mean

Layer 1 — that the three **readers** decode bit-identically — is [`PARITY_REPORT.md`](PARITY_REPORT.md).
This report covers the whole **pipeline**: parse, filter, collate, precursor lookup, render.

Keeping them apart is what makes a failure attributable. A decode error surfaces at Step 8 as a one-line
fix; a filtering or collation error surfaces here. A green differential on top of a green parity gate says
the divergence is neither in the bytes nor in the query semantics.

⚠ What this does **not** establish: that MassQL is correct. Every golden is MassQL's own output, so a bug
shared by both implementations agrees with itself. That blind spot is the reason layer 3 exists — see
Pair A below, which compares two of *our* readers against each other with no Python in the loop.

## Layer 2 — the differential

All 16 pairs pass, per column. Row counts are asserted against the spec rather than derived from the
golden, so a golden regenerated to zero rows cannot agree with an engine that also returns none.

| Fixture | Query | Rows | Result |
|---|---|---:|---|
| `data/small.mzML` | `test_mzml` | 6/6 | ✅ |
| `data/small.mzML` | `test_mzml` @60 ppm | 6/6 | ✅ |
| `data/small.mzXML` | `test_mzml` | 6/6 | ✅ |
| `data/small.mzXML` | `test_mzml` @60 ppm | 6/6 | ✅ |
| `data/small.mzML` | `test_ms1` (MS1DATA) | 14/14 | ✅ |
| `data/PlusRise.mgf` | `test` | 664/664 | ✅ |
| `data/DP00570_F02.mzxml` | `test_dp00570` | 3/3 | ✅ |
| `data/DP00570_F02.mgf` | `test_dp00570` | 2/2 | ✅ |
| `data/DP00570_F02.mzxml` | `test` | 0/0 | ✅ deliberate empty |
| `fixtures/micro/micro.mgf` | `test_micro` | 2/2 | ✅ |
| `fixtures/micro/micro.mzML` | `test_micro` | 2/2 | ✅ |
| `fixtures/micro/micro.mzXML` | `test_micro` | 2/2 | ✅ |
| `fixtures/micro/micro_rtseconds.mzML` | `test_micro` | 2/2 | ✅ |
| `fixtures/micro/micro.mzML` | `test_micro_edge` | 0/0 | ✅ C37 strict half |
| `fixtures/micro/micro_ms1var.mzML` | `test_micro_ms1var` | 1/1 | ✅ |
| `fixtures/micro/micro_onbound.mzML` | `test_micro_onbound` | 1/1 | ✅ C37 inclusive half |

**Two of those rows are empty by design and are assertions, not gaps.** `dp00570_mzxml_empty` is the
metabolomics query against a proteomics file; `micro_mzml_edge` puts a condition window's bound exactly on
a peak that MassQL excludes. Both are asserted explicitly so `[]` can never read as "not checked".

### Per-column results — measured across all 717 rows

| Column | Policy | Worst observed | Where |
|---|---|---|---|
| `scan` | exact | **bit-identical** | |
| `precmz` | rel 1e-9 | **bit-identical** | |
| `ms1scan` | exact | **bit-identical** | |
| `rt` | bit-identical | **bit-identical** | |
| `charge` | exact | **bit-identical** | |
| **`tic`** | rel 1e-6 | **4.700e-8** | `dp00570_mzxml` scan 556 |
| `mslevel` | exact | **bit-identical** | |
| `base_peak_i` | bit-identical | **bit-identical** | |
| `base_peak_mz` | rel 1e-9 | **bit-identical** | |
| `ms1_i` | bit-identical | **bit-identical** | |
| `ms1_precmz` | rel 1e-9 (1e-7 on 32-bit mzXML) | **bit-identical** | allowance unused here |
| `ms1_base_peak_i` | bit-identical | **bit-identical** | |

Null-vs-value is exact on every column, and no null mismatch occurred anywhere.

### Adopted tolerances, and why each is justified

**`tic` at relative 1e-6 — the only tolerance the differential exercises.** MassQL's intensity column is
`float32` and `tic` is a `pandas` sum over it, so the *reference* carries accumulation error our float64
sum does not (Correction C34). The measured worst case across all 16 pairs is **4.700e-8**, more than an
order of magnitude inside the tolerance. ⚠ This is deliberately **not** extended to the other intensity
columns: `base_peak_i`, `ms1_i` and `ms1_base_peak_i` are *selected* maxima and lookups with no
accumulation, so C34 does not reach them and they are held bit-identical. That split is the difference
between a justified tolerance and a blanket loosening.

**`ms1_precmz` at 1e-7 on a 32-bit mzXML — declared, and unused at this layer.** mzXML's single
`precision="32"` truncates the *measured* MS1 centroid (Correction C11). Within a pair both sides come
through the same format, so the differential sees no difference at all. The allowance earns its place in
layer 3, where the mzML and mzXML readings of the same spectrum are compared directly.

## Layer 3 — cross-format equivalence

**Pair A, `small.mzML` vs `small.mzXML`: identical rows, no Python in the loop.** Eleven columns are
bit-identical between the two readers; `ms1_precmz` differs by at most **2.929e-8**, exactly the C11
float32 truncation. Run at both 60 ppm and the default 20 ppm — at 60 ppm all six rows populate
`ms1_i`/`ms1_precmz`, which is what stops the comparison being null-against-null on four of six rows.

⚠ Not degraded. [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) records **34** `precursorScanNum`
attributes in `small.mzXML`, one per MS2 spectrum, so `ms1scan` is populated on both sides and the
comparison covers every column. This is asserted positively, so a future conversion that dropped the
attribute would fail here and name the cause rather than surfacing as an unexplained null.

**Pair B, `DP00570_F02.mzxml` vs `DP00570_F02.mgf`: differences exactly as predicted.**

**Intersection size: 0.** The scan ids are disjoint — mzXML matches `{2, 556, 871}`, MGF matches
`{370, 598}` — because the MGF has no `SCANS=` and MassQL numbers its blocks by index (Correction C13).
**No join is attempted**; the assertion is the population pattern per file.

| Column | mzXML | MGF |
|---|---|---|
| `precmz`, `rt`, `tic`, `mslevel`, `base_peak_i`, `base_peak_mz` | populated | populated |
| `ms1scan` | **populated on every row**, by document order | **null on every row** |
| `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` | **populated on every row** | **null on every row** |
| `charge` | **null on every row** | **never null** (2 on both matched rows) |

The mzXML has **zero** `precursorScanNum` attributes, so a populated `ms1scan` is only possible under Step
7's document-order rule — this is that rule observed end to end.

⚠ `charge` is a **predicted difference, not a shared column** (Correction C29). The mzXML carries no
`precursorCharge`, so every raw charge is 0 → null; the MGF carries real charge data and an absent
`CHARGE=` becomes **1**, not 0 (C6), so MGF charge is never null. A test listing `charge` among the columns
expected to agree would fail for an entirely correct reason.

## Layer 4 — the CLI contract

Established by forking the assembled uber-jar as a real process, because passing two
`ByteArrayOutputStream`s proves only that the code writes to the right *parameter*, not that a real process
keeps two real file descriptors apart.

| Property | Result |
|---|---|
| Exit 0 on success; `[]` + exit 0 when nothing matches | ✅ |
| `--precursor-tol-ppm` honoured in both directions (4/6 miss at 20 ppm, 0/6 at 60) | ✅ |
| **Tight tolerance (0.001 ppm): all `ms1_i` null, every `ms1_base_peak_i` survives** | ✅ |
| Omitting the flag is byte-identical to `--precursor-tol-ppm 20` | ✅ |
| stdout is a valid JSON array, one trailing newline, no diagnostics | ✅ |
| `--output FILE` leaves stdout **completely empty** | ✅ |
| Piped and `--output` modes agree **byte-for-byte** | ✅ |
| Missing file → exit 2 naming the path, stdout untouched | ✅ |
| Unsupported query → exit 2 naming the construct (`scansum`) | ✅ |

The tight-tolerance case is the only place the *`ms1_base_peak_i` survives a lookup miss* rule is
observable from outside the SDK. The byte-for-byte agreement between modes is what makes every file-based
assertion a valid statement about the piped payload.

## Error paths and resource behaviour

Every case from Tech_Step12 §4, across all three formats. Truncating a fixture to 60% of its bytes throws
a `MassqlException` in all three, with **no partial results** — a reader returning the rows it managed
before the damage would hand the caller a plausible short answer, which is the C44 failure shape.

⚠ One documented leniency, asserted so it stays a decision: text containing **no** `<` is treated as a peak
list (Step 6's sniffing rule), so an unmarked text file yields **zero rows rather than an error**. Markup
whose root is neither mzML nor mzXML *does* fail and names the file.

**Handle leak: none.** 250 open/close cycles per format, plus 750 interleaved and 250 on `PlusRise.mgf`,
with no descriptor growth; `close()` is idempotent, and opening without reading releases just as cleanly.
Phase 2's `shutDown()` depends on this directly.

## Performance

Captured programmatically into `build/reports/performance/measurements.txt` — a host spec typed into a
review document by hand is a number that is wrong later.

```
availableProcessors : 8
maxMemory (heap)    : 0.5 GB          <- the Gradle test JVM default, not the machine's RAM
os                  : Mac OS X 26.5.2 aarch64
jvm                 : OpenJDK 64-Bit Server VM 17.0.18
```

| Fixture | Rows | Wall-clock | Peak heap |
|---|---:|---:|---:|
| `PlusRise.mgf` (34,513 spectra) | 664 | 324 ms | 127.0 MB |
| `DP00570_F02.mgf` | 2 | 169 ms | 91.8 MB |
| `small.mzML` | 6 | 155 ms | 28.3 MB |
| `DP00570_F02.mzxml` | 3 | 65 ms | 19.9 MB |
| `small.mzXML` | 6 | 57 ms | 21.8 MB |

In-process and single-run, so these include JIT warm-up. ⚠ Peak heap of 127 MB sits against a **0.5 GB**
ceiling — that is the test JVM's default, not a measured requirement, but it is thinner headroom than the
numbers alone suggest and is worth sizing deliberately for the Cytoscape app.

**vs the pandas path — same host, same file, same query, same 664 rows.** Both measured under
`/usr/bin/time -l`, process to process, so JVM startup and interpreter startup are both included:

| | Wall-clock | Max RSS |
|---|---:|---:|
| `massql_query.py` (pandas) | 2.84 s | 669.8 MB |
| `massql-java-cli` uber-jar | **0.42 s** | **288.5 MB** |
| | **6.8× faster** | **2.3× less** |

Nothing is quadratic. [`SPIKE.md`](SPIKE.md) §7 flagged the MGF as the fixture where a linear scan standing
in for a binary search would show; it does not.

## The questions this step answers

> **§11 Q2 — does the same query return the same rows on `small.mzML` and `small.mzXML`?**
>
> **Yes** — 6 rows on both, identical on eleven of twelve columns, with `ms1_precmz` differing by at most
> 2.929e-8 because mzXML stores the MS1 array at `precision="32"`.

> **§11 Q6 — measured LOC; does the 1,200–1,800 estimate hold?**
>
> **No — hand-written production code is 3,105 non-blank non-comment lines** (2,913 SDK + 192 CLI,
> excluding 1,058 vendored and 2,989 generated parser lines), roughly 1.7–2.6× the estimate.

> **§11 Q8 — wall-clock and peak heap on all three fixtures vs. the pandas path.**
>
> **Java is 6.8× faster and uses 2.3× less memory** — 0.42 s / 288.5 MB against pandas' 2.84 s / 669.8 MB
> on `PlusRise.mgf`, with every fixture completing in under 350 ms in-process.

## How to reproduce

```
make it        # layers 2-4, both projects
make verify    # + unit suites, coverage gate, lint, banned deps, spec-audit
make report    # the verdict block from this file and PARITY_REPORT.md
```

The comparison policy lives in one place, `ResultComparator`, and `ResultComparatorTest` proves it detects
a single-bit difference — a comparator that always passes would make this whole report meaningless.
`GoldenResults` proves the same for the reader, rejecting a truncated or short golden rather than quietly
comparing fewer rows.
