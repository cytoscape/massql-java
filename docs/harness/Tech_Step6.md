# Tech Step 6 — Reader interface, MGF reader, mzML reader

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

## Goal

`SpectraFile.open(path)` sniffs the format and produces MS1/MS2 `SpectrumTable`s whose decoded values are
bit-identical to what MassQL's Python loader produces — for MGF and mzML.

## Prerequisites

| Step | Why |
|---|---|
| [Step 3](Tech_Step3.md) | Provides the build, the two-artifact dependency policy (javolution + antlr — **no MSDK**, see Correction C16), and `MassqlException`. |
| [Step 5](Tech_Step5.md) | Provides `SpectrumTable` / `SpectrumTableBuilder` — the target this step fills, including the `scanRt` double-precision requirement. |
| [Step 1](Tech_Step1.md) | Provides [`NOTES_fileloading.md`](oracle/NOTES_fileloading.md) — in-repo at `docs/harness/oracle/` since Correction **C41**. **Read it before writing any code here.** |
| [Step 2](Tech_Step2.md) | Provides `micro.mgf`, `micro.mzML` and the goldens these tests assert against. |

## Context

`massql/msql_fileloading.py` (892 lines) is the authoritative reader specification, not the MassQL docs and not
pyteomics' API docs — field names and units are undocumented there, so the source settles them. Every value the
Java readers produce must match what that file produces, because [Step 8](Tech_Step8.md) asserts
**bit-identical** intensities and the goldens are whatever Python computed.

Only `_load_data_mzML_pyteomics` is live (dispatched at `:103`); three other mzML loaders remain in the file as
dead code. Read the right one.

Governing sections: [`SPIKE.md`](SPIKE.md) §3 (population by format), §5 (reader dependencies — **see Correction C1**), §6a
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
| `src/main/java/…/massql/io/SpectraStream.java`, `ScanView.java` | The cursor (C22) |
| `src/main/java/…/massql/io/SpectraFile.java` | `open()` + format sniffing |
| `src/main/java/…/massql/io/Format.java` | `MGF`, `MZML`, `MZXML` |
| `src/main/java/…/massql/io/MgfReader.java` | ~150–200 LOC |
| `src/main/java/…/massql/io/MzmlReader.java` | Hand-written XML walk over the vendored decode layer (C21) |
| `src/main/java/…/massql/io/vendor/*` | 13 vendored decode-layer files (C21) |
| ~~`BinaryDecoder.java`~~ | **Dropped — Correction C21a.** Nothing left to share: mzML decode is the vendored `MzMLPeaksDecoder`; mzXML differs in byte order, layout, and has no Numpress |
| `src/test/java/…/io/*Test.java` | The test set below |
| [`READER_RULES.md`](READER_RULES.md) | The per-format rule table, as the single reference for Steps 6 and 7. ⚠ **Moved to `docs/harness/` by Correction C41** — it is an engineering record, read by someone confirming the steps rather than by an SDK consumer. |

## Specification

### 1. The interface

> ⚠ **REPLACED by Correction C22 — this is a streaming cursor, not two whole-file tables.** The
> original API below returned `SpectrumTable ms1()` / `ms2()` for the entire file. That projects a
> **500 MB input to 1.0–1.9 GB of heap**, which OOMs the host. See C22 for the measurements
> and why streaming is possible at all.

```java
public interface SpectraStream extends AutoCloseable {
    /** Another spectrum available? Repeatable; false PERMANENTLY once drained. */
    boolean hasNext();
    /** Advance and return it. Throws NoSuchElementException past the end.
     *  The SAME object every call -- valid only until the following next(). */
    ScanView next();
    List<String> diagnostics();
    @Override void close();
}

public interface ScanView {   // NB: what next() returns -- one reused instance, never a fresh object
    int scanId();  int msLevel();  double rt();  int polarity();
    double precmz();  int ms1scan();  int charge();     // raw 0 sentinels; Step 10 converts
    int peakCount();
    /** Copy into a single-scan SpectrumTable. THIS is what makes Step 5 reusable verbatim. */
    SpectrumTable materialize();
}

public final class SpectraFile {
    public static SpectraStream open(Path path) throws MassqlException;   // format sniffed
}
```

