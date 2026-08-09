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

Three of [`SPIKE.md`](SPIKE.md) §6b's four layers land here (layer 1 was [Step 8](Tech_Step8.md)). Their jobs differ: the
**differential** proves we match Python; **cross-format equivalence** catches reader-specific bugs a per-format
golden structurally cannot see; the **CLI contract** protects the interface Phase 2 and the differential both
depend on.

Layer 3 is worth more than its size suggests. [`SPIKE.md`](SPIKE.md) §6b: *"Both are stronger than any single golden."* A
per-format golden can hide a bug that affects both the Java reader and — because the golden was generated through
the same Python loader — nothing at all. Comparing two formats of the same data has no such blind spot.

Governing sections: [`SPIKE.md`](SPIKE.md) §6b layers 2–4, §3 (the population table), §7 Step 2 done-criteria, §11.
(Both `SPIKE.md` and [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) live in this repo under
`docs/harness/` since [C41](Tech_Step_INDEX.md#c41); the paths cited below are in-repo.)

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

Integration tests live in the **`integrationTest` source set**, not `src/test` — they are a separate Gradle
suite (C43), and there is no `it` package. The two non-IT classes live in the **unit** source set on purpose:
`integrationTest` depends on `sourceSets.test.output`, so an IT can use them, while a unit test could not reach
them the other way round.

| Path | Content |
|---|---|
| `src/test/java/…/io/GoldenResults.java` | Reads `goldens/query-results/*.json` into `ScanInfoResult` rows |
| `src/test/java/…/io/GoldenResultsTest.java` | ⛔ Proves the reader rejects a truncated or short file — see below |
| `src/test/java/…/exec/ResultComparator.java` | The per-column comparison policy, in one reusable place |
| `src/test/java/…/exec/ResultComparatorTest.java` | ⛔ Proves the comparator detects a single-bit difference |
| `src/integrationTest/java/…/exec/DifferentialIT.java` | Layer 2 |
| `src/integrationTest/java/…/io/CrossFormatEquivalenceIT.java` | Layer 3 |
| `src/integrationTest/java/…/io/ErrorPathIT.java` | Error paths |
| `src/integrationTest/java/…/io/ResourceLeakIT.java` | 200+ open/close cycles |
| `src/integrationTest/java/…/exec/PerformanceIT.java` | Wall-clock, peak heap, host spec |
| **`cli/src/integrationTest/java/…/cli/CliContractIT.java`** | Layer 4 — a **different Gradle project**, because it forks the CLI uber-jar. See §3 |
| `docs/harness/DIFFERENTIAL_REPORT.md` | The per-format, per-column table — the review artifact |

> ⛔ **Both self-tests are load-bearing, not ceremony.** A comparator that always passes, or a reader that
> silently returns fewer rows, converts this gate into a green light that proves nothing. That is not
> hypothetical here: `ParityDump`'s hand-rolled regex stopped at `polarity` and silently dropped `charge`,
> `precmz` and `ms1scan`, which let an MGF charge bug survive [Step 8](Tech_Step8.md)'s **green** gate for five
> steps (C44). The reader for *this* step therefore uses a real JSON parser — `gson`, `testImplementation` only,
> so it never reaches the shipping closure — and proves its own strictness.

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
| **`tic`** | **Relative 1e-6** — ⛔ **NOT bit-identical**; see Correction C34 below |
| `base_peak_i`, `ms1_i`, `ms1_base_peak_i` | **Bit-identical** (intensities) — verified achievable |
| `rt` | **Bit-identical** — requires the double-precision `scanRt` from [Step 5](Tech_Step5.md) §1 |
| every column | **Exact null-vs-value.** A null where the golden has a value, or vice versa, is a failure regardless of the numeric policy |

Also compare **row count** and **row order** (ascending scan id) before comparing fields, so a mismatch reports
"expected 664 rows, got 663" rather than a field-level diff on misaligned rows.

> ⚠ **Correction C37 — this layer is where a wrong m/z-window choice shows up, far from its cause.**
> [Step 5](Tech_Step5.md) §4 provides **two** window methods: `mzWindowExclusive` (strict, what
> [Step 9](Tech_Step9.md)'s conditions use) and `mzWindow` (inclusive, what [Step 10](Tech_Step10.md) §3.4's
> precursor lookup uses). They are not interchangeable, and picking the wrong one produces exactly the failure
> shape this table is built to detect:
>
> | Wrong choice | Symptom here |
> |---|---|
> | Step 9 using the **inclusive** window | **Row count** off — a scan qualifies that the golden excludes: `micro_mzml_edge` returns 1 row where its golden has 0 |
> | Step 10 using the **exclusive** window | `ms1_i`/`ms1_precmz` **null where the golden has a value** — `micro_onbound` returns `null` where its golden has `7000.0` and `499.99`, reported by the *"exact null-vs-value"* row |
>
> Both halves are covered at this layer, by one fixture each, and they fail in **different columns** — which is
> what makes a failure attributable. `micro_mzml_edge` puts a **condition** window bound exactly on scan 3's
> `201.0` peak and its golden is empty by design, so an inclusive condition window surfaces as a row count.
> `micro_onbound` puts an **MS1** peak exactly on the **lookup** bound at the default 20 ppm and its golden
> populates `ms1_i`, so an exclusive lookup surfaces as a null. Each was verified by injecting that swap and
> confirming the matching pair — and only that pair — fails.
>
> Both are silent unless a peak lands exactly on a bound, which is why
> [Step 5](Tech_Step5.md)'s `MzWindowTest` and [Step 10](Tech_Step10.md)'s `PrecursorLookupTest` each assert the
> on-bound case directly. **If a differential failure has this shape, check the window method before suspecting
> the decoder or the tolerance arithmetic.**

> ⛔ **Correction C34 — `tic` was grouped with the bit-identical intensities and CANNOT be.** This row used
> to read *"`tic`, `base_peak_i`, `ms1_i`, `ms1_base_peak_i` | Bit-identical"*, with a caveat suggesting that
> a `tic`-only failure "in the last bits" would be an **accumulation-order** artifact. The instinct was
> right and both the cause and the magnitude were wrong.
>
> **The cause is dtype.** MassQL's intensity column is `float32`, and `tic` is
> `ms2_df.groupby("scan").sum()["i"]` over it (`msql_engine.py:638,660`, renamed at
> `massql_query.py`'s `rename(columns={"i": "tic"})`) — a **float32 accumulation**. Our float64 sum is *exact*; the golden's is not.
>
> **Measured on `goldens/query-results/small_mzml_results.json` — all six rows differ, worst 3.691e-08:**
>
> | scan | golden `tic` | ours (float64) | rel diff |
> |---|---|---|---|
> | 3 | 586278.875 | 586278.8533592224 | 3.691e-08 |
> | 17 | 1102582.0 | 1102582.026266098 | 2.382e-08 |
> | 44 | 925073.8125 | 925073.8236341476 | 1.204e-08 |
>
> 3.7e-08 is **not** "the last bits", and anyone chasing an ordering bug would not find it.
>
> **Only `tic` moves.** `base_peak_i` is a `max()` — a *selected* value with no accumulation — and is
> **bit-identical on all six golden rows**, verified. `ms1_i` and `ms1_base_peak_i` are likewise lookups and
> maxima. So this is a split, not a blanket loosening: **`tic` at relative 1e-6, every other intensity
> column still bit-exact.** Record it in `DIFFERENTIAL_REPORT.md` noting the error is in the **reference**,
> not in us — the same finding [Step 8](Tech_Step8.md) §1 records for `i_sum_hex` (C33c).

Fixtures and expected counts:

| Fixture | Query | Golden | Expected |
|---|---|---|---|
| `data/small.mzML` | `test_mzml.massql` *(default 20 ppm)* | `goldens/query-results/small_mzml_results.json` | **6 rows**, of which **4 have null `ms1_i`/`ms1_precmz` with `ms1_base_peak_i` populated** |
| `data/small.mzML` | `test_mzml.massql` `--precursor-tol-ppm 60` | `goldens/query-results/small_mzml_tol60_results.json` | **6 rows**, all `ms1_*` populated |
| `data/PlusRise.mgf` | `test.massql` | `goldens/query-results/plusrise_results.json` | **664 rows** |
| `data/small.mzXML` | `test_mzml.massql` | `goldens/query-results/small_mzxml_results.json` | **6 rows** |
| `data/small.mzXML` | `test_mzml.massql` `--precursor-tol-ppm 60` | `goldens/query-results/small_mzxml_tol60_results.json` | **6 rows** |
| `data/DP00570_F02.mzxml` | **`test_dp00570.massql`** | `goldens/query-results/dp00570_mzxml_results.json` | **3 rows**, all `ms1_*` populated |
| `data/DP00570_F02.mgf` | **`test_dp00570.massql`** | `goldens/query-results/dp00570_mgf_results.json` | **2 rows**, all `ms1_*` null |
| `data/DP00570_F02.mzxml` | `test.massql` | `goldens/query-results/dp00570_mzxml_empty_results.json` | **0 rows** — deliberate empty-result case (`test.massql` is the metabolomics query and matches nothing here) |
| `data/small.mzML` | `test_ms1.massql` | `goldens/query-results/small_mzml_ms1_results.json` | **14 rows**, MS1DATA shape — see below |
| `fixtures/micro/micro.{mgf,mzML,mzXML}` | `test_micro.massql` | `goldens/query-results/micro_*_results.json` | **2 rows** each |
| `fixtures/micro/micro_rtseconds.mzML` | `test_micro.massql` | `goldens/query-results/micro_mzml_rtseconds_results.json` | **2 rows** — the mzML `unitName="second"` side of the RT conditional |
| `fixtures/micro/micro.mzML` | **`test_micro_edge.massql`** *(added Step 9)* | `goldens/query-results/micro_mzml_edge_results.json` | **0 rows.** ⚠ **An empty golden is a real assertion here, not a missing one** — do not treat `[]` as "nothing to check" or skip the pair. `MS2PROD=201.5:TOLERANCEMZ=0.5` puts the window bound exactly on scan 3's `201.0` peak, and MassQL excludes it; this file *is* the executed evidence for the strict half of Correction C37 (§1). A build that used inclusive bounds for conditions returns 1 row and fails here |
| `fixtures/micro/micro_ms1var.mzML` | **`test_micro_ms1var.massql`** *(added Step 9)* | `goldens/query-results/micro_ms1var_results.json` | **1 row**, scan `2`. Two conditions ANDed across different MS levels (`MS1MZ=400.0 AND MS2PROD=200.0`) against the only fixture whose two MS1 scans differ, so it is the only golden that can catch an `MS1MZ` condition resolved against the wrong linked MS1 scan. Every value is hand-computable: `tic` 2000.0, `base_peak_i` 1500.0, `base_peak_mz` 200.0, `ms1_base_peak_i` 2000.0, and `ms1_i`/`ms1_precmz` **null** — a [Step 10](Tech_Step10.md) §3.2 tolerance miss on a file small enough to check by hand |
| `fixtures/micro/micro_onbound.mzML` | **`test_micro_onbound.massql`** *(added Step 12)* | `goldens/query-results/micro_onbound_results.json` | **1 row**, scan `2`. The **inclusive** half of C37 (§1), at the precursor lookup rather than the conditions. The MS1 peak is at `499.99` — exactly `500.0 - 500.0 * 20 / 1e6` in IEEE-754, the same bits on both sides — so at the default tolerance the reference admits it: `ms1_i` `7000.0`, `ms1_precmz` `499.99`. An exclusive lookup returns `null` for both. `ms1_base_peak_i` is `9000.0`, a *different* peak in the same MS1 scan, so conflating the lookup with the scan-level base peak also fails |

> ✅ **RESOLVED by Correction C40 — the MS1DATA shape is the SAME 12 keys as MS2DATA.** This paragraph read
> *"The MS1DATA shape is 9 keys, not 4 (Correction C15) … Resolve the open decision in Step 10 §5 before writing
> this assertion."* Both the open decision and C15 are closed:
> [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) defines **one union schema**
> discriminated by `mslevel`, with no key ever absent.
>
> **This file also contradicted itself** — the fixture table said 9 keys while the `DifferentialIT` row said 4. Both now
> read one 12-key shape. The contract is [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md); do not restate it here.
>
> For the MS1DATA pair specifically: `precmz`/`ms1scan`/`charge` and the three `ms1_*` columns are **present and
> `null`**, while **`base_peak_i`/`base_peak_mz` carry real values**. `small_mzml_ms1_results.json` was
> regenerated accordingly — it was the only non-conforming golden of the 16.
>
> ⚠ **`tic` needs C34's relative 1e-6 on MS1DATA rows too.** Measured on MS1 scan 1: golden `69381840.0` vs our
> exact `69381842.11895752`, relative **3.05e-08** — the same `float32` accumulation, so the tolerance is not
> MS2-only.

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
[`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) says so and this comparison degrades to the non-`ms1_*` columns. **Read that file before
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

Drive `cli.Main` as a subprocess.

This is a **subprocess** test in the `:cli` project — `cli/src/integrationTest/java` — because the thing under
test is the assembled uber-jar and its real file descriptors. `Main.run(String[], PrintStream, PrintStream)` is
package-private and already fully exercised in-process by [Step 11](Tech_Step11.md); calling it again here would
test nothing new and would not prove the streams stay apart.

**The build supplies both facts the test needs**, rather than the test guessing them:

```groovy
// cli/build.gradle -- integrationTest suite
dependencies { implementation project(':') }
targets.configureEach {
    testTask.configure {
        dependsOn tasks.named('shadowJar')          // the jar exists and is current
        systemProperty 'cliJar', tasks.named('shadowJar').get().archiveFile.get().asFile
    }
}
```

```java
// java.home rather than a bare "java": the forked JVM must be the one running the test.
Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
Path cliJar = Path.of(System.getProperty("cliJar"));
Process p = new ProcessBuilder(java.toString(), "-jar", cliJar.toString(),
                spectra.toString(), query.toString(), "--output", out.toString())
        .start();
```

> ⚠ **Two traps, both of which a first draft of this section fell into** (Corrections C43, C46):
>
> - **Do not build the jar name from `System.getProperty("cliVersion")`.** `cliVersion` is a *Gradle* property
>   from `gradle.properties`, not a JVM system property — it resolves to `null` and the path becomes
>   `massql-java-cli-null.jar`. Have the build hand over the resolved path, as above; then a version bump
>   cannot break the test.
> - **Do not call `TestPaths.repositoryRoot()` from here.** It lives in the **SDK's** test source set, and
>   `:cli` deliberately takes only the SDK's test *resources*, not its classes.
>
> `dependsOn shadowJar` is what makes the jar guaranteed present. A test that skipped because the jar was
> missing would reproduce exactly the C26 failure.

**Two independent properties, deliberately tested separately** (Correction C25c) — the original version asserted both at once by reading data off the pipe, which made
a stream-hygiene regression present as a data mismatch and vice versa.

**(a) Functional correctness — read the result from `--output FILE`, not the pipe.** Point `--output` at
a temp file and compare *that*. No interleaving is possible, and a failure leaves the artifact on disk
to inspect.

| Assertion | Detail |
|---|---|
| Exit code 0 on success | |
| `--precursor-tol-ppm` honoured | Two directions, both against real goldens rather than ad-hoc checks: the **default 20 ppm** run must match `small_mzml_results.json` (**6 rows**, of which **4** have null `ms1_i`/`ms1_precmz` with **populated `ms1_base_peak_i`**), and `--precursor-tol-ppm 60` must match `small_mzml_tol60_results.json` (all 6 populated). Same file, same query, differing only in that flag — the CLI-level proof of [Step 10](Tech_Step10.md) §3.2. Additionally check a deliberately absurd tolerance (0.001 ppm) nulls **all** matches while every `ms1_base_peak_i` survives |
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
| Trailing newline | Matches `massql_query.py`'s `sys.stdout.write("\n")` |
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

Compare against the pandas path (re-run `massql_query.py` under `/usr/bin/time -l`). [`SPIKE.md`](SPIKE.md) §7: *"if Java
isn't at least as fast as pandas on the MGF, something is quadratic (probably a linear scan where a binary search
belongs)."* The MGF is the fixture that matters — 34,513 spectra.

If Java is slower, treat it as a finding and look at the two window methods first ([Step 5](Tech_Step5.md) §4) —
`mzWindowExclusive` is the hotter of the pair, being called per condition per scan. Put the numbers
in `DIFFERENTIAL_REPORT.md`; they answer [`SPIKE.md`](SPIKE.md) §11 Q8.

### 6. The differential report

`docs/harness/DIFFERENTIAL_REPORT.md` is what the reviewer reads, and [Step 13](Tech_Step13.md)'s `make verify` table
builds on it. Per format: rows expected vs matched, per-column pass/fail, any adopted tolerance with its
justification, layer 3 results including the Pair B intersection size, and the performance numbers.

Answer these [`SPIKE.md`](SPIKE.md) §11 questions here, one sentence each: **Q2** (same rows on `small.mzML` and
`small.mzXML`?), **Q6** (measured LOC — does 1,200–1,800 hold?), **Q8** (wall-clock and heap vs pandas).

## Known traps

- **Text-diffing the JSON.** Java and Python float formatting differ in known ways while values agree. Compare
  parsed values. §1.
- **Loosening a policy to reach green.** The table *is* the exit criterion. A relaxed tolerance converts a found
  bug into a permanent unknown. The one permissible exception is documented in §1, and only for `tic`.
- **Debugging Pair A before reading [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md).** The cause may be msconvert, recorded in Step 2.
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
- **Blaming the decoder for a null `ms1_i` where the golden has a value.** Check the m/z-window method first:
  [Step 10](Tech_Step10.md)'s lookup must use the **inclusive** `mzWindow`, not Step 9's exclusive variant
  (Correction C37, §1).

## Tests required

| Test | Type | Pins |
|---|---|---|
| `DifferentialIT` | IT | §1 for every fixture/golden pair, per-column policy, row count and order first. Includes the MS1DATA pair, which carries the **same 12 keys** as every other row (C40 — this row used to say "the MS1DATA 4-key shape" while the fixture table said "9 keys"). |
| `ResultComparatorTest` | unit | The comparator itself: a single-bit intensity difference **fails**; on **`precmz`** a 1e-10 relative difference **passes** and 1e-8 **fails** — scoped to that column deliberately, because `ms1_precmz` on a 32-bit mzXML permits 1e-7 and the same comparator must accept 1e-8 in that mode; null-vs-0.0 **fails**; a row-count mismatch reports counts. **Test the test** — a comparator that always passes yields a meaningless gate. |
| `CrossFormatEquivalenceIT` | IT | Pair A identical rows (or documented degradation, stated in the failure message); Pair B shared columns equal **and** `ms1_*` differing exactly per the table, with the intersection size reported. |
| `CliContractIT` | IT | §3(a) via subprocess reading `--output FILE` — including the tight-tolerance case proving `ms1_base_peak_i` survives — **and** §3(b)'s stream-separation assertions, which include the piped-vs-`--output` byte-equality check that justifies (a)'s file-based comparison. |
| `ErrorPathIT` | IT | Every §4 row, per format. |
| `ResourceLeakIT` | IT | 200+ open/close cycles across all three formats. |
| `PerformanceIT` | IT | Records wall-clock and peak heap per fixture; asserts only a generous ceiling so it reports rather than flakes. |

## Done when

- [x] `make test` and `make it` green, with **zero skips** (C26) — 699 tests: 551 SDK unit, 104 SDK
      integration, 32 CLI unit, 12 CLI integration. `make verify` is [Step 13](Tech_Step13.md)'s wrapper and
      is not this step's criterion — §Scope puts it there.
- [x] The differential table reads **6/6 on `small.mzML`, 664/664 on `PlusRise.mgf`, 6/6 on `small.mzXML`,
      3/3 on `DP00570_F02.mzxml` and 0/0 on its empty pair — per column**, and all 16 pairs pass.
      **717 rows compared; 11 of the 12 columns bit-identical**, `tic` worst case 4.700e-8.
- [x] The MS1DATA differential passes with the **same 12 keys** as every other row (C40): precursor keys
      **present and `null`**, `base_peak_i`/`base_peak_mz` **non-null**. This box used to require them "absent".
- [x] Layer 3 Pair A: **identical rows, not degraded** — [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md)
      records 34 `precursorScanNum` in `small.mzXML`, and `ms1scan` is asserted populated on both sides.
      `ms1_precmz` differs by at most 2.929e-8 (C11); every other column is bit-identical.
- [x] Layer 3 Pair B: shared columns equal, `ms1_*` and `charge` differing exactly as predicted,
      **intersection size 0** — the scan ids are disjoint (C13), so no join is attempted.
- [x] CLI: all of §3, including the tight-tolerance case at 0.001 ppm.
- [x] Every §4 error path behaves as specified; 250 cycles per format (plus 750 interleaved and 250 on
      `PlusRise.mgf`) leak nothing.
- [x] `ResultComparatorTest` proves the comparator detects a single-bit difference.
- [x] `docs/harness/DIFFERENTIAL_REPORT.md` has the per-format per-column table, any adopted tolerance with
      justification, performance numbers, and one-sentence answers to §11 Q2, Q6 and Q8.

## References

- [`SPIKE.md`](SPIKE.md) §6b layers 2–4 (per-column policy, the two pairs, the CLI contract, the error-path list), §3 (the
  population table), §7 Step 2 done-criteria and the performance note, §11
- [Step 10](Tech_Step10.md) §6 — the population table Pair B asserts
- [Step 7](Tech_Step7.md) — the document-order rule Pair B pins from outside
- [Step 2](Tech_Step2.md) [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) — read before debugging Pair A
- [Step 8](Tech_Step8.md) §1 — the bit-identity harness and the accumulation-order caveat
