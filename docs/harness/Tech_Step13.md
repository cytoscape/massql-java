# Tech Step 13 — Harden, document, hand off

> **⛔ ENDS AT THE REVIEW GATE.** When this step is done, **stop**. No `massql-app` work, no OSGi bundle, no
> Cytoscape code until `massql-java` has been manually reviewed against the goldens, the test suite and the README.

## Goal

Make the repo reviewable rather than merely working: a reviewer who has never seen it can run one command, read
one table, and judge whether the spike succeeded.

## Prerequisites

| Step | Why |
|---|---|
| [Step 12](Tech_Step12.md) | Its gate must be green, and `docs/DIFFERENTIAL_REPORT.md` is the input to the `make verify` table. |

## Context

`SPIKE.md` §7 Step 3 frames this precisely: *"What makes the repo reviewable rather than just working."* The
engineering is done by now; this step exists because a spike that cannot be independently validated has not
actually answered its questions. The review gate is the deliverable, and the artifacts below are what gets
reviewed.

Governing sections: `SPIKE.md` §7 Step 3, §8 (honest framing), §9 (the constraints to assert), §11 (the eight
questions), §6d.

## Scope

**In scope**
- README, with the feature matrix and every published contract.
- `make verify` and its per-format pass/fail table.
- The checked-in dependency audit.
- A scripted OSGi-readiness check.
- JaCoCo coverage reporting.
- Written answers to all eight `SPIKE.md` §11 questions.
- CI.

**Out of scope**
- **Everything Phase 2.** The activator, menu, dialog, node-table write-back and `MASSQL_PARSE` are sketched in
  `SPIKE.md` §10 for review context only.
- New features or semantics. If something is missing, it belongs to its owning step.
- **The OSGi canary** — presented as a decision *for* the reviewer, not performed. See §7.

## Deliverables

| Path | Content |
|---|---|
| `README.md` | The primary review artifact |
| `Makefile` | `verify` target, filling in the [Step 3](Tech_Step3.md) stub |
| `dependency-audit.txt` | Final `mvn dependency:tree` + measured total (updated from Step 6) |
| `scripts/check-osgi-readiness.sh` | The §9 assertions, scripted |
| `docs/FEATURE_MATRIX.md` | Parses / executes / rejects, generated from `UnsupportedConstructs` |
| `docs/SPIKE_ANSWERS.md` | The eight §11 questions, answered |
| `.github/workflows/ci.yml` | **Already exists** (Step 3 §8) — this step adds the OSGi-readiness check to it |
| `target/site/jacoco/` | Coverage report (generated, not committed) |

## Specification

### 1. README

The reviewer's entry point. Required content:

- **The pinned MassQL SHA** — `dad2a28c01e6e5132240270fc6700fbae29f1652` (tag `2026.03.14`). State plainly that
  this SHA *is* the definition of "MassQL-compliant" here.
- **The supported-feature matrix** — what parses, what executes, what rejects. Link `docs/FEATURE_MATRIX.md`.
- **The 12-key result contract** and the 4-key MS1DATA shape, with the absent-vs-null distinction called out.
  Point at `docs/RESULT_CONTRACT.md`.
- **The three per-format population rules** ([Step 10](Tech_Step10.md) §6 table).
- **Known deviations** — see §2. Do not bury these.
- **The EPL-1.0 election** and the vendored-code provenance ([Step 6](Tech_Step6.md) §3 — Step 6 owns all vendoring; Step 7 vendors nothing per C23).
- **CLI usage** and how to run `make verify`. Show **both output modes** and say when each fits — stdout
  for composing with other tools, `--output FILE` for a durable artifact a downstream consumer can pick up
  (Correction C25b):

  ```bash
  # default: JSON on stdout, diagnostics on stderr -- composable
  java -cp massql-java.jar edu.ucsd.idekerlab.massql.cli.Main spectra.mzML query.massql | jq '.[0]'

  # --output: JSON to a file, written atomically; stdout stays empty
  java -cp massql-java.jar edu.ucsd.idekerlab.massql.cli.Main spectra.mzML query.massql --output out.json
  ```

  While documenting this, keep the three layers distinct (Correction C25a, *Terminology* in
  [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)): the stream rules above govern the **Java CLI**. The **SDK**
  writes to no stream at all and returns `List<ScanInfoResult>` — which is what Phase 2 consumes, so a
  reviewer must not read the CLI's stdout contract as an SDK behaviour.
