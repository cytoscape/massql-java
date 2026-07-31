# Tech Step 6 — Reader interface, MGF reader, mzML reader

## Goal

`SpectraFile.open(path)` sniffs the format and produces MS1/MS2 `SpectrumTable`s whose decoded values are
bit-identical to what MassQL's Python loader produces — for MGF and mzML.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | Provides the build, the two-artifact dependency policy (javolution + antlr — **no MSDK**, see Correction C16), and `MassqlException`. |
| [Step 5](Tech_Step5.md) | Provides `SpectrumTable` / `SpectrumTableBuilder` — the target this step fills, including the `scanRt` double-precision requirement. |
| [Step 1](Tech_Step1.md) | Provides `oracle/NOTES_fileloading.md`. **Read it before writing any code here.** |
| [Step 2](Tech_Step2.md) | Provides `micro.mgf`, `micro.mzML` and the goldens these tests assert against. |

## Context

`massql/msql_fileloading.py` (892 lines) is the authoritative reader specification, not the MassQL docs and not
pyteomics' API docs — field names and units are undocumented there, so the source settles them. Every value the
Java readers produce must match what that file produces, because [Step 8](Tech_Step8.md) asserts
**bit-identical** intensities and the goldens are whatever Python computed.

Only `_load_data_mzML_pyteomics` is live (dispatched at `:103`); three other mzML loaders remain in the file as
dead code. Read the right one.

Governing sections: `SPIKE.md` §3 (population by format), §5 (reader dependencies — **see Correction C1**), §6a
(RT units, `ms1scan` document order), §6c (RT units table), §12.

## Scope

**In scope**
- `SpectraReader` interface, `SpectraFile` (`AutoCloseable`), format sniffing.
- `MgfReader` — hand-written.
- `MzmlReader` — over `msdk-io-mzml`.
- The mzML and MGF decode, RT and `ms1scan` rules below.
- Closing out the `commons-codec` / `commons-pool2` / `cdk-formula` exclusion verification left open by
  [Step 3](Tech_Step3.md).

**Out of scope**
- **mzXML** — [Step 7](Tech_Step7.md), which reuses the `SpectraReader` interface defined here.
- Cross-format parity assertions against the Python dumps — [Step 8](Tech_Step8.md).
- Any query evaluation — [Step 9](Tech_Step9.md).
- Deciding `ms1scan` → null conversion. This step produces the **raw `0` sentinel**;
  [Step 10](Tech_Step10.md) owns the 0 → null rule.

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/io/SpectraReader.java` | The interface |
| `src/main/java/…/massql/io/SpectraFile.java` | `AutoCloseable` handle + `open()` sniffing |
| `src/main/java/…/massql/io/Format.java` | `MGF`, `MZML`, `MZXML` |
| `src/main/java/…/massql/io/MgfReader.java` | ~150–200 LOC |
| `src/main/java/…/massql/io/MzmlReader.java` | MSDK-backed |
| `src/main/java/…/massql/io/BinaryDecoder.java` | Shared base64/zlib/float-widening helpers — **not** shared byte order (see §5) |
| `src/test/java/…/io/*Test.java` | The test set below |
| `docs/READER_RULES.md` | The per-format rule table, as the single reference for Steps 6 and 7 |

## Specification

### 1. The interface

```java
public interface SpectraReader {
    /** Streams the file once and returns both tables. */
    LoadedSpectra read(Path path) throws MassqlException;
    boolean supports(Format f);
}

public record LoadedSpectra(SpectrumTable ms1, SpectrumTable ms2) { }

