# Reader rules

The per-format rule table, and the single reference for Tech_Step6 (MGF, mzML) and Tech_Step7 (mzXML).

**`massql/msql_fileloading.py` is the authority**, not the format specifications and not pyteomics' API
docs — field names and units are undocumented there, so the source settles them. Every value these
readers produce must match what that file produces, because Step 8 asserts bit-identity and the goldens
are whatever MassQL computed. Where a format spec and MassQL disagree, **MassQL wins**.

---

## The three formats at a glance

| | MGF | mzML | mzXML |
|---|---|---|---|
| MS levels | **MS2 only** | 1 and 2 | 1 and 2 |
| Byte order | n/a (text) | **little**-endian | **big**-endian (`"network"`) |
| Array layout | n/a | two separate arrays | **one interleaved** m/z–intensity array |
| Precision | n/a | per-array cvParam | one `precision` attribute |
| Compression | n/a | zlib + **6 Numpress variants** | zlib or none |
| RT rule | `RTINSECONDS`÷60, absent → **0.0** | ÷60 **only if** unit is seconds | **always** ÷60 |
| `ms1scan` | hardcoded **0** | document order | document order |
| Reader | hand-written | hand-written walk + vendored decoder | hand-written (C23) |

**Three different RT rules is the trap.** A silent 60× error in any one of them passes every test for the
other two formats. `MzmlRtUnitTest` pins both mzML directions with a fixture each.

---

## Shared rules

**`ms1scan` is derived by document order — never from the file's own linkage.**

```
previous_ms1_scan = 0
for each spectrum in document order:
    if peak_count == 0: continue                       # <-- Correction C27(b). NOT optional.
    if mslevel == 1:    previous_ms1_scan = this scan id
    else:               ms1scan = previous_ms1_scan
```

> ⚠ **The `peak_count == 0` guard is the easiest line here to omit, and omitting it is silent.**
> Both loaders open with `if len(spectrum["intensity array"]) == 0: continue`
> (`msql_fileloading.py:559` mzML, `:421` mzXML) and that `continue` runs **before**
> `previous_ms1_scan` is assigned — so an empty MS1 is invisible to the chain and the next MS2 links
> to the MS1 *before* it. Verified against MassQL's own loader on `micro.mzML`: scan 5 →
> `ms1scan` **2**, not the empty MS1 at scan 4.
>
> Neither real mzXML fixture contains a single zero-peak scan, and the micro golden covers only scans
> 1 and 3, so **nothing caught this until C27**. `ZeroPeakMs1ChainTest` now does — with the guard
> removed it reports "Got 4 — expected 2". Take `peak_count` from `defaultArrayLength` (mzML) or
> `peaksCount` (mzXML) so the guard costs no decode.
>
> **The scan is still yielded.** Only the linkage skips it — same split as MGF, where all 34,513
> blocks including the 12,571 empty ones are yielded and the engine filters.

MassQL reads neither mzML's `spectrumRef` nor mzXML's `precursorScanNum` — the two attribute names appear
**zero times** in all 892 lines of `msql_fileloading.py`. Consequences:

- An MS2 scan before any MS1 gets `ms1scan = 0` → null downstream. **That is where the 0 sentinel comes
  from.**
- A reader that "correctly" resolves `spectrumRef` disagrees with MassQL whenever the reference is not the
  immediately preceding MS1 scan. For simple DDA they coincide, which is why `small.mzML` **cannot** detect
  the difference; the Ewing mzXML (zero `precursorScanNum`) is the only fixture that can.
- This rule is also what makes streaming possible: the linked MS1 scan is always the most recent one, so
  the engine retains exactly one.

**The `0` sentinels stay raw in the readers.** `precmz`, `ms1scan` and `charge` carry MassQL's 0 for "not
recorded". Tech_Step10 converts 0 → null for those three only, and **never for `rt`** — `0.0` is a real
retention time. Converting early would destroy the distinction between "absent" and "already converted".

**Levels > 2** are skipped and reported through `SpectraStream.diagnostics()`.

**Malformed input throws `MassqlException` with no partial result.** A reader that returns 40 of 48 scans
is worse than one that fails: the shortfall surfaces later as an inexplicable filtering bug.

---

## MGF

Specification: `_load_data_mgf_pyteomics` (`msql_fileloading.py:155-244`).

