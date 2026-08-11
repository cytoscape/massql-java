# Tech Step 8 — Reader parity (integration layer 1)

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

> **⛔ THIS STEP IS A GATE.** If decoded intensities are not bit-identical to the Python loader's, the decoder is
> wrong and every number produced downstream measures noise. **Do not proceed to [Step 9](Tech_Step9.md) until
> this is green.** Do not loosen the comparison to make it pass.

## Goal

Prove that all three Java readers load exactly the same peaks, with exactly the same bits, as MassQL's own Python
loader — before any query logic is written.

## Prerequisites

| Step | Why |
|---|---|
| [Step 2](Tech_Step2.md) | Provides the loader-parity dumps — per-scan peak counts, intensity sums, SHA-256 digests and first-8 values from MassQL's own loaded tables, floats as hex. This step's entire input. ⚠ They are **`.json.gz`** and live at **`src/test/resources/goldens/loader-parity/`**, committed in-repo (C26) — *not* `oracle/loader-parity/*.json` as this row used to say. C32 extended them from 8 fixtures to 14, and C36/C37 later added `micro_zeroint.mgf` and `micro_ms1var.mzML` for **16** — see §2, which holds the authoritative list. |
| [Step 6](Tech_Step6.md) | MGF and mzML readers. |
| [Step 7](Tech_Step7.md) | mzXML reader. |

## Context

[`SPIKE.md`](SPIKE.md) §6b puts this first among the four integration layers, and §7 Step 2 orders it explicitly: *"The three
readers, then integration layer 1 (reader parity) — before any query runs. If intensities aren't bit-identical to
Python's, the decoder is wrong and everything downstream is measuring noise."*

It is the cheapest place in the whole spike to learn that a decoder is wrong. A byte-order or float-precision
mistake found here is a one-line fix; the same mistake found at [Step 12](Tech_Step12.md) presents as a handful of
mismatched result rows and gets misdiagnosed as a filtering or collation bug.

It is a separate spec from the readers because a gate that lives inside another step's test suite gets treated as
one more failing test rather than as a stop condition.

Governing sections: [`SPIKE.md`](SPIKE.md) §6b layer 1, §7 Step 2 item 2, §11 Q1.

## Scope

**In scope**
- Per-format assertions of scan count, MS-level split, per-scan peak count, per-scan intensity sum, and per-scan
  `rt`, against the Step 2 dumps.
- **Bit-identical** intensity comparison.
- The instrument-attribute cross-check on the Ewing mzXML (no Python in the loop).
- A written parity report recording what passed and any accepted divergence.

**Out of scope**
- Query execution and result comparison — [Step 12](Tech_Step12.md) layers 2–4.
- Fixing decoder bugs. Those are fixes *to* [Step 6](Tech_Step6.md) or [Step 7](Tech_Step7.md); this step only
  detects them.
- Generating the dumps — [Step 2](Tech_Step2.md).

## Deliverables

| Path | Content |
|---|---|
| `src/test/java/…/io/ParityDump.java` | Shared reader for the full per-scan dump record. Three tests need it, so it is a helper rather than copied a third time |
| `src/test/java/…/io/ReaderParityIT.java` | The parameterized parity suite |
| `src/test/java/…/io/ReaderParityHarnessTest.java` | Tests the harness itself |
| `src/test/java/…/io/PeakOrderPreconditionTest.java` | The precondition that makes digest comparison valid (§1) |
| [`PARITY_REPORT.md`](PARITY_REPORT.md) | Per-format results table; the artifact the reviewer reads. ⚠ **Moved to `docs/harness/` by Correction C41** — it is an engineering record, read by someone confirming the steps rather than by an SDK consumer. |

> ⚠ **Two rows removed from this table (C32).**
> `src/test/resources/loader-parity/` — the dumps already live at
> **`src/test/resources/goldens/loader-parity/`**, committed by C26; there is nothing to copy.
> `InstrumentAttributeCrossCheckIT` — **already built and passing in [Step 7](Tech_Step7.md)**, with C30's
> corrected 1e-5 tolerances plus the no-bias and sharpness checks. §3 below documents it; this step does
> not rebuild it.

