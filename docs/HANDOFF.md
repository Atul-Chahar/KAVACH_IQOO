# Handoff — read this first

**Written 28 Aug 2026, end of the pre-event build session.**
Repo: https://github.com/Atul-Chahar/KAVACH (public, `main`, CI green)

This file exists so the next session does not re-derive what is already known.
Read it, then `CLAUDE.md`, then `docs/PRD.md`.

---

## 1. What we are building

**Kavach** — an Android app that warns someone, in real time and entirely
on-device, when the conversation they are in matches a known scam script. Hindi
and English. For the iQOO Hackathon 2026, Bengaluru, 29–30 Aug.

The pitch in one line: Google shipped on-device scam-call detection, then locked
it to Pixel 9+ — under 1% of India's Android market. Kavach brings it to the
other 99%.

**The organisers' updated rules removed the pre-event build restriction**, so the
prototype was built on Friday 28 Aug and the event itself is for refinement.
`docs/PRD.md` §7 still describes the *old* split and should be corrected before
Eval Round 1, so what we announce matches what we did.

---

## 2. The three things to fix next

### 2.1 The model integration does not exist. This is the big one.

The model was imported and nothing happened. That is not a bug — **there is no
Tier-2 integration at all.** Verified by grep, 28 Aug:

- **No inference runtime is on the classpath.** No LiteRT-LM, no MediaPipe, no
  TFLite dependency in `gradle/libs.versions.toml` or any `build.gradle.kts`.
- **`LlmAdjudicator` is an interface with zero implementations.**
  `ShieldController` declares `var adjudicator: LlmAdjudicator? = null` and it is
  **never assigned anywhere**. So it is always null, the Tier-2 branch returns
  immediately every time, and the UI always shows "Advanced analysis
  unavailable".
- **Nothing outside `ModelRepository` ever opens the model file.** The import
  path stages, verifies and stores 2.77 GB, and then no code reads it.

So today the app downloads and verifies a model it cannot use. The staging half
is done and tested; the inference half was never started. Calling it "a seam in
place" undersold how much is missing — from the outside it looks broken, because
functionally it is.

**What has to happen:**

1. Add the LiteRT-LM Android dependency and get *one* token generated from the
   staged file. Nothing else matters until that works.
2. Implement `LlmAdjudicator` against it — the interface and its whole failure
   contract already exist.
3. Assign it in `KavachApplication` when `ModelRepository.state` is `Ready`, and
   clear it when the model is deleted.

**Do not use the MediaPipe LLM Inference API.** Its own docs now say it is "in
maintenance-only mode. New features and optimizations will be focused on
LiteRT-LM." `CLAUDE.md` and `docs/ARCHITECTURE.md` §4 already say this. The
`LlmAdjudicator` seam is runtime-agnostic, so swapping backends is one class.

**The prompt handling and parsing are already built and tested — wire the
runtime, do not rewrite these.** `VerdictSchema.parseOrNull()` handles code
fences, prose wrappers, out-of-range scores, invented tactic names and runaway
reasons (10 tests). `RiskEngine.merge()` already enforces that the model may
raise a score but may never manufacture a `HIGH_RISK` state the deterministic
engine cannot justify.

### 2.2 Real voice has never been tested. Only DemoMode has ever run.

Everything verified so far was on an **emulator**, which has **no on-device
speech recogniser**. So `SystemAsrTranscriptSource` has never produced a single
real transcript. Only demo recordings appear because that is genuinely the only
working path today.

