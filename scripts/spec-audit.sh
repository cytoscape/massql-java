#!/usr/bin/env bash
#
# spec-audit.sh -- assert the harness specs still describe the code and the fixtures.
#
# WHY THIS EXISTS
#
# The harness runs on a fallout protocol: "a Correction is not done until the affected specs are
# edited." That is a rule about discipline, and it has already failed once in the worst possible
# way. C18 recorded that `Comparator.NONE` does not exist and named Tech_Step9 as affected. Step 9
# was never edited. Five steps later the identical finding was rediscovered from scratch and written
# down again as C35(a) -- while TWO statements in Tech_Step4 still *required* the removed value.
#
# Nothing failed. Nothing could fail: the specs are prose, and prose has no test suite. So this
# script gives the three drifts that round actually produced a way to break the build.
#
# The C26 lesson applies to this script itself: a guard that cannot fail is worse than no guard,
# because it reads as coverage. Every check here is verified to fail on an injected drift -- see
# the DEMONSTRATING FAILURE section at the bottom -- and any check that cannot name the specific
# thing that drifted is not worth having.

set -euo pipefail

cd "$(dirname "$0")/.."

FAILURES=0
CHECKS=0

fail() {
    printf '  FAIL: %s\n' "$1" >&2
    FAILURES=$((FAILURES + 1))
}

pass() {
    CHECKS=$((CHECKS + 1))
    printf '  ok: %s\n' "$1"
}

SPECS=docs/harness
FIXTURES=src/test/resources/fixtures
GOLDENS=src/test/resources/goldens
# The fixture inventory lives in the UNIT test source set even though ReaderParityIT (integration)
# is its main consumer: two unit tests assert properties of the inventory itself, and
# integrationTest depends on test, never the reverse.
PARITY_INVENTORY=src/test/java/edu/ucsd/idekerlab/massql/io/ParityFixtures.java

# Every tree that can hold a test class, across both projects -- but only those that EXIST.
# `find` exits non-zero on a missing root, and under `set -o pipefail` that poisons the whole
# pipeline below, making every lookup report "missing" even for classes that are right there.
TEST_ROOTS=""
for candidate in src/test src/integrationTest cli/src/test cli/src/integrationTest; do
    [ -d "$candidate" ] && TEST_ROOTS="$TEST_ROOTS $candidate"
done
TEST_ROOTS="${TEST_ROOTS# }"
[ -n "$TEST_ROOTS" ] || fail "no test source root exists at all -- check 4 would pass vacuously"

# A moved or renamed file must FAIL here rather than making the check vacuous -- grep on a missing
# path prints an error, returns 0 matches, and every count comparison below then passes trivially.
# This is the same class of hole as the phantom test-class names behind C38.
for required in "$PARITY_INVENTORY"; do
    [ -f "$required" ] || fail "spec-audit cannot find $required -- a path this script hardcodes has
        moved. Fix the path: without it the dump-count checks below compare against zero and pass
        silently."
done

echo "spec-audit: do the specs still describe the code?"
echo

# --------------------------------------------------------------------------------------------
# Check 1 -- every fixture and golden on disk is NAMED in the specs that inventory them.
#
# Catches the Step 9 drift: micro_zeroint.mgf and micro_ms1var.mzML were created, dumped, and
# used by passing tests while Tech_Step2 (which specifies the fixtures) and FIXTURES.md (which
# inventories them) said nothing about either. A fixture no document mentions is a fixture the
# next person deletes as unused.
#
# Direction matters: this checks disk -> docs. The reverse (a doc naming a file that no longer
# exists) is check 1b, because the two fail for different reasons and want different messages.
# --------------------------------------------------------------------------------------------

echo "1. every fixture and golden on disk is named in Tech_Step2.md and FIXTURES.md"

# The specs legitimately name a fixture either in full ("micro_zeroint.mgf") or by stem inside a
# grouped row ("`micro_p64`, `micro_zlib`, `micro_p64_zlib`, `micro_nested` ... same generator, one
# variable changed each"). Both are real documentation, so accept either -- requiring the extension
# would report drift where there is none, and a check that cries wolf gets disabled.
names_fixture() {   # $1 = doc, $2 = filename
    grep -qF "$2" "$1" && return 0
    grep -qF "${2%.*}" "$1" && return 0
    return 1
}

