# Kavach — remediation & build plan (v2)

Supersedes v1. v1's central claim — that the audio-policy accessibility clause is reachable by a
third-party app — **holds, and is now confirmed against Google's own documentation**, not only AOSP
source. v2 keeps that argument, fixes one defect in it that would have silently sunk the whole
approach on device, and adds the two things v1 was missing: **the user-experience specification**
and **an ordered implementation plan with per-slice definitions of done**.

Read alongside `docs/PRD.md`, `docs/ARCHITECTURE.md` §2 (now known to be wrong — see §1.6),
`docs/SAFETY.md`, and `CLAUDE.md`.

---

## 0. What changed from v1

| # | Change | Severity |
|---|---|---|
| 1 | **The recogniser owns the microphone, not Kavach.** `SpeechRecognizer` opens the mic in the *recognition service's* process. The a11y exemption is keyed to the recording client's UID, so it would not have applied. Fix: Kavach opens `AudioRecord` itself and feeds the recogniser via `RecognizerIntent.EXTRA_AUDIO_SOURCE`. | **Fatal if missed** |
| 2 | v1's condition 3 ("your Activity on TOP") is confirmed by official docs, and it also **removes the need for the notification-interaction exemption** — a visible Activity can start a mic FGS directly. The notification is the delivery mechanism, not the legal loophole. | Simplifies §2 |
| 3 | **Android 13+ "Restricted settings"** blocks enabling an accessibility service for a sideloaded app until the user explicitly unblocks it. v1 does not mention this. It will eat 20 minutes on demo day if unplanned. | High |
| 4 | Quick Settings tile added as a second, always-reachable, one-tap door that works from the lock screen and over the in-call UI. | New capability |
| 5 | §4 **User experience** — full specification, added. | New |
| 6 | §8 **Implementation plan** — slices, files, tests, gates. | New |
| 7 | v1's §5 subsections were numbered 6.x. Renumbered. | Cosmetic |

---

## 1. Wall one: the microphone during a call

### 1.1 What the platform actually says — verbatim

`developer.android.com/media/platform/sharing-audio-input`:

> **Voice call + ordinary app.** A voice call is active if the audio mode returned by
> `AudioManager.getMode()` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`.
> - The call always receives audio.
> - **The app can capture audio if it is an accessibility service.**
> - The app can capture the voice call if it is a privileged (pre-installed) app with permission
>   `CAPTURE_AUDIO_OUTPUT`.

> **Accessibility service + ordinary app.**
> - **If the service's UI is on top, both the service and the app receive audio input.** This
>   behavior offers functionality like controlling a voice call or video capture with voice commands.
> - If the service is not on top, this case is treated like the ordinary two-app case.

That is the whole argument, in Google's words, on Google's site. It is not an AOSP-only artefact and
it is not a bug. Screenshot this page for the deck.

### 1.2 The enforcement code — what you must satisfy exactly

`frameworks/av/services/audiopolicy/service/AudioPolicyService.cpp` → `updateUidStates_l()`.
Capture is allowed (not force-silenced) when:

```
OR The client is an accessibility service
   AND Is on TOP
   AND the source is VOICE_RECOGNITION or HOTWORD
```

Note what is **absent**: any condition on `isInCall` / `isInCommunication`. The in-call gate
(`!(isInCall && !canCaptureOutput)`) lives on the *default* client branch — the branch Kavach falls
into today. The a11y branch bypasses it.

Four conditions, all of which you control:

1. **`isA11yUid(uid)` is true** — your package registers an *enabled* `AccessibilityService`.
   `AccessibilityManagerService` pushes enabled a11y UIDs to audio policy via `setA11yServicesUids()`.
2. **Source is `VOICE_RECOGNITION` or `HOTWORD`.** `MicCapture.kt` already prefers `VOICE_RECOGNITION`
   and falls back to `MIC`. **The `MIC` fallback must not be taken during a call** — `MIC` is not on
   the list. Make the fallback conditional on `audioManager.mode == MODE_NORMAL`.
3. **On TOP** — process state `<= PROCESS_STATE_TOP`. A foreground service is *not* enough. A bound
   a11y service is *not* enough. You need a **visible Activity from your UID** while capturing.
4. **The recording client UID must be yours.** See §1.3. This is the one v1 got wrong.

### 1.3 The defect in v1: you do not currently own the microphone

`SystemAsrTranscriptSource` uses `SpeechRecognizer.createOnDeviceSpeechRecognizer()`. From
`RecognizerIntent`:

> Optional `ParcelFileDescriptor` pointing to an already opened audio source for the recognizer to
> use. The caller of the recognizer is responsible for closing the audio. **If this extra is not set
> or the recognizer does not support this feature, the recognizer will open the mic for audio** and
> close it when the recognition is finished.

So today the `AudioRecord` is opened **inside the system recognition service's process**, under
*its* UID, with *its* choice of audio source. That process is not an accessibility service. Under
§1.2 it lands on the default branch and gets silenced during a call — no matter how many a11y
services Kavach registers and no matter what is on screen.

`MicCapture.kt` — 125 lines currently dead, flagged in the teardown as cruft — is exactly the
component that fixes this. It becomes load-bearing.

**The corrected audio path:**

```
Kavach UID
  AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)      ← a11y exemption applies HERE
      │
      ├── AudioRingBuffer (10 s, in memory, never disk)     ← SAFETY.md's claim becomes true again
      │
      └── ParcelFileDescriptor pipe (write end)
              │
              └── SpeechRecognizer.startListening(intent.apply {
                        putExtra(EXTRA_AUDIO_SOURCE, pfdReadEnd)
                        putExtra(EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)   // default anyway
                        putExtra(EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)       // default anyway
                        putExtra(EXTRA_AUDIO_SOURCE_ENCODING, ENCODING_PCM_16BIT)
                  })
