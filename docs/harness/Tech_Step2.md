# Tech Step 2 — Build the complete fixture and golden set

## Goal

Produce every input file and every reference output the Java test suite will ever compare against — covering
all three formats — so that no later step needs Python, Docker or network access.

## Prerequisites

| Step | Why |
|---|---|
| [Step 1](Tech_Step1.md) | Provides the pinned, verified Python oracle that generates every golden here. Its gate must be green — goldens generated against an unverified oracle inherit its uncertainty. |

## Context

Only two of the needed fixtures exist (`data/small.mzML`, `data/PlusRise.mgf`) and **no mzXML file exists on
this machine at all**, yet "all three formats verified" is the spike's exit criterion. This step closes that
gap and also produces the small hand-written files that make unit-test failures readable: when a tolerance
assertion fails on a 4-peak spectrum you can see why; on a 34,000-spectrum file you cannot.

Governing sections: `SPIKE.md` §6c, §7 Step 0.

## Scope

**In scope**
- Converting `small.mzML` → `small.mzXML` and verifying what survived the conversion.
- Acquiring the matched Ewing Lab mzXML/MGF pair, by script rather than by commit.
- Hand-writing `micro.{mgf,mzML,mzXML}` and verifying them against the oracle.
- Obtaining `empty_msLevel_tag.mzXML` from MSDK's test resources.
- Generating a golden for every (fixture, query) pair, including `scaninfo(MS1DATA)`.
- Dumping per-scan peak counts and intensity sums for all three formats.

**Out of scope**
- Trimming `PlusRise.mgf` — **decided against**; see Settled decision 5 in
  [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md). The 664-record golden belongs to the full file.
- Consuming any of these artifacts — [Step 8](Tech_Step8.md) uses the parity dumps,
  [Step 12](Tech_Step12.md) uses the goldens, Steps 6/7/9 use the micro-fixtures.
- Any Java code.

## Deliverables

| Path | Content |
|---|---|
| `data/small.mzXML` | Converted from `data/small.mzML` via ProteoWizard |
| `data/CONVERSION_NOTES.md` | **Fixture provenance for the whole spike**: what each fixture is, what was verified about it, and every fixture-level finding later steps depend on. See the scope note in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) — cross-step *spec* corrections go in the INDEX, fixture *facts* go here. |
| `scripts/fetch-fixtures.sh` | Downloads the Ewing pair to `data/`, with size assertions |
| `data/.gitignore` | Excludes the Ewing files (unstated provenance — referenced by URL, not committed) |
| `fixtures/micro/micro.{mgf,mzML,mzXML}` + `micro_rtseconds.mzML` | 5 scans, <7 KB each. **Generated** by `oracle/make_micro_fixtures.py` from one explicit data table rather than hand-typed three times — the three encodings must contain identical peak data for the cross-format tests to mean anything. The table is the hand-written part. |
| **Six more mzXML variants** (added in Step 7) | `micro_p64`, `micro_zlib`, `micro_p64_zlib`, `micro_nested`, `micro_nopolarity`, `micro_noprecursor`. Same generator, **one variable changed each**. Added because Corrections C27/C29 found that the primary fixtures are *all* `precision="32"` / uncompressed / `network`, leaving the 64-bit and zlib decode branches untested, and that the writer emitted no `precursorCharge` at all — so the charge path passed for the wrong reason. The last two are **not parity fixtures**: MassQL raises `KeyError` on both, so they pin our contract only |
| `fixtures/micro/EXPECTED.md` | Hand-computed expected values per micro-fixture, with the arithmetic shown |
| `fixtures/edge/empty_msLevel_tag.mzXML` | From MSDK's `msdk-io-mzxml` test resources |
| `output/*_results.json` | One golden per (fixture, query) pair — see the matrix below |
| `oracle/loader-parity/<fixture>.json.gz` | Per scan: `peak_count`, `i_sum_hex`, **SHA-256 digests of the full m/z and intensity arrays**, and the first 8 values of each as hex for diagnosis. Gzipped: 4.8 MB total vs 86 MB storing every value as hex. |
| `oracle/generate-all.sh` | Regenerates every golden and parity dump from scratch |

## Specification

### 1. Convert `small.mzML` → `small.mzXML`

Neither `msconvert` nor `pyteomics`-based conversion is available locally, so use ProteoWizard via Docker (the
standard macOS route). The Docker daemon is confirmed running.

> **⛔ RESOLVED DIFFERENTLY (2026-07-30): msconvert cannot run on this machine.** The pwiz image is
> `linux/amd64`, so on Apple Silicon it runs under qemu and wine aborts on the 16 KB page size
> (`anon_mmap_fixed: Assertion !((UINT_PTR)start & host_page_mask) failed`). `docker run` still **exits 0**
> after the core dump. `oracle/mzml_to_mzxml.py` generates the fixture instead, validated by the oracle
> reading both formats: **11 of 12 columns bit-identical**. Full analysis and the `precision="32"` rationale
> in [`data/CONVERSION_NOTES.md`](../../../massql/data/CONVERSION_NOTES.md). The command below is kept for the record and for
> anyone running on amd64.