for f in "$FIXTURES"/micro/*.mgf "$FIXTURES"/micro/*.mzML "$FIXTURES"/micro/*.mzXML \
         "$FIXTURES"/edge/*; do
    [ -e "$f" ] || continue
    name=$(basename "$f")
    names_fixture "$SPECS/Tech_Step2.md" "$name" \
        || fail "fixture '$name' exists but Tech_Step2.md never names it (add it to Deliverables)"
    names_fixture docs/harness/FIXTURES.md "$name" \
        || fail "fixture '$name' exists but FIXTURES.md never names it (add it to the micro table)"
done

# Goldens are inventoried either by name or by the glob a whole family was generated under
# ("each fixtures/micro/* -> output/micro_*_results.json"). Expand those globs rather than
# demanding 15 literal rows the specs deliberately compress.
names_golden() {    # $1 = doc, $2 = filename
    grep -qF "$2" "$1" && return 0
    while read -r pat; do
        [ -n "$pat" ] || continue
        # shellcheck disable=SC2254
        case "$2" in $pat) return 0 ;; esac
    done < <(grep -ohE '[a-z0-9_]*\*[a-z0-9_]*_results\.json' "$1" | sort -u)
    return 1
}

for f in "$GOLDENS"/query-results/*.json; do
    [ -e "$f" ] || continue
    name=$(basename "$f")
    names_golden "$SPECS/Tech_Step2.md" "$name" \
        || fail "golden '$name' exists but Tech_Step2.md's golden matrix (§5) never names it"
    names_golden "$SPECS/Tech_Step12.md" "$name" \
        || fail "golden '$name' exists but Tech_Step12.md's fixture table never names it -- so the
        differential does not know to compare it"
done

for f in "$GOLDENS"/queries/*.massql; do
    [ -e "$f" ] || continue
    name=$(basename "$f")
    grep -qrF "$name" "$SPECS"/ \
        || fail "query '$name' exists but no spec file names it"
done

for f in "$GOLDENS"/loader-parity/*.json.gz; do
    [ -e "$f" ] || continue
    name=$(basename "$f" .json.gz)
    grep -qF "$name" "$SPECS/Tech_Step8.md" \
        || fail "parity dump for '$name' exists but Tech_Step8.md §2 does not list it in the sweep"
done

# 1b -- the reverse direction, restricted to fixture-shaped names so this stays a real check
# rather than a spellcheck over English prose.
for name in $(grep -ohE '\bmicro[A-Za-z0-9_]*\.(mgf|mzML|mzXML)\b' \
                  "$SPECS"/Tech_Step2.md docs/harness/FIXTURES.md | sort -u); do
    [ -e "$FIXTURES/micro/$name" ] \
        || fail "Tech_Step2.md or FIXTURES.md names fixture '$name', which does not exist on disk"
done

[ "$FAILURES" -eq 0 ] && pass "all fixtures, goldens, queries and dumps are documented both ways"
echo

# --------------------------------------------------------------------------------------------
# Check 2 -- every STATED COUNT matches reality.
#
# Catches the contradiction this check was written for: FIXTURES.md said "15 dumps" in one place
# and "All 14 fixtures" in another while the true number was 16, and PARITY_REPORT.md said 15.
# Three documents, three numbers, none right. Hand-maintained counts rot silently because nothing
# recomputes them -- so recompute them.
#
# The authority is the filesystem, cross-checked against ParityFixtures.FIXTURES_WITH_DUMPS: if
# the map and the directory disagree, the sweep is not covering what it appears to.
# --------------------------------------------------------------------------------------------

echo "2. stated dump counts match the filesystem and ParityFixtures"

DUMPS_ON_DISK=$(find "$GOLDENS/loader-parity" -name '*.json.gz' | wc -l | tr -d ' ')
DUMPS_IN_IT=$(grep -c 'FIXTURES_WITH_DUMPS.put' "$PARITY_INVENTORY" | tr -d ' ')

if [ "$DUMPS_ON_DISK" != "$DUMPS_IN_IT" ]; then
    fail "$DUMPS_ON_DISK dump files on disk but ParityFixtures lists $DUMPS_IN_IT -- the parity
        sweep is not covering every dump, or a dump is missing"
else
    pass "$DUMPS_ON_DISK dumps on disk == $DUMPS_IN_IT entries in FIXTURES_WITH_DUMPS"
fi

# Any "N dumps" / "N fixtures" claim in a doc that inventories them must equal the real count.
#
# BOLD IS THE SIGNAL, and it is load-bearing rather than cosmetic. A bolded total is an assertion
# about the present ("**16 dumps**"); an unbolded one is usually historical narration that is
# SUPPOSED to keep its old number ("C32 extended them from 8 fixtures to 14"). Matching every
# "N fixtures" would flag those dated statements as drift, and a check that flags correct prose
# gets switched off.
#
# The three alternatives below are all phrasings in live use, and the list is that long because
# the first version matched only two of them -- so `**16 dumps.**` (punctuation INSIDE the bold)
# slipped through and the check was silently vacuous on the very file whose contradictory counts
# motivated it. Verified against every current claim; see DEMONSTRATING FAILURE.
for doc in docs/harness/FIXTURES.md docs/harness/PARITY_REPORT.md "$SPECS/Tech_Step8.md"; do
    while read -r claim; do
        [ -n "$claim" ] || continue
        n=$(printf '%s' "$claim" | grep -oE '[0-9]+' | head -1)
        if [ "$n" != "$DUMPS_ON_DISK" ]; then
            fail "$doc claims '$claim' but there are $DUMPS_ON_DISK dumps.
        Three documents once held three different numbers here (15, 14 and the true 16), which is
        why this is checked rather than maintained by hand."
        fi
    done < <(grep -ohE \
        -e '\*\*[0-9]+[[:space:]]+(dumps|fixtures)[[:punct:]]*\*\*' \
        -e '\*\*[0-9]+\*\*[[:space:]]+(dumps|fixtures)' \
        -e '\*\*[0-9]+ of them\*\*' "$doc" || true)
done

PARITY_ROWS=$(sed -n '/^| Fixture | Format/,/^$/p' docs/harness/PARITY_REPORT.md | grep -c '^| `' || true)
if [ "$PARITY_ROWS" != "$DUMPS_ON_DISK" ]; then
    fail "PARITY_REPORT.md's per-fixture table has $PARITY_ROWS rows but there are $DUMPS_ON_DISK
        dumps -- a fixture is in the gate without a row reporting its result"
else
    pass "PARITY_REPORT.md has one table row per dump ($PARITY_ROWS)"
fi

# The coverage gate must still exist and still be wired into `check`.
#
# This replaces a check on the Makefile's old MIN_TESTS floor, which was a hand-maintained test
# count that rotted on every test added. The gate is the same kind of protection -- a floor a green
# build cannot dodge -- but the number no longer needs maintaining. What DOES need asserting is that
# the rule is present at all: deleting the violationRules block, or dropping the `check` dependency,
# leaves a build that still says "BUILD SUCCESSFUL" while enforcing nothing.
COVERAGE_MIN=$(grep -A3 "counter = 'INSTRUCTION'" build.gradle \
    | grep -oE 'minimum = [0-9.]+' | grep -oE '[0-9.]+' || true)
if [ -z "$COVERAGE_MIN" ]; then
    fail "build.gradle has no INSTRUCTION coverage minimum -- the gate that replaced the old
        MIN_TESTS floor is gone, so nothing stops coverage from decaying silently"
elif ! grep -q "dependsOn tasks.named('jacocoTestCoverageVerification')" build.gradle; then
    fail "the coverage gate exists but is not wired into 'check', so 'make verify' never runs it"
else
    pass "coverage gate present and wired into check (INSTRUCTION minimum $COVERAGE_MIN)"
fi
echo

# --------------------------------------------------------------------------------------------
# Check 3 -- every correction that NAMES A STEP is referenced in that step's file.
#
# ** This is the check with the most leverage, and the one that would have caught C18. **
#
# A correction in Tech_Step_INDEX.md whose body links Tech_StepX.md is asserting "this changes
# Step X". If Tech_StepX.md does not mention that correction, the fallout protocol was not
# carried out -- exactly what happened when C18 named Step 9 and Step 9 was never edited, so the
# same finding came back five steps later as C35(a).
#
# Parsing rule: a correction runs from its own **Cnn ...** heading to the next one. Any
# Tech_StepX.md link inside that span is a claim of fallout, and Tech_StepX.md must cite Cnn.
# --------------------------------------------------------------------------------------------

echo "3. every step a correction names must cite that correction back (the C18 check)"

INDEX="$SPECS/Tech_Step_INDEX.md"

# TWO DESIGNS WERE TRIED AND REJECTED FIRST. Both failures are instructive enough to keep.
#
# (1) "Require every correction to carry an explicit `Fallout:` line, enumerating the specs it
#     changes." Sound in principle, but 30 of the 41 corrections would have needed a declaration
#     invented for them retroactively. Inventing an affected-set to satisfy a checker produces a
#     ledger that looks authoritative and is guesswork. Rejected.
#
# (2) "Derive each correction's affected set from the specs that already cite it." This one is
#     worse, and subtly so: it passes BY CONSTRUCTION. Any correction nobody ever propagated has
#     an empty derived set and therefore no obligation, so the exact failure the check exists to
#     catch is the one it defines out of existence. That is the C26 mistake wearing a new hat --
#     a guard that cannot fail, reading as coverage. Rejected.
#
# What is left is the one signal that is both already present and independent of the thing being
# checked: **a correction's body links `Tech_StepX.md`.** That link is the author's own claim that
# Step X is involved, written before anyone knew whether Step X would be edited. So:
#
#     correction body links Tech_StepX.md  ==>  Tech_StepX.md must reference that correction
#
# Non-circular, needs no new annotation, and it catches C18: C18's body names Step 9 §3, Step 9
# was never edited, and five steps later the same finding was rediscovered as C35(a).
#
# The residue is that a link can also be a background pointer ("Full analysis in Step 1 §3a")
# rather than a fallout claim. Those are listed below, one at a time, each with a reason -- a
# short list in the open, which a reviewer can argue with. What is NOT acceptable is widening the
# match until nothing fails.

# Links that are references rather than fallout claims. Format: "Cnn:Tech_StepN.md".
POINTERS_NOT_FALLOUT="
C10:Tech_Step1.md    C10 says 'Full analysis in Step 1 §3a' -- Step 1 is where the analysis
                     already lives, not a spec that needs changing. Step 1 is also complete.
C16:Tech_Step7.md    C16 cites Step 7 as PRECEDENT ('exactly as Step 7 already does for mzXML'),
                     so the link is an appeal to existing behaviour. Step 7's own MSDK finding is
                     C23, which Step 7 does cite.
C24:Tech_Step11.md   C24 notes in passing that the same bug would also corrupt the Java CLI's
                     stdout payload. Stream discipline is Step 11 §3's own subject and C25's
                     fallout; C24's real target is Step 8, which cites it.
"

is_pointer() {  # $1 = Cnn, $2 = Tech_StepN.md
    printf '%s' "$POINTERS_NOT_FALLOUT" | grep -qE "(^|[[:space:]])$1:$2([[:space:]]|$)"
}

# THE RATCHET (from C38 onward)
#
# Link inference protects only the corrections that happen to link a step. 12 of the first 37 link
# none, so they carry no obligation at all -- and C22, the largest correction in the project
# (streaming; it reshaped Steps 5-11), is one of them. Audited by hand: all 12 are in fact
# propagated, C22 by prose rather than links, so this is a prospective hole and not a live defect.
#
# Rather than retrofit 37 entries -- which means INVENTING affected-sets for ~30 of them, producing
# a ledger that looks authoritative and is guesswork -- the obligation becomes explicit from C38
# onward, the correction that motivated the check:
#
#     **Fallout:** Tech_Step5.md, Tech_Step10.md
#     **Fallout:** none -- <reason>
#
# For C$RATCHET_FROM+, a MISSING Fallout line is itself a failure, so a new correction cannot end up
# with zero obligations by saying nothing. Below the threshold, link inference continues unchanged.
#
# The declaration is authoritative for the corrections that carry one: it is what distinguishes a
# fallout claim from a background pointer, and only the author knows which was meant. Writing
# "Fallout: none" on a correction that plainly changes a spec is then a visible false statement in
# the ledger rather than a silent omission -- the same trade made in VendoredProvenanceTest, where a
# file declares itself "Not vendored" instead of the test keeping a filename allowlist.
RATCHET_FROM=38

claims=0
no_claim=0
declared=0

while IFS=: read -r lineno corr; do
    [ -n "$corr" ] || continue

    # The body runs to the next correction heading OR the next `## ` section, whichever comes
    # first. Bounding at the section heading matters: C10 is the last correction in its section,
    # and without that guard attribution ran on through "Established facts" and the whole rest of
    # the file, blaming C10 for six specs it never mentions.
    body=$(awk -v start="$lineno" '
        NR < start { next }
        NR > start && /^\*\*C[0-9]+/ { exit }
        /^## / && NR > start { exit }
        { print }
    ' "$INDEX")

    num=${corr#C}
    decl=$(printf '%s\n' "$body" | grep -oiE '^>? *\*\*Fallout:\*\*.*' | head -1 || true)

    # ---- ratchet branch: C38+ must declare, and the declaration is what gets enforced.
    if [ "$num" -ge "$RATCHET_FROM" ]; then
        if [ -z "$decl" ]; then
            fail "$corr has no '**Fallout:**' line, and every correction from C$RATCHET_FROM onward must
        declare one. Add either:
            **Fallout:** Tech_StepN.md, Tech_StepM.md
            **Fallout:** none -- <why nothing needs editing>
        Without it a correction carries no obligation and nothing can tell whether the affected
        specs were edited -- which is how C18 named Step 9, Step 9 was never touched, and the same
        finding came back five steps later as C35(a)."
            continue
        fi
        declared=$((declared + 1))
        case "$decl" in *[Nn]one*) continue ;; esac

        for step in $(printf '%s' "$decl" | grep -oE 'Tech_Step[0-9]+\.md' | sort -u); do
            [ -f "$SPECS/$step" ] \
                || { fail "$corr declares fallout on '$step', which does not exist"; continue; }
            claims=$((claims + 1))
            grep -qE "\b${corr}([^0-9]|$)" "$SPECS/$step" || fail \
                "$corr DECLARES fallout on $step, but $step never references $corr.
        The declaration is the promise; this is the check that it was kept. Edit $step, or drop it
        from the Fallout line if it turned out not to be affected."
        done
        continue
    fi

    # ---- legacy branch: pre-C38, infer the claim from links in the body.
    linked=$(printf '%s\n' "$body" | grep -oE 'Tech_Step[0-9]+\.md' | sort -u || true)

    if [ -z "$linked" ]; then
        no_claim=$((no_claim + 1))
        continue
    fi

    for step in $linked; do
        [ -f "$SPECS/$step" ] || { fail "$corr links '$step', which does not exist"; continue; }
        is_pointer "$corr" "$step" && continue
        claims=$((claims + 1))

        # Accept "C37", "C37a", "C37(a)", "Correction C37" -- the forms the specs actually use --
        # while REFUSING a longer number that merely starts with this one.
        #
        # The first version was `\bC${n}[a-z(]?`, and the optional suffix made it a PREFIX match: for
        # C1 it matched the "C1" inside "C18", so C1 counted as cited in eight specs that never
        # mention it. Every single-digit correction was affected. Nothing was actually hidden -- the
        # four single-digit fallout claims (C6, C7, C8) do all cite correctly -- but the check was
        # unsound and would have passed a real gap. Requiring a non-digit after the number fixes it:
        # "C37a" and "C37(a)" still match, "C370" does not.
        grep -qE "\b${corr}([^0-9]|$)" "$SPECS/$step" || fail \
            "$corr names $step, but $step never references $corr.
        This is the C18 failure exactly: the correction was recorded and the affected spec was
        never edited, so the next implementer reads a spec that contradicts working code. Either
        edit $step, or -- if the link is a background reference rather than a fallout claim --
        add '$corr:$step' to POINTERS_NOT_FALLOUT in this script WITH a reason."
    done
done < <(grep -nE '^\*\*C[0-9]+' "$INDEX" | sed -E 's/^([0-9]+):\*\*(C[0-9]+).*/\1:\2/')

