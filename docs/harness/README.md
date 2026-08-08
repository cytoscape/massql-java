# Harness specs

The execution record for this SDK: one spec per implementation step, written to be handed to
one engineer and finished without reading the others.

**Start at [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)** — status table, dependency graph, the
three internal gates, settled decisions, and the numbered corrections to [`SPIKE.md`](SPIKE.md).

### The specs

| | |
|---|---|
| [`SPIKE.md`](SPIKE.md) | The original spike document. Source of record for **rationale**; every spec cites it by section. Where it and a spec disagree, **the spec wins** — see the corrections list in the index. |
| [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) | The index. Read this first. |
| `Tech_Step1.md` … `Tech_Step13.md` | One spec per step. **Numbered 1–13; there is no Tech_Step0**, and the numbering is unrelated to SPIKE.md's 0–3 (the index has the mapping). |

### The engineering record — what each step produced

| | Owner |
|---|---|
| [`FIXTURES.md`](FIXTURES.md) | test fixtures and goldens, and why they are committed in-repo (C26) — [Step 2](Tech_Step2.md) |
| [`GRAMMAR_NOTES.md`](GRAMMAR_NOTES.md) | every deliberate divergence from the Lark source, and what a re-sync needs — [Step 4](Tech_Step4.md) |
| [`STORE_DESIGN.md`](STORE_DESIGN.md) | `SpectrumTable` internals and the measured baselines — [Step 5](Tech_Step5.md) |
| [`READER_RULES.md`](READER_RULES.md) | the per-format rule table — [Step 6](Tech_Step6.md), [Step 7](Tech_Step7.md) |
| [`PARITY_REPORT.md`](PARITY_REPORT.md) | the reader-parity gate verdict — [Step 8](Tech_Step8.md) |
| [`SEMANTICS.md`](SEMANTICS.md) | every condition rule with the source line that establishes it — [Step 9](Tech_Step9.md) |
| [`oracle/`](oracle/README.md) | the pin, the verified loader facts, fixture provenance, and MassQL's own grammar — the yardstick everything above is measured against |

> These moved here from `docs/` under Correction **C41**, together with four artifacts that had been sitting in a
> non-versioned directory outside the repository. They are the *engineering record* — read by someone confirming
> or continuing the work, not by someone consuming the SDK.

### Not here, deliberately

Two documents stay at [`docs/`](..) because they are **published**, not internal — and both are read at runtime
by tests, so their paths are code:

| | |
|---|---|
| [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md) | the frozen 12-key result contract the Phase-2 app consumes and `MASSQL_PARSE` reads back. `ResultSchemaContractTest` parses it |
| [`VENDORED.md`](../VENDORED.md) | the EPL-1.0 election and vendored-code provenance a redistributor needs. `VendoredProvenanceTest` asserts it |

## Building and testing — `make` only, never `./gradlew`

**The `Makefile` is the only entry point.** `make` with no argument lists every target. CI and
`release.yml` call the same targets, so what you run locally and what runs on a push cannot drift.

The build produces **two independently versioned artifacts** (`gradle.properties`): `massql-java`, the thin SDK
jar `massql-app` embeds, and `massql-java-cli`, a standalone uber-jar. Neither version forces the other.

| | |
|---|---|
| `make build` | compile and package both jars |
| `make test` | unit tests only (`src/test`, `*Test.java`) — seconds, for the edit loop |
| `make it` | integration tests only (`src/integrationTest`, `*IT.java`) — the fast way to re-check a gate |
| `make verify` | the review entry point: unit + integration + coverage gate + lint + banned deps, then `audit` and `spec-audit` |
| `make lint` / `make lint-fix` | Spotless — report / fix. The config **is** the style specification; there is no style document |
| `make coverage` | JaCoCo report. The 90% instruction gate runs inside `make verify` |
| `make cli` | build the standalone CLI uber-jar |
| `make audit` | regenerate `dependency-audit.txt` and check the ~1.5 MB SDK budget |
| `make fixtures` | download the two gitignored Ewing-lab fixtures |
| `make test-one T=X` / `make it-one T=X` | a single suite |
| `make set-version-sdk V=` / `make set-version-cli V=` | stamp one artifact's version |
| `make publish-sdk` / `make publish-cli` | publish one artifact to the nexus |

