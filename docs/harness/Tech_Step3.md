# Tech Step 3 — Scaffold `massql-java` and fix the dependency policy

## Goal

A buildable, empty `massql-java` repo whose dependency closure is measured, minimal, and provably compliant with
the Phase-2 OSGi constraints — so that no later step can accidentally introduce a dependency that blocks the
Cytoscape app.

## Prerequisites

**None.** Independent of the Python side; start this in parallel with [Step 1](Tech_Step1.md).

## Context

The library's dependency choices get locked in during this spike and are the hardest thing to change later.
`massql-java` never sees OSGi, but `massql-app` will embed it as a nested jar on `Bundle-ClassPath`, and the
failure modes there (thread-context classloader can't see `ServiceLoader` providers inside a bundle; Felix
rejects multi-release jars and split packages) are expensive to diagnose and cheap to prevent. This step
converts [`SPIKE.md`](SPIKE.md) §9's constraint list from prose into build configuration and a measured number.

It also settles the finding that reshaped the plan: **`msdk-io-mzxml` cannot be used as a dependency** — see
Correction C1 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md).

Governing sections: [`SPIKE.md`](SPIKE.md) §4 (layout), §5 (readers), §6d (build wiring), §9 (constraints).

## Scope

**In scope**
- `git init`, package skeleton, `pom.xml`, `.gitignore`, `Makefile` stub.
- The dependency set, exclusions, and version pins — with measured byte sizes.
- Surefire/failsafe split, ANTLR plugin wiring, JaCoCo.
- The exception hierarchy and `MassqlOptions`, since every later step throws or reads them.
- A first `dependency-audit.txt`.

**Out of scope**
- Any grammar content — [Step 4](Tech_Step4.md). This step only wires the ANTLR plugin and commits a
  placeholder `.g4` that generates a trivial parser, proving the toolchain works.
- Any reader implementation — Steps [6](Tech_Step6.md) and [7](Tech_Step7.md).
- The scripted OSGi-readiness *check* — [Step 13](Tech_Step13.md). This step establishes the *policy* the
  check will later verify.

## Deliverables

| Path | Content |
|---|---|
| `/Users/shreuland/dev/massql-java/` | New git repo; remote `github.com/cytoscape/massql-java` |
| `pom.xml` | Full build as specified below |
| `.gitignore` | `target/`, IDE files |
| `Makefile` | `verify` target stub. ⚠ **Filled out at Step 9**, not Step 13 — `mvn` is never invoked directly now; the Makefile is the only entry point and both workflows call its targets. See [`README.md`](README.md) |
| `dependency-audit.txt` | `mvn dependency:tree` output + total byte size + the exclusion rationale |
| `DEPENDENCY_POLICY.md` | The constraint list below, as the standing rule for later steps |
| `src/main/antlr4/edu/ucsd/idekerlab/massql/lang/Massql.g4` | Placeholder grammar; real content in Step 4 |
| `src/main/java/.../massql/MassqlException.java`, `MassqlParseException.java`, `MassqlOptions.java` | See Specification §5 |
| `.github/workflows/ci.yml` | Full suite on every push to master and every PR — see §8 |
| `.github/workflows/release.yml` | Version-stamped jar from a semver tag, attached to the release and deployed to the Cytoscape nexus — see §8 |
| `scripts/dependency-audit.sh` | Regenerates `dependency-audit.txt`; **exits non-zero** on a constraint violation or budget breach, so it works as a CI gate |

## Specification

### 1. Coordinates and layout

Group/artifact: `edu.ucsd.idekerlab:massql-java`. Base package
`edu.ucsd.idekerlab.massql`. Layout per [`SPIKE.md`](SPIKE.md) §4:

```
massql-java/                                    packaging=jar, <release>17</release>
  src/main/antlr4/…/massql/lang/Massql.g4       NOT src/main/resources — see trap below
  src/main/java/…/massql/
    Massql.java              ← public entry point            (Step 11)
    MassqlOptions.java       ← precursorTolPpm default 20.0  (this step)
    MassqlException.java, MassqlParseException.java          (this step)
    lang/                    generated parser + facade, typed AST   (Step 4)
    spectra/                 SpectrumTable                          (Step 5)
    io/                      SpectraReader, SpectraFile, Mgf/Mzml readers (Step 6)
    io/vendor/               vendored mzXML parser                  (Step 7)
    exec/                    QueryExecutor, ConditionFilters, ScaninfoCollation (Steps 9, 10)
    result/                  ScanInfoResult, ResultJson             (Step 10)
    cli/Main.java            ← standalone CLI wrapper               (Step 11)
  src/test/java/…            *Test.java (unit) + *IT.java (integration)
  src/test/resources/…       fixtures + goldens, copied from Step 2
```

