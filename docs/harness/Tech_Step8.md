# Tech Step 8 — Reader parity (integration layer 1)

> **⛔ THIS STEP IS A GATE.** If decoded intensities are not bit-identical to the Python loader's, the decoder is
> wrong and every number produced downstream measures noise. **Do not proceed to [Step 9](Tech_Step9.md) until
> this is green.** Do not loosen the comparison to make it pass.

## Goal

Prove that all three Java readers load exactly the same peaks, with exactly the same bits, as MassQL's own Python
loader — before any query logic is written.

## Prerequisites

| Step | Why |
|---|---|
| [Step 2](Tech_Step2.md) | Provides `oracle/loader-parity/*.json` — the per-scan peak counts and intensity sums dumped from MassQL's own loaded tables, with floats as hex. This step's entire input. |
| [Step 6](Tech_Step6.md) | MGF and mzML readers. |
| [Step 7](Tech_Step7.md) | mzXML reader. |

## Context

`SPIKE.md` §6b puts this first among the four integration layers, and §7 Step 2 orders it explicitly: *"The three
readers, then integration layer 1 (reader parity) — before any query runs. If intensities aren't bit-identical to
Python's, the decoder is wrong and everything downstream is measuring noise."*

It is the cheapest place in the whole spike to learn that a decoder is wrong. A byte-order or float-precision
mistake found here is a one-line fix; the same mistake found at [Step 12](Tech_Step12.md) presents as a handful of
mismatched result rows and gets misdiagnosed as a filtering or collation bug.

It is a separate spec from the readers because a gate that lives inside another step's test suite gets treated as
one more failing test rather than as a stop condition.

Governing sections: `SPIKE.md` §6b layer 1, §7 Step 2 item 2, §11 Q1.

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
| `src/test/java/…/io/ReaderParityIT.java` | The parameterized parity suite |
| `src/test/java/…/io/InstrumentAttributeCrossCheckIT.java` | The no-Python check |
| `src/test/resources/loader-parity/` | The dumps, copied from `oracle/loader-parity/` |
| `docs/PARITY_REPORT.md` | Per-format results table; the artifact the reviewer reads |

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

**One deliberate exception.** A per-scan intensity **sum** is an accumulation, and floating-point addition is not
associative: Python's `numpy.sum` may pairwise-accumulate while a naive Java loop accumulates left to right,
giving different last bits from identical inputs. Two acceptable resolutions, in order of preference:

1. **Compare the multiset of individual intensities instead of the sum** — this is what actually establishes
   bit-identity, and it sidesteps accumulation order entirely. Prefer this.
2. If comparing sums, match the accumulation order to numpy's, or allow a relative tolerance of 1e-15 **on sums
   only** and record the exception explicitly in `PARITY_REPORT.md`.

Individual `mz` and `i` values are compared bit-exactly with no exception. Do not extend the sum exception to
them; that would defeat the gate.

### 2. What to assert, per fixture

Parameterize over every fixture with a dump: `small.mzML`, `small.mzXML`, `PlusRise.mgf`,
`DP00570_F02.mzxml`, `DP00570_F02.mgf`, and the three micro-fixtures.

> ⚠ **Correction C22 changes the shape of this test, and simplifies it.** There is no whole-file
> table to compare against any more — iterate the `SpectraStream` cursor and compare **scan by scan**
> as you go, calling `ScanView.materialize()` for the peak-level assertions. That matches the dump's
> own per-scan structure, and it means the parity suite itself never holds more than one scan, so it
> can run against arbitrarily large files.

| Assertion | Detail |
|---|---|
| MS1 scan count | Exact |
| MS2 scan count | Exact |
| Scan **ids**, in order | Exact, as a list. Catches renumbering — including any introduced by the msconvert conversion in Step 2 — and MGF scan-id derivation errors ([Step 6](Tech_Step6.md) §2) |
| Per-scan peak count | Exact |
| Per-scan intensity multiset | **Bit-identical** (§1) |
| Per-scan first `mz` | **Bit-identical** — cheap detector of an interleaving or byte-order error |
| Per-scan `rt` | **Bit-identical** against the dumped value. This is the assertion that catches all three RT-unit rules at once, and it requires the double-precision `scanRt` from [Step 5](Tech_Step5.md) §1 |
| Per-scan polarity | Exact (1 / 2 / 0) |

⚠ **The dumps record the loader's RAW types, which are not always the SDK's.** `dump_loader_parity.py` reads
`ms2_df` directly rather than going through `massql_query.py`, so it captures MassQL's own dtypes — and for
**mzXML** those include `scan` and `ms1scan` as **strings** (Correction C12), as does `scan` for
`PlusRise.mgf` (Correction C14). Java produces ints in both cases, correctly. **Compare these fields
numerically, not by type or string equality**, or every mzXML and PlusRise assertion fails for a reason that
has nothing to do with decoding. The `massql_query.py` int coercion fixes the *goldens*
([Step 12](Tech_Step12.md)), not these dumps.

Known expected counts, for a fast sanity read: `small.mzML` and `small.mzXML` → **48 spectra (14 MS1, 34 MS2)**;
`DP00570_F02.mzxml` → **916 scans (229 MS1, 687 MS2)**; `PlusRise.mgf` → **21,942 scans** loaded from 34,513 blocks, all MS2 (Correction C14 — MassQL drops ~12,571; assert the loaded count, not the block count).

> ⚠ **Correction C26 reverses what this paragraph used to say.** It required gitignored fixtures to
> **skip with a clear message** when absent. That is exactly how this suite came to prove nothing: the
> fixtures lived outside the repo, CI never had them, and *every* parity assertion skipped while the
> test counter stayed healthy (surefire counts skips inside "Tests run").

