# Vendored sources — provenance, licence election, and every modification

Everything under `src/main/java/org/cytoscape/massql/io/vendor/`.

> ⚠ **This file is a redistribution obligation, not a courtesy.** It is asserted to exist and to match the
> artifact. **All twelve vendored files' headers point readers here** (*"See docs/VENDORED.md for the rationale
> and the full modification list"*) and there was nothing to point at.
>
> per-file headers by `VendoredProvenanceTest`. Every vendored file's header points readers here, so this
> record and those headers have to agree — the test is what keeps them agreeing.

## Why anything is vendored at all

**MSDK cannot be a dependency.** The blocking problem is
Guava, not size: `msdk-datamodel` drags it in unavoidably at **27.1**, which conflicts irreconcilably with the
**9.0.0** a likely host already provides — and a library that forces its host's Guava version is not safely
embeddable. So the
**decode path only** is vendored — the smallest set that decodes an mzML binary array — and everything above it
(readers, the store, the engine) is written here.

`DEPENDENCY_POLICY.md` constraint 5 (no split packages) is why these live under our own
`…massql.io.vendor` package rather than keeping their upstream `io.github.msdk.*` names.

## Licence election

MSDK is dual-licensed **LGPL-2.1 OR EPL-1.0**. **This project elects EPL-1.0**, and the election is recorded
in every vendored file's header — not only here, because a file that travels without its election leaves the
obligation unresolved. `VendoredProvenanceTest` asserts the string `EPL-1.0` is present in each one.

Upstream: **github.com/msdk/msdk**, commit **`da2927a15c178b8ba9492d1e62571018bc70eecc`** for all files.
Original copyright is retained verbatim in each header: *(C) Copyright 2015-2016 by MSDK Development Team*.

## The vendored files

12 files, ~2,670 lines, all from `msdk-io-mzml` at the commit above.

| File | Lines | Upstream path (under `msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/`) | Modifications |
|---|---|---|---|
| `MSNumpress.java` | 1131 | `util/MSNumpress.java` | package declaration only |
| `MzMLPeaksDecoder.java` | 328 | `data/MzMLPeaksDecoder.java` | **three, all dependency removals** — see below |
| `ByteBufferInputStream.java` | 288 | `util/ByteBufferInputStream.java` | package declaration only |
| `MzMLBinaryDataInfo.java` | 278 | `data/MzMLBinaryDataInfo.java` | **dropped `javax.annotation.Nonnull`** (jsr305 is banned by policy) |
| `MzMLCV.java` | 158 | `data/MzMLCV.java` | package declaration only |
| `MzMLTags.java` | 150 | `data/MzMLTags.java` | package declaration only |
| `MzMLCVParam.java` | 107 | `data/MzMLCVParam.java` | package declaration only |
| `MzMLCompressionType.java` | 70 | `data/MzMLCompressionType.java` | package declaration only |
| `FileMemoryMapper.java` | 58 | `util/FileMemoryMapper.java` | package declaration only |
| `MzMLBitLength.java` | 52 | `data/MzMLBitLength.java` | package declaration only |
| `MzMLArrayType.java` | 49 | `data/MzMLArrayType.java` | package declaration only |

Ten of the eleven are **byte-identical to upstream apart from the package line**, which is the property that
makes a future re-sync a mechanical diff rather than a merge.

### `MzMLPeaksDecoder` — the only substantive edits

Three substitutions, each removing a dependency rather than changing behaviour:

| Upstream | Here | Why |
|---|---|---|
| Guava `com.google.common.io.LittleEndianDataInputStream` | our `LittleEndianDataInput` | Guava cannot be a dependency. See below |
| commons-io `IOUtils.toByteArray(dis)` | `InputStream.readAllBytes()` | JDK 9+ has it; the dependency bought nothing |
| `MSDKException` | `MassqlException` | MSDK types must not appear in our signatures |

### `MzMLBinaryDataInfo` — one removal

`javax.annotation.Nonnull` dropped. jsr305 is banned by `DEPENDENCY_POLICY.md`, and the annotation carries no
runtime behaviour, so removing it changes nothing observable.

## Not vendored, despite living in this package

Two files under `io/vendor/` are **ours** and deliberately carry no provenance header.
`VendoredProvenanceTest` skips them by detecting the explicit *"Not vendored"* marker in their javadoc rather
than by a filename list, so a genuinely vendored file cannot be quietly exempted by adding it to an allowlist.

| File | What it is |
|---|---|
| `LittleEndianDataInput.java` | Written here as a drop-in replacement for the Guava class `MzMLPeaksDecoder` used. Implements only the four methods the decoder calls — `readInt`, `readLong`, `readFloat`, `readDouble` — because anything else would be untested code. Extends `FilterInputStream` so `readAllBytes()` comes free, which is what replaces the commons-io call. Little-endian is load-bearing: mzML binary arrays are little-endian while mzXML's are big-endian, and getting it wrong yields plausible garbage rather than an error |
| `package-info.java` | Package documentation, written here |

It sits in `io/vendor/` because it exists solely to serve the vendored decoder; moving it out would separate it
from its only caller. That it is *not* vendored is the reason this section exists at all — the first version of
`VendoredProvenanceTest` assumed everything in the directory was upstream code and failed on this file.

## Re-syncing against upstream

1. Diff each file against `da2927a…` (or the newer commit) upstream, ignoring the package line.
2. Re-apply the modifications in the tables above — they are exhaustive, which is the point of enumerating them.
3. Update the `commit:` line in **every** header; `VendoredProvenanceTest` asserts a 40-character SHA is present
   but cannot tell a stale one from a current one, so this step is on you.
4. `make integration-test`. The mzML decode path is pinned bit-for-bit against MassQL's own loader by the
   reader-parity tests, so a behavioural regression in `MSNumpress` or
   `MzMLPeaksDecoder` shows up as a digest mismatch rather than as a silent numeric drift.

## References

- `DEPENDENCY_POLICY.md` — constraint 5 (no split packages), the jsr305 and Guava bans, the size budget
- `docs/harness/` — the project's historical engineering record, including what was vendored and why, and
  why the mzXML parser was **hand-written** instead: reusing MSDK's would have dragged in 7 `datamodel`
  types and Guava via `SimpleMsScan`
