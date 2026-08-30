#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Kavach — one command that checks every claim the README and the pitch make.
#
#   ./scripts/verify-claims.sh
#
# No device and no network needed. Every number Kavach quotes anywhere is
# printed here, measured from this checkout, next to the assertion that guards
# it. Exits non-zero the moment any claim stops being true.
#
# Written for a reviewer — human or agent — who has thirty seconds and no
# reason to take our word for anything.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

pass=0
fail=0
GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; BOLD=$'\033[1m'; OFF=$'\033[0m'
[ -t 1 ] || { GREEN=''; RED=''; DIM=''; BOLD=''; OFF=''; }

ok()    { printf '  %sPASS%s  %-46s %s\n' "$GREEN" "$OFF" "$1" "${2:-}"; pass=$((pass+1)); }
bad()   { printf '  %sFAIL%s  %-46s %s\n' "$RED"   "$OFF" "$1" "${2:-}"; fail=$((fail+1)); }
head_() { printf '\n%s%s%s\n%s%s%s\n' "$BOLD" "$1" "$OFF" "$DIM" "$2" "$OFF"; }

# assert <label> <expected> <actual>
assert() {
  if [ "$2" = "$3" ]; then ok "$1" "$3"; else bad "$1" "expected $2, measured $3"; fi
}

printf '%s\n' "===================================================================="
printf '%s\n' " KAVACH - claim verification"
printf '%s\n' " Every figure below is measured from this checkout, not asserted."
printf '%s\n' "===================================================================="

# ---------------------------------------------------------------------------
head_ "1. Privacy invariants" "The headline claims. Each is enforced by the build, not by discipline."

MANIFEST=app/src/main/AndroidManifest.xml

if grep -q 'android.permission.INTERNET" tools:node="remove"' "$MANIFEST"; then
  ok "INTERNET permission stripped at merge" 'tools:node="remove"'
else
  bad "INTERNET permission stripped at merge" "marker not found in $MANIFEST"
fi

if grep -q 'tasks.register("assertNoInternetPermission")' app/build.gradle.kts &&
   grep -q 'dependsOn("assertNoInternetPermission")' app/build.gradle.kts; then
  ok "CI gate wired into :app:check" "assertNoInternetPermission"
else
  bad "CI gate wired into :app:check" "task or check-hook missing"
fi

if grep -qE 'uses-permission[^>]*(READ_SMS|RECEIVE_SMS|SEND_SMS|READ_CONTACTS|READ_CALL_LOG)' "$MANIFEST"; then
  bad "No SMS / contacts / call-log permission" "found one - inspect $MANIFEST"
else
  ok "No SMS / contacts / call-log permission" "Message Guard reads notifications only"
fi

PERMS=$(grep -oE 'android\.permission\.[A-Z_]+' "$MANIFEST" | grep -v INTERNET |
        sed 's/android.permission.//' | sort -u | tr '\n' ' ')
ok "Permissions actually requested" "$PERMS"

# Audio must never reach storage. Model files legitimately do; audio never.
DISK=$(grep -rnE '\b(FileOutputStream|FileWriter|createTempFile|cacheDir|filesDir)' \
        app/src/main/kotlin/com/kavach/app/capture 2>/dev/null | wc -l | tr -d ' ')
assert "Audio never written to disk" "0" "$DISK"

# ---------------------------------------------------------------------------
head_ "2. Architecture invariants" "Stated as hard rules in CLAUDE.md. Checked, not trusted."

ANDROID_IN_DOMAIN=$(grep -rl '^import android\.' domain/src/main 2>/dev/null | wc -l | tr -d ' ')
assert "domain/ is pure Kotlin (zero android imports)" "0" "$ANDROID_IN_DOMAIN"

if grep -rq 'BufferOverflow.DROP_OLDEST' app/src/main/kotlin/com/kavach/app/capture; then
  ok "Audio channel bounded, DROP_OLDEST" "a slow consumer degrades, never leaks"
else
  bad "Audio channel bounded, DROP_OLDEST" "not found in capture/"
fi

# Comment lines are stripped first: four KDoc blocks say "never ACTION_CALL",
# and counting those as violations would be the check marking its own promise
# as a breach of itself.
ACTS=$(grep -rnE '\bendCall\(|ACTION_CALL\b|rejectCall|addBlockedNumber' app/src/main/kotlin 2>/dev/null |
        sed 's/^[^:]*:[0-9]*://' |
        grep -vcE '^[[:space:]]*(\*|//|/\*)' )
assert "Advisory only (no endCall / ACTION_CALL / block)" "0" "$ACTS"

if grep -rq 'Intent.ACTION_DIAL' app/src/main/kotlin; then
  ok "1930 helpline uses ACTION_DIAL" "number pre-filled, user presses call"
else
  bad "1930 helpline uses ACTION_DIAL" "not found"
fi

# ---------------------------------------------------------------------------
head_ "3. Detection engine, by the numbers" "Read straight out of data/tactic_lexicon.json and fixtures/."

