# Reader rules

The per-format rule table for the MGF, mzML and mzXML readers.

**`massql/msql_fileloading.py` is the authority**, not the format specifications and not pyteomics' API
docs — field names and units are undocumented there, so the source settles them. Every value these
readers produce must match what that file produces, because the parity gate asserts bit-identity and the goldens
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
| Reader | hand-written | hand-written walk + vendored decoder | hand-written  |

**Three different RT rules is the trap.** A silent 60× error in any one of them passes every test for the
other two formats. `MzmlRtUnitTest` pins both mzML directions with a fixture each.

---

## Shared rules

**`ms1scan` is derived by document order — never from the file's own linkage.**

```
previous_ms1_scan = 0
for each spectrum in document order:
    if peak_count == 0: continue   # NOT optional.
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
> 1 and 3, so **nothing caught this before**. `ZeroPeakMs1ChainTest` now does — with the guard
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
recorded". Collation converts 0 → null for those three only, and **never for `rt`** — `0.0` is a real
retention time. Converting early would destroy the distinction between "absent" and "already converted".

**Multiple precursors per MS2 scan → the FIRST one wins**. MassQL hard-indexes `[0]` at
every level:

| Format | What MassQL reads |
|---|---|
| mzML | `precursorList.precursor[0].selectedIonList.selectedIon[0]` (`:603`) |
| mzXML | `spectrum["precursorMz"][0]` (`:450`) — `precursorCharge` comes from the same element |

Not a pathological case: **multiplexed (MSX) acquisition co-fragments several precursors into one MS2
scan**, and DIA/SWATH uses wide isolation windows with no single selected ion. A reader that overwrites on
each occurrence takes the *last* and disagrees, and no committed fixture could tell — all of
`small.mzML`, `small.mzXML` and the Ewing file are single-precursor (`max=1`).
`micro_multiprec.{mzML,mzXML}` now pin it; the mzML one carries a second `<selectedIon>` **and** a second
`<precursor>`, so honouring only one nesting level still fails.

**Zero-intensity peaks are dropped by MGF and kept by mzML/mzXML**. MassQL's MGF loader
opens its peak loop with `if intensity == 0: continue`, so such a peak never becomes a row and cannot be
matched, counted or summed. The other two loaders have **no such guard** — `small.mzML`'s parity dump records
`i_hex_first8` as eight `0x0.0p+0` entries, retained on both sides and compared bit-for-bit by the parity gate.
**Generalising the MGF skip would break every mzML fixture in that gate**; `ZeroIntensityPeakTest` asserts both
directions in one class so the asymmetry cannot be tidied away.

A block whose peaks are *all* zero-intensity therefore reduces to a **zero-peak scan** — MassQL emits no rows
for it at all, and the engine's zero-peak guard is what makes the two agree.

`iNorm`/`iTicNorm` are unaffected: MassQL computes `i_max`/`i_sum` from the full array *before* the skip, and a
zero changes neither a max nor a sum.

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
| **`charge`** | `CHARGE=` with a trailing `+`/`-` stripped. **Absent → `1`, not 0** |
| **`rt`** | `RTINSECONDS`÷60. **Absent → `0.0`, not null** |
| **`scan`** | `SCANS=` when present, else the **1-based block index** |
| `ms1scan` | Hardcoded **0** (`:394`) |
| **zero-intensity peaks** | ⛔ **DROPPED** — `if intensity == 0: continue` opens the peak loop. MGF **only**: mzML/mzXML retain them |
| **`polarity`** | Not read from any header — but **hardcoded `1`** (positive), not 0. Measured `{1: all}` across 866k MGF rows. Reading a rule off the *parse* path missed a default set elsewhere in the function |

> **⚠ MGF `charge` is never null.** The loader uses
> `params.get('charge', [1])` with `except: charge = 1`. Since only `0` is null-converted, an absent
> `CHARGE=` is indistinguishable from a genuine 1+. The golden agrees: `{1: 653, 2: 10, 3: 1}`, zero nulls.

**Zero-peak blocks are real spectra and must be yielded.** 12,571 of `PlusRise.mgf`'s 34,513 blocks have no
peak lines. MassQL's dataframe simply has no *rows* for them, which is why it reports **21,942** unique
scans — this explains the "12,571 dropped spectra". Nothing is dropped.

**MassQL fabricates a 1-row MS1 table for MGF, and it takes one of TWO forms**. The pyteomics loader ends with
`ms1_df = pd.DataFrame([peak_dict])` where `peak_dict` **leaks from the MS2 peak loop**, so the row is a
byte-for-byte **duplicate of the last MS2 peak**. The all-zero `except` branch (mz=0, i=0, scan=1) is
reached only when that loop never ran.

| Fixture | Loader | Fake MS1 row |
|---|---|---|
| `PlusRise.mgf` | manual (pyteomics cannot index it) | **all zeros**, scan 1 |
| `micro.mgf` | pyteomics | duplicate of the last MS2 peak — scan **3**, m/z 123.456789012345, i 4096.0 |
| `DP00570_F02.mgf` | pyteomics | duplicate — scan **625**, m/z 897.5525, i 2449.0 |

These readers omit it either way, correctly: MGF has no survey scans. Two consequences for the parity gate: the dump's
`ms1_peak_rows: 1` **double-counts a real peak** on the pyteomics path (so PlusRise's `758,545` = 758,544
real + one synthetic zero, but micro/DP00570's totals include a duplicated real peak), and the fake row's
scan id is the *last MS2 scan's*, which is why it **collides** with a real id  and why the parity
harness keys on `(mslevel, scan)`.

Parsing tolerances: blank lines, `#`/`;`/`!` comments, CRLF, space- or tab-separated peaks, and a
file-level `COM=`/`CHARGE=` preamble before the first `BEGIN IONS` (both real fixtures have one).

