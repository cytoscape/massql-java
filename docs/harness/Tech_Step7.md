# Tech Step 7 — mzXML reader (hand-written)

## Goal

An `MzxmlReader` that decodes mzXML bit-identically to MassQL's Python loader — **hand-written, vendoring
nothing new** (Correction C23) — and carrying the one assertion that proves the `ms1scan` document-order
rule.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | `msdk-io-mzxml` is **excluded** there; this step supplies mzXML support in its place. Also provides `DEPENDENCY_POLICY.md`, which this step must not violate. |
| [Step 5](Tech_Step5.md) | `SpectrumTable` — the fill target. |
| [Step 6](Tech_Step6.md) | Defines the `SpectraStream` / `ScanView` cursor (C22) this reader plugs into, owns the `ms1scan` document-order rule this step asserts, and has **already vendored `ByteBufferInputStream`** plus the rest of the decode layer to `io/vendor/` (C21). |
| [Step 2](Tech_Step2.md) | Provides `micro.mzXML`, `data/small.mzXML`, `data/DP00570_F02.mzxml`, `empty_msLevel_tag.mzXML`. |

## Context

This step exists because of Correction C1 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md): `msdk-io-mzxml` cannot
be used as a dependency. `MzXMLFileParser` directly imports `it.unimi.dsi.io.ByteBufferInputStream`
(→ dsiutils → **fastutil 7.1.0, 17,655,579 B**), `com.google.common.collect.Range` (→ Guava 21, 2,521,113 B),
`org.slf4j.*`, and `SpectrumTypeDetectionAlgorithm` — over **20.6 MB against a ~1.5 MB budget**, plus logback at
runtime, which `SPIKE.md` §9 bans outright.

> ⚠ **This paragraph used to argue that vendoring beats hand-writing, on the grounds that swapping the
> dsiutils `ByteBufferInputStream` import "removes the entire tail". That was wrong** — it accounted for
> dsiutils but not for the 7 `datamodel` types, nor for Guava arriving via `SimpleMsScan`. See
> Correction C23.

**Hand-write it.** The dsiutils swap was only ever one of the problems; `MzXMLFileParser` also needs 7
`msdk-datamodel` types, and `SimpleMsScan` — which it instantiates — imports Guava `Preconditions` and
`Range`. Since [Step 6](Tech_Step6.md) is already hand-writing the mzML walk (C21) and mzXML is the
simpler format, writing this reader costs less than the datamodel surgery and adds nothing to
`io/vendor/`.

This remains a separate spec from Step 6 because it is a distinct format with its own decode rules —
big-endian, interleaved pairs, unconditional RT conversion — and because it carries **the decisive
`ms1scan` document-order assertion** that no other fixture can make.

