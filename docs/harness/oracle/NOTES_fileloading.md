# `msql_fileloading.py` — verification notes

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

Findings from reading `massql/msql_fileloading.py` at pinned SHA `dad2a28c01e6e5132240270fc6700fbae29f1652`.
**892 lines**, matching SPIKE.md's claim. This file is the authoritative reader specification for
[Tech_Step6](../Tech_Step6.md) and [Tech_Step7](../Tech_Step7.md).

Each row of the verification table from [Tech_Step1](../Tech_Step1.md) §5, confirmed or corrected from source.

---

## ✅ CONFIRMED — `spectrumRef` / `precursorScanNum` appear ZERO times

The highest-risk rule in the spike. Verified by grep over the whole file:

```
$ grep -n "spectrumRef\|precursorScanNum" massql/msql_fileloading.py
(no output — zero matches)
```

MassQL never reads the file's own declared precursor→survey linkage. Instead **every** loader tracks
`previous_ms1_scan`, initialized to `0`:

```
417:    previous_ms1_scan = 0            # _load_data_mzXML
447:                    previous_ms1_scan = spectrum["id"]
465:                    peak_dict["ms1scan"] = previous_ms1_scan
533:    previous_ms1_scan = 0            # _load_data_mzML_pyteomics  (LIVE)
600:                previous_ms1_scan = scan
617:                all_msn_ms1scan += len(mz) * [previous_ms1_scan]
674:    previous_ms1_scan = 0            # _load_data_mzML2           (unused)
791:    previous_ms1_scan = 0            # _load_data_mzML            (unused)
```

**`ms1scan` is inferred by position in the file, never read from it.** Tech_Step6 §4 and Tech_Step7 stand as
written.

## ✅ CONFIRMED — only `_load_data_mzML_pyteomics` is live

```
101:        #ms1_df, ms2_df = _load_data_mzML(input_filename)          <- commented out
102:        #ms1_df, ms2_df = _load_data_mzML2(input_filename)         <- commented out
103:        ms1_df, ms2_df = _load_data_mzML_pyteomics(input_filename) <- LIVE
```

Loader definitions: `_load_data_mzXML` :414, **`_load_data_mzML_pyteomics` :525 (live)**,
`_load_data_mzML2` :652, `_load_data_mzML` :777.

> **⚠ Minor correction to SPIKE.md §12.** It says *"three unused mzML loaders remain in the file."* There are
> **two** (`_load_data_mzML2`, `_load_data_mzML`). Immaterial, but read the right one: **`:525-650`**.

## ✅ CONFIRMED — mzML RT converts only if the declared unit is seconds

`:562-573`:

```python
try:
    rt = spectrum["scanList"]["scan"][0]["scan start time"]
except:
    rt = 0
# Correcting the unit
try:
    if spectrum["scanList"]["scan"][0]["scan start time"].unit_info == "second":
        rt = rt / 60
except:
    pass
```

A **conditional** on `unit_info`, and note the bare `except: pass` — an unreadable unit silently leaves `rt`
unconverted. `data/small.mzML` declares `minute`, so it passes through. Tech_Step6 §3 stands.

## ✅ CONFIRMED — mzXML uses `retentionTime` as-is, pyteomics having already converted to minutes

```
442:                    peak_dict["rt"] = spectrum["retentionTime"]
463:                    peak_dict["rt"] = spectrum["retentionTime"]
```

No conversion in MassQL. The conversion happens inside pyteomics —
`pyteomics/xml.py:126` `XMLValueConverter.duration_str_to_float`:

```python
minutes += hours * 60.
minutes += (seconds / 60.)
```

So the ISO-8601 duration becomes **minutes**, with `H`/`M`/`S` components all handled. `PT90S` → `1.5`.
Tech_Step7 §4's "always convert, handle H/M" rule is exactly right.

## ✅ CONFIRMED — mzXML polarity `"+"`→1, `"-"`→2

`_determine_scan_polarity_mzXML`, `:517-523`. Default `0` when neither.

## ✅ CONFIRMED — MGF `ms1scan = 0` hardcoded

`:394`, inside the per-peak dict:

```python
peak_dict["ms1scan"] = 0
```

Confirmed end-to-end: all 664 records of `output/plusrise_results.json` have `ms1scan: null`.

## ✅ CONFIRMED — MGF `rt` = `RTINSECONDS`/60, default 0

Two places, both `/60.0`:

```
:179-182   rt = float(params.get('rtinseconds', 0)) / 60.0   (except -> rt = 0.0)
:328       current_params["rt"] = float(value) / 60.0
```