if [ "$FAILURES" -eq 0 ]; then
    pass "$claims fallout claim(s) all cite back -- $declared correction(s) declared explicitly (C$RATCHET_FROM+ ratchet), $no_claim legacy correction(s) name no step"
fi
echo

# --------------------------------------------------------------------------------------------
# Check 4 -- every test class a spec names must EXIST.
#
# Catches Correction C38, and it is the cheapest check here with the largest blast radius.
# Tech_Step9's "Tests required" table named 14 classes; 10 of them were never written. The
# coverage had been consolidated into five classes during implementation -- which is fine -- but
# the table kept the original names, so "does ConditionCoverageTest exist?" was a question with
# no answer that nobody thought to ask.
#
# Under that cover, THREE of the ten 9a/9b conditions (MS2PREC, CHARGE, MS2NL) had no execution
# test at all, while "every 9a and 9b condition has a positive and a negative test" sat ticked in
# Done-when. A phantom class name is not a cosmetic error: it is a place where missing coverage
# looks like present coverage.
#
# A row for a class that was deliberately folded elsewhere or deferred is written in parentheses
# -- "(Ms2NlTest -- folded into QueryExecutorTest)" -- and skipped here, so the original intent
# stays reviewable without asserting a file that should not exist.
# --------------------------------------------------------------------------------------------

