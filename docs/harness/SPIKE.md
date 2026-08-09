# Spike: `massql-java` — a pure-Java MassQL SDK

**Audience:** the developer building the prototype.
**Spike scope:** finish **`massql-java`** only, verified by **unit and integration tests inside the SDK**,
green at the CLI layer against **all three input formats** (`.mgf`, `.mzML`, `.mzXML`). That proves the
pure-Java MassQL SDK works. **Then stop for manual review** before any Cytoscape app work begins.
**Language scope:** `scaninfo` only.

---

## 1. The two repos

| Repo | Packaging | Contents | Phase |
|---|---|---|---|
| **`massql-java`** | plain `jar` | Pure-Java MassQL SDK. Parser, spectra readers, query engine, result model, full unit + integration test suite, **plus a standalone `Main` class that acts as a CLI wrapper**. Zero Cytoscape. Zero OSGi. | **This spike** |
| **`cytoscape/massql-app`** | OSGi `bundle` | Imports `massql-java` as a dependency. Cytoscape desktop app: activator, menu, dialog, node-table write-back, `MASSQL_PARSE` Formula function. | Phase 2, after review |

**Why this split is the right call:** `massql-java` physically cannot compile against Cytoscape because
Cytoscape isn't on its classpath. That compile-time firewall is the only thing that reliably keeps
`org.cytoscape` imports out of engine code. It also means the entire spike is verified with `make verify`
and a shell command — no Cytoscape, no OSGi, no 84 MB log files, no bundle resolution debugging.
A pure-Java MassQL SDK is also independently useful (there is currently **no** Java MassQL implementation
anywhere — verified), so it deserves to be its own artifact rather than buried in an app.

**Two decisions to make before `git init`:**
1. **Where does `massql-java` live?** `cytoscape/massql-java` keeps it adjacent to the app, but the SDK has
   value beyond Cytoscape (MZmine, GNPS tooling) and might belong somewhere more neutral.
2. **How does `massql-app` consume it?** Publish to the Cytoscape nexus
   (`nrnb-nexus.ucsd.edu/repository/cytoscape_releases`) — what the existing Cytoscape repos already
   resolve against — or Maven Central for wider reach. Publishing to a local repo is fine for the spike, but
   Phase 2 needs a real answer.

---

## 2. Verdict — is the port realistic?

**Yes. ~1,200–1,800 LOC of production code for this scope, plus a comparable amount of test code.**

The Python dependency stack does not get ported — it gets **deleted**:

| Python dependency | In Java | Risk |
|---|---|---|
| pandas, numpy, pyarrow | **Deleted.** Parallel `double[]`/`int[]` + `Arrays.binarySearch`. (pyarrow is only feather result caching, never query math.) | none — *subtracts* risk |
| lark | ANTLR `4.13.2` — 319 KB, zero deps, zero reflection/ServiceLoader. Or hand-write it. | **low** — fallback is "no dependency" |
| py_expression_eval | **Deleted.** MassQL's grammar already contains the arithmetic grammar. | none |
| plotly, kaleido, pydot, tqdm, psims | Out of scope (visualizer / translator / mzML *writing*). | none |
| pyteomics.mass | Not needed — only `formula()`/`peptide()` use it, both out of scope. | none in v1 |
| **pymzml** | **The one real dependency.** MSDK — see §5. | all of it lives here |