```

The recogniser's defaults (16 000 Hz, 1 channel, `ENCODING_PCM_16BIT`) are **already** exactly
`AudioRingBuffer.SAMPLE_RATE_HZ` / mono / PCM16. Nothing to convert. Pass them explicitly anyway so
a future edit to the ring buffer breaks loudly.

Bonus effects of this change, all of them things the teardown separately asked for:
- Kills the recogniser-restart audio gap: the `AudioRecord` never stops, only the recogniser cycles.
- Makes the ring buffer real, so `docs/SAFETY.md` stops describing a code path that isn't executed.
- Gives you per-frame RMS for the silence test and for an honest "we hear nothing" UI state.
- Lets you swap in Whisper/LiteRT later behind the same PCM tap with no capture changes.

**Fallback, written first per `CLAUDE.md`:** if `EXTRA_AUDIO_SOURCE` is unsupported by the OEM
recogniser (Android's own doc says "or the recognizer does not support this feature"), the recogniser
silently opens its own mic and you are back to being silenced in-call. Detect it — if the
recogniser produces results while your `AudioRecord` is reading zeros, or if it errors on start with
the PFD — and fall back to: recogniser-owns-mic mode, which still works fine **outside** a call
(speakerphone-on-another-phone demo, in-person, room audio). Surface it as a named degraded state,
do not hide it.

### 1.4 What you will and will not hear

You capture the **microphone**, not the call. Be precise:

| Situation | What Kavach hears | Demo posture |
|---|---|---|
| Earpiece call | Victim's half only | Honest, still useful — "yes sir, my Aadhaar is…", "I'm going to the ATM" |
| **Speakerphone / video call** | **Both halves, via room acoustics** | **Demo default.** Also the actual digital-arrest posture |
| Wired / BT headset | Almost nothing | Detect and degrade out loud |

Do not say "on-device call audio analysis". Say **"on-device conversation analysis during a call"**
and name the mic. A judge who knows the platform will respect the precision more than the overclaim.

### 1.5 The silence test — four arms, not three

Run this **first**. Everything downstream is gated on arm D.

| Arm | a11y enabled | Kavach Activity visible | Mic owner | Expected |
|---|---|---|---|---|
| A | no | no | recogniser | silenced, RMS ≈ 0 |
| B | yes | no | recogniser | silenced (process state is BOUND_FGS, not TOP) |
| C | yes | **yes** | recogniser | **probably still silenced** — wrong UID (§1.3) |
| D | yes | **yes** | **Kavach (`EXTRA_AUDIO_SOURCE`)** | **not silenced, real RMS, real transcript** |

Arm C failing while D passes is the proof that §1.3 is the real mechanism. That contrast is a slide.

```kotlin
// Attach before startRecording()
audioManager.registerAudioRecordingCallback(executor, object : AudioManager.AudioRecordingCallback() {
    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
        configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }?.let {
            Log.i("Silence", "silenced=${it.isClientSilenced} mode=${audioManager.mode} src=${it.audioSource}")
        }
    }
})
// Log per-frame RMS regardless — the callback only fires on change, and RMS is the ground truth.
```

Mid-call, from the host:

```bash
adb shell dumpsys media.audio_policy | grep -iE "silenc|a11y|uid|phone state"
adb logcat -s AudioPolicyService | grep updateUidStates
```

That logcat line prints `isA11yOnTop`, `isInCall`, `allowCapture` and a reason string. It is the best
debugging artefact you have and a screenshot of it belongs in the pitch.

**If arm D fails on the iQOO,** vivo's HAL diverges from AOSP. Stop, do not spend the night on it,
and fall back to the honest product in §9.

### 1.6 `docs/ARCHITECTURE.md` §2 is wrong and must be rewritten

It currently claims ambient capture "works identically for cellular, WhatsApp, Telegram, Meet."
False: the audio-policy predicate is the audio **mode**, not the source. WhatsApp sets
`MODE_IN_COMMUNICATION`; you are silenced there for the same reason. Rewrite it around §1.1–§1.4 in
the same commit as the capture change, or the repo argues against itself in front of a judge.

### 1.7 Landmines v1 missed

- **Restricted settings (Android 13+).** A sideloaded app's accessibility toggle is greyed out until
  the user goes App info → ⋮ → **Allow restricted settings**. Your onboarding must show this step
  with a screenshot, and the demo runbook must do it by hand hours before the pitch. Alternative for
  the demo device only: `adb install -i com.android.vending …` so the installer is recorded as Play.
- **Privacy-sensitive priority.** Per the same doc: *"Apps capturing audio from a privacy-sensitive
  source have higher priority… If one of the apps is privacy-sensitive, it receives audio and the
  other app gets silence even if it has a UI on top."* Do **not** call
  `AudioRecord.Builder.setPrivacySensitive(true)` on Kavach's record, and be aware another app
  marking itself privacy-sensitive can still beat you outside the a11y branch.
- **Two ordinary apps can never capture at the same time.** If the OEM dialer or an assistant is
  holding the mic, you lose unless the a11y branch applies. Another reason arm D must be the one
  that ships.
- **Play policy.** Google banned the Accessibility API for call recording in May 2022. That is
  **policy, not platform**. Sideloading is fine; say it out loud in the pitch rather than get caught.
  Prior art: Cube ACR ships an a11y "App Connector" + helper app + floating widget and instructs
  users to select "voice recognition (software)" — the same three conditions, shipped for years.

### 1.8 Dead ends — do not spend the weekend here

- `MediaProjection` / `AudioPlaybackCapture`: cannot capture `USAGE_VOICE_COMMUNICATION`. Excluded by design.
- `VOICE_CALL` / `VOICE_UPLINK` / `VOICE_DOWNLINK`: need `CAPTURE_AUDIO_OUTPUT`, `signature|privileged`.
  `pm grant` cannot grant it — it is not a runtime permission. Requires `/system/priv-app` + a
  privapp-permissions XML, i.e. a custom ROM.
- `CONCURRENT_AUDIO_RECORD_BYPASS` (Android 15): also privileged.
- Becoming the **default dialer** does *not* grant call-audio capture. It gives you the in-call screen
  and `endCall()`, not the audio. Google's Scam Detection is privileged, not merely default.

---

## 2. Wall two: starting without the user opening the app

### 2.1 The exemption, and why it matters less than v1 thought

Android 14+ blocks background-started mic FGS. The documented exemptions
(`restrictions-bg-start#wiu-restrictions-exemptions`) that a normal app can reach:

- the service starts by interacting with a **notification**;
- the service starts by interacting with an **app widget**;
- a `PendingIntent` sent from a different, *visible* app.

v1 built everything on the notification-interaction exemption. But §1.2 condition 3 already forces a
**visible Activity** on screen during capture — and an app with a visible Activity is in the
foreground, so **no exemption is needed at all**. The rule you are exempting yourself from does not
apply once your window is up.

**So the real sequence is: get the Activity on screen, then start the FGS from it.** The notification
(or the tile, or the full-screen intent) is only the *delivery mechanism* that gets the Activity up.
This is a simpler and much more robust argument than v1's, and it fails in fewer places.

### 2.2 The chain, end to end

```
Incoming call from an unknown number
  │
  ├─ CallScreeningService.onScreenCall()          ← system-bound, ZERO user action, pre-answer
  │     • number, direction, STIR/SHAKEN verification status
  │     • ContactsContract.PhoneLookup → known contact? then do nothing at all
  │     • respondToCall(): label only. NEVER silence or reject. (CLAUDE.md rule 5)
  │     └─ if pre-answer signal is non-trivial: post CH_ALERT notification
  │        with setFullScreenIntent(...) + action "Listen to this call"
  │
  ├─ [pre-answer] mode is MODE_RINGTONE, not MODE_IN_CALL
  │     → the in-call clause does not apply yet. Ambient capture during ring is free if it works.
  │
  ├─ User taps the action / the FSI fires on the lock screen / user taps the QS tile
  │     → KavachShieldActivity launches (setShowWhenLocked + setTurnScreenOn)
  │     → app is at PROCESS_STATE_TOP
  │         ✅ FGS mic start is legal (foreground app — no exemption needed)
  │         ✅ a11y capture condition 3 satisfied
  │
  ├─ AccessibilityService enabled + Kavach owns AudioRecord + source = VOICE_RECOGNITION
  │     ✅ conditions 1, 2, 4 — not force-silenced despite MODE_IN_CALL
  │
  └─ Verdict → in-place escalation of the Activity/overlay + haptic + alert notification
```

`CallScreeningService` is genuinely zero-tap and is the part of the product you can honestly call
always-on. The audio tier is one-tap. Ship it as **"one tap, and here is exactly why"**.

### 2.3 The second door: a Quick Settings tile

A `TileService` tile is reachable from the notification shade **over the in-call UI and over the lock
screen**, in one swipe-and-tap, without leaving the call. `startActivityAndCollapse()` launches
`KavachShieldActivity` → app is TOP → FGS starts legally. This is the closest thing to Whisper Flow's
"it's just there" ergonomics that Android gives a non-privileged app, it costs about 45 minutes, and
it is the fallback when a notification is missed or an OEM eats the full-screen intent.

Ship the tile. It is the highest ratio of user-visible ease to engineering cost in this document.

### 2.4 The fully automatic version: `ROLE_DIALER`

Holding `ROLE_DIALER` gives you an `InCallService` — **you own the in-call screen**. The warning
becomes ordinary in-app UI on a screen you already control: no overlay, no full-screen intent, no
lock-screen problem, and your Activity is TOP by construction, which satisfies §1.2 condition 3 for
free. It also makes `endCall()` legitimately available (`CLAUDE.md` rule 5 bans *autonomous* action;
a button the user presses is not autonomous — but get that amendment written down, see §11).

Cost: dialpad, call log, contacts, in-call controls, audio routing. Not a weekend.

**Recommendation: build the screening + notification + tile + overlay path now; put `ROLE_DIALER` on
the roadmap slide as "the version that ships."** Say the tradeoff out loud. Truecaller follows exactly
this path — `CallScreeningService` for identification, `SYSTEM_ALERT_WINDOW` for the in-call card,
dialer role only on explicit opt-in.

### 2.5 Manifest and permission set

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
<uses-permission android:name="android.permission.READ_PHONE_STATE"/>
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<uses-permission android:name="android.permission.READ_CONTACTS"/>  <!-- "unknown callers only" -->
<!-- android.permission.INTERNET stays removed via tools:node="remove". Do not lose this. -->
<!-- No CALL_PHONE: ACTION_DIAL needs no permission and never places a call. -->

