# Tech Step 13 — Harden, document, hand off

> **⛔ ENDS AT THE REVIEW GATE.** When this step is done, **stop**. No downstream or consumer work until
> `massql-java` has been manually reviewed against the goldens, the test suite and the README.

## Goal

Make the repo reviewable rather than merely working: a reviewer who has never seen it can run one command, read
one table, and judge whether the spike succeeded.

## Prerequisites

| Step | Why |
|---|---|
| [Step 12](Tech_Step12.md) | Its gate must be green, and `docs/harness/DIFFERENTIAL_REPORT.md` is the input to the `make verify` table. It sits beside `PARITY_REPORT.md` in `docs/harness/`, which is where `Makefile`:83's `report` target looks; written to `docs/` as this row once said, that target would print "(not yet written)" forever (C46). |

## Context

[`SPIKE.md`](SPIKE.md) §7 Step 3 frames this precisely: *"What makes the repo reviewable rather than just working."* The
engineering is done by now; this step exists because a spike that cannot be independently validated has not
actually answered its questions. The review gate is the deliverable, and the artifacts below are what gets
reviewed.

Governing sections — all in this repo under `docs/harness/` since [C41](Tech_Step_INDEX.md#c41):
[`SPIKE.md`](SPIKE.md) §7 Step 3, §8 (honest framing), §11 (the eight questions), §6d.
⚠ §9 is the dependency-constraint list; its rules live in `DEPENDENCY_POLICY.md` and are enforced there.

## Scope

**In scope**
- README, with the feature matrix and every published contract.
- `make verify` and its per-format pass/fail table.
- The checked-in dependency audit.
- JaCoCo coverage reporting.
- Written answers to all eight [`SPIKE.md`](SPIKE.md) §11 questions.
- CI.

**Out of scope**
- **Anything a consumer application would own** — its UI, its packaging, its integration with this SDK.
- New features or semantics. If something is missing, it belongs to its owning step.
- **Anything about how a consumer packages this jar.** See §5: this SDK does not know or assert what embeds it.

## Deliverables

| Path | Content |
|---|---|
| `README.md` | The primary review artifact. ✅ **Already written at Step 11** (C45), alongside `docs/SDK.md` and `docs/CLI.md` — the consumer-facing docs were split by artifact when the CLI arrived, since that is when the SDK's and the CLI's stream contracts first had to be stated apart (C25). What remains here is the **review** framing: the SPIKE.md §11 answers, the differential table, and the honest-limitations section |
| `Makefile` | ✅ **Already filled in at Step 9** (the Step 3 stub grew a full target set, and both workflows now call it). This step adds only the **differential table** to `verify` |
| `dependency-audit.txt` | Final SDK runtime closure + measured total (updated from Step 6) |
| `docs/FEATURE_MATRIX.md` | Parses / executes / rejects. **Generated from `UnsupportedConstructs.all()` and committed**, with `FeatureMatrixTest` guarding the drift — the shape `UsageDocSyncTest` already uses for `docs/CLI.md`. Generating it at build time instead would make its test tautological, and a reviewer reading the repo could not see the matrix at all |
| `docs/SPIKE_ANSWERS.md` | The eight §11 questions, answered |
| `.github/workflows/ci.yml` | **Already exists** (Step 3 §8) and needs no change: its one gate is `make verify`, which now also renders the differential table |
| `build/reports/jacoco/` | Coverage report (generated, not committed) |

## Specification

### 1. README

The reviewer's entry point. Required content:

- **The pinned MassQL SHA** — `dad2a28c01e6e5132240270fc6700fbae29f1652` (tag `2026.03.14`). State plainly that
  this SHA *is* the definition of "MassQL-compliant" here.
- **The supported-feature matrix** — what parses, what executes, what rejects. Link `docs/FEATURE_MATRIX.md`.
- **The 12-key result contract** — **one shape** for both MS1DATA and MS2DATA, discriminated by `mslevel`, with
  no key ever absent. Point at **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)**, the single definition; do not
  restate the key set in the README. ⚠ This bullet read *"and the 4-key MS1DATA shape, with the absent-vs-null
  distinction called out … point at `RESULT_CONTRACT.md`"* — both the second shape and that document are
  gone under Correction **C40**. (Path deliberately written without its `docs/` prefix so `spec-audit` check 5
  does not read a quoted, retired name as a live reference once this step completes.)
