#!/usr/bin/env bash
# Kavach — real-device smoke test.
#
# Everything the emulator could not answer. Run with the phone connected over
# USB with developer mode + USB debugging on:
#
#   ./scripts/device-check.sh
#
# Nothing here is destructive; it installs the debug build and drives the demo.
set -uo pipefail

ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb"
PKG=com.kavach.app.debug
APK=app/build/outputs/apk/debug/app-debug.apk

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
info() { printf '  ....  %s\n' "$1"; }
head_() { printf '\n\033[1m%s\033[0m\n' "$1"; }

head_ "Device"
if [ "$("$ADB" get-state 2>/dev/null)" != "device" ]; then
  fail "no device. Enable Developer options > USB debugging, then accept the RSA prompt."
  exit 1
fi
prop() { "$ADB" shell getprop "$1" 2>/dev/null | tr -d '\r'; }
info "$(prop ro.product.manufacturer) $(prop ro.product.model)"
info "Android $(prop ro.build.version.release)  (API $(prop ro.build.version.sdk))"
CHIP="$(prop ro.board.platform)  $(prop ro.soc.model)"
info "chipset: $CHIP"
# The catalogue ships a Snapdragon 8 Elite NPU build of Gemma 4 E2B. If this
# phone is sm8750, that variant is worth adding — see docs/ARCHITECTURE.md 4.
case "$CHIP" in
  *8750*|*sm8750*) pass "Snapdragon 8 Elite — the sm8750 NPU model variant applies to this phone" ;;
  *) info "not sm8750; the GPU model build is the right one here" ;;
esac

head_ "Install"
if [ ! -f "$APK" ]; then fail "no APK. Run ./gradlew assembleDebug first."; exit 1; fi
"$ADB" install -r "$APK" >/dev/null 2>&1 && pass "installed" || { fail "install failed"; exit 1; }

head_ "Permissions actually granted to the APK"
"$ADB" shell dumpsys package $PKG | sed -n '/requested permissions/,/install permissions/p' \
  | grep -oE 'android\.permission\.[A-Z_]+' | sort -u | sed 's/^/    /'
if "$ADB" shell dumpsys package $PKG | grep -q "android.permission.INTERNET"; then
  fail "INTERNET permission present — the privacy claim is broken"
else
  pass "no INTERNET permission on the device"
fi

head_ "On-device speech recognition — THE unknown"
# The emulator has none, so this is the first real answer. If it is absent here,
# live capture cannot work on this phone and DemoMode carries the demo.
"$ADB" shell pm list packages | grep -qE 'com.google.android.(googlequicksearchbox|as)' \
  && pass "a Google on-device recognition provider is installed" \
  || fail "no on-device recogniser package found — live ASR will be unavailable"
info "the app reports the real answer in its own UI; check the 'Speech:' line"

head_ "Launch"
"$ADB" shell am force-stop $PKG
"$ADB" logcat -c
"$ADB" shell am start -n $PKG/com.kavach.app.MainActivity >/dev/null 2>&1
sleep 4
"$ADB" shell dumpsys activity activities | grep -q "$PKG/com.kavach.app.MainActivity" \
  && pass "app is in the foreground" || fail "app did not come up"
CRASH=$("$ADB" logcat -d -s AndroidRuntime:E | tail -5)
[ -z "$CRASH" ] && pass "no crash on launch" || { fail "crashed:"; echo "$CRASH"; }

head_ "Audio never touches disk"
BEFORE=$("$ADB" shell run-as $PKG find . -type f 2>/dev/null | wc -l | tr -d ' ')
info "$BEFORE files in private storage before a session"
info "run a session, then re-run this script: the count must not grow with audio"

head_ "Next, by hand"
cat <<'MANUAL'
    1. Tap "Run a demo conversation" > digital-arrest-01.
       Expect green -> amber -> red inside ~50s, naming tactics at each step.
    2. Turn on airplane mode and repeat. This is the pitch claim.
    3. Tap "Start listening" and speak a scam line aloud, e.g.
       "main CBI se bol raha hoon, aap OTP bataiye".
       Watch the "Speech:" line to see which engine actually ran.
    4. Lock the screen for 30 minutes with monitoring on, then:
         adb shell dumpsys meminfo com.kavach.app.debug
       Memory must be flat. This is the S0.3 acceptance criterion.
MANUAL
echo