- **Honest framing**, per `SPIKE.md` §8: "full MassQL parity" means bug-for-bug agreement with *one commit* of a
  tool whose own docs advertise functions that don't exist (`scanmaxmz`, `scanrun`) while `scanmz` and `OTHERSCAN`
  exist undocumented. **Call it a `scaninfo` subset.** Overclaiming here is the one documentation failure that
  would actively mislead Phase 2.

### 2. Known deviations — state them, don't bury them

**Five** deliberate divergences a user could otherwise discover the hard way (three were foreseen; two came out
of Steps 7–8):

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

Plus any tolerance adopted in [Step 8](Tech_Step8.md) §1 or [Step 12](Tech_Step12.md) §1 — if a `tic`
accumulation-order tolerance was accepted, it is a deviation and belongs here.

### 3. `make verify`

```
make verify   →  mvn verify
              +  the three differential comparisons
              +  prints a per-format pass/fail table
```

The table is the review artifact. `SPIKE.md` §6d: *"The reviewer should not have to reconstruct how to validate
the thing."* Shape:

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

Also add `make test` (unit only, fast) and `make audit` (dependency tree + size).

### 4. Dependency audit

Final `mvn dependency:tree` after all exclusions, plus the measured total byte size, committed as
`dependency-audit.txt`. This is the artifact that answers *"did dependency complexity stay bounded?"*

**Measured in Step 3: 785,599 B = 0.749 MB, 49.9% of budget** — two artifacts, `javolution-core-java-msftbx`
(459,292) + `antlr4-runtime` (326,307). Confirm this is unchanged after both readers are vendored.

The tree must show **no MSDK at all**, and no `dsiutils`, `fastutil`, `guava`, `jsr305`, `checker-qual`,
`slf4j`, `logback`, `jaxb-*`, `cdk-*` or `commons-*`. All are banned by the enforcer at `validate`
(Correction C16), so this is a re-confirmation rather than a fresh check.

`scripts/dependency-audit.sh` already exits non-zero on a violation or budget breach, and CI runs it
([Step 3](Tech_Step3.md) §8) — so this row is about reading the committed artifact, not building the check.

### 5. `scripts/check-osgi-readiness.sh`

Scripted so Phase 2 isn't a surprise. Unpack the resolved runtime closure and assert:

| Assertion | Why |
|---|---|
| No `META-INF/services/**` in any jar | The thread-context classloader can't see inside an OSGi bundle. cytoscape-mcp hit this twice — Lucene and the MCP SDK |
| No `ServiceLoader` / `Class.forName` in our own bytecode | Same |
| **No slf4j in any form** | Correction C16 removed it entirely — it was only ever needed by MSDK's `MzMLFileImportMethod`, and MSDK is no longer a dependency. The enforcer bans `org.slf4j:*`. (C4's narrower rule — pin 1.7.26, never 2.x — still applies if logging is ever reintroduced, since 2.x uses `ServiceLoader`.) |
| No `META-INF/versions/**` | Multi-release jars break Felix resolution on Cytoscape 3.10.x |
| No JAXB, no native libs (`.so`/`.dylib`/`.dll`), no `sun.misc.Unsafe` | §9 |
| No logback / slf4j **binding** (api only) | `cy-ndex-2` embeds slf4j+logback while cytoscape-mcp excludes `org/slf4j/**`; don't add to that conflict |
| Total closure **< 1.5 MB** | §9 |
| No split packages | Felix rejects them |
| Class file major version ≤ 61 (Java 17) | Matches Cytoscape 3.10.4's parent pom |