> ⚠ **Correction C42 reshaped this interface.** It was `boolean next()` plus `ScanView current()`, with an
> undocumented `Format format()`. Three changes, all made while reviewing [Step 11](Tech_Step11.md):
>
> - **`hasNext()`/`next()`** — idiomatic Java, and one call per iteration instead of two. The canonical loop is
>   `while (s.hasNext()) { ScanView v = s.next(); … }`.
> - **`next()` throws `NoSuchElementException` past the end**, and `hasNext()` stays `false` once drained. This
>   is what makes [Step 11](Tech_Step11.md) §1's single-pass rule enforceable: a spent stream handed to a second
>   query now fails loudly instead of returning an empty list that reads as "matched nothing".
> - **`format()` removed** — no production code ever called it; only `FormatSniffTest` did, and it can call the
>   package-private `SpectraFile.sniff` directly. `Format` is therefore package-private now, appearing in no
>   public signature.
>
> Each reader carries a `NOT_STARTED`/`PEEKED`/`EXHAUSTED` peek machine so `hasNext()` is **repeatable** —
> calling it twice must not consume a scan, which is the classic way this shape breaks.
> `SpectraStreamContractTest` asserts all of it across all three readers.
>
> **The type is deliberately not an `Iterator`.** `next()` returns the same mutable object each call, so
> `Iterator` would make `StreamSupport.stream(…).toList()` legal and silently collect N aliases of one view.

`materialize()` returning a **single-scan** `SpectrumTable` is the hinge of the design: every Step 5
primitive — `Reductions.sum(t, 0, I)`, `t.mzWindow(0, lo, hi)`, `argmax`, the derived columns — works
unchanged on a one-scan table, so **Step 5 needs no code change and its 44 tests stay green**.

Executor shape for Steps 9/10:

```
SpectrumTable retainedMs1 = null;                 // the ONE retained scan
while (stream.next()) {
    ScanView s = stream.current();
    if (s.msLevel() == 1) retainedMs1 = s.materialize();
    if (s.msLevel() == wanted && conditionsHold(s)) emitRow(s, retainedMs1);
}
```

**`AutoCloseable` is mandatory, and now genuinely load-bearing.** `open()` maps the file and parses
forward on demand, so the cursor holds a mapped region and a descriptor for its whole lifetime.
A host's own shutdown depends on release, and [Step 12](Tech_Step12.md) tests 200 open/close cycles.
`close()` must be idempotent. The mapped region is **off-heap**, so a 500 MB file costs address
space, not heap.

**Format sniffing** — by content, with the extension only as a tiebreak, because the fixtures disagree on case
(`small.mzXML` from msconvert vs `DP00570_F02.mzxml` from Ewing) and [`SPIKE.md`](SPIKE.md) uses both spellings:
- First non-blank line begins with `BEGIN IONS`, or the file contains no `<`, → **MGF**.
- XML whose root element (or first `<mzML`/`<indexedmzML`) is mzML → **MZML**.
- XML whose root contains `<mzXML` or `<msRun` → **MZXML**.
- Anything else → `MassqlException` naming the file and what was found. Never guess.

Extension matching must be case-insensitive everywhere.

**`ms1()` is never null.** For MGF it is an **empty** `SpectrumTable` (zero rows, zero scans), not null, which
keeps [Step 10](Tech_Step10.md) free of null checks.

