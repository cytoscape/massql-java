# Tech Step 7 — Vendored mzXML reader

## Goal

An `MzxmlReader` that decodes mzXML bit-identically to MassQL's Python loader, built on a vendored copy of MSDK's
`MzXMLFileParser` stripped of its 20 MB dependency tail — and carrying the one assertion that proves the
`ms1scan` document-order rule.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | `msdk-io-mzxml` is **excluded** there; this step supplies mzXML support in its place. Also provides `DEPENDENCY_POLICY.md`, which this step must not violate. |
| [Step 5](Tech_Step5.md) | `SpectrumTable` — the fill target. |
| [Step 6](Tech_Step6.md) | Defines `SpectraReader` / `SpectraFile` / `Format` / `BinaryDecoder`, which this reader plugs into, and owns the `ms1scan` document-order rule this step asserts. |
| [Step 2](Tech_Step2.md) | Provides `micro.mzXML`, `data/small.mzXML`, `data/DP00570_F02.mzxml`, `empty_msLevel_tag.mzXML`. |

## Context

This step exists because of Correction C1 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md): `msdk-io-mzxml` cannot
be used as a dependency. `MzXMLFileParser` directly imports `it.unimi.dsi.io.ByteBufferInputStream`
(→ dsiutils → **fastutil 7.1.0, 17,655,579 B**), `com.google.common.collect.Range` (→ Guava 21, 2,521,113 B),
`org.slf4j.*`, and `SpectrumTypeDetectionAlgorithm` — over **20.6 MB against a ~1.5 MB budget**, plus logback at
runtime, which `SPIKE.md` §9 bans outright.

The fix is unusually cheap, which is why vendoring beats hand-writing here: **MSDK already solved this problem
for the mzML path.** `io.github.msdk.io.mzml.util.ByteBufferInputStream` is an API-compatible replacement for the
dsiutils class — same `map(FileChannel, MapMode)`, `read()`, `read(byte[],int,int)`, `length()`, `position()`,
plus `constrain()`. The mzML parser uses it; the mzXML parser is simply the unmigrated older one. Swapping one
import removes the entire tail. MZmine has vendored this same parser into its own tree — portability is proven by
construction.

This is a separate spec from [Step 6](Tech_Step6.md) because vendoring third-party code is different work from
coding against an API: it carries provenance, license and divergence-tracking obligations that a normal
implementation does not.