Fixtures are **committed to this repository** under `src/test/resources/`, and a missing fixture is a
**hard failure** — `Fixtures.require` throws, there is no skip path, and `FixturesContractTest` asserts
that. The two Ewing files remain gitignored for licence reasons only; `scripts/fetch-fixtures.sh`
retrieves them, CI runs it and caches the result, and their absence still **fails** with that command in
the message. CI additionally asserts the skipped-test count is **0**. See `docs/FIXTURES.md`.

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

Expect **minor float drift on `totIonCurrent`** — the instrument's own value was computed by the vendor's
software in unknown precision and order — so compare it with a relative tolerance (1e-6 is generous and still
diagnostic). `basePeakMz` and `basePeakIntensity` are selected values, not accumulations, and should match much
more tightly; hold them to 1e-9 relative.

**A systematic mismatch is a bug, not drift.** If every scan is off by a consistent factor or offset, that is a
decoder or reduction error. Report the distribution of deltas in `PARITY_REPORT.md`, not just pass/fail — a
uniform tiny scatter around zero is healthy; a bias is not.

Some Ewing scans carry `peaksCount="3"` (noted in Step 2's `CONVERSION_NOTES.md`). Assert those by hand-written
literal values, so at least a few assertions in the suite are readable without tooling.

### 4. The parity report

`docs/PARITY_REPORT.md` is a review artifact, and [Step 13](Tech_Step13.md)'s `make verify` table builds on it.
Per format, record:

- scan counts, expected vs actual;
- whether intensities were bit-identical, and for how many scans;
- any accepted divergence, with the reason and the exact tolerance used (there should be at most the §1 sum
  exception);
- for the Ewing cross-check: the delta distribution for each of the three attributes.

Answer `SPIKE.md` §11 question 1 here in one sentence — *"Do all three readers produce bit-identical decoded
intensities vs. the Python loader? If not, what tolerance becomes the contract?"* — and carry that sentence into
the README at [Step 13](Tech_Step13.md).

## Known traps

- **Loosening the comparison to get to green.** The whole value of this gate is that it is exact. If it fails, the
  reader is wrong. A tolerance added here to unblock Step 9 converts a found bug into a permanent unknown.
- **Comparing sums but not individual values.** Sums can agree by cancellation while individual peaks are wrong,
  and can disagree by accumulation order while every peak is right. Compare the multiset (§1).
- **Round-tripping the dumps through decimal.** Defeats bit-comparison. Parse the hex.
- **A vacuous pass.** If the dumps fail to load, a green suite means nothing. Assert the dump file loaded and
  that a minimum number of fixtures actually ran. Note that the *skip* half of this trap is now structurally
  impossible — `Fixtures.require` fails rather than skipping, and CI asserts zero skips (Correction C26) —
  but it is exactly the failure that went unnoticed for four steps, so do not reintroduce a conditional.
- **Blaming the reader for a conversion artifact.** If `small.mzXML`'s scan ids differ from `small.mzML`'s, Step 2
  recorded that; check `CONVERSION_NOTES.md` before touching reader code.
- **Comparing `rt` at float precision.** Requires `scanRt` as double ([Step 5](Tech_Step5.md) §1); a float
  comparison here passes while the Step 12 differential fails.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `ReaderParityIT` | IT | Every assertion in §2, `@ParameterizedTest` over all fixtures with dumps. Bit-exact on individual `mz`/`i`/`rt`. |
| `ReaderParityHarnessTest` | unit | The comparison harness itself: hex parsing round-trips exactly; a deliberately-perturbed value in the last bit **fails**; the multiset comparator detects a reordering-only difference as equal and a value difference as unequal. Guards against a harness that always passes. |
| `InstrumentAttributeCrossCheckIT` | IT | §3, including the hand-written `peaksCount="3"` literals. |
| `ParityCoverageTest` | unit | At least the committed fixtures (`small.mzML`, `small.mzXML`, `PlusRise.mgf`, 3 micro) have dumps present and ran — so a skip-everything CI fails. |

`ReaderParityHarnessTest` is not ceremony: a bit-comparison harness that silently coerces to `float`, or a
multiset comparator with a broken `equals`, produces a green gate that proves nothing. Test the test.

## Done when

- [ ] `mvn verify` green.
- [ ] For **all three formats**: scan counts, scan ids, per-scan peak counts, polarity and `rt` all match the
      Step 2 dumps exactly.
- [ ] Individual `mz` and `i` values are **bit-identical** across every committed fixture — no tolerance.
- [ ] Any sum-comparison exception is documented in `PARITY_REPORT.md` with its exact tolerance, or avoided
      entirely by multiset comparison.
- [ ] The Ewing cross-check runs, and its delta distributions are recorded and show no systematic bias.
- [ ] `docs/PARITY_REPORT.md` exists with the per-format table and the one-sentence answer to §11 Q1.
- [ ] `ReaderParityHarnessTest` demonstrates the harness detects a single-bit perturbation.

## References

- `SPIKE.md` §6b layer 1 (*"Assert intensities bit-identical, not 'close' — same binary blob, same decode. A
  mismatch here means the decoder is wrong, and this is the cheapest place to learn that."*), §7 Step 2 item 2,
  §11 Q1
- [Step 2](Tech_Step2.md) §6 — the dump format
- [Step 6](Tech_Step6.md) §3 and §5, [Step 7](Tech_Step7.md) §3 — the decode rules under test
- [Step 5](Tech_Step5.md) §1 — the `scanRt` double-precision requirement
