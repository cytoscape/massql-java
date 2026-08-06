# Reader parity report — integration layer 1 (Tech_Step8, the gate)

**Verdict: GREEN.** All three readers decode **bit-identically** to MassQL's own Python loader across
**16 fixtures**, with no tolerance on any individual `mz`, `i` or `rt` value.

> **[`SPIKE.md`](SPIKE.md) §11 Q1 — "Do all three readers produce bit-identical decoded intensities vs. the Python
> loader? If not, what tolerance becomes the contract?"**
>
> **Yes — all three formats are bit-identical on every peak, verified by SHA-256 over each scan's m/z and
> intensity arrays. No tolerance is part of the contract for peak values. The only tolerance anywhere in this
> gate is 1e-6 on a per-scan intensity *sum*, and that absorbs error in the *reference*, not in ours: the
> dump records `pandas.sum()` over a `float32` column, while our float64 accumulation reproduces the true
> sum exactly.**

Carry that paragraph into the README at [Step 13](Tech_Step13.md).

---

## Per-fixture results

Every row: MS1/MS2 counts, per-scan peak counts, per-scan `polarity`, per-scan `rt` (bit-identical), and
SHA-256 over both peak arrays — all matching. Keyed by `(mslevel, scan)`, never scan alone (C32a).

| Fixture | Format | Scans | MS1 | MS2 | Peaks | Reader-only | Digests |
|---|---|---|---|---|---|---|---|
| `small.mzML` | mzML | 48 | 14 | 34 | 305,214 | 0 | ✅ |
| `small.mzXML` | mzXML | 48 | 14 | 34 | 305,214 | 0 | ✅ |
| `DP00570_F02.mzxml` | mzXML | 916 | 229 | 687 | 308,425 | 0 | ✅ |
| `DP00570_F02.mgf` | MGF | 625 | 0 | 625 | 107,178 | 0 | ✅ |
| `PlusRise.mgf` | MGF | 34,513 | 0 | 34,513 | 758,544 | **12,571** | ✅ |
| `micro.mzML` | mzML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_rtseconds.mzML` | mzML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_ms1var.mzML` | mzML | **4** | 2 | 2 | **8** | **0** | ✅ |
| `micro.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_p64.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_zlib.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_p64_zlib.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_nested.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro_multiprec.mzXML` | mzXML | 5 | 2 | 3 | 10 | 1 | ✅ |
| `micro.mgf` | MGF | 3 | 0 | 3 | 7 | 0 | ✅ |
| `micro_zeroint.mgf` | MGF | 3 | 0 | 3 | **4** | 1 | ✅ |

**"Reader-only" scans are expected and their count is *asserted*, not tolerated.** MassQL's loaders
`continue` on an empty intensity array, so its dataframes hold no rows for zero-peak scans; our readers yield
them and let the engine filter (C24b, C27b). PlusRise's 12,571 are its peak-less blocks; each micro mzML/mzXML
variant's 1 is the deliberate zero-peak MS1 at scan 4. Asserting the number is what stops a reader that
*dropped* real spectra from passing — tolerating an unbounded count would defeat the gate.

Three rows sit outside that pattern, each for its own reason: `micro.mgf` is MS2-only so the zero-peak MS1
never existed on our side; **`micro_zeroint.mgf`** reaches 1 by a different route — its all-zero block is
dropped *peak by peak* under **C36**, leaving a scan with 0 peaks rather than one that was empty in the file;
and **`micro_ms1var.mzML`** has 4 scans where every other micro fixture has 5, because it drops the zero-peak
MS1 entirely and gives its two MS1 scans **differing peaks** instead — the property **C37** needed and no other
fixture had.

**MGF MS1 counts are 0 on our side against dumps reporting 1.** That single dump entry is MassQL's fabricated
MS1 row, which our readers correctly omit — MGF has no survey scans. See C33(b): it is all-zero on
`PlusRise.mgf` but a **duplicate of the last MS2 peak** on the two pyteomics-loaded MGFs, which is also why
its scan id collides with a real one.

## Accepted divergence — exactly one, and it is in the reference

| What | Tolerance | Why |
|---|---|---|
| Per-scan intensity **sum** (`i_sum_hex`) | relative **1e-6** | The dump records `pandas .sum()` over a **`float32`** column (`dump_loader_parity.py:81`), i.e. a float32 accumulation. On `small.mzML` MS1 scan 1 that yields `69381840.0` where the true sum is `69381842.11895752` — relative error **3.05e-08**. Our float64 accumulation reproduces the true value **exactly** (measured difference `0.000e+00`), so this tolerance absorbs the reference's float32 epsilon (~1.2e-7) and none of ours. |

Nothing else. Individual `mz`, `i` and `rt` are compared with **no** tolerance.