Create every package directory with a `package-info.java` naming its owning step, so an engineer opening the
tree knows where work belongs.

### 2. Dependencies — ⚠ REVISED DURING IMPLEMENTATION (Correction C16)

**This section originally specified `msdk-io-mzml` as a dependency. It is not one.** `msdk-datamodel` cannot
link without Guava — `MsScan` declares `Range<Double> getScanningRange()` in the **interface**, and
`SimpleMsScan` holds a `Range` field and calls `Preconditions`. Guava + its annotation satellites are
2,992,669 B (2.85 MB), which took the closure to **3.97 MB**, and the OSGi hazards mattered more than the size:
Cytoscape exports Guava **9.0.0** against MSDK's **27.1**; Guava 27.1 is itself a bundle exporting
`com.google.common.*`, so embedding makes bnd emit an `Import-Package` Felix cannot satisfy; and `jsr305` rides
along exporting `javax.annotation`. Full analysis in `DEPENDENCY_POLICY.md`.

**Resolution: MSDK is a vendoring source, never a dependency.** Both parsers are vendored — mzML in
[Step 6](Tech_Step6.md), mzXML in [Step 7](Tech_Step7.md).

**The shipping closure is two artifacts. Measured, not estimated:**

| Artifact | Bytes | Why |
|---|---|---|
| `com.github.chhh:javolution-core-java-msftbx:6.11.8` | 459,292 | The `ServiceLoader`-free `XMLStreamReaderImpl` the vendored parsers instantiate **directly**. The JDK's `XMLInputFactory` uses `ServiceLoader` (constraint 1); instantiating its impl by name needs `Class.forName` (also constraint 1). |
| `org.antlr:antlr4-runtime:4.13.2` | 326,307 | Parser runtime ([Step 4](Tech_Step4.md)) |
| **TOTAL** | **785,599 = 0.749 MB** | **49.9% of the ~1.5 MB budget** |

Plus `org.junit.jupiter:junit-jupiter` at **test scope only**.

Both shipping artifacts audited: **zero** `META-INF/services`, **zero** `META-INF/versions`, zero native
libraries. javolution is a proper OSGi bundle with a unique symbolic name, nothing else in Cytoscape provides
`javolution.*`, and its `org.osgi.core` dependency is not compile-scope so it never enters the closure.

**Banned via `maven-enforcer-plugin` at `validate`**, so the build fails rather than the bundle:
`io.github.msdk:*`, `com.google.guava:guava`, `jsr305`, `checker-qual`, `error_prone_annotations`,
`j2objc-annotations`, `it.unimi.dsi:*`, `org.slf4j:*`, `ch.qos.logback:*`, all JAXB coordinates,
`org.openscience.cdk:*`.

> **`org.javolution:javolution-core-java` is NOT excluded, because it is not present.** SPIKE.md §5 suspected
> the released pom declared both javolution forks. Verified (C2): the plain fork is **commented out** in
> `msdk-io-mzml-0.0.27.pom`; only the `chhh` fork is declared, and it is the one the code imports.

### 3. Obsolete — the commons-codec / commons-pool2 / cdk-formula question

Moot. Those arrived via MSDK, which is no longer a dependency. `commons-io` is gone too: `MzMLParser` used
`IOUtils`, and the vendored copy replaces that one call with plain Java ([Step 6](Tech_Step6.md)).

### 4. Hard constraints — `DEPENDENCY_POLICY.md`

These bind every later step. State them, with the reason attached, because a reason-free rule gets bumped.

1. **No `ServiceLoader` / `META-INF/services`** anywhere in the closure. The thread-context classloader cannot
   see inside an OSGi bundle. (cytoscape-mcp hit this twice — Lucene and the MCP SDK.)
2. **`slf4j-api` is pinned to 1.7.26. Do not upgrade to 2.x.** 1.7 uses static binding; **2.x uses
   `ServiceLoader`**, violating rule 1. This is the single most likely way a routine dependency bump silently
   breaks Phase 2. (Correction C4.)
3. **No JAXB, no native code, no `sun.misc.Unsafe`.**
4. **No `META-INF/versions/**`** (multi-release jars) in embedded deps — they break Felix resolution on
   Cytoscape 3.10.x.
5. **No logging framework beyond the pinned `slf4j-api`.** The SDK itself logs **nothing** — return diagnostics
   and let the caller log. (`cy-ndex-2` embeds slf4j+logback while cytoscape-mcp deliberately excludes
   `org/slf4j/**`; do not add to that conflict.)
