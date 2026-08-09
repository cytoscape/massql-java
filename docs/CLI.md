# massql-java-cli — the command-line tool

A standalone batch filter: give it a spectra file and a query, get JSON on stdout. Distributed
as an uber-jar, so it needs nothing on the classpath.

```sh
java -jar massql-java-cli-<version>.jar spectra.mzML query.massql | jq '.[0].scan'
```

The query can come from a **file**, from **stdin**, or **inline** — see [Examples](#examples).

Its argument order and defaults mirror the reference implementation `massql_query.py`, so the two can
be run over the same inputs and compared directly; the extra query sources and `--output` are
deliberate additions on top of that interface.

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
massql-java-cli <spectra-file> [<query-file>|-] [options]

  <spectra-file>   .mgf, .mzML or .mzXML (format is sniffed from content, not extension)
  <query-file>     file containing one MassQL query
  -                read the query from stdin

Options:
  -q, --query <STRING>           the query itself, inline
  --precursor-tol-ppm <double>   tolerance for matching the precursor peak in MS1 (default 20.0)
  --output <FILE|->              write JSON to FILE; '-' means stdout (the default)
  -h, --help                     this message
```

## Where the query comes from

Exactly **one** of three sources, always chosen explicitly:

| Form | Use it when |
|---|---|
| `<query-file>` | the query lives in a `.massql` file |
| `-` | you are piping the query in |
| `-q`, `--query` | it is a one-liner |

Giving **none**, or **more than one**, is a usage error (exit 2). There is deliberately no precedence
rule: two sources means it is unclear which one runs, and quietly choosing for you would hide that. A
repeated `-q` is an ordinary last-wins override.

Whitespace is stripped from every source, so all three produce **identical output** for the same query
— a heredoc's trailing newline changes nothing.

> ⚠ `-` selects stdin for the **query** only. The spectra file must be a real path: readers memory-map
> it and sniff the format by reading the head, so a non-seekable stream cannot work.

## Examples

Every command below runs against a fixture **committed to this repository**, so you can paste them as
written after cloning — no data of your own, and no download. Paths are relative to the repo root.

> The `data/DP00570_F02.*` fixtures are *not* used here: those are fetched by `make fixtures` rather
> than committed. Everything referenced below ships in the repo.

Build the jar first with `make cli`, then set:

```sh
JAR=cli/build/libs/massql-java-cli-0.1.0-SNAPSHOT.jar
RES=src/test/resources
```

**A query file** — the classic form. 6 rows from a 48-spectrum mzML:

```sh
java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql | jq length
# 6
```

**Inline with `-q`** — best for a short query. 2 rows from the tiny mzML fixture:

```sh
java -jar $JAR $RES/fixtures/micro/micro.mzML \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5' | jq length
# 2
```

**Inline, MS1 scans** — `MS1DATA` selects survey scans instead of fragmentation ones, so the same file
yields a different set. 14 rows:

```sh
java -jar $JAR $RES/data/small.mzML \
  -q 'QUERY scaninfo(MS1DATA) WHERE MS1MZ=810.79:TOLERANCEMZ=1.0' | jq length
# 14
```

**Piped from stdin with `-`** — the form to reach for when the query is long. This one ANDs three
product-ion conditions across a 34,513-spectrum MGF and returns 664 rows:

```sh
cat $RES/goldens/queries/test.massql | java -jar $JAR $RES/data/PlusRise.mgf - | jq length
# 664
```

**Stdin from a heredoc** — no temp file, no shell quoting to fight. 6 rows from the mzXML:

```sh
java -jar $JAR $RES/data/small.mzXML - <<'EOF' | jq length
QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0
EOF
# 6
```

**Inline, writing to a file** — `--output` keeps stdout empty, so nothing needs redirecting. 2 rows
from the MGF fixture:

```sh
java -jar $JAR $RES/fixtures/micro/micro.mgf \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5' \
  --output /tmp/hits.json
jq length /tmp/hits.json
# 2
```

**Widening the precursor tolerance** — the same file and query, twice, differing only in the flag.
At the default 20 ppm four of the six rows have a null `ms1_i`; at 60 ppm all six are populated:

```sh
java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql \
  | jq '[.[] | select(.ms1_i == null)] | length'
# 4   -- four of the six precursors fall outside a 20 ppm window

java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql --precursor-tol-ppm 60 \
  | jq '[.[] | select(.ms1_i == null)] | length'
# 0   -- at 60 ppm every one of the six matches
```

Each `(fixture, query)` pair above is one the differential test suite already asserts against the
Python reference, so the row counts are pinned by tests rather than typed by hand.

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