> ⚠ Correction C14: the original rationale here was wrong. MassQL's MGF `ms1_df` is **not** empty — it is a
> fabricated 1-row table, so `massql_query.py`'s `len(ms1_df) == 0`'s `len(ms1_df) == 0` branch **never fires for MGF**. The
> precursor lookup runs anyway and yields nulls because `ms1scan` is 0 and no MS1 scan 0 exists. An empty
> Java table produces identical results, so the conclusion stands — but do not expect that branch to be the
> mechanism.
>
> ⚠ **Correction C33b refines this further: the fabricated row takes TWO forms, not one.** The all-zero
> shape (`i=0, mz=0, scan=1, rt=0, polarity=1`) is the pyteomics loader's `except` branch, reached only when
> its peak loop never ran — `PlusRise.mgf`'s case. On the normal path `peak_dict` **leaks from the loop**, so
> the row is a byte-for-byte **duplicate of the last MS2 peak**, carrying that scan's id (`micro.mgf` → 3,
> `DP00570_F02.mgf` → 625). See [`READER_RULES.md`](READER_RULES.md) for the table.
>
> 📌 **The `polarity=1` in the all-zero shape above was the clue that C8 was wrong** — the loader defaults
> polarity to 1, and it is written right here. C33 found it three steps later via the Step 8 gate instead.
> A value recorded incidentally inside an example is easy to read past; when a rule and an example disagree,
> the example is data.

### 2. MGF reader

Hand-written, ~150–200 LOC. The alternative (`uk.ac.ebi.pride.tools:mgf-parser`, 28 KB) drags fastutil (23 MB),
logback and both the javax and jakarta JAXB stacks — every one of those forbidden by `DEPENDENCY_POLICY.md`.

Structure: `BEGIN IONS` … `END IONS` blocks, each with `TITLE=`, `PEPMASS=`, `CHARGE=`, optional
`RTINSECONDS=`, then `mz intensity` lines.

| Field | Rule |
|---|---|
| **All peaks are MS2** | MGF is an MS2-only peak-list format. `mslevel` = 2, and the MS1 table is empty. |
| `precmz` | First token of `PEPMASS=` (a second token, if present, is precursor intensity — ignore it). |
| **`charge`** | The block's own `CHARGE=` if it has one; otherwise the **file-level `CHARGE=` header**; otherwise `1`. Strip a trailing `+`/`-`, and take the **first** value of a multi-charge header. **Never `0`, never null** (Corrections C6, C44).<br><br>The file-level fallback is not a courtesy — pyteomics copies the file header into *every* spectrum's params, so `CHARGE=2+ and 3+` reaches MassQL as `[2, 3]` on each spectrum and it takes element 0. `DP00570_F02.mgf` declares that header and then omits `CHARGE=` from **583 of its 625 blocks**, all of which are therefore charge 2; only the 42 blocks carrying their own `CHARGE=1+` are 1. Reading per-block lines alone gives `{1: 625}` where the oracle gives `{2: 583, 1: 42}`.<br><br>Only the header *before the first* `BEGIN IONS` counts — pyteomics stops reading it there.<br><br>The loader is `_load_data_mgf_pyteomics`, whose handling is `params.get('charge', [1])` with `except: charge = 1`. Since only `0` is null-converted ([Step 10](Tech_Step10.md) §4), **MGF `charge` is never null** — a genuine 1+ and an inherited default are indistinguishable. ⚠ [`SPIKE.md`](SPIKE.md) §3's "null if absent" is wrong here. |
| **`polarity`** | **Hardcoded `1`** (positive). ⚠ **Correction C33 fixes C8**, which said "not read at all → 0". Not read from any *header* is true; but both loaders write `"polarity": 1  # Default` into every peak dict (`msql_fileloading.py:67`, `:86`), so MassQL emits **1** for every MGF row — measured `{1: all}` across 866k rows. A `POLARITY` condition still cannot meaningfully filter an MGF, since the value is a constant rather than data. **This row already said "verify the value MassQL actually emits rather than assuming", and the implementation assumed anyway** — the note below records why that was easy to miss. |
| **`rt`** | `float(RTINSECONDS)/60.0`. **Absent → `0.0`, not null** (`msql_fileloading.py:179-181, :327-328`). |
| **`ms1scan`** | **Hardcoded `0`** for every scan (`msql_fileloading.py:394`). Not derived, not looked up. |
| **zero-intensity peaks** | ⛔ **DROPPED.** `if intensity == 0: continue` opens the peak loop. **MGF only** — mzML/mzXML retain them, and `small.mzML`'s dump proves it with eight leading `0x0.0p+0` intensities. Latent until Correction C36: no MGF fixture had a zero-intensity peak, so the Step 8 gate could not detect that we kept them. `micro_zeroint.mgf` now pins it. |
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

