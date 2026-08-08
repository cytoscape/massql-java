#!/usr/bin/env bash
# Regenerate dependency-audit.txt. Kept as a script so `make audit` and CI agree.
#
# SDK ONLY. This measures the closure massql-app embeds under OSGi, which is what
# DEPENDENCY_POLICY.md's size budget is about. The cli/ subproject's uber-jar is a standalone
# download and deliberately not an audited number -- including it would inflate the very figure the
# budget is written against with bytes that never reach a Cytoscape bundle.
set -uo pipefail
cd "$(dirname "$0")/.."
BUDGET=1572864   # ~1.5 MB, DEPENDENCY_POLICY.md constraint 6

# `:dependencyAudit` emits `coord <TAB> bytes <TAB> path` for the ROOT project's runtimeClasspath.
# Gradle hands us resolved File objects, so unlike the Maven version this needs no path guessing --
# its cache layout (.../files-2.1/<group>/<artifact>/<version>/<sha1>/x.jar) is not reconstructable.
CLOSURE=$(./gradlew -q --console=plain :dependencyAudit 2>/dev/null | grep -E $'^[^\t]+\t[0-9]+\t/')
if [ -z "$CLOSURE" ]; then
  echo "FAIL: :dependencyAudit produced no artifacts -- the audit cannot pass vacuously." >&2
  exit 1
fi

TOTAL=0; VIOLATIONS=0
{
echo "massql-java — dependency audit"
# Deliberately NO timestamp. This file is checked in (Tech_Step13), and `make verify` runs
# this script, so a timestamp would dirty the working copy on every single build and train
# everyone to ignore its diffs. Content-deterministic means it changes ONLY when the
# dependency closure actually changes -- which is the one time you want to see a diff.
echo "Regenerate with: make audit"
echo
echo "Answers SPIKE.md §11 Q3 and \"did dependency complexity stay bounded?\" at the review gate."
echo
echo "================================================================"
echo "SHIPPING CLOSURE  (the SDK's runtime closure = what massql-app embeds)"
echo "================================================================"
printf "  %-46s %9s %9s %9s %7s\n" ARTIFACT SIZE SERVICES VERSIONS NATIVE
while IFS=$'\t' read -r coord sz jar; do
  [ -f "$jar" ] || continue
  svc=$(unzip -l "$jar" 2>/dev/null | grep -c 'META-INF/services/')
  ver=$(unzip -l "$jar" 2>/dev/null | grep -c 'META-INF/versions/')
  nat=$(unzip -l "$jar" 2>/dev/null | grep -cE '\.(so|dylib|dll)$')
  TOTAL=$((TOTAL+sz))
  [ "$svc" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  [ "$ver" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  [ "$nat" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  printf "  %-46s %9d %9s %9s %7s\n" "$(basename "$jar")" "$sz" "$svc" "$ver" "$nat"
done <<< "$CLOSURE"
printf "  %-46s %9d\n" "TOTAL" "$TOTAL"
echo
awk -v t="$TOTAL" -v b="$BUDGET" 'BEGIN{printf "  %.3f MB of ~%.1f MB budget (%.1f%%) — %s\n", t/1048576, b/1048576, 100*t/b, (t<b?"PASS":"OVER BUDGET")}'
echo "  SERVICES / VERSIONS / NATIVE must all be 0 (constraints 1, 4, 3): $VIOLATIONS violation(s)"
echo
echo "  Test scope is excluded and must be: junit-platform-commons ships 10"
echo "  META-INF/versions entries and junit-jupiter-engine 2 META-INF/services."
echo
echo "  The ANTLR TOOL must NOT appear below. Gradle's antlr plugin makes the compile and runtime"
echo "  configurations extendFrom the tool configuration, which would add antlr4, ST4,"
echo "  antlr-runtime 3.x, treelayout and icu4j -- 15.44 MB, ~21x this budget. build.gradle severs"
echo "  that inheritance; this table is the regression test."
echo
echo "See DEPENDENCY_POLICY.md for why MSDK, Guava, slf4j, JAXB and CDK are absent."
echo
echo "================================================================"
echo "FULL TREE"
echo "================================================================"
./gradlew -q --console=plain dependencies --configuration runtimeClasspath 2>/dev/null \
  | sed -n '/^runtimeClasspath/,/^$/p'
} > dependency-audit.txt

awk '/^  [0-9.]+ MB of/' dependency-audit.txt
echo "  -> dependency-audit.txt"
[ "$VIOLATIONS" -eq 0 ] || { echo "FAIL: $VIOLATIONS constraint violation(s)" >&2; exit 1; }
[ "$TOTAL" -lt "$BUDGET" ] || { echo "FAIL: closure exceeds budget" >&2; exit 1; }