public final class SpectraFile implements AutoCloseable {
    public static SpectraFile open(Path path) throws MassqlException;   // sniffs format
    public Format format();
    public SpectrumTable ms1();      // never null; may be empty (MGF)
    public SpectrumTable ms2();
    @Override public void close();
}
```

**`AutoCloseable` is mandatory, not stylistic.** MSDK memory-maps files, so an unclosed `SpectraFile` holds a
mapped region and a file descriptor; Phase 2's `shutDown()` depends on release, and
[Step 12](Tech_Step12.md) tests opening and closing many files without leaking. Close every MSDK resource in
`close()`, and make `close()` idempotent.

**Format sniffing** — by content, with the extension only as a tiebreak, because the fixtures disagree on case
(`small.mzXML` from msconvert vs `DP00570_F02.mzxml` from Ewing) and `SPIKE.md` uses both spellings:
- First non-blank line begins with `BEGIN IONS`, or the file contains no `<`, → **MGF**.
- XML whose root element (or first `<mzML`/`<indexedmzML`) is mzML → **MZML**.
- XML whose root contains `<mzXML` or `<msRun` → **MZXML**.
- Anything else → `MassqlException` naming the file and what was found. Never guess.

Extension matching must be case-insensitive everywhere.

**`ms1()` is never null.** For MGF it is an **empty** `SpectrumTable` (zero rows, zero scans), not null, which
keeps [Step 10](Tech_Step10.md) free of null checks.

> ⚠ Correction C14: the original rationale here was wrong. MassQL's MGF `ms1_df` is **not** empty — it is a
> synthetic 1-row all-zero placeholder (`i=0, mz=0, scan=1, rt=0, polarity=1`), so
> `massql_query.py:170`'s `len(ms1_df) == 0` branch **never fires for MGF**. The precursor lookup runs anyway
> and yields nulls because `ms1scan` is 0 and no MS1 scan 0 exists. An empty Java table produces identical
> results, so the conclusion stands — but do not expect that branch to be the mechanism.

### 2. MGF reader

Hand-written, ~150–200 LOC. The alternative (`uk.ac.ebi.pride.tools:mgf-parser`, 28 KB) drags fastutil (23 MB),
logback and both the javax and jakarta JAXB stacks — every one of those forbidden by `DEPENDENCY_POLICY.md`.

Structure: `BEGIN IONS` … `END IONS` blocks, each with `TITLE=`, `PEPMASS=`, `CHARGE=`, optional
`RTINSECONDS=`, then `mz intensity` lines.

| Field | Rule |
|---|---|
| **All peaks are MS2** | MGF is an MS2-only peak-list format. `mslevel` = 2, and the MS1 table is empty. |
| `precmz` | First token of `PEPMASS=` (a second token, if present, is precursor intensity — ignore it). |
| **`charge`** | From `CHARGE=`; strip a trailing `+`/`-`. **Absent → `1`, NOT 0 and not null.** ⚠ See Correction C6 — `SPIKE.md` §3 is wrong here. The live loader is `_load_data_mgf_pyteomics`, whose charge handling is `params.get('charge', [1])` with `except: charge = 1` (`msql_fileloading.py:192-203`). Since only `0` is null-converted ([Step 10](Tech_Step10.md) §4), **MGF `charge` is never null** — a genuine 1+ and an absent `CHARGE=` are indistinguishable. Confirmed against the golden: `plusrise_results.json` charge counts are `{1: 653, 2: 10, 3: 1}`, zero nulls. |
| **`polarity`** | **Not read at all** on the live path (Correction C8). Verify the value MassQL actually emits against `ms1_df`/`ms2_df` rather than assuming; a `POLARITY` condition cannot meaningfully filter an MGF. |
| **`rt`** | `float(RTINSECONDS)/60.0`. **Absent → `0.0`, not null** (`msql_fileloading.py:179-181, :327-328`). |
| **`ms1scan`** | **Hardcoded `0`** for every scan (`msql_fileloading.py:394`). Not derived, not looked up. |
| **`scan`** | **`SCANS=` when present, else the 1-based index of the block in the file** — `scan = params.get('scans', index + 1)` (`msql_fileloading.py:177`). Resolved in Correction C7; the golden's first record is `scan: 576`. Check whether `PlusRise.mgf` actually carries `SCANS=` so you know which branch the fixture exercises. |

**Scan numbering — resolved (Correction C7).** `msql_fileloading.py:177`:

```python
scan = params.get('scans', index + 1)      # 'SCANS=' if present, else 1-based block index
```

So: **`SCANS=` when present, otherwise the 1-based position of the block in the file.** The manual fallback
loader agrees (`:326-327`). Reproduce exactly this, including the fallback. Getting it wrong shifts every row's
identity and makes the Step 12 differential fail in a way that looks like a filtering bug.

`plusrise_results.json`'s first record is `scan: 576`, which is consistent with either branch —
[Step 2](Tech_Step2.md) should grep `PlusRise.mgf` for `SCANS=` and record which branch the fixture exercises,
because a reader that implements only the index fallback would still pass on a file that carries `SCANS=`.

**Which MGF loader is live — BOTH are, depending on the file (Correction C14).** `_load_data_mgf` (`:145`)
calls `_load_data_mgf_pyteomics` first and falls back to `_load_data_mgf_manual` **only if pyteomics returns
zero rows**. Measured:

| Fixture | pyteomics rows | Live loader | `scan` dtype | id source |
|---|---|---|---|---|
| `PlusRise.mgf` | **0** (pyteomics cannot index it) | **manual** | **str** | `SCANS=` |
| `DP00570_F02.mgf` | 107,178 | pyteomics | int64 | block index |
| `micro.mgf` | 7 | pyteomics | int64 | block index |

The unified rule above (`SCANS=` else block index) holds for all three, so implement that once. But note
`PlusRise.mgf`'s ids arrive as **strings** — the same class of type inconsistency as the mzXML `ms1scan` issue
(C12) — and that **MassQL loads only 21,942 of its 34,513 spectra** (758,544 peak rows), which is the number
[Step 8](Tech_Step8.md) asserts. Read `_load_data_mgf_pyteomics` (`:155-244`) as the primary specification — read that one, not the manual parser, and not
the third path at `:388-396` (which belongs to `_load_data_gnps_json` and hardcodes `charge = 1`, `polarity = 1`
with `# TODO` comments).