Governing sections: `SPIKE.md` §5, §6c (the Ewing fixture's four justifications, RT-units table), §9.

## Scope

**In scope**
- Vendoring `MzXMLFileParser` and the minimal set of classes it needs.
- The four modifications that remove dsiutils, Guava and slf4j.
- Provenance headers and the EPL-1.0 election.
- `MzxmlReader` implementing `SpectraReader`.
- mzXML decode, RT and edge-case rules.
- **The decisive `ms1scan` document-order assertion.**

**Out of scope**
- Improving on upstream. Vendored code is changed **only** as §2 lists; every other line stays byte-identical so
  a future re-sync is a readable diff.
- The `SpectraReader` interface and the document-order *rule* itself — [Step 6](Tech_Step6.md) owns both; this
  step implements and proves them.
- Numpress — mzXML has none.
- The 0 → null sentinel conversion — [Step 10](Tech_Step10.md).

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/io/vendor/MzXMLFileParser.java` | Vendored + modified |
| `src/main/java/…/massql/io/vendor/ByteBufferInputStream.java` | Vendored from MSDK's **mzml** util package |
| `src/main/java/…/massql/io/vendor/FileMemoryMapper.java` | If required by the above |
| `src/main/java/…/massql/io/vendor/*` | Any further minimal `data/` classes |
| `src/main/java/…/massql/io/MzxmlReader.java` | Our reader; wraps the vendored parser |
| `docs/VENDORED.md` | Provenance, upstream commit, the exact diff from upstream, and the licence election |
| `src/test/java/…/io/Mzxml*Test.java` | The test set below |

## Specification

### 1. Vendor the minimum

Upstream: `github.com/msdk/msdk`, `msdk-io-mzxml/src/main/java/io/github/msdk/io/mzxml/MzXMLFileParser.java`.
Pin the upstream commit SHA and record it.

Copy `MzXMLFileParser` plus **only** what it needs to compile, into `io/vendor/`.

> ⚠ **Updated by Correction C16.** This spec originally said `ByteBufferInputStream` was "already on our
> classpath via `msdk-io-mzml`, so copying it is optional." **MSDK is no longer a dependency at all** — Guava is
> unavoidable via `msdk-datamodel`. So that class must be vendored, and [Step 6](Tech_Step6.md) vendors it
> anyway for the mzML reader. **Depend on Step 6's vendored copy** rather than adding a second one: both readers
> share `io/vendor/ByteBufferInputStream.java`. If Step 7 runs before Step 6 for any reason, vendor it here and
> have Step 6 reuse it.

Keep upstream package-relative structure inside `io/vendor/` so the diff against upstream stays readable.

### 2. The four modifications — and only these four

Record each in `docs/VENDORED.md` with the before/after line.

| # | Change | Removes |
|---|---|---|
| 1 | `import it.unimi.dsi.io.ByteBufferInputStream` → `io.github.msdk.io.mzml.util.ByteBufferInputStream` | **dsiutils 440,530 + fastutil 17,655,579 + commons-configuration + commons-collections + commons-math3** |
| 2 | Replace the single `com.google.common.collect.Range` use with a plain two-field range (or `SpectrumTable`'s `IntRange`) | **Guava 21 = 2,521,113 B** |
| 3 | Delete the slf4j `Logger`/`LoggerFactory` fields and calls. The SDK logs nothing — return diagnostics instead (`DEPENDENCY_POLICY.md` constraint 5) | slf4j usage on this path |
| 4 | Remove the `SpectrumTypeDetectionAlgorithm` call (centroid/profile detection is not part of the contract) | `msdk-spectra-centroidprofiledetection` |

**Verify modification 1 is truly API-compatible before relying on it.** The methods match
(`map(FileChannel, MapMode)`, `read`, `length()`, `position(long)`, `constrain(long, long)`), but confirm
behaviour on the one thing that differs between implementations of a mapped stream: **what happens past
end-of-stream** — `-1` vs an exception. A truncated-file test (§5) covers it.

If any modification turns out to need more than a local edit, **stop and report** rather than rewriting the
parser: at that point hand-writing mzXML from scratch (~250–350 LOC; mzXML is the simplest decode path in the
contract) becomes the better trade, and that is a spec change, not an implementation detail.

### 3. Provenance and licence headers

Every vendored file gets a header:

```java
/*
 * Vendored from MSDK — github.com/msdk/msdk
 *   path:    msdk-io-mzxml/src/main/java/io/github/msdk/io/mzxml/MzXMLFileParser.java
 *   commit:  <upstream SHA>
 *   date:    <date>
 * Modified: see docs/VENDORED.md. Summary: dsiutils ByteBufferInputStream replaced with
 *   MSDK's own API-compatible class; Guava Range removed; slf4j removed; centroid
 *   detection removed. No behavioural change to decoding is intended.
 *
 * MSDK is dual-licensed LGPL-2.1 OR EPL-1.0. This project elects **EPL-1.0**.
 */
```

The election matters and is settled — see Correction C3. MSDK's parent pom declares both licences with
`<distribution>repo</distribution>`, so the consumer chooses; we choose EPL-1.0. The repo's root `LICENSE` is 368
bytes and GitHub reads it as NOASSERTION, so **cite the pom, not the LICENSE file**, as the authority.
[Step 13](Tech_Step13.md) restates the election in the README.

### 4. Decode rules — mzXML differs from mzML on every one of them

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
double. So the golden values are `(double)(float)raw`, **not** full-precision doubles. Use
`BinaryDecoder`'s shared widening helper from [Step 6](Tech_Step6.md) §5 — `readFloat()` then widen — and pass
**big**-endian explicitly. Do not reuse a buffer configured for mzML.

**RT: always convert.** mzXML carries an ISO-8601 duration (`PT1.38S`). MassQL uses
`spectrum["retentionTime"]` as-is, but **pyteomics has already converted it** to minutes via
`XMLValueConverter.duration_str_to_float` → `unitfloat(minutes, 'minute')` (`pyteomics/xml.py:118-143`). So:

- `PT90S` → **1.5** minutes. `PT1.38S` → **0.023** minutes.
- Handle `H` and `M` components too: `PT1M30S` → 1.5; `PT1H` → 60.
- **This is unconditional**, unlike mzML's unit-dependent conversion ([Step 6](Tech_Step6.md) §3). Three formats,
  three different RT rules — a silent 60× error here passes every MGF-only and mzML-only test.

Store `rt` into `scanRt` at **double** precision ([Step 5](Tech_Step5.md) §1).

**Polarity:** `"+"` → 1, `"-"` → 2, absent → 0 (`msql_fileloading.py:517-523`).

**`ms1scan`:** document order, per [Step 6](Tech_Step6.md) §4. `precursorScanNum` is **never read** — not even
when present. **`small.mzXML` does carry it — 34 occurrences, one per MS2 spectrum** (Correction C11,
measured). Ignore all of them; use document order.

**The two mzXML fixtures differ deliberately, and between them cover every decode path:**

| | `small.mzXML` (generated) | `DP00570_F02.mzxml` (real) |
|---|---|---|
| `precision` | `"32"` | `"32"` |
| `byteOrder` | `"network"` | `"network"` |
| Compression | absent | absent |
| Scan layout | **flat** | **nested** (MS2 inside parent MS1) |
| `precursorScanNum` | **34 present** — must be ignored | **zero** — only document order can work |
| Schema | 2.0 | 2.0 |

So the vendored parser must handle **both flat and nested** scan layouts. Confirm which upstream supports
before assuming; the nested form is the mzXML 2.0 convention and the real-world case.

**Edge case found in Step 2:** a `<precursorMz>` element with **no attributes** makes pyteomics return a bare
string instead of a dict, and MassQL then crashes at `msql_fileloading.py:450`
(`TypeError: string indices must be integers`). Real mzXML always carries `precursorIntensity`. A Java reader
reads the element text and is immune — but it must not assume attributes are present either.

### 5. Edge cases

- **Missing or empty `msLevel`** — MSDK ships `empty_msLevel_tag.mzXML` for exactly this. mzXML in the wild
  omits it. Decide the behaviour from `msql_fileloading.py` (does the Python loader default to 1, skip the scan,
  or fail?) and reproduce that, recording it in `docs/READER_RULES.md`. Do not invent a default.
- **mzXML schema 2.0 vs MSDK's 3.2 target.** The Ewing file is 2.0. Confirm the vendored parser handles it; its
  simple decode path (`precision=32` + `byteOrder=network` + no compression) means a failure localizes cleanly.
- **Truncated file** → `MassqlException`, **no partial results**, per [Step 6](Tech_Step6.md) §6. This also
  exercises the end-of-stream behaviour of the swapped `ByteBufferInputStream` (§2).
- **`peaksCount="0"`** → an empty scan, which [Step 5](Tech_Step5.md) supports; must not throw.

### 6. Wire into `SpectraFile`

Register `MzxmlReader` for `Format.MZXML` and remove the "not yet implemented" throw
[Step 6](Tech_Step6.md) left there. Confirm `SpectraFile.close()` releases the mapped region — this reader
memory-maps, so [Step 6](Tech_Step6.md)'s `SpectraFileCloseTest` must now be re-run against an mzXML file.

## Known traps

- **Vendoring more than necessary.** Every extra file is re-sync surface. Take `MzXMLFileParser`, prefer the
  classpath copy of `ByteBufferInputStream`, and stop.
- **"Improving" the vendored code.** Any change beyond the four in §2 makes the upstream diff unreadable and
  risks a behavioural drift that Step 8 will report as a decoder bug with no obvious cause.
- **Reusing the mzML `ByteBuffer` configuration.** mzXML is big-endian and interleaved. Both wrong at once
  produces plausible-looking garbage.
- **`buf.getDouble()` on the Ewing file.** It is `precision="32"`. Near-right values; confusing Step 8 near-miss.
- **Converting mzXML RT conditionally**, by analogy with mzML. mzXML is **always** ÷60 — pyteomics has already
  done the ISO-8601 parse, and MassQL trusts it.
- **Reading `precursorScanNum` because it's there.** `small.mzXML` may have it after conversion. Ignoring it is
  the *correct* behaviour, and this fixture is the only one where the difference is observable.
- **Assuming `constrain()`/EOF semantics match** between the dsiutils and MSDK stream classes. Test it.

## Tests required

| Test | Type | Pins |
|---|---|---|
| **`Ms1ScanDocumentOrderIT`** | **IT** | **The decisive assertion of the whole `ms1scan` rule.** ✅ Premise verified in Step 2: with the C12 fix the Ewing file yields `ms1scan=1` for scan 2 and **populated `ms1_i`/`ms1_precmz`/`ms1_base_peak_i`** (e.g. `ms1_i=3107784.0`) despite zero `precursorScanNum` — so this test is viable exactly as specified. Before that fix MassQL nulled all three for every mzXML input. On `data/DP00570_F02.mzxml` — 229 MS1 interleaved with 687 MS2 and **zero `precursorScanNum` attributes** — every MS2 scan's `ms1scan` must equal the most recent preceding MS1 scan id, and be **non-zero** for all but any leading MS2 scans. A `precursorScanNum`-resolving implementation yields all-null here and fails loudly. **No other available fixture separates those two implementations.** Skip with a clear message if the fixture is absent (it is gitignored); never silently pass. |
| `MzxmlRtConversionTest` | unit | `PT90S` → exactly **1.5**; `PT1.38S` → `0.023` (to the bit MassQL produces); `PT1M30S` → 1.5; `PT1H` → 60. |
| `MzxmlDecodeTest` | unit | `micro.mzXML`: big-endian; **interleaved pairs** correctly de-interleaved; `precision="32"` widened via `(double)(float)`, asserted on **raw bits**; `precision="64"`; zlib-compressed and uncompressed; absent `compressionType` treated as uncompressed. |
| `MzxmlPolarityTest` | unit | `"+"` → 1, `"-"` → 2, absent → 0. |
| `MzxmlEdgeCaseTest` | unit | `empty_msLevel_tag.mzXML` handled per the rule derived in §5; `peaksCount="0"` yields an empty scan; truncated file throws with **no partial table**. |
| `MzxmlSchema20IT` | IT | `data/DP00570_F02.mzxml` (schema 2.0) parses: **916 scans, 229 MS1, 687 MS2**. |
| `InstrumentAttributeCrossCheckIT` | IT | A free check with **no Python in the loop**: the Ewing file's own `basePeakMz`, `basePeakIntensity` and `totIonCurrent` scan attributes vs our computed values. Hand-check the `peaksCount="3"` scans noted in Step 2. Expect minor float drift on `tic`; a **systematic** mismatch is a bug. (Full form in [Step 8](Tech_Step8.md).) |
| `VendoredProvenanceTest` | unit | Every file under `io/vendor/` contains the provenance header and the string `EPL-1.0` — makes the licence obligation a build-enforced fact rather than a convention. |

## Done when

- [ ] `mvn verify` green; `mvn dependency:tree` still shows **no** dsiutils, fastutil, Guava or logback, and the
      measured closure is unchanged from [Step 6](Tech_Step6.md).
- [ ] `docs/VENDORED.md` records the upstream SHA, all four modifications with before/after lines, and the
      EPL-1.0 election citing the **pom** as authority.
- [ ] Every file in `io/vendor/` carries the provenance header; `VendoredProvenanceTest` enforces it.
- [ ] **`Ms1ScanDocumentOrderIT` passes on the Ewing mzXML** — the assertion that proves the document-order rule.
- [ ] All four RT forms convert correctly; `precision="32"` asserted on raw bits.
- [ ] `SpectraFile.open` handles `.mzXML` and `.mzxml`; the Step 6 close/leak test passes on mzXML too.
- [ ] `docs/READER_RULES.md` extended with the mzXML column and the `msLevel`-absent rule.

## References

- `SPIKE.md` §5 (**superseded on the mzXML dependency question by Correction C1**), §6c (the Ewing fixture's four
  justifications; the RT-units table), §9
- Corrections **C1** and **C3** in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)
- `pyteomics/mzxml.py` — `_determine_dtype`, `_decode_peaks`; `pyteomics/xml.py:118-143` —
  `XMLValueConverter.duration_str_to_float` (ISO-8601 → **minutes**)
- `massql/msql_fileloading.py:414-475` (mzXML loader), `:442`/`:463` (RT), `:517-523` (polarity)
- Upstream: `github.com/msdk/msdk/blob/master/msdk-io-mzxml/src/main/java/io/github/msdk/io/mzxml/MzXMLFileParser.java`
- [Step 6](Tech_Step6.md) §4 — the `ms1scan` document-order rule this step proves
