#!/usr/bin/env bash
# Regenerate dependency-audit.txt. Kept as a script so `make audit` and CI agree.
set -uo pipefail
cd "$(dirname "$0")/.."
BUDGET=1572864   # ~1.5 MB, DEPENDENCY_POLICY.md constraint 6

mvn -B -q dependency:list -DoutputFile=/tmp/massql-dl.txt -DincludeScope=runtime 2>/dev/null
ships() { sed 's/^ *//; s/ *--.*$//' /tmp/massql-dl.txt | grep -E ':(compile|runtime)$'; }
jarfor() {
  local g a v; g=$(echo "$1"|cut -d: -f1|tr '.' '/'); a=$(echo "$1"|cut -d: -f2); v=$(echo "$1"|cut -d: -f4)
  echo "$HOME/.m2/repository/$g/$a/$v/$a-$v.jar"
}

TOTAL=0; VIOLATIONS=0
{
echo "massql-java — dependency audit"
echo "Generated $(date -u +%Y-%m-%dT%H:%M:%SZ) by scripts/dependency-audit.sh"
echo
echo "Answers SPIKE.md §11 Q3 and \"did dependency complexity stay bounded?\" at the review gate."
echo
echo "================================================================"
echo "SHIPPING CLOSURE  (compile+runtime = what massql-app embeds)"
echo "================================================================"
printf "  %-46s %9s %9s %9s %7s\n" ARTIFACT SIZE SERVICES VERSIONS NATIVE
while read -r ga; do
  j=$(jarfor "$ga"); [ -f "$j" ] || continue
  sz=$(stat -f '%z' "$j" 2>/dev/null || stat -c '%s' "$j")
  svc=$(unzip -l "$j" 2>/dev/null | grep -c 'META-INF/services/')
  ver=$(unzip -l "$j" 2>/dev/null | grep -c 'META-INF/versions/')
  nat=$(unzip -l "$j" 2>/dev/null | grep -cE '\.(so|dylib|dll)$')
  TOTAL=$((TOTAL+sz))
  [ "$svc" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  [ "$ver" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  [ "$nat" != "0" ] && VIOLATIONS=$((VIOLATIONS+1))
  printf "  %-46s %9d %9s %9s %7s\n" "$(basename "$j")" "$sz" "$svc" "$ver" "$nat"
done < <(ships)
printf "  %-46s %9d\n" "TOTAL" "$TOTAL"
echo
awk -v t="$TOTAL" -v b="$BUDGET" 'BEGIN{printf "  %.3f MB of ~%.1f MB budget (%.1f%%) — %s\n", t/1048576, b/1048576, 100*t/b, (t<b?"PASS":"OVER BUDGET")}'
echo "  SERVICES / VERSIONS / NATIVE must all be 0 (constraints 1, 4, 3): $VIOLATIONS violation(s)"
echo
echo "  Test scope is excluded and must be: junit-platform-commons ships 10"
echo "  META-INF/versions entries and junit-jupiter-engine 2 META-INF/services."
echo
echo "See DEPENDENCY_POLICY.md for why MSDK, Guava, slf4j, JAXB and CDK are absent."
echo
echo "================================================================"
echo "FULL TREE"
echo "================================================================"
mvn -B -q dependency:tree 2>/dev/null | sed 's/^\[INFO\] //'
} > dependency-audit.txt

awk '/^  [0-9.]+ MB of/' dependency-audit.txt
echo "  -> dependency-audit.txt"
[ "$VIOLATIONS" -eq 0 ] || { echo "FAIL: $VIOLATIONS constraint violation(s)" >&2; exit 1; }
[ "$TOTAL" -lt "$BUDGET" ] || { echo "FAIL: closure exceeds budget" >&2; exit 1; }
