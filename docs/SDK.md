# massql-java — the SDK

A pure-Java MassQL engine, published as SDK.

> **Class-level documentation is in the javadoc.** Every published version ships a
> `massql-java-<version>-javadoc.jar`, and that is the reference for entry points, parameters, return
> types and behaviour. 

---

## Quick start

This is an example app demonstrating usage of the SDK. It is compreised of 4 files.

**Prerequisites: JDK 17+ and Gradle.** The example is a Gradle project, so `gradle` must be on your
PATH — if you don't have it already, install it direct from pre-compiled binaries - https://gradle.org/releases/.

### 1. `build.gradle`

```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
    maven { url = 'https://nrnb-nexus.ucsd.edu/repository/cytoscape_snapshots/' }
}

dependencies {
    implementation 'org.cytoscape:massql-java:0.0.1-SNAPSHOT'
}

application {
    mainClass = 'Main'
}
```

The Nexus repository is required — this artifact is not on Maven Central. Use the
`cytoscape_snapshots` URL for a `-SNAPSHOT` version and `cytoscape_releases` for a released one; a
version resolved against the wrong repository simply fails to resolve.

### 2.  `settings.gradle`:

```groovy
rootProject.name = 'massql-hello'
```

### 3. `sample.mgf`

Three scans, so the query below has something to match and something to reject. Save it in same directory:

```
BEGIN IONS
TITLE=micro.scan1
PEPMASS=250.25
100.0 250.0
200.5 1500.0
END IONS

BEGIN IONS
TITLE=micro.scan3
PEPMASS=500.0
RTINSECONDS=60.0
100.0 250.0
200.5 1500.0
201.0 100.0
300.0 750.0
END IONS

BEGIN IONS
TITLE=micro.scan5
PEPMASS=500.0
CHARGE=2+
RTINSECONDS=120.0
123.456789012345 4096.0
END IONS
```

### 4. `src/main/java/Main.java`

```java
import java.nio.file.Path;
import java.util.List;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.result.ScanInfoResult;

public class Main {

    // Every MS2 scan containing a fragment ion at m/z 200.5, within a 0.5 Da window.
    private static final String QUERY =
            "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5";

    public static void main(String[] args) {
        try {
            List<ScanInfoResult> rows = Massql.run(QUERY, Path.of("sample.mgf"), null);

            System.out.println(rows.size() + " matching scans");
            for (ScanInfoResult r : rows) {
                System.out.printf(
                        "scan=%d  precursor=%s  rt=%.2f min  tic=%.1f%n",
                        r.scan(),
                        r.precmz() == null ? "-" : String.format("%.4f", r.precmz()),
                        r.rt(),
                        r.tic());
            }
        } catch (MassqlException e) {
            System.err.println("massql: " + e.getMessage());
            System.exit(1);
        }
    }
}
```


Now run it with gradle. Qualify the path to the downloaded gradle bin if needed.

`gradle run` - will print to console:

```
2 matching scans
scan=1  precursor=250.2500  rt=0.00 min  tic=1750.0
scan=2  precursor=500.0000  rt=1.00 min  tic=2600.0
```

The third scan has no peak near 200.5, so it does not match. Scans are numbered by **document
order** — nothing is read from `TITLE`.

Two details in that snippet are worth highlighting:

- **`precmz` is null-guarded.** Every column is a boxed type and several are genuinely nullable.
  [`RESULT_SCHEMA.md`](RESULT_SCHEMA.md) is the full 12-key contract and says exactly which.
- **`rt` is in minutes**, not seconds — `RTINSECONDS=60.0` above comes back as `1.00`.

To point it at your own data, change the path: `.mgf`, `.mzML` and `.mzXML` all work, and the format
is detected from content rather than the extension.

### The three SDK artifacts

| Classifier | What it is |
|---|---|
| *(none)* | the library |
| `-javadoc` | **the API reference** — IDEs attach it automatically and show it inline |
| `-sources` | sources for stepping through in a debugger |

Snapshots live in `cytoscape_snapshots` on the https://nrnb-nexus.ucsd.edu/repository host, releases in `cytoscape_releases`.

---

### Supported massql queries

`QUERY scaninfo(MS1DATA|MS2DATA) WHERE … [FILTER …]`. Anything outside that subset is rejected.

---

## Known deviations

1. **`ms1scan` is inferred by document order, not read from the file.** ignores
   `spectrumRef` (mzML) and `precursorScanNum` (mzXML) same as the py MassQL does, but when a precursor reference points further back than the immediately
   preceding MS1 scan, the java `ms1scan` will disagree with the file's declared linkage. For simple DDA
   they coincide.
2. **`=` means `>=` for intensity comparisons.** 
3. **`i_norm` and `i_norm_ms1` are not emitted.** Both are structurally constant, so they carry no
   information.
4. **`tic` is not bit-identical** The py intensity
   column is `float32` and `tic` is a pandas sum over it, while java sdk accumulate in float64. 


---

## Working on the SDK itself

This section is for changing the library, not using it.

The `Makefile` is the entry point.

```sh
make build             # jar, -sources.jar and -javadoc.jar -> build/libs/
make test              # unit tests, seconds
make integration-test  # unit + integration tests, coverage gate, lint, banned deps
make publish-local     # install into your local m2 cache
```
