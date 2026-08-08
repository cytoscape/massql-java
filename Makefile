# massql-java — the ONLY entry point for building and testing.
#
# Do not invoke `gradlew` directly. Every command a developer or CI runs has a target here, so
# "build", "test" and "verify" mean one thing in a terminal, in the docs and in CI. If you
# need something this file does not do, ADD A TARGET rather than running Gradle by hand.
#
# `make verify` is the review entry point (Tech_Step13 §3).
#
# Two artifacts, versioned INDEPENDENTLY (gradle.properties):
#   massql-java       the SDK, the thin jar massql-app embeds  -> make publish-sdk
#   massql-java-cli   the standalone CLI uber-jar              -> make publish-cli

GRADLE := ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help all build test it verify lint lint-fix coverage cli audit spec-audit fixtures \
        report clean test-one it-one set-version-sdk set-version-cli publish-sdk publish-cli

## help: list the targets (default)
help:
	@echo "massql-java — make targets"
	@echo
	@grep -E '^## [a-z-]+:' $(MAKEFILE_LIST) | sed 's/^## /  /' | sort
	@echo
	@echo "  Single suites:  make test-one T=MgfReaderTest     make it-one T=ReaderParityIT"
	@echo
	@echo "  Never run ./gradlew directly — add a target instead."

## all: alias for verify
all: verify

## build: compile and package both jars
build:
	$(GRADLE) assemble
	@ls -1 build/libs/*.jar cli/build/libs/*.jar 2>/dev/null | sed 's/^/  -> /'

## test: unit tests only (*Test.java in src/test). Seconds, for the edit loop.
test:
	$(GRADLE) test

## it: integration tests only (*IT.java in src/integrationTest). Fast gate re-check.
it:
	$(GRADLE) integrationTest

## verify: unit + integration + coverage gate + lint + banned deps, then audit and spec-audit. What the reviewer runs.
verify:
	$(GRADLE) check
	@$(MAKE) --no-print-directory audit
	@$(MAKE) --no-print-directory spec-audit

## lint: report style violations (Spotless is the whole style specification)
lint:
	$(GRADLE) spotlessCheck

## lint-fix: fix them
lint-fix:
	$(GRADLE) spotlessApply

## coverage: write the JaCoCo report (build/reports/jacoco/test/html/index.html)
coverage:
	$(GRADLE) jacocoTestReport
	@echo "  -> build/reports/jacoco/test/html/index.html"

## cli: build the standalone CLI uber-jar
cli:
	$(GRADLE) :cli:shadowJar
	@ls -1 cli/build/libs/*.jar | sed 's/^/  -> /'

## audit: regenerate dependency-audit.txt and check the SDK size budget
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
	@for f in docs/harness/PARITY_REPORT.md docs/harness/DIFFERENTIAL_REPORT.md; do \
	  if [ -f "$$f" ]; then \
	    echo "=== $$f ==="; sed -n '1,12p' "$$f"; echo; \
	  else \
	    echo "=== $$f === (not yet written)"; echo; \
	  fi; \
	done

## clean: remove build output
clean:
	$(GRADLE) clean

# Version stamping is per-artifact: a CLI fix must not force a new SDK coordinate, and vice versa.
## set-version-sdk: stamp the SDK version, e.g. make set-version-sdk V=1.2.3 (release.yml)
set-version-sdk:
	@$(MAKE) --no-print-directory stamp KEY=sdkVersion V=$(V)

## set-version-cli: stamp the CLI version, e.g. make set-version-cli V=1.2.3 (release.yml)
set-version-cli:
	@$(MAKE) --no-print-directory stamp KEY=cliVersion V=$(V)

.PHONY: stamp
stamp:
	@[ -n "$(V)" ] || { echo "usage: make set-version-$(KEY:Version=) V=1.2.3" >&2; exit 2; }
	@echo "$(V)" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$$' \
	  || { echo "refusing '$(V)': not plain semver X.Y.Z" >&2; exit 2; }
	@grep -q "^$(KEY)=" gradle.properties \
	  || { echo "$(KEY) not found in gradle.properties" >&2; exit 2; }
	@sed -i.bak "s|^$(KEY)=.*|$(KEY)=$(V)|" gradle.properties && rm -f gradle.properties.bak
	@grep "^$(KEY)=" gradle.properties | sed 's/^/  /'

## publish-sdk: publish the SDK jar to the Cytoscape nexus. Needs REPO_USER / REPO_PWD.
publish-sdk:
	$(GRADLE) :publish

## publish-cli: publish the CLI uber-jar to the Cytoscape nexus. Needs REPO_USER / REPO_PWD.
publish-cli:
	$(GRADLE) :cli:publish

# Single-suite runs. T is the class name, e.g. make test-one T=MgfReaderTest
test-one:
	@[ -n "$(T)" ] || { echo "usage: make test-one T=SomeTest" >&2; exit 2; }
	$(GRADLE) test --tests '*$(T)'

it-one:
	@[ -n "$(T)" ] || { echo "usage: make it-one T=SomeIT" >&2; exit 2; }
	$(GRADLE) integrationTest --tests '*$(T)'
