# Harness specs

The execution record for this SDK: one spec per implementation step, written to be handed to
one engineer and finished without reading the others.

**Start at [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)** — status table, dependency graph, the
three internal gates, settled decisions, and the numbered corrections to `SPIKE.md`.

| | |
|---|---|
| [`SPIKE.md`](SPIKE.md) | The original spike document. Source of record for **rationale**; every spec cites it by section. Where it and a spec disagree, **the spec wins** — see the corrections list in the index. |
| [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md) | The index. Read this first. |
| `Tech_Step1.md` … `Tech_Step13.md` | One spec per step. **Numbered 1–13; there is no Tech_Step0**, and the numbering is unrelated to SPIKE.md's 0–3 (the index has the mapping). |

## Building and testing — `make` only, never `mvn`

**The `Makefile` is the only entry point.** `make` with no argument lists every target. CI and
`release.yml` call the same targets, so what you run locally and what runs on a push cannot drift.

| | |
|---|---|
| `make build` | compile and package the jar |
| `make test` | unit tests only (surefire, `*Test.java`) — seconds, for the edit loop |
| `make it` | integration tests only (failsafe, `*IT.java`), **skipping** the unit suite — the fast way to re-check a gate |
| `make verify` | the review entry point: unit + integration + JaCoCo + enforcer, then `skipcheck` and `audit` |
| `make skipcheck` | asserts tests ran **and none were skipped** (Correction C26) |
| `make audit` | regenerate `dependency-audit.txt` and check the ~1.5 MB budget |
| `make fixtures` | download the two gitignored Ewing-lab fixtures |
| `make test-one T=X` / `make it-one T=X` | a single suite |

**If you need something `make` does not do, add a target** — do not reach for `mvn`. A one-off
invocation is how the ad-hoc commands that prompted this file came to diverge from CI in the first
place. `mvn` appears in these specs only where it *describes the mechanism* a target wraps (the
surefire/failsafe split in [Step 3](Tech_Step3.md)) or in a completed step's record of what was run.

## ⚠ Two locations, and the specs reference both

This is not obvious from the file paths, so read this before following a path in a spec.

**This repo (`massql-java`) is the only deliverable.** Production code, tests, and these
specs. Nothing outside it ships.

**The oracle working directory (`../massql`, relative to this repo's root)** holds the
pinned Python MassQL install, `massql_query.py`, and the scripts that *generate* the fixtures
and goldens. It is **never shipped** and is not a git repo. It exists to define the
behavioural contract and to produce the goldens the Java test suite diffs against.

> ⚠ **Correction C26 — the test suite no longer reads from the oracle directory at all.**
> Fixtures and goldens are **committed to this repo** under `src/test/resources/`, because CI
> checks out only `massql-java` and the old arrangement made every fixture-dependent test skip
> silently. `make verify` now needs nothing outside this repo. See [`../FIXTURES.md`](../FIXTURES.md).

Paths in the specs resolve like this:

| Path form in a spec | Where it actually lives |
|---|---|
| `src/main/java/…`, `docs/…`, `pom.xml` | **this repo** |
| `data/…`, `fixtures/…`, `goldens/…`, `reference_parses/…` | **this repo**, under `src/test/resources/` (C26) |
| `oracle/…` (e.g. `oracle/PINNED.md`, `oracle/msql.ebnf`) | the oracle directory |
| `output/…` (where goldens are *generated*) | the oracle directory; the committed copies are `src/test/resources/goldens/query-results/` |
| `data/CONVERSION_NOTES.md` | the oracle directory — per-fixture provenance and generation notes |

The one exception is the two Ewing-lab fixtures (`data/DP00570_F02.*`), gitignored because
ewinglab.org states no redistribution terms. `scripts/fetch-fixtures.sh` retrieves them and CI
caches the result; their absence **fails** a test rather than skipping it.

## Where a finding goes

The index's **fallout protocol** section is the rule, and it matters more than it sounds:
a correction is not done until the affected specs are edited, because the engineer building
Step 12 will never think to re-read Step 2's notes. In short:

- a spec is wrong, or a later step's assumption breaks → a numbered **Correction in the
  index** *and* an edit at the point of use in every affected spec;
- a fact about a fixture → `data/CONVERSION_NOTES.md` in the oracle directory;
- a fact about the oracle → `oracle/PINNED.md` or `oracle/NOTES_fileloading.md`.