<service android:name=".screening.KavachCallScreeningService"
         android:permission="android.permission.BIND_SCREENING_SERVICE"
         android:exported="true">
    <intent-filter><action android:name="android.telecom.CallScreeningService"/></intent-filter>
</service>

<service android:name=".a11y.KavachAccessibilityService"
         android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
         android:exported="true">
    <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
    <meta-data android:name="android.accessibilityservice"
               android:resource="@xml/kavach_accessibility_config"/>
</service>

<service android:name=".tile.KavachTileService"
         android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
         android:exported="true">
    <intent-filter><action android:name="android.service.quicksettings.action.QS_TILE"/></intent-filter>
</service>
```

The a11y service must do **as close to nothing as possible** — no `canRetrieveWindowContent`, no
`canRequestFilterKeyEvents`, no event types beyond the minimum, empty `onAccessibilityEvent`. Its only
job is to put your UID in the a11y list. Write that as a comment in
`res/xml/kavach_accessibility_config.xml`, in the service's KDoc, **and** in `docs/SAFETY.md`. A
reviewer *will* ask why you want accessibility. "We request the narrowest possible accessibility
service purely to obtain the documented audio-concurrency exemption, and it reads nothing" is a
strong answer — but only if the code visibly matches it.

Runtime prompts to build:
- `ROLE_CALL_SCREENING` — `RoleManager.createRequestRoleIntent(ROLE_CALL_SCREENING)`
- Accessibility — deep link to `Settings.ACTION_ACCESSIBILITY_SETTINGS`; cannot be granted in-app;
  **plus** the restricted-settings instruction from §1.7
- Overlay — `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`
- Full-screen intent — check `NotificationManager.canUseFullScreenIntent()`, send
  `Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` if false

### 2.6 Demo-day adb pre-arm (never rely on live granting)

```bash
adb shell pm grant com.kavach.app android.permission.RECORD_AUDIO
adb shell pm grant com.kavach.app android.permission.POST_NOTIFICATIONS
adb shell pm grant com.kavach.app android.permission.READ_PHONE_STATE
adb shell pm grant com.kavach.app android.permission.READ_CONTACTS
adb shell appops set com.kavach.app SYSTEM_ALERT_WINDOW allow
adb shell appops set com.kavach.app USE_FULL_SCREEN_INTENT allow
adb shell settings put secure enabled_accessibility_services \
  com.kavach.app/com.kavach.app.a11y.KavachAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING com.kavach.app
adb shell dumpsys deviceidle whitelist +com.kavach.app
```

Verify, do not assume: `adb shell settings get secure enabled_accessibility_services` and
`adb shell cmd role get-role-holders android.app.role.CALL_SCREENING`.

---

## 3. The alert surface

Ship in this order. Each layer works without the one after it.

| Layer | Over in-call UI | Over lock screen | Blocker | Cost |
|---|---|---|---|---|
| 1. `IMPORTANCE_HIGH` notification | heads-up banner | yes | none | **30 min — do it first** |
| 2. Full-screen intent | degrades to banner | **yes, full screen** | `canUseFullScreenIntent()` | 1 h |
| 3. `TYPE_APPLICATION_OVERLAY` | **yes** | no (keyguard is a higher layer) | `SYSTEM_ALERT_WINDOW` | 2 h |
| 4. Activity + `setShowWhenLocked` + `setTurnScreenOn` | yes | yes | needs a launch path | 1 h |

Layers 2 and 4 are the same thing: the full-screen intent *is* how you launch the show-when-locked
Activity. Build them together. Layer 4 is also what puts you at TOP for §1.2, so it is not optional.

### 3.1 Fix the channels — 20 lines, unblocks every alert path

```kotlin
// Two channels. Not one. Bump the IDs — channels are immutable after creation and a reinstall
// will silently keep IMPORTANCE_LOW, which you will then debug at 3am.
NotificationChannel(CH_STATUS_V2, "Monitoring status", IMPORTANCE_LOW).apply {
    setShowBadge(false); setSound(null, null)          // the ongoing FGS notification
}
NotificationChannel(CH_ALERT_V2, "Scam warnings", IMPORTANCE_HIGH).apply {
    enableVibration(true)
    vibrationPattern = longArrayOf(0, 400, 200, 400)
    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    setBypassDnd(true)                                  // this call is the emergency
}
nm.deleteNotificationChannel("kavach_monitoring")        // delete the stale one on upgrade
```

```kotlin
NotificationCompat.Builder(ctx, CH_ALERT_V2)
    .setCategory(NotificationCompat.CATEGORY_CALL)      // required for FSI + DND bypass
    .setFullScreenIntent(shieldPendingIntent, true)
    .setOngoing(true)
    .setSmallIcon(R.drawable.ic_shield_alert)
    .addAction(0, "Call 1930", dialPendingIntent)
    .addAction(0, "Not a scam", dismissPendingIntent)   // NOT the same as stop