- **The three per-format population rules** ([Step 10](Tech_Step10.md) §6 table).
- **Known deviations** — see §2. Do not bury these.
- **The EPL-1.0 election** and the vendored-code provenance ([Step 6](Tech_Step6.md) §3 — Step 6 owns all vendoring; Step 7 vendors nothing per C23).
- **CLI usage** and how to run `make verify`. Show **both output modes** and say when each fits — stdout
  for composing with other tools, `--output FILE` for a durable artifact a downstream consumer can pick up
  (Correction C25b):

  ```bash
  # default: JSON on stdout, diagnostics on stderr -- composable
  java -jar massql-java-cli-<version>.jar spectra.mzML query.massql | jq '.[0]'

  # --output: JSON to a file, written atomically; stdout stays empty
  java -jar massql-java-cli-<version>.jar spectra.mzML query.massql --output out.json
  ```

  ⚠ **The uber-jar, never `-cp massql-java.jar`.** The thin SDK jar carries neither `antlr4-runtime` nor
  `javolution`, so that form fails at the first parse (C43). `make cli` builds the uber-jar, which is
  correct by construction.

  The query has **three sources** — a file, `-` for stdin, or `-q`/`--query` inline — and all three produce
  identical bytes. Keep the README to the two *output* modes above and link
  [`docs/CLI.md`](../CLI.md) for the query sources and its runnable examples, rather than maintaining a
  second copy here that will drift.

  While documenting this, keep the three layers distinct (Correction C25a, *Terminology* in
  [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)): the stream rules above govern the **Java CLI**. The **SDK**
  writes to no stream at all and returns `List<ScanInfoResult>`, so a reviewer must not read the CLI's stdout
  contract as an SDK behaviour.
- **Honest framing**, per [`SPIKE.md`](SPIKE.md) §8: "full MassQL parity" means bug-for-bug agreement with *one commit* of a
  tool whose own docs advertise functions that don't exist (`scanmaxmz`, `scanrun`) while `scanmz` and `OTHERSCAN`
  exist undocumented. **Call it a `scaninfo` subset.** Overclaiming here is the one documentation failure that
  would actively mislead a consumer.

### 2. Known deviations — state them, don't bury them

**Six** deliberate divergences a user could otherwise discover the hard way (three were foreseen; two came out
of Steps 7–8; the sixth is an output-format difference the CLI documents):

1. **`ms1scan` is inferred by document order, not read from the file.** MassQL ignores `spectrumRef` (mzML) and
   `precursorScanNum` (mzXML); we reproduce that to match its answers. **Consequence:** on interleaved
   acquisition, multiple MS1 scans per cycle, or a precursor reference pointing further back than the immediately
   preceding MS1 scan, our `ms1scan` disagrees with the file's declared linkage. For simple DDA they coincide.
   This is bug-for-bug fidelity, chosen deliberately. ([Step 6](Tech_Step6.md) §4, proved in
   [Step 7](Tech_Step7.md).)
2. **`=` means `>=` for intensity comparisons** — MassQL's historical semantics, reproduced.
   ([Step 9](Tech_Step9.md) §3.)
3. **`i_norm` and `i_norm_ms1` are dropped** from the output as structurally constant.
   ([Step 10](Tech_Step10.md).)
4. **`tic` is NOT bit-identical to MassQL's, and ours is the more accurate value** (Correction C34). MassQL's
   intensity column is `float32` and `tic` is a pandas `groupby.sum()` over it; we accumulate in float64.
   Measured divergence on `small.mzML`: up to **3.7e-08** relative, e.g. golden `586278.875` vs our
   `586278.8533592224`. Compared at 1e-6. **State plainly that the error is in the reference, not in us** — a
   reviewer seeing a tolerance will otherwise assume the opposite. Every *other* intensity column
   (`base_peak_i`, `ms1_i`, `ms1_base_peak_i`) **is** bit-identical, because they are selections rather than
   sums.
5. **`POLARITY` on an MGF filters a constant, not the data** (Correction C34b). MassQL hardcodes MGF polarity to
   **1** (C33 — our reader returned `0` until the Step 8 gate caught it), so `POLARITY=Positive` matches every
   MGF scan and `POLARITY=Negative` matches none, whatever the spectra are. There is no way to tell from the
   output, which is precisely why it belongs in this list.