## Specification

### 1. What "bit-identical" means, and how to assert it

```java
assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
```

Not `assertEquals(expected, actual, delta)`. Not `Double.compare`. The point is to catch a decoder that produces
values which are *nearly* right — the signature of reading 8 bytes where 4 were written, or decoding a 32-bit
array straight to double instead of widening from float. Those errors are invisible under any tolerance a
reasonable person would pick, and they are precisely what this gate exists to find.

The Step 2 dumps are gzipped and store, per scan, `peak_count`, `i_sum_hex`, **SHA-256 digests of the full m/z and intensity arrays** (packed big-endian IEEE754 double), and the first 8 values of each as hex for diagnosis. The digest verifies every bit of a 20,000-peak scan in 64 characters; storing all values as hex produced 86 MB and was no more rigorous. Reproduce a digest with `ByteBuffer.allocate(8*n).order(BIG_ENDIAN)`, `putDouble` each value, then `MessageDigest.getInstance("SHA-256")`. Parse them with
`Double.parseDouble` on the hex form (Java accepts `0x1.5c8f2ap+20`) — **never** via a decimal string, which can
lose or fabricate low bits and turn a real bug into a passing test.

> ⛔ **Correction C32(d) — this section used to prefer comparing "the multiset of individual
> intensities". That is not implementable, and it contradicted the harness test.** The dumps store
> **SHA-256 digests** plus the first 8 values; the individual peaks are not in them, because Step 2
> measured the all-values form at **86 MB** and rejected it as no more rigorous. Digests are also
> **order-sensitive**, which contradicted `ReaderParityHarnessTest`'s requirement that a reordering-only
> difference compare *equal*.

**Compare the digests.** That is strictly stronger than a multiset comparison, because it pins the array
**order** as well as every bit of every value. Repack our array and hash it:

```java
ByteBuffer b = ByteBuffer.allocate(8 * n).order(ByteOrder.BIG_ENDIAN);
for (double v : values) b.putDouble(v);
MessageDigest.getInstance("SHA-256").digest(b.array());   // hex-compare to i_sha256 / mz_sha256
```

**The precondition that makes this valid, and why it must be asserted.** Digest equality requires our
array order to equal MassQL's *file* order — and `SpectrumTableBuilder` **sorts a scan by m/z when it is
not already ascending** ([Step 5](Tech_Step5.md), `:174-176`). Measured: **zero fixtures have descending
m/z within a scan**, so the sort never fires and file order is preserved. `PeakOrderPreconditionTest`
asserts that, so a future unsorted fixture fails saying *"this fixture has unsorted peaks, digest
comparison is no longer valid"* rather than presenting as a mystery decode bug.

**One deliberate exception, for sums only.** A per-scan intensity **sum** is an accumulation, and
floating-point addition is not associative: Python's `numpy.sum` may pairwise-accumulate while a naive Java
loop goes left to right, giving different last bits from identical inputs. So `i_sum_hex` is a **secondary
signal only**, at relative 1e-15, recorded explicitly in [`PARITY_REPORT.md`](PARITY_REPORT.md). The digests are what establish
bit-identity; the sum is a cheap cross-check that costs nothing.

Individual `mz` and `i` values are compared bit-exactly with no exception. Do not extend the sum exception to
them; that would defeat the gate.

**Make a digest failure actionable.** A mismatched 64-character hash says nothing on its own. When digests
differ, additionally compare `i_hex_first8` / `mz_hex_first8`: if those still match, the values are right at
the head and the fault is **ordering**, not decoding. That is exactly what the first-8 fields are in the
dump for — use them in the failure message rather than only for manual diagnosis.

### 2. What to assert, per fixture