6. **Total embedded closure under ~1.5 MB.**
7. **`<release>17</release>`** — matches Cytoscape 3.10.4's parent pom.
8. **No split packages.**
9. **No network access in any test.** The 46 parse goldens are checked-in files; never call
   `massql.gnps2.org/parse` from a test. Flaky CI destroys the credibility of a conformance number.

### 5. Types created in this step

Every later step throws or reads these, so they exist now to avoid a circular dependency between specs.

```java
public class MassqlException extends RuntimeException { ... }

/** Thrown for any query text this version cannot parse or does not support. */
public class MassqlParseException extends MassqlException {
    /** The offending construct, e.g. "scansum" or "FILTER". Never null. */
    public String construct();
    /** 1-based character offset in the query text, or -1 if not localizable. */
    public int position();
}

/** Immutable execution options. */
public final class MassqlOptions {
    public static MassqlOptions defaults();          // precursorTolPpm = 20.0
    public double precursorTolPpm();
    public MassqlOptions withPrecursorTolPpm(double ppm);
}
```

`construct()` is not decoration — [Step 4](Tech_Step4.md)'s rejection tests assert on it, and
[Step 12](Tech_Step12.md) requires the CLI to name the offending construct in its error output. Make it
mandatory at construction.

### 6. Build wiring

- **`antlr4-maven-plugin` 4.13.2** bound to `generate-sources`, reading `src/main/antlr4/`, `visitor=true`,
  `listener=false`. Commit a placeholder grammar that generates cleanly so the toolchain is proven before
  [Step 4](Tech_Step4.md) starts.
