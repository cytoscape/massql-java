# Tech Step 12 — Integration layers 2–4 and error paths

> **⛔ THIS STEP IS A GATE.** Its differential table *is* the spike's exit criterion. Green means the SDK
> reproduces MassQL; anything less is a finding to report, not a threshold to adjust.

## Goal

Prove the whole pipeline reproduces MassQL's answers on real files in all three formats, that the same data gives
the same rows across formats, that the CLI honours its contract, and that every error path fails cleanly.

## Prerequisites

| Step | Why |
|---|---|
| [Step 11](Tech_Step11.md) | Provides the public API and the CLI these tests drive. |
| [Step 2](Tech_Step2.md) | Provides every fixture and golden. |
| [Step 8](Tech_Step8.md) | Its gate must be green — otherwise a failure here cannot be attributed to the query layer. |

## Context

Three of `SPIKE.md` §6b's four layers land here (layer 1 was [Step 8](Tech_Step8.md)). Their jobs differ: the
**differential** proves we match Python; **cross-format equivalence** catches reader-specific bugs a per-format
golden structurally cannot see; the **CLI contract** protects the interface Phase 2 and the differential both
depend on.

Layer 3 is worth more than its size suggests. `SPIKE.md` §6b: *"Both are stronger than any single golden."* A
per-format golden can hide a bug that affects both the Java reader and — because the golden was generated through
the same Python loader — nothing at all. Comparing two formats of the same data has no such blind spot.

Governing sections: `SPIKE.md` §6b layers 2–4, §3 (the population table), §7 Step 2 done-criteria, §11.

## Scope

**In scope**
- Layer 2: end-to-end differential vs the Python goldens, per format, per column.
- Layer 3: the two cross-format equivalence pairs.
- Layer 4: the CLI contract.
- Error paths, per format.
- Wall-clock and peak-heap measurement.

**Out of scope**
- Reader parity — [Step 8](Tech_Step8.md).
- Unit-level semantics — Steps [9](Tech_Step9.md) and [10](Tech_Step10.md).
- The `make verify` wrapper and the review artifacts — [Step 13](Tech_Step13.md), which consumes this step's
  results.

## Deliverables

| Path | Content |
|---|---|
| `src/test/java/…/it/DifferentialIT.java` | Layer 2 |
| `src/test/java/…/it/CrossFormatEquivalenceIT.java` | Layer 3 |
| `src/test/java/…/it/CliContractIT.java` | Layer 4 |
| `src/test/java/…/it/ErrorPathIT.java` | Error paths |
| `src/test/java/…/it/ResultComparator.java` | The per-column comparison policy, in one reusable place |
| `docs/DIFFERENTIAL_REPORT.md` | The per-format, per-column table — the review artifact |

## Specification

### 1. Layer 2 — the differential, and its per-column policy

Run the public API over each fixture with its query and compare against the Python golden. **Compare values, not
text** — [Step 10](Tech_Step10.md) §5 established that Java and Python float *formatting* differ in known ways
while the *values* are identical, so a text diff would fail on differences that don't matter. Parse both sides and
compare field by field.

| Column | Policy |
|---|---|
| `scan`, `ms1scan`, `charge`, `mslevel` | **Exact**, including null-vs-value |
| `precmz`, `base_peak_mz` | **Relative 1e-9** (m/z) |
| `ms1_precmz` | **Relative 1e-9** normally, but **1e-7 when the fixture is 32-bit mzXML**. It is a *measured* m/z read from the binary array, so mzXML's single `precision="32"` truncates it: measured deltas vs the mzML golden are 4.9e-9 and 2.9e-8, and `float32(mzML_value) == our_value` exactly (Correction C11). This is a format property, not a decoder bug |
| `tic`, `base_peak_i`, `ms1_i`, `ms1_base_peak_i` | **Bit-identical** (intensities) |
| `rt` | **Bit-identical** — requires the double-precision `scanRt` from [Step 5](Tech_Step5.md) §1 |
| every column | **Exact null-vs-value.** A null where the golden has a value, or vice versa, is a failure regardless of the numeric policy |