```

---

## 4. The user experience

This is the section v1 was missing, and it is the part that decides whether anyone uses this.

### 4.1 The north star

> **You never open Kavach.** It opens itself, once, at the only moment it matters — and when it is
> wrong, being wrong costs you nothing.

The failure mode we are designing against is not "missed a scam". It is **"user turned it off in week
two because it kept talking to them."** Every notification Kavach posts that did not need to exist is
a step toward uninstall. Silence is the default state and it is a feature.

Whisper Flow's actual lesson is not "no UI". It is: *the tool's entry point lives where you already
are, and its first success happens before you need it.* Both are buildable here.

### 4.2 Three speeds — the core of the design

Most designs fail by having one speed. Kavach has three, and the user only ever pays attention at the
speed the situation earns.

| Speed | Trigger | Taps | Surface | Frequency |
|---|---|---|---|---|
| **0 — silent** | every incoming call | 0 | call label via `CallScreeningService`; no sound, no heads-up, no vibration | every call |
| **1 — offer** | unknown number **and** a pre-answer reason (STIR/SHAKEN failed, first contact, international, number-shape match) | 1 | heads-up: *"Unknown caller. Want Kavach listening?"* + QS tile always available | a minority of calls |
| **2 — warn** | risk band crosses HIGH during the call | 0 | full-screen / overlay alert + haptic + DND-bypassing sound | rare, and it is the product |

**Never post a speed-1 heads-up for a contact.** `ContactsContract.PhoneLookup` in the screening
service, before anything else. A known number produces no UI whatsoever.

**Never post a speed-1 heads-up for every unknown call.** That is the Truecaller trap — users stop
seeing it within a week, and then speed 2 is invisible too because they have learned the icon means
nothing. Gate speed 1 on a reason, log the reason, show the reason in the notification text.

### 4.3 First run — the only time the user is asked for anything

Three screens, and then a rehearsal. No carousel, no account, no settings screen (`CLAUDE.md`).

1. **What it does.** One sentence, one illustration. *"When an unknown number calls, Kavach listens
   for the pressure tactics scammers use, and warns you on screen. Everything happens on this phone."*
2. **What it hears, exactly.** The honest §1.4 table in plain language, including "on a normal
   earpiece call we hear your side, not theirs — put it on speaker and we hear both." Users forgive a
   limitation they were told about. They do not forgive discovering one.
3. **The permission ladder.** Not a wall of dialogs — a **checklist with live status per row**, each
   row one line of *why*, each row tappable to fix, and the screen re-checks on resume:

   ```
   ✅  Microphone                 so we can hear the conversation
   ✅  Notifications              so we can warn you
   ⬜  Call screening role        so we know an unknown number is calling      [Grant]
   ⬜  Accessibility service      Android silences every other app during a
                                 call. This is the one documented exception.
                                 We read nothing.                             [Open settings]
       ↳ ⚠️ Android will grey this out for sideloaded apps.
          App info → ⋮ → Allow restricted settings.                  [Show me]
   ⬜  Draw over other apps       so the warning appears over your call        [Grant]
   ⬜  Full-screen alerts         so it reaches you on the lock screen         [Grant]
   ```

   Kavach must remain **usable at every rung of that ladder**, degrading loudly, not silently. Mic
   only = manual sessions. + screening = zero-tap labels. + a11y = in-call audio. + overlay/FSI = the
   real product. Show the user which tier they are on, in one line, on the home screen.

4. **The rehearsal.** A "Show me what a warning looks like" button that replays a frozen fixture and
   fires the *real* alert surface. This is the single highest-value screen in the app:
   - the user has now seen the alert once, calmly, so the real one is recognised in 200 ms under stress;
   - it proves every permission actually works, on *their* device, *before* it matters;
   - it is your activation metric and your demo, and it needs no phone call to run.

Gate onboarding completion in DataStore. Never show it twice.

### 4.4 The in-call alert — designed for a 60-year-old under pressure

Assume: not wearing glasses, phone at the ear then moved to look, being actively shouted at by
someone claiming to be the police, adrenaline high, and Kavach has roughly **three seconds**.

- **One word, enormous.** `LIKELY SCAM` / `BE CAREFUL`. Not a number. Not a percentage. The 0–100
  score is engineering telemetry; it is not a hero element.
- **One sentence of because.** *"This caller asked you to keep the call secret and to move money to a
  'safe account'."* Plain language generated from the fired markers, never raw model text
  (`CLAUDE.md` rule 4). The *because* is what converts a warning into a decision.
- **At most three markers**, in the caller's own paraphrased words. Evidence beats authority.
- **Exactly two buttons.** `Call 1930` (`ACTION_DIAL`, pre-filled — the user presses green, Kavach
  never places a call) and `I'm fine`. No third option. No settings gear.
- **Never cover the hang-up control.** Occupy the top ~60% of the screen. The user must always be able
  to end the call without dismissing Kavach first. Getting this wrong is a demo-day disaster and a
  real-world safety bug.
- **Colour is never the only channel.** Word + icon + haptic pattern + colour. `KavachTokens`
  PressRed for HIGH, ochre for CAUTION — both already legible on the paper ground in light and dark.
- **Escalate in place, never stack.** One alert per call, mutated as the band rises. A second popup is
  how you teach someone to dismiss reflexively.
- **Say when you cannot hear.** If `isClientSilenced` is true or RMS is flat, the surface reads
  *"Kavach can't hear this call on this device"* — not a green all-clear. **An honest failure state is
  worth more than a false negative dressed as safety**, and it is the difference between a tool and a
  liability.

### 4.5 After the call — the flywheel

On `MODE_IN_CALL → MODE_NORMAL`, a bottom sheet, once:

> **That call reached: CAUTION**
> We heard: urgency, a request for an OTP.
> **Was it a scam?**  [ Yes ]  [ No ]  [ Not sure ]

This is your labelling flywheel, it never leaves the device, and it is the thing a YC partner will
recognise — a product that gets more accurate the more it is used, with no data collection story to
defend. `No` writes a negative example locally and suppresses that number for a week.

### 4.6 Never-nag rules — write these as tests