Parameterize over every fixture with a dump — **16 of them**, listed in `ReaderParityIT.FIXTURES_WITH_DUMPS`,
which is the single source of truth for both the sweep and its per-fixture reader-only counts:

| Group | Fixtures |
|---|---|
| Real files | `small.mzML`, `small.mzXML`, `PlusRise.mgf`, `DP00570_F02.mzxml`, `DP00570_F02.mgf` |
| Original micro | `micro.mzML`, `micro.mzXML`, `micro.mgf` |
| RT unit | `micro_rtseconds.mzML` |
| Decode variants (C32) | `micro_p64.mzXML`, `micro_zlib.mzXML`, `micro_p64_zlib.mzXML`, `micro_nested.mzXML` |
| First precursor (C31) | `micro_multiprec.mzXML` |
| **Zero-intensity peaks (C36)** | **`micro_zeroint.mgf`** — added in Step 9 |
| **Differing MS1 scans (C37)** | **`micro_ms1var.mzML`** — added in Step 9 |

This started as *"the three original micro-fixtures"* and grew by seven as later corrections found decode and
metadata branches with no bit-identity check. `make spec-audit` now asserts that the count stated here, the
count in [`FIXTURES.md`](FIXTURES.md), the number of files in `goldens/loader-parity/` and `FIXTURES_WITH_DUMPS.size()` all
agree — they had drifted to three different numbers before that check existed.

> ⚠ **Correction C28 — the dumps are authoritative per SCAN, never for ORDER.**
> `dump_loader_parity.py` builds its `scans` list from `ms1_df` then `ms2_df`, so a dump holds **all MS1
> entries followed by all MS2 entries** — 229 then 687 on the Ewing file, not document order. Anything
> order-dependent must be re-derived from the file itself.
>
> Found the hard way in Step 7: `Ms1ScanDocumentOrderIT` originally derived its expected `ms1scan` chain
> from the dump and failed against a *correct* reader, assigning the last MS1 (913) to all 687 MS2 scans.
> It now walks the raw XML with a regex instead — which is better anyway, because that shares no code
> with the streaming walk, so agreement is a real cross-check rather than one bug appearing twice.
>
> Use the dumps for `peak_count`, `i_sum_hex`, digests and first-8 values, **keyed by `(mslevel, scan)`**
> (C32a — scan id alone collides with the MGF phantom). Never for "the Nth scan is…".

> ✅ **Resolved by C32: the six missing dumps are now generated.** Step 7 left `micro_p64.mzXML`,
> `micro_zlib.mzXML`, `micro_p64_zlib.mzXML` and `micro_nested.mzXML` pinned only by cross-fixture
> *equivalence* — zlib decodes bit-identically to uncompressed, nested row-for-row to flat. That cannot
> catch an error **common to both sides** of an equivalence pair, so the 64-bit, zlib and nested decode
> branches had no bit-identity check against Python at all.
>
> `dump_loader_parity.py` takes fixtures as argv and writes with `mtime=0` (idempotent), so no script
> change was needed:
>
> ```bash
> oracle/.venv/bin/python oracle/dump_loader_parity.py oracle/loader-parity \
>   fixtures/micro/micro_p64.mzXML fixtures/micro/micro_zlib.mzXML \
>   fixtures/micro/micro_p64_zlib.mzXML fixtures/micro/micro_nested.mzXML \
>   fixtures/micro/micro_multiprec.mzXML fixtures/micro/micro_rtseconds.mzML
> ```
>
> `micro_rtseconds.mzML` gains its **only** parity check — the seconds-side mzML RT rule was unit-tested
> only. `micro_multiprec.mzXML` gives C31's first-precursor rule a golden. `micro_multiprec.mzML` is
> deliberately excluded: a second `<precursor>` adds nothing for *peak* parity over the mzXML one, and C31
> is already pinned by `MultiPrecursorTest`.