Also compare **row count** and **row order** (ascending scan id) before comparing fields, so a mismatch reports
"expected 664 rows, got 663" rather than a field-level diff on misaligned rows.

`tic` is the one column where an accumulation-order caveat could apply, as in [Step 8](Tech_Step8.md) §1. Try
bit-identical first; if it fails **only** on `tic` and **only** in the last bits, that is an accumulation-order
artifact, not a bug — but record it explicitly in `DIFFERENTIAL_REPORT.md` with the exact tolerance adopted and
why, and keep every other intensity column bit-exact.

Fixtures and expected counts:

| Fixture | Query | Golden | Expected |
|---|---|---|---|
| `data/small.mzML` | `test_mzml.massql` *(default 20 ppm)* | `output/small_mzml_results.json` | **6 rows**, of which **4 have null `ms1_i`/`ms1_precmz` with `ms1_base_peak_i` populated** |
| `data/small.mzML` | `test_mzml.massql` `--precursor-tol-ppm 60` | `output/small_mzml_tol60_results.json` | **6 rows**, all `ms1_*` populated |
| `data/PlusRise.mgf` | `test.massql` | `output/plusrise_results.json` | **664 rows** |
| `data/small.mzXML` | `test_mzml.massql` | `output/small_mzxml_results.json` | **6 rows** |
| `data/small.mzXML` | `test_mzml.massql` `--precursor-tol-ppm 60` | `output/small_mzxml_tol60_results.json` | **6 rows** |
| `data/DP00570_F02.mzxml` | **`test_dp00570.massql`** | `output/dp00570_mzxml_results.json` | **3 rows**, all `ms1_*` populated |
| `data/DP00570_F02.mgf` | **`test_dp00570.massql`** | `output/dp00570_mgf_results.json` | **2 rows**, all `ms1_*` null |
| `data/DP00570_F02.mzxml` | `test.massql` | `output/dp00570_mzxml_empty_results.json` | **0 rows** — deliberate empty-result case (`test.massql` is the metabolomics query and matches nothing here) |
| `data/small.mzML` | `test_ms1.massql` | `output/small_mzml_ms1_results.json` | **14 rows**, MS1DATA shape — see below |
| `fixtures/micro/micro.{mgf,mzML,mzXML}` | `test_micro.massql` | `output/micro_*_results.json` | **2 rows** each |
| `fixtures/micro/micro_rtseconds.mzML` | `test_micro.massql` | `output/micro_mzml_rtseconds_results.json` | **2 rows** — the mzML `unitName="second"` side of the RT conditional |

⚠ **The MS1DATA shape is 9 keys, not 4** (Correction C15). `precmz`/`ms1scan`/`charge` are absent as
documented, but the reference wrapper adds the five computed columns unconditionally and they come back
**null**: `scan, rt, mslevel, tic, base_peak_i, base_peak_mz, ms1_i, ms1_precmz, ms1_base_peak_i`.
**Resolve the open decision in [Step 10](Tech_Step10.md) §5 before writing this assertion.**

> ⚠ **Correction C26 reverses this too** — it read *"gitignored fixtures (both Ewing files) skip with a
> clear message when absent; never fail"*. Skipping is what made the whole suite prove nothing.

A missing fixture is a **hard failure** naming `scripts/fetch-fixtures.sh` (the two Ewing files stay
gitignored for licence reasons only; CI fetches and caches them). CI asserts the skipped-test count is
**0** plus a floor on the number executed, so "the committed fixtures actually ran" is enforced by the
build rather than left to a reviewer.

### 2. Layer 3 — cross-format equivalence

Two pairs. Each tests something the other cannot.

