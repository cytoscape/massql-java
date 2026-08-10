# Feature matrix — what parses, what executes, what rejects

> ⚠ **Generated from the code by `make feature-matrix`. Do not edit by hand.**
> Sources: `QueryFunction`, `DataSource`, `ConditionType`, `QualifierType` and
> `UnsupportedConstructs`. `FeatureMatrixTest` fails if this file and they disagree.

This build implements the MassQL **`scaninfo` subset**. Anything below marked rejected
fails at parse time with a message naming the construct — never silently ignored,
because a query that quietly does something other than what it says is worse than one
that refuses.

## Query functions

| Function | Status |
|---|---|
| `scaninfo(...)` | ✅ parses and executes |

## Data sources

| Source | Status |
|---|---|
| `MS1DATA` | ✅ parses and executes |
| `MS2DATA` | ✅ parses and executes |

Both emit the **same 12-key row shape**, discriminated by `mslevel` — see
[`RESULT_SCHEMA.md`](RESULT_SCHEMA.md).

## Conditions

| Condition | Status |
|---|---|
| `MS2PROD` | ✅ parses and executes |
| `MS2PREC` | ✅ parses and executes |
| `MS2NL` | ✅ parses and executes |
| `MS1MZ` | ✅ parses and executes |
| `RTMIN` | ✅ parses and executes |
| `RTMAX` | ✅ parses and executes |
| `SCANMIN` | ✅ parses and executes |
| `SCANMAX` | ✅ parses and executes |
| `CHARGE` | ✅ parses and executes |
| `POLARITY` | ✅ parses and executes |
| `MS2MZ` | ✅ accepted as an alias for `MS2PROD` |

## Qualifiers

| Qualifier | Status |
|---|---|
| `TOLERANCEMZ` | ✅ parses and executes |
| `TOLERANCEPPM` | ✅ parses and executes |
| `INTENSITYPERCENT` | ✅ parses and executes |
| `INTENSITYTICPERCENT` | ✅ parses and executes |
| `INTENSITYVALUE` | ✅ parses and executes |

## Rejected — parses, then fails by name

| Construct | Why |
|---|---|
| `scansum` | only scaninfo is supported in this version |
| `scannum` | only scaninfo is supported in this version |
| `scanmaxint` | only scaninfo is supported in this version |
| `scanmz` | only scaninfo is supported in this version |
| `scanrangesum` | only scaninfo is supported in this version; note MassQL's own scanrangesum ignores its TOLERANCE parameter and hardcodes 0.1 m/z bins |
| `<no function>` | a query function is required; only scaninfo(MS1DATA) / scaninfo(MS2DATA) are supported, not the bare MS1DATA / MS2DATA form |
| `X` | X/Y variables and the candidate enumerator are not supported in this version |
| `Y` | X/Y variables and the candidate enumerator are not supported in this version |
| `INTENSITYMATCH` | intensity matching is not supported in this version |
| `INTENSITYMATCHPERCENT` | intensity matching is not supported in this version |
| `INTENSITYMATCHREFERENCE` | intensity matching is not supported in this version |
| `MOBILITY` | ion mobility is not supported in this version |
| `OTHERSCAN` | OTHERSCAN requires a second retained index over pre-filter MS1 data |
| `CARDINALITY` | cardinality constraints are not supported in this version |
| `MATCHCOUNT` | cardinality constraints are not supported in this version |
| `EXCLUDED` | EXCLUDED is not supported in this version |
| `MASSDEFECT` | mass-defect qualifiers are not supported in this version |
| `ANY` | the ANY wildcard is not supported in this version |
| `formula()` | formula() requires monoisotopic mass tables and is not supported in this version |
| `aminoaciddelta()` | aminoaciddelta() requires mass tables and is not supported in this version |
| `peptide()` | peptide() requires mass tables and is not supported in this version |
| `nested subquery` | nested sub-queries are not supported in this version |

Every one of these is rejected with the construct named, so the message says what to
change rather than reporting a bare syntax error.