### 3. mzML reader — vendored DECODE layer + hand-written XML walk (Correction C21)

> ⚠ This section previously said to vendor `MzMLParser` + `MzMLFileImportMethod` and replace the
> `datamodel` types. **That is not achievable** — `MzMLMsScan`/`MzMLFileImportMethod`/
> `MzMLChromatogram` carry 9/17/11 `msdk-datamodel` imports plus Guava `Range`, slf4j and
> `msdk-spectra`; ~30 files, and replacing the datamodel types means rewriting the scan model. The
> "stop and report if more than a local edit" clause fired. See C21.

**Already vendored to `io/vendor/` — 13 files, 112 KB, zero non-JDK imports.** `MSNumpress` (44 KB)
is byte-identical to upstream; only `MzMLPeaksDecoder` (3 swaps) and `MzMLBinaryDataInfo` (one
annotation) were modified. Full list and provenance in [`VENDORED.md`](../VENDORED.md).

**The decoder already implements the 32-bit widening rule** — `Float.intBitsToFloat(readInt())` into
a `double[]`, i.e. `(double)(float)raw`. Verified on raw bits. **Do not re-implement it and do not
"fix" it to read 8 bytes.**

**Write the XML walk** over `javolution.xml.internal.stream.XMLStreamReaderImpl`, **instantiated
directly** (the JDK's `XMLInputFactory` uses `ServiceLoader`, banned by constraint 1; naming its impl
needs `Class.forName`, also banned). Capture each `binaryDataArray`'s offset and length into an
`MzMLBinaryDataInfo` and decode **on demand** per scan — that is what makes the streaming design work.

Only the slice `scaninfo` needs. **Not** chromatograms, the export path, products, scan windows, or
referenceable param groups unless a fixture forces them.

| Field | Rule |
|---|---|
| **`scan`** | ⚠ **was missing from this spec entirely.** `int(spectrum["id"].replace("scanId=","").split("scan=")[-1])` (`msql_fileloading.py:575`) — strip any `scanId=` prefix, split on `scan=`, take the **LAST** segment. This is the field that determines every row's identity. An id with no `scan=` makes MassQL raise `ValueError`; we throw `MassqlException` naming the id — a documented deviation, a clean error either way |
| `mslevel` | `MzMLCV.cvMSLevel` = `MS:1000511`. Route to the MS1 or MS2 side. Levels > 2 are out of scope: skip and report via `diagnostics()` |
| **`rt`** | `MzMLCV.MS_RT_SCAN_START` = `MS:1000016`, **with its declared unit**. Convert **only if** seconds (`:564-571`). `small.mzML` says `unitName="minute"` → pass through. Read `unitName` *or* `unitAccession`. Store at **double** precision |
| `precmz` | `MzMLCV.cvPrecursorMz` = `MS:1000744`. Absent → `0` sentinel. ⚠ **Read it from `selectedIon[0]` of `precursor[0]` ONLY** — MassQL hard-indexes both (`:603`), so when a scan declares several precursors (multiplexed/MSX acquisition) the **FIRST wins, not the last**. This step originally overwrote on every matching cvParam; no fixture was multi-precursor, so nothing caught it (Correction C31) |
| `charge` | `MzMLCV.cvChargeState` = `MS:1000041`. Absent → `0` sentinel |
| **`ms1scan`** | **Document order.** See §4 — do **not** read `spectrumRef` |
| `polarity` | `cvPolarityPositive` `MS:1000130` → 1, `cvPolarityNegative` `MS:1000129` → 2, else 0 |
| binary arrays | `MzMLArrayType.MZ`/`INTENSITY` (`MS:1000514`/`MS:1000515`); bit length and compression come from **`MzMLBitLength` / `MzMLCompressionType`** |