**Pair A — `small.mzML` vs `small.mzXML`: same data, same query, identical rows.**
Including populated `ms1_*`. This catches reader-specific bugs — a byte-order or interleaving error in one reader
that a per-format golden would not reveal, because that golden came through the same Python loader either way.

Caveat from [Step 2](Tech_Step2.md) §1: if msconvert dropped `precursorScanNum` or renumbered scans,
`CONVERSION_NOTES.md` says so and this comparison degrades to the non-`ms1_*` columns. **Read that file before
debugging a failure here** — the cause may be the conversion, not the code. If degraded, the test must state the
degradation in its failure message rather than silently comparing fewer columns.

**Pair B — `DP00570_F02.mzxml` vs `DP00570_F02.mgf`: same experiment, rows must differ in exactly the predicted
way.**
This is the higher-value half. Per the population table ([Step 10](Tech_Step10.md) §6):

| Column | mzXML | MGF |
|---|---|---|
| `precmz`, `rt`, `tic`, `mslevel`, `base_peak_i`, `base_peak_mz` | populated | populated |
| `scan` | populated — but **disjoint ids**, see below | populated |
| `ms1scan` | **populated, by document order** | **null** |
| `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` | **populated** | **null** |
| **`charge`** | **null on EVERY row** | **`2` (583 scans) or `1` (42 scans)** — never null |

> ⚠ **`charge` was missing from this table and it does NOT agree across the pair** (Correction C29).
> Measured with MassQL's own loader on the two files:
>
> - The **mzXML has zero `precursorCharge` attributes**, so every row's raw charge is `0`, which
>   [Step 10](Tech_Step10.md) converts to **null**. All 110,547 rows.
> - The **MGF carries real charge data** and, by Correction C6, an absent `CHARGE=` becomes **`1`** rather
>   than 0 — so MGF charge is **never null**: 583 scans at 2, 42 at 1.
>
> So `charge` belongs with the *predicted differences*, not the shared columns. A test asserting "the
> shared columns agree" over a list that includes `charge` fails for an entirely correct reason. This is
> the same underlying trap as C6 — three formats, three charge defaults — surfacing at the cross-format
> layer where it is easiest to mistake for a bug.

Assert both halves: the shared columns agree, **and** the `ms1_*` / `charge` columns differ exactly this
way. That pins the format distinction *and* the document-order rule in one test — the mzXML has **zero
`precursorScanNum` attributes**, so populated `ms1scan` values are only possible under the document-order
rule ([Step 7](Tech_Step7.md)), where all 687 links are now verified.

⚠ **This is NOT a row-identity join — the two files have DISJOINT scan ids** (Correction C13: mzXML matches `[2, 556, 871]`, MGF matches `[370, 598]`). The MGF has no `SCANS=`, so MassQL numbers it by block index, and charge filtering dropped 62 of 687 MS2 scans. Assert the **population pattern per file** instead: on the mzXML every row has `ms1scan` and all three `ms1_*` populated; on the MGF every row has them null with `rt` = `0.0`. Do not attempt a join, and do not weaken this to "some rows populated" — it is every row on one side and none on the other.

### 3. Layer 4 — the CLI contract

Drive `cli.Main` as a subprocess. **Two independent properties, deliberately tested separately**
(Correction C25c) — the original version asserted both at once by reading data off the pipe, which made
a stream-hygiene regression present as a data mismatch and vice versa.

**(a) Functional correctness — read the result from `--output FILE`, not the pipe.** Point `--output` at
a temp file and compare *that*. No interleaving is possible, and a failure leaves the artifact on disk
to inspect.

| Assertion | Detail |
|---|---|
| Exit code 0 on success | |
| `--precursor-tol-ppm` honoured | Two directions, both against real goldens rather than ad-hoc checks: the **default 20 ppm** run must match `small_mzml_results.json` (4 rows with null `ms1_i`/`ms1_precmz` and **populated `ms1_base_peak_i`**), and `--precursor-tol-ppm 60` must match `small_mzml_tol60_results.json` (all 6 populated). Same file, same query, differing only in that flag — the CLI-level proof of [Step 10](Tech_Step10.md) §3.2. Additionally check a deliberately absurd tolerance (0.001 ppm) nulls **all** matches while every `ms1_base_peak_i` survives |
| Default is 20.0 | Omitting the flag reproduces the golden |