Parsing rules: tolerate blank lines and comment lines (`#`, `;`); tolerate CRLF; peak lines may be
whitespace- or tab-separated; a malformed peak line is an error, not a skip (see §6).

### 3. mzML reader — ⚠ VENDORED, not a dependency (Correction C16)

**This spec originally said "use `io.github.msdk.io.mzml.MzMLFileImportMethod`" as a Maven dependency. That is
no longer possible.** `msdk-datamodel` cannot link without Guava (`MsScan` declares
`Range<Double> getScanningRange()` in the interface; `SimpleMsScan` holds a `Range` field and calls
`Preconditions`), and Guava brings 2.85 MB plus three OSGi resolution hazards — Cytoscape exports Guava **9.0.0**
against MSDK's **27.1**, Guava 27.1 is itself a bundle exporting `com.google.common.*` so bnd emits an
`Import-Package` Felix cannot satisfy, and `jsr305` exports `javax.annotation`. See `DEPENDENCY_POLICY.md`.

**So vendor the mzML parser, the same way [Step 7](Tech_Step7.md) vendors the mzXML one.** Follow Step 7 §1–§3
for the mechanics (vendor the minimum, record every modification, provenance header + EPL-1.0 election in
`docs/VENDORED.md`). Specifics for mzML:

**Vendor from** `msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/`:
`data/MzMLParser.java`, `MzMLFileImportMethod.java`, `util/MSNumpress.java`,
`util/ByteBufferInputStream.java`, `util/FileMemoryMapper.java`, `util/TagTracker.java`, plus the minimal
`data/` value classes the parser populates.

**Modifications required, and record each:**