> ✅ **Corrections C36 and C37 added two more, by the same route.** Both were found in Step 9 and both are
> the same failure shape as C32 — a rule the code got right with no fixture able to prove it:
>
> ```bash
> oracle/.venv/bin/python oracle/dump_loader_parity.py oracle/loader-parity \
>   fixtures/micro/micro_zeroint.mgf fixtures/micro/micro_ms1var.mzML
> ```
>
> `micro_zeroint.mgf` (**C36**) pins the rule that **MGF drops zero-intensity peaks while mzML/mzXML keep
> them** — a divergence no fixture contained, so a reader that "tidied up" either side would have passed
> everywhere. Its all-zero block reduces to a zero-peak scan and vanishes from MassQL's dataframe, giving
> exactly **1** reader-only scan.
>
> `micro_ms1var.mzML` (**C37**) is the only fixture whose two MS1 scans carry **different** peaks, so it is
> the only one that can distinguish an `MS1MZ` condition evaluated against the correct linked MS1 scan from
> one evaluated against the wrong scan. Every scan has peaks, so it has **0** reader-only scans.

> ⛔ **Two fixtures MUST be excluded from this sweep — MassQL cannot load them at all**
> (Correction C27c). `micro_nopolarity.mzXML` raises `KeyError: 'polarity'` and
> `micro_noprecursor.mzXML` raises `KeyError: 'precursorMz'`, both verified by execution. **No golden or
> dump can exist for either**, so a parity harness that globs `fixtures/micro/*.mzXML` will fail trying
> to load a dump that was never generated. Exclude them by name, with a comment saying why — they pin
> *our* contract in `MzxmlPolarityTest` / `MzxmlEdgeCaseTest`, and parity is not available.

> ⚠ **Correction C22 changes the shape of this test, and simplifies it.** There is no whole-file
> table to compare against any more — iterate the `SpectraStream` cursor and compare **scan by scan**
> as you go, calling `ScanView.materialize()` for the peak-level assertions. That matches the dump's
> own per-scan structure, and it means the parity suite itself never holds more than one scan, so it
> can run against arbitrarily large files.

> ⛔ **Correction C32(a) — key every comparison by `(mslevel, scan)`, NEVER by scan id alone.** MassQL
> synthesises an all-zero MS1 placeholder for MGF (C14/C24b) and **it is in the dumps**. Its scan id
> **collides with a real MS2 id** in 2 of the 3 MGF fixtures — `micro.mgf` phantom at **3**,
> `DP00570_F02.mgf` phantom at **625**, both also real MS2 ids. Keying by scan id silently compares a real
> MS2 scan against a synthetic row of zeros, and it passes or fails for reasons unrelated to decoding.

| Assertion | Detail |
|---|---|
| MS2 scan count | Exact, against `ms2_scan_count` |
| MS1 scan count | Exact for mzML/mzXML. ⛔ **For MGF, assert our count is `0`** — C32(b): every MGF dump reports `ms1_scan_count: 1`, which is MassQL's phantom placeholder, and our reader correctly omits it. Skip `mslevel == 1` dump entries entirely on MGF fixtures |
| Scan **ids** | Exact as a **set**, keyed by `(mslevel, scan)`. ⛔ **Not "in order, as a list"** — C32(e): that contradicted the C28 note below, since the dumps are level-grouped rather than document order. Still catches renumbering (including any msconvert artifact from Step 2) and MGF scan-id derivation errors ([Step 6](Tech_Step6.md) §2) |
| Per-scan peak count | Exact |
| Per-scan intensity + m/z arrays | **Bit-identical via `i_sha256` / `mz_sha256`** (§1) — the digest pins order as well as values |
| Per-scan first 8 `mz` and `i` | **Bit-identical** via `i_hex_first8` / `mz_hex_first8`. Cheap detector of an interleaving or byte-order error, and the diagnostic that separates "wrong values" from "wrong order" when a digest fails |
| Per-scan `rt` | **Bit-identical** against `rt_hex`. This is the assertion that catches all three RT-unit rules at once, and it requires the double-precision `scanRt` from [Step 5](Tech_Step5.md) §1 |
| Per-scan polarity | Exact (1 / 2 / 0) |
| Per-scan **`charge`**, **`ms1scan`**, **`precmz`** | ⛔ Exact on every **MS2** scan; `precmz` **bit-identical** against its hex. MS1 dump records carry none of the three — the generator emits them under `if level == "2"` because `ms1_df` has no such columns, which is a property of the reference's two-dataframe shape, not a gap. These are the precursor-metadata columns [Step 10](Tech_Step10.md) collates and [Step 12](Tech_Step12.md) compares, so a divergence here is a **reader** defect and must be caught as one |
| Per-scan intensity sum | `i_sum_hex` at relative **1e-15**, secondary only (§1) |