The tight-tolerance case is the one to write first — it is the only place the `ms1_base_peak_i`-survives-a-miss
rule is observable from outside the SDK.

**(b) Stream hygiene — the one thing only a subprocess can prove.** [Step 11](Tech_Step11.md)'s
`MainStreamDisciplineTest` owns the payload-shape assertions in-process; keep here only what an
in-process `System.setOut` test genuinely **cannot** establish, namely that a real forked process with
real file descriptors keeps the two streams apart (see the trap in §*Known traps*).

| Assertion | Detail |
|---|---|
| stdout is a valid JSON array | Default mode, no `--output`. Parse it; assert nothing but JSON |
| Diagnostics on **stderr only** | Assert stdout has no diagnostic text and no stack frame |
| Trailing newline | Matches `massql_query.py:195` |
| `--output` leaves stdout empty | The complement: with `--output FILE`, stdout is **empty** and the file holds the array |
| Both modes agree byte-for-byte | Same run twice, once piped and once to `--output`; the bytes must be identical. This is what makes (a)'s file-based comparison a valid proxy for the piped payload |

### 4. Error paths, per format

Each of these, for each of the three formats where applicable:

| Case | Required behaviour |
|---|---|
| Truncated / malformed file | Clear exception, **no partial results**. Truncate a real fixture mid-spectrum to construct it |
| Unsupported query | `MassqlParseException` **naming the offending construct** — e.g. `QUERY scansum(MS2DATA)` names `scansum` |
| `QUERY scaninfo WHERE …` | Parse error whose message explains the function-call form ([Step 4](Tech_Step4.md) §4) |
| Query matching nothing | **Empty JSON array, exit 0** — not a crash, not exit 1 |
| Missing / empty `msLevel` tag | Handled per [Step 7](Tech_Step7.md) §4, using `empty_msLevel_tag.mzXML` |
| Missing file / directory / empty file | Clear error naming the path, exit 2 |
| **Handle leak** | Open and close **200+** files across all three formats without exhausting descriptors. Phase 2's `shutDown()` depends on this |

### 5. Performance measurement

Record, don't gate — but record honestly. Per fixture: **wall-clock** and **peak heap**.

Compare against the pandas path (re-run `massql_query.py` under `/usr/bin/time -l`). `SPIKE.md` §7: *"if Java
isn't at least as fast as pandas on the MGF, something is quadratic (probably a linear scan where a binary search
belongs)."* The MGF is the fixture that matters — 34,513 spectra.

If Java is slower, treat it as a finding and look at `mzWindow` first ([Step 5](Tech_Step5.md) §4). Put the numbers
in `DIFFERENTIAL_REPORT.md`; they answer `SPIKE.md` §11 Q8.

### 6. The differential report

`docs/DIFFERENTIAL_REPORT.md` is what the reviewer reads, and [Step 13](Tech_Step13.md)'s `make verify` table
builds on it. Per format: rows expected vs matched, per-column pass/fail, any adopted tolerance with its
justification, layer 3 results including the Pair B intersection size, and the performance numbers.

Answer these `SPIKE.md` §11 questions here, one sentence each: **Q2** (same rows on `small.mzML` and
`small.mzXML`?), **Q6** (measured LOC — does 1,200–1,800 hold?), **Q8** (wall-clock and heap vs pandas).

## Known traps

- **Text-diffing the JSON.** Java and Python float formatting differ in known ways while values agree. Compare
  parsed values. §1.
- **Loosening a policy to reach green.** The table *is* the exit criterion. A relaxed tolerance converts a found
  bug into a permanent unknown. The one permissible exception is documented in §1, and only for `tic`.
