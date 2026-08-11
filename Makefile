# massql-java — the ONLY entry point for building and testing.
#
# Do not invoke `gradlew` directly. Every command a developer or CI runs has a target here, so
# "build" and "test" mean one thing in a terminal, in the docs and in CI. If you need something
# this file does not do, ADD A TARGET rather than running Gradle by hand.
#
#   make build              both projects' jars, sources and javadoc
#   make integration-test   the full suite and the coverage gate -- what CI runs
#
# Two artifacts, versioned INDEPENDENTLY (gradle.properties):
#   massql-java       the SDK, the thin jar consumers embed  -> make publish-sdk
#   massql-java-cli   the standalone CLI uber-jar            -> make publish-cli

GRADLE := ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help all build test integration-test lint lint-fix coverage cli deps fixtures \
        clean test-one it-one set-version-sdk set-version-cli publish-sdk publish-cli

## help: list the targets (default)
help:
	@echo "massql-java — make targets"
	@echo
	@grep -E '^## [a-z-]+:' $(MAKEFILE_LIST) | sed 's/^## /  /' | sort
	@echo
	@echo "  Single suites:  make test-one T=MgfReaderTest     make it-one T=ReaderParityIT"
	@echo
	@echo "  Never run ./gradlew directly — add a target instead."

## all: alias for integration-test
all: integration-test

## build: compile and package both projects -- jar, -sources.jar and -javadoc.jar each
#
# `assemble` produces all three per project: withJavadocJar()/withSourcesJar() in the build scripts
# wire them in, so javadoc and sources are never a separate step. The javadoc task also writes the
# browsable build/docs/javadoc/index.html on the way.
build:
	$(GRADLE) assemble
	@ls -1 build/libs/*.jar cli/build/libs/*.jar 2>/dev/null | sed 's/^/  -> /'
	@echo "  -> build/docs/javadoc/index.html"

## test: unit tests only (*Test.java in src/test). Seconds, for the edit loop.
test:
	$(GRADLE) test

## integration-test: everything -- unit + integration suites, coverage gate, lint, banned deps
#
# `check` rather than the suites by name: it pulls in both projects' integrationTest suites, the 90%
# jacocoTestCoverageVerification and checkBannedDependencies. Naming `integrationTest` alone would
# resolve to the ROOT project's task and silently skip the CLI contract suite.
integration-test:
	$(GRADLE) check

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

## deps: print the SDK's runtime dependency tree (trace a transitive arrival before adding anything)
deps:
	$(GRADLE) -q dependencies --configuration runtimeClasspath

## fixtures: download the two gitignored fixtures whose licence is unstated
fixtures:
	@bash scripts/fetch-fixtures.sh

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

## publish-sdk: publish the SDK jar to the Nexus repository. Needs REPO_USER / REPO_PWD.
publish-sdk:
	$(GRADLE) :publish

## publish-cli: publish the CLI uber-jar to the Nexus repository. Needs REPO_USER / REPO_PWD.
publish-cli:
	$(GRADLE) :cli:publish

# Single-suite runs. T is the class name, e.g. make test-one T=MgfReaderTest
test-one:
	@[ -n "$(T)" ] || { echo "usage: make test-one T=SomeTest" >&2; exit 2; }
	$(GRADLE) test --tests '*$(T)'

it-one:
	@[ -n "$(T)" ] || { echo "usage: make it-one T=SomeIT" >&2; exit 2; }
	$(GRADLE) integrationTest --tests '*$(T)'