**If you need something `make` does not do, add a target** — do not reach for `./gradlew`. A one-off
invocation is how the ad-hoc commands that prompted this file came to diverge from CI in the first
place. `mvn` still appears in completed steps' records of what was run at the time; that is history and is
left alone. Anywhere it *describes how the build works today*, it has been corrected to Gradle.

## ⚠ Two locations, and only one of them matters for reading

**This repo (`massql-java`) is the only deliverable, and now holds every document.** Production code, tests,
these specs, and the engineering record above. Nothing outside it ships, and nothing outside it needs reading.

**The oracle working directory (`../massql`, relative to this repo's root)** holds the pinned Python MassQL
install and the scripts that *generate* fixtures and goldens — `massql_query.py`, `generate-all.sh`,
`reproduce-goldens.sh`, `dump_loader_parity.py`, `make_micro_fixtures.py`, `mzml_to_mzxml.py`,
`venv-setup.sh`, `test_query_py_reference.py`, `requirements.freeze.txt`, the venv, and the pinned MassQL clone.
It is **never shipped**, is **not a git repo**, and is now **executable tooling only**.

> ⚠ **Corrections C26 and C41 — nothing outside this repo is required to build, test, or read.**
> **C26** moved the fixtures and goldens in: they are committed under `src/test/resources/`, because CI checks
> out only `massql-java` and the old arrangement made every fixture-dependent test skip *silently*.
> **C41** finished the job for documentation. Four artifacts the specs actively cite — the pin, the verified
> loader facts, fixture provenance, and MassQL's own grammar — were still in that unversioned directory, which is
> how `SPIKE.md` came to exist in two copies that **forked**, and how `PINNED.md` lost a record its own code
> comment pointed at. `make verify` needs nothing outside this repo. See [`FIXTURES.md`](FIXTURES.md) and
> [`oracle/README.md`](oracle/README.md).

Paths in the specs resolve like this:

| Path form in a spec | Where it actually lives |
|---|---|
| `src/main/java/…`, `docs/…`, `build.gradle` | **this repo** |
| `data/…`, `fixtures/…`, `goldens/…`, `reference_parses/…` | **this repo**, under `src/test/resources/` (C26) |
| [`oracle/PINNED.md`](oracle/PINNED.md), [`oracle/msql.ebnf`](oracle/msql.ebnf) and siblings | **this repo**, `docs/harness/oracle/` (C41 — these were in the oracle directory until then) |
| `output/…` (where goldens are *generated*) | the oracle directory; the committed copies are `src/test/resources/goldens/query-results/` |
| a `.py` or `.sh` under `oracle/` | the oracle directory — tooling, not something a spec cites as design input |

The one exception is the two Ewing-lab fixtures (`data/DP00570_F02.*`), gitignored because
ewinglab.org states no redistribution terms. `scripts/fetch-fixtures.sh` retrieves them and CI
caches the result; their absence **fails** a test rather than skipping it.

## Where a finding goes

The index's **fallout protocol** section is the rule, and it matters more than it sounds:
a correction is not done until the affected specs are edited, because the engineer building
Step 12 will never think to re-read Step 2's notes. In short:

- a spec is wrong, or a later step's assumption breaks → a numbered **Correction in the
  index** *and* an edit at the point of use in every affected spec. **From C38 onward the Correction must carry
  a `Fallout:` line**, and `make spec-audit` fails the build until every spec it names cites it back;
- a fact about a fixture → [`oracle/CONVERSION_NOTES.md`](oracle/CONVERSION_NOTES.md);
- a fact about the oracle → [`oracle/PINNED.md`](oracle/PINNED.md) or
  [`oracle/NOTES_fileloading.md`](oracle/NOTES_fileloading.md).

All three are **in this repo** as of C41. They were in the unversioned oracle directory, which is exactly how one
of them lost a record it was the designated home for.