echo "4. every test class named in a COMPLETED step's spec exists (the C38 check)"

# SCOPED TO COMPLETED STEPS ONLY, and the boundary is read from the specs rather than hardcoded:
# a step is complete when its "Done when" section has zero unticked `- [ ]` boxes. Steps 10-13 are
# not built yet, so their test classes are SUPPOSED not to exist -- enforcing there would fail the
# build on unwritten work, and the natural response would be to weaken the check until it passed.
# Reading the checkboxes makes the scope widen by itself as each step lands.
#
# Tech_Step_INDEX.md is excluded: it narrates history, so it legitimately quotes class names that
# were later renamed ("SpectraFileCloseTest, renamed under C22"). Its job is to record what was
# said at the time, not to describe the tree as it stands.

# JUnit API names, not test classes of ours. `ParameterizedTest` is an annotation; a spec saying
# "table-driven via @ParameterizedTest" is correct and must not be read as a missing file.
JUNIT_API="Test ParameterizedTest RepeatedTest TestFactory TestTemplate NestedTest"

is_junit_api() {
    for j in $JUNIT_API; do [ "$1" = "$j" ] && return 0; done
    return 1
}

phantoms=0
checked_classes=0
skipped_pending=0

for spec in "$SPECS"/Tech_Step[0-9]*.md; do
    # Complete iff Done-when has no unticked boxes AND at least one ticked (i.e. it has a real
    # checklist that has been worked through).
    done_sec=$(sed -n '/^## Done when/,/^## References/p' "$spec")
    open=$(printf '%s' "$done_sec" | grep -c '^- \[ \]' || true)
    ticked=$(printf '%s' "$done_sec" | grep -c '^- \[x\]' || true)
    if [ "$open" -ne 0 ] || [ "$ticked" -eq 0 ]; then
        skipped_pending=$((skipped_pending + 1))
        continue
    fi

    while read -r cls; do
        [ -n "$cls" ] || continue
        is_junit_api "$cls" && continue

        # A spec-era name whose coverage moved is recorded with an explicit arrow mapping in the
        # spec's "Renamed and folded test classes" block:
        #
        #     | `Ms2NlTest` | → `QueryExecutorTest` | three methods rather than a class |
        #
        # One greppable form, so the exemption is a documented redirect rather than a silent skip.
        # Deleting these rows instead would lose the record of what was originally required, which
        # is worth more than a tidy table -- and it is how a real gap gets tidied out of sight.
        if grep -qE "\`$cls\`[^|]*\| *→" "$spec"; then
            continue
        fi

        checked_classes=$((checked_classes + 1))
        # EVERY test source root, not just src/test. Integration tests live in
        # src/integrationTest (their own Gradle source set) and the CLI project has its own trees --
        # searching one root would report every IT named in a completed spec as missing, which is
        # noise, and would then be "fixed" by weakening the check that C38 exists to enforce.
        if ! find $TEST_ROOTS -name "$cls.java" 2>/dev/null | grep -q .; then
            fail "$(basename "$spec") names test class '$cls', which exists in none of: $TEST_ROOTS.
        Either write it, or -- if its coverage was folded into another class -- write the row as
        '($cls -- folded into <RealClass>)' so the intent stays reviewable. A phantom class name is
        where MISSING coverage looks like PRESENT coverage: under exactly this cover, MS2PREC,
        CHARGE and MS2NL had no execution test while Step 9's table implied all ten conditions did
        (C38)."
            phantoms=$((phantoms + 1))
        fi
    done < <(grep -ohE '\b[A-Z][A-Za-z0-9]*(Test|IT)\b' "$spec" | sort -u)