```
docker run --rm -v "$PWD/data:/data" chambm/pwiz-skyline-i-agree-to-the-vendor-licenses \
  wine msconvert --mzXML /data/small.mzML -o /data
```

Record the resolved image digest in `data/CONVERSION_NOTES.md` so the conversion is reproducible.

**Then assert three things, and record each result:**

1. **Scan count and level split survived.** `small.mzML` has **48 spectra (14 MS1, 34 MS2)**. Conversion is
   *not* lossless — mzXML carries no chromatograms — so verify the spectrum count and MS-level split before
   trusting any golden generated from it.
2. **Scan numbering survived.** The golden `ms1scan` values from `small.mzML` are **2, 9, 16, 23, 36, 43**. If
   msconvert renumbers scans, the cross-format equivalence test in [Step 12](Tech_Step12.md) compares
   different rows and will fail for a reason that has nothing to do with the Java code.
3. **`precursorScanNum` survived.** `small.mzML` carries `spectrumRef` on all 34 MS2 spectra, so the conversion
   *should* carry `precursorScanNum` through:

   ```
   grep -c 'precursorScanNum' data/small.mzXML
   ```

   **If the count is 0, say so explicitly in `CONVERSION_NOTES.md`.** The consequence is bounded and known:
   [Step 12](Tech_Step12.md) layer 3 degrades to comparing only the non-`ms1_*` columns for this pair. That is
   an acceptable, documented degradation — silently accepting it is not.

   Note the asymmetry: `small.mzXML` having `precursorScanNum` does **not** help test the document-order rule,
   because `small.mzML`'s declared linkage already agrees with document order. Only the Ewing mzXML separates
   those two implementations ([Step 7](Tech_Step7.md)).

### 2. Acquire the matched Ewing Lab pair

Same experiment in two formats, both verified live. **Do not commit them** — license and provenance are
unstated, so reference them by URL and add them to `data/.gitignore`.

`scripts/fetch-fixtures.sh`:

```
curl -fL -o data/DP00570_F02.mzxml https://www.ewinglab.org/omicsanalysistutorial/data/DP00570_F02.mzxml
curl -fL -o data/DP00570_F02.mgf   https://www.ewinglab.org/omicsanalysistutorial/data/DP00570_F02.mgf
```

Assert the sizes — **3,761,778 B** for the mzxml, **2,196,881 B** for the mgf — and fail loudly on mismatch, so
a silently-changed upstream file becomes a visible error rather than a mysterious test failure.

> ⚠ **Correction C26 reverses what this paragraph used to say next** — *"tests that depend on these files
> must skip with a clear message when they are absent, never fail"*. That instruction is how the whole
> verification story came to prove nothing: the fixtures lived outside the repo, CI never had them, and
> every parity assertion **skipped silently** while the test counter stayed healthy (surefire counts skips
> inside "Tests run"). A missing fixture is now a **hard failure** carrying the
> `scripts/fetch-fixtures.sh` command; CI fetches and caches these two files and asserts the skipped-test
> count is **0**. See `docs/FIXTURES.md`.

Expected content, for assertion: **916 scans (229 MS1, 687 MS2)**, mzXML schema **2.0**, `precision="32"`,
`byteOrder="network"`, no compression, and **zero `precursorScanNum` attributes**:

```
grep -c 'precursorScanNum' data/DP00570_F02.mzxml   # must be 0
```

That zero is what makes this file the only available fixture that can distinguish the document-order `ms1scan`
rule from the intuitive `precursorScanNum`-resolving implementation.

Some scans carry `peaksCount="3"` — note a few of their scan numbers in `data/CONVERSION_NOTES.md`, as they
give [Step 8](Tech_Step8.md) hand-checkable assertions.

### 3. Hand-write the micro-fixtures

Three files, 3–5 scans each, under 10 KB, whose every expected value can be computed by hand and written out in
`fixtures/micro/EXPECTED.md` **with the arithmetic shown**. These are the backbone of the unit tests in Steps
5, 6, 7, 9 and 10.

Design them deliberately to exercise the rules those steps must implement:

| Property to build in | Why | Consumed by |
|---|---|---|
| An MS2 scan whose **base peak is not the peak nearest the precursor** | The single most likely misreading of the contract is "most intense" instead of "closest" | [Step 10](Tech_Step10.md) |
| An MS1 scan containing two peaks inside a ±tol window at **different distances** from `precmz`, the **farther one more intense** | Makes closest-vs-most-intense a failing assertion rather than a coincidence | [Step 10](Tech_Step10.md) |
| A peak exactly at the window edge (`precmz + tol` to the bit) | Inclusive/exclusive boundary | [Step 5](Tech_Step5.md), [Step 9](Tech_Step9.md) |
| An **empty scan** (zero peaks) and a **single-peak scan** | Reduction edge cases; `i_norm` division | [Step 5](Tech_Step5.md) |
| An MS2 scan appearing **before any MS1 scan** | Produces `ms1scan = 0` → null. This is where the sentinel comes from | [Step 6](Tech_Step6.md), [Step 10](Tech_Step10.md) |
| `rt` genuinely `0.0` on one scan | Proves `rt` is **not** null-converted | [Step 10](Tech_Step10.md) |
| Absent `CHARGE=` in the MGF; `charge="0"` somewhere in the mzML | Both routes to a null `charge` | [Step 10](Tech_Step10.md) |
| **`micro.mzXML` with RT as `PT90S`** | Must read back as `1.5` minutes | [Step 7](Tech_Step7.md) |
| **`micro.mzML` with `unitName="second"`** on scan start time | The mzML conditional converts; `small.mzML` (`minute`) does not — one fixture each side | [Step 6](Tech_Step6.md) |
| `micro.mgf` **with** and **without** `RTINSECONDS` | ÷60 vs default `0.0` | [Step 6](Tech_Step6.md) |
| A `precision="32"` binary array | Forces the `readFloat()`-then-widen path | Steps [6](Tech_Step6.md), [7](Tech_Step7.md) |

Write intensities as values exactly representable in float32 (e.g. 100.0, 250.5, 1024.0) **except** where you
are deliberately testing the widening rule — there, use a value whose float32 and float64 decodes differ
visibly, and record both in `EXPECTED.md`.

Run each micro-fixture through the oracle and check the output matches your hand computation. Where the oracle
and your arithmetic disagree, **the oracle wins** — and that disagreement is itself a finding worth recording,
because it means a rule was misunderstood.

### 4. Obtain the edge-case fixture

`empty_msLevel_tag.mzXML` from `msdk-io-mzxml/src/test/resources/` in the MSDK repo — a file with a
missing/empty `msLevel` tag, which [Step 7](Tech_Step7.md) must handle without crashing.

### 5. Generate the golden matrix

Every golden comes from the Step 1 oracle, via `massql_query.py`, captured from **stdout** only.

**Every golden must record the non-default flags it was generated with.** [Step 1](Tech_Step1.md) §3a found the
original `small_mzml_results.json` had been produced at an unrecorded ~60 ppm tolerance, which cost a gate
failure to diagnose. Encode the flags per golden in `oracle/generate-all.sh` and in
`oracle/reproduce-goldens.sh`.

| Fixture | Query | Flags | Golden | Expected |
|---|---|---|---|---|
| `data/small.mzML` | `test_mzml.massql` | *(defaults, 20 ppm)* | `output/small_mzml_results.json` | **6 records** (exists — must still reproduce) |
| `data/small.mzML` | `test_mzml.massql` | `--precursor-tol-ppm 60` | `output/small_mzml_tol60_results.json` | **6 records** (exists — the successful-match counterpart; see [Step 1](Tech_Step1.md) §3a) |
| `data/PlusRise.mgf` | `test.massql` | *(defaults)* | `output/plusrise_results.json` | **664 records** (exists — must still reproduce) |
| `data/small.mzXML` | `test_mzml.massql` | `output/small_mzxml_results.json` | new; ideally identical rows to the mzML golden |
| `data/DP00570_F02.mzxml` | `test.massql` | `output/dp00570_mzxml_results.json` | new |
| `data/DP00570_F02.mgf` | `test.massql` | `output/dp00570_mgf_results.json` | new; `ms1scan` + all `ms1_*` **null** |
| `data/small.mzML` | `test_ms1.massql` *(new)* | `output/small_mzml_ms1_results.json` | new; the **4-key** MS1DATA shape |
| each `fixtures/micro/*` | `test.massql` and `test_ms1.massql` | `output/micro_*_results.json` | new |

Create `test_ms1.massql` containing a `scaninfo(MS1DATA)` query — e.g.
`QUERY scaninfo(MS1DATA) WHERE MS1MZ=810.79:TOLERANCEMZ=1.0` — so the 4-key shape has a golden. Confirm from
the output that `precmz`, `ms1scan`, `charge` and all three `ms1_*` keys are **absent**, not null; that shape
is what [Step 10](Tech_Step10.md) must reproduce.

**Compare the two `small.*` goldens against each other** and record the result. Same data, same query, so the
rows should match. Any difference is a property of the *conversion*, not of Java, and knowing it now prevents
misdiagnosing [Step 12](Tech_Step12.md) layer 3 later.

