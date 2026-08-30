# Evaluation — how to verify every claim Kavach makes

**For a reviewer, human or automated.** This file exists because a hackathon
submission that says "100% detection, zero false positives, fully offline" is
worth nothing unless those three things can be checked in under a minute
without trusting the team that wrote them.

Everything below is measured from this checkout. Where a number appears in the
README, the deck or the demo, it appears here too, next to the exact command
that produces it.

---

## The one command

```bash
./scripts/verify-claims.sh
```

No device and no network needed. It prints 31 claims with their measured
values and **exits non-zero if any one of them has stopped being true.**
Runtime is about a minute, most of it Gradle.

Current output on `main`:

```
  PASS  INTERNET permission stripped at merge          tools:node="remove"
  PASS  CI gate wired into :app:check                  assertNoInternetPermission
  PASS  No SMS / contacts / call-log permission        Message Guard reads notifications only
  PASS  Audio never written to disk                    0
  PASS  domain/ is pure Kotlin (zero android imports)  0
  PASS  Advisory only (no endCall / ACTION_CALL / block) 0
  PASS  Tactic families                                5
  PASS  Detection markers                              180  (English + Hinglish + Devanagari)
  PASS  Negative guards                                40  (subtract score - the false-positive defence)
  PASS  HIGH_RISK rule                                 score >= 70 AND >= 3 distinct families
  PASS  CAUTION threshold                              score >= 40
  PASS  Score decay half-life                          120s
  PASS  Scam fixtures (positive)                       10
  PASS  Legitimate fixtures (negative)                 8
  PASS  Unit tests (JVM, no device needed)             136
  PASS  Calls - scam scripts reaching HIGH_RISK        10/10
  PASS  Calls - false positive rate                    0%      (0/8)
  PASS  Messages - positives flagged                   10/10
  PASS  Messages - false positive rate                 0%      (0/8)
  PASS  Fastest scam to HIGH_RISK                      24s of speech
  ...
  All 31 claims verified.
```

---

## The rubric, criterion by criterion

Weights are the published iQOO Hackathon 2026 City Battles rubric.