- **Debugging Pair A before reading `CONVERSION_NOTES.md`.** The cause may be msconvert, recorded in Step 2.
- **A vacuous Pair B pass** from a near-empty scan-id join. Report the intersection size.
- **Testing stream separation in-process.** Use a subprocess; an in-process `System.setOut` test can pass while
  the real CLI interleaves streams. This is why §3(b) survives as a subprocess test rather than being folded
  into [Step 11](Tech_Step11.md)'s in-process one.
- **Reading the payload off the pipe to check data correctness.** That is §3(a)'s job and it uses
  `--output FILE`; mixing the two makes a hygiene regression look like a data mismatch (Correction C25c).
- **Skipping everything.** Fixtures and goldens are **committed to this repo** (Correction C26) and
  `Fixtures` now **fails** rather than skipping when one is missing — a green run with zero assertions is
  the failure mode this replaced. CI asserts the skipped-test count is **0**.
- **Exit 1 for a no-match query.** It is exit 0 with `[]`.
- **Comparing `rt` with a tolerance.** It is bit-identical, which is why `scanRt` is a double.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `DifferentialIT` | IT | §1 for every fixture/golden pair, per-column policy, row count and order first. Includes the MS1DATA 4-key shape. |
| `ResultComparatorTest` | unit | The comparator itself: a single-bit intensity difference **fails**; a 1e-10 relative m/z difference **passes** and 1e-8 fails; null-vs-0.0 **fails**; a row-count mismatch reports counts. **Test the test** — a comparator that always passes yields a meaningless gate. |
| `CrossFormatEquivalenceIT` | IT | Pair A identical rows (or documented degradation, stated in the failure message); Pair B shared columns equal **and** `ms1_*` differing exactly per the table, with the intersection size reported. |
| `CliContractIT` | IT | §3(a) via subprocess reading `--output FILE` — including the tight-tolerance case proving `ms1_base_peak_i` survives — **and** §3(b)'s stream-separation assertions, which include the piped-vs-`--output` byte-equality check that justifies (a)'s file-based comparison. |
| `ErrorPathIT` | IT | Every §4 row, per format. |
| `ResourceLeakIT` | IT | 200+ open/close cycles across all three formats. |
| `PerformanceIT` | IT | Records wall-clock and peak heap per fixture; asserts only a generous ceiling so it reports rather than flakes. |

## Done when

- [ ] `mvn verify` green.
- [ ] The differential table reads **6/6 on `small.mzML`, 664/664 on `PlusRise.mgf`, and the full mzXML golden —
      per column**.
- [ ] The MS1DATA differential passes with precursor keys **absent**.
- [ ] Layer 3 Pair A: identical rows, or a degradation documented and traced to `CONVERSION_NOTES.md`.
- [ ] Layer 3 Pair B: shared columns equal, `ms1_*` differing exactly as predicted, intersection size reported.
- [ ] CLI: all of §3, including the tight-tolerance case.
- [ ] Every §4 error path behaves as specified; 200+ file cycles leak nothing.
- [ ] `ResultComparatorTest` proves the comparator detects a single-bit difference.
- [ ] `docs/DIFFERENTIAL_REPORT.md` has the per-format per-column table, any adopted tolerance with
      justification, performance numbers, and one-sentence answers to §11 Q2, Q6 and Q8.

## References

- `SPIKE.md` §6b layers 2–4 (per-column policy, the two pairs, the CLI contract, the error-path list), §3 (the
  population table), §7 Step 2 done-criteria and the performance note, §11
- [Step 10](Tech_Step10.md) §6 — the population table Pair B asserts
- [Step 7](Tech_Step7.md) — the document-order rule Pair B pins from outside
- [Step 2](Tech_Step2.md) `data/CONVERSION_NOTES.md` — read before debugging Pair A
- [Step 8](Tech_Step8.md) §1 — the bit-identity harness and the accumulation-order caveat
