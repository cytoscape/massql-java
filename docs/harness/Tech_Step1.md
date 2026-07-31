# Tech Step 1 — Restore the Python oracle

> **⛔ THIS STEP IS A GATE.** If the two checked-in goldens do not reproduce float-identically, **stop and
> escalate**. Do not adjust the goldens to match, and do not proceed to any other step that consumes them.

## Goal

Stand up a pinned, reproducible Python MassQL installation on this machine and prove it regenerates the two
existing golden result files byte-for-byte — establishing the fixed yardstick every later step is measured
against.

## Prerequisites

**None.** This step is the root of the dependency graph. [Step 3](Tech_Step3.md) (Java scaffold) is independent
and may run in parallel.

## Context

`massql_query.py`, `RESULT_SCHEMA.md` and `output/*_results.json` are checked in, but nothing on this machine
can currently regenerate them — there is no `MassQueryLanguage` clone and `massql`/`lark`/`pandas` are not
importable from any interpreter present. Those two JSON files (6 and 664 records) are the behavioural contract
for the entire spike, and right now they are orphaned artifacts of unknown provenance. Until they reproduce, no
Java output can be called correct or incorrect.

Governing sections: `SPIKE.md` §7 Step 0, §12.

## Scope

**In scope**
- Pinned clone of `mwang87/MassQueryLanguage` at a specific commit SHA.
- Python 3.12 virtual environment with a captured, verbatim dependency freeze.
- Reproduction of `output/small_mzml_results.json` and `output/plusrise_results.json`.
- Extraction of the 46 reference parse goldens and `tests/test_query.py` from the clone.
- A close read of `massql/msql_fileloading.py`, recorded as notes for Steps 6 and 7.

**Out of scope**
- Generating any *new* golden, converting or downloading any fixture, and the loader-parity dumps — all owned
  by [Step 2](Tech_Step2.md).
- Any Java work — [Step 3](Tech_Step3.md) onward.
- Changing `massql_query.py`. It is the reference implementation; treat it as read-only.

## Deliverables

| Path | Content |
|---|---|
| `oracle/MassQueryLanguage/` | The pinned clone. Add to `.gitignore`; the SHA is what gets recorded, not the source. |
| `oracle/PINNED.md` | The commit SHA, the tag it came from, clone URL, and the date pinned. |
| `oracle/requirements.freeze.txt` | `pip freeze` output, verbatim and unedited. |
| `oracle/venv-setup.sh` | Reproducible re-creation of the venv from scratch. |
| `oracle/reproduce-goldens.sh` | Re-runs both queries and diffs against the checked-in goldens; non-zero exit on any difference. |
| `oracle/reference_parses/` | The 46 golden parse files (35 scaninfo, 11 non-scaninfo), copied out of the clone. |
| `oracle/test_query_py_reference.py` | Copy of the clone's `tests/test_query.py`, for porting in [Step 9](Tech_Step9.md). |
| `oracle/NOTES_fileloading.md` | Your findings from reading `msql_fileloading.py` (see Specification §5). |

## Specification

### 1. Clone and pin

```
git clone https://github.com/mwang87/MassQueryLanguage oracle/MassQueryLanguage
cd oracle/MassQueryLanguage
git checkout dad2a28c01e6e5132240270fc6700fbae29f1652
git rev-parse HEAD          # must echo dad2a28c01e6e5132240270fc6700fbae29f1652
```

`dad2a28c01e6e5132240270fc6700fbae29f1652` is tag `2026.03.14`. **Pin the SHA, not the tag or branch** — the
default HEAD is `17e8c74`, already past this tag, and tags are mutable. That SHA is the definition of
"MassQL-compliant" for this spike and gets published in the README ([Step 13](Tech_Step13.md)).

Record all of it in `oracle/PINNED.md`.

### 2. Virtual environment

**Name the interpreter explicitly.** The `python3` shim on this machine resolves to 3.13; MassQL is pinned
against 3.12. `python3.12` is available via pyenv but is not on the default shim path.

```
pyenv shell 3.12.0            # or: ~/.pyenv/versions/3.12.0/bin/python -m venv ...
python -V                     # must print Python 3.12.x
python -m venv oracle/.venv
oracle/.venv/bin/pip install -e oracle/MassQueryLanguage
oracle/.venv/bin/pip freeze > oracle/requirements.freeze.txt
```

Do **not** hand-edit, sort or prune the freeze file. Its value is being an exact record of what produced the
goldens, including transitive pins.

Sanity check before proceeding:

```
oracle/.venv/bin/python -c "import massql, lark, pandas, pyteomics; print('ok')"
```

### 3. Reproduce the goldens — the gate

Two (fixture, query) pairs already have checked-in goldens:

| Fixture | Query file | Golden | Records |
|---|---|---|---|
| `data/small.mzML` | `test_mzml.massql` | `output/small_mzml_results.json` | **6** |
| `data/PlusRise.mgf` | `test.massql` | `output/plusrise_results.json` | **664** |

Run each and diff. Note that `massql_query.py` writes JSON to **stdout** and progress to **stderr**, so a plain
redirect captures exactly the comparable payload:

```
oracle/.venv/bin/python massql_query.py data/small.mzML  test_mzml.massql > /tmp/small_mzml.json
oracle/.venv/bin/python massql_query.py data/PlusRise.mgf test.massql      > /tmp/plusrise.json
diff /tmp/small_mzml.json output/small_mzml_results.json
diff /tmp/plusrise.json   output/plusrise_results.json
```

Both diffs must be **empty**. `massql_query.py` emits `indent=2` with Python's default float repr, so a
byte-identical diff is the correct expectation — not an approximate comparison.

**Spot-check anchors.** The first record of `output/small_mzml_results.json` (at the **20 ppm default**, after the
regeneration described in §3a) must be exactly:

```json
{ "scan": 3, "precmz": 810.79, "ms1scan": 2, "rt": 0.011218333333333334, "charge": null,
  "tic": 586278.875, "mslevel": 2, "base_peak_i": 161140.859375, "base_peak_mz": 736.6370849609375,
  "ms1_i": null, "ms1_precmz": null, "ms1_base_peak_i": 183838.71875 }
```

The same record in `output/small_mzml_tol60_results.json` differs in exactly two fields:
`"ms1_i": 131528.0625, "ms1_precmz": 810.8182000219822`.

Both files' 6 records carry `ms1scan` = **2, 9, 16, 23, 36, 43**. The first record of
`output/plusrise_results.json` must be `scan` 576, `precmz` 161.0209, `ms1scan` **null**, `rt` **0.0**,
`charge` 1, `tic` 1299900.0, `base_peak_i` 230000.0, `base_peak_mz` 162.1122, and all three `ms1_*` **null**.

Wrap this in `oracle/reproduce-goldens.sh`, exiting non-zero on any difference, so later steps and CI can
re-assert the yardstick cheaply.

**If either diff is non-empty: STOP.** Capture the actual output, the freeze file and the diff, and escalate.
Likely causes, in order of likelihood: wrong commit pinned; a transitive dependency (pandas or pyteomics) whose
float formatting or `groupby` ordering differs from what produced the goldens; a different Python minor
version. Resolve by moving the *environment*, never by regenerating the goldens — a golden edited to match the
current run has zero evidentiary value.

### 3a. ⚠ What actually happened on the first run — the tolerance finding

Recorded here because it is the reason `output/` now holds two mzML goldens, and because the failure mode was
none of the three anticipated above.

`PlusRise.mgf` reproduced byte-identically (664/664) on the first attempt, so the environment was sound. But
`small.mzML` reproduced **only at `--precursor-tol-ppm ≥ 60`**, not at the documented default of 20. Ten of its
twelve columns were bit-identical; only `ms1_i` and `ms1_precmz` differed, on 4 of 6 records.

The 20 ppm arithmetic was *correct* — the golden's matched MS1 peaks simply sit 26–55 ppm from their `precmz`:

| scan | `precmz` | golden `ms1_precmz` | distance | ppm required |
|---|---|---|---|---|
| 3 | 810.79 | 810.8182 | 0.0282 | 34.8 |
| 10 | 810.75 | 810.7273 | 0.0227 | 28.0 |
| 17 | 811.41 | 811.4546 | 0.0446 | **54.9** |
| 24 | 810.84 | 810.8182 | 0.0218 | 26.9 |
| 37 | 810.73 | 810.7273 | 0.0027 | 3.3 |
| 44 | 810.82 | 810.8182 | 0.0018 | 2.2 |

So the original file had been generated with an **unrecorded** non-default tolerance. The exact value is
unrecoverable: MS1 peak spacing there is ~0.0908 Da (~112 ppm), so any tolerance above ~56 ppm behaves
identically to unbounded — 60 and 100 both reproduce it byte-for-byte.

**Resolution:** regenerate at the documented 20 ppm so the primary golden is reproducible from documented
parameters, and preserve the original bytes as `output/small_mzml_tol60_results.json`. Nothing was lost — the
60 ppm output is byte-identical to the original file.

This *improved* coverage. The 20 ppm golden now has 4 records where `ms1_i`/`ms1_precmz` are null while
`ms1_base_peak_i` is **populated** — exactly the rule [Step 10](Tech_Step10.md) §3.2 flags as easy to get wrong,
which previously had no golden coverage at all. The 60 ppm golden covers the successful-match path on all 6.
[Step 12](Tech_Step12.md) diffs both.

**The general lesson, for any future golden:** a golden must record the non-default flags it was generated with.
`oracle/reproduce-goldens.sh` now encodes the flag per golden, so this class of drift cannot recur silently.

### 4. Extract the parse goldens and property tests

```
cp -R oracle/MassQueryLanguage/tests/reference_parses/ oracle/reference_parses/
cp    oracle/MassQueryLanguage/tests/test_query.py     oracle/test_query_py_reference.py
```

**Count the reference parse files and record the number.** `SPIKE.md` asserts 47; if the pinned commit has a
different count, the real number is authoritative and [Step 4](Tech_Step4.md)'s conformance count must be
updated to match. Report the discrepancy rather than silently accepting either figure.