| # | Criterion | Weight | Assessed by | Evidence |
|---|---|---|---|---|
| 1 | End product quality | 30% | Jury | [§1](#1-end-product-quality--30) |
| 2 | Novelty and impact | 20% | Jury | [§2](#2-novelty-and-impact--20) |
| 3 | Creative phone use | 15% | HackTracker telemetry | [§3](#3-creative-phone-use--15) |
| 4 | Technical depth | 15% | Jury | [§4](#4-technical-depth--15) |
| 5 | Office Kit usage | 10% | HackTracker telemetry | [§5](#5-office-kit-usage--10) |
| 6 | Demo and presentation | 10% | Jury | [§6](#6-demo-and-presentation--10) |

Criteria 3 and 5 — 25% combined — are scored from device telemetry, not from
this repository. Nothing written here can influence them, and this file does
not pretend otherwise; it records what the app genuinely uses so the jury half
of those criteria has something to read.

The rubric's own tiebreaker is **"a well-built simple product beats a broken
complex one."** That is also the prime directive in this project's
`CLAUDE.md`, written before the rubric was read, and it is why
[§7](#7-what-we-deliberately-did-not-build) exists.

---

## 1. End product quality — 30%

**The claim:** a complete, working Android app a stranger can install and use —
not a demo harness. Two independent defences, calls and messages, both running
end to end on real hardware.

| What works | Where to look |
|---|---|
| Detects scam calls live, on-device, in Hindi / Hinglish / English | `./scripts/verify-claims.sh` §4, or read a script from `docs/DEMO_SCRIPT.md` aloud |
| Raises itself when a call starts — the user opens nothing | `a11y/KavachAccessibilityService.kt:87` (`onCallStarted`), `capture/CallWatcher.kt` |
| Scans incoming SMS / RCS and warns on the **lock screen** | `message/MessageNotificationListener.kt` |
| The warning is actionable without unlocking — Call 1930 / This is fine | `message/MessageGuardActions.kt` |
| Degrades honestly when mic, model or permission is missing | `setup/Readiness.kt` — `tier()` returns one of four honest sentences |
| Runs with the network off | The app holds no INTERNET permission, so offline is not a mode — it is the only mode |

**Quality gates, all green on `main`:**

```bash
./gradlew test                              # 136 unit tests
./gradlew ktlintCheck detekt
./gradlew :app:assertNoInternetPermission
```

**Verified on hardware:** iQOO I2501, Android 16. Not an emulator — the in-call
audio path does not behave on emulators, and we would rather say so than demo
on one.

---

## 2. Novelty and impact — 20%

**The gap, with sources** (all in [`docs/PRD.md`](PRD.md) §2):

| Fact | Source |
|---|---|
| ₹22,495 crore lost to cybercrime in India in 2025, up 24% YoY | ThePrint, citing national figures |
| ₹109 crore to "digital arrest" scams in Karnataka (641 cases); **₹42.41 crore from 480 cases in Bengaluru** | Deccan Herald, Dec 2024, citing the state Home Minister |
| 1 in 5 Indian families with a UPI user defrauded in 3 years; 51% never complained | LocalCircles, 32,000+ respondents, 365 districts |
| Google's on-device scam detection is **Pixel 9+ only and English-only**; Pixel is <1% of India's Android market | TechCrunch / Gulf News, Nov 2025 |

**What is actually novel, stated narrowly:**

1. **It works during the call, not after it.** Truecaller-class products match a
   *number* against a database and are blind to an unflagged VoIP number.
   Kavach matches the *script*, so a fresh number buys the scammer nothing.
2. **It is code-switched by construction.** 180 markers carry English,
   romanised Hinglish and Devanagari forms side by side, because a real scam
   call mixes all three inside one sentence. Lexicon v1.2.0 brought the
   Devanagari half to parity specifically because a Hindi-transcribed call was
   being matched against a seventh of the lexicon — see `notes` in
   `data/tactic_lexicon.json`.
3. **It guards the message door too**, with no `READ_SMS` permission anywhere.
4. **The privacy claim is a build failure, not a promise.** See §4.

---

## 3. Creative phone use — 15%

Scored from HackTracker telemetry. What the app genuinely exercises:

| Capability | Where |
|---|---|
| **Voice / microphone** — continuous ambient capture for a whole session | `capture/MicCapture.kt` |
| **On-device AI** — Android's offline speech recogniser, `hi-IN` and `en-IN`, rotated per utterance | `inference/PipedAsrTranscriptSource.kt`, `SystemAsrTranscriptSource.kt` |
| **On-device model management** — `checkRecognitionSupport()` / `triggerModelDownload()` so the user never hunts through OEM settings | `inference/SpeechModelManager.kt` |
| **Accessibility service** — the documented exemption that keeps the mic alive mid-call | `a11y/KavachAccessibilityService.kt` |
| **Overlay windows** — the call shield, plus a `TYPE_APPLICATION_OVERLAY` Dynamic Island capsule for messages | `ui/ShieldOverlayActivity.kt`, `ui/MessageIslandOverlay.kt` |
| **Notification listener** — Message Guard's entire input | `message/MessageNotificationListener.kt` |
| **Quick Settings tile** — one tap from the shade, over a call or a lock screen | `tile/KavachTileService.kt` |
| **Foreground service, `type=microphone`** — the persistent notification treated as a feature, not a cost | `capture/KavachService.kt` |
| **Full-screen intent and lock-screen surfaces** | `capture/KavachNotifications.kt` |

**Camera is not used.** A UPI QR guard was scoped P2 in `docs/PRD.md` and was
not built. `UpiLinkAnalyzer` ships and is real, but it runs on links found
*inside messages*, not on a camera feed. We would rather lose the point than
claim a feature a reviewer cannot find.

---

## 4. Technical depth — 15%

### The detection engine

```
HIGH_RISK  requires  score >= 70  AND  >= 3 distinct tactic families
CAUTION    requires  score >= 40
signals decay exponentially, half-life 120 s
```

All four numbers live in one place — `data/tactic_lexicon.json` → `scoring` —
and are printed by the verify script. Five families: authority impersonation,
isolation and secrecy, urgency and threat, credential extraction, remote access
and transfer.

**The Tactical Diversity Rule is the whole design.** One loud keyword can never
raise an alarm. That is why `fixtures/negative/genuine-delivery-otp-en-01` — a
real courier asking for a real delivery OTP — stays silent, and why a real bank
saying "we will never ask for your OTP" stays silent: **40 negative guards**
subtract from the score, and they decay like any other signal, so they only
offset markers from the same part of the conversation.

Marker provenance is recorded in the lexicon itself: public advisories from
I4C, PhonePe Trust & Safety, Seqrite and RBI. **No private or field data.**

### The hard part — hearing anything at all during a call

Android silences an ordinary app's microphone in `MODE_IN_CALL`. It does not
error; it hands over a stream of zeroes. Kavach solves this the only way a
non-privileged app can:

1. Register an accessibility service, which places our UID on the list the
   audio policy consults. **It reads nothing** — `onAccessibilityEvent` is
   literally `= Unit` (`a11y/KavachAccessibilityService.kt:62`) and
   `canRetrieveWindowContent="false"`.
2. Open `AudioRecord` under **our own** UID with `VOICE_RECOGNITION` and pipe
   samples to the recogniser through a `ParcelFileDescriptor`. Letting the
   recogniser open the mic puts the recording in *its* UID, which is silenced.
   This is not a style choice — the obvious implementation returns silence
   during exactly the calls the app exists for.
3. Show the shield, because the exemption requires our UI on top, which also
   legalises the microphone foreground service. The two constraints solve each
   other; neither is worked around.

And because Android fails *silently* here, capture is instrumented rather than
trusted: `capture/CaptureDiagnostics.kt` measures RMS on every 20 ms frame and
asks the platform `isClientSilenced`, so "the room is quiet" and "we have been
muted" are distinguishable and the UI says which. A calm green screen over a
dead microphone is the one failure this architecture exists to prevent.

### Enforced invariants

| Invariant | How it is enforced | Check |
|---|---|---|
| No `INTERNET` permission, ever | `tools:node="remove"` (manifest line 13) makes even a transitive one impossible; `:app:assertNoInternetPermission` is wired into `check` | `./gradlew :app:assertNoInternetPermission` |
| Audio never touches disk | Fixed in-memory ring buffer, cleared when the session ends | verify script §1 |
| `domain/` has zero `android.*` imports | Pure Kotlin, 122 JVM tests | verify script §2 |
| Bounded channels, `DROP_OLDEST` | A slow consumer degrades quality, never blocks the mic thread or leaks | verify script §2 |
| All model output schema-validated | `VerdictSchema.parseOrNull()` — raw model text can never reach the UI | `domain/VerdictSchema.kt:45` |
| Advisory only | No `endCall()`, no `ACTION_CALL`, no auto-block, no payment | verify script §2 |

### Measured accuracy

```bash
./gradlew :domain:test --tests '*FixtureCorpusTest*' -i   # calls
./gradlew :domain:test --tests '*SmsCorpusTest*'     -i   # messages
```

| Corpus | Result |
|---|---|
| Scam calls reaching HIGH_RISK | **10 / 10** |
| Legitimate calls reaching HIGH_RISK | **0 / 8 — 0% false positives** |
| Legitimate calls reaching even CAUTION | 0 / 8 |
| Scam messages flagged | **10 / 10** |
| Legitimate messages reaching HIGH_RISK | **0 / 8 — 0% false positives** |
| Fastest scam to HIGH_RISK | **24 s of speech** (budget 42 s) |

`SmsCorpusTest` deliberately scores the *call* corpus through the *message*
path, which is unkind on purpose: a real courier asking for an OTP and a real
bank asking for a KYC update arrive by SMS too. It caught a genuine bug — a
Devanagari bank fraud desk came out HIGH_RISK on the message path while the
call engine cleared it, and under the lock-screen surfaces that is a warning
accusing a real bank. Message-path false positives went 12% → 0%.

---

## 5. Office Kit usage — 10%

Scored from HackTracker telemetry, not from this repo. Where Office Kit is
load-bearing in the product itself rather than only in the workflow:

- The **incident report** is metadata only — timestamps, tactic IDs, scores,
  duration; never audio, never transcript — and exports through the system
  share sheet specifically so Office Kit file transfer can move it to a laptop
  and open it there. `domain/IncidentRecorder.kt`, `MainActivity.kt:442`.
- The **model file** (Gemma, 2.58 GB) reaches the phone over Office Kit at
  check-in and is imported through the system file picker; the app has no
  network permission to download it. `docs/ARCHITECTURE.md`.
- **Screen mirror** is the intended demo surface, so the jury watches the phone
  UI on a large display rather than the team's hands. `docs/DEMO_RUNBOOK.md`.

The build workflow is documented in
[`docs/MOBILE_FIRST_SETUP.md`](MOBILE_FIRST_SETUP.md), including the honest
finding that Gradle + Compose cannot be compiled on the phone, and what we did
instead.

---

## 6. Demo and presentation — 10%

- [`docs/DEMO_RUNBOOK.md`](DEMO_RUNBOOK.md) — checklist and running order.
- [`docs/DEMO_SCRIPT.md`](DEMO_SCRIPT.md) — the lines to read aloud.
- **Scenario pack**, calls and messages, every verdict measured rather than
  estimated: <https://claude.ai/code/artifact/ceec8e2c-925d-431f-b604-3a0df4716ca1>
- `DemoMode` replays any fixture through the **identical** pipeline, so the demo
  survives a dead network, a noisy hall and a missing second phone. It is not a
  mock: the same `RiskEngine` produces the same bands from the same lexicon.

The demo deliberately includes a **legitimate** call that stays calm. A
detector that only ever says "scam" is a smoke alarm taped to the on position,
and the negative corpus is the more interesting half of the result.

---

## 7. What we deliberately did *not* build

Listed so nobody has to discover it by asking, and because the rubric rewards a
well-built simple product over a broken complex one.

| Not built | Why |
|---|---|
| **Auto-blocking, auto-rejecting, hanging up** | Kavach is advisory and never acts on the user's behalf. Enforced by the verify script. |
| **Camera / UPI QR scanning** | Scoped P2, not reached. `UpiLinkAnalyzer` ships for links inside messages only. |
| **Alerting a relative's phone** | Needs a push backend, which contradicts the zero-network claim. The claim is worth more. |
| **Accounts, cloud sync, settings screens, onboarding** | Out of scope in `docs/PRD.md` §3, on purpose. |
| **Languages beyond Hindi and English** | Would be an untested claim. |
| **Instrumented (`androidTest`) tests** | 136 JVM unit tests plus manual device testing was the right trade at 30 hours. |

### The one thing that is integrated but switched off

**Tier 2 — Gemma via LiteRT-LM on the GPU — is disabled in this build.**
`KavachApplication.kt:166`, `TIER2_ENABLED = false`.

It is fully wired: model catalogue, import, GPU backend, schema-validated JSON
verdicts, and a silent fallback to Tier 1 on any parse failure. It is off
because LiteRT-LM 0.16.1 calls `SendChannel.close$default(...)` as a static on
the interface, while every kotlinx-coroutines in the 1.9–1.10 line puts that
bridge in `SendChannel$DefaultImpls`. The first completed inference throws
`NoSuchMethodError` on LiteRT-LM's own JNI thread — outside any coroutine we
own, so no `catch` of ours can reach it — and the process dies mid-call. The
stack trace and both attempted fixes are recorded in the source comment.

An adjudicator that kills the app during a scam call is strictly worse than no
adjudicator. Tier 1 was designed from the start to carry the product alone, and
**every corpus number in this document is Tier 1 on its own.** Nothing here
depends on the LLM being switched on.

We flag this rather than leave it for a reviewer to find in a constructor.
"Write the fallback before the risky path" is a rule in this project's
`CLAUDE.md`, and this is what it bought.

---

## Claims ledger

Every number Kavach quotes, and the single source of truth for it.

| Claim | Value | Source of truth |
|---|---|---|
| Tactic families | 5 | `data/tactic_lexicon.json` |
| Detection markers | 180 | same |
| Negative guards | 40 | same |
| Lexicon version | 1.3.0 | same |
| HIGH_RISK threshold / families | 70 / 3 | same → `scoring` |
| Decay half-life | 120 s | same → `scoring` |
| Scam fixtures | 10 | `fixtures/positive/*.txt` |
| Legitimate fixtures | 8 | `fixtures/negative/*.txt` |
| Unit tests | 136 | `grep -rh '@Test' domain/src/test app/src/test \| wc -l` |
| Call detection | 10/10 HIGH_RISK | `FixtureCorpusTest` |
| Call false positives | 0% (0/8) | `FixtureCorpusTest` |
| Message detection | 10/10 flagged | `SmsCorpusTest` |
| Message false positives | 0% (0/8) | `SmsCorpusTest` |
| Fastest to HIGH_RISK | 24 s | `FixtureCorpusTest` latency report |
| INTERNET permission | none, enforced | `:app:assertNoInternetPermission` |

If a number in the README, the deck or the pitch disagrees with this table,
**this table and the script are right and the other thing is stale.** Run
`./scripts/verify-claims.sh` and believe the output.

---

## Further reading

| File | What it is |
|---|---|
| [`CLAUDE.md`](../CLAUDE.md) | The operating rules the build was held to, written first |
| [`docs/PRD.md`](PRD.md) | Problem, sourced statistics, MVP scope and explicit non-goals |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | How in-call capture actually works, and why the obvious approaches fail |
| [`docs/SAFETY.md`](SAFETY.md) | What the app promises the user, and the false-all-clear failure it is built to prevent |
| [`docs/HANDOFF.md`](HANDOFF.md) | Known gaps and open findings, unedited |