No Java dataframe library is usable here (Tablesaw pulls ~44 MB and finds its I/O registry by classpath
scanning; Arrow has split packages and needs `sun.misc.Unsafe` plus JVM flags you don't control). **So you
are writing the dataframe** — a columnar store plus per-scan reductions. That's the bulk of the LOC, and
it's ordinary Java.

`scaninfo`-only cuts the two hardest things in the Python codebase: the `X`/`Y` variable enumerator
(350 LOC of recursive sub-query resolution, candidate enumeration and greedy tolerance skipping) and the
monoisotopic mass tables.

---

## 3. The contract — what `scaninfo` must produce

This is the definition of done. From [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26)
+ [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) + `massql_query.py`.

### `scaninfo(MS2DATA)` → one JSON object per matching scan, 12 keys

```json
{
  "scan": 576, "precmz": 161.0209, "ms1scan": null, "rt": 0.0, "charge": 1,
  "tic": 1299900.0, "mslevel": 2, "base_peak_i": 230000.0, "base_peak_mz": 162.1122,
  "ms1_i": null, "ms1_precmz": null, "ms1_base_peak_i": null
}
```

**Only 7 of these 12 come from MassQL. The SDK must compute the other 5 itself** — the most easily-missed
part of the contract:

| Source | Columns |
|---|---|
| MassQL `scaninfo` native | `scan`, `precmz`, `ms1scan`, `rt`, `charge`, `tic` (MassQL's `i`, renamed), `mslevel` |
| **Computed on top** (`massql_query.py`'s `add_precursor_intensity`) | `base_peak_i`, `base_peak_mz` — per-scan argmax over MS2 peaks |
| **Computed on top** | `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` — precursor lookup in the linked MS1 scan |
| **Dropped** from MassQL's raw output | `i_norm` (structurally always 1.0), `i_norm_ms1` (only null or 1.0) |

### Population by input format — this is what the 3 formats determine

| Column | MGF (MS2 only) | mzML / mzXML (MS1 + MS2) |
|---|---|---|
| `scan` | ✔ | ✔ |
| `precmz` | ✔ (from `PEPMASS=`) | ✔ |
| `ms1scan` | **null** — no survey scans exist | ✔ **only if the file records the precursor→MS1 link** — see the linkage note below |
| `rt` | **`0.0`, not null** (MassQL's value when none recorded) | ✔ |
| `charge` | ✔ if `CHARGE=` present, else null | ✔ if recorded |
| `tic` | ✔ — sum of all MS2 fragment intensities | ✔ |
| `mslevel` | `2` | `2` |
| `base_peak_i` / `base_peak_mz` | ✔ | ✔ |
| `ms1_i` | **null** | ✔ if `ms1scan` resolved **and** a precursor peak matches within `--precursor-tol-ppm` (default 20), else null |
| `ms1_precmz` | **null** | ✔ same condition — the *measured* centroid, usually a few ppm off `precmz` |
| `ms1_base_peak_i` | **null** | ✔ whenever the linked MS1 scan exists — a tolerance miss does *not* null it |

> **⚠ How `ms1scan` is actually derived — MassQL ignores the file's own precursor reference.**
> The obvious implementation reads the explicit precursor→survey-scan link: `spectrumRef` on
> `<precursor>` in mzML, `precursorScanNum` on `<precursorMz>` in mzXML. **MassQL does neither** —
> those two attribute names appear **zero times** in all 892 lines of `msql_fileloading.py`. Every
> loader (all four mzML variants and the mzXML one) instead tracks `previous_ms1_scan`: the id of the
> most recent MS1 spectrum seen while streaming in **document order**, initialized to `0`. So
> `ms1scan` is *inferred by position in the file*, never read from it. Three consequences:
>
> - MS2 scans appearing before any MS1 scan get `ms1scan = 0` → converted to null downstream. **That is
>   where the 0 sentinel comes from**, and why the sentinel rule exists at all.
> - MGF hardcodes `ms1scan = 0` (`msql_fileloading.py:394`), hence null.
> - **A Java reader that "correctly" resolves `spectrumRef` will disagree with MassQL** whenever that
>   reference does not point at the immediately preceding MS1 scan — interleaved acquisition, multiple
>   MS1 scans per cycle, or a reference pointing further back. For simple DDA they coincide.
>   **Reproduce the document-order behavior** to match the goldens, and record the divergence from
>   file-declared linkage in the README as a known deviation.
>
> `data/small.mzML` **cannot distinguish the two rules** — its `spectrumRef` values happen to agree with
> document order (goldens: `ms1scan` = 2, 9, 16, 23, 36, 43). Do not use it to validate this; use the
> unlinked mzXML fixture in §6c, which has no `precursorScanNum` at all and therefore *only* works under
> the document-order rule.

### `scaninfo(MS1DATA)` → ⛔ the SAME 12 keys (Correction C40)

> **This section was wrong, and is corrected in the ledger rather than quietly rewritten.** It read:
>
> > *"`scaninfo(MS1DATA)` → a different, smaller shape. `scan`, `rt`, `tic`, `mslevel: 1` only. No precursor
> > exists, so `precmz`/`ms1scan`/`charge` and all `ms1_*` columns are **absent, not null**."*
>
> **There is no second shape.** [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) —
> which §3 cites as its own source four lines above — defines the schema as *"a union of all possible attributes
> from ms1 and ms2"*, with **`mslevel`** as the discriminator. `MS1DATA` emits the **same 12 keys**;
> inapplicable fields are present as **`null`**, never absent.
>
> `precmz`, `ms1scan`, `charge` and the three `ms1_*` columns *are* null for an MS1 row — a survey scan has no
> precursor, and that part of the old text was right for the right reason. But **`base_peak_i` and
> `base_peak_mz` are real values**: a survey scan plainly has a base peak, and issue #26 marks both
> *"Can be null? **No**"*. Their nulls in the old golden were a left-join artifact in `massql_query.py`, proven
> by the `micro.mgf` phantom-id collision producing a *wrong non-null*.
>
> **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) is the single definition.** Correction **C40** has the
> analysis.

Still true: nearly free once the store exists, since it is the same collation path.

### Exact rules — each is one line, and each is a silent wrong-answer bug if missed

- **Precursor lookup:** among MS1 peaks in scan `ms1scan` within `precmz ± tol`, take the **closest to
  `precmz`**, not the most intense. `ms1_base_peak_i` = max intensity across that whole MS1 scan (the
  normalization reference: relative abundance = `ms1_i / ms1_base_peak_i`).
- **`0` → null for `precmz`, `ms1scan`, `charge` only.** MassQL uses 0 as a "not recorded" sentinel and
  those three can never legitimately be 0. **Never convert `rt`** — `0.0` is a real retention time.
- **NaN / infinity → null** so the output is valid JSON.
- `TOLERANCEPPM` **wins** if both tolerances are given; **default 0.1 Da** if neither.
- `=` means `>=` for intensity comparisons ("preserving historical semantics"). A missing comparator
  defaults to greater-than. Intensity columns with no explicit qualifier get an implicit `> 0`.
  `INTENSITYPERCENT` with `>` is **capped at 0.99**.
- `RTMIN`/`RTMAX` are **strict** (`>`/`<`); `SCANMIN`/`SCANMAX` are **inclusive ints**.
- `i_norm = i / max(i in scan)`; `i_tic_norm = i / sum(i in scan)`; `rt` in **minutes**; polarity is
  **1 = positive, 2 = negative**.
- `QUERY scaninfo WHERE ...` is a **parse error** — the function-call form `scaninfo(MS2DATA)` is required.
  A real trap; it reads perfectly legal.

---

## 4. `massql-java` layout and public API

The public API is the contract with `massql-app`, so design it deliberately — everything else can churn.

```
massql-java/                          packaging=jar, <release>17</release>
  src/main/antlr/…/Massql.g4           NOT in src/main/resources (see §9)
  src/main/java/…/massql/
    Massql.java              ← public entry point
    MassqlOptions.java       ← precursorTolPpm (default 20.0), future knobs
    MassqlParseException.java, MassqlException.java
    lang/                    generated parser + facade, typed AST
    spectra/                 SpectrumTable (double[] mz,i,iNorm,iTicNorm; int[] scan; float[] rt; byte[] polarity)
    io/                      SpectraStream iface → MzmlReader, MzxmlReader, MgfReader   (was "SpectraReader": C22/C42)
    exec/                    QueryExecutor, ConditionFilters, ScaninfoCollation
    result/                  ScanInfoResult, ResultJson
    cli/Main.java            ← the standalone CLI wrapper
  src/test/java/…            unit tests (*Test.java) + integration tests (*IT.java)
  src/test/resources/…       fixtures + goldens (see §6)
```

Sketch of the surface `massql-app` will code against:

```java
MassqlQuery q = Massql.parse(queryText);                    // throws MassqlParseException
try (SpectraStream s = SpectraFile.open(path)) {            // format sniffed; MGF | mzML | mzXML
    List<ScanInfoResult> rows = Massql.execute(q, s, opts);
}
List<ScanInfoResult> rows = Massql.run(queryText, path, opts);   // one-shot convenience
```

> ⛔ **Corrections C22 and C42 — this sketch said `SpectraFile f`, and [Step 11](Tech_Step11.md) was told to
> copy it "exactly".** `SpectraFile` is a **factory** with a single static `open`; the value you hold is a
> **`SpectraStream`** cursor. Step 11 §1 ended up carrying a C22 note saying so directly above a code block that
> still declared `SpectraFile` as a parameter — and its Done-when demanded an exact match, which made that box
> unsatisfiable.
>
> The tree above has the same error one line up: `io/  SpectraReader iface` — the interface is `SpectraStream`.
>
> Two further points the sketch cannot show. **A stream is single-pass**: `execute` consumes it, so several
> queries over one file means reopening per query (`next()` throws `NoSuchElementException` once drained, so
> reuse fails loudly). And the real surface has **four** entry points — `executeWithDiagnostics` is the fourth.

Two API rules that matter downstream:
- **`ScanInfoResult` uses boxed `Double`/`Integer`** so null is a real, testable value. All the sentinel and
  NaN rules from §3 live **in the SDK**, not in the app — so they're unit-tested outside OSGi and the app's
  write-back stays a dumb loop.
- **`ResultJson` produces exactly the 12-key object** in §3. The app writes that string into the node table
  verbatim and `MASSQL_PARSE` reads it back — so key names and float formatting are a published contract,
  not an implementation detail.

**CLI — mirror `massql_query.py`'s *interface***: same positional order, same flag names and defaults, same
stdout/stderr split, so one harness can drive both.

```
java -cp massql-java.jar …cli.Main <spectra-file> <query-file> [--precursor-tol-ppm 20] [--output FILE]
  → the JSON array on stdout, progress/diagnostics on stderr
```

> ⛔ **Correction C42 — this said "mirror … exactly **so the differential test is a literal diff**", and a
> literal diff is impossible.** `ResultJson` emits **compact** JSON ([`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md),
> Correction C40) while the reference uses `json.dump(…, indent=2)`; and Java and Python format floats
> differently regardless (`1.0E-5` vs `1e-05`, the trailing `.0` on integral values). [Step 12](Tech_Step12.md)
> §1 therefore compares **parsed values, never text** — which is the stronger check anyway, since it cannot
> fail on a formatting difference that means nothing.
>
> `--output FILE` was added by Correction C25b and is shown above; the original signature had stdout only.

---

## 5. File readers — one dependency family, all three formats

`io.github.msdk:msdk-io-mzml:0.0.27` (71 KB, 39 classes). Verified from source, not from the pom:

- **Both the mzML and mzXML parsers are hand-written streaming parsers, not JAXB.** They use
  `javolution.xml.internal.stream.XMLStreamReaderImpl` (instantiated **directly** — no
  `XMLInputFactory`/`ServiceLoader` discovery, which matters for Phase 2), `java.util.Base64`, and
  `java.util.zip.InflaterInputStream`.
- **`msdk-io-mzml` depends on `msdk-io-mzxml`** — you get mzXML in the transitive closure whether you want
  it or not, convenient since all three formats are in the contract. mzXML's pom declares
  `jaxb-api`/`jaxb-core`/`jaxb-impl` 2.3.0, but **the code never touches JAXB** (only `javax.xml.datatype`,
  still in the JDK on 17). **Exclude all three.**
- mzML needs **Numpress** decompression; MSDK bundles it (`util/MSNumpress`). mzXML has no Numpress —
  `compressionType` is `none` or zlib, `precision` 32 or 64. Numpress is the reason to take MSDK rather
  than hand-write mzML.
- **⚠ 32-bit precision trap, and it bites the "bit-identical" assertion.** For mzXML, pyteomics decodes
  with `np.float32 if precision == '32' else np.float64` (`pyteomics/mzxml.py:_determine_dtype`), then
  Python widens to double downstream. So for a `precision="32"` file — which the §6c Ewing fixture is —
  the golden values are `(double)(float)raw`, **not** a full-precision double. A Java reader must
  `readFloat()` then widen to `double`; reading 8 bytes, or decoding to double directly, yields different
  bits and integration layer 1 will fail with a confusing near-miss. Same rule for mzML's
  `32-bit float` binary data array. Also from the same function: mzXML peaks are **interleaved m/z /
  intensity pairs**, and `byteOrder="network"` means **big-endian** — `ByteBuffer` defaults to big-endian,
  but mzML's binary arrays are **little-endian**, so don't share one buffer configuration between readers.
- Bytecode scan: zero `ServiceLoader`, zero `Class.forName`, zero reflection, zero `Unsafe`, zero JavaFX.
- **MZmine has vendored this exact parser** into its own tree — proof by construction that it's portable
  pure Java. If MSDK-as-a-dependency disappoints, vendoring is the known fallback (+3–5 days).
- **Split-package check:** the *published* 0.0.27 pom reportedly declares both
  `org.javolution:javolution-core-java` **and** `com.github.chhh:javolution-core-java-msftbx` — two forks of
  the same packages, which Felix will reject in Phase 2. On master the plain fork is **commented out**.
  Verify the released pom; if both are present, **exclude `org.javolution:javolution-core-java`** and keep
  the `chhh` fork (the one the code imports).
- Also exclude CDK + Guava (2.7 MB, arrives via `msdk-datamodel`) and slf4j.
- **License: dual LGPL-2.1 / EPL-1.0.** Get a written yes/no on shipping LGPL. Cytoscape core is LGPL so
  probably fine, but it's blocking and it's a 1-hour question. **Ask in week 1, not in Phase 2** — a "no"
  changes the reader choice, which is the foundation of this repo.

**MGF: write it yourself, ~150–200 LOC.** `BEGIN IONS` / `TITLE=` / `PEPMASS=` / `CHARGE=` /
`mz intensity` lines / `END IONS`. The alternative (`uk.ac.ebi.pride.tools:mgf-parser`) is 28 KB but drags
fastutil (23 MB), logback, and *both* the javax and jakarta JAXB stacks.

**Out permanently:** Thermo `.raw` and mzMLb — no pure-Java reader exists for either.

---

## 6. Verification — unit and integration tests in the SDK

Verification lives in `massql-java` from day one, not bolted on at the end. Two suites with different jobs:
**unit tests pin semantics** and run in milliseconds; **integration tests prove the whole pipeline** against
real files in all three formats and diff against the Python goldens.

### 6a. Unit tests — `*Test.java`, JUnit 5, `make test`

| Area | What gets pinned |
|---|---|
| Parser conformance | The 47 golden parses → **canonical AST compare, not JSON text**. `@ParameterizedTest` over the corpus |
| Parser rejection | `QUERY scaninfo WHERE …` (function-call form required), lowercase `filter`/`or`, exponent floats (`1e5`), multi-char variables (`XY`), the 5 unsupported functions → clear `MassqlParseException`, never a crash |
| Keyword case matrix | `QUERY/query/Query`, `WHERE/where/Where`, `AND/and/And`, `MS1DATA/ms1data/Ms1Data` accepted; `filter`, `or` and lowercase condition names rejected |
| Tolerance math | PPM wins over MZ when both present; default 0.1 Da when neither; window edges at exactly ±tol |
| Intensity comparators | `=` behaves as `>=`; missing comparator → greater-than; implicit `>0` on unqualified columns; `INTENSITYPERCENT` ÷100 and the `>` 0.99 cap |
| Intensity algebra | Property tests ported from Python's `test_query.py`: `>`/`<` disjointness, monotonicity, tripartite partition. **Need no reference data** — pure profit |
| Store reductions | Per-scan sum / max / first / argmax; mask AND; mz-sorted binary-search window edges; empty-scan and single-peak cases |
| Precursor lookup | Picks the **closest** peak to `precmz`, not the most intense — construct a window where those differ. **This is the test that catches the most likely misreading of the whole contract.** Also: `ms1_base_peak_i` populated even when the tolerance match fails |
| Null / sentinel rules | `0`→null for `precmz`/`ms1scan`/`charge`; `rt=0.0` **preserved**; NaN/inf→null |
| Result JSON | ⛔ **Corrected by C40** — this read *"the 4-key MS1DATA shape with precursor keys absent, not null"*. There is **one** shape: the exact 12 keys in the frozen order for **both** MS1DATA and MS2DATA, `mslevel` discriminating, inapplicable fields present as `null`; null renders as JSON `null`. Definition: [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) |
| Scan/RT/charge filters | `RTMIN`/`RTMAX` strict; `SCANMIN`/`SCANMAX` inclusive; polarity 1=pos / 2=neg |
| **RT units, per format** | mzXML `PT90S` → `1.5` minutes; mzML converts **only if** the declared unit is seconds (`small.mzML` says `minute` → unchanged); MGF `RTINSECONDS`÷60, absent → `0.0`. Three different rules — see the §6c table |
| **`ms1scan` document order** | `ms1scan` = id of the most recent **preceding** MS1 spectrum, **not** the file's `spectrumRef`/`precursorScanNum`; `0` when no MS1 precedes it; MGF always `0`. Assert on a fixture with no declared linkage — see §3 |

**Micro-fixtures make these readable.** A hand-written 3–5 scan file per format, under 10 KB, with expected
values computable by hand. When a tolerance test fails on a 4-peak spectrum you can see why; on a
34,000-spectrum file you cannot.

### 6b. Integration tests — `*IT.java`, its own source set, `make it`

Four layers, **each run against all three formats**:

1. **Reader parity.** Scan count, per-scan peak count, per-scan intensity sum vs. numbers dumped from the
   Python loader. Assert intensities **bit-identical, not "close"** — same binary blob, same decode. A
   mismatch here means the decoder is wrong, and this is the cheapest place to learn that.
2. **End-to-end differential.** Run the public API over each fixture with its query and diff against the
   Python golden. Per-column policy: exact on `scan`/`ms1scan`/`charge`/`mslevel`, relative 1e-9 on m/z,
   bit-identical on intensities, exact null-vs-value. ⚠ Two refinements are **measured facts** and live in
   [Step 12](Tech_Step12.md) §1, which is authoritative: `tic` is a `float32` accumulation on the reference
   side and needs relative **1e-6** (C34), and `ms1_precmz` needs **1e-7** on a 32-bit mzXML (C11).
3. **Same-data cross-format equivalence.** Two pairs, each testing something the other can't.
   `small.mzML` / `small.mzXML` — same data, same query, identical rows including populated `ms1_*` (⚠ except
   `ms1_precmz`, where the mzXML's `precision="32"` truncates a *measured* m/z — C11);
   catches reader-specific bugs a per-format golden hides. `DP00570_F02.mzxml` / `DP00570_F02.mgf` — same
   experiment, and the rows must differ in exactly the way §3's format table predicts: the mzXML populates
   `ms1scan`/`ms1_*` **by document order** while the MGF nulls them, so this pair pins the format
   distinction *and* the document-order rule at once. **Both are stronger than any single golden.**
4. **CLI contract.** Exit code 0; stdout is a valid JSON array; diagnostics on **stderr only** (the Python
   wrapper deliberately redirects progress to stderr so stdout stays pipeable — match that);
   `--precursor-tol-ppm` honored, and a deliberately tight tolerance nulls `ms1_i`/`ms1_precmz` while
   leaving `ms1_base_peak_i` populated.

**Error paths, per format:** malformed/truncated file → clear exception, no partial results; unsupported
query → `MassqlParseException` naming the offending construct; query matching nothing → **empty JSON array,
exit 0**, not a crash; missing/empty `msLevel` tag → handled (MSDK ships `empty_msLevel_tag.mzXML` for
exactly this case).

### 6c. Fixtures

| Fixture | Format | Size / content | Provenance |
|---|---|---|---|
| `small.mzML` | mzML | 4.9 MB, **48 spectra (14 MS1, 34 MS2)**, `spectrumRef` on all 34 MS2 → `ms1_*` populate | Have it: `data/small.mzML`. Commit as-is |
| `small.mzXML` | mzXML | same data as `small.mzML`, converted | **Generate it** — task below |
| `DP00570_F02.mzxml` | mzXML | 3.6 MB, **916 scans (229 MS1, 687 MS2)**, mzXML **2.0**, `precision=32`, `byteOrder=network`, no compression, **no `precursorScanNum`** | Download; verified live |
| `DP00570_F02.mgf` | MGF | 2.2 MB, **same experiment as the mzXML above** | Download; verified live |
| `plusrise_subset.mgf` | MGF | trimmed from 14 MB / **34,513 spectra** | `data/PlusRise.mgf`. Trim, then **regenerate its golden** |
| `micro.mgf` / `micro.mzML` / `micro.mzXML` | all three | 3–5 scans, <10 KB | Hand-written, for the unit tests |
| `empty_msLevel_tag.mzXML` | mzXML | edge case | MSDK `msdk-io-mzxml/src/test/resources/` |
| `*_results.json` | — | one golden per (fixture, query) pair | Generated by the Python path in Step 0 |

**Task: generate `small.mzXML` from `small.mzML`.** Neither `msconvert` nor `pyteomics` is installed here,
so use ProteoWizard via Docker (the standard macOS route):

```
docker run --rm -v "$PWD/data:/data" chambm/pwiz-skyline-i-agree-to-the-vendor-licenses \
  wine msconvert --mzXML /data/small.mzML -o /data
```

Converting *from* `small.mzML` is what makes the cross-format equivalence test (layer 3) possible, and
`small.mzML` has `spectrumRef` on all 34 MS2 spectra, so the conversion should carry `precursorScanNum`
through and the `ms1_*` columns should populate. **Assert that after converting** — if msconvert drops it,
you have an unlinked mzXML and layer 3 degrades to comparing only the non-`ms1_*` columns.

**Task: download the matched Ewing Lab pair.** Same experiment, two formats, both verified live:

```
curl -O https://www.ewinglab.org/omicsanalysistutorial/data/DP00570_F02.mzxml
curl -O https://www.ewinglab.org/omicsanalysistutorial/data/DP00570_F02.mgf
```

These are **complementary to** `small.mzXML`, not a substitute — they earn their place four ways:

- **The decisive test of the document-order `ms1scan` rule in §3.** This file has 229 MS1 scans
  interleaved with 687 MS2 scans and **zero `precursorScanNum` attributes**. Under MassQL's
  document-order rule, `ms1scan` and all three `ms1_*` columns **populate normally**. A reader
  implemented the obvious way — resolving `precursorScanNum` — produces all-null here and fails loudly.
  **No other available fixture separates those two implementations**, since `small.mzML`'s declared
  linkage agrees with document order.
- **An older-writer reader test.** Schema is mzXML **2.0**, while MSDK targets 3.2. Confirm MSDK parses it;
  its `compressionType` check (`!= null && != "none"`) already treats an absent attribute as
  uncompressed, so this should work — verify rather than assume. `precision="32"` +
  `byteOrder="network"` + no compression is the simplest decode path, so a failure localizes cleanly.
- **A free independent check on 3 of the 5 computed columns.** mzXML scan attributes carry
  `basePeakMz`, `basePeakIntensity` and `totIonCurrent` — compare them against your computed
  `base_peak_mz`, `base_peak_i` and `tic`. That validates the collation arithmetic against the
  *instrument's own numbers*, with no Python in the loop. Expect minor float drift on `tic`; treat a
  systematic mismatch as a bug.
- Some scans carry `peaksCount="3"`, so a handful of assertions are hand-checkable.

**⚠ RT units differ per format, and each rule is different. Verified from source:**

| Format | MassQL behavior | What the Java reader must do |
|---|---|---|
| **mzXML** | uses `spectrum["retentionTime"]` as-is (`msql_fileloading.py:442,463`) — but **pyteomics has already converted it**: `XMLValueConverter.duration_str_to_float` parses the ISO-8601 duration and returns `unitfloat(minutes, 'minute')` | **Convert `PT…S` seconds → minutes.** `PT1.38S` → `0.023`. Also handle `H`/`M` components |
| **mzML** | reads `scan start time`, then `if unit_info == "second": rt = rt / 60` (`:564-571`) — a **conditional** on the declared unit | **Read `unitName`/`unitAccession` and convert only when seconds.** `data/small.mzML` declares `unitName="minute"` → pass through unconverted |
| **MGF** | `float(RTINSECONDS)/60.0`, **default 0** when absent (`:179-181, :327-328`) | Always ÷60; absent → `0.0` (not null — see §3) |

A silent 60× error here would pass every MGF-only and mzML-only test. Pin all three with unit tests.

Conversion is **not lossless** — mzXML carries no chromatograms. Verify scan numbering survives before
trusting any generated golden.

License/provenance is unstated for the Ewing Lab files, so treat them as convenience fixtures: reference
them by URL in a download script rather than committing them, unless you confirm terms.

**Trimming the MGF:** 34,513 spectra / 14 MB is committable but bloaty. Keep the first N
`BEGIN IONS`…`END IONS` blocks that still contain a useful number of matches for `test.massql`, then re-run
the Python path to regenerate the golden. **Do not trim without regenerating** — the checked-in 664-record
golden belongs to the full file. Or commit the full 14 MB and skip this; it's repo hygiene, not correctness.

### 6d. Build wiring

- JUnit 5 (`junit-jupiter`), **test scope only** — never leaks into the artifact `massql-app` embeds.
- Split fast from slow: unit tests in `src/test` (`make test`), integration tests in `src/integrationTest`
  on `*IT.java` (`make it`). The reviewer runs `make verify`, which runs both.
- **No network in any test.** The 47 golden parses are checked-in files, never live calls to
  `massql.gnps2.org/parse`. Flaky CI destroys the credibility of a conformance number.
- `make verify` wraps `gradle check` plus the three differential comparisons and prints a **pass/fail table
  per format**. That table is the review artifact.
- CI (GitHub Actions, JDK 17): `make verify` on push/PR. No display needed — nothing here touches AWT.

---

## 7. The spike — 4 steps, ~12 working days

### Step 0 — Restore the Python oracle and build the fixture set · ~0.5 day · **DO THIS FIRST**

Nothing on this machine can currently regenerate `output/*_results.json` — there's no `MassQueryLanguage`
clone, and `massql`/`lark`/`pandas` aren't importable from any interpreter present. Those two files (6 and
664 records) are the yardstick for the whole spike, and right now they're orphaned artifacts.

- Clone `github.com/mwang87/MassQueryLanguage`, **pin the exact commit SHA** (not the branch) for version
  `2026.03.14`. Record it in the README — that SHA is the definition of "MassQL-compliant" here.
- Python 3.12 venv, `pip install -e .`, save `pip freeze` verbatim.
- Re-run `massql_query.py` against both existing fixtures; diff against the checked-in JSON.
- Copy the 47 golden parse files from `tests/reference_parses/` into `src/test/resources/`.
- **Build the mzXML fixtures** (§6c): convert `small.mzML` → `small.mzXML` with Docker msconvert, and
  download the matched Ewing Lab `DP00570_F02.{mzxml,mgf}` pair. Generate a golden for each. No mzXML file
  exists on disk today, and "all 3 formats verified" is this spike's exit criterion.
- Trim the MGF and regenerate its golden, or decide to commit the full 14 MB.
- Dump the per-scan peak counts and intensity sums from the Python loader for all three formats — that's
  the input to integration layer 1.

**Done when:** all three format goldens exist and reproduce float-identically; the 47 parse goldens and the
loader-parity dumps are checked in. **Stop if the existing two don't reproduce** — a moving yardstick makes
everything downstream unmeasurable.

### Step 1 — Parser + its unit tests · ~3 days

MassQL's entire formal language is **one 165-line Lark EBNF file** ([`msql.ebnf`](oracle/msql.ebnf)). Translate to an
ANTLR4 `.g4`; ~90% mechanical — rules are already `lowercase: alt | alt`, keywords are inline literals, and
ANTLR auto-rewrites the direct left recursion. Two gotchas worth a day if you hit them cold:

- Lark uses Earley with **contextual lexing**, so it distinguishes the variable `X` from an identifier
  inside `formula(...)`/`peptide(...)`. ANTLR's maximal-munch DFA lexer can't. **Fix: lexer modes** pushed
  by those literals — or, since those functions are out of scope, don't admit them and reject cleanly.
- Keyword casing is **inconsistent**: only `QUERY/WHERE/AND/MS1DATA/MS2DATA/POSITIVE/NEGATIVE` have case
  variants; `FILTER` and `OR` have none; condition and qualifier names are strictly uppercase. Enumerate
  literally (~20 lines) — it's the difference between accepting and rejecting queries users paste from GNPS.

Write the §6a parser rows as you go: conformance, rejection, case matrix.

**Done when:** all `scaninfo` goldens parse to an equivalent AST; every other golden parses or rejects
cleanly with a named construct; `make test` green.
**Escape hatch:** `https://massql.gnps2.org/parse?query=...` returns the canonical AST as JSON (verified
live). Parse remotely, execute locally — removes all parser risk but adds a network dependency to an SDK
that should work offline. Price it only if the grammar fights back.

### Step 2 — Engine + the full test suite · ~6 days

**`scaninfo`-only fixes the *function* axis. The `WHERE`/`FILTER` condition axis is a separate choice** —
recommended split, both landing in this step:

- **2a, required** (runs `test.massql` + `test_mzml.massql`): `scaninfo(MS2DATA)`, `MS2PROD` (+`MS2MZ`
  alias), `MS2PREC`, `TOLERANCEMZ`, `TOLERANCEPPM`, `INTENSITYPERCENT`, `AND`.
- **2b, recommended (+~1 day, no new machinery)**: `scaninfo(MS1DATA)`, `MS1MZ`, `MS2NL`, `RTMIN`/`RTMAX`,
  `SCANMIN`/`SCANMAX`, `CHARGE`, `POLARITY`, `INTENSITYVALUE`, `INTENSITYTICPERCENT`, `FILTER`,
  `MASSDEFECT`, `OR` value lists, arithmetic literals with constant folding. Every one is a row mask or a
  per-scan reduction over the **same store built in 2a** — cheap precisely because the hard part is done,
  and RT/precursor filtering is what real metabolomics queries lean on. Cut 2b for the narrowest first cut;
  it's additive either way.

Order within the step matters:

1. **Columnar store + reductions**, with the §6a store-reduction unit tests written alongside. Biggest
   single chunk. **Leave a seam for a second retained index over pre-filter MS1 data** — `OTHERSCAN` needs
   it later, expensive to retrofit, free to anticipate.
2. **The three readers, then integration layer 1 (reader parity) — before any query runs.** If intensities
   aren't bit-identical to Python's, the decoder is wrong and everything downstream is measuring noise.
3. **Filters** → `scaninfo` collation → the 5 computed columns and the null rules from §3, each with its
   §6a unit test.
4. **`cli/Main`**, then integration layers 2–4 (differential, cross-format equivalence, CLI contract) and
   the error-path tests.

**Done when:** `make verify` is green and the differential table reads **6/6 on `small.mzML`, 664/664 (or
the trimmed count) on the MGF, and the full mzXML golden** — per column. Record wall-clock and peak heap; if
Java isn't at least as fast as pandas on the MGF, something is quadratic (probably a linear scan where a
binary search belongs).

### Step 3 — Harden, document, hand off · ~2 days

What makes the repo reviewable rather than just working.

- **README:** the pinned MassQL SHA, the supported-feature matrix (what parses / executes / rejects), the
  12-key result contract, the three format-population rules from §3, CLI usage, and how to run `make verify`.
- **`make verify`** printing the per-format pass/fail table. The reviewer should not have to reconstruct how
  to validate the thing.
- **Dependency audit checked in** — the SDK runtime closure after exclusions, with total byte size. This is
  the artifact that answers "did dependency complexity stay bounded?"
- **OSGi-readiness assertions** (§9) as a scripted check, so Phase 2 isn't a surprise.
- **Coverage report** (JaCoCo) — not as a target to game, but so the reviewer can see which of the §3 rules
  are actually exercised.

**⛔ REVIEW GATE — stop here.** Manual review of `massql-java` against the goldens, the test suite and the
README before any `massql-app` work starts.

---

## 8. Out of scope for v1 — reject cleanly, don't half-implement

Everything here must produce a clear "not supported in this version" error, never a wrong answer, and each
gets a rejection unit test in §6a.

**The other five functions:** `scansum`, `scannum`, `scanmaxint`, `scanmz`, `scanrangesum`.
(`scanrangesum` is a trap even later — the Python engine **ignores its own `TOLERANCE` parameter** and
hardcodes 0.1 m/z bins, so implementing it "correctly" gives different answers than MassQL.)

**`X`/`Y` variables and the enumerator.** 350 Python LOC: recursive sub-query resolution, constraint
extraction, pre-search, candidate enumeration with greedy tolerance-based skipping, per-candidate deepcopy
+ string substitution. 500–800 Java LOC, and debugging dominates writing. If it's ever wanted,
*characterize before porting*: instrument the Python, dump the candidate lists, try to write its spec in one
page. Success means a bounded job; failure is the evidence for capping scope.

**`INTENSITYMATCH` / `REFERENCE` / `PERCENT`** — compares per-scan intensity *sums* via a register, and is a
**silent no-op** without `INTENSITYMATCHPERCENT`. If built later, warn rather than replicate the silence.

**`MOBILITY`** (bounds may contain `X`, so it's gated on variables), **`OTHERSCAN`** (needs the second
retained index — leave the seam), **`CARDINALITY`/`EXCLUDED`**, **nested sub-queries**,
**`formula()`/`aminoaciddelta()`/`peptide()`** (monoisotopic mass tables needing per-element pyteomics
agreement — 250–500 LOC of pure conformance risk), **byte-exact JSON AST output**.

**Frame it honestly in the README:** "full MassQL parity" means bug-for-bug agreement with *one commit* of a
tool whose own docs advertise functions (`scanmaxmz`, `scanrun`) that don't exist, while `scanmz` and
`OTHERSCAN` exist undocumented. Publish a feature matrix and call it a `scaninfo` subset.

---

## 9. Constraints `massql-java` must satisfy so Phase 2 isn't blocked

The library's dependency choices get locked in during this spike and are the hardest thing to change later.
`massql-java` never sees OSGi, but `massql-app` will embed it — so build these in from the start and verify
them with a scripted check in Step 3:

- **No `ServiceLoader` / `META-INF/services`** anywhere in the dependency closure — the thread-context
  classloader can't see inside an OSGi bundle. (cytoscape-mcp hit this twice, with Lucene and the MCP SDK.)
  MSDK is clean; anything added later must be checked.
- **No JAXB, no native code, no `sun.misc.Unsafe`.**
- **No `META-INF/versions/**`** (multi-release jars) in embedded deps — they break Felix resolution on
  Cytoscape 3.10.x.
- **No slf4j/logback dependency.** Use `java.util.logging`, or better, no logging in the SDK at all — return
  diagnostics and let the caller log. Sidesteps a real conflict (`cy-ndex-2` embeds slf4j+logback while
  cytoscape-mcp deliberately excludes `org/slf4j/**`).
- **Under ~1.5 MB of embedded dependencies** after the §5 exclusions.
- **Java 17** — matches Cytoscape 3.10.4's parent pom. (The build is Gradle since
  [C43](Tech_Step_INDEX.md#c43); §6d's Maven wording was updated with it.)
- Put the `.g4` in **`src/main/antlr/`, never `src/main/resources/`** — Cytoscape app poms set
  `<filtering>true</filtering>` on resources and a grammar containing `${...}` gets silently corrupted. Also
  confirm the formatter (Spotless) excludes the generated-source directory.
- **Close your file handles.** MSDK memory-maps files, so `SpectraFile` must be `AutoCloseable` and the app's
  `shutDown()` will depend on it. Add an integration test that opens and closes many files without leaking.

**Recommended: a 2-hour OSGi canary at the end of Step 3.** Not an app — a throwaway bundle that just embeds
`massql-java` and logs a scan count from `small.mzML` inside Cytoscape. It's the one risk that, if it fails,
forces a change *inside* `massql-java` (vendor the MSDK parser rather than depend on it). Cheap insurance
against discovering it after the review gate. Cut it if you'd rather keep the spike strictly pure-Java —
just know that's where the exposure sits.

---

## 10. Phase 2 preview (`cytoscape/massql-app`) — not part of this spike

Sketch only, so the review has context for what it's approving into: activator copied from
`cytoscape-mcp/.../CyActivator.java` (`AppsFinishedStartingListener` + `ServiceTracker` dynamic-install
fallback + `initDone` guard + `shutDown()`); `MassqlTaskFactory` registered twice, once for the
Apps→Run MassQL menu and once with `COMMAND`/`COMMAND_NAMESPACE` so it's CyREST-drivable for in-Cytoscape
verification; a dialog with file chooser, query name, scan-column selector and query text area;
`ResultToNodeTable` writing the 12-key JSON into `massql _<query name>` joined on scan (watch for graphml
typing `scan` as String while results carry Integer — the most likely real bug there); and
`MassqlParseFunction extends AbstractFunction` registered as `org.cytoscape.equations.Function`
(`org.cytoscape:equations-api:3.10.4` is in the local Maven repo — verified). `massql-java` arrives as a nested jar on
`Bundle-ClassPath` via `Embed-Dependency`, the normal pattern (`cy-ndex-2-3.7.3.jar` nests 26 of them).

---

## 11. Questions this spike must answer

1. Do all three readers produce **bit-identical** decoded intensities vs. the Python loader? If not, what
   tolerance becomes the contract?
2. Does the same query return the same rows on `small.mzML` and `small.mzXML`?
3. Does MSDK's dependency closure stay under ~1.5 MB after exclusions? Does the released 0.0.27 pom carry
   both javolution forks?
4. Is MSDK's LGPL-2.1/EPL-1.0 shippable here? *(blocking — ask in week 1)*
5. Parser: ANTLR embedded, hand-written, or remote `/parse`?
6. Measured LOC — does the 1,200–1,800 estimate hold?
7. Where does `massql-java` live, and how is it published for `massql-app` to consume?
8. Wall-clock and peak heap on all three fixtures vs. the pandas path.

---

## 12. Reference

- Behavioral contract: `massql_query.py` (esp. `add_precursor_intensity`, lines 62-116),
  [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md), `output/*_results.json` — all in this directory
- App spec: [cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26)
- Grammar + goldens: [`msql.ebnf`](oracle/msql.ebnf) (165 lines), `tests/reference_parses/` (47 files),
  `tests/test_query.py` (the intensity property tests worth porting) in
  `github.com/mwang87/MassQueryLanguage` @ pinned SHA
- **`massql/msql_fileloading.py` (892 lines) is the authoritative reader spec** — read it before writing
  any reader. Key lines: `:414-475` mzXML loader, `:525-650` mzML loader (`_load_data_mzML_pyteomics`, the
  one actually dispatched to), `:394` MGF `ms1scan = 0`, `:517-523` mzXML polarity (`"+"`→1, `"-"`→2),
  `:564-571` the mzML RT seconds conditional. Note three unused mzML loaders remain in the file — only
  `_load_data_mzML_pyteomics` is live (`:103`)
- pyteomics is the layer MassQL's conversions actually come from, so its source settles questions the
  [API docs](https://pyteomics.readthedocs.io/en/latest/api/mzxml.html) leave open (field names and units
  are not documented there): `pyteomics/mzxml.py` for `_determine_dtype` / `_decode_peaks`,
  `pyteomics/xml.py:118-143` for `XMLValueConverter.duration_str_to_float` — the ISO-8601 → **minutes**
  conversion
- Live AST oracle: `https://massql.gnps2.org/parse?query=<urlencoded>` (documented `msql.ucsd.edu/parse` now
  serves a deprecation redirect). **No endpoint executes a query against a user-supplied file** — execution
  must be local.
- MSDK parsers (read before deciding to depend vs. vendor):
  `github.com/msdk/msdk/blob/master/msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLParser.java`,
  `.../msdk-io-mzxml/.../MzXMLFileParser.java`
- Phase-2 patterns: `../open-cyweb/pom.xml:116-132` (bundle config),
  `../cytoscape-mcp/src/main/java/edu/ucsd/idekerlab/cytoscapemcp/CyActivator.java`,
  `../cytoscape-mcp/build.gradle:143-200` (OSGi exclusion list)
