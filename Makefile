# massql-java. `make verify` is the review entry point (Tech_Step13 fills in the
# per-format differential table; for now it is mvn verify plus the dependency audit).
.PHONY: all test verify audit clean

all: verify

## Unit tests only (surefire, *Test.java). Expected to run in seconds.
test:
	mvn -B test

## What the reviewer runs: unit + integration (failsafe, *IT.java) + JaCoCo + enforcer.
verify:
	mvn -B verify
	@$(MAKE) --no-print-directory audit

## Regenerate dependency-audit.txt. Answers SPIKE.md §11 Q3 at the review gate.
audit:
	@bash scripts/dependency-audit.sh

clean:
	mvn -B clean