Live capture needs `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+).
On the emulator the app correctly reports "On-device speech recognition is not
available on this device" and degrades honestly — that message is *not* a bug,
it is the emulator telling the truth.

**First thing to do with a real phone connected** (developer mode + USB
debugging, accept the RSA prompt):

```bash
./scripts/device-check.sh          # chipset, permissions, recogniser, launch
```

Then tap **Start listening** and speak a scam line aloud, e.g.
*"main CBI se bol raha hoon, aap OTP bataiye"*, and watch the `Speech:` line at
the bottom of the screen to see which engine actually ran.

**Three things that can go wrong, in likelihood order:**

1. **No on-device recogniser on the phone.** Then live capture cannot work at
   all, and the fallback ladder in `docs/ARCHITECTURE.md` §4 has to be climbed —
   Whisper via Qualcomm AI Hub, or whisper.cpp on CPU.
2. **It needs the network.** The pitch says "airplane mode is on" out loud. If
   the recogniser silently needs a connection, that line dies on stage. **Test
   with airplane mode on**, specifically.
3. **Language mismatch.** `SystemAsrTranscriptSource.DEFAULT_LANGUAGE` is
   `en-IN`, which emits Latin script and matches the lexicon's romanised
   Hinglish markers. `hi-IN` emits Devanagari, which the lexicon also carries.
   Code-switched speech will be mangled by whichever is chosen. Try both against
   a spoken fixture and record which scores better.

### 2.3 The UI needs a real redesign

Verbatim: *"it looks like dog shit."* That is fair. What exists is Material 3
defaults with three band colours dropped in — correct against every constraint,
and with no visual identity: flat cards, default type, no hierarchy beyond font
size, an amber state that reads like a system warning toast rather than
something you would trust with your mother's money.

Screenshots of every current state: `docs/screenshots/`, plus a shareable
gallery at https://claude.ai/code/artifact/a9f0f181-d566-4e82-8900-48da26f7d4c7

**Constraints the redesign must keep** (`docs/SAFETY.md` §5 — these are good
constraints and are *not* the reason it looks bad):

- Minimum 18sp body text; legible at arm's length
- High contrast; calm, instructional copy
- **No panic aesthetics** — no sirens, no countdowns, no alarm red
- Every alerting state names the matched tactics in plain language
- Hindi-first when the device locale is Hindi
- Never "this is a scam" as fact — "matches known scam patterns"

**Two things to ask before starting:** an app whose look to use as the target,
and whether to stay strictly inside the no-alarm rule or push on it.

Do the device testing first. A finding there — no ASR, or a memory leak over 30
minutes — changes what the UI has to show, and redesigning twice is waste.

---

## 3. What genuinely works, and how it was verified

Verified by *running* it, not by reading it.

| Thing | Status | Evidence |
|---|---|---|
| Tier-1 detection engine | Works | 79 JVM tests; full corpus regression |
| Risk UI, three states | Works, ugly | Driven on device through the whole arc |
| `DemoMode` fixture replay | Works | Ran clean **in airplane mode** |
| Incident log + report | Works | Rendered on device, metadata only |
| `UpiLinkAnalyzer` | Works | 10 unit tests (camera QR not wired) |
| Model staging (download + import) | Works | Truncated file rejected on device |
| **Tier-2 LLM** | **Does not exist** | See §2.1 |
| **Live ASR / real voice** | **Never run** | See §2.2 |
| Camera QR capture (S3.1) | Not built | Logic done, capture not wired |
| Fixture WAV recordings (S0.5) | Not done | Only `.txt` scripts exist |

**Corpus numbers, measured — these are real, quote them:**

```
3/3 scam scripts        reach HIGH_RISK
0/4 legitimate calls    reach HIGH_RISK
0/4 legitimate calls    even reach CAUTION
```

Re-measure any time:
```bash
./gradlew :domain:test --tests '*FixtureCorpusTest*' -i
```
The rates print on every run. **Never tune thresholds by intuition** — tune
against `fixtures/` and report the false-positive rate, every time.

State the corpus size out loud when quoting: **7 conversations**. A jury that
discovers that themselves will discount everything else.

---

## 4. Build and environment

**There is no JDK on this machine's PATH.** Java is installed via mise. Either
`eval "$(mise activate bash)"` in the project (a trusted `mise.toml` pins JDK
21), or export it yourself:

```bash
export JAVA_HOME="$(mise where java@temurin-21)"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Android/Sdk"
```

Pinned deliberately — **do not bump any of this mid-event**: Gradle 8.11.1,
AGP 8.7.3, Kotlin 2.0.21, JDK 21, compileSdk 35, minSdk 30, targetSdk 35.

```bash
./gradlew ktlintFormat               # run BEFORE check; formatting fails the gate
./gradlew check                      # tests + ktlint + detekt + lint + invariants
./gradlew assembleDebug
./scripts/device-check.sh            # real-hardware smoke test
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

An emulator AVD named **`aegis`** (android-35 x86_64) exists and boots headless:
```bash
$ANDROID_HOME/emulator/emulator -avd aegis -no-window -no-audio -gpu swiftshader_indirect
```
Useful for UI work. **Useless for ASR and for any model runtime** — which is
exactly why §2.1 and §2.2 are still open.

---

## 5. Rules that must not be broken

From `CLAUDE.md`. These are not preferences.

1. **Never add `android.permission.INTERNET`.** CI task
   `assertNoInternetPermission` fails the build if it appears, and the manifest
   carries `tools:node="remove"` so a dependency cannot add it transitively.
   Both directions were tested. This is the headline privacy claim and the app
   is *designed* around it — see §6.