> **Three gotchas in the vendored code**, found while vendoring:
> `MzMLCV` does **not** define the bit-length or compression accessions — those are on the enums, and
> you will look in `MzMLCV` first. Accessor naming is inconsistent: `MzMLBitLength.getValue()` but
> `MzMLArrayType.getAccession()`. And `MzMLBinaryDataInfo.setBitLength(String)` /
> `setCompressionType(String)` accept raw accession strings, which is the convenient path from a
> cvParam.

**Binary arrays are little-endian** — the vendored `LittleEndianDataInput` handles it. mzXML is
big-endian; never share a configured buffer between the readers.

### 4. `ms1scan` — document order, not the file's own linkage

**This is the highest-risk rule in the spike.** The obvious implementation reads the explicit precursor → survey
link: `spectrumRef` on `<precursor>` in mzML. **MassQL does neither that nor the mzXML equivalent** — those two
attribute names appear **zero times** in all 892 lines of `msql_fileloading.py`
([`NOTES_fileloading.md`](oracle/NOTES_fileloading.md) records the grep proving it).

Every MassQL loader instead tracks `previous_ms1_scan`: the id of the most recent MS1 spectrum seen while
streaming in **document order**, initialized to `0`.

```
previous_ms1_scan = 0
for each spectrum in document order:
    if peak_count == 0: continue                       # <-- Correction C27(b). NOT optional.
    if mslevel == 1:    previous_ms1_scan = this scan id
    else:               ms1scan = previous_ms1_scan
```

> ⚠ **Correction C27(b) — this step shipped without the `peak_count == 0` guard, and no test caught
> it.** Both loaders open with `if len(spectrum["intensity array"]) == 0: continue`
> (`msql_fileloading.py:559` mzML, `:421` mzXML), and that `continue` runs **before**
> `previous_ms1_scan` is assigned — so a zero-peak MS1 is invisible to the chain and the next MS2 links
> to the MS1 *before* it. Confirmed against MassQL's own loader on `micro.mzML`: scan 5 → `ms1scan`
> **2**, not the empty MS1 at scan 4.
>
> It went unnoticed because no real mzXML fixture has a zero-peak scan and the micro golden covers only
> scans 1 and 3 — scan 5 does not match `test_micro.massql`. The fixture had the case all along and
> nothing was looking. `ZeroPeakMs1ChainTest` now pins it; with the guard removed it reports "Got 4 —
> expected 2".
>
> **The scan is still yielded** — only the linkage skips it, matching the MGF split below. Take
> `peak_count` from `defaultArrayLength`, so the guard costs no decode.

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

### 5. Decoding is NOT shared between readers (Correction C21a)

`BinaryDecoder` is **dropped**. There is nothing left to share:

| | mzML | mzXML ([Step 7](Tech_Step7.md)) |
|---|---|---|
| Decoder | vendored `MzMLPeaksDecoder` | its own, inline |
| Byte order | **little**-endian | **big**-endian (`"network"`) |
| Layout | separate m/z and intensity arrays | **interleaved pairs** |
| Numpress | yes, 6 variants | **none** |

The only common ground is base64 + inflate — a handful of JDK calls. Forcing an abstraction over two
decoders that agree on nothing else is how the 60×-class silent bug this spec warns about gets
introduced. Step 7 reuses the already-vendored `ByteBufferInputStream` and decodes inline.