done

if [ "$phantoms" -eq 0 ]; then
    pass "$checked_classes class name(s) in completed steps all resolve ($skipped_pending step(s) still pending, not enforced)"
fi
echo

# --------------------------------------------------------------------------------------------
# Check 5 -- every review DOCUMENT a completed step names must exist.
#
# This is check 4 applied to prose deliverables, and it exists because that gap has already been
# exercised: docs/VENDORED.md was a Step 6 deliverable, Step 7 ticked "VENDORED.md unchanged",
# Step 13 listed it as a review artifact, all eleven vendored source headers told the reader to
# go there -- and the file did not exist for three steps. Nothing noticed, because a ticked
# checkbox is a claim and nothing was comparing claims to the filesystem.
#
# Scoped to completed steps for the same reason as check 4: DIFFERENTIAL_REPORT.md (Step 12) and
# API.md (Step 11) are SUPPOSED to be absent right now.
#
# ⚠ The pattern is `docs/[A-Za-z_/]+\.md` -- note the SLASH. It was `[A-Za-z_]+` until Correction
# C41 moved six docs into docs/harness/ and four artifacts into docs/harness/oracle/, at which
# point a subdirectory path stopped matching and this check would have silently covered less than
# before while still reporting green. The move would have looked like a fix and verified nothing.
#
# This check only sees references written as a repo-root path. Check 7 is what covers relative
# links, which is how most references are actually written.
# --------------------------------------------------------------------------------------------