6. **The JSON is compact; the reference emits `indent=2`.** Values are identical and round-trip exactly, but
   the two outputs are **not byte-comparable** — which matters the moment a reviewer tries `diff` instead of
   comparing parsed values. Already stated in [`docs/CLI.md`](../CLI.md); it belongs here because a user who
   reaches for `diff` first will otherwise conclude the results disagree.

Plus any tolerance adopted in [Step 8](Tech_Step8.md) §1 or [Step 12](Tech_Step12.md) §1 — if a `tic`
accumulation-order tolerance was accepted, it is a deviation and belongs here.

### 3. `make verify`

> ✅ **The Makefile was filled in early, at Step 9** — ad-hoc build-tool invocations had already begun to
> diverge from what CI ran, so the convention was pulled forward: **the build tool is never invoked directly;
> the Makefile is the only entry point**, and both workflows call its targets. What remains for this step is
> the **differential table** below, not the plumbing.
>
> Already in place: `build`, `test`, `it` (integration only, skipping the unit suite), `verify`, `audit`,
> **`spec-audit`**, `lint`/`lint-fix`, `coverage`, `cli`, `fixtures`, `report`, `clean`,
> `set-version-sdk`/`set-version-cli`, `publish-sdk`/`publish-cli`, `test-one`/`it-one`, and a `help` default
> goal.
>
> ⚠ `skipcheck` (C26's zero-skip guard) **no longer exists** — its hand-maintained test-count floor rotted on
> every test added, and it was replaced by the 90% coverage gate inside `check` (C43).

```
make verify   →  gradle check (unit + integration + coverage gate + lint + banned deps)
              +  make audit       (SDK runtime closure + size budget)
              +  make spec-audit  (the specs still describe the code)
              +  the three differential comparisons        ← THIS STEP
              +  prints a per-format pass/fail table       ← THIS STEP
```

**`make spec-audit` (added after Step 9) is the harness auditing itself.** The three drifts it fails on are
the three this project actually produced: a fixture or golden on disk that no spec names; a stated count that
no longer matches the filesystem (three documents once held **15**, **14** and the true **16**); and a
correction naming a step that never cites it back — the check that would have caught **C18 → C35(a)**, where
the same finding was recorded twice, five steps apart, because the named spec was never edited.

Its own header records that check 2 was **silently vacuous on first write** (its regex missed
`**16 dumps.**`, punctuation inside the bold) and was only caught by injecting drift and getting no failure.
Every check is verified to fail before being trusted, which is C26's lesson applied to the guard rather than
to the tests.

The table is the review artifact. [`SPIKE.md`](SPIKE.md) §6d: *"The reviewer should not have to reconstruct how to validate
the thing."*

⛔ **It is rendered from the JUnit XML that `gradle check` has just written — it re-runs nothing.**
[Step 12](Tech_Step12.md)'s `DifferentialIT` asserts **16 fixture/query pairs across 5 fixtures and 3
formats**; the table is a *view* of those results, grouped by fixture for display. That is why it costs no
time and why it cannot disagree with the tests. There is deliberately **no committed XML snapshot**: if
`DifferentialIT` gains or loses a pair, the table follows on the next run with nothing to regenerate.

⚠ **The script must fail on an empty or short parse.** A renamed test, a moved results directory or a
changed XML layout must turn `make verify` **red** rather than print a table with rows quietly missing —
a confident, incomplete table is worse than a broken one. Assert the pair count it found, and fail if it
is zero.

Shape (five display rows over the 16 pairs):

```
FORMAT   FIXTURE              ROWS      SCAN  PRECMZ  MS1SCAN  RT   CHARGE  TIC  MSLEVEL  BASE_PEAK  MS1_*
mzML     small.mzML           6/6       ok    ok      ok       ok   ok      ok   ok       ok         ok
MGF      PlusRise.mgf         664/664   ok    ok      n/a      ok   ok      ok   ok       ok         n/a
mzXML    small.mzXML          6/6       ok    ok      ok       ok   ok      ok   ok       ok         ok
mzXML    DP00570_F02.mzxml    …/…       ok    ok      ok       ok   ok      ok   ok       ok         ok
MGF      DP00570_F02.mgf      …/…       ok    ok      n/a      ok   ok      ok   ok       ok         n/a
```

`n/a` where the format cannot populate a column — distinct from `ok` and from `FAIL`, so the population rules are
visible in the table itself. **Non-zero exit on any FAIL.** Skipped fixtures print `SKIP` with the reason, and a
run where nothing executed must fail rather than print an empty table.

✅ `make test` (unit only, fast) and `make audit` (SDK closure + size) already exist, added when the
Makefile was filled in at Step 9 — along with `it`, `spec-audit`, `fixtures`, `report`, `build`,
`lint`/`lint-fix`, `coverage`, `cli`, `set-version-sdk`/`set-version-cli`, `publish-sdk`/`publish-cli` and
`test-one`/`it-one`. Nothing to add here but the differential table.

### 4. Dependency audit

The final SDK runtime closure after all exclusions, plus the measured total byte size, committed as
`dependency-audit.txt`. This is the artifact that answers *"did dependency complexity stay bounded?"*

**Measured in Step 3: 785,599 B = 0.749 MB, 49.9% of budget** — two artifacts, `javolution-core-java-msftbx`
(459,292) + `antlr4-runtime` (326,307). Confirm this is unchanged after both readers are vendored.

The tree must show **no MSDK at all**, and no `dsiutils`, `fastutil`, `guava`, `jsr305`, `checker-qual`,
`slf4j`, `logback`, `jaxb-*`, `cdk-*` or `commons-*`. All are rejected by `checkBannedDependencies`
(Correction C16), so this is a re-confirmation rather than a fresh check.

`scripts/dependency-audit.sh` already exits non-zero on a violation or budget breach, and CI runs it
([Step 3](Tech_Step3.md) §8) — so this row is about reading the committed artifact, not building the check.

### 5. The SDK knows nothing about what embeds it

⛔ **`massql-java` processes MassQL. That is its entire remit.** It carries no packaging metadata for any
consumer, makes no assertion about the environment it will run in, and names no downstream project. How a
consuming application packages this jar is that application's business, in its own repository.

What remains is enforced for reasons that stand on their own, and none of them names a host:

| Rule | Why, without reference to any host |
|---|---|
| No `ServiceLoader` / `Class.forName` | Provider lookup fails wherever the thread-context classloader cannot see the caller's classes — a shaded jar, a plugin system, a module path. `DEPENDENCY_POLICY.md` constraint 1 |
| No logging framework | An embedded library that brings its own logger conflicts with whatever the host already uses. Constraint 2 |
| Closure **< 1.5 MB** | A budget, enforced by `scripts/dependency-audit.sh`. Constraint 6 |
| JDK 17 bytecode | A compatibility target, set by the toolchain rather than checked afterwards. Constraint 7 |

⚠ Do not reintroduce an embeddability check here on the grounds that it is cheap. A library that asserts
facts about its future container has taken on a dependency on that container, in documentation if not in
bytecode — and the next person reads those assertions as requirements.

### 6. Coverage, CI, and the §11 answers

**JaCoCo** — *"not as a target to game, but so the reviewer can see which of the §3 rules are actually
exercised."* A **90% instruction gate** is enforced inside `check` (`build.gradle`, `jacocoTestCoverageVerification`),
which is what replaced the rotting hand-maintained test-count floor (C43): a floor had to be edited on every
test added, while a coverage ratio maintains itself.

⚠ **The percentage is a floor, not the review artifact.** A number cannot tell a reviewer *which* rules are
pinned. So the README also maps each [`SPIKE.md`](SPIKE.md) §3 claim to the test that holds it — that table is what a
reviewer actually reads, and it is the reason the gate does not become a target to write tests against.

§3 is the **result contract** rather than a numbered list, so "each rule" means: one row per claim in its
column-source table — the **7** columns MassQL produces natively, the **2** computed base-peak columns, the
**3** computed `ms1_*` columns, and the **2** deliberately dropped (`i_norm`, `i_norm_ms1`) — plus the 12-key
union shape and the two m/z-window semantics (C37).

**CI — already built in [Step 3](Tech_Step3.md) §8**, which owns `ci.yml` and `release.yml`. Do not
re-specify them here. This step only *adds* to the existing `ci.yml`:

- the differential table (§3). ⚠ **Not as a new CI step** — `make verify` runs it, and CI's one gate is
  `make verify`. A separate step would create a second definition of what CI checks, which is exactly the
  drift the Makefile-only rule exists to prevent;
- confirmation that **no test reaches the network** (`DEPENDENCY_POLICY.md` constraint 8) — the 46 parse
  goldens are checked-in files under `src/test/resources/reference_parses/`, never live calls to
  `massql.gnps2.org/parse`.

Already in place from Step 3 and not to be duplicated: JDK 17, **`make verify`** (which runs `gradle check`,
covering the `integrationTest` suite — every gate lives in an `*IT.java`), the dependency audit as a gate, and
the guard that stops a skip-everything run from passing green — introduced by Correction C26 and since
**replaced by the 90% coverage gate** (C43), which needs no maintained count, plus a `fetch-fixtures.sh` step
with an `actions/cache`
for the two gitignored Ewing files.

**`docs/SPIKE_ANSWERS.md` is the canonical home for the eight answers**, each citing the evidence behind it.
The README carries a **one-line verdict per question and a link** — the same split already used for
`docs/SDK.md` and the published javadoc jar, and for the same reason: one place to maintain, and a README
that stays readable. §1's "the §11 answers" means those verdicts, not a second full copy.

Answer all eight plainly. Four are already settled and just need carrying forward:

| # | Question | Source |
|---|---|---|
| 1 | Bit-identical decoded intensities vs Python? If not, what tolerance is the contract? | [Step 8](Tech_Step8.md) [`PARITY_REPORT.md`](PARITY_REPORT.md) |
| 2 | Same rows on `small.mzML` and `small.mzXML`? | [Step 12](Tech_Step12.md) layer 3 Pair A |
| 3 | Closure under ~1.5 MB? Both javolution forks in the released pom? | **Answered:** **785,599 B = 0.749 MB, 49.9% of budget** — the measured figure from §4 and `dependency-audit.txt`, two artifacts only; and **no**, the plain fork is commented out (Correction C2) |
| 4 | Is MSDK's licence shippable? | **Answered:** dual LGPL-2.1 / EPL-1.0; **we elect EPL-1.0** (Correction C3). Not blocking |
| 5 | Parser: ANTLR, hand-written, or remote `/parse`? | **Answered:** ANTLR 4.13.2 embedded, 326,307 B |
| 6 | Measured LOC — does 1,200–1,800 hold? | Count production vs test LOC here |
| 7 | Where does `massql-java` live, and how is it published? | **Answered:** GitHub → the NRNB-hosted Nexus; `~/.m2` during the spike |
| 8 | Wall-clock and peak heap vs the pandas path? | [Step 12](Tech_Step12.md) §5 |

For Q6, report production and test LOC separately and say whether the estimate held. If it did not, say by how
much and where — that is useful calibration for anyone estimating similar work, and a quietly-missed estimate is the kind of
thing a review exists to surface.

### 7. The review gate

Stop here. The reviewer checks `massql-java` against the goldens, the test suite and the README before any
downstream work begins.

⚠ **No packaging trial is part of this step, or offered as a decision.** Whether this jar embeds cleanly in a
particular application is that application's question to answer, with its own build. Raising it here would put
a consumer's concern back into a library that has none — see §5.

## Known traps

- **Overclaiming parity in the README.** "MassQL-compatible" invites a consumer to assume features that reject. Say
  `scaninfo` subset, and publish the matrix.
- **Burying the `ms1scan` deviation** in a code comment. A user comparing our `ms1scan` against their file's
  `spectrumRef` will conclude we have a bug. §2.
- **Treating the coverage percentage as the review artifact.** The 90% gate is a floor that stops the suite
  rotting; it cannot tell a reviewer which rules are pinned, and a step written to raise the number rather
  than to pin a rule is a step backwards. The §3-claim→test map is the artifact. Both, not either.
- **A hand-maintained feature matrix** that drifts from `UnsupportedConstructs`. Generate it.
- **`make verify` exiting 0 when fixtures were skipped.** A green empty table is worse than a red one. Since
  Correction C26 a skip is structurally impossible — `Fixtures.require` fails — so the guard is now "assert
  **zero** skips", and a skip appearing at all means someone reintroduced an `assumeTrue` or `@Disabled`.
- **Adding a packaging or container check to this repo.** It does not belong here at all (§5), however cheap it
  looks. Each consumer owns its own packaging.
- **Starting downstream work "while the review is pending."** The gate exists to prevent exactly that.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `FeatureMatrixTest` | unit | The matrix in `docs/FEATURE_MATRIX.md` matches `UnsupportedConstructs` — every rejected construct listed, no listed construct silently supported. Keeps the published matrix honest. |
| `DifferentialTableIT` | IT | Guards the review artifact against a false green by testing **`scripts/differential-table.sh`**, not `make verify`. ⛔ It must not invoke `make verify`: `check` depends on `integrationTest`, so an IT that ran it would recurse forever. The test **synthesizes its own JUnit XML in a temp directory** — one all-passing run, one with a failed pair, one empty — and asserts exit 0, non-zero with `FAIL` in the output, and non-zero on the empty case respectively. Synthesized rather than committed, so there is no snapshot to rebuild when `DifferentialIT` changes. |

## Done when

- [x] `make verify` prints the per-format per-column table and exits 0 — **16 pairs, VERDICT: GREEN** — and exits
      non-zero on a broken differential and on **any** skipped test (C26). Proven by `DifferentialTableIT`, which
      injects a failed pair, a short parse and a missing results file.
- [x] README contains: the pinned SHA, the feature matrix link, **the one 12-key union shape** (C40 — there is
      no second shape; point at [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) rather than restating the keys), the
      population rules, **all known deviations**, the EPL-1.0 election, CLI usage, and the honest-framing
      paragraph.
- [x] `dependency-audit.txt` committed; **785,599 B = 0.749 MB, 49.9% of budget**, two artifacts; none of the
      forbidden artifacts present. Unchanged after both readers were vendored.
- [x] **No consumer or container references anywhere in the repository** (§5): no packaging metadata, no
      readiness check, and no named downstream project in any source file, build script or document. The
      dependency rules that remain are justified without naming a host.
- [x] JaCoCo report generates — **91.11% SDK / 94.46% CLI** against the 90% gate — and the README maps each
      §3 claim to the test that pins it. Every class named there was checked to exist.
- [x] CI green on push/PR with **zero skipped tests** (C26). ⚠ There is no longer a floor on the number
      executed — that was `skipcheck`, whose hand-maintained count rotted on every test added and which C43
      replaced with the 90% coverage gate. Note CI does make one network call — `fetch-fixtures.sh` for the
      two gitignored Ewing files — behind an `actions/cache`, so the flaky upstream is contacted once rather
      than per run.
- [x] `docs/SPIKE_ANSWERS.md` answers all eight §11 questions with evidence, and is canonical; the README carries
      one-line verdicts. Q6 reports **3,162 production lines against the 1,200–1,800 estimate — a 1.8–2.6× miss**,
      with where the extra went.
- [x] **Work stops.** Verified, not assumed.
      The repository names no downstream project and no container technology in any source set, build script or
      document. The only external addresses left are the Nexus URL this project publishes to and the issue that
      defines the 12-key result schema — addresses of things outside the repo, not claims about what consumes it.

## References

- [`SPIKE.md`](SPIKE.md) §7 Step 3 (this step), §8 (honest framing and the out-of-scope list), §11 (the eight
  questions), §6d (build wiring and `make verify`). §9's dependency constraints live in
  `DEPENDENCY_POLICY.md`
- Inputs: [`PARITY_REPORT.md`](PARITY_REPORT.md) ([Step 8](Tech_Step8.md)), `docs/harness/DIFFERENTIAL_REPORT.md`
  ([Step 12](Tech_Step12.md)), **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)** ([Step 10](Tech_Step10.md) — renamed from the
  never-written `RESULT_CONTRACT.md` by **C40**, which folded it into the one existing definition),
  [`SEMANTICS.md`](SEMANTICS.md) ([Step 9](Tech_Step9.md)), [`VENDORED.md`](../VENDORED.md) ([Step 6](Tech_Step6.md), owned by
  [Step 7](Tech_Step7.md)), [`READER_RULES.md`](READER_RULES.md) (Steps [6](Tech_Step6.md), [7](Tech_Step7.md))

  > ⚠ **Correction C38 — check each of these exists before relying on it as a review artifact.**
  > [`VENDORED.md`](../VENDORED.md) was listed here, recorded as a completed Step 6 deliverable, and referenced by all
  > eleven vendored source headers — and **did not exist**. It does now, asserted by
  > `VendoredProvenanceTest`. Nothing was verifying that a named artifact was actually on disk; a review
  > gate whose inputs may be absent is not a gate. `make spec-audit` check 1 covers fixtures and goldens,
  > check 4 covers test classes; **the review documents in this list are still checked by eye**, so check
  > them here rather than assuming a ticked exit criterion means the file is there.
- Corrections C1–C5 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) — C2, C3, C4 answer §11 questions directly
