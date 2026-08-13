# massql-java-cli — the command-line tool

A standalone massql query tool: run with jvm as a cli tool, give it a spectra file and a query, get JSON on output. Distributed
as an uber-jar.

```sh
java -jar massql-java-cli.jar spectra.mzML query.massql
```

The query can come from a **file**, from **stdin**, or **inline** — see [Examples](#examples).

---

## Getting it

Attach it from a GitHub release, or resolve it from the NRNB Nexus as
`org.cytoscape:massql-java-cli`.

Or build it locallyu from this repository:

```sh
make build          # -> cli/build/libs/massql-java-cli.jar
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

Giving none, or more than one, is a usage error (exit 2). A repeated `-q` is last-wins.

Whitespace is stripped, so all three forms give identical output for the same query.

> ⚠ `-` selects stdin for the **query** only. The spectra file must be a real path, not a stream.

## Examples

Every command below runs against a fixture committed to this repository — paste them as written, no
data of your own needed. Paths are relative to the repo root.

**Prerequisites:** JDK 17+. 

```sh
JAR=cli/build/libs/massql-java-cli.jar
RES=src/test/resources
```

**Start here — what a result looks like.** *MS2 scans with a precursor within 1.0 Da of m/z 810.79:*

```sh
java -jar $JAR $RES/data/small.mzML \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0'
```

Output is a compact JSON array, one object per matching scan. Formatted, the first element:

```json
{
  "scan": 3,
  "precmz": 810.79,
  "ms1scan": 2,
  "rt": 0.011218333333333334,
  "charge": null,
  "tic": 586278.8533592224,
  "mslevel": 2,
  "base_peak_i": 161140.859375,
  "base_peak_mz": 736.6370849609375,
  "ms1_i": null,
  "ms1_precmz": null,
  "ms1_base_peak_i": 183838.71875
}
```

Twelve keys per row, `null` where a value does not exist. `rt` is in **minutes**.
[`RESULT_SCHEMA.md`](RESULT_SCHEMA.md) defines each key. The remaining examples show only row counts.

**A query file.** *Same query, read from a file.* 6 rows from a 48-spectrum mzML:

```sh
java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql
```

**Inline with `-q`** — for a short query. *Fragment ion at m/z 200.5 ± 0.5.* 2 rows:

```sh
java -jar $JAR $RES/fixtures/micro/micro.mzML \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5'
```

**MS1 scans** — `MS1DATA` selects survey scans instead of fragmentation ones. *Survey scans with a
peak at m/z 810.79 ± 1.0.* 14 rows:

```sh
java -jar $JAR $RES/data/small.mzML \
  -q 'QUERY scaninfo(MS1DATA) WHERE MS1MZ=810.79:TOLERANCEMZ=1.0'
```

**Piped from stdin with `-`** — for a long query. *Three product-ion conditions ANDed, across a
34,513-spectrum MGF.* 664 rows:

```sh
cat $RES/goldens/queries/test.massql | java -jar $JAR $RES/data/PlusRise.mgf -
```

**Writing to a file** — `--output` keeps stdout empty, so nothing needs redirecting. 2 rows from the
MGF fixture:

```sh
java -jar $JAR $RES/fixtures/micro/micro.mgf \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5' \
  --output /tmp/hits.json
```

**Widening the precursor tolerance** — same file and query, differing only in the flag. At the default
20 ppm four of the six rows have a null `ms1_i`; at 60 ppm all six are populated:

```sh
java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql 
```

```sh
java -jar $JAR $RES/data/small.mzML $RES/goldens/queries/test_mzml.massql --precursor-tol-ppm 60 
```

Then point it at your own data. `.mgf`, `.mzML` and `.mzXML` all work, and the format is detected from
content rather than the extension, so a misnamed file still reads correctly:

```sh
java -jar $JAR /path/to/your/run.mzML -q 'QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5'
```

## Streams

| | |
|---|---|
| **stdout** | the JSON array and nothing else, plus a trailing newline |
| **stderr** | diagnostics, warnings, errors and usage |

That split is what makes `| jq` usage safe. `--help` goes to stdout when requested, to stderr when it
accompanies an error.

## Output modes

`--output FILE` writes the JSON to `FILE` and leaves stdout empty. The bytes are identical either way.

The write is atomic — a consumer polling the path never sees a partial file, and a failed run leaves
nothing behind.

## Exit codes

| Code | Meaning |
|---|---|
| **0** | success — including a query that matched nothing, which prints `[]` |
| **1** | the file is readable, but its **content** will not parse |
| **2** | usage — bad arguments, missing file, empty query, unsupported query, unwritable `--output` |

Branch on `0` versus non-zero; an empty result is a valid answer, not a failure. An unsupported query
names the offending construct on stderr.

## Output format

One object per matching scan, ascending by scan id, following the 12-key contract in
[`RESULT_SCHEMA.md`](RESULT_SCHEMA.md).

The JSON is compact rather than indented — pipe through `jq` to format it.