### 6. Error handling

- **Truncated or malformed file → `MassqlException`, and no partial results.** Build into a
  `SpectrumTableBuilder` and only `build()` on success; never hand back a half-populated table. A partially-read
  file that silently returns 40 of 48 scans is worse than an exception.
- Missing/empty `msLevel`: mzML normally always has it; if absent, treat the spectrum as unreadable and throw
  with the spectrum id named. (mzXML's more permissive handling is [Step 7](Tech_Step7.md) §4.)
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
| `MgfReaderTest` | `PEPMASS` with and without a trailing intensity token; `CHARGE=2+` → 2; the **file-level `CHARGE=` header as the per-block default**, a block's own `CHARGE=` overriding it without leaking into the next, a stray `CHARGE=` *between* blocks being ignored, and `1` only when the file declares none anywhere (C6, C44) — plus the real `DP00570_F02.mgf` distribution `{2: 583, 1: 42}`; `RTINSECONDS=90` → **1.5**; **absent `RTINSECONDS` → `0.0`, not null**; `ms1scan == 0` on every scan; MS1 table **empty, not null**; CRLF and blank lines tolerated; malformed peak line throws. |
| `MgfScanNumberingTest` | Scan ids match what `msql_fileloading.py` derives — assert against the micro-fixture golden, including the missing-field fallback. |
| `MzmlReaderTest` | Scan/peak counts on `micro.mzML`; MS1/MS2 routing; `precmz`, `charge`, polarity 1/2/0; `scanRt` exact at **double** precision. |
| `MzmlRtUnitTest` | `micro.mzML` declaring `unitName="second"` **is** converted; a copy declaring `"minute"` is **not**. Both directions, one fixture each — this is the test that catches a 60× error. |
| `Ms1ScanDocumentOrderTest` | An MS2 scan **before any MS1** → `ms1scan == 0`; subsequent MS2 scans → the most recent preceding MS1 id. Include a comment stating that the decisive `spectrumRef`-vs-document-order assertion is in [Step 7](Tech_Step7.md), because this fixture cannot distinguish them. |
| `ZeroPeakMs1ChainTest` | **Added by Correction C27(b), after this step was marked done.** A zero-peak MS1 must **not** become the `ms1scan` link: on `micro.mzML`, scan 5 → **2**, not the empty MS1 at scan 4. Also asserts the empty scan is still *yielded* (peak count 0, `materialize()` gives an empty table), so a reader that dropped it outright cannot pass. Verified to have teeth — with the guard removed it reports "Got 4 — expected 2". |
| `MzMLPeaksDecoderTest` | ✅ **Done.** Proves the vendored decode layer standalone, before any XML walking rests on it: 64-bit bit-exact; 32-bit decodes to `(double)(float)raw` asserted on raw bits; a companion test that the two decodes genuinely differ (so the first cannot pass vacuously); zlib == uncompressed; empty array; accession→enum mapping; and our `LittleEndianDataInput` against `ByteBuffer`. |
| `ReaderErrorPathTest` | Truncated mzML → throws, **no partial table**; empty file; missing path; directory instead of file. |
| `SpectraStreamCloseTest` | `close()` idempotent; 200 open/close cycles without exhausting descriptors. Meaningful now precisely because the cursor holds the mapping (C22). |
| `StreamingMemoryTest` | **Prove the C22 claim, do not assert it**: stream `PlusRise.mgf` (758,545 peaks) sampling used heap, asserting peak retained heap is bounded by *scan* size, not file size. |

### Renamed and folded test classes

Redirects for the names this spec required, kept rather than deleted so the original requirement stays
reviewable. Read by `make spec-audit` check 4 (Correction **C38**), which fails the build when a completed
step names a test class that neither exists nor redirects.

| Spec-era name | → Real home | Note |
|---|---|---|
| `MgfScanNumberingTest` | → `MgfReaderTest` | `scanIdIsScansWhenPresentElseTheOneBasedBlockIndex` — both the `SCANS=` path and the block-index fallback in one method |
| `Ms1ScanDocumentOrderTest` | → `Ms1ScanDocumentOrderIT` | **became an integration test**, which is the right home: the decisive `spectrumRef`-vs-document-order distinction needs the Ewing fixture (916 scans, zero `precursorScanNum`), and no micro-fixture can make it |
| `ReaderErrorPathTest` | → `MzmlReaderTest` + `FormatSniffTest` | `truncatedMzmlThrowsWithNoPartialResult`; `missingEmptyAndDirectoryPathsFailClearly` and `unknownContentThrowsAndNamesTheFile`. Split because the sniffer owns path- and content-level failures while the reader owns truncation mid-parse |

## Done when

- [x] `mvn test` green — **282 tests** (275 at completion, plus C26's `FixturesContractTest` and C27's
      `ZeroPeakMs1ChainTest`), including all 44 Step 5 store tests **unchanged**.
- [x] **C27(b) fixed after the fact:** the zero-peak `ms1scan` guard, which this step shipped without.
- [x] `SpectraFile.open` sniffs all three by **content**, not extension (the fixtures disagree on case).
      mzXML throws a clear "not yet implemented" naming Tech_Step7.
- [x] All three MGF rules verified: `ms1scan` always 0, `rt` ÷60 with `0.0` default, **`charge` from the block,
      else the file-level header, else `1`**
      (Correction C6 — this line previously said "0 sentinel", contradicting the field table above it).
- [x] Both mzML RT directions verified with separate fixtures (`micro.mzML` minute → unchanged,
      `micro_rtseconds.mzML` second → ÷60), plus a third test asserting they agree after conversion.
- [x] The 32-bit widening is asserted **on raw bits** — already done by `MzMLPeaksDecoderTest` against the vendored decoder (8 tests green), including a companion assertion that the 32- and 64-bit decodes genuinely differ so it cannot pass vacuously. (This spec previously asked for "a 32-bit array whose float32 and float64 decodes differ visibly", which is incoherent — 4 bytes have no float64 decode.)
- [x] [`READER_RULES.md`](READER_RULES.md) written: the three-format table, the shared `ms1scan` document-order rule, the
      MGF scan-numbering rule, all three RT rules side by side, and the vendored-API gotchas.
- [x] Closure unchanged: **two artifacts, 785,599 B**.
- [x] **Oracle cross-check inside this step**, not deferred: `small.mzML` matches the parity dump on scan
      counts (48 / 14 / 34), every per-scan peak count, 305,214 total peaks, bit-exact `rt`, and the
      golden's six `ms1scan` links.
- [x] **C22 proven, not asserted**: 34,513 scans / 758,544 peaks streamed inside a **48 MB heap**, ~0 KB
      retained after settling.

**✅ STEP 6 COMPLETE — 2026-07-31.** See Correction **C24** in
[`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) and [`READER_RULES.md`](READER_RULES.md).

## References

- **`massql/msql_fileloading.py` is the authoritative spec.** Key lines: `:103` (live loader dispatch),
  `:525-650` (`_load_data_mzML_pyteomics`), `:564-571` (mzML RT conditional), `:179-181`/`:327-328` (MGF RT),
  `:394` (MGF `ms1scan = 0`)
- [`NOTES_fileloading.md`](oracle/NOTES_fileloading.md) from [Step 1](Tech_Step1.md)
- [`SPIKE.md`](SPIKE.md) §3 (population by format, the `ms1scan` derivation note), §5, §6a, §6c (RT-units table)
- MSDK source: `github.com/msdk/msdk/blob/master/msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLParser.java`
- Consumers: [Step 7](Tech_Step7.md) reuses `SpectraReader`; [Step 8](Tech_Step8.md) asserts parity