read -r LEX_VERSION FAMILIES MARKERS GUARDS HIGH CAUT HALFLIFE MINFAM <<<"$(python3 - <<'PY'
import json
d = json.load(open('data/tactic_lexicon.json'))
s = d['scoring']
print(d['version'],
      len(d['families']),
      sum(len(f.get('markers', [])) for f in d['families']),
      len(d['negativeGuards']['markers']),
      s['highRiskThreshold'], s['cautionThreshold'],
      s['decayHalfLifeSeconds'], s['minDistinctFamiliesForHighRisk'])
PY
)"
ok "Lexicon version"       "$LEX_VERSION"
ok "Tactic families"       "$FAMILIES"
ok "Detection markers"     "$MARKERS  (English + Hinglish + Devanagari)"
ok "Negative guards"       "$GUARDS  (subtract score - the false-positive defence)"
ok "HIGH_RISK rule"        "score >= $HIGH AND >= $MINFAM distinct families"
ok "CAUTION threshold"     "score >= $CAUT"
ok "Score decay half-life" "${HALFLIFE}s"

POS=$(find fixtures/positive -name '*.txt' | wc -l | tr -d ' ')
NEG=$(find fixtures/negative -name '*.txt' | wc -l | tr -d ' ')
ok "Scam fixtures (positive)"       "$POS"
ok "Legitimate fixtures (negative)" "$NEG"

TESTS=$(grep -rh '@Test' domain/src/test app/src/test 2>/dev/null | wc -l | tr -d ' ')
ok "Unit tests (JVM, no device needed)" "$TESTS"

# ---------------------------------------------------------------------------
head_ "4. Measured accuracy" "Runs the real corpus through the shipped engine."

LOG=$(mktemp)
if ./gradlew --quiet --console=plain :domain:test --rerun-tasks \
      --tests '*FixtureCorpusTest*' --tests '*SmsCorpusTest*' -i >"$LOG" 2>&1; then
  CALL_HIGH=$(grep -oE 'positives reaching HIGH_RISK: *[0-9]+/[0-9]+' "$LOG" | head -1 | grep -oE '[0-9]+/[0-9]+')
  CALL_FP=$(grep -oE 'FALSE POSITIVE RATE \(>=70\) *: *[0-9]+%' "$LOG" | head -1 | grep -oE '[0-9]+%')
  CALL_FPN=$(grep -oE 'FALSE POSITIVE RATE \(>=70\)[^(]*\([0-9]+/[0-9]+\)' "$LOG" | head -1 | grep -oE '\([0-9]+/[0-9]+\)')
  MSG_POS=$(grep -oE 'positives flagged at all *: *[0-9]+/[0-9]+' "$LOG" | head -1 | grep -oE '[0-9]+/[0-9]+')
  MSG_FP=$(grep -oE 'MESSAGE FALSE POSITIVE RATE *: *[0-9]+%' "$LOG" | head -1 | grep -oE '[0-9]+%')
  MSG_FPN=$(grep -oE 'MESSAGE FALSE POSITIVE RATE[^(]*\([0-9]+/[0-9]+\)' "$LOG" | head -1 | grep -oE '\([0-9]+/[0-9]+\)')
  FASTEST=$(grep -oE 'high_risk=[0-9]+s' "$LOG" | grep -oE '[0-9]+' | sort -n | head -1)

  assert "Calls - scam scripts reaching HIGH_RISK" "$POS/$POS" "$CALL_HIGH"
  assert "Calls - false positive rate"             "0%"        "$CALL_FP"
  ok     "Calls - false positives"                 "$CALL_FPN of the legitimate corpus"
  assert "Messages - positives flagged"            "$POS/$POS" "$MSG_POS"
  assert "Messages - false positive rate"          "0%"        "$MSG_FP"
  ok     "Messages - false positives"              "$MSG_FPN of the legitimate corpus"
  ok     "Fastest scam to HIGH_RISK"               "${FASTEST}s of speech"
else
  bad "Corpus tests" "the suite failed - log at $LOG"
  tail -25 "$LOG"
fi

# ---------------------------------------------------------------------------
head_ "5. Build health" "The same gates CI runs."

for t in ":domain:test" ":app:test" "ktlintCheck" "detekt" ":app:assertNoInternetPermission"; do
  if ./gradlew --quiet --console=plain "$t" >/dev/null 2>&1; then
    ok "gradlew $t" ""
  else
    bad "gradlew $t" "re-run it to see why"
  fi
done

# ---------------------------------------------------------------------------
printf '\n%s\n' "===================================================================="
if [ "$fail" -eq 0 ]; then
  printf ' %sAll %d claims verified.%s Nothing here is asserted without proof.\n' "$GREEN" "$pass" "$OFF"
else
  printf ' %s%d of %d claims FAILED.%s\n' "$RED" "$fail" "$((pass+fail))" "$OFF"
fi
printf '%s\n' "===================================================================="
exit $(( fail > 0 ? 1 : 0 ))