| Field | Rule |
|---|---|
| `mslevel` | Always **2**. MGF is an MS2-only peak list; the MS1 table is empty |
| `precmz` | First token of `PEPMASS=`; a second token is precursor intensity and is ignored |
| **`charge`** | `CHARGE=` with a trailing `+`/`-` stripped. **Absent → `1`, not 0** (Correction C6) |
| **`rt`** | `RTINSECONDS`÷60. **Absent → `0.0`, not null** |
| **`scan`** | `SCANS=` when present, else the **1-based block index** (Correction C7) |
| `ms1scan` | Hardcoded **0** (`:394`) |
| `polarity` | **Not read** on the live path (Correction C8) → 0 |

> **⚠ MGF `charge` is never null.** SPIKE.md §3 says "null if absent"; the live loader uses
> `params.get('charge', [1])` with `except: charge = 1`. Since only `0` is null-converted, an absent
> `CHARGE=` is indistinguishable from a genuine 1+. The golden agrees: `{1: 653, 2: 10, 3: 1}`, zero nulls.

**Zero-peak blocks are real spectra and must be yielded.** 12,571 of `PlusRise.mgf`'s 34,513 blocks have no
peak lines. MassQL's dataframe simply has no *rows* for them, which is why it reports **21,942** unique
scans — this explains the "12,571 dropped spectra" left unexplained in Correction C14. Nothing is dropped.

**MassQL synthesises a 1-row all-zero MS1 placeholder for MGF** (mz=0, i=0, scan=1). It is not a peak, and
our reader correctly omits it — so the parity dump's `758,545` total is `758,544` real peaks plus that
phantom row. Step 8's comparison must exclude it.

Parsing tolerances: blank lines, `#`/`;`/`!` comments, CRLF, space- or tab-separated peaks, and a
file-level `COM=`/`CHARGE=` preamble before the first `BEGIN IONS` (both real fixtures have one).

---

## mzML

Hand-written XML walk over the vendored decode layer (Correction C21). `XMLStreamReaderImpl` is
instantiated **directly** — the JDK's `XMLInputFactory` uses `ServiceLoader`, banned by constraint 1.

| Field | Accession / rule |
|---|---|
| **`scan`** | `int(id.replace("scanId=","").split("scan=")[-1])` (`:575`) — the **LAST** `scan=` segment. No `scan=` → `MassqlException` naming the id (MassQL raises `ValueError`) |
| `mslevel` | `MS:1000511` |
| **`rt`** | `MS:1000016`, ÷60 **only if** `unitName="second"` / `unitAccession="UO:0000010"` (`:564-571`). `small.mzML` declares `minute` → **pass through** |
| `precmz` | `MS:1000744`, absent → 0 |
| `charge` | `MS:1000041`, absent → 0 |
| `polarity` | `MS:1000130` → 1, `MS:1000129` → 2, else 0 |
| arrays | `MS:1000514` m/z, `MS:1000515` intensity; bit length and compression resolve against the `MzMLBitLength` / `MzMLCompressionType` enums |

**32-bit widening.** The vendored `MzMLPeaksDecoder` already does it correctly:
`data[i] = Float.intBitsToFloat(dis.readInt())` into a `double[]` — exactly `(double)(float)raw`, matching
pyteomics. **Do not re-implement it, and do not "fix" it to read 8 bytes.**

> **Three gotchas in the vendored API**, each hit during implementation:
> - `MzMLCV` does **not** define the bit-length or compression accessions — they live on the enums.
> - Accessor naming is inconsistent: `MzMLBitLength.getValue()` but `MzMLArrayType.getAccession()`.
> - **`setBitLength(String)` / `setCompressionType(String)` store `null` on an unrecognised accession**
>   rather than ignoring it. Calling both for every cvParam makes the compression param clobber the bit
>   length, surfacing as an NPE deep inside the decoder. Resolve the accession explicitly first.

