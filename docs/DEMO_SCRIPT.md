# Demo script — a live call that escalates on cue

Read by the **caller**, on a second phone, into a **speakerphone** call.
Tuned against `data/tactic_lexicon.json` v1.2.0 and the Tier-1 scorer only —
it does **not** need the Gemma model imported.

---

## Why speakerphone is not optional

Kavach captures the **room**, not the call stream (Android blocks
`VOICE_CALL` — `docs/ARCHITECTURE.md` §2). So:

| Mode | What Kavach hears |
|---|---|
| Earpiece | **Your half only.** The caller is inaudible. |
| **Speakerphone** | **Both halves.** The only mode the demo works in. |
| Headset / Bluetooth | Almost nothing. |

Put the phone on a table, speakerphone on, caller's volume high.
If you demo on earpiece it will look broken, and it is not.

---

## How the score is built

Caps per family: AUTHORITY 18 · ISOLATION 22 · URGENCY 15 · CREDENTIAL 30 ·
REMOTE 28. Diversity bonus: +12 per family beyond the second.
`HIGH_RISK` needs **≥70 AND ≥3 distinct families** — so the escalation below
is deterministic, not hopeful.

| After beat | Families | Score | Band |
|---|---|---|---|
| 1 | 1 | ~18 | WATCHING |
| 2 | 2 | ~33 | WATCHING |
| 3 | 3 | ~67 | **CAUTION (amber)** |
| 4 | 4 | 100 | **HIGH_RISK (red alert)** |
| 5 | 5 | 100 | HIGH_RISK, all five named |

---

## THE SCRIPT (English — most reliable with `en-IN`)

**Delivery rules for the caller:**
- **Pause 2 seconds between beats.** The recogniser only finalises an utterance
  at a silence boundary, and only finalised text is scored.
- Speak at normal pace. Do not shout.
- Say the **bolded phrases exactly** — they are the markers.

> **Beat 1 — Authority** *(expect: still calm, "Watching")*
> "Good afternoon. This is Inspector Sharma from the **CBI**.
> **Your Aadhaar is linked** to a **money laundering** case."

> **Beat 2 — Threat** *(expect: still calm — this is the point, one loud
> family is not enough)*
> "An **arrest warrant** has been issued against you. This is a
> **non bailable** offence. **You will be arrested within two hours**."

> **Beat 3 — Isolation** *(expect: **amber CAUTION**, tactics start listing)*
> "You are under **digital arrest**. **Do not tell anyone** — not your family.
> **Keep your camera on** and do not disconnect this call."

> **Beat 4 — Money + remote access** *(expect: **RED. Full-screen warning,
> double buzz, Call 1930 button**)*
> "For clearance you must **transfer to this account**. It is a
> **safe custody account**, fully refundable. Now install **AnyDesk** and
> turn **screen sharing on**."

> **Beat 5 — Credentials** *(expect: all five tactics named on the card)*
> "**Read me the code** that just arrived. Tell me the **OTP**.
> And confirm your **UPI PIN**."

**Then hang up** → the post-call verdict notification fires on the loud channel.

---

## Hinglish variant (shows code-switching, riskier ASR)

Same five beats. Swap in:

1. "Main Sub Inspector bol raha hoon, **CBI** se. Aapke naam par
   **illegal parcel** pakda gaya, **parcel mein drugs** mile hain."
2. "**Giraftari warrant** issue ho gaya hai. Yeh **gair zamanti** offence hai.
   **Do ghante ke andar** **aap giraftar ho jayenge**."
3. "Aap **digital arrest** par hain. **Kisi ko mat bataiye**,
   **ghar walon ko mat batao**. **Camera on rakhiye**, **video call mat katna**."
4. "**Verification ke liye paise** **is account me transfer** kijiye,
   **safe custody account** hai. **AnyDesk** download karke **screen share** kijiye."
5. "**OTP bataiye**. Message me jo code aaya hai, **code padhiye**.
   Aur apna **UPI PIN** confirm kijiye."

---

## The second half nobody expects: prove it does NOT cry wolf

Call again and read this **legitimate bank** script. It deliberately trips
AUTHORITY and URGENCY — and must stay **below red**, because the negative
guards subtract.

> "Good afternoon, I am calling from your bank's fraud monitoring team about a
> transaction on your card ending 4471. We flagged eighteen thousand rupees at
> an online merchant. Was that you?"
> *(pause)*
> "Thank you, I have blocked the card. **We will never ask for your OTP**, PIN
> or password. If you prefer, **hang up and call us back on the number on your
> card**, or **visit your nearest branch**. Your **reference number for this
> call** is 8829104. **Take your time**, discuss with your family."

Expected: stays WATCHING or low CAUTION. Guards are worth −35, −30, −25, −20, −15.

**Say this out loud on stage.** A detector that only ever says "scam" is a
smoke alarm taped to the on position.

---

## Pre-flight (do this BEFORE anyone is watching)

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

# verify — vivo resets the accessibility list
adb shell settings get secure enabled_accessibility_services
adb shell appops get $PKG SYSTEM_ALERT_WINDOW
```

By hand on the device (see `DEVICE_TEST.md`): Autostart on, battery
"Allow high" + "Don't optimise", lock Kavach in Recents.

**Then confirm it can actually hear**, before the demo:
open the app, start a session, and watch the live capture readout —
`Silenced: no`, a real dB level, `Microphone owner: Kavach`, transcripts
climbing. If `Silenced: YES`, the demo will not work and no script fixes it.

Watch from the host throughout:

```bash
adb logcat -c
adb logcat -s KavachMatch KavachMic KavachAsr KavachA11y KavachCallWatcher KavachService
```

`KavachMatch` prints the families and the score on every window. That is your
ground truth that detection is live — and it never logs what was said.

---

## If it goes wrong on stage

- **Nothing transcribes** → the offline language pack is missing. `KavachAsr`
  will show error 12/13. Fall back to **DemoMode** and replay
  `fixtures/positive/digital-arrest-01.txt` through the identical pipeline.
- **Shield never appears** → overlay permission or OEM background-launch block.
  Use the **Quick Settings tile** ("Kavach shield") — one tap, works over a
  call and from the lock screen.
- **`Silenced: YES` mid-call** → accessibility got reset. Say so from the
  stage, show the logcat, and run the demo as speakerphone-with-app-open.
