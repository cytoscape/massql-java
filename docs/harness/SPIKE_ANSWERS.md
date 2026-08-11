# The eight spike questions, answered

> ⚠ **Historical record of the initial bootstrap coding effort.** Kept for reference only. It is not
> maintained against the code and will diverge from it; the source and `docs/` are authoritative.

[`SPIKE.md`](SPIKE.md) §11 asked eight questions. This file answers all eight and cites the
evidence for each, so a reviewer can check the answer rather than take it.

**Canonical here.** The README carries a one-line verdict per question and links back; if the two ever
disagree, this file is right.

Every number below was measured on this repository — none is transcribed from an estimate.

---

## Q1 — Do all three readers produce bit-identical decoded intensities vs. the Python loader? If not, what tolerance becomes the contract?

**Yes, bit-identical. No tolerance is part of the contract for peak values.**

Verified by SHA-256 over each scan's full m/z and intensity arrays, across **16 fixtures** in all three
formats, against dumps taken from MassQL's *own* loader. The only tolerance anywhere in that gate is
**1e-6 on a per-scan intensity sum**, and it absorbs error in the **reference**: the dump records a
`pandas.sum()` over a `float32` column, while our float64 accumulation reproduces the true sum exactly.

> Evidence: [`PARITY_REPORT.md`](PARITY_REPORT.md) — verdict GREEN, 16/16 fixtures.

## Q2 — Does the same query return the same rows on `small.mzML` and `small.mzXML`?

**Yes — 6 rows on both, identical on 11 of the 12 columns.**

The single exception is `ms1_precmz`, differing by at most **2.929e-8** relative. That is a *format*
property, not a decoder bug: mzXML stores the MS1 array at `precision="32"`, so the measured centroid
truncates. Every other column, including `base_peak_mz` and all intensities, is bit-identical, because
those are read from the MS2 array whose values round-trip through float32 exactly.

> Evidence: `CrossFormatEquivalenceIT` (layer 3, Pair A), run at both 20 and 60 ppm;
> [`DIFFERENTIAL_REPORT.md`](DIFFERENTIAL_REPORT.md). Correction C11.

## Q3 — Does the closure stay under ~1.5 MB? Does the released pom carry both javolution forks?

**785,599 B = 0.749 MB — 49.9% of the budget. And no: the plain fork is commented out.**

Two artifacts only:

| Artifact | Bytes |
|---|---|
| `javolution-core-java-msftbx` | 459,292 |
| `antlr4-runtime` | 326,307 |
| **Total** | **785,599** |

No MSDK, and none of `dsiutils`, `fastutil`, `guava`, `jsr305`, `checker-qual`, `slf4j`, `logback`,
`jaxb-*`, `cdk-*` or `commons-*` — all rejected by `checkBannedDependencies` rather than by convention.

> Evidence: [`dependency-audit.txt`](dependency-audit.txt), as measured at the review gate.

## Q4 — Is MSDK's licence shippable here?

**Moot in the end — MSDK is not a dependency at all — and where its code survives, EPL-1.0 is elected.**

MSDK is dual LGPL-2.1 / EPL-1.0, and EPL-1.0 is elected. But the question
turned out narrower than expected: MSDK's parser could not be vendored (its scan classes carry 9–17
`msdk-datamodel` imports plus Guava and slf4j), so only the **decode layer** was taken — `MSNumpress`
and the base64/zlib path — and the readers are hand-written. Provenance for every vendored file is
recorded per-file.

> Evidence: [`VENDORED.md`](../VENDORED.md), asserted on disk by `VendoredProvenanceTest`.
> Corrections C3, C16, C21.

## Q5 — Parser: ANTLR embedded, hand-written, or remote `/parse`?

**ANTLR 4.13.2, embedded. 326,307 B of runtime, 2,989 generated lines.**

Never the remote `/parse` endpoint: `DEPENDENCY_POLICY.md` constraint 8 forbids a test reaching the
network, and a parser that needs a live service is not embeddable at all.

