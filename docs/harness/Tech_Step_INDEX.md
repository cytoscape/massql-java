# Tech Step Index — `massql-java` spike

Execution index for the spike defined in [`SPIKE.md`](SPIKE.md). [`SPIKE.md`](SPIKE.md) remains the source of record for
**rationale**; these 13 specs are the source of record for **what to build**. Where they disagree, the specs
win — they incorporate findings that post-date [`SPIKE.md`](SPIKE.md) (see [Corrections](#corrections-to-spikemd)).

Each `Tech_StepX.md` is written to be handed to one engineer and finished without reading the others, beyond
the artifacts its **Prerequisites** section names.

---

## ⚠ Numbering: there is no Tech_Step0

**[`SPIKE.md`](SPIKE.md) numbers its four steps 0–3. These specs are numbered 1–13.** The two numbering schemes are
unrelated — do not read "Tech_Step1" as "SPIKE.md Step 1". Mapping:

| [`SPIKE.md`](SPIKE.md) §7 step | Becomes | Note |
|---|---|---|
| **Step 0** — Restore the Python oracle **and** build the fixture set | **[1](Tech_Step1.md) + [2](Tech_Step2.md)** | **Split in two.** The oracle is its own gate; the fixture work is substantial and depends on it |
| *(not a SPIKE.md step — folded into §4/§6d)* | **[3](Tech_Step3.md)** | Scaffold + dependency policy, promoted to its own step because the dependency decisions are locked in here and are the hardest thing to change later |
| **Step 1** — Parser + its unit tests | **[4](Tech_Step4.md)** | |
| **Step 2** — Engine + the full test suite | **[5](Tech_Step5.md) – [12](Tech_Step12.md)** | The 6-day step, re-cut into eight. [`SPIKE.md`](SPIKE.md)'s own ordering within it (store → readers → parity → filters → collation → CLI → integration) is preserved |
| **Step 3** — Harden, document, hand off | **[13](Tech_Step13.md)** | |

Also note **[`SPIKE.md`](SPIKE.md)'s 2a/2b condition split is Tech_Step9's 9a/9b** — same content, renumbered to match its
owning step.

## Status table

| Step | Goal | Prereqs | Est. | Gate | State |
|---|---|---|---|---|---|
| [1](Tech_Step1.md) | Restore the Python oracle; prove the goldens reproduce | — | 0.25 d | ⛔ **GATE** | ✅ **DONE 2026-07-29 — gate green** |
| [2](Tech_Step2.md) | Build the full fixture + golden set (3 formats) | 1 | 0.5 d | | ✅ **DONE 2026-07-30** |
| [3](Tech_Step3.md) | Scaffold `massql-java`; fix the dependency policy; **CI + release workflows** | — | 0.5 d | | ✅ **DONE 2026-07-30** |
| [4](Tech_Step4.md) | Grammar, typed AST, `Massql.parse()` | 3, 1 | 3 d | | ✅ **DONE 2026-07-30** |
| [5](Tech_Step5.md) | Columnar store + per-scan reductions | 3 | 1.5 d | | ✅ **DONE 2026-07-30** |
| [6](Tech_Step6.md) | `SpectraStream` cursor + MGF reader + mzML reader | 3, 5, 1, 2 | 1.5 d | | ✅ **DONE 2026-08-03 — 275 tests** |
| [7](Tech_Step7.md) | **Hand-written** mzXML reader (C23) | 3, 5, 6, 2 | 1 d | | ✅ **DONE 2026-08-03 — 335 tests** |
| [8](Tech_Step8.md) | Reader parity — bit-identical vs Python | 2, 6, 7 | 0.5 d | ⛔ **GATE** | ✅ **GATE GREEN 2026-08-03 — 392 tests** |
| [9](Tech_Step9.md) | Condition filters (9a required + 9b) | 4, 5, 8 | 2 d | | ✅ **DONE 2026-08-05 — 453 tests** |
| [10](Tech_Step10.md) | `scaninfo` collation, result model, JSON | 9, 5 | 1.5 d | | not started |
| [11](Tech_Step11.md) | Public API surface + CLI | 10 | 0.5 d | | not started |
| [12](Tech_Step12.md) | Integration layers 2–4 + error paths | 11, 2, 8 | 1.5 d | ⛔ **GATE** | not started |
| [13](Tech_Step13.md) | Harden, document, hand off | 12 | 2 d | ⛔ **REVIEW** | not started |

**Total ≈ 16 days** (SPIKE.md §7 estimated ~12; the delta is the finer integration split, plus what was
originally scoped as mzXML *vendoring* in Step 7 — now hand-written per **C23** — both of which were
folded into Step 2 of the original estimate).

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

Verified after [`SPIKE.md`](SPIKE.md) was written. **These override it.**

> ### Adding a Correction — read this first
>
> **From C38 onward, a `Fallout:` line is mandatory and `make verify` fails without it.** Full rationale in
> [*Where a discovery goes — the fallout protocol*](#where-a-discovery-goes--the-fallout-protocol), rule 2b;
> the template:
>
> ```markdown
> **C39 — one-line statement of what was wrong.**
>
> **Fallout:** Tech_Step5.md, Tech_Step10.md
>
> <the evidence, measured or executed rather than reasoned>
> ```
>
> Use `**Fallout:** none -- <reason>` when nothing needs editing. Then **edit each declared spec and cite the
> correction's label in it** — `make spec-audit` check 3 fails if a declared file never mentions it.
>
> Corrections **C1–C37 predate the rule** and keep the older convention, where the obligation is inferred from
> `Tech_StepX.md` links in the body. Do not retrofit them: 12 link no step at all, and inventing affected-sets
> for them would make the ledger look authoritative while being guesswork. The gap is written down instead.
>
> Two habits this ledger has earned the hard way, both worth more than the numbering:
> **state the evidence, not the reasoning** — C34, C36 and C37 were all found by *running* the reference
> implementation, and C33's cause was misattributed for a whole step by inference that sounded right; and
> **a rule with no test able to falsify it is not yet a rule** — C36, C37 and C38 are all instances of code
> that was correct with nothing able to prove it, which is the single most common shape of defect here.

**C1 — `msdk-io-mzxml` is not usable as a dependency (§5 is wrong, by ~20 MB).**
[`SPIKE.md`](SPIKE.md) §5 says taking mzXML transitively is "convenient" and instructs "exclude CDK + Guava and slf4j."
That is impossible: `MzXMLFileParser` **directly imports** `it.unimi.dsi.io.ByteBufferInputStream`,
`com.google.common.collect.Range`, `org.slf4j.*`, and `SpectrumTypeDetectionAlgorithm`. dsiutils is not
mentioned anywhere in [`SPIKE.md`](SPIKE.md).

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
Not in [`SPIKE.md`](SPIKE.md). slf4j **1.7 uses static binding; 2.x uses `ServiceLoader`**, which §9 forbids outright. A
routine dependency bump would silently break Phase 2 OSGi resolution with no obvious cause. This is a hard
build constraint with the reason recorded, not a version preference (Step 3).

**C5 — `msdk-spectra-centroidprofiledetection` exists in the closure.** Not mentioned in §5. Only the mzXML
parser calls it, so excluding `msdk-io-mzxml` removes the need (Steps 3, 7).

### Corrections found while executing Step 1 (verified from the pinned source)

**C6 — MGF `charge` defaults to `1`, not to null. [`SPIKE.md`](SPIKE.md) §3's population table is wrong.**
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
unused mzML loaders, not 3. [`msql.ebnf`](oracle/msql.ebnf) is 165 lines and `msql_fileloading.py` is 892 lines, both as claimed.

### Corrections found while implementing Step 6

**C24 — four implementation findings, each now pinned by a test.**

**(a) javolution logs to STDOUT.** `XMLStreamReaderImpl` calls `LogContext.info(...)` every time it
grows its character buffer, and an mzML `<binary>` element triggers that on essentially every real
file — 6 lines on `small.mzML`. **The governing rule is `DEPENDENCY_POLICY.md` constraint 2: the SDK
logs nothing at all**, to *either* stream — this is an SDK-layer reader, so that is the whole argument,
and it is why `StdoutCleanlinessTest` asserts stdout **and** stderr are silent. Secondarily, and at a
different layer, it would also corrupt the **Java CLI**'s stdout payload ([Step 11](Tech_Step11.md); the
Step 12 differential is such a consumer). Fixed by `JavolutionQuiet` raising `LogContext.LEVEL` above
`INFO`. **Nothing else in the suite would have noticed.** (Leading this entry with the CLI contract
instead of constraint 2 is the defect corrected by **C25(a)**.)

**(b) The `12,571 dropped spectra` mystery in C14 is explained — nothing is dropped.**
`PlusRise.mgf` has 34,513 blocks of which **12,571 contain no peak lines**, leaving exactly the 21,942
MassQL reports. They are real spectra with zero peaks; MassQL's dataframe simply has no *rows* for them.
Our reader yields all 34,513, which is correct. **[Step 8](Tech_Step8.md) must expect that asymmetry**,
and must also exclude MassQL's synthetic 1-row all-zero MGF MS1 placeholder — the dump's 758,545 total
is 758,544 real peaks plus that phantom row.

**(c) `SpectrumTableBuilder`'s capacity was tuned for whole-file building.** Under streaming it runs once
per *scan*, so its 1024-element initial arrays cost 16 KB for a 22-peak MGF spectrum — ~190 MB of
garbage across PlusRise. Added a capacity-hint constructor (mzML passes `defaultArrayLength`, MGF its
counted peaks) and reduced the defaults. **No Step 5 test changed**, so the redesign still has not leaked.

**(d) Peak used-heap is not evidence of bounded memory.** The first `StreamingMemoryTest` asserted on it
and failed at 119 MB — but with only ~10 KB *retained*, that is short-lived garbage the JVM had no reason
to collect. Peak heap under no memory pressure measures GC laziness. Replaced with a subprocess that
streams PlusRise inside **`-Xmx48m`**: it passes, which is an actual proof. Retained-after-settling is the
other honest assertion.

### Corrections found while reviewing the stdout / data-transfer design

**C25 — the specs conflate three artifacts, and the Java CLI has no `--output` flag.**

**(a) The terminology defect, and the harm it did.** C24(a) justified the `JavolutionQuiet` fix by
saying javolution's logging "breaks [Step 11](Tech_Step11.md)'s contract that stdout carries only the
JSON array" — without stating that Step 11 is the **CLI** layer. The bug is in `MzmlReader`, an **SDK**
component, so the claim read as *"the SDK treats stdout as a data pipe"*. That is false, and it drew a
justified challenge that the design was violating 12-factor. The rule that actually governs an SDK
reader is `DEPENDENCY_POLICY.md` constraint 2: **a library writes to no stream at all** — which is why
`StdoutCleanlinessTest` asserts stdout *and* stderr are silent. The CLI contract was the weaker
argument and should never have been the leading one. See **Terminology** under *Spec conventions*; the
three labels are now binding on every spec.

**(b) There is no `--output FILE`.** stdout-as-data stays the **default** — it is the Unix filter
convention, it is what the reference implementation deliberately does, and it keeps the Step 12
differential a literal diff. But offering *only* stdout is a real gap for a batch tool, so
[Step 11](Tech_Step11.md) gains `--output FILE`, written as `FILE.tmp` then `Files.move(…,
ATOMIC_MOVE)` so a downstream consumer never observes a partial file.

**(c) [Step 12](Tech_Step12.md)'s differential conflated two independent properties** — "is the JSON
correct" and "are the streams clean" — by testing correctness *through a pipe*. Now split: the
differential runs `--output <tmp>` and diffs the **file**; stream hygiene stays in Step 11's
`MainStreamDisciplineTest` / `MainNoStackTraceOnStdoutTest`, cross-referenced not duplicated.

**Not affected: the SDK.** The Cytoscape app calls `Massql.execute` and writes the JSON into the node
table in-process. There is no stdout, no temp file and no process boundary anywhere in the app's data
path — the layer the reader assumed was at issue was never involved.

### Corrections found while auditing the real §3 authority

**C37 — most of [Step 9](Tech_Step9.md) §3's rules live in `msql_engine_filters.py`, a file §3 never names,
and two of them are wrong.** §3 cites `msql_engine.py` as *"the authority for every rule in §3"*. The
tolerance computation, the intensity comparators and all four condition functions are in
**`msql_engine_filters.py`**. That mis-citation is the plausible root cause of the errors below.

**(a) The m/z window is STRICT, not inclusive.** §3 said *"both bounds inclusive… a peak exactly at an edge
matches"*. All four condition functions use `>` and `<` — `:253` (MS2PROD), `:410` (MS2PREC), `:493`/`:519`
(MS1MZ), `:607` (`ms1_filter`).

**Proven by execution, not just by reading.** `micro.mzML` scan 3 has a peak at exactly `201.0`; the query
`MS2PROD=201.5:TOLERANCEMZ=0.5` gives the window `[201.0, 202.0]`, putting that peak precisely on the lower
bound. **MassQL returns 0 rows.**

**(b) …but Step 10's precursor lookup is INCLUSIVE, so the two callers genuinely differ.**
`massql_query.py:101-103` uses `>=` / `<=`. Also proven: at `--precursor-tol-ppm 7.8125` the
`499.99609375` peak lands exactly on the bound and **`ms1_i` comes back populated** (`1000.0`).

**Resolution: `mzWindow` stays inclusive for Step 10; Step 9 gets `mzWindowExclusive`.** Changing the single
primitive would have silently broken `ms1_i`/`ms1_precmz`, the columns Step 12 checks at 1e-9 — a new
divergence introduced while fixing an old one. `MzWindowTest`'s comment claimed *"an exclusive bound here
would silently narrow every tolerance"*; the reasoning is backwards, and it is corrected in place.

**(c) `INTENSITYTICPERCENT` also divides by 100.** §3 stated the ÷100 rule for `INTENSITYPERCENT` only. Both
carry `scale = 100.0`.

**(d) The 0.99 cap covers BOTH percent qualifiers.** Known-traps said *"it is `>` and `INTENSITYPERCENT`
only"*. The guard is `if scale > 1.0`, which includes `INTENSITYTICPERCENT`. §3's other half — *"apply the cap
only for `>`"* — **is** correct: it sits inside the `greaterthan` branch.

**(e) The implicit `> 0` is per column, on all three.** An absent qualifier yields
`i > 0 AND i_norm > 0 AND i_tic_norm > 0`, not one blanket check.

**(f) `MS2NL` matches per peak on `(precmz − mz)`**, not against a precomputed target — algebraically the same
window, so either implementation is faithful. Two notes: the source carries
`#TODO: This is incorrect logic if it comes to PPM accuracy`, because with `TOLERANCEPPM` the tolerance is
derived from the neutral-loss *value* rather than an m/z — bug-for-bug fidelity means reproducing that. And a
`precmz == 0` scan is excluded **naturally** (`0 − mz` is negative, the window positive), so §3's rule needs
no explicit guard.

**Verified CORRECT, recorded so nobody re-derives them:** PPM beats Da and the default is `0.1`; `=` → `>=`
(`:89`); the cap applies only to `>`; `RTMIN`/`RTMAX` strict vs `SCANMIN`/`SCANMAX` inclusive — the asymmetry
is real; `POLARITY` → `==1`/`==2`; `CHARGE` → `==`; an OR value list without `CARDINALITY` is `pd.concat`,
i.e. union.

**Scan-level intersection is confirmed, and this is the strongest result of the audit.** Every condition
reduces to a *scan set* and then re-admits **all rows of those scans** (`:283-288`, `:557-562`). So a later
condition sees every peak of each surviving scan, which is exactly why two conditions may be satisfied by
different peaks. §1's central claim holds.

**(g) Condition order is provably irrelevant for the in-scope set — and no existing fixture could test it.**
Because each condition re-admits all rows of surviving scans, no predicate ever sees a *reduced* peak list; so
each condition is a pure intersection `S ← S ∩ P` with `P` fixed by the file, and intersection commutes. The
constructs that *do* read filtered state — `OTHERSCAN`, `INTENSITYMATCH*`, `CARDINALITY`, `EXCLUDED` — are
**all rejected at parse**, which is what makes the argument airtight rather than merely plausible.

Empirical backing needed a new fixture: `small.mzML`'s MS1 scans are profile-mode on an **identical m/z grid**
(19,800 peaks each, verified), so no `MS1MZ` value can distinguish them, and `micro.mzML` has one usable MS1
scan. **Neither could discriminate.** `micro_ms1var.mzML` has two MS1 scans with *different* peaks; the mixed
query returns `[2]` with the conditions in **either** order — not `[2,4]` (no MS1 filtering) or `[]`
(over-filtering).

**(h) Two errors in §Tests' description of the property tests.** It called them *"pure profit — these need
no reference data"*. The properties are self-referential, but **the tests as written need two fixtures we do
not have** — `featurelist_pos.mgf` and `GNPS00002_A3_p.mzML`, MassQL's own test data, verified absent — so they
had to be reconstructed on our fixtures rather than ported.

And the described properties are not all real:
- **"tripartite partition (`<` ∪ `=` ∪ `>` covers everything exactly once)" is impossible.** `=` means `>=`,
  which *contains* `>` by construction. The reference test asserts the actual relationship, `>` ⊆ `=`, which is
  the more useful assertion anyway — it directly encodes the `=` → `>=` rule.
- **Disjointness of `>` and `<` is not general.** Under scan-level semantics a scan may hold one peak above the
  threshold and another below it, so it belongs in *both* sets — correctly. The reference test avoids this with
  a narrow `TOLERANCEMZ=0.01` window; `IntensityAlgebraTest` constructs that precondition and **asserts it**,
  so the disjointness test cannot pass or fail for unrelated reasons.

Monotonicity is general and needs no precondition.

**New fixtures and goldens:** `test_micro_edge.massql` pins the strict bound against Python with an **empty**
golden — itself a meaningful assertion, and the reason the error survived is that no prior query isolated that
peak. `micro_ms1var.mzML` + `test_micro_ms1var.massql` pin order-independence. Both added to the Step 8 gate,
now **16 fixtures**.

### Corrections found while executing Step 9

**C36 — MGF drops ZERO-INTENSITY peaks; our reader kept them. mzML and mzXML keep them on both sides.**

`_load_data_mgf_pyteomics` opens its peak loop with `if intensity == 0: continue`, so such a peak never
becomes a row — MassQL cannot match it, count it or sum it. **The mzML and mzXML loaders have no such
guard**, and that asymmetry is genuine: `small.mzML`'s parity dump records `i_hex_first8` as **eight
`0x0.0p+0` entries**, retained on both sides and compared bit-for-bit by the Step 8 gate.

**Why it was latent, and why that keeps happening.** Not one of the three MGF fixtures contained a single
zero-intensity peak — measured — so the parity gate passed while structurally unable to see the
divergence. That is now the fifth instance of the same shape (C27b, C28, C29, C31, C33): *a rule with no
fixture that can discriminate*. `micro_zeroint.mgf` closes it, with three deliberately different blocks:

| Block | Contents | MassQL | Ours |
|---|---|---|---|
| 1 | real peaks with a zero **between** two of them and one **trailing** | 3 rows | 3 peaks |
| 2 | **every** peak zero-intensity | **no rows at all** — the scan vanishes from `ms2_df` | yielded with **0 peaks** |
| 3 | control, one normal peak | 1 row | 1 peak |

Block 2 is the interesting one: it reduces to a zero-peak scan, which is exactly what C35(c)'s executor
guard then has to skip. The two corrections meet there.

**`iNorm`/`iTicNorm` needed no change**, verified rather than assumed: MassQL computes `i_max`/`i_sum` from
the **full** array *before* the skip, and a zero alters neither a max nor a sum — so our builder's
denominators, computed over the retained peaks, are identical. No Step 5 change.

**Proven to have teeth.** With the skip reverted, `ZeroIntensityPeakTest` fails 4 of 6 (`expected <4> but
was <8>` on the peak total) **and** `ReaderParityIT` fails with `micro_zeroint.mgf MS2 scan 1: peak count
expected: <3> but was: <5>`. Both directions catch it.

**Do not generalise the skip to mzML/mzXML.** `ZeroIntensityPeakTest` asserts both formats *retain* their
zeros, in the same class as the MGF assertions, so a future tidy-up that unifies the three readers fails
loudly rather than breaking every mzML fixture in the gate.

### Corrections found while auditing spec-vs-code drift after Step 9

<a id="c38"></a>
**C38 — ten test classes named by [Step 9](Tech_Step9.md) were never written, and under that cover THREE
conditions had no execution test while the exit criterion claiming otherwise sat ticked.**

**Fallout:** Tech_Step6.md, Tech_Step7.md, Tech_Step8.md, Tech_Step9.md, Tech_Step13.md

Step 9's *"Tests required"* table listed 14 classes; 10 did not exist. The coverage had been consolidated into
five classes during implementation — which is fine — but the table kept the original names, so *"does
`ConditionCoverageTest` exist?"* was a question with no answer that nobody thought to ask.

**What hid there.** `MS2PREC`, `CHARGE` and `MS2NL` are implemented in `ConditionFilters` and were exercised
**only by parse tests**. The whole filter for any of them could have been inverted and the suite stayed green,
while *"every 9a and 9b condition has a positive and a negative test"* was checked in Done-when. This is the
recurring shape — C36, C37 and now C38 are all *a rule with nothing able to falsify it* — except that here the
missing coverage was **disguised as present coverage** by a phantom class name.

Closed by six methods in `QueryExecutorTest`. The `MS2PREC` strict bound is sabotage-verified: flipping
`>`/`<` to `>=`/`<=` at `ConditionFilters:76` fails with `expected: <[]> but was: <[3, 5]>`.

**The same drift existed in three earlier "complete" steps**, and — unlike Step 9 — none of it was a real
coverage gap, which is worth recording because it is the reassuring half of the finding:

| Spec | Phantom | Reality |
|---|---|---|
| [Step 6](Tech_Step6.md) | `MgfScanNumberingTest`, `Ms1ScanDocumentOrderTest`, `ReaderErrorPathTest` | all covered — folded into `MgfReaderTest`, promoted to `Ms1ScanDocumentOrderIT`, split across `MzmlReaderTest`/`FormatSniffTest` |
| [Step 7](Tech_Step7.md) | `SpectraFileCloseTest` | renamed to `SpectraStreamCloseTest` under C22; §5 already said so in prose |
| [Step 8](Tech_Step8.md) | `ParityCoverageTest` | deliberately dropped under C32 |

**One genuinely missing deliverable surfaced too.** `VendoredProvenanceTest` ([Step 7](Tech_Step7.md)) was
never written, and **[`VENDORED.md`](../VENDORED.md) did not exist at all** — a [Step 6](Tech_Step6.md) deliverable that
Step 7's exit criteria recorded as *"unchanged"* and [Step 13](Tech_Step13.md) lists as a review artifact,
while **all eleven vendored files' headers point readers at it**. Every header was correct; the central record
they defer to was absent, and the licence election (MSDK is dual-licensed, this project elects **EPL-1.0**) was
a convention rather than a build-enforced fact. Both now exist. Writing the test found a second thing: not
everything under `io/vendor/` is upstream — `LittleEndianDataInput` is ours (C16) — so the test detects
non-vendored files by a **marker in the file itself** rather than a filename allowlist in the test, which is
what stops a genuinely vendored file from being exempted quietly.

**Now enforced by `make spec-audit` check 4**: a **completed** step naming a test class that neither exists nor
carries a `→` redirect fails the build. Completion is read from each spec's own Done-when checkboxes, so
Steps 10–13 are exempt until they land and the scope widens by itself.

**C39 — the spec-audit guard was twice wrong in the direction that reads as coverage; fallout is now DECLARED
from C38 onward rather than inferred.**

**Fallout:** none — this correction is about `scripts/spec-audit.sh` and the recording convention, both of
which are documented at [*Adding a Correction*](#corrections-to-spikemd) and in the fallout protocol rule 2b.
No step spec's content changes.

Two bugs in the guard itself, both found by using it rather than by reading it, and both of the same shape —
**a check that passes while proving nothing**:

| Bug | Effect | Found by |
|---|---|---|
| Check 2's regex missed `**16 dumps.**` — punctuation *inside* the bold | **silently vacuous** on [`FIXTURES.md`](FIXTURES.md), the one file whose three contradictory counts (15 / 14 / 16) motivated writing it | injecting the drift and getting **no failure** |
| Check 3's citation test was a **prefix match** (`\bC1[a-z(]?` matches the `C1` inside `C18`) | every single-digit correction counted as cited in specs that never mention it — C1 "cited" in 8 | an ad-hoc count returning an implausible 8 |

Neither was concealing a real gap — verified both ways, and all four single-digit fallout claims (C6→Steps 6
and 10, C7→Step 6, C8→Step 9) do cite correctly under the fixed pattern — but each would have passed one.
**Every check is now demonstrated to fail on injected drift before being trusted**, procedure in the script's
`DEMONSTRATING FAILURE` block. Re-run it after touching any pattern.

**The ratchet.** Inferring obligation from `Tech_StepX.md` links protects only corrections that link a step,
and **12 of C1–C37 link none** — including [C22](#c22), the largest correction in the project. Hand-audited:
all 12 are genuinely propagated (C22 by prose), so the hole was prospective. From **C38** a `Fallout:` line is
**required**, and check 3 fails on a missing line as well as on a declared-but-uncited spec. Deliberately not
retrofitted: ~30 of the older entries would need an affected-set invented, and a ledger that reads as
authoritative while being guesswork is worse than one whose gap is recorded.

**What this still cannot catch.** A divergence nobody records as a Correction at all. That is rule 4 (*do not
silently fix a spec*), and it is not mechanizable — the build can check that a declared obligation was met, not
that a discovery was declared.

<a id="c40"></a>
**C40 — the result contract has ONE uniform 12-key shape. Three documents and one golden said otherwise, and
`scaninfo(MS1DATA)`'s `base_peak_i`/`base_peak_mz` nulls were a join artifact in our own wrapper.**

**Fallout:** Tech_Step2.md, Tech_Step10.md, Tech_Step11.md, Tech_Step12.md, Tech_Step13.md

Found while reviewing [Step 10](Tech_Step10.md) before implementation. [`SPIKE.md`](SPIKE.md) §3 cites
[cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) as its source at `:64` and then
**narrows it** at `:124-127`: *"`scaninfo(MS1DATA)` → a different, smaller shape … `precmz`/`ms1scan`/`charge`
and all `ms1_*` columns are **absent, not null**."* The issue says the opposite — the schema is
*"a union of all possible attributes from ms1 and ms2"* with **`mslevel`** as the discriminator. **No key is
ever absent.** Four sources had drifted apart:

| Source | Claimed | |
|---|---|---|
| **issue #26** | one 12-key union, `mslevel` discriminates | ✅ authoritative |
| `SPIKE.md:124-127`, `:261` | MS1DATA is a smaller shape; keys **absent** | ❌ |
| oracle `RESULT_SCHEMA.md:25-29` | *"a different, smaller schema"* | ❌ |
| `small_mzml_ms1_results.json` | **9 keys**, 5 all-null | ❌ neither 4 nor 12 |
| `Tech_Step12.md` | `:139` said **9**, `:290` said **4** | ❌ self-contradictory |

**The two defects behind the 9-key golden, which are not the same defect.** Its five nulls split cleanly:

- **`ms1_i` / `ms1_precmz` / `ms1_base_peak_i` are genuinely null and stay that way.** An MS1 survey scan has
  no precursor, so they are undefined. The reference reaches this for the right reason — MassQL's `ms1_df`
  carries no `ms1scan` column, so `massql_query.py`'s empty-MS1 branch fires. Issue #26 sanctions exactly these
  as nullable. **Unchanged.**
- **`base_peak_i` / `base_peak_mz` were a LEFT-JOIN ARTIFACT.** `massql_query.py:186` computed base peaks from
  `ms2_df` and then `results_df.merge(base, how="left", on="scan")`. For an MS1DATA query `results_df` holds MS1
  scan ids while `ms2_df` holds only MS2 ids — in `small.mzML` those sets are **disjoint** (`[1,2,8,9,…]` vs
  `[3,4,5,…]`), so every row missed the join and became `NaN` → `null`.

**Proof it was an artifact rather than a rule about MS1 spectra:** in `micro.mgf` the phantom MS1 id (`3`)
**collides** with a real MS2 id (`3`), so the identical join attaches an unrelated MS2 scan's base peak to the
MS1 row — a **wrong non-null**. The output depended on scan-id collision, not on any property of the spectrum.
Independently, issue #26 marks `base_peak_i` *"Can be null? **No**"*, so the golden violated the published
model. A survey scan plainly has a base peak.

**Resolution.** The rule is *MS1 ids join only to MS1 data, MS2 ids only to MS2 data.* `massql_query.py` now
selects the base-peak source frame by the query's level and emits the full union key set for MS1DATA;
`small_mzml_ms1_results.json` was regenerated (12 keys, real base peaks, the three `ms1_*` still null) — the
**only** golden affected, the other 14 were already 12-key.

**[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) is now the single definition** and every other document links to it instead of
restating the key set. That is what stops a fourth variant appearing: the contract was specified in four places
precisely because it was *specifiable* in four places. Enforced by `spec-audit` **check 6** (every non-empty
golden carries exactly the 12 keys in order) and by **`ResultSchemaContractTest`**, which parses the key table
out of that document and asserts `ResultJson` emits exactly those keys — making the doc executable rather than
decorative.

**[C15](#c15) is retired into this entry**, and this is the first Correction recorded under the C38+ ratchet —
the `Fallout:` line above is mandatory, and `spec-audit` check 3 fails the build until all five named specs
cite `C40`.

<a id="c41"></a>
**C41 — harness documentation lived in a non-versioned directory outside the repo, and `SPIKE.md` had already
forked there.**

**Fallout:** Tech_Step1.md, Tech_Step2.md, Tech_Step4.md, Tech_Step5.md, Tech_Step6.md, Tech_Step8.md,
Tech_Step9.md, Tech_Step12.md, Tech_Step13.md

`../massql` held **1,229 lines** of our own markdown and **is not a git repo** — no history, no reviewable
change, no way to notice a divergence. Four of those artifacts were cited by the specs as *design input*:

| Artifact | Why it mattered |
|---|---|
| `data/CONVERSION_NOTES.md` | **26 citations**; the fallout protocol's designated home for fixture facts |
| `oracle/NOTES_fileloading.md` | the verified loader facts behind [`READER_RULES.md`](READER_RULES.md); carries the **C6/C7/C8** text |
| `oracle/PINNED.md` | the pin, the environment, golden provenance, the wrapper divergences |
| `oracle/msql.ebnf` | **18 references** — [Step 4](Tech_Step4.md)'s declared prerequisite, [Step 1](Tech_Step1.md)'s exit checkbox, `Massql.g4`'s cited translation source, and the file **C19** was found by reading |

**Two defects had already happened, and neither was luck.**

1. **`SPIKE.md` forked.** The outside copy still read *"`scaninfo(MS1DATA)` → a different, smaller shape …
   absent, not null"* — the text [C40](#c40) had corrected **hours earlier** in the in-repo copy. Two files, one
   fixed, nothing able to see the difference.
2. **`PINNED.md` lost a record it was the designated home for.** `massql_query.py` said *"the one deliberate
   divergence … see oracle/PINNED.md"* and PINNED.md carried none until C40 added it — the same
   defer-to-a-missing-document shape as [C38](#c38)'s `VENDORED.md`.

The common cause: **`spec-audit` could not reach outside the repo**, so this was the only harness content with no
verification at all. [C26](#c26) had already moved the *fixtures* in for exactly this reason; the documents were
left behind.

**Resolution.** Everything a spec cites is now in-repo. Six engineering-record documents moved from `docs/` into
`docs/harness/` — they are read by someone *confirming the steps*, not by someone consuming the SDK — and the
four artifacts above moved into `docs/harness/oracle/`. The redundant outside copies are deleted, not stubbed.

**Two documents deliberately stayed in `docs/`**: [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md), the published
contract the Phase-2 app consumes, and [`VENDORED.md`](../VENDORED.md), the EPL-1.0 election a redistributor
needs. Both are also **read at runtime by tests**, so their paths are code rather than prose.

**The boundary is now a rule, not a judgement call:**

> `docs/harness/oracle/` holds the oracle **artifacts and records that harness design references**.
> `../massql` keeps **only executable tooling** — nothing a spec cites as design input.

**Enforced by `spec-audit` check 7**, which resolves every markdown link three ways: the file exists, an
`#anchor` matches a real heading or `<a id>`, and the target is **inside the repo**. Run against the tree
*before* the move it found two defects unrelated to it — a `[C22](#c22)` link with no matching anchor, and five
links pointing out of the repo — which is why the move was verified by measurement rather than by inspection.
Check 5's pattern also had to gain a `/`: it matched `docs/[A-Za-z_]+\.md`, so a subdirectory path would have
stopped matching and the check would have covered *less* than before while still reporting green.

### Corrections found while reviewing Step 9 as its implementer

**C35 — [Step 9](Tech_Step9.md) names a type that does not exist, and its §1 contradicts itself.** Five
defects, of which **(a) is retired into [C18](#c18)** as a duplicate — leaving four live. The code is right in
every case; the spec would have pushed an implementer to break it.

**(a) ⛔ RETIRED — superseded by [C18](#c18), which recorded this same finding when Step 4 completed. See C18.**

Nothing else changes: the finding stands, and every existing citation of **C35(a)** — in
[Step 9](Tech_Step9.md), `Comparator.java`'s javadoc, `ComparatorSemanticsTest` and
`IntensityQualifiers.java` — resolves correctly by landing here and following the link. No numbering moved and
no reference was rewritten.

**Why it is retired rather than merged.** C35(a) and C18 are the *same* conclusion (`Comparator` is
`{EQ, GT, LT}`; "a missing comparator defaults to greater-than" is about an **absent qualifier**, the implicit
`> 0`) recorded **five steps apart**. Keeping two entries invites a future reader to think there were two
findings, or to fix one and leave the other. C18 has priority as the original.

**And the duplication is itself the lesson.** C18 named Step 9 §3 as affected but **Step 9 was never edited**,
so at Step 9 the fact was rediscovered from scratch and written down again. That is the fallout protocol —
*"a Correction is not done until the affected specs are edited"* — failing in the one way discipline cannot
catch, which is why `make spec-audit` check 3 now asserts mechanically that a correction naming a step is
referenced in that step's file.

**(b) §1's `execute` signature contradicted the C22 note eight lines above it.** It read
`execute(MassqlQuery, SpectrumTable ms1, SpectrumTable ms2)` returning "ordinals (not scan ids)" — exactly the
whole-file tables C22 abolished. Under streaming there is no whole-file ordinal space; a single-scan table's
only ordinal is **0**. Replaced with a **per-scan callback**: `QualifyingScanConsumer` plus
`ExecutionSummary(qualifyingScans, diagnostics)`. That keeps retained memory at one scan + one MS1 (the C22
property proven under `-Xmx48m`), needs one pass, and yields scan-id-ascending order from document order for
free. Step 10's collation *is* the consumer.

**(c) Zero-peak scans must be skipped, and §1's skeleton had no guard.** MassQL's loaders `continue` on an
empty intensity array, so its dataframes hold no rows for them. Measured: **PlusRise's dump reports 21,942 MS2
scans where our reader yields 34,513**; `micro.mzML`'s `ms1_df` is `[2]`, with the zero-peak scan 4 absent.

A *peak-based* condition fails an empty scan naturally — but a **scan-level** condition (`POLARITY`, `RTMIN`,
`SCANMIN`, `CHARGE`) never looks at peaks, so without the guard a scan-level-only query returns **34,513 where
MassQL returns 21,942**. A third of the result set, silently. Guard added before the MS1 retention, since an
empty MS1 must also not become an `ms1scan` link (C27b) — the same rule one layer up. Pinned by
`ZeroPeakScanExclusionTest`.

**(d) Two stale references.** §3 still instructed the implementer to *"derive the exact definition"* of
`MASSDEFECT` from the Python source, while §2 marks it out of scope (C19) and `UnsupportedConstructs:59`
rejects it **by name** — research spent on a construct the SDK refuses. And §1 named
`SpectrumTable.scansWithAnyRow`; it is `RowMask.scansWithAnyRow(SpectrumTable)` → `BitSet`.

**(e) §4 named two AST types that do not exist and omitted one that does.** It said `ConstantFolder` reduces
"`BinaryExpr` over `NumberLiteral`s". The AST is `sealed interface Expr` with **three** records:
`Expr.Literal`, `Expr.Binary` and **`Expr.Unary`** — never mentioned. A folder written to the spec would
silently leave a negated literal such as `MS2NL=-18` unfolded. `ConstantFoldingTest` now covers `Unary`.

Also corrected: §5 (and [Step 11](Tech_Step11.md) §2) cited `DEPENDENCY_POLICY.md` **constraint 5** for "the
SDK logs nothing" — that is constraint **2**; 5 is "No split packages". And "All ten §3 rules" in Done-when
became "every rule" — there are ~14, and a hard count goes stale the moment one is added.

**Verified sound, so recorded rather than re-derived:** `test_query_py_reference.py` exists (42 KB), so the
property tests are portable; `test.massql`'s golden really is **664** rows with the three-`MS2PROD` shape;
`MS2MZ` is collapsed to `MS2PROD` at `AstBuilder:160`, so the engine never sees the alias; `MOBILITY` and
`MASSDEFECT` are rejected at parse; `ConditionType`/`QualifierType` cover every in-scope condition; and the
in-scope qualifier set is exactly `TOLERANCEMZ`, `TOLERANCEPPM`, `INTENSITYVALUE`, `INTENSITYPERCENT`,
`INTENSITYTICPERCENT`.

### Corrections propagated FROM Step 8 into later steps

**C34 — `tic` cannot be bit-identical, and two later specs require it to be. Found by following C33(c)
forward instead of waiting for Step 12 to fail.**

C33(c) established that MassQL accumulates intensities in **`float32`**. `tic` is the same
computation: `msql_engine.py:638,660` produce it as `ms2_df.groupby("scan").sum()["i"]` over that float32
column, then `massql_query.py:158` renames `i` → `tic`. So the golden's `tic` carries float32 accumulation
error while our float64 sum is exact.

**Measured against `output/small_mzml_results.json` — all six rows differ:**

| scan | golden `tic` (float32 sum) | float64 sum (ours) | rel diff |
|---|---|---|---|
| 3 | 586278.875 | 586278.8533592224 | 3.691e-08 |
| 10 | 848427.375 | 848427.357460022 | 2.067e-08 |
| 17 | 1102582.0 | 1102582.026266098 | 2.382e-08 |
| 24 | 990298.625 | 990298.6140146255 | 1.109e-08 |
| 37 | 1041573.6875 | 1041573.6759586334 | 1.108e-08 |
| 44 | 925073.8125 | 925073.8236341476 | 1.204e-08 |

**Only `tic`.** `base_peak_i` is a `max()` — a *selected* value, no accumulation — and is
**bit-identical on all six rows**, verified. Same for `ms1_i` and `ms1_base_peak_i`, which are lookups and
maxima. So the fix is a split, not a blanket loosening:

| Column | Policy |
|---|---|
| **`tic`** | relative **1e-6** — float32 accumulation in the *reference* |
| `base_peak_i`, `ms1_i`, `ms1_base_peak_i` | **bit-identical**, unchanged |

**(a) [Step 12](Tech_Step12.md) §1 grouped all four as "Bit-identical (intensities)".** Its caveat had the
right instinct — "`tic` is the one column where an accumulation-order caveat could apply… if it fails only
on `tic` and only in the last bits" — but the wrong **cause** (dtype, not order) and the wrong
**magnitude**: 3.7e-08 is not "the last bits", and a reader chasing an ordering bug would not find it.

**(b) [Step 10](Tech_Step10.md)'s `CollationAnchorTest` hardcodes `tic 586278.875`** and asserts the golden
record "field by field". As written it **fails** — our value is `586278.8533592224`. It must assert `tic`
within 1e-6 while keeping every other field exact.

> **Why this matters more than the tolerance itself.** Step 12 is a gate. Arriving there to find `tic`
> failing on every row of every fixture, with a spec that says the cause is accumulation *order*, is a day
> lost to chasing the wrong thing — and the tempting fix ("just loosen it") would have been adopted without
> knowing that the error lives in the reference rather than in us. It does: our sum is the accurate one.

**C34(b) — `POLARITY` cannot filter an MGF, and now matches everything rather than nothing.** Following
C33(a): MGF polarity is a constant **1**, so `POLARITY=Positive` matches **every** MGF scan and
`POLARITY=Negative` matches **none**. Under the old (wrong) value of 0 both matched nothing.
[Step 9](Tech_Step9.md) §5 says "`0` (unknown) matches neither", which is still true of mzML/mzXML but no
longer describes MGF at all. The condition is not a filter on data there — it is a filter on a constant.

### Corrections found while executing Step 8 — the gate found a real reader bug

**C33 — MGF `polarity` is a hardcoded `1`, not `0`. Correction C8 was wrong, and this is the first genuine
reader bug the parity gate caught.**

`MgfReader.polarity()` returned `0`, citing C8: *"polarity is not read on the live path"*. C8's premise is
right — no MGF header carries polarity — but its **conclusion was wrong**. Both MGF loaders write
`"polarity": 1  # Default` into every peak dict (`msql_fileloading.py:67` and `:86`), so MassQL reports
**positive** for every MGF row. Measured across all three MGF fixtures — `micro.mgf` 7 rows,
`DP00570_F02.mgf` 107,178, `PlusRise.mgf` 758,544 — the distribution is `{1: all}`. Not one `0`.

**This is exactly what Step 8 exists for.** Returning `0` would have failed the
[Step 12](Tech_Step12.md) differential on the polarity column for **every MGF row**, where it would have
presented as a collation bug three steps away from its cause. Found before any query logic was written,
fixed in one line. `MgfReaderTest` previously *asserted* the wrong value, so the unit suite was actively
defending the bug — the golden was the only thing that could have known.

> **Process note.** C8 was derived by reading which fields the loader *parses*, and the loader does not
> parse polarity. The defaulting happened somewhere else in the function. Reading a rule off the parse
> path is not the same as reading it off the output — when they can disagree, only the output settles it.

**C33(b) — the MGF fake MS1 row is not always an all-zero placeholder.** [`READER_RULES.md`](READER_RULES.md) and C14/C24b
said MassQL "synthesises a 1-row all-zero MS1 placeholder for MGF (mz=0, i=0, scan=1)". True for one of the
two MGF loaders only. The pyteomics loader ends with:

```python
# This is kind of a hack for compatibility
try:
    ms1_df = pd.DataFrame([peak_dict])     # peak_dict LEAKS from the MS2 peak loop
except Exception:
    peak_dict = { "i": 0, "mz": 0, "scan": 1, ... }   # the all-zero form
    ms1_df = pd.DataFrame([peak_dict])
```

`peak_dict` is the loop variable, so the fake MS1 row is a **byte-for-byte duplicate of the last MS2
peak** — verified identical on `micro.mgf` (scan 3, m/z 123.456789012345, i 4096.0) and
`DP00570_F02.mgf` (scan 625, m/z 897.5525, i 2449.0). The all-zero `except` branch is reached only when the
loop never ran, which is `PlusRise.mgf`'s case via the manual-loader fallback (pyteomics cannot index it).

Consequences: the dump's `ms1_peak_rows: 1` **double-counts a real peak** on the pyteomics path rather than
adding a synthetic zero, and its scan id is the *last* MS2 scan's — which is why it collides (C32a). Our
readers omit it either way, correctly, since MGF has no survey scans.

**C33(c) — the `i_sum` tolerance was blamed on the wrong cause.** [Step 8](Tech_Step8.md) §1 attributed the
sum exception to numpy's pairwise accumulation and set **1e-15**. Measured, that fails on correct code: the
real cause is **dtype**. MassQL's intensity column is `float32`, and `dump_loader_parity.py:81` records
`g["i"].sum()` — a **float32 accumulation**. On `small.mzML` MS1 scan 1 the dump holds `69381840.0` where
the true sum is `69381842.11895752`: relative error **3.05e-08**.

Our Java sum is the *more accurate* one — accumulating the same values in float64 reproduces it **exactly**
(measured difference `0.000e+00`). Tolerance set to **1e-6**, absorbing the dump's float32 epsilon
(~1.2e-7) and nothing of ours. It costs the gate nothing because the **digests** establish bit-identity with
no tolerance at all.

### Corrections found while reviewing Step 8 as its implementer

**C32 — [Step 8](Tech_Step8.md)'s parity harness, as specified, would compare the wrong rows and assert
things that are false on a correct reader.** Five defects, four of them created by Steps 6/7 changing
reality underneath the spec. None is a code defect — the readers appear correct.

**(a) Keying by scan id alone is unsafe: the MGF phantom MS1's id COLLIDES with a real MS2 id.** MassQL
synthesises an all-zero MS1 placeholder for MGF (C14/C24b) and **it is present in the dumps**. Measured:

| dump | phantom MS1 scan id | collides with a real MS2 id? |
|---|---|---|
| `micro.mgf` | **3** | **yes** |
| `DP00570_F02.mgf` | **625** | **yes** |
| `PlusRise.mgf` | 1 | no |

§2 said "keyed by scan id", which would silently compare a real MS2 scan against the synthetic zero row —
2 of 3 MGF fixtures. **Key by `(mslevel, scan)`.**

**(b) "MS1 scan count — Exact" is wrong for every MGF fixture.** Each MGF dump reports
`ms1_scan_count: 1` (that phantom); our reader correctly yields **0** MS1 scans for MGF. Skip
`mslevel == 1` entries on MGF fixtures, and assert our MGF MS1 count is 0.

**(c) The zero-peak reconciliation was unspecified, and the deltas are large.** The dumps omit every
zero-peak scan; our readers deliberately yield them. **PlusRise: 34,513 reader scans vs 21,942 dump
entries.** `micro.mzML`: 5 vs 4. The rule is now stated, and the count of reader-only scans is
**asserted** per fixture rather than tolerated — otherwise a reader that dropped real scans passes.

**(d) §1's preferred "multiset of individual intensities" is not implementable, and contradicts itself.**
The dumps store **SHA-256 digests** plus first-8 values; individual peaks are not there (Step 2 measured
the all-values form at 86 MB and rejected it). Digests are also **order-sensitive**, which contradicts
`ReaderParityHarnessTest`'s required "reordering compares equal". Resolved as **digest-based** —
strictly stronger than a multiset, because it pins order too.

Digest comparison is only valid because our array order matches MassQL's file order, and
`SpectrumTableBuilder` **sorts by m/z when a scan is unsorted** (`:174-176`). Verified: **zero fixtures
have descending m/z within a scan**, so the sort never fires. `PeakOrderPreconditionTest` now asserts that
precondition, so a future unsorted fixture fails with the right diagnosis instead of looking like a decode
bug.

**(e) "Scan ids, in order, as a list — Exact" contradicts the spec's own C28 note** that the dumps are
level-grouped rather than document order. Replaced with a keyed comparison.

**Four stale references, all from C26/C30 fallout:**
- The ⚠ claiming the dumps hold `scan`/`ms1scan` as **strings** is **false**.
  `dump_loader_parity.py:78` coerces (`int(scan) if str(scan).lstrip("-").isdigit() else str(scan)`), and
  every dump reads back `int`. Harmless advice that sends the implementer hunting for a non-problem.
- Deliverable path `src/test/resources/loader-parity/` → `src/test/resources/goldens/loader-parity/`.
- Prerequisite said `oracle/loader-parity/*.json` → they are `.json.gz` and committed in-repo.
- `InstrumentAttributeCrossCheckIT` was listed as a Step 8 deliverable but **was built in Step 7** and
  passes with C30's corrected 1e-5 tolerances. Step 8 cross-references it rather than rebuilding it.
  `ParityCoverageTest`'s stated purpose is now covered by C26's zero-skip CI gate plus
  `FixturesContractTest`; its residual value folds into `ReaderParityIT`.

**Also resolved here: four decode branches had no parity check at all.** `micro_p64`, `micro_zlib`,
`micro_p64_zlib` and `micro_nested` were pinned only by cross-fixture *equivalence* (Step 7), which cannot
catch an error common to both sides of a pair. Dumps generated for those four plus `micro_multiprec.mzXML`
and `micro_rtseconds.mzML` — the latter gaining its only parity check, since the seconds-side RT rule was
unit-tested only.

### Corrections found while executing Step 7

**C28 — the loader-parity dumps are grouped by ms level, so they cannot express document order.**
`oracle/dump_loader_parity.py` builds its `scans` list from `ms1_df` then `ms2_df`, so a dump holds all
MS1 entries followed by all MS2 entries — 229 then 687 on the Ewing file. Deriving the `ms1scan` chain
from a dump therefore assigns the **last** MS1 (913) to every MS2. Found by writing
`Ms1ScanDocumentOrderIT` that way and having it fail against a correct reader.

**Consequence for [Step 8](Tech_Step8.md):** the dumps are authoritative for *per-scan* facts — peak
counts, hex sums, digests, keyed by scan id — and **not** for anything order-dependent. Any assertion
about sequence must re-derive order from the file. `Ms1ScanDocumentOrderIT` now does exactly that with a
regex over the raw XML, which has the side benefit of sharing no code with the streaming walk, so
agreement is a genuine cross-check rather than one bug appearing twice.

**C31 — an MS2 scan may declare MULTIPLE precursors, and both readers took the LAST instead of the
first.** MassQL hard-indexes `[0]` at every level: `precursorList.precursor[0].selectedIonList
.selectedIon[0]` for mzML (`msql_fileloading.py:603`) and `spectrum["precursorMz"][0]` for mzXML (`:450`).
Both of our readers instead **overwrote** `precmz` and `charge` on every occurrence, so a multi-precursor
scan reported the last declared precursor.

**Why nothing caught it:** every fixture in the project is single-precursor — measured `max=1` on
`small.mzML`, `small.mzXML` and the Ewing file — so first-wins and last-wins are indistinguishable across
the whole suite. It was found by asking whether mzML *can* carry several precursors, not by a test.

**This is not a pathological input.** Multiplexed (MSX) acquisition deliberately co-fragments several
precursors into one MS2 scan, and DIA/SWATH uses wide isolation windows with no single selected ion.
MassQL keeps the first and discards the rest, so `micro_multiprec.{mzML,mzXML}` are **parity** fixtures —
unlike the C27(c) pair, which MassQL cannot load at all.

The mzML fixture carries **both** a second `<selectedIon>` inside the first `<precursor>` **and** a whole
second `<precursor>`, so it catches a reader that honours one nesting level but not the other. Worth
noting how the fix failed first time: the guard flag was added but never latched on `</selectedIon>`, so
the reader silently reverted to last-wins and the mzML half of the test still reported the decoy
(`1000.875`). A guard that is never set reads exactly like a guard that works.

**C30 — [Step 8](Tech_Step8.md) §3's instrument-attribute tolerances are too tight on all three columns,
and its reasoning was wrong.** It prescribed **1e-9** relative on `basePeakMz` and `basePeakIntensity`
because they are "selected values, not accumulations", and called **1e-6** generous for `totIonCurrent`.
Measured across all 916 Ewing scans: **4.850e-06**, **4.895e-06** and **4.724e-06** respectively — every
one exceeds its prescribed tolerance, so the gate would fail against a correct reader.

The premise was the error. The drift is not ours: the vendor wrote those attributes as decimal text
derived from **`float32`** values, so a *selected* value is no more exact than a *summed* one. That is
exactly why all three land at the same ~5e-6 magnitude rather than splitting into "tight" and "loose"
columns. **Resolution: 1e-5 on all three**, just above the measurement.

Two strengthenings came with it, now in `InstrumentAttributeCrossCheckIT`: assert the differences fall on
**both sides of zero** (a max-delta check cannot see a systematic one-sided bias), and assert the check is
**sharp** — the runner-up peak's m/z must be far enough from the base peak's that a wrong `argmax` could
not hide inside the tolerance.

**C29 — `make_micro_fixtures.py` never wrote `precursorCharge`, leaving mzXML's charge path untested.**
The MGF writer emits `CHARGE=2+` for scan 5, but the mzXML writer emitted no charge attribute at all, so
every MS2 scan came back `0` — which is *also* the absent-attribute default. A reader that ignored the
attribute entirely would have passed. Fixed by emitting `precursorCharge` when the scan table has one.
Scan 5 is the only such scan and it is absent from `micro_mzxml_results.json`, so no golden changed.

> **Process note.** Both of these are the same shape as C27(b): a rule with no test, or a test that could
> only pass. Neither was found by reading — C28 surfaced because a correct implementation failed a wrong
> assertion, and C29 because an assertion passed for the wrong reason and the expected value looked
> suspicious. Fixtures need the same "would this fail if the code were wrong?" check that assertions do.

### Corrections found while reviewing Step 7 as its implementer

<a id="c26"></a>
**C26 — CI verifies almost nothing; the fixtures are invisible to it.** `Fixtures.require` resolves to
`../massql` (deliberately outside this repo) and gates on `Assumptions.assumeTrue`, so a missing
fixture makes the test **skip**. `ci.yml` checks out only `massql-java`, so `../massql` never exists
there: [Step 6](Tech_Step6.md)'s oracle cross-check, [Step 8](Tech_Step8.md)'s parity assertions and
**`Ms1ScanDocumentOrderIT` — the assertion Step 7 exists for — all skip silently.** Surefire counts
skips inside `Tests run`, so the CI test-count guard cannot see it either. A green CI proved only that
the code compiles and the pure-unit tests pass.

**Resolution: commit the fixtures and goldens into the repo** (~29 MB + 7.8 MB), resolve `Fixtures`
in-repo, and **fail instead of skip** on a missing fixture. CI additionally asserts the skipped-test
count is **0**, so this regression cannot recur. Excluded: `oracle/.venv/` (contains an unrelated
3.6 MB plotly JSON). One caveat carried from
[`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md):13-14 — `DP00570_F02.mzxml` and
`DP00570_F02.mgf` are recorded there as *"gitignored, unstated licence"*, so redistribution must be
confirmed before those two are committed; `PlusRise.mgf` is already cleared under settled decision 5.

**C27 — four reader rules were wrong or missing, and one is a live Step 6 defect.**

**(a) The Step 7 §4 open item is answered — the scan is dropped.** pyteomics converts `msLevel=""` to
**`None`**, and `msql_fileloading.py:434,450` test `mslevel == 1` / `== 2`, so it matches neither branch
and the spectrum contributes **zero rows**. Not a default of 1, not a skip-with-diagnostic, not a
failure. Verified on `fixtures/edge/empty_msLevel_tag.mzXML`: 8 of its 10 scans are `msLevel=""`.

**(b) A zero-peak scan must NOT update the `ms1scan` chain — `MzmlReader` gets this wrong today.** Both
loaders `continue` on `len(spectrum["intensity array"]) == 0` (`:421` mzXML, `:559` mzML) **before**
`previous_ms1_scan` is ever assigned, so a zero-peak MS1 cannot become a link — a following MS2 links
to the MS1 *before* it. [`READER_RULES.md`](READER_RULES.md) states the document-order rule
unconditionally and `MzmlReader` has no such guard. **Latent, not benign:** there are zero zero-peak
scans in either mzXML fixture, so nothing in the suite can catch it. Readers still **yield** the scan
(consistent with MGF, where all 34,513 blocks including the 12,571 empty ones are yielded per C24(b)) —
only the linkage skips it. `peakCount` comes from `defaultArrayLength` / `peaksCount`, so the guard
costs no decode.

**Confirmed against MassQL's own loader, and no new fixture was needed** — `micro.mzML` has held the
case since Step 2 (`SCANS[3]` is an MS1 with `peaks=[]`, followed by the MS2 at scan 5) and nothing was
looking at it. The loader returns `scan 5 -> ms1scan 2`, skipping the empty MS1 at scan 4. Two further
findings from that: the same is true through the **nested** mzXML path (`{1:0, 3:2, 5:2}`), and
`make_micro_fixtures.py`'s own `expected()` carried the identical defect, so **`EXPECTED.md` had been
publishing `ms1scan=4` for scan 5** — with all three `ms1_*` wrongly null, since the empty scan has no
peaks to look up. Both fixed. `ZeroPeakMs1ChainTest` pins it and was verified to have teeth: with the
guard removed it reports "Got 4 — expected 2".

> **Why the golden could not catch this.** The micro `scaninfo` golden contains only scans 1 and 3 —
> scan 5 does not match `test_micro.massql`. So the one fixture that encoded the case had no golden
> coverage of it, and the one document stating the expectation (`EXPECTED.md`) was generated by the same
> wrong logic. A rule needs a test, not a second copy of the assumption.

**(c) Two rules pinned behaviour the oracle cannot produce.** `_determine_scan_polarity_mzXML`
(`:517-523`) does `spec["polarity"]` **unguarded**, and `:450` does
`spectrum["precursorMz"][0]["precursorMz"]` unguarded — both raise `KeyError`, so **no golden can
exist** for the absent case. **Confirmed by execution, not inferred from reading:** running MassQL's own
loader over the two new fixtures gives `KeyError: 'polarity'` and `KeyError: 'precursorMz'` respectively. What the `polarity = 0` initialiser actually covers is *present but
neither `+` nor `-`*, which is the only parity claim available. Our choices, recorded as **non-parity**:
absent `polarity` → `0`; absent `<precursorMz>` on an MS2 → `precmz = 0` (the existing "not recorded"
sentinel, consistent with mzML's `MS:1000744` absent → 0 rule, rather than a throw that would make
mzXML stricter than mzML for the same missing field). Both get explicit tests, and
`MzxmlPolarityTest` must assert the parity case and the non-parity case **separately**.

**(d) The mzXML rule table was missing three fields entirely** — no `scan`, `precmz` or `charge` row.
`scan` = `int(num)`; pyteomics returns `spectrum["id"]` as a **`str`** (`'1'`), which is the root cause
of C12. `precmz` = `precursorMz[0]`. **`charge` absent → `0`** (`:451`) — *unlike MGF, where absent is
`1`* per C6. Three formats, three different charge defaults.

**(e) Verified sound, so no change needed** — recorded to stop the next reader re-deriving them: the RT
literals and their bit-exactness (pyteomics `xml.py:136-141` does `minutes += hours*60.; minutes +=
seconds/60.`, reproducing 1.5 / 0.023 / 1.5 / 60 exactly, and accumulation order does not shift the
bits); the nesting requirement (`DP00570` `<scan>` depth **2**, `small.mzXML` depth **1**); absent
`compressionType` → uncompressed on all three real fixtures; and
`InstrumentAttributeCrossCheckIT`'s premise (`basePeakMz` / `basePeakIntensity` / `totIonCurrent` on
all 916 scans — and there are **11** `peaksCount="3"` scans, more than Step 7 §6 implies).

### Corrections found while reviewing Step 7

**C23 — the mzXML parser cannot be vendored either; hand-write it. Same defect as C21, one spec over.**
[Step 7](Tech_Step7.md) §2 listed "four modifications and only these four" for vendoring
`MzXMLFileParser`. Measured: it carries **13 msdk imports**, including 7 `datamodel` types
(`SimpleMsScan`, `SimpleIsolationInfo`, `IsolationInfo`, `MsScanType`, `MsSpectrumType`,
`PolarityType`, `RawDataFile`). And modification #2 — "replace the single Guava `Range` use" —
misidentifies the source: Guava arrives through **`SimpleMsScan`, which imports `Preconditions` and
`Range`**. Vendoring means writing our own scan holder, the surgery that killed Step 6 §3.

**Resolution: hand-write mzXML, vendor nothing new.** Cheap now because C21 already commits us to
hand-writing the mzML walk, and mzXML is the simpler format — zlib-or-none vs zlib + 6 Numpress
variants, one `precision` attribute vs per-array cvParams, one interleaved array vs two. Step 7's own
estimate (~250–350 LOC, "the simplest decode path in the contract") stands. `io/vendor/` stays at 13
files; mzXML reuses only `ByteBufferInputStream` / `FileMemoryMapper`.

**Hard requirement this makes ours:** `small.mzXML` is **flat** but the Ewing file **nests** MS2 inside
its parent MS1, so the walk must not assume `</scan>` ends the current spectrum. A flat-only reader
passes on `small.mzXML` and silently mis-associates every nested scan.

> **Process note.** C21 and C23 are the same finding in two specs, and C23 was foreseeable the moment
> C21 landed — both parsers sit on the same `datamodel`. When a correction invalidates an approach,
> check every spec that shares the approach, not just the one in hand. Also: my C21a propagation
> **truncated a sentence** in Step 7 §4 (left `"…not full-precision doubles. Use"` dangling). Scripted
> spec edits need a read-back, not just a grep for the newly inserted string.

### Corrections found while executing Step 6

**C21 — the mzML parser cannot be vendored as specced; only the DECODE layer can.**
[Step 6](Tech_Step6.md) §3 (itself already a C16 rewrite) said to vendor `MzMLParser` +
`MzMLFileImportMethod` + "the minimal `data/` value classes", replacing the `datamodel` types with
our own holders. Measured from source, that is not a local edit: `MzMLMsScan` (19.5 KB) carries **9**
`datamodel` imports plus Guava `Range`, slf4j, `SpectrumTypeDetectionAlgorithm`, `MsSpectrumUtil` and
`MzTolerance`; `MzMLFileImportMethod` **17**; `MzMLChromatogram` **11** (and `MzMLParser` imports
`Chromatogram`). ~30 files, ~160–190 KB, and "replace the datamodel types" means rewriting the scan
model. **Step 6 §3's own "stop and report if more than a local edit" clause fired.**

**Resolution: vendor the decode layer, hand-write the XML walk.** The decode layer is genuinely
clean — **13 files, 112 KB, and after transformation ZERO non-JDK imports** across the whole set
except our own `MassqlException`:

| Vendored | Modification |
|---|---|
| `MSNumpress.java` (44 KB) | **package declaration only** — byte-identical |
| `ByteBufferInputStream`, `FileMemoryMapper`, `MzMLArrayType`, `MzMLBitLength`, `MzMLCompressionType`, `MzMLCV`, `MzMLTags`, `MzMLCVParam` | package declaration only |
| `MzMLBinaryDataInfo` | dropped one `@Nonnull` (jsr305 banned) |
| `MzMLPeaksDecoder` | 3 swaps: Guava `LittleEndianDataInputStream` → our `LittleEndianDataInput`, commons-io `IOUtils.toByteArray` → `readAllBytes()`, `MSDKException` → `MassqlException` |
| `LittleEndianDataInput` | **not vendored — written for this project**, only the 4 methods the decoder calls |

Upstream commit `da2927a15c178b8ba9492d1e62571018bc70eecc`. Provenance headers and the EPL-1.0
election on every file; full list in [`VENDORED.md`](../VENDORED.md).

Three findings for whoever writes the XML walk:
- **`MzMLCV` does NOT define the bit-length or compression accessions** — those live on the
  `MzMLBitLength` / `MzMLCompressionType` **enums**. You will look in `MzMLCV` and not find them.
- Accessor naming is inconsistent in the vendored code: `MzMLBitLength.getValue()` but
  `MzMLArrayType.getAccession()`.
- `MzMLCV` references `MzMLCVParam` with no import (same package). Vendored rather than deleting the
  four unused `static final` fields it needs, so the constants table stays byte-identical.

**✅ And the vendored decoder is provably bit-correct for our contract.** `decodeToDouble` at 32-bit
does `data[i] = Float.intBitsToFloat(dis.readInt())` into a `double[]` — exactly `(double)(float)raw`,
the pyteomics-matching rule. Verified on raw bits with a real `small.mzML` m/z, plus a companion test
asserting the 32- and 64-bit decodes genuinely differ so the first cannot pass vacuously. **Do not
"fix" this to read 8 bytes.** It also handles zlib and all six Numpress variants.

**C21a — `BinaryDecoder` is dropped.** [Step 6](Tech_Step6.md) §5 specified a shared
base64/zlib/widening helper for both readers. There is nothing left to share: mzML's decode is the
vendored `MzMLPeaksDecoder`, and mzXML's differs in byte order (big-endian), array layout
(interleaved pairs) and has no Numpress at all. The only common ground is base64 + inflate, which is
a handful of JDK calls. Step 6 §5's own warning — *"a shared, pre-configured `ByteBuffer` is how a
60×-style silent bug gets introduced across two readers at once"* — argues against the abstraction.
→ [Step 7](Tech_Step7.md) decodes inline; `ByteBufferInputStream` is already vendored here so Step 7
reuses it.

<a id="c22"></a>
**C22 — execution is STREAMING, not whole-file. The store is never materialised for a whole file.**
Prompted by a direct question about 500 MB inputs. Calibrating from the fixtures (10.7–20.0 bytes of
file per loaded peak) against the store's 41 bytes/peak, a **500 MB input projects to 1.0–1.9 GB of
heap** — an OOM or GC-thrash lockup inside Cytoscape, not a graceful failure. **This is a gap in
SPIKE.md itself**: §9's constraints are entirely about bundle size and OSGi resolution; there is no
heap constraint and no target file size anywhere in the document.

Streaming works because **every v1 condition is a per-scan computation** (`i_norm` is `i/max(scan)`,
`tic` and `base_peak_*` are per-scan reductions, the m/z conditions are per-scan predicates, RT/scan/
charge/polarity are metadata), and **the precursor lookup needs exactly one retained scan** — by the
document-order rule, `ms1scan` is the most recent *preceding* MS1 scan. The rule we treated as a
fidelity burden is what makes streaming possible.

Retained state is **one MS1 scan + the current scan**. Measured: largest single scan across all
fixtures is **33,335 peaks → 2.6 MB**; a pathological 1,000,000-peak scan would be 78 MB. The mapped
region is **off-heap**, so the file costs address space, not heap.

**Step 5 needs no code change.** At 2.6 MB retained the 41 B/peak layout is irrelevant, so no column
trimming is required. `SpectrumTable`, `Reductions`, `RowMask`, `mzWindow` and their 44 tests survive
**unchanged** — only their *lifetime* changes, from one table per file to one table per scan, and
every invariant still holds for a single-scan table. Results stay correctly ordered for free:
`PlusRise.mgf`'s 34,513 `SCANS=` values are strictly ascending, so document order is scan order.

### Corrections found while executing Step 5

**C20 — `precmz` / `ms1scan` / `charge` are per-SCAN metadata and live on `ScanIndex`.**
[Step 5](Tech_Step5.md)'s layout list named only `mz`/`i`/`iNorm`/`iTicNorm`/`scan`/`rt`/
`polarity`, omitting the three columns [Step 10](Tech_Step10.md) needs. Verified against the
loader: MassQL's `ms2_df` carries them, and **each has exactly one distinct value per scan** —
they are flattened per-peak only because pandas is a flat frame. In Java they belong on the
scan index, which is semantically right and much smaller (a 20,000-peak MS1 scan would
otherwise hold 20,000 copies of its retention time). They carry MassQL's raw **0 sentinel**;
the 0-to-null conversion stays in [Step 10](Tech_Step10.md). Note `ms1_df` has no such columns
at all, so they are 0 throughout on an MS1 table.

Also confirmed while building: **`rt` really does need storing twice.** The per-peak column is
`float` per SPIKE.md §4, but the golden `rt = 0.011218333333333334` does not survive a float
round-trip, so `ScanIndex.rtOf` is an exact `double` and is the value that reaches the result
JSON. `ScanIndexTest` asserts the bit-exactness *and* that the value genuinely fails a float
round-trip, so the requirement is pinned rather than remembered.

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

<a id="c18"></a>
**C18 — `Comparator.NONE` is unreachable and was removed.** Step 4 §3 required a `NONE`
value "distinct from `EQ`". Verified against the corpus: every in-scope qualifier carries
`=`, `>` or `<`; the only comparator-less qualifiers are the out-of-scope ones
(`INTENSITYMATCHREFERENCE`, `EXCLUDED`, `CARDINALITY`, `MASSDEFECT`). So SPIKE.md §3's
"a missing comparator defaults to greater-than" refers to an **absent qualifier** — the
implicit `> 0` on an unqualified intensity column ([Step 9](Tech_Step9.md) §3) — not to a
qualifier that parsed without one. `Comparator` is `{EQ, GT, LT}`.

Affected specs, **edited only in the C35 round rather than here**: [Step 4](Tech_Step4.md) §3 (the
`Comparator` sketch, the Known-traps entry warning against normalizing a state that cannot exist, and the
`AstShapeTest` row that still *required* `NONE` to survive) and [Step 9](Tech_Step9.md) §3.

> ⚠ **This correction is also the harness's own worst fallout failure, and it is recorded here deliberately.**
> C18 named Step 9 §3 as affected and **nobody edited Step 9**, so five steps later the identical finding was
> rediscovered from scratch and written down again as **C35(a)** — now retired back to this entry. Two Step 4
> statements requiring `NONE` also survived until that round.
>
> The protocol — *"a Correction is not done until the affected specs are edited"* — was correct and was simply
> not carried out, which is the failure mode a written rule cannot prevent. `make spec-audit` check 3 therefore
> asserts mechanically that **every correction whose body links `Tech_StepX.md` is referenced in that file**.
> This entry is the check's reason for existing, and the case it is validated against.
>
> Note the shape, since it recurs: the *code* was right the whole time. What drifted was the spec, and a spec
> that contradicts working code is worse than a silent one — it argues an implementer into breaking something.
> `AstShapeTest.comparatorHasExactlyThreeConstantsAndNoNONE` now pins the enum's arity so a reintroduction
> fails at the AST rather than downstream.

**C19 — three constructs the specs never mentioned, all now rejected by name.** Found by
reading [`msql.ebnf`](oracle/msql.ebnf): **`ANY`** (`wildcard: "ANY"`, so `MS2PROD=ANY` is legal MassQL);
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
attribute. Full analysis in [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md).
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

<a id="c15"></a>
**C15 — ⛔ RETIRED, superseded by [C40](#c40).** It recorded that the `scaninfo(MS1DATA)` output shape was
**9 keys, not the 4** SPIKE.md §3 specified, and left an **open decision** for Step 10: emit the reference's 9,
SPIKE.md's 4, or 9 with real base peaks.

**The decision is made and the premise was wrong: the answer is 12, and there is only ever one shape.**
[cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26) — which SPIKE.md §3 cites as its own
source — defines a **uniform 12-key union** discriminated by `mslevel`, so none of the three candidates was
right. C15's measurement was accurate; what it measured was two separate defects in the wrapper. See
[C40](#c40) for the resolution, and [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) for the contract.

Every existing citation of C15 still resolves by landing here and following the link; no inbound reference was
rewritten (the [C35(a) → C18](#c18) precedent).

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

### Terminology — always name the layer (Correction C25)

Three distinct artifacts. **Never write "the CLI" when the SDK is meant, and never use "CLI" as
shorthand for "reference implementation".** An unqualified claim about output or streams reads as an
**SDK** claim, because that is the layer consumers code against.

| Label | Artifact | Streams |
|---|---|---|
| **reference implementation** | `../massql/massql_query.py` — the Python oracle. Generates goldens; **never ships** | JSON → stdout, by its own deliberate choice (`contextlib.redirect_stdout(sys.stderr)` at `:141`). We inherit the diff contract, not the convention |
| **Java CLI** | `cli.Main` ([Step 11](Tech_Step11.md)) — a thin wrapper that mirrors the reference's interface so [Step 12](Tech_Step12.md) can diff | JSON → stdout **or** `--output FILE`; diagnostics → stderr |
| **SDK** | `Massql.parse/execute/run` — the programmatic API the Cytoscape app codes against | **Neither.** Returns objects; `DEPENDENCY_POLICY.md` constraint 2 says it logs nothing at all |

Two conventions are in play and they govern different layers. The **Unix filter convention** (stdout =
the program's data, stderr = diagnostics) governs the Java CLI — it is what makes `| jq` work.
**12-factor factor XI** (logs → stdout) governs long-running services, whose real output leaves over
HTTP; it does not govern a batch filter. When citing a step number, say which layer it governs.

### Where a discovery goes — the fallout protocol

Every step turns up things its spec did not predict. Without a declared destination those findings live only
in whoever's head found them. **Three destinations, and a finding usually needs more than one:**

| Kind of finding | Goes to | Example |
|---|---|---|
| **A spec is wrong, or a later step's assumption breaks** | A numbered **Correction in this file**, *and* an edit to every affected step spec at the point of use | C13: Pair B cannot join on `scan` → INDEX + Tech_Step12 §2 rewritten |
| **A fact about a fixture** — provenance, what was verified, measured counts | [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) | the Ewing file's 11 `peaksCount="3"` scans and their instrument attributes |
| **A fact about the oracle** — pin, environment, golden provenance, reader-source verification | [`PINNED.md`](oracle/PINNED.md) and [`NOTES_fileloading.md`](oracle/NOTES_fileloading.md) | the corpus is 46 files, not 47 |

Rules:

1. **A Correction is not done until the affected specs are edited.** The INDEX entry records *what* changed;
   the step spec is where an engineer will actually read it. Adding one without the other is the failure mode
   this protocol exists to prevent — the engineer building Step 12 will not think to re-read Step 2's notes.
2. **Reference the Correction by its label** (`Correction C13`) in the spec edit, so the two stay findable
   from each other.
2b. ⛔ **Every Correction from C38 onward MUST carry a `Fallout:` line. The build enforces it.**

   ```
   **C39 — one-line statement of what was wrong.**

   **Fallout:** Tech_Step5.md, Tech_Step10.md
   ```

   or, when genuinely nothing needs editing:

   ```
   **Fallout:** none -- records a measurement; changes no spec.
   ```

   Then edit each declared file and cite `C39` in it. `make spec-audit` check 3 fails the build if the line
   is missing **or** if a declared file never mentions the correction. Both halves matter: the second catches
   the C18 failure, and the first stops you escaping the check by declaring nothing.

   **Why C38 and not C1 — this is a ratchet, deliberately.** Before the ratchet, obligation was *inferred*
   from `Tech_StepX.md` links in a correction's body. That protects only corrections that happen to link a
   step, and **12 of the first 37 link none** — including [C22](#c22), the largest correction in the project,
   which reshaped Steps 5–11 and yet carried **zero** enforced obligations. Those 12 were audited by hand and
   are all genuinely propagated (C22 by prose rather than links), so the hole is prospective rather than a
   live defect. Retrofitting them would mean **inventing affected-sets for roughly 30 entries**, producing a
   ledger that reads as authoritative and is guesswork — worse than one whose gap is known and written down.
   So the old entries keep link inference, and everything new declares.

   **The declaration is authoritative, and that is a deliberate trade.** Only the author can tell a fallout
   claim (*"Step 5's attribution is backwards"*) from a background pointer (*"full analysis in Step 1 §3a"*) —
   they are identical in shape, which is why inference over-fires. Writing `Fallout: none` on a correction
   that plainly changes a spec is therefore a **visible false statement in the ledger** rather than a silent
   omission. That is the same trade `VendoredProvenanceTest` makes, where a file declares itself
   *"Not vendored"* instead of the test keeping a filename allowlist: put the claim where a reviewer reads it.
3. **When a step completes, audit the propagation** rather than trusting recall: list the Corrections that
   step produced and confirm each is referenced by every spec it affects. Note that
   [`CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md) scope grew during Step 2 — it is now fixture provenance for the whole spike, not
   just the msconvert record its Step 2 deliverable row described.
4. **Do not silently fix a spec.** If an implementation deviates from what the spec says, either the spec was
   wrong (Correction) or the implementation is (fix it). Quietly diverging leaves the spec lying to the next
   reader.
5. **Rule 3 is now enforced by the build, because rule 3 alone was not enough.** Rules 1–3 are discipline, and
   discipline failed — [C18](#c18) named Step 9 §3, Step 9 was never edited, and five steps later the identical
   finding was rediscovered and filed again as C35(a). Nothing could have caught it: prose has no test suite.

   **`make spec-audit`** (in `make verify`) now fails the build on five drifts, **every one of which this
   project actually produced** — none is hypothetical:

   | Check | Fails when | The case it was written for |
   |---|---|---|
   | 1 | a fixture, golden, query or dump on disk is named by no spec — or a spec names one that no longer exists | `micro_zeroint.mgf` and `micro_ms1var.mzML` were created, dumped and used by passing tests while Step 2 and [`FIXTURES.md`](FIXTURES.md) said nothing about either |
   | 2 | a **stated count** disagrees with the filesystem or with `ReaderParityIT.FIXTURES_WITH_DUMPS` | three documents held **15**, **14** and the true **16** simultaneously |
   | 3 | a C38+ correction has no `Fallout:` line, or any declared/linked spec never cites the correction | **C18 → C35(a)**: the same finding recorded twice, five steps apart, because the named spec was never edited |
   | 4 | a **completed** step names a test class that neither exists nor carries a `→` redirect | **C38**: Step 9's table named 10 classes that were never written, and under that cover `MS2PREC`, `CHARGE` and `MS2NL` had no execution test while the exit criterion claimed all ten conditions did |
   | 5 | a **completed** step names a `docs/*.md` review artifact that does not exist | **C38**: [`VENDORED.md`](../VENDORED.md) was a ticked Step 6 deliverable, a Step 13 review input, and the target of eleven vendored source headers — and was absent for three steps |

   Checks 4 and 5 read completion from each spec's own **Done-when checkboxes**, so Steps 10–13 are exempt
   until they land and the scope widens by itself rather than needing a hardcoded list maintained by hand.

   One design note worth keeping, because the wrong version is seductive: deriving a correction's affected set
   from *the specs that already cite it* passes **by construction** — a correction nobody propagated gets an
   empty set and therefore no obligation, defining the target failure out of existence. That is C26's mistake
   in new clothing. Obligation must come from something independent of the citation: a body link (legacy) or an
   explicit declaration (C38+, rule 2b).

   A pre-C38 link that is a background pointer rather than a fallout claim goes in the script's
   `POINTERS_NOT_FALLOUT` list **with a written reason** — currently three, argued individually.

   **Widening a pattern until nothing fails is not an option, and this script has twice been the thing at
   fault.** Check 2 was silently *vacuous* when first written: its regex missed `**16 dumps.**` (punctuation
   inside the bold), on the very file whose contradictory counts motivated it. Check 3's citation test was a
   **prefix match** (`\bC1[a-z(]?` matched the `C1` inside `C18`), so every single-digit correction counted as
   cited in specs that never mention it. Neither was hiding a real gap when found — verified both ways — but
   both would have passed one. Every check here is demonstrated to fail on injected drift before it is
   trusted; the procedure is in the script's `DEMONSTRATING FAILURE` block. **Re-run it after any edit to a
   pattern.**

### Two rules that keep 13 documents consistent

1. **One rule, one home.** A rule is stated in full in the spec that *implements* it, and cross-referenced
   (never restated) elsewhere. If you need a rule's exact wording, follow the cross-reference rather than
   trusting a paraphrase.
2. **Scope has no gaps.** Every "out of scope" item names the step that owns it. If you find something in
   neither an in-scope nor an out-of-scope list, that is a spec bug — report it rather than improvising.

Shared vocabulary: **native column** = produced by MassQL's own `scaninfo`; **computed column** = one of the 5
the SDK must derive itself (Step 10); **golden** = a Python-generated reference output; **micro-fixture** = a
hand-written 3–5 scan file with hand-computable values (Step 2).
