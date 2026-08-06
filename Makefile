# massql-java — the ONLY entry point for building and testing.
#
# Do not invoke `mvn` directly. Every command a developer or CI runs has a target here, so
# "build", "test" and "verify" mean one thing in a terminal, in the docs and in CI. If you
# need something this file does not do, ADD A TARGET rather than running maven by hand.
#
# `make verify` is the review entry point (Tech_Step13 §3). Filled in early, at Step 9,
# because ad-hoc `mvn` invocations had already started to diverge from what CI ran.

MVN := mvn -B
JAR := target/massql-java-0.1.0-SNAPSHOT.jar

# Minimum tests expected to execute. A run below this means suites are not being discovered,
# which is how a green build can prove nothing (Correction C26).
MIN_TESTS := 519

.DEFAULT_GOAL := help
.PHONY: help all build test it verify skipcheck audit spec-audit fixtures report clean \
        test-one it-one set-version deploy

## help: list the targets (default)
help:
	@echo "massql-java — make targets"
	@echo
	@grep -E '^## [a-z-]+:' $(MAKEFILE_LIST) | sed 's/^## /  /' | sort
	@echo
	@echo "  Single suites:  make test-one T=MgfReaderTest     make it-one T=ReaderParityIT"
	@echo
	@echo "  Never run mvn directly — add a target instead."

## all: alias for verify
all: verify

## build: compile and package the jar
build:
	$(MVN) package -DskipTests
	@echo "  -> $(JAR)"

## test: unit tests only (surefire, *Test.java). Seconds, for the edit loop.
test:
	$(MVN) test

## it: integration tests only (failsafe, *IT.java), skipping the unit suite. Fast gate re-check.
it:
	$(MVN) test-compile failsafe:integration-test failsafe:verify

## verify: unit + integration + JaCoCo + enforcer, then skipcheck, audit and spec-audit. What the reviewer runs.
verify:
	$(MVN) verify
	@$(MAKE) --no-print-directory skipcheck
	@$(MAKE) --no-print-directory audit
	@$(MAKE) --no-print-directory spec-audit

## skipcheck: assert tests ran and NOTHING was skipped (Correction C26)
skipcheck:
	@set -eu; \
	total=0; skipped=0; \
	for f in target/surefire-reports/*.txt target/failsafe-reports/*.txt; do \
	  [ -e "$$f" ] || continue; \
	  line=$$(grep -ohE 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+' "$$f" | head -1 || true); \
	  if [ -z "$$line" ]; then continue; fi; \
	  n=$$(printf '%s' "$$line" | sed -E 's/Tests run: ([0-9]+).*/\1/'); \
	  s=$$(printf '%s' "$$line" | sed -E 's/.*Skipped: ([0-9]+).*/\1/'); \
	  total=$$((total + n)); skipped=$$((skipped + s)); \
	done; \
	echo "  tests executed: $$total (skipped: $$skipped)"; \
	if [ "$$total" -lt $(MIN_TESTS) ]; then \
	  echo "  FAIL: only $$total tests ran, expected at least $(MIN_TESTS)." >&2; \
	  echo "        Suites are not being discovered — a green run here proves nothing." >&2; \
	  exit 1; \
	fi; \
	if [ "$$skipped" -ne 0 ]; then \
	  echo "  FAIL: $$skipped test(s) skipped." >&2; \
	  echo "        Under C26 a missing fixture must FAIL, so a skip means an assumeTrue or" >&2; \
	  echo "        @Disabled was reintroduced. Offending suites:" >&2; \
	  grep -l 'Skipped: [1-9]' target/surefire-reports/*.txt target/failsafe-reports/*.txt 2>/dev/null >&2 || true; \
	  exit 1; \
	fi

## audit: regenerate dependency-audit.txt and check the size budget
audit:
	@bash scripts/dependency-audit.sh

## spec-audit: assert the harness specs still describe the code (fixtures, counts, fallout)
spec-audit:
	@bash scripts/spec-audit.sh

## fixtures: download the two gitignored Ewing-lab fixtures (unstated licence, C26)
fixtures:
	@bash scripts/fetch-fixtures.sh

## report: print the review artifacts' headline results
report:
	@for f in docs/PARITY_REPORT.md docs/DIFFERENTIAL_REPORT.md; do \
	  if [ -f "$$f" ]; then \
	    echo "=== $$f ==="; sed -n '1,12p' "$$f"; echo; \
	  else \
	    echo "=== $$f === (not yet written)"; echo; \
	  fi; \
	done

## clean: remove build output
clean:
	$(MVN) clean

## set-version: stamp the pom version, e.g. make set-version V=1.2.3 (release.yml)
set-version:
	@[ -n "$(V)" ] || { echo "usage: make set-version V=1.2.3" >&2; exit 2; }
	@echo "$(V)" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$$' \
	  || { echo "refusing '$(V)': not plain semver X.Y.Z" >&2; exit 2; }
	$(MVN) versions:set -DnewVersion=$(V) -DgenerateBackupPoms=false

## deploy: publish to the Cytoscape nexus. Needs REPO_USER / REPO_PWD (release.yml only).
deploy:
	$(MVN) deploy -DskipTests

# Single-suite runs. T is the class name, e.g. make test-one T=MgfReaderTest
test-one:
	@[ -n "$(T)" ] || { echo "usage: make test-one T=SomeTest" >&2; exit 2; }
	$(MVN) test -Dtest=$(T) -DfailIfNoSpecifiedTests=false

it-one:
	@[ -n "$(T)" ] || { echo "usage: make it-one T=SomeIT" >&2; exit 2; }
	$(MVN) test-compile failsafe:integration-test failsafe:verify -Dit.test=$(T)