> **✅ RESOLVED (2026-07-29): the corpus is 46 files, not 47** — 35 `scaninfo` queries and 11 non-`scaninfo`.
> [Step 4](Tech_Step4.md) asserts 46. `oracle/PINNED.md` carries the counts.

Also copy `massql/msql.ebnf` to `oracle/msql.ebnf` — it is [Step 4](Tech_Step4.md)'s translation source.
`SPIKE.md` describes it as 165 lines; verify and record the actual length.

> **✅ RESOLVED: `msql.ebnf` is 165 lines**, as claimed. `msql_fileloading.py` is 892 lines, as claimed.

### 5. Read `msql_fileloading.py` and write up the findings

892 lines, and it is **the authoritative reader specification** for Steps 6 and 7. Read it before either of
those steps starts, and record answers to these questions in `oracle/NOTES_fileloading.md`. Each is a claim
`SPIKE.md` makes that the specs depend on — confirm or correct it from source:

| Claim to verify | Where |
|---|---|
| Only `_load_data_mzML_pyteomics` is live; three other mzML loaders are dead code | dispatch at `:103`, loader at `:525-650` |
| mzXML loader uses `spectrum["retentionTime"]` as-is, pyteomics having already converted to minutes | `:442`, `:463` |
| mzML RT converts **only if** the declared unit is seconds | `:564-571` |
| MGF hardcodes `ms1scan = 0` | `:394` |
| MGF `rt` = `float(RTINSECONDS)/60.0`, default `0` when absent | `:179-181`, `:327-328` |
| mzXML polarity `"+"`→1, `"-"`→2 | `:517-523` |
| **`spectrumRef` and `precursorScanNum` appear ZERO times in the whole file**; every loader instead tracks `previous_ms1_scan` initialized to `0` | grep the whole file |

That last row is the highest-risk finding in the spike — [Step 6](Tech_Step6.md) and
[Step 7](Tech_Step7.md) both depend on it. Verify it with an actual grep and paste the (empty) result into the
notes:

```
grep -n "spectrumRef\|precursorScanNum" oracle/MassQueryLanguage/massql/msql_fileloading.py
grep -n "previous_ms1_scan" oracle/MassQueryLanguage/massql/msql_fileloading.py
```

Also note the pyteomics files that settle conversion questions the API docs leave open:
`pyteomics/mzxml.py` (`_determine_dtype`, `_decode_peaks`) and `pyteomics/xml.py:118-143`
(`XMLValueConverter.duration_str_to_float`, the ISO-8601 → **minutes** conversion). Both are inside
`oracle/.venv/lib/python3.12/site-packages/` once installed.

## Known traps

- **The `python3` shim is 3.13.** `python3 -m venv` silently gives you the wrong interpreter and MassQL may
  install and even run, producing subtly different floats. Assert `python -V` inside the venv.
- **Editing a golden to make a diff pass destroys the spike's only ground truth.** If they disagree, the
  environment is wrong, not the golden.
- **Don't compare parsed JSON when you can compare bytes.** Round-tripping through `json.load` hides exactly
  the float-formatting differences this gate exists to catch.
- **`pip install -e` against the clone, not `pip install massql`** from PyPI — the PyPI build may not correspond
  to the pinned SHA.

## Tests required

This step predates the Java test suite; its "tests" are scripts:

- `oracle/reproduce-goldens.sh` — exits 0 only if both diffs are empty. Re-runnable, and re-run by
  [Step 2](Tech_Step2.md) after any fixture work.
- `oracle/venv-setup.sh` — re-creates the environment from scratch on a clean machine and asserts the
  interpreter version and the pinned SHA.

## Done when

- [x] `git rev-parse HEAD` in the clone prints `dad2a28c01e6e5132240270fc6700fbae29f1652`.
- [x] `oracle/.venv/bin/python -V` prints 3.12.x (3.12.0).
- [x] `oracle/requirements.freeze.txt` exists and is unedited (28 packages).
- [x] `bash oracle/reproduce-goldens.sh` exits **0** — all three diffs empty: 664, 6 and 6 records.
- [x] `oracle/reference_parses/` populated; the actual count (**46**, not 47) recorded in `oracle/PINNED.md`.
- [x] `oracle/msql.ebnf` copied; line count recorded (**165**, as claimed).
- [x] `oracle/NOTES_fileloading.md` answers all seven verification rows, including the pasted grep output
      proving `spectrumRef`/`precursorScanNum` are absent.
- [x] `bash oracle/venv-setup.sh` re-runs clean and idempotent, asserting interpreter, SHA and both counts.

**✅ STEP 1 COMPLETE — 2026-07-29. Gate green.**

## References

- `SPIKE.md` §7 Step 0 (this step's origin), §12 (reference material)
- `massql_query.py` — the reference implementation; `add_precursor_intensity` at lines 62–116 is the contract
  for [Step 10](Tech_Step10.md)
- `RESULT_SCHEMA.md` — the 12-column result contract
- `github.com/mwang87/MassQueryLanguage` @ `dad2a28c01e6e5132240270fc6700fbae29f1652`
- Corrections C1–C5 and Established facts in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)
