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

## ⚠ Two locations, and the specs reference both

This is not obvious from the file paths, so read this before following a path in a spec.

**This repo (`massql-java`) is the only deliverable.** Production code, tests, and these
specs. Nothing outside it ships.

**The oracle working directory (`../massql`, relative to this repo's root)** holds the
pinned Python MassQL install, the fixtures, the goldens, and `massql_query.py`. It is
**never shipped** and is not a git repo. It exists to define the behavioural contract and to
generate the goldens the Java test suite diffs against.

Paths in the specs resolve like this:

| Path form in a spec | Where it actually lives |
|---|---|
| `src/main/java/…`, `docs/…`, `pom.xml` | **this repo** |
| `oracle/…` (e.g. `oracle/PINNED.md`, `oracle/msql.ebnf`) | the oracle directory |
| `data/…` (e.g. `data/small.mzML`, `data/CONVERSION_NOTES.md`) | the oracle directory |
| `fixtures/…` (e.g. `fixtures/micro/EXPECTED.md`) | the oracle directory |
| `output/…` (the goldens) | the oracle directory |

Artifacts copied *into* this repo for the test suite — the reference-parse corpus and the
loader-parity dumps — live under `src/test/resources/` and are the committed copies the
tests actually read.

## Where a finding goes

The index's **fallout protocol** section is the rule, and it matters more than it sounds:
a correction is not done until the affected specs are edited, because the engineer building
Step 12 will never think to re-read Step 2's notes. In short:

- a spec is wrong, or a later step's assumption breaks → a numbered **Correction in the
  index** *and* an edit at the point of use in every affected spec;
- a fact about a fixture → `data/CONVERSION_NOTES.md` in the oracle directory;
- a fact about the oracle → `oracle/PINNED.md` or `oracle/NOTES_fileloading.md`.