| # | Change | Why |
|---|---|---|
| 1 | Replace `io.github.msdk.datamodel` types (`MsScan`, `SimpleMsScan`, `RawDataFile`, `IsolationInfo`, …) with our own minimal holders, or write straight into `SpectrumTableBuilder` | **This is what removes Guava.** Those interfaces expose `Range<Double>` |
| 2 | Replace `org.apache.commons.io.IOUtils` with plain Java | Removes `commons-io` (208,700 B) |
| 3 | Remove the slf4j `Logger` in `MzMLFileImportMethod` | Constraint 2: the SDK logs nothing |
| 4 | Replace any Guava `Range`/`Preconditions` use with a plain pair / explicit checks | Constraint: no Guava |

**`MSNumpress` is the reason this is vendoring rather than hand-writing.** It is self-contained, has no Guava,
and hand-writing Numpress decompression would be the single largest avoidable risk in the spike. Take it
verbatim.

**Keep instantiating `javolution.xml.internal.stream.XMLStreamReaderImpl` DIRECTLY**, exactly as upstream does.
That is the whole reason javolution is a dependency: the JDK's `javax.xml.stream.XMLInputFactory` discovers
implementations via `ServiceLoader`, banned by constraint 1, and naming the JDK's internal impl would need
`Class.forName`, also banned.

If a modification needs more than a local edit, **stop and report** rather than rewriting the parser — at that
point hand-writing mzML becomes a spec change, not an implementation choice.

| Field | Rule |
|---|---|
| `mslevel` | From the spectrum's MS level; route peaks to the MS1 or MS2 table. Levels > 2 are out of scope — skip them and count how many were skipped, reporting it as a diagnostic. |
| **`rt`** | Read `scan start time` **and its declared unit**. Convert **only if** the unit is seconds: `if unit == second: rt /= 60` (`msql_fileloading.py:564-571`). `data/small.mzML` declares `unitName="minute"` → **pass through unconverted**. Read `unitName` *or* `unitAccession` — either may carry it. Store at **double** precision into `scanRt`. |
| `precmz` | The selected-ion m/z from `<precursor>`. Absent → `0` sentinel. |
| `charge` | Charge state if recorded; absent → `0` sentinel. |
| **`ms1scan`** | **Document order.** See §4 — do **not** read `spectrumRef`. |
| `polarity` | Positive → 1, negative → 2, unknown → 0. |

**Binary arrays: little-endian.** mzML's binary data arrays are little-endian; `ByteBuffer` defaults to
big-endian, so set the order explicitly. **Do not share a configured buffer with the mzXML reader**, which needs
big-endian ([Step 7](Tech_Step7.md) §4).

**32-bit precision — the bit-identity trap.** For a `32-bit float` binary data array, pyteomics decodes to
`float32` and Python widens to double downstream. So the golden value is `(double)(float)raw`, **not** a
full-precision double. In Java: `readFloat()` then widen.

```java
double v = (double) buf.getFloat();     // correct
double v = buf.getDouble();             // WRONG for a 32-bit array — different bits
```

Reading 8 bytes, or decoding to double directly, produces values that are *nearly* right, and
[Step 8](Tech_Step8.md) fails with a confusing near-miss. Put this in `BinaryDecoder` once so both readers share
one implementation of the widening rule (but not of byte order).

### 4. `ms1scan` — document order, not the file's own linkage

**This is the highest-risk rule in the spike.** The obvious implementation reads the explicit precursor → survey
link: `spectrumRef` on `<precursor>` in mzML. **MassQL does neither that nor the mzXML equivalent** — those two
attribute names appear **zero times** in all 892 lines of `msql_fileloading.py`
(`oracle/NOTES_fileloading.md` records the grep proving it).

Every MassQL loader instead tracks `previous_ms1_scan`: the id of the most recent MS1 spectrum seen while
streaming in **document order**, initialized to `0`.

```
previous_ms1_scan = 0
for each spectrum in document order:
    if mslevel == 1: previous_ms1_scan = this scan id
    else:            ms1scan = previous_ms1_scan
```