1. Contact → no UI, ever.
2. Max one alert per call.
3. `I'm fine` suppresses alerting for the rest of that call; capture continues silently.
4. `Not a scam` on the post-call sheet suppresses that number for 7 days.
5. Long-press the notification → `Never for this number`, honoured forever.
6. No persistent notification when not in a call. The FGS exists only for the duration of a session.
7. If the user dismisses three speed-1 offers in a row without acting, stop offering for 24 hours and
   say so once. **The app must be able to notice it is being annoying.**

### 4.7 What to measure (the YC read)

Three numbers, and none of them is DAU. Kavach is a product whose success looks like *absence*.

| Metric | Definition | Target |
|---|---|---|
| **Activation** | % of installs completing the §4.3 rehearsal | > 70% |
| **Zero-touch days** | median days monitored per app-open | as high as possible — opening the app is a *failure* |
| **False-positive rate** | alerts on `fixtures/negative/` | **0 in 50.** This is the headline number |

Positioning line: *"Truecaller tells you who is calling. Kavach tells you what they are doing to you."*
Identity is a solved, commoditised, database problem. **Behaviour** is the moat, it is on-device, it
needs no crowd-sourced number list, and it works on a number nobody has ever reported.

---

## 5. The product layer

| Gap | Fix | Effort |
|---|---|---|
| No trigger | `CallScreeningService` → notification → one tap (§2.2) | 3 h |
| No second door | QS `TileService` → `startActivityAndCollapse` (§2.3) | 45 min |
| No onboarding/consent | §4.3, four screens, gated in DataStore | 2 h |
| No persistence | `androidx.datastore:datastore-preferences`. Keys: `onboarding_done`, `rehearsal_done`, `tier_ack`, `unknown_callers_only`, `alert_threshold`, `suppressed_numbers`, `corrections[]` | 1 h |
| "Unknown callers only" | `ContactsContract.PhoneLookup` **inside the screening service**, not later | 45 min |
| 1930 not tappable | `Intent(ACTION_DIAL, "tel:1930".toUri())` on the action *and* the in-app row. `ACTION_DIAL`, never `ACTION_CALL` | 15 min |
| `"Not a scam"` == `"Stop"` | Split the handlers. `onDismissFalsePositive()` records `{transcript_hash, markers_fired, band, ts}` locally and suppresses alerting **for this session only**; capture continues. `onStop()` ends the session. Two verbs, two code paths | 1 h |
| No post-call surface | §4.5 sheet | 2 h |
| vivo/iQOO survival | `DEMO_RUNBOOK.md` by hand: Battery → Background power consumption → allow high; Autostart on; lock in Recents; Battery optimisation → Don't optimise; Notifications → lock screen + banner for `CH_ALERT_V2`; restricted settings unblocked. Do **not** build OEM intents | 20 min |

---

## 6. Runtime defects — concrete fixes

### 6.1 Tier-2 verdict overwritten within 1 s
`merge()` is pure; `tick()` republishes `assess()` unconditionally every 1000 ms, so the LLM verdict
survives less than a second. A 2.97 GB model with no observable effect.

```kotlin
private val llmVerdict = MutableStateFlow<LlmVerdict?>(null)

private fun adjudicateOne(text: String) {
    val v = adjudicator.adjudicate(text)
    llmVerdict.value = v                                        // persist
    publish(engine.merge(engine.assess(elapsedMs()), v))
}

private fun tick() {
    publish(engine.merge(engine.assess(elapsedMs()), llmVerdict.value))   // same merge, every tick
}
```

Clear `llmVerdict` in `reset()`. **Domain test:** `assess()` returns CAUTION, LLM returns HIGH_RISK,
advance the virtual clock 5 s, assert the published band is still HIGH_RISK. That test is the proof
the model does anything, and it belongs in the deck.

### 6.2 Stale transcripts crossing sessions
`adjudicationQueue` is a member `Channel(2, DROP_OLDEST)` reused across `start()`, so up to two
transcripts from the previous call leak into the next session — immediately after `engine.reset()`.
On stage this reads as a false positive on your own negative fixture.

```kotlin
private fun start() {
    val queue = Channel<String>(capacity = 2, onBufferOverflow = DROP_OLDEST)
    currentQueue = queue
    sessionJob = scope.launch {
        try { for (t in queue) adjudicateOne(t) } finally { queue.close() }
    }
}
```

Per-session channel, created in `start()`, closed in `finishSession()`, consumer scoped to the session
job. Never a member. Belt and braces: stamp each transcript with the session id and drop mismatches.

### 6.3 `close()` racing native inference
`adjudicate()` takes the mutex; `close()` does not; `KavachApplication` calls it from `collectLatest`
on another thread. Native use-after-free.

```kotlin
suspend fun close() = mutex.withLock {
    conversation?.close(); conversation = null
    engine?.close();       engine = null
}
```

Null-check the handles in `adjudicate()` *after* acquiring the lock. Also disable model deletion while
a session is live — grey the button and say why.

### 6.4 `runCatching` swallowing `CancellationException`

```kotlin
runCatching { block() }
    .onFailure { if (it is CancellationException) throw it else reportFailure(it) }
```

Everywhere `runCatching` wraps suspending work. Separately: make `finishSession()` idempotent (guard
on an `AtomicBoolean`) and have `stop()` `join()` the job rather than race it.