Wrap everything in `oracle/generate-all.sh`, and re-run `oracle/reproduce-goldens.sh` from
[Step 1](Tech_Step1.md) at the end to confirm the two pre-existing goldens are still bit-identical.

### 6. Dump loader parity data

This is [Step 8](Tech_Step8.md)'s entire input, so it must come from MassQL's **own** loaded tables — the same
`msql_fileloading.load_data()` call `massql_query.py` uses — not from a re-parse.

Write `oracle/dump_loader_parity.py`. For each fixture, emit JSON:

```json
{
  "fixture": "small.mzML",
  "ms1_scan_count": 14,
  "ms2_scan_count": 34,
  "scans": [
    {"scan": 2, "mslevel": 1, "peak_count": 1234,
     "i_sum_hex": "0x1.5c8f2ap+20", "mz_first_hex": "0x1.9p+7", "rt": 0.0102}
  ]
}
```

**Serialize every float as a hex literal** (`float.hex()`), not as a decimal string. Step 8 asserts
bit-identical intensities; a decimal round-trip through JSON can lose or fabricate low bits and turn a real
decoder bug into a passing test, or vice versa. Include `peak_count`, the intensity sum, the first m/z, and
`rt` per scan.

Do this for all three formats: `small.mzML`, `small.mzXML`, `PlusRise.mgf`, both Ewing files, and each
micro-fixture.

## Known traps

- **`--mzXML` output naming.** msconvert may write `small.mzXML` or `small.mzxml` depending on version; the
  Ewing file uses lowercase `.mzxml`. Format sniffing in [Step 6](Tech_Step6.md) must be
  case-insensitive — note the actual filenames produced here so that spec matches reality.
- **Conversion is not lossless.** mzXML carries no chromatograms, and scan renumbering is possible. Verify
  before trusting, per Specification §1.
- **Committing the Ewing files.** Provenance is unstated. Script them; gitignore them; make dependent tests
  skip-not-fail when absent.
- **Generating goldens with a decimal float dump.** Use `massql_query.py` unmodified for the goldens (its
  `indent=2` output is the published contract) and hex only for the parity dumps.
- **Hand-computed expectations that were actually derived from program output** are circular. Compute first,
  then check.

## Tests required

Scripts, verified by running them:

- `oracle/generate-all.sh` — regenerates every golden and parity dump; idempotent (a second run produces
  byte-identical files).
- `scripts/fetch-fixtures.sh` — asserts both Ewing byte counts.
- `oracle/reproduce-goldens.sh` (from Step 1) — still exits 0 after all fixture work.

## Done when

- [x] `data/small.mzXML` exists (generated, not msconvert — see above); scan count **48 (14 MS1 / 34 MS2)** and `ms1scan` numbering **2, 9, 16, 23,
      36, 43** confirmed; the `precursorScanNum` survival result recorded in `CONVERSION_NOTES.md`.
- [x] `scripts/fetch-fixtures.sh` retrieves both Ewing files at the exact expected byte counts; `grep -c
      precursorScanNum data/DP00570_F02.mzxml` = **0**; files are gitignored.
- [x] Four micro-fixtures exist (three formats + an mzML `unitName="second"` variant), each <10 KB, each exercising every row of the design table, with
      `EXPECTED.md` showing the arithmetic and agreeing with the oracle.
- [x] `fixtures/edge/empty_msLevel_tag.mzXML` present (118,392 B).
- [x] All 13 goldens exist; the two pre-existing ones still reproduce bit-identically; the MS1DATA
      golden confirmed to have precursor keys **absent, not null**.
- [x] `oracle/loader-parity/` has one gzipped file per fixture (4.7 MB total; digests, not full hex arrays), floats as hex, with per-scan peak counts and intensity
      sums.
- [x] The `small.mzML` vs `small.mzXML` golden comparison is recorded, whatever the result.

## References

- `SPIKE.md` §6c (fixture table, RT-units table, conversion and download tasks), §7 Step 0
- [Step 1](Tech_Step1.md) — the oracle and `oracle/NOTES_fileloading.md`
- Consumers: [Step 6](Tech_Step6.md), [Step 7](Tech_Step7.md) (micro-fixtures, edge case),
  [Step 8](Tech_Step8.md) (parity dumps), [Step 9](Tech_Step9.md) (micro-fixtures),
  [Step 12](Tech_Step12.md) (goldens)
- Established facts in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)

**✅ STEP 2 COMPLETE — 2026-07-30.** See Corrections C11–C15 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)
and [`data/CONVERSION_NOTES.md`](../../../massql/data/CONVERSION_NOTES.md). One open decision was deferred to
[Step 10](Tech_Step10.md) §5: the `scaninfo(MS1DATA)` key set.