Three consequences:
- An MS2 scan appearing **before any MS1 scan** gets `ms1scan = 0` → null downstream. **That is where the 0
  sentinel comes from**, and why the sentinel rule exists at all.
- MGF hardcodes `0`, hence null.
- **A reader that "correctly" resolves `spectrumRef` will disagree with MassQL** whenever that reference does not
  point at the immediately preceding MS1 scan — interleaved acquisition, multiple MS1 scans per cycle, or a
  reference pointing further back. For simple DDA they coincide.

**Reproduce the document-order behaviour** to match the goldens, and record the divergence from file-declared
linkage as a **named known deviation** in the README ([Step 13](Tech_Step13.md)).

`data/small.mzML` **cannot** validate this: its `spectrumRef` values happen to agree with document order
(goldens `ms1scan` = 2, 9, 16, 23, 36, 43), so both implementations pass. The decisive assertion lives in
[Step 7](Tech_Step7.md) on a fixture with no declared linkage at all. Do not conclude from a green `small.mzML`
test that this rule is verified — add a comment saying so at the assertion site.

### 5. What `BinaryDecoder` shares — and what it must not

| Share | Do **not** share |
|---|---|
| Base64 decode (`java.util.Base64`) | **Byte order** — mzML little-endian, mzXML big-endian |
| zlib inflate (`java.util.zip.InflaterInputStream`) | **Array layout** — mzML has separate m/z and intensity arrays; mzXML interleaves pairs |
| The `readFloat()`-then-widen rule for 32-bit precision | Numpress — mzML only |

Take byte order as an explicit parameter. A shared, pre-configured `ByteBuffer` is how a 60×-style silent bug
gets introduced across two readers at once.

### 6. Error handling

- **Truncated or malformed file → `MassqlException`, and no partial results.** Build into a
  `SpectrumTableBuilder` and only `build()` on success; never hand back a half-populated table. A partially-read
  file that silently returns 40 of 48 scans is worse than an exception.