2. **`domain/` has zero `android.*` imports.** Enforced by
   `:domain:assertDomainIsPureKotlin`, wired into `check` and CI.
3. **Audio never touches disk.** No `File`, no `OutputStream` in `capture/` or
   `inference/`.
4. **All model output is schema-validated.** Raw model text must never reach the
   UI. On failure, fall back silently to Tier 1.
5. **Advisory only.** Never `endCall()`, never auto-reject, never auto-block,
   never initiate a payment. Grep for
   `endCall|rejectCall|disallowCall|setSilenceCall` returns zero hits and must
   keep doing so.
6. **Bounded channels only**, `DROP_OLDEST`. A slow consumer degrades quality,
   never leaks memory and never blocks the mic thread.

---

## 6. Decisions that will look odd without the reason

**The app cannot download its own model.** An in-app downloader needs the
INTERNET permission, which would break rule 1 and the pitch line. So
`ModelSetupScreen` hands the official URL to the **browser** via `ACTION_VIEW`,
and the finished file comes back through the Storage Access Framework picker.
This was put to the user, who chose it over adding the permission. The picker
also avoids any storage permission: access to exactly one file, once.

**The model catalogue is one entry, with a hash.** Read from the Hugging Face
API on 28 Aug — not from memory, because a wrong URL is a silent, unrecoverable
failure on the day. Repo `litert-community` is Google's own LiteRT distribution
org and is **ungated**: no account, no token.

```
gemma-4-E4B-it-gpu.litertlm   2,969,059,328 bytes
sha256 4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff
```

Other variants are listed in `docs/ARCHITECTURE.md` §4. **If the loaner iQOO is
a Snapdragon 8 Elite, `gemma-4-E2B-it_qualcomm_sm8750.litertlm` is the only
variant that genuinely puts reasoning on the NPU** — which would make the
"division of silicon" line in the pitch literally true. Adding it is one line in
`ModelCatalog`. `device-check.sh` reports the chipset.

**The mic is exclusive.** `SpeechRecognizer` and `AudioRecord` cannot both hold
it. That is why the abstraction is `TranscriptSource` (transcript level) and not
audio level, and why `KavachService` hosts whichever source is active rather
than owning the microphone itself. Building it the other way produces a service
that fights its own ASR.

**`DemoMode` runs in-process, with no foreground service.** Deliberate: it keeps
working with the mic permission denied and in airplane mode, which is what makes
it a trustworthy stage fallback.

---

## 7. Bugs already found by running it — do not reintroduce

- **Marker precedence.** All lexicon markers (scoring *and* negative guards)
  sort into **one** list, longest first. Before this, bare `otp` claimed its span
  before the longer guard `otp nahi maangte`, so a police officer *warning*
  someone about OTP fraud scored like a scammer demanding one. That one fix took
  `police-verification-01` from 66 to 18.
- **Tick liveness.** The 1 Hz UI tick must key off its **own** coroutine's
  `isActive`, not the parent job's — a parent whose body has finished but whose
  children still run reports `isActive == false`, which froze the elapsed clock
  the instant a fixture ended.
- **The service must not outlive the session.** `KavachService` stops itself
  when `monitoring` goes false. A persistent notification saying "Listening"
  while nothing is listening is a lie, and that notification is the user's
  evidence that we are honest about when the mic is live.
- **Free space must not be read during composition.** Import progress emits
  every ~8 MB — roughly 350 recompositions per 2.77 GB copy — and an inline
  `usableSpace` call blocks the composition thread every time.
- **Lexicon 1.1.0 added meta-discussion guards.** People warning each other
  about scams use a scammer's vocabulary in the opposite direction; without
  those guards `family-money-talk-01` scored 49.

---

## 8. Suggested order for the next session

1. **Connect the phone.** `./scripts/device-check.sh`. Answer the ASR question
   before anything else — it decides how much of the rest matters.
2. **Test real voice**, including with airplane mode on, and both `en-IN` and
   `hi-IN`.
3. **Wire LiteRT-LM** and get one token out of the staged model. Time-box it;
   Tier 1 is a complete story without it, and `docs/TASKS.md` S2.1 says to ship
   Tier 1 alone rather than sink the day.
4. **Redesign the UI**, once 1–3 have stopped changing the requirements.
5. Record fixture WAVs (S0.5), and correct `docs/PRD.md` §7.

**The rule that matters most, from `docs/TASKS.md`:** at every moment you must be
≤2 hours from a working, demoable build. That is true right now — DemoMode works,
in airplane mode, and carries the entire demo on its own.