Wire it into `make verify` and CI so a future dependency change fails the build rather than the bundle.

### 6. Coverage, CI, and the §11 answers

**JaCoCo** — *"not as a target to game, but so the reviewer can see which of the §3 rules are actually
exercised."* Do not add a coverage-percentage gate. Instead, in the README, map each `SPIKE.md` §3 rule to the
test that pins it — a short table is far more useful to a reviewer than a percentage.

**CI — already built in [Step 3](Tech_Step3.md) §8**, which owns `ci.yml` and `release.yml`. Do not
re-specify them here. This step only *adds* to the existing `ci.yml`:

- a step running `scripts/check-osgi-readiness.sh` (§5 below);
- confirmation that **no test reaches the network** (`DEPENDENCY_POLICY.md` constraint 8) — the 46 parse
  goldens are checked-in files, never live calls to `massql.gnps2.org/parse`.

Already in place from Step 3 and not to be duplicated: JDK 17, `mvn verify` (not `mvn test` — every gate lives
in an `*IT.java`), the dependency audit as a gate, and the "assert tests actually ran" guard that stops a
skip-everything run from passing green — **strengthened by Correction C26** to also assert the skipped-test
count is **0**, plus a floor on the number executed, and a `fetch-fixtures.sh` step with an `actions/cache`
for the two gitignored Ewing files.

**`docs/SPIKE_ANSWERS.md`** — answer all eight §11 questions plainly. Four are already settled and just need
carrying forward:

| # | Question | Source |
|---|---|---|
| 1 | Bit-identical decoded intensities vs Python? If not, what tolerance is the contract? | [Step 8](Tech_Step8.md) `PARITY_REPORT.md` |
| 2 | Same rows on `small.mzML` and `small.mzXML`? | [Step 12](Tech_Step12.md) layer 3 Pair A |
| 3 | Closure under ~1.5 MB? Both javolution forks in the released pom? | **Answered:** ≈1.17 MB; **no** — the plain fork is commented out (Correction C2). Final number from §4 |
| 4 | Is MSDK's licence shippable? | **Answered:** dual LGPL-2.1 / EPL-1.0; **we elect EPL-1.0** (Correction C3). Not blocking |
| 5 | Parser: ANTLR, hand-written, or remote `/parse`? | **Answered:** ANTLR 4.13.2 embedded, 326,307 B |
| 6 | Measured LOC — does 1,200–1,800 hold? | Count production vs test LOC here |
| 7 | Where does `massql-java` live, and how is it published? | **Answered:** `github.com/cytoscape/massql-java` → nrnb-nexus `cytoscape_releases`; `~/.m2` during the spike |
| 8 | Wall-clock and peak heap vs the pandas path? | [Step 12](Tech_Step12.md) §5 |

For Q6, report production and test LOC separately and say whether the estimate held. If it did not, say by how
much and where — that is useful calibration for Phase 2's estimate, and a quietly-missed estimate is the kind of
thing a review exists to surface.

### 7. The review gate

Stop here. The reviewer checks `massql-java` against the goldens, the test suite and the README before any
`massql-app` work begins.

**Present the OSGi canary as a decision, not a deliverable.** `SPIKE.md` §9 recommends a 2-hour throwaway bundle
that embeds `massql-java` and logs a scan count from `small.mzML` inside Cytoscape. Its value: it is the one risk
that, if it fails, forces a change *inside* `massql-java` (vendoring more of MSDK rather than depending on it) —
and discovering that after the gate is expensive. Its cost: it is not pure Java, so it breaks the spike's clean
boundary. **Put both sides in the README and let the reviewer choose.** Do not run it unasked.

Note for the reviewer that the vendoring in [Step 7](Tech_Step7.md) has already removed the largest OSGi risk —
`ServiceLoader`-bearing and multi-megabyte dependencies — so the canary is now cheaper insurance on a smaller
exposure than `SPIKE.md` assumed.

## Known traps