Governing sections: `SPIKE.md` §5, §6c (the Ewing fixture's four justifications, RT-units table), §9.

## Scope

**In scope**
- A hand-written `MzxmlReader` plugging into Step 6's `SpectraStream` / `ScanView` cursor.
- mzXML decode, RT and edge-case rules — including **both flat and nested scan layouts**.
- **The decisive `ms1scan` document-order assertion.**

**Out of scope**
- **Vendoring anything.** C23: nothing new goes under `io/vendor/`. Reuse Step 6's
  `ByteBufferInputStream` / `FileMemoryMapper`; provenance and the EPL-1.0 election are already handled
  there.
- The cursor interface and the document-order *rule* itself — [Step 6](Tech_Step6.md) owns both; this
  step implements and proves them.
- Numpress — mzXML has none.
- The 0 → null sentinel conversion — [Step 10](Tech_Step10.md).

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/io/MzxmlReader.java` | **Hand-written** streaming reader (~250–350 LOC) |
| *(nothing new under `io/vendor/`)* | C23 — reuse Step 6's `ByteBufferInputStream` / `FileMemoryMapper` |
| `docs/VENDORED.md` | Already written by Step 6; **no new entries from this step** |
| `src/test/java/…/io/Mzxml*Test.java` | The test set below |

## Specification

### 1. Hand-write it — vendor nothing new (Correction C23)

> ⚠ **This section previously said to vendor `MzXMLFileParser` with "four modifications and only these
> four". That list is incomplete in the same way Step 6 §3's was.** Measured: `MzXMLFileParser` carries
> **13 msdk imports**, including 7 `datamodel` types (`SimpleMsScan`, `SimpleIsolationInfo`,
> `IsolationInfo`, `MsScanType`, `MsSpectrumType`, `PolarityType`, `RawDataFile`). And modification #2 —
> "replace the single Guava `Range` use" — misidentifies where Guava comes from: it arrives through
> **`SimpleMsScan`, which itself imports `Preconditions` and `Range`**. Vendoring the parser therefore
> requires providing our own scan holder, which is the surgery that made Step 6 §3 unachievable. The
> **stop-and-report clause fired again.**

**Hand-write the reader.** What changed since this spec was written is that [Step 6](Tech_Step6.md) is
now hand-writing the mzML XML walk too (C21) — and **mzXML is the simpler of the two**:

| | mzML | mzXML |
|---|---|---|
| Compression | zlib **+ 6 Numpress variants** | zlib or none |
| Precision | per-array cvParam | one `precision` attribute |
| Arrays | two separate arrays with cvParams | **one** interleaved array |

So hand-writing costs less than the datamodel surgery, `io/vendor/` stays at its **13 decode-layer
files**, and there is no new re-sync surface. This spec's own estimate stands: **~250–350 LOC**, and it
called mzXML "the simplest decode path in the contract".

**Reuse, do not re-vendor:** `io/vendor/ByteBufferInputStream` and `io/vendor/FileMemoryMapper` are
already vendored by Step 6 (JDK-only, package declaration changed only). Use them for the mapping.
Nothing else from MSDK is needed here.

**Both scan layouts are a hard requirement.** `small.mzXML` is **flat**; the Ewing file **nests** MS2
inside its parent MS1 (`<scan>` … `<scan>` … `</scan></scan>`). Hand-writing means we own this: the
walk must not assume `</scan>` ends the current spectrum. mzXML 2.0's nested form is the real-world
case.

### 2. Provenance — nothing to do here (Correction C23)

This step vendors nothing, so it adds no provenance headers and no `docs/VENDORED.md` entries.
[Step 6](Tech_Step6.md) already carries both for the 13 decode-layer files, including the **EPL-1.0
election** (Correction C3: MSDK's parent pom declares LGPL-2.1 **or** EPL-1.0 with
`<distribution>repo</distribution>`, so the consumer elects; cite the pom, not the 368-byte root
`LICENSE` that GitHub reads as NOASSERTION). [Step 13](Tech_Step13.md) restates it in the README.

`VendoredProvenanceTest` still applies — it asserts every file **already** under `io/vendor/` carries a
header and the string `EPL-1.0`, which keeps the licence obligation build-enforced.

### 3. Decode rules — mzXML differs from mzML on every one of them

| Rule | mzXML | Contrast with mzML |
|---|---|---|
| **Byte order** | `byteOrder="network"` = **big-endian** (`ByteBuffer`'s default) | mzML is **little-endian** |
| **Array layout** | m/z and intensity **interleaved as pairs** in one array | mzML has separate arrays |
| **Precision** | `precision` attribute, `32` or `64` | same values, different attribute |
| **Compression** | `compressionType` absent or `"none"` → uncompressed; else zlib | mzML also has Numpress |
| **Numpress** | **none, ever** | mzML needs it — the reason MSDK is worth taking there |

Upstream's `compressionType` check is `!= null && != "none"`, which already treats an absent attribute as
uncompressed — the Ewing file relies on that. Verify rather than assume, since it is a one-line read.

**32-bit precision — the bit-identity trap, and the Ewing fixture is precision="32".** pyteomics decodes with
`np.float32 if precision == '32' else np.float64` (`pyteomics/mzxml.py:_determine_dtype`), then Python widens to
double. So the golden values are `(double)(float)raw`, **not** full-precision doubles. Read a 4-byte
float and **then** widen it to double; reading 8 bytes, or reinterpreting the bits as a double, gives
values that are *nearly* right and Step 8 fails with a confusing near-miss.

⚠ **Correction C21a: there is no `BinaryDecoder`.** Step 6 originally specified a shared
base64/zlib/widening helper; it was dropped because mzML and mzXML agree on nothing — mzML uses the
vendored `MzMLPeaksDecoder` (little-endian, separate arrays, Numpress), while mzXML is **big-endian,
interleaved pairs, no Numpress**. **Decode inline here**, reusing the already-vendored
`io/vendor/ByteBufferInputStream`. Implement the widening rule directly: `readFloat()` **then** widen
to double. Do not reuse a buffer configured for mzML.

**RT: always convert.** mzXML carries an ISO-8601 duration (`PT1.38S`). MassQL uses
`spectrum["retentionTime"]` as-is, but **pyteomics has already converted it** to minutes via
`XMLValueConverter.duration_str_to_float` → `unitfloat(minutes, 'minute')` (`pyteomics/xml.py:118-143`). So:

- `PT90S` → **1.5** minutes. `PT1.38S` → **0.023** minutes.
- Handle `H` and `M` components too: `PT1M30S` → 1.5; `PT1H` → 60.
- **This is unconditional**, unlike mzML's unit-dependent conversion ([Step 6](Tech_Step6.md) §3). Three formats,
  three different RT rules — a silent 60× error here passes every MGF-only and mzML-only test.

Store `rt` into `scanRt` at **double** precision ([Step 5](Tech_Step5.md) §1).

**Polarity:** `"+"` → 1, `"-"` → 2, **present but neither** → 0 (`msql_fileloading.py:517-523`). An
**absent** `polarity` attribute raises `KeyError` in MassQL, so our 0 there is a non-parity choice —
Correction C27(c), §4.

**Three fields this spec never stated** (Correction C27d) — see the full table in
`docs/READER_RULES.md`:

| Field | Rule |
|---|---|
| **`scan`** | `int(num)`. pyteomics hands back `spectrum["id"]` as a **`str`** (`'1'`) — the root cause of C12 |
| `precmz` | text of the **first** `<precursorMz>` element — MassQL indexes `precursorMz[0]` (`:450`), so with several declared the **FIRST wins, not the last** (Correction C31; multiplexed/MSX acquisition produces such files). Absent on an MS2 → 0 (non-parity) |
| **`charge`** | `precursorCharge`, absent → **`0`** (`:451`) — ⚠ **unlike MGF, where absent is `1`** (C6) |

**`ms1scan`:** document order, per [Step 6](Tech_Step6.md) §4. `precursorScanNum` is **never read** — not even
when present. **`small.mzXML` does carry it — 34 occurrences, one per MS2 spectrum** (Correction C11,
measured). Ignore all of them; use document order.

**The two mzXML fixtures differ deliberately — but they do NOT cover every decode path** (Correction
C27, §6: this table previously claimed they did):

| | `micro.mzXML` | `small.mzXML` (generated) | `DP00570_F02.mzxml` (real) | `empty_msLevel_tag.mzXML` |
|---|---|---|---|---|
| `precision` | `"32"` | `"32"` | `"32"` | **`"64"`** |
| `byteOrder` | `"network"` | `"network"` | `"network"` | `"network"` |
| Compression | absent | absent | absent | **zlib** |
| Scan layout | flat | **flat** | **nested** (MS2 inside parent MS1) | flat |
| `precursorScanNum` | — | **34 present** — must be ignored | **zero** — only document order can work | — |
| Schema | 2.0 | 2.0 | 2.0 | 2.0 |

**All three of the primary fixtures are `precision="32"`, uncompressed, big-endian — the same
configuration.** So `precision="64"` and zlib were exercised by nothing except the edge fixture, whose
scans are mostly dropped on `msLevel`. **These six variants now exist** (generated by
`oracle/make_micro_fixtures.py`, committed under `src/test/resources/fixtures/micro/`, one variable each):

| Fixture | Varies | Verified against MassQL's loader |
|---|---|---|
| `micro_p64.mzXML` | `precision="64"` | 64-bit gives `123.456789012345` where 32-bit gives `123.456787109375` — the widening rule is **observable**, not assumed |
| `micro_zlib.mzXML` | `compressionType="zlib"` | decodes **bit-identically** to uncompressed |
| `micro_p64_zlib.mzXML` | both together | so a bug in their interaction cannot hide behind either alone |
| `micro_nested.mzXML` | `<scan>` depth 2 | same `ms1scan` links as flat: `{1:0, 3:2, 5:2}` |
| `micro_nopolarity.mzXML` | `polarity` omitted | MassQL raises **`KeyError: 'polarity'`** → non-parity (C27c) |
| `micro_noprecursor.mzXML` | no `<precursorMz>` on MS2 | MassQL raises **`KeyError: 'precursorMz'`** → non-parity (C27c) |

The reader must handle **both flat and nested** scan layouts. Measured, not assumed: `<scan>` nesting
depth is **1** in `small.mzXML` and **2** in the Ewing file. The nested form is the mzXML 2.0 convention
and the real-world case.

**Edge case found in Step 2:** a `<precursorMz>` element with **no attributes** makes pyteomics return a bare
string instead of a dict, and MassQL then crashes at `msql_fileloading.py:450`
(`TypeError: string indices must be integers`). Real mzXML always carries `precursorIntensity`. A Java reader
reads the element text and is immune — but it must not assume attributes are present either.

### 4. Edge cases

- **Missing or empty `msLevel` — RESOLVED, Correction C27(a). No longer an open item.** pyteomics
  converts `msLevel=""` to **`None`**, and MassQL tests `mslevel == 1` / `mslevel == 2` (`:434`, `:450`),
  so `None` matches neither branch and **the scan is silently dropped, contributing zero rows**. Not a
  default of 1, not a diagnostic, not a failure. Measured on `fixtures/edge/empty_msLevel_tag.mzXML`: 8
  of its 10 scans are `msLevel=""`; only scans 4 (MS2) and 8 (MS1) survive. Already recorded in
  `docs/READER_RULES.md`.
- **`polarity` absent, and MS2 with no `<precursorMz>` — MassQL raises `KeyError` for both**
  (Correction C27c). `_determine_scan_polarity_mzXML` reads `spec["polarity"]` unguarded (`:519`) and
  `:450` reads `spectrum["precursorMz"][0]["precursorMz"]` unguarded, so **no golden can exist** for
  either. Ours: absent `polarity` → **0**, absent `<precursorMz>` on an MS2 → **`precmz = 0`**. Both are
  **our contract, not parity** — the rule table in `docs/READER_RULES.md` labels them, and the tests must
  assert the parity case (`polarity` present but neither `+` nor `-` → 0) **separately** from the
  non-parity one. Do not let a passing test imply MassQL agreement here.
- **mzXML schema 2.0 vs MSDK's 3.2 target.** The Ewing file is 2.0. Confirm the vendored parser handles it; its
  simple decode path (`precision=32` + `byteOrder=network` + no compression) means a failure localizes cleanly.
- **Truncated file** → `MassqlException`, **no partial results**, per [Step 6](Tech_Step6.md) §6. This also
  exercises the end-of-stream behaviour of the reused `ByteBufferInputStream` (§1).
- **`peaksCount="0"`** → an empty scan, which [Step 5](Tech_Step5.md) supports; must not throw.

### 5. Wire into `SpectraFile`

Register `MzxmlReader` for `Format.MZXML` and remove the "not yet implemented" throw
[Step 6](Tech_Step6.md) left there. Confirm `SpectraStream.close()` releases the mapped region — this
reader memory-maps, so [Step 6](Tech_Step6.md)'s **`SpectraStreamCloseTest`** (renamed under C22; this
spec previously called it `SpectraFileCloseTest`) must now be re-run against an mzXML file, and
`StreamingMemoryTest` extended to cover the Ewing fixture.

## Known traps

- **Reaching for MSDK's parser after all.** It looks like a shortcut; it drags 7 `datamodel` types and
  Guava via `SimpleMsScan` (C23). If the hand-written walk gets hard, the answer is more tests, not a
  vendored parser.
- **Assuming `</scan>` ends the spectrum.** The Ewing file **nests** MS2 inside MS1. A flat-only walk
  passes on `small.mzXML` and silently mis-associates every nested scan.
- **Reusing the mzML `ByteBuffer` configuration.** mzXML is big-endian and interleaved. Both wrong at once
  produces plausible-looking garbage.
- **`buf.getDouble()` on the Ewing file.** It is `precision="32"`. Near-right values; confusing Step 8 near-miss.
- **Converting mzXML RT conditionally**, by analogy with mzML. mzXML is **always** ÷60 — pyteomics has already
  done the ISO-8601 parse, and MassQL trusts it.
- **Reading `precursorScanNum` because it's there.** `small.mzXML` may have it after conversion. Ignoring it is
  the *correct* behaviour, and this fixture is the only one where the difference is observable.
- **Forgetting that `precursorIntensity` may be absent.** A bare `<precursorMz>` with no attributes crashes MassQL (Step 2 finding); we read element text and are immune, but must not require attributes either.

## Tests required

| Test | Type | Pins |
|---|---|---|
| **`Ms1ScanDocumentOrderIT`** | **IT** | **The decisive assertion of the whole `ms1scan` rule.** ✅ Premise verified in Step 2: with the C12 fix the Ewing file yields `ms1scan=1` for scan 2 and **populated `ms1_i`/`ms1_precmz`/`ms1_base_peak_i`** (e.g. `ms1_i=3107784.0`) despite zero `precursorScanNum` — so this test is viable exactly as specified. Before that fix MassQL nulled all three for every mzXML input. On `data/DP00570_F02.mzxml` — 229 MS1 interleaved with 687 MS2 and **zero `precursorScanNum` attributes** — every MS2 scan's `ms1scan` must equal the most recent preceding MS1 scan id, and be **non-zero** for all but any leading MS2 scans. A `precursorScanNum`-resolving implementation yields all-null here and fails loudly. **No other available fixture separates those two implementations.** ⚠ **Correction C26 reverses the old instruction here** ("skip with a clear message if the fixture is absent"): the fixture is still gitignored for licence reasons, but a missing fixture now **fails** — `Fixtures.require` throws, CI runs `scripts/fetch-fixtures.sh` and caches the result, and CI asserts the skipped-test count is 0. Skipping this test is how the whole verification story came to prove nothing. |
| `MzxmlRtConversionTest` | unit | `PT90S` → exactly **1.5**; `PT1.38S` → `0.023` (to the bit MassQL produces); `PT1M30S` → 1.5; `PT1H` → 60. |
| `MzxmlDecodeTest` | unit | Big-endian; **interleaved pairs** correctly de-interleaved; `precision="32"` widened via `(double)(float)`, asserted on **raw bits**; `precision="64"`; zlib-compressed and uncompressed; absent `compressionType` treated as uncompressed. ⚠ **`micro.mzXML` alone cannot satisfy this** — it, `small.mzXML` and the Ewing file are *all* `precision="32"` / uncompressed / `network`, so §3's claim that the two fixtures "cover every decode path" was false (Correction C27, §6). The 64-bit and zlib paths need the new `micro_p64.mzXML` / `micro_zlib.mzXML` variants; `empty_msLevel_tag.mzXML` happens to be 64-bit + zlib but only 2 of its 10 scans survive the `msLevel` filter, so it is a poor primary source. |
| `MzxmlPolarityTest` | unit | **Two groups, kept separate** (C27c). *Parity:* `"+"` → 1, `"-"` → 2, present-but-other → 0 — these MassQL can produce. *Our contract, no golden:* absent attribute → 0, asserted with a comment saying MassQL raises `KeyError` here. A single test lumping them together would imply parity we do not have. |
| `MzxmlEdgeCaseTest` | unit | `empty_msLevel_tag.mzXML`: `msLevel=""` scans are **dropped, zero rows** (C27a — 8 of 10 dropped, scans 4 and 8 survive); `peaksCount="0"` yields an empty scan and **must not update the `ms1scan` chain** (C27b); MS2 with no `<precursorMz>` → `precmz = 0`, non-parity; truncated file throws with **no partial table**. |
| `MzxmlSchema20IT` | IT | `data/DP00570_F02.mzxml` (schema 2.0) parses: **916 scans, 229 MS1, 687 MS2**; scan ids strictly increasing (the nested walk must not re-emit or reorder); `peaksCount` agrees with the decoded length on every scan; the file really is nested (depth 2) and really is schema 2.0, so the premise cannot rot; the **11** `peaksCount="3"` scans are read rather than treated as empty; and a clean file produces **no** diagnostics. |
| `MzxmlReaderTest` | unit | The Step 6-style oracle cross-check for `small.mzXML`, plus the **flat-vs-nested row-for-row equivalence** on the micro pair, `precursorScanNum` ignored (with the 34 occurrences asserted present first), and mzXML `charge` absent → 0 contrasted against MGF's 1. |
| `StreamingMemoryTest` (extended) | unit | Step 7 §5: the Ewing file streams inside a **48 MB heap** and retains ~87 KB. mzXML holds a mapped region plus one scan's base64, which is a different retention shape from MGF's reader, so it needs its own proof. |
| `InstrumentAttributeCrossCheckIT` | IT | A free check with **no Python in the loop**: the Ewing file's own `basePeakMz`, `basePeakIntensity` and `totIonCurrent` scan attributes vs our computed values. Hand-check the `peaksCount="3"` scans noted in Step 2. Expect minor float drift on `tic`; a **systematic** mismatch is a bug. (Full form in [Step 8](Tech_Step8.md).) |
| `VendoredProvenanceTest` | unit | Every file under `io/vendor/` contains the provenance header and the string `EPL-1.0` — makes the licence obligation a build-enforced fact rather than a convention. |

## Done when

- [x] `mvn verify` green — **326 unit + 9 IT, 0 skipped**. `scripts/dependency-audit.sh` reports the
      unchanged **two-artifact 0.749 MB** closure; no dsiutils, fastutil, Guava or logback.
- [x] **Both scan layouts** covered: flat (`small.mzXML`, `micro.mzXML`) and nested
      (`DP00570_F02.mzxml`, `micro_nested.mzXML`). The flat/nested pair is asserted as a **row-for-row
      equivalence**, which is stronger than either alone, and the walk never treats `</scan>` as a
      spectrum boundary — it emits at `<peaks>`, so both layouts take one code path.
- [x] **Nothing new under `io/vendor/`** (C23) — still **13** files. `docs/VENDORED.md` unchanged.
- [x] `VendoredProvenanceTest` still passes over the files Step 6 vendored (this step adds none).
- [x] **`Ms1ScanDocumentOrderIT` passes on the Ewing mzXML** — all **687** MS2 links match document
      order re-derived from the raw XML, including the non-trivial scan 100 → **97**. Zero
      `precursorScanNum` in the file is asserted first, so the test cannot go vacuous. It **fails**
      rather than skips when the fixture is absent (C26).
- [x] All RT forms convert correctly, including the three pyteomics quirks (`P1DT1H` → 60, `P1M` → 0,
      a leading `-` refused); `PT1.38S` asserted **bit-exact**. `precision="32"` asserted on raw bits,
      with a companion assertion that the 32- and 64-bit decodes genuinely differ.
- [x] `SpectraFile.open` handles `.mzXML` and `.mzxml` by **content**; close/leak tests extended to
      mzXML (200 open/close cycles), and the constrained-heap proof extended to the Ewing file —
      **916 scans / 308,425 peaks in a 48 MB heap, 87 KB retained**.
- [x] `docs/READER_RULES.md` extended: the mzXML rules now include `scan`, `precmz` and `charge`
      (absent → **0**, unlike MGF's 1), the resolved `msLevel`-absent rule, and the two non-parity cases.
- [x] **The instrument cross-check reconciles with no Python in the loop** — worst relative delta
      4.72e-06 / 4.90e-06 / 4.85e-06 on `totIonCurrent` / `basePeakIntensity` / `basePeakMz` across all
      916 scans, matching an independent Python measurement to three digits, with a bias check proving
      the drift is noise rather than systematic.

## References

- `SPIKE.md` §5 (**superseded on the mzXML dependency question by Correction C1**), §6c (the Ewing fixture's four
  justifications; the RT-units table), §9
- Corrections **C1** and **C3** in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)
- `pyteomics/mzxml.py` — `_determine_dtype`, `_decode_peaks`; `pyteomics/xml.py:118-143` —
  `XMLValueConverter.duration_str_to_float` (ISO-8601 → **minutes**)
- `massql/msql_fileloading.py:414-475` (mzXML loader), `:442`/`:463` (RT), `:517-523` (polarity)
- Upstream: `github.com/msdk/msdk/blob/master/msdk-io-mzxml/src/main/java/io/github/msdk/io/mzxml/MzXMLFileParser.java`
- [Step 6](Tech_Step6.md) §4 — the `ms1scan` document-order rule this step proves