⚠ **The ANTLR *tool* is severed from the runtime classpath in the build.** Gradle's `antlr` plugin puts
the whole tool — icu4j, ST4, antlr-runtime 3.x, treelayout, **15.44 MB, ~21× this budget** — on the
consumer classpath by default. `build.gradle` removes it explicitly; without that, Q3's answer would be
a failure rather than 49.9%.

> Evidence: `build.gradle`'s `configurations.configureEach` block; the closure in Q3.

## Q6 — Measured LOC: does the 1,200–1,800 estimate hold?

**No. Hand-written production code is 3,162 lines — 1.8–2.6× the estimate.**

Non-blank, non-comment lines:

| | Lines |
|---|---|
| SDK, hand-written | 2,913 |
| CLI, hand-written | 249 |
| **Production total** | **3,162** |
| *Vendored (excluded)* | *1,058* |
| *ANTLR-generated (excluded)* | *2,989* |
| Tests (unit + integration, both projects) | 10,125 |

**Where the extra went**, since that is the useful part for anyone estimating similar work: the readers are
hand-written because MSDK's could not be vendored (Q4), which is roughly a thousand lines the estimate
did not anticipate; and the bug-for-bug fidelity work — the two m/z-window semantics, three per-format
charge defaults, document-order MS1 linkage — costs more code than "implement MassQL" suggests.

**Test-to-production ratio is 3.2:1.** That is high, and deliberate: the spike's deliverable is
*evidence* that the SDK reproduces MassQL, so the harness is the product as much as the engine is.

> Evidence: measured with a non-blank/non-comment count over `src/main/java`, `cli/src/main/java` and
> the four test source sets.

## Q7 — Where does `massql-java` live, and how is it published?

**GitHub → the NRNB-hosted Nexus.** `~/.m2` during the spike.

Two coordinates, **versioned independently**, because the CLI is a standalone download while the SDK is
embedded by other applications and should not be forced to a new version by a CLI change:

- `edu.ucsd.idekerlab:massql-java` — the thin SDK
- `edu.ucsd.idekerlab:massql-java-cli` — the uber-jar

Each publishes **three artifacts**: the jar, `-sources` and `-javadoc`. The javadoc jar is the SDK's
class-level reference, so no hand-written prose can drift from the code.

> Evidence: `gradle.properties`, `make publish-sdk` / `make publish-cli`, [`SDK.md`](../SDK.md).
> Correction C45.

## Q8 — Wall-clock and peak heap on all three fixtures vs. the pandas path?

**Java is 6.8× faster and uses 2.3× less memory.**

Measured process-to-process under `/usr/bin/time -l` on one host, same file, same query, same 664 rows
from `PlusRise.mgf` (34,513 spectra):

| | Wall-clock | Max RSS |
|---|---:|---:|
| `massql_query.py` (pandas) | 2.84 s | 669.8 MB |
| `massql-java-cli` uber-jar | **0.42 s** | **288.5 MB** |

In-process per fixture, every one completes in under 350 ms. [`SPIKE.md`](SPIKE.md) §7 flagged
the MGF as the fixture where a linear scan standing in for a binary search would show; **nothing is
quadratic**.

> Evidence: `PerformanceIT` writes `build/reports/performance/measurements.txt` with the host spec
> captured programmatically; [`DIFFERENTIAL_REPORT.md`](DIFFERENTIAL_REPORT.md).
> ⚠ The host was **8 CPU / 24 GB**, and the JVM figures were taken with a 0.5 GB default test heap.

---

## What these answers do not claim

The spike reproduces MassQL's **`scaninfo` subset**, bug-for-bug, against **one pinned commit**. It is
not a general MassQL implementation, and "parity" here means agreement with that commit's behaviour —
including its quirks. See the README's honest-framing section and
[`SDK.md`](../SDK.md) for the supported subset and the known deviations.
