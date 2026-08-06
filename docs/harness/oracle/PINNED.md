# Pinned Python oracle

The fixed yardstick for the whole spike. Everything in `massql-java` is measured against this and nothing else.

## The pin

| | |
|---|---|
| **Repo** | `https://github.com/mwang87/MassQueryLanguage` |
| **Commit SHA** | **`dad2a28c01e6e5132240270fc6700fbae29f1652`** |
| Tag at that SHA | `2026.03.14` |
| Default branch HEAD when pinned | `17e8c7496df0188bc2751e13701faf06284266b7` (already past the tag) |
| Date pinned | 2026-07-29 |
| Clone path | `oracle/MassQueryLanguage/` (gitignored — the SHA is the record, not the source) |

**This SHA is the definition of "MassQL-compliant" for this spike** and gets published in the `massql-java`
README ([Tech_Step13](../Tech_Step13.md) §1). HEAD had already moved past the tag when we pinned, so pinning the
SHA rather than the tag or branch was load-bearing, not pedantry.

## Environment

| | |
|---|---|
| Interpreter | **Python 3.12.0** via pyenv (`~/.pyenv/versions/3.12.0/bin/python`) |
| Venv | `oracle/.venv/` (gitignored) |
| Install | `pip install -e oracle/MassQueryLanguage` |
| Freeze | `oracle/requirements.freeze.txt` — 28 packages, verbatim, unedited |

> ⚠ The `python3` shim on this machine resolves to **3.13**. Always name the 3.12 interpreter explicitly;
> `python3 -m venv` silently produces the wrong one. `oracle/venv-setup.sh` asserts the version.

Key resolved versions: `lark-parser 0.12.0`, `pandas 3.0.5`, `numpy 2.5.1`, `pyteomics 5.0.1`,
`py-expression-eval 0.3.14`, `pyarrow 25.0.0`, `tqdm 4.70.0`.

## Verified counts — measured, not assumed

| Artifact | SPIKE.md claim | **Actual** | Status |
|---|---|---|---|
| [`msql.ebnf`](msql.ebnf) | 165 lines | **165 lines** | ✅ confirmed |
| `msql_fileloading.py` | 892 lines | **892 lines** | ✅ confirmed |
| `tests/reference_parses/` | 47 files | **46 files** | ⚠ **corrected** |
| ↳ of which `scaninfo` queries | — | **35** | Tech_Step4 conformance subset |
| ↳ non-`scaninfo` | — | **11** | must parse-or-reject cleanly |
| `tests/test_query.py` | — | 989 lines, **80 test functions** | porting source for Tech_Step9 |
| Unused mzML loaders | 3 | **2** | ⚠ minor, see [`NOTES_fileloading.md`](NOTES_fileloading.md) |

**The reference-parse corpus is 46, not 47.** [Tech_Step4](../Tech_Step4.md) must assert 46, and its
`ParseConformanceTest` must fail if the count drifts.

## Golden reproduction status

`bash oracle/reproduce-goldens.sh` — exits 0 only when all three diffs are empty.

| Fixture | Query | Golden | Records | Status |
|---|---|---|---|---|
| `data/PlusRise.mgf` | `test.massql` | `output/plusrise_results.json` | **664** | ✅ byte-identical, unchanged |
| `data/small.mzML` | `test_mzml.massql` | `output/small_mzml_results.json` | **6** | ✅ byte-identical **at the documented 20 ppm default** — regenerated, see below |
| `data/small.mzML` | `test_mzml.massql` | `output/small_mzml_tol60_results.json` | **6** | ✅ byte-identical at `--precursor-tol-ppm 60` — the original bytes, preserved |

### ⚠ Why `small_mzml_results.json` was regenerated

The originally checked-in `output/small_mzml_results.json` **could not be reproduced at the documented default
tolerance of 20 ppm.** 10 of its 12 columns reproduced bit-identically; only `ms1_i` and `ms1_precmz` differed,
on 4 of 6 records.

The cause was not an environment or pin problem — the arithmetic at 20 ppm is correct. The golden's matched MS1
peaks sit **26–55 ppm** away from their `precmz`:

| scan | `precmz` | golden `ms1_precmz` | distance | ppm required |
|---|---|---|---|---|
| 3 | 810.79 | 810.8182 | 0.0282 | 34.8 |
| 10 | 810.75 | 810.7273 | 0.0227 | 28.0 |
| 17 | 811.41 | 811.4546 | 0.0446 | **54.9** |
| 24 | 810.84 | 810.8182 | 0.0218 | 26.9 |
| 37 | 810.73 | 810.7273 | 0.0027 | 3.3 |
| 44 | 810.82 | 810.8182 | 0.0018 | 2.2 |

So the original was generated with an **unrecorded** non-default `--precursor-tol-ppm`. The exact value is
unrecoverable: MS1 peak spacing in this region is ~0.0908 Da (~112 ppm), so **any** tolerance above ~56 ppm
behaves identically to unbounded. Both 60 and 100 reproduce it byte-for-byte.

Resolution (decided 2026-07-29): regenerate at the documented **20 ppm** default so the primary golden is
reproducible from documented parameters, and preserve the original bytes as
`small_mzml_tol60_results.json`. Nothing was lost — the 60 ppm output is byte-identical to the original file.

### ⚠ Why `small_mzml_ms1_results.json` was regenerated (Correction C40)