- **Overclaiming parity in the README.** "MassQL-compatible" invites Phase 2 to assume features that reject. Say
  `scaninfo` subset, and publish the matrix.
- **Burying the `ms1scan` deviation** in a code comment. A user comparing our `ms1scan` against their file's
  `spectrumRef` will conclude we have a bug. §2.
- **A coverage-percentage gate.** Invites tests written for the number. Map rules to tests instead.
- **A hand-maintained feature matrix** that drifts from `UnsupportedConstructs`. Generate it.
- **`make verify` exiting 0 when fixtures were skipped.** A green empty table is worse than a red one. Since
  Correction C26 a skip is structurally impossible — `Fixtures.require` fails — so the guard is now "assert
  **zero** skips", and a skip appearing at all means someone reintroduced an `assumeTrue` or `@Disabled`.
- **Doing the OSGi canary because it's recommended.** It is a decision for the gate. §7.
- **Starting Phase 2 "while the review is pending."** The gate exists to prevent exactly that.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `FeatureMatrixTest` | unit | The matrix in `docs/FEATURE_MATRIX.md` matches `UnsupportedConstructs` — every rejected construct listed, no listed construct silently supported. Keeps the published matrix honest. |
| `OsgiReadinessIT` | IT | Runs `check-osgi-readiness.sh` and asserts exit 0, so the constraints are enforced by the build. |
| `MakeVerifyIT` | IT | `make verify` exits non-zero when a differential is deliberately broken, and non-zero if **any** test skips or fewer than the expected number run (C26 — "all fixtures skip" is no longer reachable, so the guard is a zero-skip assertion). Guards the review artifact against a false green. |

## Done when

- [ ] `make verify` prints the per-format per-column table and exits 0; it exits non-zero on a broken differential
      and on **any** skipped test (C26).
- [ ] README contains: the pinned SHA, the feature matrix link, both result shapes, the population rules, **all
      known deviations**, the EPL-1.0 election, CLI usage, and the honest-framing paragraph.
- [ ] `dependency-audit.txt` committed; total recorded; none of the forbidden artifacts present.
- [ ] `scripts/check-osgi-readiness.sh` passes all nine assertions and runs in CI.
- [ ] JaCoCo report generates; the README maps each §3 rule to the test that pins it.
- [ ] CI green on push/PR, with **zero skipped tests** and a floor on the number executed (C26). Note CI
      does make one network call — `fetch-fixtures.sh` for the two gitignored Ewing files — behind an
      `actions/cache`, so the flaky upstream is contacted once rather than per run.
- [ ] `docs/SPIKE_ANSWERS.md` answers all eight §11 questions, including a measured LOC figure with a verdict on
      the 1,200–1,800 estimate.
- [ ] The OSGi canary is **presented as a decision** in the README, not performed.
- [ ] **Work stops.** Nothing in the repo references Cytoscape, OSGi or `massql-app` except as documented Phase-2
      context.

## References

- `SPIKE.md` §7 Step 3 (this step), §8 (honest framing and the out-of-scope list), §9 (the constraints and the
  canary), §11 (the eight questions), §6d (build wiring and `make verify`), §10 (Phase-2 sketch — context only)
- Inputs: `docs/PARITY_REPORT.md` ([Step 8](Tech_Step8.md)), `docs/DIFFERENTIAL_REPORT.md`
  ([Step 12](Tech_Step12.md)), `docs/RESULT_CONTRACT.md` ([Step 10](Tech_Step10.md)),
  `docs/SEMANTICS.md` ([Step 9](Tech_Step9.md)), `docs/VENDORED.md` ([Step 7](Tech_Step7.md)),
  `docs/READER_RULES.md` (Steps [6](Tech_Step6.md), [7](Tech_Step7.md))
- Corrections C1–C5 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) — C2, C3, C4 answer §11 questions directly
- Phase-2 patterns, for the reviewer's context: `../cytoscape-mcp/.../CyActivator.java`,
  `../open-cyweb/pom.xml:116-132`, `../cytoscape-mcp/build.gradle:143-200`
