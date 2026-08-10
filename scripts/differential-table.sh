#!/usr/bin/env bash
# Render the per-format, per-column differential table -- Tech_Step13 §3, the review artifact.
#
# ⛔ RENDERS, NEVER RE-RUNS. Every number here comes from the JUnit XML that `gradle check` has just
# written for Tech_Step12's DifferentialIT. Re-running the CLI per fixture would re-process ~40,000
# spectra to learn what the suite already established, and would let the table disagree with the tests
# it claims to summarise. There is deliberately no committed snapshot: if DifferentialIT gains or
# loses a pair, this table follows on the next run with nothing to regenerate.
#
# Exit non-zero on any FAIL, and on an empty or short parse -- a confident, incomplete table is worse
# than a broken one.
set -uo pipefail
cd "$(dirname "$0")/.."

RESULTS="${1:-build/test-results/integrationTest}"
GOLDENS=src/test/resources/goldens/query-results
MIN_PAIRS=16          # Tech_Step12 §1. A short parse must fail, not print fewer rows.

XML="$RESULTS/TEST-edu.ucsd.idekerlab.massql.exec.DifferentialIT.xml"

echo "differential table -- Tech_Step12 layer 2, rendered from $RESULTS"
echo

if [ ! -f "$XML" ]; then
  echo "FAIL: $XML not found." >&2
  echo >&2
  echo "This table is rendered from DifferentialIT's results, so it needs a full run:" >&2
  echo "  make verify   (or: gradle check)" >&2
  echo "A filtered run such as --tests '*SomethingElse*' replaces the results directory." >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# Collect: per golden name, did every testcase bearing it pass, and what failed.
#
# The three parameterized methods in DifferentialIT all report the golden name as the testcase
# name, so grouping by name gives "this pair is green in every assertion made about it" -- which is
# exactly what a row of this table claims.
# ---------------------------------------------------------------------------------------------
STATUS=$(awk '
  /<testcase / {
    name = ""
    if (match($0, /name="[^"]*"/)) {
      name = substr($0, RSTART + 6, RLENGTH - 7)
    }
    current = name
    if (!(name in seen)) { seen[name] = 1; order[++n] = name; ok[name] = 1 }
    # A self-closing testcase passed; otherwise look for failure/error before its close.
    if ($0 ~ /\/>[[:space:]]*$/) current = ""
    next
  }
  /<failure|<error/ { if (current != "") { ok[current] = 0 } }
  /<\/testcase>/ { current = "" }
  END { for (i = 1; i <= n; i++) printf "%s\t%s\n", order[i], (ok[order[i]] ? "PASS" : "FAIL") }
' "$XML")

PAIRS=$(echo "$STATUS" | grep -c .)
if [ "$PAIRS" -lt "$MIN_PAIRS" ]; then
  echo "FAIL: parsed $PAIRS pair(s) from $XML, expected at least $MIN_PAIRS." >&2
  echo >&2
  echo "Either DifferentialIT lost pairs, or this script can no longer read its XML (a renamed" >&2
  echo "test, a moved results directory, a changed layout). Both must fail loudly: a table with" >&2
  echo "rows quietly missing reads as a clean bill of health." >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# Render. `n/a` marks a column the FORMAT cannot populate (Step 10 §6's population rules), which is
# distinct from `ok` and from `FAIL` -- so the population rules are visible in the table itself.
# ---------------------------------------------------------------------------------------------
printf "%-6s %-28s %-9s %-9s %-7s %-8s %-5s %-7s %-5s %-8s %-10s %-6s\n" \
  FORMAT GOLDEN ROWS SCAN PRECMZ MS1SCAN RT CHARGE TIC MSLEVEL BASE_PEAK "MS1_*"

FAILED=0
while IFS=$'\t' read -r name status; do
  [ -z "$name" ] && continue
  stem="${name%% *}"                       # drop the " @60.0ppm" suffix
  golden="$GOLDENS/$stem.json"

  # ⚠ The format label is an explicit mapping, never guessed from the golden's name: `plusrise` is
  # an MGF and contains neither "mgf" nor "mzxml", so a name-based guess mislabels it and reports
  # its MS1 columns as populated when the format cannot populate them at all.
  case "$stem" in
    plusrise_*)          fmt=MGF ;;
    *_mgf_*|*_mgf)       fmt=MGF ;;
    *mzxml*)             fmt=mzXML ;;
    *)                   fmt=mzML ;;
  esac

  if [ ! -f "$golden" ]; then
    echo "FAIL: golden $golden is missing but DifferentialIT reported on it." >&2
    exit 1
  fi
  rows=$(grep -o '"scan":' "$golden" | wc -l | tr -d ' ')

  # `n/a` is read from the DATA -- a column null in every row of the golden is one this
  # fixture/query cannot populate -- rather than inferred from format rules a reader would have to
  # trust. That covers MGF's absent MS1 scans and MS1DATA's absent precursor in one rule, and it
  # self-corrects if a fixture changes.
  col() {  # $1 = column name -> ok | n/a
    local nulls
    nulls=$(grep -c "\"$1\": null" "$golden")
    [ "$rows" -gt 0 ] && [ "$nulls" -eq "$rows" ] && echo "n/a" || echo "ok"
  }

  if [ "$status" = "FAIL" ]; then
    FAILED=$((FAILED+1))
    printf "%-6s %-28s %-9s %-9s %-7s %-8s %-5s %-8s %-5s %-7s %-10s %-6s\n" \
      "$fmt" "$stem" "$rows/$rows" FAIL FAIL FAIL FAIL FAIL FAIL FAIL FAIL FAIL
  elif [ "$rows" -eq 0 ]; then
    # Empty by design (the C37 strict-window evidence and the no-match pair). Marked distinctly:
    # there are no columns to judge, and printing `ok` across an empty row would overstate it.
    printf "%-6s %-28s %-9s %-9s %-7s %-8s %-5s %-8s %-5s %-7s %-10s %-6s\n" \
      "$fmt" "$stem" "0/0" "empty" "-" "-" "-" "-" "-" "-" "-" "-"
  else
    printf "%-6s %-28s %-9s %-9s %-7s %-8s %-5s %-8s %-5s %-7s %-10s %-6s\n" \
      "$fmt" "$stem" "$rows/$rows" ok "$(col precmz)" "$(col ms1scan)" ok "$(col charge)" \
      ok ok ok "$(col ms1_i)"
  fi
done <<< "$STATUS"

echo
echo "  $PAIRS pairs compared against the Python goldens; n/a = the format cannot populate that column."

if [ "$FAILED" -ne 0 ]; then
  echo >&2
  echo "differential table: $FAILED PAIR(S) FAILED." >&2
  echo "The SDK no longer reproduces MassQL. Do not loosen a tolerance to clear this --" >&2
  echo "see the per-column diff in the DifferentialIT output." >&2
  exit 1
fi

echo "  VERDICT: GREEN -- the SDK reproduces MassQL on every pair."
exit 0
