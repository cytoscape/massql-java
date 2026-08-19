# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [cli-0.0.1] - 2026-08-19

### Added

- Inaugural first version of **`massql-java-cli`**, a standalone uber-jar that runs a MassQL
  `scaninfo` query from the command line — given a spectra file and a query it writes the results as
  JSON to stdout or to a file — see [`docs/CLI.md`](docs/CLI.md).

## [sdk-0.0.1] - 2026-08-19

### Added

- Inaugural first version of **`massql-java`**, a pure-Java SDK that runs MassQL `scaninfo` queries
  against `.mgf`, `.mzML` and `.mzXML` files and returns the 12-column result set — see
  [`docs/SDK.md`](docs/SDK.md).
