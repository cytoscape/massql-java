# Tech Step 11 — Public API surface and CLI

## Goal

The public API `massql-app` will code against, plus a standalone CLI that mirrors `massql_query.py`'s interface
exactly — so the [Step 12](Tech_Step12.md) differential is a literal `diff`.

## Prerequisites

| Step | Why |
|---|---|
| [Step 10](Tech_Step10.md) | Provides `ScanInfoResult` and `ResultJson` — the values the API returns and the CLI prints. |

## Context

The public API is the contract with `massql-app`; everything behind it can churn. `SPIKE.md` §4 says to design it
deliberately for that reason. Two API rules matter downstream, and both are already satisfied by
[Step 10](Tech_Step10.md): boxed types so null is testable, and `ResultJson` as a published string contract the
app stores verbatim.

The CLI matters for a narrower reason: it must mirror `massql_query.py` closely enough that comparing the two is
a plain `diff` of two files rather than a bespoke harness. That includes the stream discipline — the Python
wrapper deliberately redirects MassQL's chatty progress output to stderr (`massql_query.py:141`,
`contextlib.redirect_stdout(sys.stderr)`) so stdout stays pipeable.

Governing sections: `SPIKE.md` §4.

## Scope

**In scope**
- `Massql.parse` / `Massql.execute` / `Massql.run`.
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
| `src/main/java/…/massql/Massql.java` | The three entry points |
| `src/main/java/…/massql/cli/Main.java` | The CLI |
| `src/test/java/…/MassqlApiTest.java`, `…/cli/MainTest.java` | The test set below |
| `docs/API.md` | The public surface, with the stability promise and a usage example |

## Specification

### 1. The public surface

Exactly as sketched in `SPIKE.md` §4 — `massql-app` is written against this, so keep the shape:

```java
MassqlQuery q = Massql.parse(queryText);                    // throws MassqlParseException
try (SpectraFile f = SpectraFile.open(path)) {              // format sniffed; MGF | mzML | mzXML
    List<ScanInfoResult> rows = Massql.execute(q, f, opts);
}
List<ScanInfoResult> rows = Massql.run(queryText, path, opts);   // one-shot convenience
```

```java
public final class Massql {
    public static MassqlQuery parse(String queryText) throws MassqlParseException;
    public static List<ScanInfoResult> execute(MassqlQuery q, SpectraFile f, MassqlOptions opts);
    public static List<ScanInfoResult> run(String queryText, Path path, MassqlOptions opts);
    private Massql() { }
}
```

Rules on this surface:

- **`run` must close the file it opens**, including on exception — it is the convenience path and callers will not
  get a handle back to close.
- **`execute` must not close the caller's `SpectraFile`.** Ownership stays with whoever opened it; the app may run
  several queries against one file.
- **A null `opts` means defaults**, not an NPE. `MassqlOptions.defaults()` is `precursorTolPpm = 20.0`.
- **Return an immutable, possibly empty list.** Never null. A query matching nothing returns `List.of()`.
- **No MSDK, ANTLR or vendored type appears anywhere in a public signature.** Verify by grep; this is what keeps
  the reader and parser swappable, and it is a `SPIKE.md` §4 requirement, not a style preference.
- Results are ordered by **scan id ascending**, so output is deterministic and `diff`-able. Confirm the goldens are
  in that order (`small_mzml_results.json` starts at scan 3 and `plusrise_results.json` at 576, both ascending);
  if MassQL's own ordering differs anywhere, match MassQL and record it in `docs/API.md`.

Where the query is `scaninfo(MS1DATA)`, `execute` returns rows with `ms1DataShape = true` so
`ResultJson` emits the 4-key form ([Step 10](Tech_Step10.md) §5).

### 2. Diagnostics

The SDK logs nothing (`DEPENDENCY_POLICY.md` constraint 5). [Step 9](Tech_Step9.md) §5 produces diagnostics for
valid-but-degenerate queries; surface them on the API so the CLI can print them to stderr and the Phase-2 app can
show them in a dialog:

```java
public record ExecutionResult(List<ScanInfoResult> rows, List<String> diagnostics) { }
```

Add `Massql.executeWithDiagnostics(...)` returning this, keeping `execute` as the simple list-returning form.
Do not make callers parse diagnostics out of a log they cannot see.

### 3. The CLI

Mirror `massql_query.py`'s interface exactly:

```
java -cp massql-java.jar edu.ucsd.idekerlab.massql.cli.Main <spectra-file> <query-file> [--precursor-tol-ppm 20]
```

| Aspect | Requirement |
|---|---|
| **stdout** | The JSON array, and **nothing else**. Ever. |
| **stderr** | All progress, warnings and diagnostics. Matches the Python wrapper's deliberate redirect. |
| Positional args | `<spectra-file>` then `<query-file>`, same order as Python |
| `--precursor-tol-ppm` | double, default **20.0** |
| Query file handling | Read whole file, `.strip()` equivalent; empty → error, exit 2 |
| Exit 0 | Success, **including a query that matched nothing** (stdout is `[]`) |
| Exit 1 | Execution failure — unreadable/malformed file |
| Exit 2 | Usage error — bad args, missing file, empty query, unsupported query |

Unsupported-query behaviour: print the `MassqlParseException` message **naming the offending construct** to stderr
and exit 2. [Step 12](Tech_Step12.md) asserts the construct name appears in the output, so pass
`MassqlParseException.construct()` through rather than a generic message.

**Never print a Java stack trace to stdout.** A stack trace on stdout corrupts the JSON payload for any consumer
piping the output — and the differential test is exactly such a consumer.

Trailing newline after the JSON array, matching `massql_query.py:195` (`sys.stdout.write("\n")`).

Use `System.exit()` only from `main`, never from library code — the Phase-2 app embeds this jar and an exit call
in a library path would kill Cytoscape.

### 4. Resource lifecycle

[Step 12](Tech_Step12.md) tests opening and closing many files without leaking, and Phase 2's `shutDown()` depends
on it. So:

- `SpectraFile.close()` is idempotent (established in [Step 6](Tech_Step6.md)).
- `run` uses try-with-resources internally.
- `Main` closes the file before exiting on **every** path, including error paths.

## Known traps

- **Progress or warnings on stdout.** Silently corrupts the JSON payload and the differential.
- **A stack trace on stdout** on the error path. Same failure, harder to spot because the happy path looks fine.
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
| `MainStreamDisciplineTest` | unit | Capture both streams: stdout is **only** the JSON array plus a trailing newline; a run that emits diagnostics puts them on stderr and leaves stdout parseable. |
| `MainExitCodeTest` | unit | 0 on success; **0 with `[]` on a no-match query**; 1 on a malformed spectra file; 2 on an unsupported query, with the offending construct named on stderr. |
| `MainNoStackTraceOnStdoutTest` | unit | Force each failure mode; assert stdout contains no `at ` frame and no `Exception`. |

## Done when

- [ ] `mvn test` green.
- [ ] The three entry points match the `SPIKE.md` §4 sketch exactly.
- [ ] `ApiEncapsulationTest` passes — no third-party type on the public surface.
- [ ] CLI arg order, flag and default match `massql_query.py`; a manual run against `data/small.mzML` +
      `test_mzml.massql` produces JSON on stdout that parses.
- [ ] All four exit codes verified, including 0-with-`[]`.
- [ ] stdout is provably free of diagnostics and stack traces on every path.
- [ ] `docs/API.md` documents the surface, the ownership rules, the exit codes, and the stream discipline.

## References

- `SPIKE.md` §4 (the API sketch, the two API rules, the CLI signature)
- `massql_query.py` — `:119-133` (argparse shape), `:141` (the deliberate stdout→stderr redirect), `:194-195`
  (`json.dump(..., indent=2, allow_nan=False)` then a newline)
- [Step 10](Tech_Step10.md) §5 — the JSON contract this prints
- Consumer: [Step 12](Tech_Step12.md) layer 4 asserts the CLI contract; Phase 2 codes against this surface