**javolution logs to stdout.** `XMLStreamReaderImpl` calls `LogContext.info(...)` whenever it grows its
character buffer, and an mzML `<binary>` element triggers that on essentially every real file — 6 lines on
`small.mzML`. Readers are **SDK** code, so the governing rule is `DEPENDENCY_POLICY.md` constraint 2 —
**the SDK logs nothing at all**, to either stream; diagnostics go back through
`SpectraStream.diagnostics()`. (Secondarily, at the CLI layer, stdout output would also corrupt the Java
CLI's JSON payload — a consequence, not the reason. Correction C25a.) `JavolutionQuiet` raises
`LogContext.LEVEL` above `INFO`; `StdoutCleanlinessTest` guards both streams.

---

## mzXML (Tech_Step7)

Hand-written, vendoring nothing new (Correction C23).

| Field | Rule |
|---|---|
| **`scan`** | `int(num)`. ⚠ pyteomics returns `spectrum["id"]` as a **`str`** (`'1'`), which is the root cause of Correction C12 — `previous_ms1_scan` then propagates a string into `ms1scan`. Parse to int in Java |
| `mslevel` | `msLevel` attribute. **Empty or absent → the scan is dropped** (see below) |
| **`rt`** | ISO-8601 duration, **always** → minutes. `PT90S` → 1.5, `PT1.38S` → 0.023, `PT1M30S` → 1.5, `PT1H` → 60. pyteomics has already converted, and MassQL uses the value as-is |
| `precmz` | `precursorMz[0]`, i.e. the text of the first `<precursorMz>` element. Absent on an MS2 → **0** (non-parity, see below) |
| **`charge`** | `precursorCharge` attribute, absent → **`0`** (`:451`). ⚠ **Unlike MGF, where absent is `1`** (C6). Three formats, three charge defaults |
| `polarity` | `"+"` → 1, `"-"` → 2, present-but-other → 0. **Absent → 0 is non-parity** (see below) |
| precision | `precision="32"` or `"64"`, one attribute for the whole interleaved array |
| compression | `compressionType` absent or `"none"` → uncompressed, else zlib. **No Numpress, ever** |
| byte order | `byteOrder="network"` = big-endian |
| `precursorScanNum` | **Never read**, even when present — `small.mzXML` has 34 of them and they must be ignored |

**`msLevel` empty or absent → the scan is dropped, contributing zero rows** (Correction C27a, and the
resolution of this spec's long-standing open item). pyteomics converts `msLevel=""` to **`None`**;
MassQL tests `mslevel == 1` and `mslevel == 2` (`:434`, `:450`), so `None` matches neither branch and the
spectrum is silently skipped. **Not** a default of 1, **not** a diagnostic, **not** a failure. Measured on
`fixtures/edge/empty_msLevel_tag.mzXML`: 8 of its 10 scans are `msLevel=""` and only scans 4 and 8
survive.

### Two cases where MassQL crashes, so no golden can exist (Correction C27c)

Both of these read a dict key **unguarded**, so the absent case raises `KeyError` and the reference
implementation produces no output at all. Our behaviour is therefore *our choice*, and must be labelled
as such — a test asserting it is pinning our contract, **not** parity.

| Case | MassQL | Ours | Why |
|---|---|---|---|
| `polarity` attribute absent | `spec["polarity"]` → **`KeyError`** (`:519`) | **0** | The `polarity = 0` initialiser only ever covers *present but neither `+` nor `-`* — that case **is** parity. Absent is not reachable in a successful MassQL run |
| MS2 with no `<precursorMz>` | `spectrum["precursorMz"][0]["precursorMz"]` → **`KeyError`** (`:450`) | **`precmz = 0`** | Consistent with mzML, where an absent `MS:1000744` is already 0, and with the 0-sentinel meaning "not recorded" (Step 10 maps it to null). Throwing would make mzXML stricter than mzML for the same missing field |

> The absent-`polarity` case is easy to believe is parity, because `empty_msLevel_tag.mzXML` contains 8
> scans with no `polarity` attribute and MassQL reads it without error. It does not crash only because
> those same 8 scans have `msLevel=""` and are dropped **before** the polarity call. Change the
> `msLevel` and it raises.

**Both scan layouts must work.** `small.mzXML` is **flat** (`<scan>` depth 1); the Ewing file **nests**
MS2 inside its parent MS1 (depth 2). A flat-only walk passes on `small.mzXML` and silently mis-associates
every nested scan. Measured, not assumed.

~~**Open item:** the behaviour for a missing/empty `msLevel`…~~ — **resolved, Correction C27a.** The scan
is dropped and contributes zero rows; see the `msLevel` rule above.

---

## Verified against the oracle

`small.mzML`, read by `MzmlReader` and compared to
`src/test/resources/goldens/loader-parity/small.mzML.json.gz` (relocated into the repo by C26):

| | reader | dump |
|---|---|---|
| scans / MS1 / MS2 | 48 / 14 / 34 | 48 / 14 / 34 |
| peaks | **305,214** | 305,214 |
| per-scan peak counts | all match | — |
| scan 3 `rt` | `0.011218333333333334` (bit-exact) | golden |
| `ms1scan` for scans 3, 10, 17, 24, 37, 44 | 2, 9, 16, 23, 36, 43 | golden |

`PlusRise.mgf`: 34,513 scans, **758,544** real peaks, streamed inside a **48 MB heap** — the proof that
retained memory is bounded by scan size, not file size (C22).
