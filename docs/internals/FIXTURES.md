# Test fixtures and goldens

Everything the test suite reads lives under `src/test/resources/`. **One exception**, at the bottom.

⛔ **`Fixtures.require` throws when a fixture is absent — it never skips.** A skipped test still counts as
a test that ran, so a missing fixture would let the parity assertions and `Ms1ScanDocumentOrderIT` report
green while proving only that the code compiled. The 90% coverage gate is the backstop: a test that stops
running shows up as lost coverage.

## Layout

| Path | Contents |
|---|---|
| `data/` | The four real spectra files, one per format plus the MGF/mzXML pair |
| `fixtures/micro/` | Hand-written 5-scan files with hand-computable values, one variable each — see below |
| `fixtures/edge/` | Pathological inputs — currently MSDK's `empty_msLevel_tag.mzXML` |
| `goldens/loader-parity/` | **16 dumps.** Per scan: counts, hex intensity sum, SHA-256 of the m/z **and** intensity arrays, the leading 8 values of each as hex, `rt_hex` and `polarity` — all from MassQL's own loaded tables. The input to the parity gate, via `ReaderParityIT.FIXTURES_WITH_DUMPS` |
| `goldens/query-results/` | `scaninfo` output per fixture/query, as `{"results": [...]}`. The reference emits a bare array, so a regenerated golden must be wrapped |
| `goldens/queries/` | The `.massql` query files those goldens were produced from |
| `reference_parses/` | MassQL's own 46-file parse corpus (+ manifest) — `ParseConformanceTest` |

## The micro fixtures — one variable per file

All generated from a single explicit 5-scan table, so every expected value follows from arithmetic you
can check by hand (`fixtures/micro/EXPECTED.md`).

| File | The one thing it varies |
|---|---|
| `micro.mgf` | MGF baseline: MS2 only, no `SCANS=` → scan ids are block indices 1–3, not 1/3/5 |
| `micro_zeroint.mgf` | **zero-intensity peaks** — dropped by MGF, kept by mzML/mzXML. One block is all-zero, so it reduces to a zero-peak scan |
| `micro.mzML` | mzML baseline, `unitName="minute"` → RT **not** converted |
| `micro_rtseconds.mzML` | `unitName="second"` → RT **÷60**. The pair catches a silent 60× error |
| `micro.mzXML` | mzXML baseline: `precision="32"`, uncompressed, `network`, flat |
| `micro_p64.mzXML` | `precision="64"` — full doubles instead of `(double)(float)` |
| `micro_zlib.mzXML` | `compressionType="zlib"` |
| `micro_p64_zlib.mzXML` | both at once, so a bug in their interaction cannot hide |
| `micro_nested.mzXML` | MS2 nested **inside** its parent MS1 (`<scan>` depth 2) |
| `micro_nopolarity.mzXML` | `polarity` attribute **omitted** — non-parity, see below |
| `micro_noprecursor.mzXML` | MS2 with **no** `<precursorMz>` — non-parity, see below |
| `micro_multiprec.mzXML` | **Two** `<precursorMz>` elements; the second is a decoy. MassQL indexes `[0]`, so first wins  |
| `micro_multiprec.mzML` | Same idea, plus a second `<selectedIon>` **and** a second `<precursor>` — so honouring one nesting level but not the other still fails |
| `micro_ms1var.mzML` | **Two MS1 scans with different peaks.** The baseline's two MS1 scans are identical, so no existing fixture could tell an `MS1MZ` condition evaluated against the *right* linked MS1 scan from one evaluated against the wrong one — nor discriminate condition **order**. Every scan here has peaks, so nothing is reader-only |
| `micro_onbound.mzML` | **An MS1 peak exactly on the precursor-lookup window bound** — `499.99`, which *is* `500.0 - 500.0 * 20 / 1e6` in IEEE-754, the same bits in CPython and Java. Pins the **inclusive** half of the window rule (the precursor lookup) where `micro_mzml_edge` pins the strict half (the conditions). The reference admits the peak, so `ms1_i` is `7000.0`; an exclusive lookup yields `null`. `ms1_base_peak_i` is a *different* peak (`9000.0`), so the two cannot be conflated. Every scan has peaks, so nothing is reader-only |

## Parity coverage — which fixtures have a dump, and which cannot

All **16** fixtures with a dump are compared **bit-identically** against MassQL by the parity gate.
**Five** fixtures deliberately have none:

| Fixture | Why no dump |
|---|---|
| `micro_nopolarity.mzXML` | MassQL raises `KeyError: 'polarity'`  — verified by execution. Parity is not available; `MzxmlPolarityTest` pins this SDK's contract |
| `micro_noprecursor.mzXML` | MassQL raises `KeyError: 'precursorMz'`. `MzxmlEdgeCaseTest` pins this SDK's |
| `micro_multiprec.mzML` | Adds nothing over the mzXML twin for *peak* parity; the first-precursor rule is pinned by `MultiPrecursorTest` |
| `micro_onbound.mzML` | Same encoding as `micro.mzML` from the same generator, so it adds no decode path. Its point is the **query** layer, and it is pinned there by `DifferentialIT` — uniquely sharply: its MS1 peak sits exactly on the lookup bound, so a decode off by a single ULP flips the `>=` and turns `ms1_i` null, which the differential reports  |
| `fixtures/edge/empty_msLevel_tag.mzXML` | 8 of its 10 scans are dropped on `msLevel`. Still the only 64-bit + zlib decode of a file generated elsewhere |

The four decode variants (`micro_p64`, `micro_zlib`, `micro_p64_zlib`, `micro_nested`) carry dumps too.
Cross-fixture *equivalence* alone cannot catch an error common to both sides of a pair, so the 64-bit, zlib
and nested branches each need their own bit-identity check.

**Why the mzXML variants exist.** `micro.mzXML`, `small.mzXML` and the Ewing file are
*all* `precision="32"` / uncompressed / `network`, so the 64-bit and zlib decode branches were exercised
by nothing at all — the spec had claimed the fixtures "cover every decode path". Verified against MassQL's
own loader: zlib decodes bit-identically to uncompressed, and 32-bit vs 64-bit genuinely differ
(`123.456787109375` vs `123.456789012345`), so the widening rule is observable rather than assumed.

**Two fixtures pin this SDK's contract, not parity.** MassQL **crashes** on both — verified, not inferred:

```
micro_nopolarity.mzXML    KeyError: 'polarity'      (spec["polarity"] unguarded,:519)
micro_noprecursor.mzXML   KeyError: 'precursorMz'   (spectrum["precursorMz"][0] unguarded,:450)
```

So no golden can exist for either. The readers here give `polarity = 0` and `precmz = 0`; a passing test
there proves the behaviour is sane, **not** that it agrees with MassQL. [`READER_RULES.md`](READER_RULES.md) labels both.

## The rule: a missing fixture FAILS

`Fixtures.require` throws `AssertionError`. There is no skip path, and `FixturesContractTest` asserts
that — including a test that demonstrates, executably, that `assumeTrue` reports as a *skip* rather than
a failure. CI separately asserts the skipped-test count is **0**.

If a fixture is missing, restore it. Do not make the assertion conditional — that is the hole a hard
failure exists to close.

## Attribution — the Ewing pair is CC BY 4.0

`data/DP00570_F02.mzxml` and `data/DP00570_F02.mgf` are two views of one experiment, redistributed here
under the **Creative Commons Attribution 4.0 International** licence their source declares. They are
committed like every other fixture, so the test suite needs no network access.

> **Omics Analysis Tutorial** — developed by **Professor Rob Ewing**, with financial support from The
> University Center for Innovation in Teaching and Education (UCITE), Case Western Reserve University,
> Cleveland, Ohio.
>
> Source: <https://www.ewinglab.org/omicsanalysistutorial>  
> Licence: [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/)  
> **Both files are redistributed unmodified.**

The licence permits redistribution, including commercially, so long as that attribution travels with the
files — which is what this section is for. It also carries a disclaimer of warranties; see the licence
text at the link above.

⚠ The declaration is made for the tutorial resource as a whole, at the page that serves these files from
its own `/data/` path, rather than per file. If a stricter record is ever wanted, ask the lab to confirm
it covers the data files specifically.

## Why the Ewing mzXML matters more than its size suggests

It is **the only fixture that can distinguish document-order `ms1scan` from `precursorScanNum`
resolution.** It carries 916 scans (229 MS1 / 687 MS2), nests MS2 inside its parent MS1, and has **zero**
`precursorScanNum` attributes. `small.mzXML` cannot make that distinction — for simple DDA the two
coincide, so a `precursorScanNum`-resolving reader passes on it. See [`READER_RULES.md`](READER_RULES.md).

## How the goldens were made

Some of the static fixtures here were produced by running queries against the data files through
MassQL's Python implementation.
