# massql-java-cli — the command-line tool

A standalone batch filter: give it a spectra file and a query file, get JSON on stdout. Distributed
as an uber-jar, so it needs nothing on the classpath.

```sh
java -jar massql-java-cli-<version>.jar spectra.mzML query.massql | jq '.[0].scan'
```

Its argument order and defaults mirror the reference implementation `massql_query.py`, so the two can
be run over the same inputs and compared directly.

---

## Getting it

Attach it from a GitHub release, or resolve it from the Cytoscape nexus as
`edu.ucsd.idekerlab:massql-java-cli` — the uber-jar is the primary artifact, with `-javadoc` and
`-sources` beside it.

Versioned **independently of the SDK**: `massql-java-cli` and `massql-java` will not share a version
number.

To build it from this repository:

```sh
make cli            # -> cli/build/libs/massql-java-cli-<version>.jar
```

---

## Usage

```
massql-java-cli <spectra-file> <query-file> [options]

  <spectra-file>   .mgf, .mzML or .mzXML (format is sniffed from content, not extension)
  <query-file>     file containing one MassQL query

Options:
  --precursor-tol-ppm <double>   tolerance for matching the precursor peak in MS1 (default 20.0)
  --output <FILE|->              write JSON to FILE; '-' means stdout (the default)
  -h, --help                     this message
```

The query comes from a **file**, not an argument — matching the reference implementation.

## Streams

| | |
|---|---|
| **stdout** | the JSON array and **nothing else, ever**, plus a trailing newline |
| **stderr** | diagnostics, warnings, errors and usage — on every output mode |

That split is what makes `| jq` safe. It is also why `--help` prints to **stdout** when you ask for
it (it is the output you requested) but to **stderr** when it accompanies an error — a failing run
must never put non-JSON on stdout.

> This is the **CLI's** contract. The SDK underneath writes to no stream at all; treating stdout as a
> data pipe is a property of this tool, not of the library.

## Output modes

`--output FILE` writes the JSON to `FILE` and leaves stdout empty. The bytes are **identical** to
what the same run puts on stdout — one render, two destinations.

The write is **atomic**: a temp file beside the target, then a rename. A consumer polling the path
never sees a partial file, and a failure mid-write leaves **neither** the output file nor the temp
behind. A truncated result that looks complete is worse than no result at all.

## Exit codes

| Code | Meaning |
|---|---|
| **0** | success — **including a query that matched nothing**, which prints `[]` |
| **1** | the file exists and is readable, but its **content** will not parse |
| **2** | usage — bad arguments, missing file, empty query, unsupported query, unwritable `--output` |

The line between 1 and 2 is: *could the user have known from the command line alone?*

Branch on `0` versus non-zero. Treating "no matches" as a failure would make an empty result
indistinguishable from a broken tool — an empty result is a valid answer.

An unsupported query names the offending construct on stderr, so the message says what to change.

## Output format

The JSON array follows the frozen 12-key contract in [`RESULT_SCHEMA.md`](RESULT_SCHEMA.md) — the
same rows the SDK returns, one object per matching scan, ascending by scan id.

Note it is **compact** rather than indented. The reference implementation emits `indent=2`; the
values are identical and round-trip exactly, but the two are not byte-comparable. Pipe through `jq`
if you want it formatted.