### 6.5 `START_STICKY` restarting the mic unbidden

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action != ACTION_START_USER_INITIATED) { stopSelf(); return START_NOT_STICKY }
    if (checkSelfPermission(RECORD_AUDIO) != GRANTED)  { stopSelf(); return START_NOT_STICKY }
    try {
        ServiceCompat.startForeground(this, ID, notif(), FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } catch (e: Exception) {   // SecurityException / ForegroundServiceStartNotAllowedException
        notifyDegraded("Android blocked listening — tap to retry"); stopSelf(); return START_NOT_STICKY
    }
    if (!started.compareAndSet(false, true)) return START_NOT_STICKY
    startMonitoring(); return START_NOT_STICKY
}
```

This is the finding a privacy-minded judge looks for, and fixing it is also the honest thing.

### 6.6 Repeated markers decaying instead of accumulating
`seenSpans.putIfAbsent(key, ts)` keeps the *first* timestamp; `SignalAggregator.decayed()` decays from
it. So a phrase repeated for twenty minutes decays to nothing — but a twenty-minute digital-arrest
script **is** repetition. Repetition must raise confidence.

```kotlin
val prev = seen[key]
if (prev == null || now - prev.lastSeen > DEDUP_WINDOW_MS) {   // 3–5 s covers ASR partials
    seen[key] = Sighting(firstSeen = prev?.firstSeen ?: now, lastSeen = now,
                         count = (prev?.count ?: 0) + 1)
}
// decay from lastSeen; optionally weight by ln(1 + count), capped
```

**This is a threshold change.** Per `CLAUDE.md`: re-run all fixtures and report the false-positive rate
on `fixtures/negative/` before and after, in the commit message. Do not ship it without that number.

### 6.7 Smaller
- **Recogniser leak:** assign the instance before `startListening`, `try/catch` the start, destroy in
  the catch. `awaitClose` must be able to see it.
- **Listening gaps:** solved for free by §1.3 — the `AudioRecord` runs continuously and the recogniser
  cycles against buffered audio. If §1.3 is deferred, drop `RESTART_DELAY_MS` to ~50 ms and log a gap
  counter so the loss is quantified rather than guessed.
- **No init backoff:** exponential backoff on `GemmaLlmAdjudicator.initialize()` — 1 s, 2 s, 4 s, then
  latch off for the session and surface "Tier-2 unavailable" honestly in the UI.
- **`MicCapture.kt`:** no longer dead — it is the §1.3 capture path. Its `MIC` fallback must be gated
  on `mode == MODE_NORMAL` (§1.2 condition 2).

---

## 7. Corpus — the thing that actually kills you

Nothing above matters if a real bank fraud desk trips HIGH_RISK on stage.

- **50 negatives minimum.** Real bank verification, courier calls, KYC, police, delivery, and people
  *warning each other about scams* (the `police-verification-01` shape — a conversation about a scam
  looks exactly like a scam to a lexicon).
- **Put the false-positive rate on the home slide.** *"0 false positives across 50 real calls"* beats
  any model name on any slide.
- **Generate cheaply:** script your 7 conversation shapes into 50 variants, read them aloud, run them
  through the **real ASR**. Real ASR output — with its errors, its code-switching, its dropped words —
  is the input the engine actually sees. Text fixtures hide precisely the failure mode that matters.
- **State the corpus size before anyone asks.** Volunteering a limit reads as rigour.

---

## 8. Implementation plan

Each slice is one vertical slice per `CLAUDE.md`: it runs end to end on the physical iQOO, its JVM
tests pass, ktlint/detekt/CI are green including `assertNoInternetPermission`, it degrades safely, and
it is tagged `demo-safe-h<NN>` before the next slice starts.

### Slice 0 — Ground truth (gates everything) · 1.5 h
Nothing else is worth writing until this returns a number.

- Run the app on the iQOO at all. Live ASR, `en-IN`, airplane mode, quiet room.
- Instrument `isClientSilenced` + per-frame RMS + `AudioManager.getMode()`.
- Run the **four-arm silence test** (§1.5). Record logcat for all four arms.

**Done when:** you can state, with a logcat screenshot, whether arm D passes on this device.
**If arm D fails:** stop. Go to §9. Do not proceed to slice 3.

### Slice 1 — Alert plumbing · 1.5 h · *no device dependency, do it in parallel with slice 0*
- Split notification channels (§3.1), delete the stale one, bump IDs.
- `ACTION_DIAL` for 1930 on both the notification action and the in-app row.
- Split `Not a scam` from `Stop listening` (§5).

**Done when:** a HIGH_RISK verdict produces a heads-up banner with sound and haptics with the app
backgrounded, and the two footer buttons do two different things.

### Slice 2 — Make Tier-2 real · 1.5 h · *domain + one file*
- 6.1 persisted `llmVerdict` + the virtual-clock domain test.
- 6.2 per-session channel + session-id stamping + its test.

**Done when:** the new domain test passes and an LLM HIGH_RISK verdict survives 5 seconds of ticks.

### Slice 3 — Kavach owns the microphone · 3 h · **the load-bearing slice**
- Wire `MicCapture` into `ShieldController`; gate the `MIC` fallback on `mode == MODE_NORMAL`.
- `ParcelFileDescriptor.createPipe()`; ring-buffer writer feeds the write end; recogniser reads via
  `EXTRA_AUDIO_SOURCE` + the three format extras.
- **Write the fallback first:** unsupported-PFD detection → recogniser-owns-mic mode → named degraded
  state in the UI.
- Rewrite `docs/ARCHITECTURE.md` §2 in the same commit.

**Done when:** a transcript arrives on device via Kavach's own `AudioRecord`, and `AudioRecordingConfiguration`
confirms the recording client is Kavach's UID.

### Slice 4 — The a11y exemption · 2 h
- `KavachAccessibilityService`, minimal config, no window content, empty event handler.
- `KavachShieldActivity` with `setShowWhenLocked` + `setTurnScreenOn`, starting the FGS from the
  foreground (§2.1).
- Re-run arm D with the real pipeline.

**Done when:** `allowCapture=1` mid-call in logcat, and a real transcript from a live call.

### Slice 5 — The zero-tap trigger · 3 h
- `KavachCallScreeningService` + `ROLE_CALL_SCREENING` request flow.
- `PhoneLookup` contact filter **in the screening service**.
- Speed-1 reason gate (§4.2) — never notify on every unknown call.
- Alert notification with `setFullScreenIntent`.

**Done when:** an unknown-number call produces a lock-screen offer with zero user action, and a
contact produces nothing at all.

### Slice 6 — The visible product · 3 h
- `TYPE_APPLICATION_OVERLAY` in-call alert per §4.4 — one word, one because, three markers, two
  buttons, top 60% only.
- QS `TileService` (§2.3).
- Escalate-in-place; the never-nag rules (§4.6) as domain tests.

**Done when:** a live scam call raises a legible full-screen warning over the in-call UI without
hiding the hang-up button.

### Slice 7 — Credibility · 3 h
- Onboarding four screens + the **rehearsal** (§4.3).
- DataStore persistence.
- Post-call sheet (§4.5).
- Tier indicator on the home screen.

**Done when:** a fresh install reaches a working rehearsal alert without a single line of adb.

### Slice 8 — Hardening · 2 h
- 6.3, 6.4, 6.5, 6.7.
- 6.6 **with the before/after false-positive number in the commit message.**

### Slice 9 — Corpus · ongoing, start now, finish last
- 50 negatives through real ASR (§7).

### Slice 10 — Demo survival · 20 min, the morning of
- `DEMO_RUNBOOK.md` by hand on the device (§5), then a full dry run from a cold boot.

**Parallelism:** slices 1, 2 and 9 have no device dependency and can proceed while slice 0 runs.
Slices 3 → 4 → 5 → 6 are strictly sequential.

---

## 9. If arm D fails — the honest fallback product

Do not improvise this at 2am. Decide it now.

Kavach becomes **two tiers that both still work**:

1. **Zero-tap caller intelligence** — `CallScreeningService` with STIR/SHAKEN + number heuristics +
   contact filtering. No audio, no accessibility, no overlay dependency, no Play-policy problem. This
   ships to the Play Store as-is.
2. **User-initiated conversation analysis** — everything already built, for the situations where the
   mic genuinely works: speakerphone on a *second* phone, in-person pressure (the ATM, the shop
   counter), a scam call a family member is on, a laptop video call. Reframe from "we monitor your
   calls" to **"the guardian phone"** — the phone you put on the table when something feels wrong.

Then say from the stage: *"Android reserves in-call audio for the pre-installed dialer. Here is
exactly why, here is the one documented exemption, here is our test showing this device does not
honour it, and here is the product that works anyway."* That is a stronger talk than a demo that
works by accident.

---

## 10. What to say on stage

- **Do** say: *"Android silences the microphone during calls for every app that isn't the pre-installed
  dialer. Here is the one exemption in Google's own documentation that a third party can reach, here
  is the AOSP source, and here is our logcat showing `allowCapture=1` mid-call."*
- **Do** show the arm C vs arm D contrast. Finding the exemption is good; knowing *why* it only works
  when you own the AudioRecord is what proves you understand the platform.
- **Do** say: *"This is Play-policy restricted, so the shipping path is the default-dialer role. We
  built the sideload version to prove the detection works."*
- **Do** say the corpus is seven conversations, and what you'd do with a month.
- **Do** lead with the Hindi–English code-switched lexicon and the negative guards. That is the asset.
  Gemma is a download.
- **Don't** say "on-device scam call detection" unqualified. Say what you hear and when.
- **Don't** claim the alert is fully automatic. It is one tap, by platform design, and the tap is also
  the consent `docs/SAFETY.md` §7 requires.

The team that names its own walls and shows the exemption it found is more impressive than the team
that claims there were no walls.

---

## 11. `CLAUDE.md` / docs amendments this plan requires

These are decisions, not code. Get them written down before the slices start.

1. **`docs/ARCHITECTURE.md` §2** — factually wrong (§1.6). Rewrite in slice 3.
2. **`docs/SAFETY.md`** — must gain: why an accessibility service is requested and what it does not
   read; the honest §1.4 capture table; the never-nag rules; the post-call label storage.
3. **New permissions** — `READ_PHONE_STATE`, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`,
   `READ_CONTACTS`. `INTERNET` stays removed; `assertNoInternetPermission` stays green. Each new
   permission needs a one-line justification in `SAFETY.md` — a reviewer will diff the manifest.
4. **`CLAUDE.md` rule 5 (advisory only)** — §2.4 proposes a user-pressed `endCall()` under
   `ROLE_DIALER`. That is roadmap, not this build, but if it moves in, amend rule 5 explicitly to
   "no *autonomous* action; user-initiated actions are permitted" rather than quietly reinterpreting it.
5. **`CLAUDE.md` "no new dependencies"** — this plan adds `androidx.datastore:datastore-preferences`
   only. No Hilt, no Room, no Retrofit, no networking. Confirm before slice 7.
6. **`demo/`** — untouched by every slice above. If `DEMO_FROZEN` exists, it stays read-only.