> ⛔ **Correction C32(c) — the dumps omit every zero-peak scan; our readers deliberately yield them.**
> The deltas are not small: **PlusRise.mgf is 34,513 reader scans against 21,942 dump entries**, and
> `micro.mzML` is 5 against 4 (its zero-peak MS1 at scan 4). MassQL's loaders `continue` on an empty
> intensity array, so its dataframe simply has no rows for those scans (C24b), while our readers yield them
> and let the engine filter (C27b). The reconciliation rule:
>
> ```
> for each dump entry, keyed by (mslevel, scan), skipping MGF mslevel==1:
>     a matching reader scan MUST exist, and every field above must match
> for each reader scan with NO dump entry:
>     peakCount MUST be 0
> assert the NUMBER of such reader-only scans equals a per-fixture expected value
>     PlusRise.mgf                                  = 12,571
>     small.mzML / small.mzXML / DP00570_F02.*      = 0
>     micro mzML/mzXML variants (zero-peak MS1 #4)  = 1
>     micro.mgf         (MS2-only: that MS1 never   = 0
>                        existed on our side)
>     micro_zeroint.mgf (all-zero block -> 0 peaks) = 1     <- C36
>     micro_ms1var.mzML (every scan has peaks)      = 0     <- C37
> ```
>
> The last three are why this cannot be written as a blanket `micro* = 1`: `micro.mgf` is MS2-only, so the
> zero-peak MS1 the mzML/mzXML variants contribute never existed on our side, and the two Step 9 fixtures
> land on opposite values for unrelated reasons. The authoritative per-fixture numbers live in
> `ReaderParityIT.FIXTURES_WITH_DUMPS` as the map's values.
>
> **Assert that count, do not merely tolerate reader-only scans** — otherwise a reader that dropped real
> spectra passes this gate silently, which is the exact failure mode the gate exists to catch.

> ⚠ **Correction C32 — this paragraph used to warn that the dumps hold `scan`/`ms1scan` as **strings**
> for mzXML and PlusRise (C12/C14). That is FALSE of the dumps.** `dump_loader_parity.py:78` already
> coerces on the way out — `int(scan) if str(scan).lstrip("-").isdigit() else str(scan)` — and every dump
> verified reads back a JSON **int**, including `small.mzXML`, `DP00570_F02.mzxml` and `PlusRise.mgf`.
> The advice ("compare numerically") was harmless, but the premise sends an implementer hunting for a
> problem that does not exist.
>
> The underlying C12/C14 string typing is real in MassQL's *dataframes* and is what
> `massql_query.py`'s int coercion fixes for the **goldens** ([Step 12](Tech_Step12.md)). It does not reach
> these dumps. Still parse defensively — `Integer.parseInt(String.valueOf(v))` — so a future generator
> change cannot break the harness silently.

Known expected counts, for a fast sanity read: `small.mzML` and `small.mzXML` → **48 spectra (14 MS1, 34 MS2)**;
`DP00570_F02.mzxml` → **916 scans (229 MS1, 687 MS2)**; `PlusRise.mgf` → **21,942 scans** loaded from 34,513 blocks, all MS2 (Correction C14 — MassQL drops ~12,571; assert the loaded count, not the block count).