The MS1DATA golden shipped **9 keys** where the published contract
([cytoscape/cytoscape#26](https://github.com/cytoscape/cytoscape/issues/26)) specifies **one uniform 12-key
union** discriminated by `mslevel`, with no key ever absent. Two separate defects in `massql_query.py`:

1. **`base_peak_i` / `base_peak_mz` came back `null` for every MS1 row — a left-join artifact.** The script
   computed base peaks from `ms2_df` and merged `how="left", on="scan"`. An MS1DATA query's `results_df` holds
   MS1 scan ids while `ms2_df` holds only MS2 ids; in `small.mzML` those sets are **disjoint**
   (`[1,2,8,9,…]` vs `[3,4,5,…]`), so every row missed the join.

   **Proof this was an artifact and not a rule about MS1 spectra:** in `micro.mgf` the phantom MS1 placeholder
   id (`3`) **collides** with a real MS2 id (`3`), and the identical join then attaches an unrelated MS2 scan's
   base peak to the MS1 row — a *wrong non-null*. The output depended on scan-id collision. Issue #26 marks
   `base_peak_i` *"Can be null? **No**"*; a survey scan plainly has a base peak.

   Fixed by selecting the peak frame from the query's own level: `ms1_df` for MS1DATA, `ms2_df` otherwise —
   *MS1 ids join only to MS1 data.*

2. **`precmz` / `ms1scan` / `charge` were omitted rather than emitted as `null`.** MassQL's `ms1_df` carries no
   such columns, so they never reached the output. The script now reindexes every result onto the frozen 12-key
   list, which both adds the missing keys as `null` and **pins the key order**.

**Verification.** The new base peaks were cross-checked against a value proven by a *different* golden:
`max(i)` over MS1 scan 2 is `183838.71875`, exactly the `ms1_base_peak_i` that `small_mzml_results.json`
independently reports for MS2 scan 3 — same computation, one verified data point, so MS1 scan 1's
`base_peak_i = 1471224.875` is trustworthy. A full `generate-all.sh` run confirms **only this golden changed**;
the other 14 and all 16 parity dumps are byte-identical.

The three `ms1_*` columns remain `null` for MS1 rows and that is correct — a survey scan has no precursor.

[`RESULT_SCHEMA.md`](../../RESULT_SCHEMA.md) is now the single definition of the contract; this directory's own
[`RESULT_SCHEMA.md`](../../RESULT_SCHEMA.md) is superseded in place and points there.

### ⚠ The deliberate divergences from stock MassQL in `massql_query.py`

`massql_query.py` says *"This is the one deliberate divergence from stock massql behaviour in this script; see
oracle/PINNED.md"* — and **this file carried no such record until C40**, so the pointer resolved to nothing.
(Same shape as the [`VENDORED.md`](../../VENDORED.md) gap C38 found: a source comment deferring to a document that does not carry
the record.) There are now **two**, both recorded here:

| # | Divergence | Why |
|---|---|---|
| 1 | **mzXML `ms1scan` coerced `str` → `int`** (`pd.to_numeric(...).astype("Int64")`) | MassQL's mzXML loader assigns `spectrum["id"]`, which pyteomics returns as a **string**, while `ms1_df["scan"]` is `int64`. `ms1_base.get('2')` → `None` and `ms1_df["scan"] == '2'` → 0 rows, so **all three `ms1_*` columns come back null for ANY mzXML input** and the `0`→null sentinel silently no-ops on a str/int comparison. mzML is unaffected. Without this the mzXML goldens would be meaningless. Java readers parse `num="2"` to an int natively, so the SDK needs no equivalent. |
| 2 | **Base peaks read from the query's own level** (C40, above) | Stock behaviour would leave `base_peak_i`/`base_peak_mz` null for every MS1DATA row, violating the published schema's *"Can be null? No"*. |

Both exist so the **goldens are meaningful**, which is the whole purpose of this oracle. Neither changes MassQL's
own filtering or peak decoding — the parity gate (Tech_Step8) still compares against `msql_fileloading.load_data`
output untouched.

**This turned out to improve coverage.** The 20 ppm golden now has 4 records where `ms1_i`/`ms1_precmz` are null
while `ms1_base_peak_i` is **populated** — which is exactly the rule
[Tech_Step10](../Tech_Step10.md) §3.2 flags as easy to get wrong ("a tolerance miss nulls only `ms1_i` and
`ms1_precmz`"), and it previously had no golden coverage at all. The 60 ppm golden covers the successful-match
path on all 6 records. [Tech_Step12](../Tech_Step12.md) should diff both.

**Do not "fix" either golden to match a future run.** A golden edited to match the code has no evidentiary
value; that is the whole point of this gate.

## Extracted artifacts

| Path | From |
|---|---|
| `oracle/reference_parses/` | `tests/reference_parses/` — 46 files |
| `oracle/test_query_py_reference.py` | `tests/test_query.py` |
| [`msql.ebnf`](msql.ebnf) | `massql/msql.ebnf` — [Step 4](../Tech_Step4.md)'s translation source |
| [`NOTES_fileloading.md`](NOTES_fileloading.md) | Written from reading `massql/msql_fileloading.py` |

## Corrections to SPIKE.md discovered in this step

See [`NOTES_fileloading.md`](NOTES_fileloading.md) for full detail and source lines.

- **C6 — MGF `charge` defaults to 1, not null.** SPIKE.md §3's population table is wrong. The live pyteomics MGF
  loader uses `params.get('charge', [1])` and falls back to `1` on any parse failure, so **MGF `charge` is never
  null**. Golden confirms: `{1: 653, 2: 10, 3: 1}`, zero nulls. Affects Tech_Step6 §2 and Tech_Step10 §6.
- **C7 — MGF scan id** = `SCANS=` when present, else the 1-based block index (`:177`).
- **C8 — MGF polarity is not read** on the live path.
- Corpus is 46 files, not 47; 2 unused mzML loaders, not 3.