Confirmed end-to-end: all 664 golden records have `rt: 0.0` — **not null**. `PlusRise.mgf` records no
retention time, and `0.0` is what MassQL reports. Tech_Step6 §2 and Tech_Step10 §4 stand.

---

# ⚠ CORRECTIONS to SPIKE.md §3, discovered while reading

## C6 — MGF `charge` defaults to **1**, not to null

**SPIKE.md §3's population table says MGF `charge` is *"✔ if `CHARGE=` present, else null."* That is wrong.**

`.mgf` dispatches to `_load_data_mgf` (`:145`), which tries pyteomics first and only falls back to the manual
parser if it yields nothing:

```python
def _load_data_mgf(input_filename):
    ms1_df, ms2_df = _load_data_mgf_pyteomics(input_filename)
    if len(ms2_df) == 0:                                    # fallback only on total failure
        ms1_df, ms2_df = _load_data_mgf_manual(input_filename)
    return ms1_df, ms2_df
```

`_load_data_mgf_pyteomics` is therefore **the live path**. Its charge handling, `:192-203`:

```python
charge_val = params.get('charge', [1])      # <-- default 1, not 0 and not None
...
charge = int(charge_str.strip('+'))
except:
    charge = 1                              # <-- and 1 again on any parse failure
```

Consequences for the Java reader:

- An MGF spectrum with **no `CHARGE=`** yields `charge = 1`, which is **never null-converted** — the 0→null
  rule ([Tech_Step10](../Tech_Step10.md) §4) only fires on `0`, and this is `1`.
- So **MGF `charge` is never null.** A genuine 1+ and an absent charge are indistinguishable in the output.
- Confirmed against the golden: `output/plusrise_results.json` charge counts are `{1: 653, 2: 10, 3: 1}` —
  zero nulls. Real charges *are* read when present; 1 absorbs both "1+" and "absent".

Note the third MGF path at `:388-396` (used by `_load_data_gnps_json`, not by `.mgf`) hardcodes
`peak_dict["charge"] = 1  # TODO: Add Charge Correctly here` and `polarity = 1  # TODO`. Not our path, but it
is where the "charge is always 1" instinct comes from — don't copy it for MGF.

**Action:** Tech_Step6 §2's MGF table row for `charge` and Tech_Step10 §6's population table both need this
correction. Tech_Step6's `MgfReaderTest` assertion *"absent `CHARGE` → 0 sentinel"* is **wrong as written** and
must become *"absent `CHARGE` → 1"*.

## C7 — MGF scan id = `SCANS=` param, else 1-based index

Tech_Step6 §2 left this to be derived from source. Answer, `:177`:

```python
scan = params.get('scans', index + 1)       # 'SCANS=' if present, else 1-based enumeration index
```

and in the manual fallback, `:326-327`: `elif key == "SCANS": current_params["scan"] = value`.

So: **`SCANS=` when present, otherwise the 1-based position of the block in the file.** The golden's first
record is `scan: 576`, consistent with either — `PlusRise.mgf` should be checked for `SCANS=` in
[Tech_Step2](../Tech_Step2.md) to pin which branch the fixture exercises.

## C8 — MGF `polarity` is not read at all on the live path

The pyteomics MGF loader sets no polarity from the file. Treat MGF polarity as unavailable; a `POLARITY`
condition ([Tech_Step9](../Tech_Step9.md)) cannot meaningfully filter an MGF. Verify the emitted value (0 or 1)
against the loaded dataframe in Tech_Step6 rather than assuming.

---

## Loaded dtypes — relevant to bit-identity

From the live mzML loader on `data/small.mzML`:

```
i             float32      <- intensities are float32 in the dataframe
i_norm        float32
i_tic_norm    float32
mz            float64
scan            int64
rt            float64      <- rt is float64: confirms Tech_Step5 §1's scanRt-as-double requirement
polarity        int64
```

**Intensities are `float32` in MassQL's own dataframe**, then widened to Python float on output. This is the
same widening rule as the binary decode ([Tech_Step6](../Tech_Step6.md) §3) and it corroborates that golden
intensities are `(double)(float)raw`. `rt` being `float64` while intensities are `float32` is why
[Tech_Step5](../Tech_Step5.md) §1 insists `rt` be carried as a double.

## Reference

- `massql/msql_fileloading.py` @ `dad2a28c01e6e5132240270fc6700fbae29f1652`
- `pyteomics/xml.py:126-141` — `duration_str_to_float`
- `pyteomics/mzxml.py` — `_determine_dtype`, `_decode_peaks`