> ⚠ **Correction C26 reverses what this paragraph used to say.** It required gitignored fixtures to
> **skip with a clear message** when absent. That is exactly how this suite came to prove nothing: the
> fixtures lived outside the repo, CI never had them, and *every* parity assertion skipped while the
> test counter stayed healthy — a skipped test still counts as one that ran. The guard is now the 90% coverage
> gate ([C43](Tech_Step_INDEX.md#c43)); a test that stops running shows up as lost coverage.

Fixtures are **committed to this repository** under `src/test/resources/`, and a missing fixture is a
**hard failure** — `Fixtures.require` throws, there is no skip path, and `FixturesContractTest` asserts
that. The two Ewing files remain gitignored for licence reasons only; `scripts/fetch-fixtures.sh`
retrieves them, CI runs it and caches the result, and their absence still **fails** with that command in
the message. CI additionally asserts the skipped-test count is **0**. See [`FIXTURES.md`](FIXTURES.md).

### 3. The instrument-attribute cross-check

A free, independent check on three of the five computed columns, with **no Python in the loop** — it validates
the collation arithmetic against the *instrument's own numbers*.

mzXML scan attributes carry `basePeakMz`, `basePeakIntensity` and `totIonCurrent`. Compare them against values
computed from our loaded table using [Step 5](Tech_Step5.md)'s reductions:

| Instrument attribute | Compare against |
|---|---|
| `basePeakMz` | `mz` at `argmax(i)` for that scan |
| `basePeakIntensity` | `max(i)` for that scan |
| `totIonCurrent` | `sum(i)` for that scan |

> ⛔ **Correction C30 — the tolerances this section prescribed are too tight on all three columns, and
> the reasoning behind them was wrong.** It asked for 1e-9 relative on `basePeakMz` and
> `basePeakIntensity` because they are "selected values, not accumulations", and called 1e-6 "generous"
> for `totIonCurrent`. **Measured across all 916 Ewing scans in Step 7:**
>
> | Attribute | Worst relative delta | At scan |
> |---|---|---|
> | `totIonCurrent` | **4.724e-06** | 654 |
> | `basePeakIntensity` | **4.895e-06** | 502 |
> | `basePeakMz` | **4.850e-06** | 344 |
>
> Every one of those exceeds the prescribed tolerance, so the check as specified fails on a correct
> reader. The premise was the mistake: the drift does **not** come from our accumulation order, it comes
> from the attributes themselves — the vendor wrote them as decimal text derived from `float32` values,
> so a *selected* value is no more exact than a summed one. That is why all three land at the same ~5e-6
> magnitude.
>
> **Use 1e-5 relative on all three** — just above the measurement, so a real regression cannot hide
> inside it. Already implemented in `InstrumentAttributeCrossCheckIT` (Step 7), which reproduces the
> table above to three digits against an independent Python measurement.

**A systematic mismatch is a bug, not drift**, and a small worst-case delta does not by itself prove the
absence of one. Assert the *shape*: differences must fall on both sides of zero roughly evenly. A
one-sided distribution means we are consistently dropping or double-counting, which a max-delta check
cannot see. Step 7's implementation does this by counting how many scans come out high versus low and
requiring both sides to be non-trivial. Report the distribution in [`PARITY_REPORT.md`](PARITY_REPORT.md), not just pass/fail.

Also assert that the check is **sharp**: within each scan the runner-up peak's m/z must be far enough
from the base peak's that a wrong `argmax` could not hide inside the tolerance. Otherwise `basePeakMz`
agreement is weaker evidence than it appears.

**11** Ewing scans carry `peaksCount="3"` — measured, and more than Step 2's [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md)
implies. Assert those by hand-written literal values, so at least a few assertions in the suite are
readable without tooling.

### 4. The parity report

[`PARITY_REPORT.md`](PARITY_REPORT.md) is a review artifact, and [Step 13](Tech_Step13.md)'s `make verify` table builds on it.
Per format, record:

- scan counts, expected vs actual;
- whether intensities were bit-identical, and for how many scans;
- any accepted divergence, with the reason and the exact tolerance used (there should be at most the §1 sum
  exception);
- for the Ewing cross-check: the delta distribution for each of the three attributes.

Answer [`SPIKE.md`](SPIKE.md) §11 question 1 here in one sentence — *"Do all three readers produce bit-identical decoded
intensities vs. the Python loader? If not, what tolerance becomes the contract?"* — and carry that sentence into
the README at [Step 13](Tech_Step13.md).

## Known traps

- **Loosening the comparison to get to green.** The whole value of this gate is that it is exact. If it fails, the
  reader is wrong. A tolerance added here to unblock Step 9 converts a found bug into a permanent unknown.
- **Comparing sums but not individual values.** Sums can agree by cancellation while individual peaks are wrong,
  and can disagree by accumulation order while every peak is right. Compare the **digests** (§1); the sum is a
  secondary signal only.
- **Keying by scan id alone.** The MGF phantom MS1's id collides with a real MS2 id in `micro.mgf` (3) and
  `DP00570_F02.mgf` (625), so the harness would compare a real spectrum against a row of zeros. Key by
  `(mslevel, scan)` — C32(a).
- **Treating reader-only scans as slack.** The dumps omit zero-peak scans and our readers yield them, so
  extras are expected — but their **count must be asserted** (PlusRise 12,571). Tolerating an unbounded
  number lets a reader that dropped real spectra pass the gate — C32(c).
- **Round-tripping the dumps through decimal.** Defeats bit-comparison. Parse the hex.
- **A vacuous pass.** If the dumps fail to load, a green suite means nothing. Assert the dump file loaded and
  that a minimum number of fixtures actually ran. Note that the *skip* half of this trap is now structurally
  impossible — `Fixtures.require` fails rather than skipping, and CI asserts zero skips (Correction C26) —
  but it is exactly the failure that went unnoticed for four steps, so do not reintroduce a conditional.
- **Blaming the reader for a conversion artifact.** If `small.mzXML`'s scan ids differ from `small.mzML`'s, Step 2
  recorded that; check [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) before touching reader code.
- **Comparing `rt` at float precision.** Requires `scanRt` as double ([Step 5](Tech_Step5.md) §1); a float
  comparison here passes while the Step 12 differential fails.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `ReaderParityIT` | IT | Every assertion in §2, `@ParameterizedTest` over all fixtures with dumps, keyed by `(mslevel, scan)`. Bit-exact on `mz`/`i` digests and on `rt`. Also folds in the old `ParityCoverageTest`: **every expected dump is present and was consumed**, and the reader-only scan count matches its per-fixture figure. |
| `ReaderParityHarnessTest` | unit | The comparison harness itself: hex parsing round-trips exactly; a value perturbed in its **last bit** must **fail**; a digest over a **reordered** array must **differ** (⚠ C32d — the inverse of the "multiset compares reordering equal" requirement this row used to state, which the digest format makes both impossible and undesirable); an unloadable dump must fail rather than silently pass. |
| `PeakOrderPreconditionTest` | unit | §1's precondition: our materialised m/z is strictly ascending, **and** each dump's `mz_hex_first8` is ascending — so a future unsorted fixture fails with the right diagnosis rather than as a mystery digest mismatch. |
| `InstrumentAttributeCrossCheckIT` | IT | §3. ✅ **Already built in [Step 7](Tech_Step7.md)** at C30's 1e-5 tolerances, with the no-bias and sharpness checks and the 11 `peaksCount="3"` scans. Not rebuilt here. |

`ReaderParityHarnessTest` is not ceremony: a bit-comparison harness that silently coerces to `float`, or a
digest routine that hashes a decimal rendering instead of the raw bits, produces a green gate that proves
nothing. Test the test — and prove it has teeth by breaking the input on purpose, as
`ZeroPeakMs1ChainTest` was proven in Step 7.

> ⚠ **`ParityCoverageTest` is dropped as a separate test (C32).** Its stated purpose — "so a
> skip-everything CI fails" — is now covered twice over by C26: `Fixtures.require` fails rather than
> skipping, `FixturesContractTest` asserts that, and CI asserts the skipped-test count is **0**. Its
> residual value (every expected dump present and consumed) folds into `ReaderParityIT`, where the count
> it guards actually lives.

### Renamed and folded test classes

Redirects for names this spec required, kept rather than deleted so the original requirement stays reviewable.
Read by `make spec-audit` check 4 (Correction **C38**), which fails the build when a completed step names a
test class that neither exists nor redirects — the phantom-name problem that let three Step 9 conditions go
untested while the table implied otherwise.

| Spec-era name | → Real home | Note |
|---|---|---|
| `ParityCoverageTest` | → `ReaderParityIT` | dropped as a separate class under **C32** (see the note above); the dump-count half of its purpose is additionally enforced outside the test suite by `make spec-audit` check 2, which compares the files on disk against `FIXTURES_WITH_DUMPS` and against every count stated in the docs |

## Done when

- [x] `mvn verify` green — **369 unit + 23 IT, 0 skipped**.
- [x] For **all three formats**: scan counts, scan ids (as a set keyed by `(mslevel, scan)`), per-scan peak
      counts, polarity, `rt`, and — on MS2 scans — `charge`, `ms1scan` and `precmz` all match the dumps
      exactly.
- [x] `mz` and `i` are **bit-identical** via `i_sha256` / `mz_sha256` on all **16** fixtures with a dump — no
      tolerance. The set now includes the four decode variants that previously had none.
- [x] The reader-only scan count is **asserted** per fixture, not tolerated: PlusRise **12,571**, micro
      **1** each, `small.*` and `DP00570_F02.*` **0**.
- [x] MGF MS1 count asserted as **0** against dumps that report 1 (MassQL's fabricated row — C33b).
- [x] The `i_sum_hex` exception documented in [`PARITY_REPORT.md`](PARITY_REPORT.md) at **1e-6**, with the corrected cause:
      **float32 accumulation dtype**, not ordering (C33c). Our float64 sum is exact; the tolerance absorbs
      the reference's error, not ours.
- [x] `PeakOrderPreconditionTest` green — asserts the precondition from **both** sides (our arrays and
      MassQL's own file order via `mz_hex_first8`), since checking only ours would be circular.
- [x] The Ewing cross-check still green at 1e-5, with delta distributions recorded and no systematic bias.
- [x] [`PARITY_REPORT.md`](PARITY_REPORT.md) written, with the per-format table and the §11 Q1 answer.
- [x] `ReaderParityHarnessTest` **demonstrated** to detect a single-ULP perturbation — proven by shifting one
      intensity with `Math.nextUp` and confirming `ReaderParityIT` fails on exactly the three mzML fixtures
      with an actionable message.
- [x] **⛔ GATE GREEN.** Two real reader bugs found and fixed: MGF `polarity` was 0 where MassQL emits 1
      (C33), and MGF `charge` ignored the file-level `CHARGE=` header, giving 1 for 583 of `DP00570_F02.mgf`'s
      625 blocks where MassQL gives 2 (C44). One stale dump was refreshed at the same time —
      `micro.mzXML.json.gz` predated that fixture gaining `precursorCharge="2"`.

## References

- [`SPIKE.md`](SPIKE.md) §6b layer 1 (*"Assert intensities bit-identical, not 'close' — same binary blob, same decode. A
  mismatch here means the decoder is wrong, and this is the cheapest place to learn that."*), §7 Step 2 item 2,
  §11 Q1
- [Step 2](Tech_Step2.md) §6 — the dump format
- [Step 6](Tech_Step6.md) §3 and §5, [Step 7](Tech_Step7.md) §3 — the decode rules under test
- [Step 5](Tech_Step5.md) §1 — the `scanRt` double-precision requirement
