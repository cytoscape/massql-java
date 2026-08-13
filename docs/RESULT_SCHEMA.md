# Result schema — the frozen 12-key `scaninfo` contract

**This file is the single definition of the result contract.** Every other document links here rather than
restating it. 

---

## One shape, always

`ResultJson` emits a **JSON array** of objects. Every object carries **these 12 keys, in this order**:

```
scan, precmz, ms1scan, rt, charge, tic, mslevel, base_peak_i, base_peak_mz, ms1_i, ms1_precmz, ms1_base_peak_i
```

```json
{
  "scan": 576, "precmz": 161.0209, "ms1scan": null, "rt": 0.0, "charge": 1,
  "tic": 1299900.0, "mslevel": 2, "base_peak_i": 230000.0, "base_peak_mz": 162.1122,
  "ms1_i": null, "ms1_precmz": null, "ms1_base_peak_i": null
}
```

> ### ⛔ `scaninfo(MS1DATA)` and `scaninfo(MS2DATA)` emit the SAME keys
>
> Issue #26 defines the schema as *"a union of all possible attributes from ms1 and ms2"*, with **`mslevel`**
> as the discriminator: `2` for `MS2DATA`, `1` for `MS1DATA`. **There is no second, smaller shape, and no key
> is ever absent.** A field that does not apply to a row is present with the value `null`.

>
> A consumer may write this string into
> a table column verbatim and read back later. One stable key set means that round-trip is
> a fixed-arity loop with no branch on query type; a variable key set makes every consumer discriminate before
> it can read a row. Absent-vs-null is a contract difference, not a formatting choice.

An empty result is `[]`.

## Field definitions

The **MS1DATA** column states what each field means for a survey-scan row,
which the issue's MS2-centric wording leaves implicit.