---

## mzML

Hand-written XML walk over the vendored decode layer. `XMLStreamReaderImpl` is
instantiated **directly** — the JDK's `XMLInputFactory` uses `ServiceLoader`, which this project does
not permit.

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
`small.mzML`. Readers are **SDK** code, and **the SDK logs nothing at all**, to either stream;
diagnostics go back through `SpectraStream.diagnostics()`. (Secondarily, at the CLI layer, stdout output
would also corrupt the JSON payload — a consequence, not the reason.) `JavolutionQuiet` raises
`LogContext.LEVEL` above `INFO`; `StdoutCleanlinessTest` guards both streams.

---

## mzXML

Hand-written, vendoring nothing new.

| Field | Rule |
|---|---|
| **`scan`** | `int(num)`. ⚠ pyteomics returns `spectrum["id"]` as a **`str`** (`'1'`), so `previous_ms1_scan` would otherwise propagate a string into `ms1scan`. Parse to int in Java |
| `mslevel` | `msLevel` attribute. **Empty or absent → the scan is dropped** (see below) |
| **`rt`** | ISO-8601 duration, **always** → minutes. `PT90S` → 1.5, `PT1.38S` → 0.023, `PT1M30S` → 1.5, `PT1H` → 60. pyteomics has already converted, and MassQL uses the value as-is |
| `precmz` | `precursorMz[0]`, i.e. the text of the first `<precursorMz>` element. Absent on an MS2 → **0** (non-parity, see below) |
| **`charge`** | `precursorCharge` attribute, absent → **`0`** (`:451`). ⚠ **Unlike MGF, where absent is `1`**. Three formats, three charge defaults |
| `polarity` | `"+"` → 1, `"-"` → 2, present-but-other → 0. **Absent → 0 is non-parity** (see below) |
| precision | `precision="32"` or `"64"`, one attribute for the whole interleaved array |
| compression | `compressionType` absent or `"none"` → uncompressed, else zlib. **No Numpress, ever** |
| byte order | `byteOrder="network"` = big-endian |
| `precursorScanNum` | **Never read**, even when present — `small.mzXML` has 34 of them and they must be ignored |

**`msLevel` empty or absent → the scan is dropped, contributing zero rows**. pyteomics converts `msLevel=""` to **`None`**;
MassQL tests `mslevel == 1` and `mslevel == 2` (`:434`, `:450`), so `None` matches neither branch and the
spectrum is silently skipped. **Not** a default of 1, **not** a diagnostic, **not** a failure. Measured on
`fixtures/edge/empty_msLevel_tag.mzXML`: 8 of its 10 scans are `msLevel=""` and only scans 4 and 8
survive.