- **Surefire** runs `*Test.java` at the `test` phase. **Failsafe** runs `*IT.java` at the `verify` phase. The
  reviewer runs `make verify`; a developer iterating runs `make test` and expects milliseconds.

  > ⚠ **The `Makefile` is the only entry point — do not invoke `mvn` directly.** The `mvn` phase names in
  > this spec describe the *mechanism* each target wraps, not commands to type. `make verify` also runs
  > `skipcheck` (C26's zero-skip guard) and `audit`, and CI calls the same targets, so what runs locally
  > and what runs on a push cannot drift. `make` alone lists the targets; `make it` runs the integration
  > suite without the unit suite. See [`README.md`](README.md).
- **JaCoCo** `prepare-agent` + `report`, bound to `verify`.
- If any formatter (Spotless etc.) is added later, it **must exclude `target/generated-sources/`** —
  `open-cyweb` binds Spotless to the `test` phase and it would reformat generated parser code.

### 7. First dependency audit

```
mvn -q dependency:tree -Dverbose > dependency-audit.txt
mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
tr ':' '\n' < /tmp/cp.txt | xargs -I{} stat -f '%z {}' | awk '{s+=$1} END {print s " bytes total"}'
```

Append the total and the exclusion rationale to `dependency-audit.txt` and commit it. This file is the artifact
that answers [`SPIKE.md`](SPIKE.md) §11 question 3 and "did dependency complexity stay bounded?" at the review gate.

### 8. CI and release workflows

**Step 3 owns these**, not [Step 13](Tech_Step13.md) — CI belongs with the build it verifies, and having it
land in the last step would mean twelve steps of unverified commits. Step 13 references them for the review
checklist rather than specifying them again.

Modelled on `../cy-ndex-2/.github/workflows/` for house consistency, with four deliberate differences. Each
matters:

| # | cy-ndex-2 | massql-java | Why |
|---|---|---|---|
| 1 | `mvn test` | **`mvn verify`** | `mvn test` runs surefire only. **All three gates of this spike live in `*IT.java`** (reader parity, differential, CLI contract), so `mvn test` would report green while skipping every one of them. |
| 2 | JDK 11 | **JDK 17** | Constraint 7 — matches Cytoscape 3.10.4's parent pom. |
| 3 | `xvfb-run` | *(none)* | cy-ndex-2 needs a virtual display because Cytoscape touches AWT. Nothing here does ([`SPIKE.md`](SPIKE.md) §6d: "No display needed"). |
| 4 | release uses `-DskipTests` | **runs the full suite** | For an SDK whose entire value is bug-for-bug agreement with one pinned MassQL commit, publishing an unverified jar defeats the purpose. `verify` also packages, so it is one build, not two. |

**`ci.yml`** — push to `master` + PRs against `master`: checkout, JDK 17 with maven cache, **`make fixtures`**
then **`make verify`** (which itself runs `skipcheck` and `audit`),
`scripts/dependency-audit.sh`, then an **"assert tests actually ran"** step, then upload reports on failure.

That last step is not ceremony. Fixtures with unstated provenance are gitignored (the Ewing pair), so tests
needing them **skip by design** — which means a misconfigured run can execute *nothing* and still be green.
The step sums `Tests run:` across surefire and failsafe reports and fails if the total is zero. Verified locally
against the real reports: counts **10** (9 unit + 1 IT).

**`release.yml`** — on published release: extract the version from the tag (`v1.2.3` → `1.2.3`) and **reject
anything that is not plain semver**, so a typo cannot publish a bogus coordinate; **`make set-version`**
(which re-checks semver itself); **`make fixtures`** then **`make verify`**; `gh release upload` the jar;
**`make deploy`** to the nexus. Every step goes through the Makefile.

Requires `distributionManagement` in the pom (added, pointing at
`nrnb-nexus.ucsd.edu/repository/cytoscape_releases` exactly as cy-ndex-2 does, so `massql-app` needs no extra
repo config) and `REPO_USER` / `REPO_PWD` secrets in the `MVN` environment.

## Known traps

- **Putting the `.g4` in `src/main/resources/`.** Cytoscape app poms set `<filtering>true</filtering>` on
  resources, and a grammar containing `${...}` gets **silently corrupted** — a grammar that compiles today and
  breaks when the app embeds it. `src/main/antlr4/` only.
- **Letting a dependency-update bot bump slf4j-api to 2.x.** See constraint 2. Consider pinning via
  `dependencyManagement` and a `maven-enforcer-plugin` `bannedDependencies` rule so the build fails rather than
  the bundle.
- **Assuming an exclusion is safe because the parser doesn't import it.** Another class in the same artifact may.
  Verify by building and running (§3).
- **Adding `junit` at compile scope.** It must never leak into the artifact `massql-app` embeds.

## Tests required

- A trivial `ScaffoldTest.java` asserting the generated placeholder parser class loads — proves the ANTLR
  plugin, `generate-sources` wiring and `<release>17</release>` all work together.
- A trivial `ScaffoldIT.java` — proves failsafe is actually bound and running, which is easy to get silently
  wrong.
- `MassqlOptionsTest.java` — defaults, immutability of `withPrecursorTolPpm`.

## Done when

- [x] `mvn verify` green in a clean checkout; output shows **both** surefire (9 tests) and failsafe (1 IT).
- [x] `mvn dependency:tree` shows **no** MSDK, `dsiutils`, `fastutil`, `guava`, `slf4j`, `logback`, `jaxb-*`,
      `cdk-*`, or `commons-*`. Enforced by the enforcer plugin at `validate`.
- [x] `dependency-audit.txt` generated with a measured total **under 1.5 MB**: **785,599 B = 0.749 MB (49.9%)**.
      `scripts/dependency-audit.sh` exits non-zero on any constraint violation or budget breach.
- [x] ~~`slf4j-api` resolves to exactly 1.7.26~~ — **no slf4j at all** (C16); `org.slf4j:*` is banned outright.
- [x] `DEPENDENCY_POLICY.md` lists all constraints, each with its **failure mechanism**.
- [x] `MassqlException`, `MassqlParseException` (with mandatory `construct()`) and `MassqlOptions` exist.
- [x] `.github/workflows/ci.yml` runs `mvn verify` (not `mvn test`) on JDK 17, plus the dependency audit and
      the "assert tests actually ran" guard. Valid YAML; the guard verified locally at 10 tests.
- [x] `.github/workflows/release.yml` validates the tag as semver, stamps the version, runs the full suite,
      uploads `massql-java-X.Y.Z.jar`, and deploys to `cytoscape_releases`. `distributionManagement` present.
- [x] The `commons-codec` / `commons-pool2` / `cdk-formula` open item is **closed as moot** — MSDK is no longer
      a dependency.

**✅ STEP 3 COMPLETE — 2026-07-30.** Repo at `/Users/shreuland/dev/massql-java`, uncommitted pending review.
See Correction **C16** in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md).

## References

- [`SPIKE.md`](SPIKE.md) §4 (layout and public API), §5 (reader dependency analysis — **see Correction C1**), §6d (build
  wiring), §9 (constraints), §10 (how Phase 2 embeds this)
- Corrections C1, C2, C4, C5 in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md)
- `../open-cyweb/pom.xml:116-132` (bundle config), `../cytoscape-mcp/build.gradle:143-200` (OSGi exclusion list)
- Released poms: `repo1.maven.org/maven2/io/github/msdk/msdk-io-mzml/0.0.27/msdk-io-mzml-0.0.27.pom`
