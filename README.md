# massql-java

A pure-Java implementation of [MassQL](https://github.com/mwang87/MassQueryLanguage)'s `scaninfo`
queries.

**MassQL is a query language for mass spectrometry data.** You describe the scans you want — by
precursor or product ion, retention time, charge, intensity — and get back the ones that match:

```
QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0
```

*Every MS2 scan whose precursor is within 1.0 Da of m/z 810.79.* A `scaninfo` query returns one row
per matching scan, with its scan number, precursor m/z, retention time, charge and intensity columns
— 12 in all, listed in [`docs/RESULT_SCHEMA.md`](docs/RESULT_SCHEMA.md).

Reads `.mgf`, `.mzML` and `.mzXML`. 

---

## Try it 

The library requires JDK 17+.

The command-line tool needs no data of your own — this runs against a fixture in the repository:

```sh
make build
java -jar cli/build/libs/massql-java-cli.jar src/test/resources/data/small.mzML \
  -q 'QUERY scaninfo(MS2DATA) WHERE MS2PREC=810.79:TOLERANCEMZ=1.0'
```

That prints a JSON array — one object per matching scan, six of them here.

---

## Two artifacts

### 📚 [`massql-java`](docs/SDK.md) — the SDK

The library. Embed it in any JVM application: add one coordinate, call `Massql.run(...)`, get a
`List<ScanInfoResult>` back.

→ **[docs/SDK.md](docs/SDK.md)** — a complete copy-paste project to start from. Standard javadocs
documentation is the published `-javadoc.jar`.

### 🖥 [`massql-java-cli`](docs/CLI.md) — the command-line tool

A standalone uber-jar that can be run as cli tool. Accepts spectra file and massql query as inputs and json for output results.

→ **[docs/CLI.md](docs/CLI.md)** — arguments, streams, output modes and exit codes, plus **runnable
examples** against fixtures committed here; paste them as written.

---

## Reference documents

| | |
|---|---|
| [`docs/RESULT_SCHEMA.md`](docs/RESULT_SCHEMA.md) | the frozen 12-key result structure |
| [`docs/internals/`](docs/internals/READER_RULES.md) | engineering notes for maintainers: reader rules, query semantics, the column store, grammar and fixture provenance |
| [`docs/VENDORED.md`](docs/VENDORED.md) | vendored MSDK provenance and the EPL-1.0 election a redistributor needs |

---

## Building

The `Makefile` is preferred entry point.

```sh
make                   # list every target
make build             # both projects: jar, -sources.jar and -javadoc.jar each
make test              # unit tier, seconds
make integration-test  # both tiers and the coverage gate
```

Tests live in one tree, `src/test/java`, split by filename: **`*IT.java` is the integration tier**,
everything else the unit tier. Helpers both tiers use live in `…massql.testsupport`; `TierBoundaryTest`
fails the build if an integration test reaches anywhere else in the test tree.

## Implementation basis

This SDK was **initiated from** MassQL's Python implementation as a precedent — tag **`2026.03.14`**. 

⛔ **This SDK does not continue to track the Python MassQL going forward.** This SDK maintains its own behavior in relation to MassQL specs going forward.

## Licensing and vendored code

**This project is licensed under the Eclipse Public License 1.0** — see [`LICENSE`](LICENSE). 


**Two test fixtures are third-party data under [CC BY 4.0](http://creativecommons.org/licenses/by/4.0/)**
— `src/test/resources/data/DP00570_F02.*`, from Professor Rob Ewing's Omics Analysis Tutorial. 