echo "5. every review document a completed step names by repo-root path exists"

missing_docs=0
checked_docs=0

for spec in "$SPECS"/Tech_Step[0-9]*.md; do
    done_sec=$(sed -n '/^## Done when/,/^## References/p' "$spec")
    open=$(printf '%s' "$done_sec" | grep -c '^- \[ \]' || true)
    ticked=$(printf '%s' "$done_sec" | grep -c '^- \[x\]' || true)
    [ "$open" -ne 0 ] || [ "$ticked" -eq 0 ] && continue

    while read -r doc; do
        [ -n "$doc" ] || continue
        checked_docs=$((checked_docs + 1))
        if [ ! -f "$doc" ]; then
            fail "$(basename "$spec") names review document '$doc', which does not exist.
        Its step is marked complete, so this is a ticked checkbox with nothing behind it -- the
        exact state docs/VENDORED.md was in for three steps while every vendored source header
        pointed readers at it (C38)."
            missing_docs=$((missing_docs + 1))
        fi
    done < <(grep -ohE '\bdocs/[A-Za-z_/]+\.md' "$spec" | sort -u)
done

if [ "$missing_docs" -eq 0 ]; then
    pass "$checked_docs repo-root document path(s) in completed steps all resolve"
fi
echo

# --------------------------------------------------------------------------------------------
# Check 6 -- every non-empty result golden carries exactly the 12 keys, in the frozen order.
#
# Catches Correction C40's entire defect class. The result contract was specifiable in four places
# (SPIKE.md, the oracle's RESULT_SCHEMA.md, Tech_Step10, and the goldens themselves) and drifted
# into THREE different answers: 4 keys, 9 keys, and the actual 12. The 9-key
# small_mzml_ms1_results.json was the only non-conforming golden and nothing was comparing it to
# anything -- it had shipped that way since Step 2.
#
# The key list is read from docs/RESULT_SCHEMA.md, not hardcoded here, so this script and
# ResultSchemaContractTest share the single definition rather than duplicating it. Hardcoding it
# would recreate the exact problem C40 was about.
#
# Empty goldens ([] -- micro_mzml_edge, dp00570_mzxml_empty) are skipped: they carry no keys, and
# both are DELIBERATE assertions that a query matches nothing, not missing data.
# --------------------------------------------------------------------------------------------

echo "6. every non-empty golden carries exactly the 12 keys, in the frozen order (the C40 check)"

SCHEMA_DOC=docs/RESULT_SCHEMA.md
GOLDEN_DIR=src/test/resources/goldens/query-results

if [ ! -f "$SCHEMA_DOC" ]; then
    fail "$SCHEMA_DOC is missing -- it is the single definition of the result contract (C40)"
