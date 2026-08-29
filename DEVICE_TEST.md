# Device test — iQOO 15, no SIM

The one question this build exists to answer:

> **During a live call, does Kavach's microphone receive real audio or digital silence?**

Everything else in the roadmap is ordinary work. This is the part nobody can answer from a
desk, because it depends on whether vivo's audio HAL follows AOSP.

The debug build's package is **`com.kavach.app.debug`** (`applicationIdSuffix = ".debug"`).
Every command below uses it. Using `com.kavach.app` will silently do nothing.

---

## 0. Install

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`-g` grants the runtime permissions up front. It does **not** grant the three special ones
below — those are appops and secure settings, not runtime permissions.

---

## 1. Pre-arm everything

Never grant permissions live in front of a judge, and never at 2am.

```bash
PKG=com.kavach.app.debug

adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow
adb shell settings put secure enabled_accessibility_services \
  $PKG/com.kavach.app.a11y.KavachAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell dumpsys deviceidle whitelist +$PKG
```

**Verify rather than assume** — vivo's Settings app resets the accessibility list more
eagerly than stock Android does:

```bash
adb shell settings get secure enabled_accessibility_services
adb shell appops get $PKG SYSTEM_ALERT_WINDOW
```

If the accessibility line comes back empty, the OEM refused it. Enable it by hand:
Settings → Accessibility → Installed apps → Kavach. If that toggle is **greyed out**, this
is Android's restricted-settings block for sideloaded apps, not a bug:
App info → ⋮ (top right) → **Allow restricted settings**, then try again. The in-app setup
screen says this too.

### vivo/OriginOS survival, by hand, before the demo

- Settings → Battery → Background power consumption → Kavach → **Allow high**
- Autostart → Kavach → **on**
- Battery optimisation → Kavach → **Don't optimise**
- Notifications → Kavach → **Scam warnings** channel → allow lock screen + banner
- Lock Kavach in the Recents list (swipe up, tap the padlock)

Do **not** write code for any of this. OEM settings intents are undocumented, break between
firmware versions, and would cost hours for something a checklist does in twenty seconds.

---

## 2. Open the app once

The first screen is the permission ladder. It tells you which of four tiers this device is on:

| Tier | Meaning |
|---|---|
| Not ready | No microphone permission. Nothing works. |
| Manual sessions only | No overlay permission — Kavach cannot raise itself. |
| Starts on its own, but not during calls | No accessibility service — the mic will be muted mid-call. |
| **Full — listens during calls** | Everything granted. This is the tier the test needs. |

At the bottom is the **live capture readout**. It is deliberately raw, and it is the evidence
for every claim the app makes. Leave that screen open for the next step.

---

## 3. The four-arm silence test

With no SIM, use a **WhatsApp or Google Meet call from a second device**. That is not a
compromise: VoIP sets `MODE_IN_COMMUNICATION`, which is governed by the exact same audio-policy
rule as a cellular `MODE_IN_CALL`. If it works here it works on a real call.

Run each arm with someone talking on the other end, for about thirty seconds.

| Arm | Accessibility | Kavach on screen | Expected |
|---|---|---|---|
| A | **off** | no | `Silenced: YES`, level `digital silence` |
| B | **on** | no | probably still silenced — a bound service is not `TOP` |
| C | **on** | yes, but mic owned by the recogniser | probably silenced — wrong UID |
| **D** | **on** | **yes**, `Microphone owner: Kavach` | **`Silenced: no`, a real dB level, transcripts climbing** |

Arm D is the whole product. Arm C failing while D passes is the single best slide in the deck:
it shows the exemption is real *and* that it is keyed to who opened the microphone.

Watch it from the host at the same time:

```bash
adb logcat -c
adb logcat -s KavachMic KavachA11y KavachCallWatcher KavachService KavachAsr
```

and, mid-call, in another shell:

```bash
adb shell dumpsys media.audio_policy | grep -iE "silenc|a11y|uid|phone state"
```

Screenshot that output. It is the most persuasive artefact you have.

---

## 4. What should happen with no interaction at all

With everything granted, put the phone down and have the second device call it on WhatsApp.

1. The call connects → the audio mode flips.
2. The accessibility service notices — **no notification was read, no number was read**.
3. Kavach raises the shield. It appears as a band across the top of the call screen. Touches
   pass straight through it; the call is fully usable.
4. Kavach starts listening. The dot on the band is coloured while it is genuinely hearing audio
   and grey when it is not.
5. Play a scam script from `fixtures/positive/` aloud on the other end. As the score crosses,
   the band becomes the full warning, takes touch, and offers **Call 1930** and **I'm fine**.
6. It never covers the bottom of the screen. You can always hang up.

If the shield does not appear, the overlay permission or the OEM's background-launch blocking
is the cause — check `adb logcat -s KavachA11y` for "overlay permission is missing" or a
`Permission Denied` activity-start line. The **Quick Settings tile** ("Kavach shield") is the
fallback door: add it to the shade once, then one tap raises the shield from anywhere,
including over a call and from the lock screen.

---

## 5. If arm D fails

Stop. Do not spend the night on it. `KAVACH_REMEDIATION.md` §9 is the pre-decided fallback:
Kavach becomes zero-tap caller intelligence plus a "guardian phone" mode for speakerphone,
in-person pressure, and calls on someone else's device — and you say from the stage exactly
why, with the logcat to prove it.

That is a better talk than a demo that works by accident.

---

## 6. Known limits of this build, stated up front

- **No `CallScreeningService`.** Pointless on a SIM-less device and untestable here. The audio-mode
  watcher replaces it and covers more: VoIP as well as cellular.
- **No contact filtering yet**, so every call triggers the shield. Fine for a test, wrong for a
  product — it is the next slice.
- **No onboarding rehearsal, no persistence, no post-call sheet.** Planned, not built.
- **The corpus is seven conversations.** Say so before anyone asks.
