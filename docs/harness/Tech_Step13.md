# Tech Step 13 — Harden, document, hand off

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

## Goal

Make the repo reviewable rather than merely working: a reviewer who has never seen it can run one command,
read the results, and judge whether the spike succeeded.

## Prerequisites

| Step | Why |
|---|---|
| [Step 12](Tech_Step12.md) | Its gate must be green — the differential is what the review reads. |

## Context

[`SPIKE.md`](SPIKE.md) §7 Step 3 frames this precisely: *"What makes the repo reviewable rather than just
working."* The engineering is done by now; this step exists because a spike that cannot be independently
validated has not actually answered its questions. The review gate is the deliverable.

Governing sections: [`SPIKE.md`](SPIKE.md) §7 Step 3, §8 (honest framing), §11 (the eight questions), §6d.

## Scope

**In scope**
- README, with every published contract a user needs.
- One command that runs the whole suite and the coverage gate.
- Written answers to all eight [`SPIKE.md`](SPIKE.md) §11 questions.
- JaCoCo coverage reporting.
- CI.

**Out of scope**
- **Anything a consumer application would own** — its UI, its packaging, its integration with this SDK.
- New features or semantics. If something is missing, it belongs to its owning step.
- **Anything about how a consumer packages this jar.** See §5.

## Deliverables

| Path | Content |
|---|---|
| `README.md` | The entry point: what the two artifacts are, how to build, the implementation basis, the licence election |
| `docs/SDK.md`, `docs/CLI.md` | Consumer-facing docs, **split by artifact** so the SDK's and the CLI's stream contracts cannot be read as one. `SDK.md` is obtain-and-build plus the known deviations; class-level documentation is the published `-javadoc.jar`, not prose |
| `Makefile` | `make integration-test` runs the whole suite and the coverage gate. The Makefile is the only entry point; Gradle is never invoked directly |
| [`SPIKE_ANSWERS.md`](SPIKE_ANSWERS.md) | The eight §11 questions, answered, each citing its evidence |
| [`dependency-audit.txt`](dependency-audit.txt) | The SDK runtime closure and its measured total, as recorded at the review gate |
| `.github/workflows/ci.yml` | Already exists (Step 3 §8); its one gate is `make integration-test` |
| `build/reports/jacoco/` | Coverage report (generated, not committed) |

## Specification

### 1. README

The reader's entry point. Required content:

- **The implementation basis** — the reference Python implementation at tag `2026.03.14`
  (`dad2a28c01e6e5132240270fc6700fbae29f1652`), and that this covers the **`scaninfo` subset**.
- **The two artifacts**, and that they are versioned independently.
- **How to build**, and that the Makefile is the only entry point.
- **The EPL-1.0 election** and the vendored-code provenance ([Step 6](Tech_Step6.md) §3 owns all vendoring).
- **Honest framing**, per [`SPIKE.md`](SPIKE.md) §8: "full MassQL parity" means bug-for-bug agreement with
  *one commit* of a tool whose own docs advertise functions that don't exist (`scanmaxmz`, `scanrun`) while
  `scanmz` and `OTHERSCAN` exist undocumented. **Call it a `scaninfo` subset.** Overclaiming is the one
  documentation failure that would actively mislead a consumer.

⚠ **Keep it short.** Anything a *user* does not need belongs in `docs/SDK.md`, `docs/CLI.md`, or here in the
harness. A README that doubles as a review report is one nobody reads.

### 2. Known deviations — state them, don't bury them

**Six** deliberate divergences a user could otherwise discover the hard way. They live in **`docs/SDK.md`**,
where someone integrating the library will meet them:

1. **`ms1scan` is inferred by document order, not read from the file.** MassQL ignores `spectrumRef` (mzML)
   and `precursorScanNum` (mzXML); we reproduce that to match its answers. **Consequence:** on interleaved
   acquisition, multiple MS1 scans per cycle, or a precursor reference pointing further back than the
   immediately preceding MS1 scan, our `ms1scan` disagrees with the file's declared linkage. For simple DDA
   they coincide. This is bug-for-bug fidelity, chosen deliberately. ([Step 6](Tech_Step6.md) §4, proved in
   [Step 7](Tech_Step7.md).)
2. **`=` means `>=` for intensity comparisons** — MassQL's historical semantics, reproduced.
   ([Step 9](Tech_Step9.md) §3.)
3. **`i_norm` and `i_norm_ms1` are dropped** from the output as structurally constant.
   ([Step 10](Tech_Step10.md).)
4. **`tic` is NOT bit-identical to MassQL's, and ours is the more accurate value.** MassQL's intensity column
   is `float32` and `tic` is a pandas `groupby.sum()` over it; we accumulate in float64. Worst measured
   divergence **4.700e-8** relative, compared at 1e-6. **State plainly that the error is in the reference,
   not in us** — a reviewer seeing a tolerance will otherwise assume the opposite. Every *other* intensity
   column (`base_peak_i`, `ms1_i`, `ms1_base_peak_i`) **is** bit-identical, because they are selections
   rather than sums.
