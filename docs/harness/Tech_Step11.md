# Tech Step 11 — Public API surface and CLI

## Goal

The public API `massql-app` will code against, plus a standalone CLI whose **argv shape, flags and stream
discipline** mirror `massql_query.py` — so [Step 12](Tech_Step12.md) can drive both with the same command line
and compare their outputs.

> ⛔ **Correction C42 — this read *"mirrors `massql_query.py`'s interface exactly — so the Step 12 differential
> is a literal `diff`"*, and a literal diff is impossible.** `ResultJson` emits **compact** JSON
> ([`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md)) while the reference uses `json.dump(…, indent=2)`, and Java and
> Python disagree on float formatting regardless (`1.0E-5` vs `1e-05`, the trailing `.0`). [Step 12](Tech_Step12.md)
> §1 accordingly compares **parsed values, never text**.
>
> The claim came from [`SPIKE.md`](SPIKE.md) §4 and was copied here. What actually has to match is the
> **interface**: same positional argument order, same flag names and defaults, same stdout/stderr split. That is
> what lets one test harness invoke both without special-casing.

## Prerequisites

| Step | Why |
|---|---|
| [Step 10](Tech_Step10.md) | Provides `ScanInfoResult` and `ResultJson` — the values the API returns and the CLI prints. |
| [Step 6](Tech_Step6.md), [Step 7](Tech_Step7.md) | Provide `SpectraStream`, **reshaped to `hasNext()`/`next()` by Correction C42**. `current()` and `format()` are gone; `next()` returns the view and throws `NoSuchElementException` past the end, which is what makes this step's single-pass rule enforceable. The returned view is **the same mutable object each call** — the type is deliberately *not* an `Iterator`, so it cannot be collected into a list of aliases. |

## Context

The public API is the contract with `massql-app`; everything behind it can churn. [`SPIKE.md`](SPIKE.md) §4 says to design it
deliberately for that reason. Two API rules matter downstream, and both are already satisfied by
[Step 10](Tech_Step10.md): boxed types so null is testable, and `ResultJson` as a published string contract the
app stores verbatim.

The **Java CLI** matters for a narrower reason: its **interface** must mirror the **reference implementation**
(`massql_query.py`) closely enough that one harness can invoke both with the same command line — same
positional order, same flags and defaults, same stream split. (Not a byte-level `diff` of their outputs; see
the C42 note under *Goal*.) The stream discipline is part of that: the Python wrapper deliberately redirects
MassQL's chatty progress output to stderr via `contextlib.redirect_stdout(sys.stderr)`, so stdout stays
pipeable. The Java CLI also gains an `--output FILE` mode this spec originally lacked (Correction C25b).

Keep the three layers distinct throughout this spec — SDK, Java CLI, reference implementation — per
*Terminology* in [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md). §1–§2 are the **SDK**; §3 is the **Java
CLI**; only the latter has an opinion about streams.

Governing sections: [`SPIKE.md`](SPIKE.md) §4.

## Scope

**In scope**
- `Massql.parse` / `Massql.execute` / **`Massql.executeWithDiagnostics`** / `Massql.run` — **four** entry points.
  The fourth was described in §2 but missing from this list and from §1's sketch (Correction **C42**).
- `cli.Main`: argument parsing, stream discipline, exit codes.
- Resource lifecycle guarantees on the public surface.

**Out of scope**
- Any new semantics. This step is wiring; a behavioural change here means a rule was missed in Steps 9 or 10 and
  belongs there.
- The integration tests that exercise the CLI contract — [Step 12](Tech_Step12.md) layer 4.
- Anything Cytoscape — Phase 2.

## Deliverables

| Path | Content |
|---|---|
| `src/main/java/…/massql/Massql.java` | The **four** entry points (C42 — this said "three", omitting `executeWithDiagnostics`) |
| `cli/src/main/java/…/massql/cli/Main.java` | The CLI. ⚠ **A separate Gradle project** (C43): it is versioned and released independently of the SDK, and because it depends on the SDK as an external project it can only compile against the **public API** — making the boundary `ApiEncapsulationTest` checks a compile error too |
| `src/test/java/…` — `MassqlApiTest`, `ApiEncapsulationTest`; and `cli/src/test/java/…` — `MainTest`, `MainStreamDisciplineTest`, `MainOutputFileTest`, `MainExitCodeTest`, `MainNoStackTraceOnStdoutTest`. **All seven** are named in *Tests required* | ⚠ This row listed **three** while *Tests required* names **seven** (Correction **C42**). `spec-audit` check 4 enforces the match the moment Done-when is ticked, so a name here that is never written fails the build — the C38 lesson |
| `docs/API.md` | The public surface, with the stability promise and a usage example |

## Specification

### 1. The public surface

The **shape** sketched in [`SPIKE.md`](SPIKE.md) §4 — `massql-app` is written against this, so keep it. Two of
§4's details are wrong and are corrected below; "keep the shape" means the try-with-resources pattern and the
one-shot convenience method, not §4's literal types.

> ⚠ **Correction C22:** `SpectraFile.open` now returns a **`SpectraStream` cursor**, not an object
> exposing whole-file `ms1()`/`ms2()` tables. The public shape below is unchanged in spirit — the
> caller still opens a resource in try-with-resources and passes it to `execute` — but the type is the
> stream, and `execute` consumes it exactly once.

```java
MassqlQuery q = Massql.parse(queryText);                    // throws MassqlParseException
try (SpectraStream s = SpectraFile.open(path)) {            // format sniffed; MGF | mzML | mzXML
    List<ScanInfoResult> rows = Massql.execute(q, s, opts);
}                                                           // one stream = one query; reopen for the next
List<ScanInfoResult> rows = Massql.run(queryText, path, opts);   // one-shot convenience
```

**A stream is single-pass, and that is now enforced by the type rather than by this paragraph.**
Correction **C42** reshaped `SpectraStream` to `hasNext()`/`next()`: once drained, `hasNext()` returns
`false` permanently and `next()` throws `NoSuchElementException`. So handing a spent stream to a second
`execute` fails loudly instead of returning an empty list that reads as "matched nothing" — which is what
this spec demanded and nothing previously delivered.

> ⚠ **Two rules here look contradictory and are not.** *"`execute` must not close the caller's stream —
> the app may run several queries against one file"* sits beside *"a stream is single-pass"*. Both hold,
> and the distinction is **file** versus **stream**:
>
> - **Ownership**: `execute` never closes what it did not open. The caller decides when the resource dies.
> - **Reuse**: one stream serves **one** query. Several queries over one *file* means reopening it —
>   `SpectraFile.open(path)` per query, each in its own try-with-resources.
>
> Say this in `docs/API.md` explicitly. The old whole-file design made re-querying free; this does not, and
> a Phase-2 author who assumes otherwise gets an exception rather than a wrong answer.

```java
public final class Massql {
    public static MassqlQuery parse(String queryText) throws MassqlParseException;
    public static List<ScanInfoResult> execute(MassqlQuery q, SpectraStream s, MassqlOptions opts);
    public static ExecutionResult executeWithDiagnostics(MassqlQuery q, SpectraStream s, MassqlOptions opts);
    public static List<ScanInfoResult> run(String queryText, Path path, MassqlOptions opts);
    private Massql() { }
}
```

> ⚠ **Two corrections to the sketch above, both of which it previously got wrong.**
>
> 1. **The parameter is `SpectraStream`, not `SpectraFile`** (Correction **C22**, restated as **C42**). The note
>    at the top of this section already said so, while the code block six lines below still declared
>    `SpectraFile` — and `SpectraFile` is a *factory* with one static `open`, never a parameter type. The error
>    originates in [`SPIKE.md`](SPIKE.md) §4, which this section was told to copy "exactly".
> 2. **`executeWithDiagnostics` belongs in the sketch**, not only in §2. It was absent here and from *Scope*,
>    so a reader working from either list would have shipped three entry points instead of four.

Rules on this surface:

- **`run` must close the file it opens**, including on exception — it is the convenience path and callers will not
  get a handle back to close.
- **`execute` must not close the caller's stream.** Ownership stays with whoever opened it. For several queries
  over one file, reopen per query — see the single-pass note above.
- **A null `opts` means defaults**, not an NPE. `MassqlOptions.defaults()` is `precursorTolPpm = 20.0`.
- **Return an immutable, possibly empty list.** Never null. A query matching nothing returns `List.of()`.
- **No MSDK, ANTLR or vendored type appears anywhere in a public signature.** Verify by grep; this is what keeps
  the reader and parser swappable, and it is a [`SPIKE.md`](SPIKE.md) §4 requirement, not a style preference.
- Results are ordered by **scan id ascending**, so output is deterministic and `diff`-able. Confirm the goldens are
  in that order (`small_mzml_results.json` starts at scan 3 and `plusrise_results.json` at 576, both ascending);
  if MassQL's own ordering differs anywhere, match MassQL and record it in `docs/API.md`.

> ⚠ **Correction C40 deleted this paragraph's subject.** It read: *"Where the query is `scaninfo(MS1DATA)`,
> `execute` returns rows with `ms1DataShape = true` so `ResultJson` emits the 4-key form."*
>
> **There is one shape.** MS1DATA and MS2DATA both emit the same 12 keys, discriminated by the `mslevel` value
> rather than by a shape flag, so `ScanInfoResult` has **no `ms1DataShape` component** and `ResultJson` takes no
> shape parameter. `execute` therefore needs no special case for MS1DATA at all — a simplification, not a
> restriction. Contract: [`RESULT_SCHEMA.md`](../RESULT_SCHEMA.md).

### 2. Diagnostics

The SDK logs nothing (`DEPENDENCY_POLICY.md` **constraint 2**). [Step 9](Tech_Step9.md) §5 produces diagnostics for
valid-but-degenerate queries; surface them on the API so the CLI can print them to stderr and the Phase-2 app can
show them in a dialog:

```java
public record ExecutionResult(List<ScanInfoResult> rows, List<String> diagnostics) { }
```

Add `Massql.executeWithDiagnostics(...)` returning this, keeping `execute` as the simple list-returning form.
Do not make callers parse diagnostics out of a log they cannot see.

> **Where the `diagnostics` list comes from — the seam this spec left unstated** (Correction **C35(b)**, which
> replaced Step 9's whole-file `execute` signature with a per-scan callback plus a summary record).
> [Step 9](Tech_Step9.md) is already implemented and returns:
>
> ```java
> public record ExecutionSummary(int qualifyingScans, int scansExamined, List<String> diagnostics) { }
> ```
>
> from `QueryExecutor.execute(...)`. So `ExecutionResult.diagnostics` is **`ExecutionSummary.diagnostics`
> passed straight through** — this step neither generates nor reformats diagnostic text; doing so would put
> the same message in two places with two wordings. Its job is to carry the list from the executor to the
> caller.
>
> `qualifyingScans` and `scansExamined` are **not** part of `ExecutionResult`: the row count already gives the
> first, and the second is a progress statistic with no consumer in the published contract. If the CLI ever
> wants "examined N scans, matched M", read it from the summary rather than widening the SDK's result record —
> the record is a contract the Phase-2 app depends on, and `ResultJson`'s 12 keys are frozen at
> [Step 10](Tech_Step10.md) §5.

### 3. The CLI

> **Layer note (Correction C25).** This section governs the **Java CLI** only. The **SDK** (§1–§2)
> writes to no stream at all — `DEPENDENCY_POLICY.md` constraint 2 — and the Phase-2 app consumes
> `Massql.execute` in-process, never this CLI. See *Terminology* in
> [`Tech_Step_INDEX.md`](Tech_Step_INDEX.md).

Mirror `massql_query.py`'s interface, plus one addition:

```
java -jar massql-java-cli-<version>.jar \
     <spectra-file> <query-file> [--precursor-tol-ppm 20] [--output FILE]
```

> ⚠ **This replaces a command that could never have run** (Correction **C43**). The original form,
> `java -cp massql-java.jar edu.ucsd.idekerlab.massql.cli.Main …`, puts only the thin SDK jar on the
> classpath — no `antlr4-runtime`, no `javolution` — so it would fail at the first parse. `make cli` builds
> the uber-jar (`./gradlew :cli:shadowJar`), which bundles both and is correct by construction.

| Aspect | Requirement |
|---|---|
| **stdout** (default output mode) | The JSON array, and **nothing else**. Ever. |
| **stderr** | All progress, warnings and diagnostics — on **every** output mode. Matches the Python wrapper's deliberate redirect. |
| Positional args | `<spectra-file>` then `<query-file>`, same order as Python |
| `--precursor-tol-ppm` | double, default **20.0** |
| `--output FILE` | JSON → `FILE`; **nothing** on stdout. See the atomicity rule below |
| `--output -` | Explicit stdout; identical to omitting the flag |
| Query file handling | Read whole file, `.strip()` equivalent; empty → error, exit 2 |
| Exit 0 | Success, **including a query that matched nothing** (`[]`) |
| Exit 1 | Execution failure — a file that exists and is readable but whose **content** will not parse |
| Exit 2 | Usage error — bad args, missing file, empty query, unsupported query, unwritable `--output` path |

> ⛔ **These two are not mechanically distinguishable from the exception type, so `Main` must separate them
> itself** (Correction **C42**). `SpectraFile.open` throws a plain `MassqlException` for *both* categories —
> `"no such file"` and `"file is empty"` (which this table puts at **2**) and `"cannot read"` /
> `"cannot determine format"` (which it puts at **1**). There is no subtype to switch on, and matching on
> message text would be worse than the problem.
>
> **Resolve it in `Main`, before opening**: check the path exists, is a regular file, is readable and is
> non-empty → any failure is exit **2**. Once past that gate, any `MassqlException` from reading the content is
> exit **1**. No public API change, and the rule reads the way the table does: *could the user have known from
> the command line alone?*

**Why stdout stays the default.** The Java CLI is a batch filter, so the Unix convention applies: stdout
carries the program's data, stderr carries diagnostics — which is what makes `| jq` work, and what the
reference implementation deliberately does. `--output` exists because a batch tool should not *force*
callers through a pipe, not because stdout-as-data is wrong here.

**`--output` is atomic.** Write to `FILE.tmp` in the **same directory** as the target, then
`Files.move(tmp, target, ATOMIC_MOVE)` — same-directory so the move stays within one filesystem. A
downstream consumer therefore never observes a partial or half-written file. On any failure, delete the
temp file and **leave no output file behind**: a truncated result that looks complete is worse than no
result at all.

**One writer, both modes — already satisfied, and by something simpler than this asked for.**
[Step 10](Tech_Step10.md) ships `ResultJson.write(List<ScanInfoResult>)` returning a **single `String`**, so both
sinks necessarily receive identical bytes; there is no second render to drift. State the requirement as the
**property** — one render, two sinks — rather than as the mechanism. ⚠ This paragraph specified "a single method
taking an `Appendable`" (Correction **C42**); an `Appendable` would be the right answer if the payload were
streamed, but it is built in memory and a `String` gives the guarantee outright.

The original wording, kept because the *reason* still applies:

> Render the JSON through a single method […] so the payload — including the trailing newline that matches
> the reference's `sys.stdout.write("\n")` — is byte-identical whether it lands on stdout or in a file. Two
> code paths would drift.

**The trailing newline is `Main`'s to add**, not `ResultJson`'s: the SDK returns a JSON document, and a
terminating newline is a *console* convention. Append it once, at the single point where the string meets its
sink, so both modes get it.

Unsupported-query behaviour: print the `MassqlParseException` message **naming the offending construct** to stderr
and exit 2. [Step 12](Tech_Step12.md) asserts the construct name appears in the output, so pass
`MassqlParseException.construct()` through rather than a generic message.

**Never print a Java stack trace to stdout.** A stack trace on stdout corrupts the JSON payload for any consumer
piping the output.

Use `System.exit()` only from `main`, never from library code — the Phase-2 app embeds this jar and an exit call
in a library path would kill Cytoscape.

**Which requires a seam this spec never gave** (Correction **C42**): `MainExitCodeTest` has to assert all four
exit codes, and it cannot do that if the only entry point terminates the JVM. Split it:

```java
public final class Main {
    /** Testable: returns the exit code, writes to the given streams, never calls System.exit. */
    static int run(String[] args, PrintStream out, PrintStream err) { … }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
```

Passing the streams in rather than touching `System.out` directly is what lets `MainStreamDisciplineTest`
capture both **without** `System.setOut`, which is global mutable state that makes tests order-dependent.
[Step 12](Tech_Step12.md) §3(b) still drives a real subprocess, because only that proves real file descriptors
stay apart — see the note below.

### 4. Resource lifecycle

[Step 12](Tech_Step12.md) tests opening and closing many files without leaking, and Phase 2's `shutDown()` depends
on it. So:

- `SpectraFile.close()` is idempotent (established in [Step 6](Tech_Step6.md)).
- `run` uses try-with-resources internally.
- `Main` closes the file before exiting on **every** path, including error paths.

## Known traps

- **Progress or warnings on stdout.** Silently corrupts the JSON payload in the default output mode.
- **A stack trace on stdout** on the error path. Same failure, harder to spot because the happy path looks fine.
- **Rendering the JSON twice**, once per output mode. They drift — usually on the trailing newline, which
  is exactly what the [Step 12](Tech_Step12.md) differential compares. One `Appendable` writer.
- **Writing `--output` in place rather than temp-then-rename.** A consumer polling the path reads a
  half-written array, and a crash mid-write leaves a truncated file that looks like a valid short result.
- **Leaving `FILE.tmp` behind** on the error path, or writing the temp file to the system temp directory
  instead of the target's directory — `ATOMIC_MOVE` across filesystems throws.
- **`execute` closing the caller's file.** Breaks the multi-query-per-file pattern the app needs.
- **`run` leaking the file it opened** on the exception path.
- **Exit non-zero when a query matched nothing.** An empty result is a valid answer: `[]`, exit 0.
- **Returning `null` for an empty result** instead of an empty list.
- **`System.exit` reachable from library code.** Fatal in an embedded OSGi context.
- **Argument order swapped** relative to Python. The differential invokes both with the same argv shape.

## Tests required

| Test | Type | Pins |
|---|---|---|
| `MassqlApiTest` | unit | `run` closes what it opened, including on exception (use a probe path or a wrapper asserting `close` ran); `execute` does **not** close the caller's file; null `opts` → defaults; empty result is an empty immutable list, never null; results ordered by ascending scan id. |
| `ApiEncapsulationTest` | unit | No MSDK / ANTLR / `io.vendor` type in any public signature — reflect over the public API and assert every parameter and return type is a JDK type or one of ours. Cheap, and it is the check that keeps the parser and reader swappable. |
| `MainTest` | unit | Arg parsing: order, `--precursor-tol-ppm` parsing and default, missing args → exit 2, empty query file → exit 2. |
| `MainStreamDisciplineTest` | unit | **Owns the payload-shape half of stream hygiene** (C25c). Capture both streams via the `PrintStream` parameters — not `System.setOut` — and assert stdout is **only** the JSON array plus a trailing newline, while a run that emits diagnostics puts them on stderr and leaves stdout parseable. ⚠ This row claimed [Step 12](Tech_Step12.md) *"delegates here rather than re-asserting it"*, which overstates it (Correction **C42**): Step 12 §3(b) keeps the assertions **only a forked process can make** — that real file descriptors keep the streams apart, which an in-process capture cannot establish however carefully it is written. The two are complements, not a delegation. |
| `MainOutputFileTest` | unit | `--output FILE` writes the JSON to `FILE` and leaves stdout **empty**; the bytes are **identical** to what the same run puts on stdout without the flag (this is what proves the single-writer rule); `--output -` behaves as stdout; no `FILE.tmp` survives a successful run; an unwritable path exits 2 with **no output file and no temp file** left behind. |
| `MainExitCodeTest` | unit | 0 on success; **0 with `[]` on a no-match query**; 1 on a malformed spectra file; 2 on an unsupported query, with the offending construct named on stderr. |
| `MainNoStackTraceOnStdoutTest` | unit | Force each failure mode; assert stdout contains no `at ` frame and no `Exception`. |

## Done when

- [ ] `make test` green (and `make verify` before calling the step done).
- [ ] The **four** entry points match §1's sketch — which is [`SPIKE.md`](SPIKE.md) §4's *shape* with C22's
      type. ⚠ This box read *"the three entry points match the SPIKE.md §4 sketch **exactly**"* and was
      **unsatisfiable** (Correction **C42**): §4 shows a `SpectraFile` parameter that no longer exists, and it
      omits `executeWithDiagnostics`. Matching it exactly would mean writing code that does not compile.
- [ ] `ApiEncapsulationTest` passes — no third-party type on the public surface. `Format` is package-private as
      of C42, so it must not appear either.
- [ ] **A drained stream handed to `execute` throws** rather than returning an empty list, and `docs/API.md`
      states the reopen-per-query rule.
- [ ] `Main.run(String[], PrintStream, PrintStream)` returns the exit code and **never** calls `System.exit`;
      only `main` does.
- [ ] CLI arg order, flag and default match `massql_query.py`; a manual run against `data/small.mzML` +
      `test_mzml.massql` produces JSON on stdout that parses.
- [ ] All four exit codes verified, including 0-with-`[]`.
- [ ] stdout is provably free of diagnostics and stack traces on every path.
- [ ] **`--output FILE` is byte-identical to stdout mode**, atomic (temp-then-`ATOMIC_MOVE`), and leaves
      neither a partial output file nor a `.tmp` behind on the failure path.
- [ ] `docs/API.md` documents the surface, the ownership rules, the exit codes, both output modes, and
      **which layer each stream rule governs** (Correction C25 — the SDK writes to no stream).

## References

- [`SPIKE.md`](SPIKE.md) §4 (the API sketch, the two API rules, the CLI signature)
- `massql_query.py` — `:119-133` (argparse shape), `:141` (the deliberate stdout→stderr redirect), `:194-195`
  (`json.dump(..., indent=2, allow_nan=False)` then a newline)
- [Step 10](Tech_Step10.md) §5 — the JSON contract this prints
- Consumer: [Step 12](Tech_Step12.md) layer 4 asserts the CLI contract; Phase 2 codes against this surface