| Key | Type | Description | Can be null? | MS1DATA (`mslevel` 1) |
|---|---|---|---|---|
| `scan` | int | The scan number — the unique identifier of the spectrum that matched the query. | **No** | the MS1 scan's own id |
| `precmz` | double | The precursor m/z (the intact molecule that was isolated and fragmented), as recorded in the MS2 scan's metadata. | Yes — the file recorded no precursor m/z | **always `null`** — a survey scan has no precursor |
| `ms1scan` | int | The scan number of the MS1 survey scan the precursor was selected from (the link back to full-scan data). | Yes — no linked MS1 survey scan (e.g. **MGF** input) | **always `null`** — an MS1 scan is not selected from another |
| `rt` | double | Retention time of the scan, in **minutes**. Passed through exactly as MassQL reports it — **not** null-converted, since `0.0` can be genuine. | **No** | the MS1 scan's own rt |
| `charge` | int | Precursor charge state. | Yes — the file specified no charge | **always `null`** |
| `tic` | double | Total ion current of the scan — the **sum of the intensities of all peaks** in this spectrum. (MassQL's `i`, renamed.) | **No** | sum over the MS1 scan's own peaks |
| `mslevel` | int | MS level of the reported scan: `2` for `scaninfo(MS2DATA)`, `1` for `scaninfo(MS1DATA)`. **The discriminator.** | **No** | `1` |
| `base_peak_i` | double | Absolute intensity of the **strongest peak** (the base peak) in this scan. | **No** | the MS1 scan's own base peak — **a real value, not null** |
| `base_peak_mz` | double | The m/z of that strongest peak. | **No** | as above |
| `ms1_i` | double | Absolute intensity of the **precursor peak** as measured in its MS1 survey scan (the peak near `precmz` in scan `ms1scan`). | Yes — no MS1 data (**MGF**), or the nearest MS1 peak to `precmz` is beyond `precursorTolPpm` | **always `null`** |
| `ms1_precmz` | double | The measured m/z of that matched MS1 peak — the real centroid, which usually sits within a few ppm of the reported `precmz`. | Yes — same conditions as `ms1_i` | **always `null`** |
| `ms1_base_peak_i` | double | Intensity of the **tallest peak in the entire MS1 survey scan** — the normalization reference; relative precursor abundance = `ms1_i / ms1_base_peak_i`. | Yes — no linked MS1 scan (**MGF**); ⚠ **a tolerance miss does NOT null it** (only `ms1_i` / `ms1_precmz`) | **always `null`** |

> ⚠ **`base_peak_i` / `base_peak_mz` are "Can be null? **No**" — including for MS1DATA.** A survey scan
> plainly has a base peak. The `small_mzml_ms1_results.json` golden used to emit `null` here, which was a
> **left-join artifact** in `massql_query.py`, not semantics: it computed base peaks from `ms2_df` and merged on
> `scan`, so MS1 scan ids missed the join entirely. Proof it was an artifact rather than a rule — in
> `micro.mgf` the phantom MS1 id (`3`) *collides* with a real MS2 id (`3`), and the same join attached an
> unrelated MS2 scan's base peak to the MS1 row: a **wrong non-null**. Fixed in the wrapper.
>
> The rule the fix encodes: **MS1 ids join only to MS1 data, MS2 ids only to MS2 data.**

## MS1 data is optional in the source file

The three `ms1_*` columns and `ms1scan` describe a link to full-scan data that **many inputs simply do not
have**. That is a supported, expected outcome — not a degraded result:

- **MGF is an MS2-only peak-list format.** It has no survey scans at all, so `ms1scan` and all three `ms1_*`
  columns are `null` for every row. `plusrise_results.json`'s 664 rows are exactly this shape.
- **mzML / mzXML** populate them, *provided* a precursor peak matches within `precursorTolPpm`. A tolerance
  too tight for a coarsely-recorded `precmz` still yields `null` even though MS1 data is present — 4 of the 6
  rows in `small_mzml_results.json` are that case at the default 20 ppm.

A consumer must therefore treat every nullable column as genuinely optional and never infer failure from it.

## Population by input format

| Key | MGF (MS2 only) | mzML / mzXML (MS1 + MS2) |
|---|---|---|
| `scan` | ✔ | ✔ |
| `precmz` | ✔ (from `PEPMASS=`) | ✔ |
| `ms1scan` | **null** — no survey scans exist | ✔ by **document order**, never the file's own `spectrumRef` / `precursorScanNum` |
| `rt` | **`0.0`, not null** | ✔ |
| `charge` | ⚠ **never null** — `CHARGE=` if present, else **`1`** (an absent `CHARGE=` defaults to 1, not null) | ✔ if recorded, else null via the `0` sentinel. ⚠ **mzXML's absent default is `0`, not MGF's `1`** (`msql_fileloading.py:451`); `DP00570_F02.mzxml` carries **zero** `precursorCharge` attributes, so every row from it is null — a **predicted difference**, not a shared column |
| `tic` | ✔ sum of MS2 fragment intensities | ✔ |
| `mslevel` | `2` | `2` for MS2DATA, `1` for MS1DATA |
| `base_peak_i` / `base_peak_mz` | ✔ | ✔ |
| `ms1_i` | **null** | ✔ if `ms1scan` resolved **and** a peak matches within `precursorTolPpm` |
| `ms1_precmz` | **null** | ✔ same condition — the *measured* centroid |
| `ms1_base_peak_i` | **null** | ✔ whenever the linked MS1 scan exists — **a tolerance miss does not null it** |


**Only 7 of the 12 keys come from MassQL.** The other 5 are computed.

| Source | Keys |
|---|---|
| MassQL `scaninfo` native | `scan`, `precmz`, `ms1scan`, `rt`, `charge`, `tic` (its `i`, renamed), `mslevel` |
| **Computed by the SDK** | `base_peak_i`, `base_peak_mz` — per-scan argmax over the scan's own peaks, ties → lowest row index (matching pandas `idxmax`) |
| **Computed by the SDK** | `ms1_i`, `ms1_precmz`, `ms1_base_peak_i` — the precursor lookup in the linked MS1 scan |

The precursor lookup's exact rules — **closest to `precmz`, not most intense**; `ms1_base_peak_i` surviving a
tolerance miss; ties → lower m/z; and the **inclusive** m/z window — live in `PrecursorLookup`, which is
where they are implemented and tested.


