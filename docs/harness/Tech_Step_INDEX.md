# Tech Step Index — `massql-java` spike

Execution index for the spike defined in [`SPIKE.md`](SPIKE.md). `SPIKE.md` remains the source of record for
**rationale**; these 13 specs are the source of record for **what to build**. Where they disagree, the specs
win — they incorporate findings that post-date `SPIKE.md` (see [Corrections](#corrections-to-spikemd)).

Each `Tech_StepX.md` is written to be handed to one engineer and finished without reading the others, beyond
the artifacts its **Prerequisites** section names.

---

## ⚠ Numbering: there is no Tech_Step0

**`SPIKE.md` numbers its four steps 0–3. These specs are numbered 1–13.** The two numbering schemes are
unrelated — do not read "Tech_Step1" as "SPIKE.md Step 1". Mapping:

| `SPIKE.md` §7 step | Becomes | Note |
|---|---|---|
| **Step 0** — Restore the Python oracle **and** build the fixture set | **[1](Tech_Step1.md) + [2](Tech_Step2.md)** | **Split in two.** The oracle is its own gate; the fixture work is substantial and depends on it |
| *(not a SPIKE.md step — folded into §4/§6d)* | **[3](Tech_Step3.md)** | Scaffold + dependency policy, promoted to its own step because the dependency decisions are locked in here and are the hardest thing to change later |
| **Step 1** — Parser + its unit tests | **[4](Tech_Step4.md)** | |
| **Step 2** — Engine + the full test suite | **[5](Tech_Step5.md) – [12](Tech_Step12.md)** | The 6-day step, re-cut into eight. `SPIKE.md`'s own ordering within it (store → readers → parity → filters → collation → CLI → integration) is preserved |
| **Step 3** — Harden, document, hand off | **[13](Tech_Step13.md)** | |

Also note **`SPIKE.md`'s 2a/2b condition split is Tech_Step9's 9a/9b** — same content, renumbered to match its
owning step.

## Status table

| Step | Goal | Prereqs | Est. | Gate | State |
|---|---|---|---|---|---|
| [1](Tech_Step1.md) | Restore the Python oracle; prove the goldens reproduce | — | 0.25 d | ⛔ **GATE** | ✅ **DONE 2026-07-29 — gate green** |
| [2](Tech_Step2.md) | Build the full fixture + golden set (3 formats) | 1 | 0.5 d | | ✅ **DONE 2026-07-30** |
| [3](Tech_Step3.md) | Scaffold `massql-java`; fix the dependency policy; **CI + release workflows** | — | 0.5 d | | ✅ **DONE 2026-07-30** |
| [4](Tech_Step4.md) | Grammar, typed AST, `Massql.parse()` | 3, 1 | 3 d | | ✅ **DONE 2026-07-30** |
| [5](Tech_Step5.md) | Columnar store + per-scan reductions | 3 | 1.5 d | | not started |
| [6](Tech_Step6.md) | `SpectraReader` iface + MGF reader + mzML reader | 3, 5, 1, 2 | 1.5 d | | not started |
| [7](Tech_Step7.md) | Vendored mzXML reader | 3, 5, 6, 2 | 1 d | | not started |
| [8](Tech_Step8.md) | Reader parity — bit-identical vs Python | 2, 6, 7 | 0.5 d | ⛔ **GATE** | not started |
| [9](Tech_Step9.md) | Condition filters (9a required + 9b) | 4, 5, 8 | 2 d | | not started |
| [10](Tech_Step10.md) | `scaninfo` collation, result model, JSON | 9, 5 | 1.5 d | | not started |
| [11](Tech_Step11.md) | Public API surface + CLI | 10 | 0.5 d | | not started |
| [12](Tech_Step12.md) | Integration layers 2–4 + error paths | 11, 2, 8 | 1.5 d | ⛔ **GATE** | not started |
| [13](Tech_Step13.md) | Harden, document, hand off | 12 | 2 d | ⛔ **REVIEW** | not started |

**Total ≈ 16 days** (SPIKE.md §7 estimated ~12; the delta is the mzXML vendoring in Step 7 and the finer
integration split, both of which were folded into Step 2 of the original estimate).

## Dependency graph

```
Step1 (oracle, GATE) ──► Step2 (fixtures) ─────────────────────────┐
                                                                    │
Step3 (scaffold) ─┬──► Step4 (parser) ──────────────────────┐       │
                  │                                          │       │
                  └──► Step5 (store) ──► Step6 (mgf+mzml) ──► Step7 (mzxml vendored)
                                                │                   │
                                                └──► Step8 (parity GATE) ◄──┘
                                                             │
                                                    Step9 (filters)
                                                             │
                                                    Step10 (collation)
                                                             │
                                                    Step11 (API + CLI)
                                                             │
                                                    Step12 (integration GATE)
                                                             │
                                                    Step13 (harden) ──► ⛔ REVIEW GATE
```

Steps 1–2 (Python side) and Step 3 (Java scaffold) are independent — **Step 3 can start immediately**, in
parallel with Step 1.

### The three internal gates

Each gate exists because work past it is unmeasurable if it fails. **Do not work around a red gate.**

| Gate | Condition | Why it stops the work |
|---|---|---|
| **Step 1** | Both checked-in goldens reproduce float-identically | A moving yardstick makes every later comparison meaningless |
| **Step 8** | Decoded intensities bit-identical to the Python loader, all 3 formats | If the decoder is wrong, every downstream number measures noise |
| **Step 12** | Differential green per column: 6/6 mzML, 664/664 MGF, full mzXML | This is the spike's exit criterion |

---

## Settled decisions

These are decided. Specs implement them; they are not open for re-litigation inside a step.

| # | Decision | Resolution |
|---|---|---|
| 1 | Function axis | **`scaninfo` only.** The other 5 functions reject cleanly (Step 4) |
| 2 | Condition axis | **Both 9a and 9b** — the full `WHERE`/`FILTER` set (Step 9) |
| 3 | Repo on disk | `/Users/shreuland/dev/massql-java`, sibling to `massql/` |
| 4 | Repo home + publishing | `github.com/cytoscape/massql-java` → nrnb-nexus `cytoscape_releases`. `mvn install` to `~/.m2` during the spike |
| 5 | MGF fixture | Commit the full 14 MB `PlusRise.mgf`. **Do not trim** — the 664-record golden belongs to the full file |
| 6 | Parser | **ANTLR 4.13.2**, embedded |
| 7 | mzML + MGF readers | **Vendor** MSDK's `MzMLParser` + `MSNumpress` (Correction C16 — Guava); MGF hand-written |
| 8 | mzXML reader | **Vendor** MSDK's `MzXMLFileParser`, dsiutils import swapped out (Step 7) |
| 9 | MSDK license | **Elect EPL-1.0** from the dual LGPL-2.1 / EPL-1.0 offer |
| 10 | OSGi canary | Presented as a decision *at* the review gate, not done unasked |
| 11 | MSDK overall | **Vendoring source, never a dependency** (C16). Shipping closure is 2 artifacts — javolution + antlr = **785,599 B (0.749 MB), 49.9% of budget**. Measured, not estimated. |

---

## Corrections to `SPIKE.md`

Verified after `SPIKE.md` was written. **These override it.**

**C1 — `msdk-io-mzxml` is not usable as a dependency (§5 is wrong, by ~20 MB).**
`SPIKE.md` §5 says taking mzXML transitively is "convenient" and instructs "exclude CDK + Guava and slf4j."
That is impossible: `MzXMLFileParser` **directly imports** `it.unimi.dsi.io.ByteBufferInputStream`,
`com.google.common.collect.Range`, `org.slf4j.*`, and `SpectrumTypeDetectionAlgorithm`. dsiutils is not
mentioned anywhere in `SPIKE.md`.

| Path | Measured closure |
|---|---|
| mzML only — io-mzml 73,102 + datamodel 65,591 + javolution 459,292 + commons-io 208,700 + slf4j-api 41,105 | 847,790 B ≈ 0.81 MB — ⚠ **WRONG, superseded by C16:** this omits Guava 27.1 + satellites (2,992,669 B), which `msdk-datamodel` requires unavoidably. The real MSDK-based figure is **3.97 MB**. |
| + ANTLR 4.13.2 runtime 326,307 | ≈ 1.17 MB — ⚠ same omission. **Actual shipping closure after vendoring both parsers: 785,599 B (0.749 MB).** |
| mzXML via `msdk-io-mzxml` as-is | + dsiutils 440,530 + **fastutil 7.1.0 = 17,655,579** + Guava 21 = 2,521,113 + commons-configuration/collections/math3 → **> 20.6 MB, ~14× budget**, plus logback at runtime, which §9 bans |

→ **Exclude `msdk-io-mzxml` entirely** (Step 3) and **vendor the parser** (Step 7). The swap is cheap because
MSDK already wrote an API-compatible replacement for the mzML path:
`io.github.msdk.io.mzml.util.ByteBufferInputStream` exposes the same `map(FileChannel, MapMode)` / `read` /
`length()` / `position()`. MZmine vendored this parser too — portability is proven by construction.

**C2 — the javolution split-package risk does not exist (§5 asked to verify).**
The released `msdk-io-mzml-0.0.27.pom` has `org.javolution:javolution-core-java` **commented out**; only
`com.github.chhh:javolution-core-java-msftbx:6.11.8` is declared. **Do not add that exclusion.**

**C3 — the MSDK license question is resolved and not blocking (§5 called it blocking, week-1).**
`msdk:0.0.27`'s parent pom declares a genuine **dual** license — LGPL-2.1 **or** EPL-1.0, both
`<distribution>repo</distribution>`. Dual licensing means the consumer elects, so **we elect EPL-1.0**
(weak-copyleft, business-friendly). Record the election in the README and in every vendored file header
(Steps 7, 13). The repo's root `LICENSE` is 368 bytes and GitHub reads it as NOASSERTION; **the pom is
authoritative**.

**C4 — pin `slf4j-api` at 1.7.26 and forbid 2.x.**
Not in `SPIKE.md`. slf4j **1.7 uses static binding; 2.x uses `ServiceLoader`**, which §9 forbids outright. A
routine dependency bump would silently break Phase 2 OSGi resolution with no obvious cause. This is a hard
build constraint with the reason recorded, not a version preference (Step 3).

**C5 — `msdk-spectra-centroidprofiledetection` exists in the closure.** Not mentioned in §5. Only the mzXML
parser calls it, so excluding `msdk-io-mzxml` removes the need (Steps 3, 7).

### Corrections found while executing Step 1 (verified from the pinned source)

**C6 — MGF `charge` defaults to `1`, not to null. `SPIKE.md` §3's population table is wrong.**
`.mgf` dispatches to `_load_data_mgf` (`msql_fileloading.py:145`), which tries `_load_data_mgf_pyteomics` and
falls back to the manual parser **only if pyteomics yields zero rows** — so the pyteomics loader is the live
specification. Its charge handling (`:192-203`) is `params.get('charge', [1])` with `except: charge = 1`. Since
only `0` is null-converted, **MGF `charge` is never null**, and a genuine 1+ is indistinguishable from an absent
`CHARGE=`. Confirmed end-to-end: `plusrise_results.json` charge counts are `{1: 653, 2: 10, 3: 1}`, zero nulls.
→ Affects [Step 6](Tech_Step6.md) §2 and [Step 10](Tech_Step10.md) §6, both updated.

**C7 — MGF scan id = `SCANS=` if present, else the 1-based block index.** `:177`
(`scan = params.get('scans', index + 1)`). [Step 6](Tech_Step6.md) §2 had left this to be derived; now resolved.

**C8 — MGF polarity is not read at all** on the live path. A `POLARITY` condition cannot meaningfully filter an
MGF ([Step 9](Tech_Step9.md)).

**C9 — the reference-parse corpus is 46 files, not 47** (35 `scaninfo`, 11 non-`scaninfo`), and there are **2**
unused mzML loaders, not 3. `msql.ebnf` is 165 lines and `msql_fileloading.py` is 892 lines, both as claimed.

### Corrections found while executing Step 4

**C17 — "every `scaninfo` golden must parse" is wrong; the corpus splits 15 parse / 31 reject.**
[Step 4](Tech_Step4.md)'s conformance row said every `scaninfo` golden must parse to a
canonical-equal AST. In fact **20 of the 35 `scaninfo` goldens contain out-of-scope
constructs** (X/Y variables, `MOBILITY`, `formula()`, the intensity-match family,
`EXCLUDED`, `CARDINALITY`) and must reject. Measured partition: **15 parse, 31 reject**,
recorded in the checked-in `src/test/resources/reference_parses/corpus-manifest.tsv` so the
scope decisions are reviewable in one place rather than buried in test code.

Also: when a query contains **several** unsupported constructs, the test asserts the reported
construct is *one of* those present, not a specific one. Asserting a specific one pins
traversal order, which has no user-visible meaning. `AstBuilder` reports the first in
**source order** (it validates a condition before its qualifiers for that reason).

**C18 — `Comparator.NONE` is unreachable and was removed.** Step 4 §3 required a `NONE`
value "distinct from `EQ`". Verified against the corpus: every in-scope qualifier carries
`=`, `>` or `<`; the only comparator-less qualifiers are the out-of-scope ones
(`INTENSITYMATCHREFERENCE`, `EXCLUDED`, `CARDINALITY`, `MASSDEFECT`). So SPIKE.md §3's
"a missing comparator defaults to greater-than" refers to an **absent qualifier** — the
implicit `> 0` on an unqualified intensity column ([Step 9](Tech_Step9.md) §3) — not to a
qualifier that parsed without one. `Comparator` is `{EQ, GT, LT}`.

**C19 — three constructs the specs never mentioned, all now rejected by name.** Found by
reading `msql.ebnf`: **`ANY`** (`wildcard: "ANY"`, so `MS2PROD=ANY` is legal MassQL);
**`MATCHCOUNT`** (a second spelling of `CARDINALITY`); and the **bare `MS1DATA`/`MS2DATA`
querytype with no function at all** (3 reference parses use it), rejected as
`<no function>`. Also **`MASSDEFECT` is a qualifier, not a condition** — [Step 9](Tech_Step9.md)
listed it under conditions. And the `TOLERANCE` parameter alternative accepts only
`MS1DATA`, not `MS2DATA`; an asymmetry in the source, faithfully reproduced.

### Corrections found while executing Step 3

**C16 — MSDK cannot be a dependency at all; Guava is unavoidable via `msdk-datamodel`. SPIKE.md §5's core
reader decision is reversed, and so is half of C1.**
`msdk-datamodel` cannot link without Guava: `MsScan` declares `Range<Double> getScanningRange()` in the
**interface**, and `SimpleMsScan` holds a `Range` field and calls `Preconditions`. This is not a
"try excluding it" case. SPIKE.md §5 *did* say "exclude CDK + Guava (2.7 MB, arrives via msdk-datamodel)" —
C1 under-credited that line by tracing Guava only through the mzXML path.

Guava + annotation satellites = **2,992,669 B (2.85 MB)**, taking the closure to **3.97 MB** (2.65× budget).
**Size was the least of it:**

- Cytoscape exports **Guava 9.0.0** (`guava-osgi:9.0.0`, circa 2011); MSDK compiles against **27.1**.
  Importing cannot satisfy an 18-major-version gap.
- Guava 27.1 **is itself an OSGi bundle** exporting `com.google.common.*` at `version="27.1.0"`, so embedding
  makes bnd emit `Import-Package: com.google.common.collect;version="[27.1,28)"` by default — Felix tries to
  satisfy it from the runtime, finds Guava 9, and **fails to resolve the bundle** unless the imports are
  explicitly negated. The classic `Embed-Dependency` footgun.
- `jsr305` rides along exporting `javax.annotation` (69 classes), a known duplicate-exporter conflict source.

`cy-ndex-2` does embed Guava 30.1.1 alongside core's 9.0.0, so private embedding is proven here — but it hands
Phase 2 three standing obligations. **Resolution: vendor the mzML parser too** ([Step 6](Tech_Step6.md)),
exactly as [Step 7](Tech_Step7.md) already does for mzXML, replacing Guava `Range` with a plain pair.

**Shipping closure is now two artifacts — 785,599 B (0.749 MB), 49.9% of budget:**
`javolution-core-java-msftbx` 459,292 (the ServiceLoader-free `XMLStreamReaderImpl`) + `antlr4-runtime`
326,307. Both audited: zero `META-INF/services`, zero `META-INF/versions`, zero native libs. **No Guava, no
slf4j, no MSDK, no commons-io, no JAXB, no CDK.** Enforced by `maven-enforcer-plugin` at `validate`, so the
build fails rather than the bundle.

> Also worth recording: **JUnit itself violates constraints 1 and 4** (`junit-platform-commons` ships 10
> `META-INF/versions` entries, `junit-jupiter-engine` 2 `META-INF/services`). Correct and harmless — but it is
> why the constraint check must be scoped to compile+runtime and never to test.

### Corrections found while executing Step 2

**C11 — `msconvert` cannot run on Apple Silicon; `small.mzXML` is generated instead.** SPIKE.md §6c's
"standard macOS route" (pwiz via Docker) aborts under qemu — wine's 32-bit code asserts on the 16 KB page
size. `docker run` still exits 0 after the core dump. `oracle/mzml_to_mzxml.py` generates the fixture;
validated Java-independently by the oracle reading both formats. **11 of 12 columns bit-identical**; the
twelfth (`ms1_precmz`) differs by exactly a float32 truncation, inherent to mzXML's single `precision`
attribute. Full analysis in [`data/CONVERSION_NOTES.md`](../../../massql/data/CONVERSION_NOTES.md).
→ [Step 12](Tech_Step12.md) Pair A must allow ~1e-7 relative on `ms1_precmz` for 32-bit mzXML.

**C12 — MassQL's mzXML loader emitted `ms1scan` as a `str`, nulling all three `ms1_*` columns for every
mzXML input.** `msql_fileloading.py:441,447,463` assign `spectrum["id"]`, which pyteomics returns as a
string for mzXML, while `ms1_df["scan"]` is `int64` — so `ms1_base.get('2')` → `None` and
`ms1_df["scan"] == '2'` → 0 rows. Values were never wrong, only the type. **Fixed in `massql_query.py`**
with a `pd.to_numeric(...).astype("Int64")` coercion (the one deliberate divergence from stock behaviour;
mzML unaffected, both pre-existing goldens still byte-identical). This also repaired the `0`→null sentinel
rule, which had been silently no-opping on mzXML by comparing a str to an int. **Java is immune by
construction** — it parses `num="2"` to an int. This restores SPIKE.md §6c's premise: the Ewing file now
yields `ms1scan=1` and populated `ms1_*` with zero `precursorScanNum`, so [Step 7](Tech_Step7.md)'s decisive
test is viable exactly as specified.

**C13 — Pair B cannot be a row-identity join; the two Ewing files have disjoint scan ids.** mzXML matches
`[2, 556, 871]`, MGF matches `[370, 598]` — intersection empty. The MGF was converted *from* this mzXML
(`COM=` header) and `TITLE=DP00570_F02.0003.0003.2` holds the original scan number, but there is no `SCANS=`
so MassQL uses the block index; charge filtering also dropped 62 of 687 MS2 scans (625 blocks remain).
→ [Step 12](Tech_Step12.md) Pair B is a **population-pattern** comparison, not a join. **Carry to
Phase 2:** for MGF without `SCANS=`, `scan` is a positional index, not the instrument scan number — and the
Cytoscape app joins the node table on `scan`.

**C14 — both MGF loaders are live, depending on the file.** `_load_data_mgf` falls back to the manual parser
when pyteomics yields zero rows. `PlusRise.mgf` → **manual** (pyteomics cannot index it), `scan` dtype
**str**, ids from `SCANS=`. `DP00570_F02.mgf` and `micro.mgf` → pyteomics, `int64`, ids from the block index.
The unified rule for [Step 6](Tech_Step6.md) is unchanged (**`SCANS=` if present, else 1-based block
index**), but revises C6: charge defaults to 1 only on the *pyteomics* path. Also measured: **MassQL loads
21,942 of `PlusRise.mgf`'s 34,513 spectra** (758,544 peak rows) — [Step 8](Tech_Step8.md) must assert
**21,942**, not 34,513. And **MGF `ms1_df` is a synthetic 1-row all-zero placeholder, not empty**, so
`massql_query.py:170`'s `len(ms1_df) == 0` branch never fires for MGF (Step 6's conclusion still holds; its
rationale did not).

**C15 — the `scaninfo(MS1DATA)` output shape is 9 keys, not 4.** SPIKE.md §3 and
[Step 10](Tech_Step10.md) §5 say `scan, rt, tic, mslevel` only. Measured from the reference wrapper:
`scan, rt, mslevel, tic, base_peak_i, base_peak_mz, ms1_i, ms1_precmz, ms1_base_peak_i`. `precmz`/`ms1scan`/
`charge` **are** absent as documented, but `massql_query.py:161-179` adds the five computed columns
unconditionally, and they come back **null** for MS1DATA (base peaks are computed from `ms2_df`, which has no
MS1 scan ids). **⚠ OPEN DECISION before Step 10:** does the SDK emit the reference's 9-key shape, or the
4-key shape SPIKE.md specifies (and if 4, the mzML MS1DATA golden must be regenerated)? Arguably the SDK
should compute `base_peak_i`/`base_peak_mz` from the MS1 scan itself, which is neither.

**C10 — `output/small_mzml_results.json` was regenerated at the documented 20 ppm default.** The original had
been produced at an unrecorded ~60 ppm tolerance and could not be reproduced from documented parameters; its
exact bytes are preserved as `output/small_mzml_tol60_results.json`. Full analysis in
[Step 1](Tech_Step1.md) §3a. Consequence: there are now **two** mzML goldens, and the 20 ppm one supplies the
golden coverage of the "a tolerance miss nulls `ms1_i`/`ms1_precmz` but not `ms1_base_peak_i`" rule that
previously had none. **Every golden must record the non-default flags it was generated with**
([Step 2](Tech_Step2.md) §5).

## Established facts

Verified; do not re-derive.

- **MassQL pin:** tag `2026.03.14` = **`dad2a28c01e6e5132240270fc6700fbae29f1652`**. Default HEAD is
  `17e8c74` — pin the SHA, not the tag or branch. ✅ cloned and verified in Step 1.
- **Oracle environment (Step 1, reproducible via `oracle/venv-setup.sh`):** Python **3.12.0**,
  `lark-parser 0.12.0`, `pandas 3.0.5`, `numpy 2.5.1`, `pyteomics 5.0.1`, `py-expression-eval 0.3.14`,
  `pyarrow 25.0.0`. Full freeze in `oracle/requirements.freeze.txt` (28 packages).
- **Goldens reproduce** — `bash oracle/reproduce-goldens.sh` is green: 664, 6 and 6 records, byte-identical.
- **MassQL's own dataframe dtypes:** `i`/`i_norm`/`i_tic_norm` are **float32**; `mz` and `rt` are **float64**.
  Corroborates that golden intensities are `(double)(float)raw` ([Step 6](Tech_Step6.md) §3) and that `rt` must
  be carried as a double ([Step 5](Tech_Step5.md) §1).
- **Ewing fixtures are live:** `DP00570_F02.mzxml` = 3,761,778 B; `DP00570_F02.mgf` = 2,196,881 B; HTTP 200.
- **Toolchain present:** JDK 17.0.18, Maven 3.9.12, Docker daemon up, git. Python **3.12.0 via pyenv** — the
  `python3` shim resolves to 3.13, so always name the interpreter explicitly. `massql` is not importable.
- **On disk today:** `data/small.mzML` (5,103,183 B), `data/PlusRise.mgf` (15,172,489 B),
  `output/small_mzml_results.json` (6 records), `output/plusrise_results.json` (664 records),
  `test.massql`, `test_mzml.massql`. No mzXML anywhere. `massql/` is **not** a git repo.

---

## Out of scope for v1 — reject cleanly, never half-implement

Everything here must produce a clear "not supported in this version" error naming the construct, never a wrong
answer. **Each gets a rejection unit test in Step 4.**

- **The other five functions:** `scansum`, `scannum`, `scanmaxint`, `scanmz`, `scanrangesum`.
  (`scanrangesum` is a trap even later — the Python engine **ignores its own `TOLERANCE` parameter** and
  hardcodes 0.1 m/z bins, so implementing it "correctly" would disagree with MassQL.)
- **`X`/`Y` variables and the enumerator** — 350 Python LOC of recursive sub-query resolution, candidate
  enumeration and greedy tolerance skipping. If ever wanted, *characterize before porting*.
- **`INTENSITYMATCH` / `REFERENCE` / `PERCENT`** — a silent no-op without `INTENSITYMATCHPERCENT`. If built
  later, warn rather than replicate the silence.
- **`MOBILITY`** (bounds may contain `X`), **`OTHERSCAN`** (needs the second retained index — leave the seam in
  Step 5), **`CARDINALITY`/`EXCLUDED`**, **nested sub-queries**,
  **`formula()`/`aminoaciddelta()`/`peptide()`** (monoisotopic mass tables — pure conformance risk),
  **byte-exact JSON AST output**.
- **Formats:** Thermo `.raw` and mzMLb — permanently out, no pure-Java reader exists.

**Frame parity honestly in the README (Step 13):** "full MassQL parity" means bug-for-bug agreement with *one
commit* of a tool whose own docs advertise functions that don't exist (`scanmaxmz`, `scanrun`) while `scanmz`
and `OTHERSCAN` exist undocumented. Publish a feature matrix and call it a **`scaninfo` subset**.

---

## Spec conventions

Every `Tech_StepX.md` has these sections, in this order: **Goal · Prerequisites · Context · Scope ·
Deliverables · Specification · Known traps · Tests required · Done when · References.**

### Where a discovery goes — the fallout protocol

Every step turns up things its spec did not predict. Without a declared destination those findings live only
in whoever's head found them. **Three destinations, and a finding usually needs more than one:**

| Kind of finding | Goes to | Example |
|---|---|---|
| **A spec is wrong, or a later step's assumption breaks** | A numbered **Correction in this file**, *and* an edit to every affected step spec at the point of use | C13: Pair B cannot join on `scan` → INDEX + Tech_Step12 §2 rewritten |
| **A fact about a fixture** — provenance, what was verified, measured counts | [`data/CONVERSION_NOTES.md`](../../../massql/data/CONVERSION_NOTES.md) | the Ewing file's 11 `peaksCount="3"` scans and their instrument attributes |
| **A fact about the oracle** — pin, environment, golden provenance, reader-source verification | `oracle/PINNED.md` and `oracle/NOTES_fileloading.md` | the corpus is 46 files, not 47 |

Rules:

1. **A Correction is not done until the affected specs are edited.** The INDEX entry records *what* changed;
   the step spec is where an engineer will actually read it. Adding one without the other is the failure mode
   this protocol exists to prevent — the engineer building Step 12 will not think to re-read Step 2's notes.
2. **Reference the Correction by its label** (`Correction C13`) in the spec edit, so the two stay findable
   from each other.
3. **When a step completes, audit the propagation** rather than trusting recall: list the Corrections that
   step produced and confirm each is referenced by every spec it affects. Note that
   `data/CONVERSION_NOTES.md` scope grew during Step 2 — it is now fixture provenance for the whole spike, not
   just the msconvert record its Step 2 deliverable row described.
4. **Do not silently fix a spec.** If an implementation deviates from what the spec says, either the spec was
   wrong (Correction) or the implementation is (fix it). Quietly diverging leaves the spec lying to the next
   reader.

### Two rules that keep 13 documents consistent

1. **One rule, one home.** A rule is stated in full in the spec that *implements* it, and cross-referenced
   (never restated) elsewhere. If you need a rule's exact wording, follow the cross-reference rather than
   trusting a paraphrase.
2. **Scope has no gaps.** Every "out of scope" item names the step that owns it. If you find something in
   neither an in-scope nor an out-of-scope list, that is a spec bug — report it rather than improvising.

Shared vocabulary: **native column** = produced by MassQL's own `scaninfo`; **computed column** = one of the 5
the SDK must derive itself (Step 10); **golden** = a Python-generated reference output; **micro-fixture** = a
hand-written 3–5 scan file with hand-computable values (Step 2).
