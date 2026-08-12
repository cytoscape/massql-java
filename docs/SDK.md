# massql-java — the SDK

A pure-Java MassQL engine, published as an ordinary jar with two dependencies. Parse a MassQL query,
run it over an `.mgf` / `.mzML` / `.mzXML` file, get rows back.

> **Class-level documentation is not here — it is in the javadoc.** Every published version ships a
> `massql-java-<version>-javadoc.jar`, and that is the reference for entry points, parameters, return
> types and behaviour. Prose duplicating javadoc drifts from it; this page covers only what javadoc
> cannot: how to obtain the library, and how to build it.

---

## Getting it

Published to the NRNB-hosted Nexus at `nrnb-nexus.ucsd.edu`. Add that repository and the coordinate
below; there is nothing else to configure.

**Gradle**

```groovy
repositories {
    maven { url = 'https://nrnb-nexus.ucsd.edu/repository/cytoscape_releases/' }
}

dependencies {
    implementation 'edu.ucsd.idekerlab:massql-java:<version>'
}
```

**Maven**

```xml
<repositories>
  <repository>
    <id>nrnb-nexus</id>
    <url>https://nrnb-nexus.ucsd.edu/repository/cytoscape_releases/</url>
  </repository>
</repositories>

<dependency>
  <groupId>edu.ucsd.idekerlab</groupId>
  <artifactId>massql-java</artifactId>
  <version>[version]</version>
</dependency>
```

Snapshots are at the matching `_snapshots` repository on the same host.

### The three artifacts

| Classifier | What it is |
|---|---|
| *(none)* | the library |
| `-javadoc` | **the API reference** — IDEs attach it automatically and show it inline |
| `-sources` | sources for stepping through in a debugger |

The SDK is versioned **independently of the CLI**, so `massql-java` and `massql-java-cli` will not
share a version number. That is deliberate: a CLI fix should not force a new SDK coordinate.

### What it drags in

Two artifacts, **0.749 MB total** — `javolution-core-java-msftbx` and `antlr4-runtime`. Both are
`runtime` scope, so neither appears on your compile classpath.

That number is enforced, not aspirational: the build fails if the closure exceeds ~1.5 MB or if any
of a list of banned coordinates appears. No logging framework, no `ServiceLoader`, no JAXB, no native
code — see [`DEPENDENCY_POLICY.md`](DEPENDENCY_POLICY.md) for the constraints and why each exists.
The point of all of it is that embedding this SDK should be uneventful: it brings no logger to conflict
with yours, no provider lookup that depends on classloader layout, and little enough weight that you
need not think about it.

---

## Using it

```java
List<ScanInfoResult> rows = Massql.run(queryText, Path.of("spectra.mzML"), null);
```

That is the one-shot form. When you want to own the resource, or need the diagnostics a
valid-but-degenerate query produces, use the explicit form — **see the javadoc on `Massql`**, which
documents all four entry points and the resource rules in full.

Two behaviours are worth knowing before you start, because they are easy to assume wrongly:

- **A stream is single-pass.** Several queries over one file means reopening the file, once per
  query. A spent stream throws rather than quietly returning an empty result.
- **The SDK writes to no stream.** It never prints or logs; diagnostics come back as return values
  for you to surface however you like.

Results follow the frozen 12-key contract in [`RESULT_SCHEMA.md`](RESULT_SCHEMA.md).

### Supported query subset

`QUERY scaninfo(MS1DATA|MS2DATA) WHERE … [FILTER …]`. Anything outside that subset parses and is then
rejected **by name** — the exception says which construct was the problem, not "syntax error".

---

## Known deviations from the Python reference

Six deliberate differences. Each is a decision rather than an oversight, and each is the kind of thing
that looks like a bug if you compare our output against `massql_query.py` without knowing about it.

1. **`ms1scan` is inferred by document order, not read from the file.** The reference ignores
   `spectrumRef` (mzML) and `precursorScanNum` (mzXML), and we reproduce that to match its answers. On
   interleaved acquisition, or where a precursor reference points further back than the immediately
   preceding MS1 scan, our `ms1scan` will disagree with the file's declared linkage. For simple DDA
   they coincide.
2. **`=` means `>=` for intensity comparisons.** The reference's historical semantics, reproduced.
3. **`i_norm` and `i_norm_ms1` are not emitted.** Both are structurally constant, so they carry no
   information; the row is 12 keys, not 14.
4. **`tic` is not bit-identical — and ours is the more accurate value.** The reference's intensity
   column is `float32` and `tic` is a pandas sum over it, while we accumulate in float64. Worst
   measured divergence is **4.7e-8** relative. ⚠ The error is in the reference, not here. Every other
   intensity column *is* bit-identical, because those are selections rather than sums.
5. **`POLARITY` on an MGF filters a constant, not the data.** The reference hardcodes MGF polarity to
   `1`, so `POLARITY=Positive` matches every MGF scan and `POLARITY=Negative` matches none, whatever
   the spectra actually contain. Nothing in the output reveals this, which is why it is listed.
6. **The JSON is compact; the reference emits `indent=2`.** Values are identical and round-trip
   exactly, but the two outputs are **not byte-comparable** — compare parsed values, not `diff`.

---

## Building from source

The `Makefile` is the only entry point; do not invoke `./gradlew` directly.

```sh
make build          # both jars -> build/libs/ and cli/build/libs/
make test           # unit tests, seconds
make integration-test  # unit + integration tests, coverage gate, lint, banned deps
make publish-sdk    # publish to the nexus (needs REPO_USER / REPO_PWD)
```

`make` with no argument lists every target. JDK 17 is required, and the build enforces it.

`make build` produces all three artifacts, and writes the browsable javadoc on the way — open
`build/docs/javadoc/index.html` to read it locally without publishing.