### Two cases where MassQL crashes, so no golden can exist

Both of these read a dict key **unguarded**, so the absent case raises `KeyError` and the reference
implementation produces no output at all. The behaviour here is therefore a local choice, and must be
labelled as such — a test asserting it pins this SDK's contract, **not** parity.

| Case | MassQL | Here | Why |
|---|---|---|---|
| `polarity` attribute absent | `spec["polarity"]` → **`KeyError`** (`:519`) | **0** | The `polarity = 0` initialiser only ever covers *present but neither `+` nor `-`* — that case **is** parity. Absent is not reachable in a successful MassQL run |
| MS2 with no `<precursorMz>` | `spectrum["precursorMz"][0]["precursorMz"]` → **`KeyError`** (`:450`) | **`precmz = 0`** | Consistent with mzML, where an absent `MS:1000744` is already 0, and with the 0-sentinel meaning "not recorded" (collation maps it to null). Throwing would make mzXML stricter than mzML for the same missing field |

> The absent-`polarity` case is easy to believe is parity, because `empty_msLevel_tag.mzXML` contains 8
> scans with no `polarity` attribute and MassQL reads it without error. It does not crash only because
> those same 8 scans have `msLevel=""` and are dropped **before** the polarity call. Change the
> `msLevel` and it raises.

**Both scan layouts must work.** `small.mzXML` is **flat** (`<scan>` depth 1); the Ewing file **nests**
MS2 inside its parent MS1 (depth 2). A flat-only walk passes on `small.mzXML` and silently mis-associates
every nested scan. Measured, not assumed.

A missing or empty `msLevel`: the scan is dropped and contributes zero rows; see the `msLevel` rule above.

---

## Verified against the oracle

`small.mzML`, read by `MzmlReader` and compared to
`src/test/resources/goldens/loader-parity/small.mzML.json.gz`:

| | reader | dump |
|---|---|---|
| scans / MS1 / MS2 | 48 / 14 / 34 | 48 / 14 / 34 |
| peaks | **305,214** | 305,214 |
| per-scan peak counts | all match | — |
| scan 3 `rt` | `0.011218333333333334` (bit-exact) | golden |
| `ms1scan` for scans 3, 10, 17, 24, 37, 44 | 2, 9, 16, 23, 36, 43 | golden |

`small.mzXML` against `goldens/loader-parity/small.mzXML.json.gz`: **48 / 14 / 34**, per-scan peak counts
all match, and its 34 `precursorScanNum` attributes are ignored.

`DP00570_F02.mzxml` (the Ewing file), read by `MzxmlReader`:

| | reader | source of truth |
|---|---|---|
| scans / MS1 / MS2 | **916 / 229 / 687** | parity dump |
| `<scan>` nesting depth | 2 (nested) — handled | the file itself |
| `ms1scan`, all **687** MS2 scans | match | document order re-derived from the raw XML |
| e.g. scan 100 → **97** (not 99) | ✓ | MassQL's own loader |
| `totIonCurrent` / `basePeakIntensity` / `basePeakMz` | worst relative delta **4.72e-06 / 4.90e-06 / 4.85e-06** | the instrument's own scan attributes — **no Python in the loop** |
| peaks streamed | 308,425 in a **48 MB heap**, 87 KB retained | — |

That instrument cross-check is the strongest independent evidence the decode is right: the drift matches an
independent Python measurement to three digits, and it depends on no golden, no pyteomics and no MassQL.

> ⚠ **The loader-parity dumps are GROUPED BY LEVEL, not document order.** They are built from `ms1_df`
> then `ms2_df`, so `scans` holds all MS1 entries followed by all MS2 entries. Reconstructing the
> `ms1scan` chain from a dump therefore yields the *last* MS1 for every MS2 (913, on the Ewing file).
> Derive document order from the file, not the dump.

`PlusRise.mgf`: 34,513 scans, **758,544** real peaks, streamed inside a **48 MB heap** — the proof that
retained memory is bounded by scan size, not file size.
