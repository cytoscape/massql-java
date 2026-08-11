# Oracle records and artifacts

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

The **records** the harness design references, and the **artifacts** it was derived from. Everything here is
input to, or evidence for, the step specs one directory up.

| | |
|---|---|
| [`PINNED.md`](PINNED.md) | The pin — MassQL SHA, Python version, the frozen environment — plus golden provenance and the **two deliberate divergences** in `massql_query.py`. The answer to *"what exactly is this SDK compliant with?"* |
| [`NOTES_fileloading.md`](NOTES_fileloading.md) | `msql_fileloading.py` verified line by line: which loaders are live, how each format's RT / polarity / charge / `ms1scan` are really derived. Carries the **C6 / C7 / C8** corrections. The authority behind [`READER_RULES.md`](../READER_RULES.md) |
| [`CONVERSION_NOTES.md`](CONVERSION_NOTES.md) | Per-fixture provenance for the whole spike: what each fixture is, what was verified about it, and every fixture-level finding later steps depend on |
| [`msql.ebnf`](msql.ebnf) | MassQL's entire formal grammar — 165 lines of Lark EBNF. [Step 4](../Tech_Step4.md)'s translation source, and the file **C19** was found by reading |

## Provenance

Extracted from the pinned MassQL clone at
**`dad2a28c01e6e5132240270fc6700fbae29f1652`** (tag `2026.03.14`) — the SHA
[`PINNED.md`](PINNED.md) records as the definition of "MassQL-compliant" for this project.

| File | Upstream origin |
|---|---|
| [`msql.ebnf`](msql.ebnf) | `massql/msql.ebnf` in the pinned clone — **byte-identical** to upstream |
| the three `.md` files | written for this project while reading the pinned source; not upstream content |

**[`msql.ebnf`](msql.ebnf) deliberately carries no provenance header**, unlike the vendored Java under
[`VENDORED.md`](../../VENDORED.md). Those files are compiled and redistributed, so the licence election has
to travel *in* the file. This one is a reference artifact that
[`GRAMMAR_NOTES.md`](../GRAMMAR_NOTES.md) instructs a future re-sync to **diff against upstream** — a header
would put noise in every such diff forever. Provenance lives here instead, where it costs nothing.

`reference_parses/` (the 46-file conformance corpus, from the same clone) is the precedent: also committed, also
unmodified, and it lives under `src/test/resources/` because the test suite reads it directly.

## Why these are in the repo at all

They were in a sibling `../massql` working directory until **Correction C41**, which is **not** a git repo — so
746 lines of actively-cited harness content had no history, no reviewable change, and no way to notice a fork.
Two things had already gone wrong there:

- **[`SPIKE.md`](../SPIKE.md) forked.** The copy outside the repo still carried the pre-C40 *"MS1DATA → a different, smaller
  shape … absent, not null"* text, hours after C40 corrected the in-repo one.
- **[`PINNED.md`](PINNED.md) lost a record it was the designated home for** — `massql_query.py` pointed at it for *"the one
  deliberate divergence"* and it carried none until C40 added it.

Neither was luck: `spec-audit` had nothing that could reach outside the repo, so this was the only harness
content with no verification at all. It is now covered like everything else.

## What stayed outside, and why

`../massql` keeps **only executable tooling** — the thing that *generates* fixtures and goldens, never something
a spec cites as design input:

`massql_query.py` · `generate-all.sh` · `reproduce-goldens.sh` · `dump_loader_parity.py` ·
`make_micro_fixtures.py` · `mzml_to_mzxml.py` · `venv-setup.sh` · `test_query_py_reference.py` ·
`requirements.freeze.txt` · the venv · the pinned MassQL clone

Nothing in this repository's build depends on that directory being present — `make verify` needs nothing
outside the repo (Correction **C26**). The generated outputs are committed under
`src/test/resources/`; see [`FIXTURES.md`](../FIXTURES.md).
