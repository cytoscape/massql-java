# massql-java — the ONLY entry point for building and testing. Do not invoke `gradlew` directly;
# add a target here instead.
#
# Two artifacts, versioned independently (gradle.properties):
#   massql-java       the SDK, the thin jar consumers embed  -> make publish-sdk
#   massql-java-cli   the standalone CLI uber-jar            -> make publish-cli

# On Windows, make runs its recipes through cmd.exe, which cannot parse "./gradlew" -- the
# workflow's `shell: bash` only chooses the shell that launches make, not the one make uses. So the
# wrapper is selected per platform. $(OS) is set to Windows_NT there and unset everywhere else.
ifeq ($(OS),Windows_NT)
GRADLE := gradlew.bat --console=plain
else
GRADLE := ./gradlew --console=plain
endif

.DEFAULT_GOAL := help
.PHONY: help all build test integration-test lint lint-fix coverage \
        clean set-version-sdk set-version-cli publish-local publish-sdk publish-cli

## help: list the targets (default)
help:
	@echo "massql-java — make targets"
	@echo
	@grep -E '^## [a-z-]+:' $(MAKEFILE_LIST) | sed 's/^## /  /' | sort
	@echo
	@echo "  Never run ./gradlew directly — add a target instead."

## all: alias for integration-test
all: integration-test

## build: compile and package both projects -- jar, -sources.jar and -javadoc.jar each
build:
	$(GRADLE) assemble
	@echo "  -> build/libs/massql-java.jar"
	@echo "  -> build/libs/massql-java-sources.jar"
	@echo "  -> build/libs/massql-java-javadoc.jar"
	@echo "  -> cli/build/libs/massql-java-cli.jar"
	@echo "  -> cli/build/libs/massql-java-cli-sources.jar"
	@echo "  -> cli/build/libs/massql-java-cli-javadoc.jar"
	@echo "  -> build/docs/javadoc/index.html"

## test: unit tier only (*Test.java). Seconds, for the edit loop.
test:
	$(GRADLE) test

## integration-test: everything -- both tiers, coverage gate, lint
#
# Both tiers live in src/test/java and are selected by filename: *IT.java is the integration tier,
# everything else the unit tier. Helpers both tiers use live in the testsupport package.
#
# `check` rather than the tasks by name: naming `integrationTest` alone would resolve to the ROOT
# project's task and silently skip the CLI contract tests.
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

## publish-local: install both artifacts into ~/.m2 -- a dry run for the Nexus publishes
publish-local:
	$(GRADLE) publishToMavenLocal
	@echo "  -> ~/.m2/repository/org/cytoscape/"

## publish-sdk: publish the SDK jar to the Nexus repository.
#
# Credentials: ~/.gradle/gradle.properties (<repo-id>User / <repo-id>Pwd), or REPO_USER / REPO_PWD.
# A -SNAPSHOT version goes to cytoscape_snapshots, anything else to cytoscape_releases.
publish-sdk:
	$(GRADLE) :publish

## publish-cli: publish the CLI uber-jar to the Nexus repository (credentials as for publish-sdk)
publish-cli:
	$(GRADLE) :cli:publish
