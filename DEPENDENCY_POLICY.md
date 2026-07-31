# Dependency policy

**These constraints bind every later step.** `massql-java` never sees OSGi, but `massql-app` embeds it as a
nested jar on `Bundle-ClassPath`, and the failure modes there are expensive to diagnose and cheap to prevent.

Each rule states its **failure mechanism**. A rule without one gets bumped by the next person who finds it
inconvenient — which is exactly how this project nearly shipped Guava (see §Guava below).

Most of these are enforced by `maven-enforcer-plugin` at the `validate` phase, so **the build fails rather than
the bundle**. `scripts/check-osgi-readiness.sh` (Tech_Step13) checks the rest against the packaged artifact.

---

## The constraints

**1. No `ServiceLoader`, no `META-INF/services`, no `Class.forName` anywhere in the shipping closure.**
The thread-context classloader cannot see inside an OSGi bundle, so provider lookup silently finds nothing.
cytoscape-mcp hit this twice — Lucene and the MCP SDK.
*Consequence for us:* we cannot use `javax.xml.stream.XMLInputFactory`, which is why `javolution` is a
dependency at all — the vendored parsers instantiate `javolution.xml.internal.stream.XMLStreamReaderImpl`
**directly**. Instantiating the JDK's internal implementation by name would need `Class.forName`, also banned.
*Enforced:* enforcer bans known offenders; the readiness script unpacks the closure and greps.

**2. No logging framework. The SDK logs nothing at all.**
Return diagnostics to the caller and let them log. `cy-ndex-2` embeds slf4j+logback while cytoscape-mcp
deliberately excludes `org/slf4j/**`; do not add to that conflict. Note slf4j **2.x** switched to
`ServiceLoader`, so a future "harmless" logging addition would breach constraint 1 as well.
*Enforced:* enforcer bans `org.slf4j:*` and `ch.qos.logback:*`.

**3. No JAXB, no native code, no `sun.misc.Unsafe`.**
JAXB is not in the JDK from 11 onward and drags a provider-lookup stack. Native libraries cannot be loaded
from inside a nested jar. `Unsafe` needs JVM flags we do not control.
*Note:* MSDK's mzXML pom declares JAXB but the code only touches `javax.xml.datatype`, still in the JDK on 17.
*Enforced:* enforcer bans the JAXB coordinates; readiness script scans for `.so`/`.dylib`/`.dll` and `Unsafe`.

**4. No `META-INF/versions/**` (multi-release jars) in the shipping closure.**
They break Felix resolution on Cytoscape 3.10.x.
*Note:* JUnit violates this (`junit-platform-commons` ships 10 such entries) — which is fine, and precisely why
the check must be scoped to compile+runtime and never to test.
*Enforced:* readiness script + `dependency-audit.txt`'s per-artifact table.

**5. No split packages.** Two bundles exporting the same package makes Felix pick one wiring framework-wide.
*Enforced:* readiness script.

**6. Shipping closure under ~1.5 MB.** Currently **785,599 B (0.749 MB), 49.9% of budget** — see
`dependency-audit.txt`. This is the one constraint with no hard failure mechanism; it is a bloat guideline. Do
not let that make it feel negotiable, because the artifacts that blow it tend to breach constraints 1–5 too.

**7. `<release>17</release>`.** Matches Cytoscape 3.10.4's parent pom. Class file major version ≤ 61.

**8. No network access in any test.** The 46 parse goldens are checked-in files; never call
`massql.gnps2.org/parse` from a test. Flaky CI destroys the credibility of a conformance number.

**9. No Cytoscape or OSGi dependency, ever.** `massql-java` must be physically unable to compile against
Cytoscape. That compile-time firewall is the only thing that reliably keeps `org.cytoscape` imports out of
engine code — it is the reason this repo is separate from `massql-app`.

---

## The shipping closure is two artifacts

| Artifact | Bytes | Why it is here |
|---|---|---|
| `com.github.chhh:javolution-core-java-msftbx:6.11.8` | 459,292 | The `ServiceLoader`-free `XMLStreamReaderImpl` the vendored parsers instantiate directly (constraint 1) |
| `org.antlr:antlr4-runtime:4.13.2` | 326,307 | Parser runtime (Tech_Step4) |

Both audited 2026-07-30: zero `META-INF/services`, zero `META-INF/versions`, zero native libraries. javolution
is a proper OSGi bundle with a unique symbolic name, and nothing else in Cytoscape provides `javolution.*`, so
there is no split package or version conflict. Its `org.osgi.core` dependency is not compile-scope and does not
enter the closure.

## MSDK is a vendoring source, not a dependency

`SPIKE.md` §5 planned to depend on `io.github.msdk:msdk-io-mzml`. **We do not.** Both readers vendor MSDK's
parser code instead — mzML in Tech_Step6, mzXML in Tech_Step7 — under the EPL-1.0 election, with provenance
headers and the upstream diff recorded in `docs/VENDORED.md`. MZmine vendored these same parsers, so
portability is proven by construction.

### <a name="guava"></a>Why: Guava is unavoidable via `msdk-datamodel`, and the real problem was not size

`msdk-datamodel` cannot link without Guava — `MsScan` declares `Range<Double> getScanningRange()` in the
**interface**, and `SimpleMsScan` holds a `Range` field and calls `Preconditions`. So this was never a
"try excluding it and see" case.

Guava plus its annotation satellites is **2,992,669 B (2.85 MB)**, taking the closure to 3.97 MB — 2.65× the
budget. But size was the least of it:

- **Cytoscape exports Guava 9.0.0** (`guava-osgi:9.0.0`, circa 2011). MSDK compiles against **27.1**. Importing
  cannot satisfy that; an 18-major-version gap is not a version range.
- **Guava 27.1 is itself an OSGi bundle** — `Bundle-SymbolicName: com.google.guava`,
  `Bundle-Version: 27.1.0.jre` — exporting `com.google.common.*` at `version="27.1.0"`. Embedding it therefore
  makes bnd emit `Import-Package: com.google.common.collect;version="[27.1,28)"` **by default**. Felix tries to
  satisfy that from the runtime, finds Guava 9, and **fails to resolve the bundle** unless the imports are
  explicitly negated. This is the classic `Embed-Dependency` footgun.
- **`jsr305` rides along exporting `javax.annotation`** (69 classes) — a known duplicate-exporter conflict
  source, since `javax.*` packages are provided from several places.

`cy-ndex-2` does embed plain Guava 30.1.1 successfully alongside core's 9.0.0, so private embedding is a proven
pattern here. But it hands Phase 2 three standing obligations (negate the imports, suppress the
`javax.annotation` export, never re-export `com.google.common.*`). Vendoring removes all three, and 2.85 MB,
on a code path where we were already vendoring the sibling parser.

---

## Adding a dependency

Before adding anything, in this order:

1. **Can it be avoided?** The MassQL Python stack was mostly *deleted*, not ported — pandas, numpy, lark and
   `py_expression_eval` all became ordinary Java. Assume the same until proven otherwise.
2. **Unpack the jar and check constraints 1, 3, 4, 5** — do not trust the pom. The Guava finding came from
   reading `MsScan.java`, not from a dependency tree.
3. **Trace it transitively.** Guava arrives via `msdk-datamodel`, not via the artifact you name. `mvn
   dependency:tree -Dverbose` and read the whole thing.
4. **Check what Cytoscape already exports, and at what version.** A package the framework provides at an
   incompatible version is worse than one it does not provide at all.
5. **Add an enforcer rule** for whatever you decided to keep out, so the decision survives you.
6. **Regenerate `dependency-audit.txt`** (`make audit`) and commit it.