> Tech_Step8 §1 originally attributed this to numpy's *pairwise accumulation* and prescribed 1e-15. That
> diagnosis was wrong and the tolerance unachievable on correct code — the cause is **dtype**, not ordering
> (Correction C33c).

## How bit-identity is established

**SHA-256 over each array**, packed big-endian IEEE754 double — not a multiset, and not a value-by-value
loop. This is **strictly stronger** than a multiset comparison because it pins the array's **order** too
(C32d; the dumps store digests, so a multiset comparison was never implementable).

Order-sensitivity is only *valid* because our array order equals MassQL's file order.
`SpectrumTableBuilder` sorts a scan by m/z when it is unsorted, which would silently change the digest —
so `PeakOrderPreconditionTest` asserts the precondition from both sides: our materialised m/z is
non-decreasing, **and** each dump's `mz_hex_first8` (MassQL's own file order) is non-decreasing. Measured:
**zero scans descend**, on any fixture. A future unsorted fixture fails there, saying why, instead of
presenting as a decode bug.

**The gate was proven to have teeth.** With one intensity per mzML scan shifted by a single ULP
(`Math.nextUp`), `ReaderParityIT` fails on exactly the three mzML fixtures with:

```
small.mzML MS1 scan 1: intensity array is NOT bit-identical.
  First divergence at index 0: want 0.0 (0x0.0p+0), got 4.9E-324 (0x0.0000000000001p-1022).
micro.mzML MS2 scan 1: intensity array is NOT bit-identical.
  First divergence at index 0: want 250.0 (0x1.f4p+7), got 250.00000000000003 (0x1.f400000000001p7).
```

A one-ULP change is caught, and the message names the index, both values in hex, and the likely cause. When
digests differ but the leading eight values match, it reports *"suspect ORDERING, not decoding"* instead —
which is what the dump's `*_hex_first8` fields exist for.

## Coverage note — what is *not* covered by parity, and why

| Fixture | Status |
|---|---|
| `micro_nopolarity.mzXML` | **No dump can exist.** MassQL raises `KeyError: 'polarity'` (C27c), verified by execution. Pinned as *our* contract in `MzxmlPolarityTest`, explicitly labelled non-parity |
| `micro_noprecursor.mzXML` | **No dump can exist.** MassQL raises `KeyError: 'precursorMz'` (C27c). Pinned in `MzxmlEdgeCaseTest` |
| `micro_multiprec.mzML` | Deliberately excluded — a second `<precursor>` adds nothing over the mzXML twin for *peak* parity, and C31's first-precursor rule is pinned by `MultiPrecursorTest` |
| `fixtures/edge/empty_msLevel_tag.mzXML` | No dump — 8 of its 10 scans are dropped on `msLevel` (C27a). Pinned in `MzxmlEdgeCaseTest`, which is also our only 64-bit + zlib decode of a file we did not generate |

The four decode variants (`micro_p64`, `micro_zlib`, `micro_p64_zlib`, `micro_nested`) **were** in this list
until C32: Step 7 had pinned them only by cross-fixture *equivalence*, which cannot catch an error common to
both sides of a pair. Dumps were generated for them here, so the 64-bit, zlib and nested branches now carry
real bit-identity against Python.

## The instrument cross-check — no Python in the loop

An independent check that depends on no golden, no pyteomics and no MassQL: the Ewing file's own
`totIonCurrent`, `basePeakIntensity` and `basePeakMz` scan attributes, computed by the acquisition software
from the same peaks it then encoded.

| Attribute | Worst relative delta | At scan |
|---|---|---|
| `totIonCurrent` | 4.724e-06 | 654 |
| `basePeakIntensity` | 4.895e-06 | 502 |
| `basePeakMz` | 4.850e-06 | 344 |

Tolerance **1e-5** on all three (C30 — the spec's 1e-9/1e-6 were unachievable, because the vendor wrote these
attributes as decimal text derived from `float32`, so a *selected* value is no more exact than a summed one).

**No systematic bias.** The differences fall on both sides of zero, asserted rather than assumed — a
max-delta check cannot see a one-sided distribution, which is what consistently dropping or double-counting a
peak would produce. The check is also asserted to be **sharp**: the runner-up peak's m/z is far enough from
the base peak's that a wrong `argmax` could not hide inside the tolerance.

These figures reproduce an independent Python measurement to three digits.

## What this gate caught

**One real reader bug, before any query logic existed.** `MgfReader.polarity()` returned `0`; MassQL
hardcodes **1** for every MGF row (C33). It would have failed the Step 12 differential on the polarity column
for *every* MGF row, three steps from its cause, looking like a collation bug. `MgfReaderTest` had been
*asserting* the wrong value, so the unit suite was defending it — only the golden could have known.

That is the case for running layer 1 before layer 2, made concrete.