- Missing/empty `msLevel`: mzML normally always has it; if absent, treat the spectrum as unreadable and throw
  with the spectrum id named. (mzXML's more permissive handling is [Step 7](Tech_Step7.md) §5.)
- Unreadable path / not a file / empty file → `MassqlException` naming the path.
- Wrap MSDK's `MSDKException` in `MassqlException`, preserving the cause. Do not let MSDK types escape into
  public signatures — that is what keeps the reader swappable if the vendoring fallback is ever needed.

### 7. Confirm the closure is unchanged

The commons-codec / commons-pool2 / cdk-formula verification [Step 3](Tech_Step3.md) left open is **moot** —
all three arrived via MSDK, which is no longer a dependency (C16).

What this step must confirm instead: after vendoring, `bash scripts/dependency-audit.sh` still reports the
**two-artifact, 785,599 B (0.749 MB)** closure. If vendoring accidentally reintroduces a dependency, the
enforcer fails at `validate` — but re-run the audit so the number in `dependency-audit.txt` reflects reality.

## Known traps

- **`buf.getDouble()` on a 32-bit array.** Near-right values, confusing Step 8 failure. §3.
- **Sharing one configured `ByteBuffer` between readers.** mzML is little-endian, mzXML is big-endian. §5.
- **Resolving `spectrumRef` for `ms1scan`.** Intuitive, wrong, and `small.mzML` won't catch it. §4.
- **Converting mzML `rt` unconditionally.** `small.mzML` declares minutes; a blind ÷60 is a silent 60× error
  that passes every MGF-only test. The conversion is **conditional on the declared unit**.
- **Nulling `rt` when MGF has no `RTINSECONDS`.** It is `0.0`, a real value. Confusing `0.0` with null here
  propagates all the way to the result JSON.
- **Nulling the `0` sentinels in the reader.** `precmz`/`ms1scan`/`charge` stay `0` here;
  [Step 10](Tech_Step10.md) converts. Doing it early means Step 10 can't distinguish "absent" from "converted".
- **Assuming MGF scan ids are block ordinals.** The golden's first scan is 576. §2.
- **Forgetting `close()`**, or making it non-idempotent. MSDK memory-maps.

## Tests required

Unit (`*Test.java`), on the [Step 2](Tech_Step2.md) micro-fixtures — no large files, no network.

| Test | Pins |
|---|---|
| `FormatSniffTest` | MGF / mzML / mzXML detected by content; **case-insensitive** extensions (`.mzXML` and `.mzxml`); unknown content throws naming the file. |
| `MgfReaderTest` | `PEPMASS` with and without a trailing intensity token; `CHARGE=2+` → 2; **absent `CHARGE` → `1`** (Correction C6 — *not* 0, and therefore never null downstream); `RTINSECONDS=90` → **1.5**; **absent `RTINSECONDS` → `0.0`, not null**; `ms1scan == 0` on every scan; MS1 table **empty, not null**; CRLF and blank lines tolerated; malformed peak line throws. |
| `MgfScanNumberingTest` | Scan ids match what `msql_fileloading.py` derives — assert against the micro-fixture golden, including the missing-field fallback. |
| `MzmlReaderTest` | Scan/peak counts on `micro.mzML`; MS1/MS2 routing; `precmz`, `charge`, polarity 1/2/0; `scanRt` exact at **double** precision. |
| `MzmlRtUnitTest` | `micro.mzML` declaring `unitName="second"` **is** converted; a copy declaring `"minute"` is **not**. Both directions, one fixture each — this is the test that catches a 60× error. |
| `Ms1ScanDocumentOrderTest` | An MS2 scan **before any MS1** → `ms1scan == 0`; subsequent MS2 scans → the most recent preceding MS1 id. Include a comment stating that the decisive `spectrumRef`-vs-document-order assertion is in [Step 7](Tech_Step7.md), because this fixture cannot distinguish them. |
| `Float32WideningTest` | A 32-bit array whose float32 and float64 decodes differ visibly decodes to `(double)(float)raw`. Assert on **raw bits** (`Double.doubleToLongBits`), not `assertEquals` with a delta. |
| `ReaderErrorPathTest` | Truncated mzML → throws, **no partial table**; empty file; missing path; directory instead of file. |
| `SpectraFileCloseTest` | `close()` idempotent; open/close 200 files in a loop without exhausting descriptors. |

## Done when

- [ ] `mvn test` green.
- [ ] `SpectraFile.open` correctly sniffs all three formats (mzXML routes to the Step 7 reader once it exists;
      until then it throws a clear "not yet implemented" naming Step 7).
- [ ] All three MGF rules verified: `ms1scan` always 0, `rt` ÷60 with `0.0` default, `charge` 0 sentinel.
- [ ] Both mzML RT directions verified with separate fixtures.
- [ ] `Float32WideningTest` asserts on raw bits and passes.
- [ ] `docs/READER_RULES.md` contains the per-format rule table, including the MGF scan-numbering rule you
      derived from source.
- [ ] `dependency-audit.txt` updated with the final verified exclusion set and measured total.

## References

- **`massql/msql_fileloading.py` is the authoritative spec.** Key lines: `:103` (live loader dispatch),
  `:525-650` (`_load_data_mzML_pyteomics`), `:564-571` (mzML RT conditional), `:179-181`/`:327-328` (MGF RT),
  `:394` (MGF `ms1scan = 0`)
- `oracle/NOTES_fileloading.md` from [Step 1](Tech_Step1.md)
- `SPIKE.md` §3 (population by format, the `ms1scan` derivation note), §5, §6a, §6c (RT-units table)
- MSDK source: `github.com/msdk/msdk/blob/master/msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLParser.java`
- Consumers: [Step 7](Tech_Step7.md) reuses `SpectraReader`; [Step 8](Tech_Step8.md) asserts parity