else
    # The frozen order line: the fenced block after "in this order**:".
    EXPECTED_KEYS=$(awk '
        /in this order\*\*:/ { want = 1; next }
        want && /^```$/      { infence = !infence; if (!infence) exit; next }
        want && infence      { print }
    ' "$SCHEMA_DOC" | tr -d ' \n')

    if [ -z "$EXPECTED_KEYS" ]; then
        fail "could not read the frozen key order from $SCHEMA_DOC (expected a fenced block after
        'in this order**:'). That block is the contract; this check cannot run without it."
    else
        n_expected=$(printf '%s' "$EXPECTED_KEYS" | tr ',' '\n' | grep -c .)
        checked_goldens=0
        for g in "$GOLDEN_DIR"/*.json; do
            [ -e "$g" ] || continue
            actual=$(python3 -c "
import json,sys
d=json.load(open(sys.argv[1]))
print('' if not d else ','.join(d[0].keys()))
" "$g")
            [ -z "$actual" ] && continue    # empty golden -- deliberate, no keys to check
            checked_goldens=$((checked_goldens + 1))
            if [ "$actual" != "$EXPECTED_KEYS" ]; then
                fail "$(basename "$g") does not carry the frozen key set/order.
        expected: $EXPECTED_KEYS
        actual:   $actual
        The contract is ONE uniform 12-key shape for both MS1DATA and MS2DATA, discriminated by
        mslevel, with no key ever absent (C40, $SCHEMA_DOC). A golden with a different shape means
        either the oracle wrapper regressed or the golden predates the contract."
            fi
        done
        [ "$FAILURES" -eq 0 ] && pass \
            "$checked_goldens non-empty golden(s) carry the $n_expected keys from $SCHEMA_DOC in order"
    fi
fi
echo

# --------------------------------------------------------------------------------------------
# Check 7 -- every markdown link resolves: file, anchor, and inside the repo.
#
# Added by Correction C41, which moved six docs into docs/harness/ and four artifacts into
# docs/harness/oracle/. The question that prompted it was the right one to ask: "how will you
# verify no dead or broken links are left over after the files are re-org'd?" -- and the honest
# answer is that a promise is not verification, so this exists instead.
#
# Run on the tree BEFORE that move, it immediately found two defects that had nothing to do with
# the move: a `[C22](#c22)` link written without ever adding the `<a id="c22">` anchor, and five
# links pointing OUT of the repo at ../../../massql/. Both were invisible to every other check.
#
# Three dimensions, each resolved relative to the file containing the link -- which is what a
# markdown link actually means, and why the docs/-prefix grep in check 5 can only ever see a
# fraction of the references:
#
#   (a) the target file exists
#   (b) an #anchor matches a heading slug or an explicit <a id="...">
#   (c) the target is INSIDE the repo -- an escaping link is how CONVERSION_NOTES.md was cited
#       for four steps while living somewhere CI could never see (C26's lesson, unlearned)
#
# Deliberately covers ALL markdown under docs/, not only completed steps: a dead link is dead
# whether or not its step has run. Pending-step *deliverables* are a different matter and stay
# with checks 4 and 5, which know about Done-when.
# --------------------------------------------------------------------------------------------

echo "7. every markdown link resolves -- file, anchor, and inside the repo (the C41 check)"

link_report=$(python3 - <<'PY'
import re, sys
from pathlib import Path

root = Path('.').resolve()
mds = [p for p in Path('docs').rglob('*.md')] + [p for p in Path('.').glob('*.md')]
link_re = re.compile(r'\[([^\]]*)\]\(([^)]+)\)')

def anchors_of(p):
    """Explicit <a id> plus GitHub-style heading slugs."""
    t = p.read_text()
    out = set(re.findall(r'<a\s+id="([^"]+)"', t))
    for h in re.findall(r'(?m)^#{1,6}\s+(.*)$', t):
        h = re.sub(r'\[([^\]]*)\]\([^)]*\)', r'\1', h)   # a link in a heading contributes its TEXT only
        out.add(re.sub(r'[^\w\s-]', '', h.lower()).strip().replace(' ', '-'))
    return out

cache, problems, ok = {}, [], 0
for md in sorted(mds):
    for _, target in link_re.findall(md.read_text()):
        t = target.strip()
        if t.startswith(('http://', 'https://', 'mailto:')):
            continue
        frag = None
        if '#' in t:
            t, frag = t.split('#', 1)
        dest = md.resolve() if t == '' else (md.parent / t).resolve()
        if not dest.exists():
            problems.append(f"{md} -> {target}   (no such file)"); continue
        if dest != root and root not in dest.parents:
            problems.append(f"{md} -> {target}   (ESCAPES the repo)"); continue
        if frag and dest.suffix == '.md':
            cache.setdefault(dest, anchors_of(dest))
            if frag.lower() not in cache[dest]:
                problems.append(f"{md} -> {target}   (no such anchor)"); continue
        ok += 1

print(ok)
for p in problems:
    print(p)
PY
)
link_ok=$(printf '%s' "$link_report" | head -1)
link_bad=$(printf '%s' "$link_report" | tail -n +2 | grep -c . || true)

if [ "$link_bad" -ne 0 ]; then
    # `|| [ -n "$line" ]` is load-bearing: `printf '%s'` emits no trailing newline, so plain
    # `while read` returns non-zero on the final line and SKIPS ITS BODY. With exactly one broken
    # link -- the common case -- this printed the count and named nothing, which is precisely the
    # "a check that cannot name the specific thing that drifted is not worth having" failure this
    # script's own header warns about. Found by probing with one bad link and getting silence.
    while read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        fail "broken link: $line"
    done < <(printf '%s' "$link_report" | tail -n +2)
    fail "$link_bad broken link(s). A moved or renamed document is the usual cause; an ESCAPES
        result means a link points outside the repo, which CI cannot follow (C26)."
else
    pass "$link_ok markdown link(s) resolve -- file, anchor and in-repo"
fi
echo

# --------------------------------------------------------------------------------------------
# Check 8 -- every anchor a spec attributes to massql_query.py actually appears in it.
#
# Added by C42, for a defect C40 caused and C40's own fallout pass missed. Editing
# massql_query.py (+46 lines) silently invalidated the LINE NUMBERS the specs cited: 9 of 13
# distinct citations went stale, several by 40-65 lines, and the worst landed INSIDE comments C40
# had just added -- a reader following "massql_query.py:194-195" for the trailing-newline rule
# arrived at a sentence about micro.mgf phantom scan-id collisions.
#
# Check 3 could not see it. It verifies that a spec cites the correction's LABEL, not that the
# spec's other facts survived the code change.
#
# The fix was to stop citing line numbers at all. Specs now name a STABLE ANCHOR -- a function
# name or a distinctive expression -- which survives edits above it and, unlike a line number, can
# be checked. This is that check.
#
# NOTE the direction: it verifies the anchor EXISTS, not that it says what the spec claims. A
# renamed or deleted function fails here; a function whose body changed meaning does not. That is
# a real limit, and the honest mitigation is that anchors are chosen to be the thing the claim is
# about, so a meaning change usually renames or removes them.
# --------------------------------------------------------------------------------------------

echo "8. every massql_query.py anchor a spec cites exists in it (the C42 check)"

ORACLE_WRAPPER=../massql/massql_query.py

if [ ! -f "$ORACLE_WRAPPER" ]; then
    # The oracle directory is deliberately outside the repo and absent in CI (C26/C41), so this
    # check is advisory there rather than a failure -- it cannot verify what it cannot read.
    pass "skipped: $ORACLE_WRAPPER not present (the oracle dir is not part of the deliverable)"
else
    # Any leftover line-number citation is itself the defect this check exists to prevent.
    stale=$(grep -rhoE 'massql_query\.py[`]*:[0-9]+' docs/ | sort -u || true)
    if [ -n "$stale" ]; then
        while read -r s; do
            [ -n "$s" ] || continue
            fail "a spec still cites '$s' by LINE NUMBER. Line numbers rot the moment that file is
        edited -- 9 of 13 did, under C40. Cite a stable anchor instead: a function name, or a
        distinctive expression such as 'for col in (\"precmz\", \"ms1scan\", \"charge\")'."
        done < <(printf '%s\n' "$stale")
    fi

    # Every backticked anchor attributed to the wrapper must be findable in it.
    missing=0
    checked_anchors=0
    while read -r anchor; do
        [ -n "$anchor" ] || continue
        checked_anchors=$((checked_anchors + 1))
        grep -qF "$anchor" "$ORACLE_WRAPPER" || {
            fail "specs cite \`$anchor\` in massql_query.py, but it is not there.
        Either the wrapper changed and the specs did not follow, or the anchor was mistyped."
            missing=$((missing + 1))
        }
    done < <(grep -rhoE "\`massql_query\.py\`'s \`[^\`]+\`" docs/ \
             | sed -E "s/^.*'s \`//; s/\`$//" | sort -u)

    if [ "$missing" -eq 0 ] && [ -z "$stale" ]; then
        pass "$checked_anchors distinct anchor(s) all present in massql_query.py, and no line-number citations remain"
    fi
fi
echo

# --------------------------------------------------------------------------------------------

if [ "$FAILURES" -ne 0 ]; then
    printf 'spec-audit: %d FAILURE(S).\n' "$FAILURES" >&2
    echo >&2
    echo "The specs no longer describe the code. Fix the specs -- do not weaken this script." >&2
    echo "A spec that contradicts working code is worse than no spec: it argues the next" >&2
    echo "implementer into breaking something that works." >&2
    exit 1
fi

printf 'spec-audit: GREEN (%d checks).\n' "$CHECKS"

# --------------------------------------------------------------------------------------------
# DEMONSTRATING FAILURE
#
# Every check here was verified to fail on injected drift before being trusted, and one of them
# needed two attempts to earn that. The C26 lesson is that a guard which cannot fail is worse than
# no guard: it reads as coverage. So each check must be shown breaking, not argued to work.
#
# Check 2 is the cautionary case. Its first regex matched `**16** fixtures` but not
# `**16 dumps.**` -- punctuation inside the bold -- so it was silently vacuous on FIXTURES.md,
# the one file whose three contradictory counts motivated writing it. It only surfaced by running
# the probe below and getting no failure. If you touch that regex, re-run ALL FIVE probes:
# passing one phrasing proves nothing about the others.
#
#   1a. sed -i '' 's/micro_zeroint.mgf/micro_ZZZ.mgf/' docs/harness/FIXTURES.md
#       -> two failures: micro_zeroint.mgf undocumented, AND phantom micro_ZZZ.mgf on no disk
#
#   2.  Each of these must fail (verified, all five live phrasings):
#         sed -i '' 's/\*\*16 dumps\.\*\*/**15 dumps.**/'        docs/harness/FIXTURES.md
#         sed -i '' 's/All \*\*16\*\* fixtures/All **14** fixtures/' docs/harness/FIXTURES.md
#         sed -i '' 's/\*\*16 fixtures\*\*/**15 fixtures**/'     docs/harness/PARITY_REPORT.md
#         sed -i '' 's/\*\*16 of them\*\*/**14 of them**/'       docs/harness/Tech_Step8.md
#         sed -i '' 's/all \*\*16\*\* fixtures/all **14** fixtures/' docs/harness/Tech_Step8.md
#
#   3.  Legacy branch -- append to Tech_Step_INDEX.md:
#         '**C99 -- claims fallout on [Step 3](Tech_Step3.md).**'
#       -> fails: C99 names Tech_Step3.md, which never references C99
#
#   3b. Ratchet branch (C38+) -- all three verified:
#         '**C39 -- a finding.**'                          -> fails: no '**Fallout:**' line
#         '**C39 -- a finding.**\n\n**Fallout:** Tech_Step3.md'
#                                                          -> fails: Step 3 never references C39
#         '**C39 -- note.**\n\n**Fallout:** none -- reason' -> PASSES, as intended
#
#   4.  In a COMPLETED step's spec, rename a real class: `ToleranceTest` -> `ToleranceCalculationTest`
#       -> fails naming the phantom. Confirm pending steps stay exempt: Step 10 names 10 unwritten
#          classes and must NOT fail.
#
#   5.  mv docs/VENDORED.md /tmp/ -> fails from both Tech_Step6.md and Tech_Step7.md
#
#   8.  Both directions verified:
#         add "`massql_query.py:194-195`" to any spec  -> fails: "cites ... by LINE NUMBER"
#         sed -i '' 's/clean_nan/scrub_nan/g' ../massql/massql_query.py
#                                                     -> fails: "cite `clean_nan` ... not there"
#
#       ⚠ Rename ALL occurrences in that probe, not just the `def`. Renaming the definition alone
#       leaves the call site, the anchor string is still found, and the check appears not to work
#       when in fact the probe was unrealistic.
#
#   7.  All three dimensions verified, and the check needed a fix to earn the third:
#         mv docs/harness/oracle/PINNED.md /tmp/          -> "(no such file)"
#         rename <a id="c40"> to something else           -> "(no such anchor)"
#         add a link to ../../../massql/massql_query.py   -> "(ESCAPES the repo)"
#
#       ⚠ The single-broken-link case reported a COUNT and named nothing, because
#       `while read` skips a final line with no trailing newline. One bad link is the common case,
#       so the check was at its least useful exactly when it mattered most. Re-probe with ONE bad
#       link, not two, after touching that loop.
#
# Revert with `git checkout` afterwards.
# --------------------------------------------------------------------------------------------