5. **`POLARITY` on an MGF filters a constant, not the data.** MassQL hardcodes MGF polarity to **1**, so
   `POLARITY=Positive` matches every MGF scan and `POLARITY=Negative` matches none, whatever the spectra are.
   There is no way to tell from the output, which is precisely why it belongs in this list.
6. **The JSON is compact; the reference emits `indent=2`.** Values are identical and round-trip exactly, but
   the two are **not byte-comparable** — a reviewer reaching for `diff` will otherwise conclude the results
   disagree.

### 3. One command runs everything

> ✅ **The Makefile was filled in early, at Step 9** — ad-hoc build-tool invocations had already begun to
> diverge from what CI ran, so the convention was pulled forward: **the build tool is never invoked
> directly; the Makefile is the only entry point**, and both workflows call its targets.

```
make integration-test  →  gradle check
                            ├─ unit suites, both projects
                            ├─ integration suites, both projects
                            ├─ jacocoTestCoverageVerification (90% instructions)
                            ├─ Spotless
                            └─ checkBannedDependencies
```

⚠ **`check` rather than the suites by name.** Naming `integrationTest` alone resolves to the **root**
project's task and silently skips the CLI contract suite, which forks the uber-jar.

**Non-zero exit on any failure, and zero skips.** A skip is structurally impossible — `Fixtures.require`
fails on an absent fixture — so the guard is "assert **zero** skips", and a skip appearing at all means
someone reintroduced an `assumeTrue` or `@Disabled`. `FixturesContractTest` asserts that from both sides.

### 4. Dependency audit

The SDK runtime closure after all exclusions, plus the measured total byte size, recorded in
[`dependency-audit.txt`](dependency-audit.txt). This is the artifact that answers *"did dependency complexity
stay bounded?"*

**Measured: 785,599 B = 0.749 MB, 49.9% of budget** — two artifacts, `javolution-core-java-msftbx`
(459,292) + `antlr4-runtime` (326,307).

The tree shows **no MSDK at all**, and no `dsiutils`, `fastutil`, `guava`, `jsr305`, `checker-qual`, `slf4j`,
`logback`, `jaxb-*`, `cdk-*` or `commons-*`. All are rejected by `checkBannedDependencies`, which runs inside
`check`, so a dependency change that reintroduced one fails the build.

### 5. The SDK knows nothing about what embeds it

⛔ **`massql-java` processes MassQL. That is its entire remit.** It carries no packaging metadata for any
consumer, makes no assertion about the environment it will run in, and names no downstream project. How a
consuming application packages this jar is that application's business, in its own repository.

What remains is enforced for reasons that stand on their own, and none of them names a host:

| Rule | Why, without reference to any host |
|---|---|
| No `ServiceLoader` / `Class.forName` | Provider lookup fails wherever the thread-context classloader cannot see the caller's classes — a shaded jar, a plugin system, a module path. `DEPENDENCY_POLICY.md` constraint 1 |
| No logging framework | An embedded library that brings its own logger conflicts with whatever the host already uses. Constraint 2 |
| Closure **< 1.5 MB** | A budget. Constraint 6 |
| JDK 17 bytecode | A compatibility target, set by the toolchain rather than checked afterwards. Constraint 7 |

⚠ Do not reintroduce an embeddability check here on the grounds that it is cheap. A library that asserts
facts about its future container has taken on a dependency on that container, in documentation if not in
bytecode — and the next person reads those assertions as requirements.

### 6. Coverage, CI, and the §11 answers

**JaCoCo** — a **90% instruction gate** is enforced inside `check`, which is what replaced the rotting
hand-maintained test-count floor: a floor had to be edited on every test added, while a coverage ratio
maintains itself.

⚠ **The percentage is a floor, not evidence.** A number cannot tell a reviewer *which* rules are pinned.
What does that is the tests themselves: each one names the rule it holds, and that is where the reasoning
belongs.

**CI — already built in [Step 3](Tech_Step3.md) §8**, which owns `ci.yml` and `release.yml`. Its one gate is
`make integration-test`; there are no separate audit steps, because a second definition of what CI checks is
the drift the Makefile-only rule exists to prevent.

Already in place from Step 3: JDK 17, the dependency rule as a gate, the guard that stops a skip-everything
run passing green — now the coverage gate, which needs no maintained count — and a `fetch-fixtures.sh` step
with an `actions/cache` for the two gitignored fixtures.

**[`SPIKE_ANSWERS.md`](SPIKE_ANSWERS.md) is the canonical home for the eight answers**, each citing the
evidence behind it. Four were already settled and just needed carrying forward:

| # | Question | Source |
|---|---|---|
| 1 | Bit-identical decoded intensities vs Python? If not, what tolerance is the contract? | [Step 8](Tech_Step8.md) [`PARITY_REPORT.md`](PARITY_REPORT.md) |
| 2 | Same rows on `small.mzML` and `small.mzXML`? | [Step 12](Tech_Step12.md) layer 3 Pair A |
| 3 | Closure under ~1.5 MB? Both javolution forks in the released pom? | **Answered:** 785,599 B = 0.749 MB; **no** — the plain fork is commented out. §4 |
| 4 | Is MSDK's licence shippable? | **Answered:** dual LGPL-2.1 / EPL-1.0; **we elect EPL-1.0**. Not blocking |
| 5 | Parser: ANTLR, hand-written, or remote `/parse`? | **Answered:** ANTLR 4.13.2 embedded, 326,307 B |
| 6 | Measured LOC — does 1,200–1,800 hold? | Count production vs test LOC here |
| 7 | Where does `massql-java` live, and how is it published? | **Answered:** GitHub → the NRNB-hosted Nexus; `~/.m2` during the spike |
| 8 | Wall-clock and peak heap vs the pandas path? | [Step 12](Tech_Step12.md) §5 |

For Q6, report production and test LOC separately and say whether the estimate held. If it did not, say by
how much and where — that is useful calibration, and a quietly-missed estimate is the kind of thing a review
exists to surface.

### 7. The review gate

Stop here. The reviewer checks `massql-java` against the goldens, the test suite and the README before any
downstream work begins.

⚠ **No packaging trial is part of this step.** Whether this jar embeds cleanly in a particular application is
that application's question to answer, with its own build. Raising it here would put a consumer's concern
back into a library that has none — see §5.

## Known traps

- **Overclaiming parity in the README.** "MassQL-compatible" invites a consumer to assume features that
  reject. Say `scaninfo` subset.
- **Burying the `ms1scan` deviation** in a code comment. A user comparing our `ms1scan` against their file's
  `spectrumRef` will conclude we have a bug. §2.
- **Treating the coverage percentage as the review artifact.** The 90% gate is a floor that stops the suite
  rotting; a test written to raise the number rather than to pin a rule is a step backwards.
- **A green run that skipped fixtures.** A green empty result is worse than a red one. Assert **zero** skips.
- **Adding a packaging or container check to this repo.** It does not belong here at all (§5), however cheap
  it looks.
- **Starting downstream work "while the review is pending."** The gate exists to prevent exactly that.

## Done when

- [x] `make integration-test` green, with **zero skips** — unit and integration suites for both projects,
      plus the 90% coverage gate, Spotless and the banned-dependency rule.
- [x] README contains the implementation basis, the two artifacts, how to build, the EPL-1.0 election and the
      honest-framing paragraph — and nothing a user does not need.
- [x] `docs/SDK.md` carries the **six known deviations**, where someone integrating the library will meet
      them, and `docs/CLI.md` carries runnable examples against committed fixtures.
- [x] [`dependency-audit.txt`](dependency-audit.txt) records the closure and its total; none of the forbidden
      artifacts present.
- [x] JaCoCo report generates, and coverage is **≥90%** on both projects.
- [x] CI green on push/PR with **zero skipped tests**. Note CI makes one network call —
      `fetch-fixtures.sh` for the two gitignored fixtures — behind an `actions/cache`, so the flaky upstream
      is contacted once rather than per run.
- [x] [`SPIKE_ANSWERS.md`](SPIKE_ANSWERS.md) answers all eight §11 questions, including a measured LOC figure
      with a verdict on the 1,200–1,800 estimate.
- [x] **No consumer or container references anywhere in the repository** (§5): no packaging metadata, no
      readiness check, and no named downstream project in any source file, build script or document.
- [x] **Work stops.** Verified, not assumed.

## References

- [`SPIKE.md`](SPIKE.md) §7 Step 3 (this step), §8 (honest framing and the out-of-scope list), §11 (the eight
  questions), §6d (build wiring). §9's dependency constraints live in `DEPENDENCY_POLICY.md`
- Inputs: [`PARITY_REPORT.md`](PARITY_REPORT.md) ([Step 8](Tech_Step8.md)),
  [`DIFFERENTIAL_REPORT.md`](DIFFERENTIAL_REPORT.md) ([Step 12](Tech_Step12.md)),
  **[`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)** ([Step 10](Tech_Step10.md)),
  [`SEMANTICS.md`](SEMANTICS.md) ([Step 9](Tech_Step9.md)), [`VENDORED.md`](../VENDORED.md)
  ([Step 6](Tech_Step6.md)), [`READER_RULES.md`](READER_RULES.md) (Steps [6](Tech_Step6.md),
  [7](Tech_Step7.md))

  > ⚠ **Check each of these exists before relying on it as a review artifact.**
  > [`VENDORED.md`](../VENDORED.md) was a ticked Step 6 deliverable, a Step 13 review input, and the target
  > of eleven vendored source headers — and was absent for three steps. It exists now, asserted by
  > `VendoredProvenanceTest`. A review gate whose inputs may be absent is not a gate.
