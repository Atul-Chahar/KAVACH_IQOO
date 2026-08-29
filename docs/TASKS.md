# Kavach — build order

Slices are ordered. **Do not start slice N+1 until slice N meets its acceptance criteria on the physical device.**

Each slice: implement → `./gradlew test ktlintCheck detekt` → run on device → `git tag demo-safe-h<NN>`.

---

# PHASE 0 — before the event (Fri 28 Aug)

Organiser-confirmed as permitted; disclosed in the submission. Build the things that fail *unpredictably*, so Saturday is spent on the product rather than on toolchain archaeology.

### S0.1 — Project skeleton ✅ DONE 27 Aug
- Android project, Kotlin, Compose, `minSdk 30`, `targetSdk 35`
- Modules: `:app`, `:domain` (pure Kotlin JVM), `:demo`
- ktlint + detekt configured
- **Done when:** empty app installs and opens on the iQOO device; `./gradlew test` runs

> Built on AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.11.1 / JDK 21, compileSdk 35.
> `./gradlew check` is green (tests + ktlint + detekt + Android lint + both
> invariants). Toolchain pinned in `gradle/libs.versions.toml` and `mise.toml` —
> **do not bump it mid-event.** Remaining on this slice: install and open the APK
> on the physical iQOO device.

### S0.2 — CI with the privacy invariant ✅ DONE 27 Aug
- GitHub Actions: build + test + ktlint + detekt
- Custom Gradle task `assertNoInternetPermission` that fails if `android.permission.INTERNET` appears in the merged manifest
- **Done when:** CI is green, and deliberately adding the permission turns it red. *Test this — an unverified guard is worse than none.*

> **Verified 27 Aug, both directions:**
> 1. `INTERNET` added to `app/src/main/AndroidManifest.xml` → build goes red with
>    `✗ PRIVACY INVARIANT VIOLATED`. ✅
> 2. `INTERNET` declared by a *library* module (the transitive-dependency case) →
>    stripped by `tools:node="remove"` in the app manifest, guard stays green, and
>    `aapt2 dump permissions app-debug.apk` confirms it is absent from the shipped
>    APK. ✅ Two layers: the remove-node prevents it, the guard proves it.
>
> A second invariant now exists: `:domain:assertDomainIsPureKotlin` fails on any
> `android.*` import in `domain/`. Both are wired into `check` and into CI.
> CI also uploads `app-debug.apk` as an artifact — a download path for the phone
> that does not depend on the venue network.

### S0.3 — Audio capture that survives
- `AudioCaptureService`, `foregroundServiceType="microphone"`, `FOREGROUND_SERVICE_MICROPHONE` + `RECORD_AUDIO`
- 16 kHz mono `AudioRecord` → 20 ms frames → fixed 10 s ring buffer in RAM
- Simple energy-based VAD gate
- Persistent notification with a working Stop action
- **Done when:** runs 30 minutes continuously with flat memory; screen-off survives; no file is ever written. **This is the highest-risk pre-event slice — do it first.**

### S0.4 — ASR integration
- Whisper Tiny via Qualcomm AI Hub export (`qai-hub-models export whisper_tiny_en --target-runtime tflite`)
- Try NPU delegate → GPU → CPU, in that order, logging which one won
- Emit `Flow<TranscriptWindow>` (rolling 60 s, partials allowed)
- **Done when:** speaking into the phone produces visible text within 3 s. Record which delegate initialised.

### S0.5 — Fixture corpus (do this even if you do nothing else)
- Record every script in `fixtures/positive/` and `fixtures/negative/` as WAVs, played from a second device
- These are what you tune against and what makes the demo safe
- **Done when:** ≥6 positive and ≥6 negative WAVs exist and play back cleanly

### S0.6 — Model staging
- Download Gemma 4 E2B `.litertlm` (2.58 GB) to the laptop **now**, on home wifi
- Verify it loads via LiteRT-LM on the device once, then keep the file ready for Office Kit transfer at check-in
- **Done when:** one successful load + one generated token on the device

> **Stop at the end of Phase 0.** Do not build the detection engine or UI before Saturday. That is the actual product, and it should be built during the event.

---

# PHASE 1 — Saturday (clock starts 10:00, hacking 11:00)

### S1.1 — Tier-1 matcher `[11:00–13:00]`
`domain/`: load `data/tactic_lexicon.json`, match against a transcript, emit `Signal`s.
- **Tests first.** Every family gets a positive and a negative case.
- **Done when:** `TacticMatcherTest` passes with ≥15 cases

### S1.2 — Aggregator + risk score `[13:00–14:00]`
Time decay (half-life 120 s) + family-diversity rule (`HIGH_RISK` needs ≥3 distinct families).
- **Done when:** every `fixtures/positive/` transcript reaches ≥40 and every `fixtures/negative/` stays <70, as a JVM test over the text fixtures

### S1.3 — Vertical slice: mic → transcript → score → screen `[14:00–15:30]`
Wire S0.3 + S0.4 + S1.1 + S1.2 into one `StateFlow<ShieldUiState>` and one Compose screen showing state + matched tactics.
- **🎯 THE GATE: end-to-end works by 15:30, before Mentor Round 1.** Tag `demo-safe-h04`.
- If this slips, cut Tier 2 entirely and spend the day polishing Tier 1. That is a winning submission on its own.

### S1.4 — Mentor Round 1 `[15:30–16:30]`
Show the working slice. Not slides.

### S1.5 — Risk UI proper `[16:30–18:30]`
Three states, large type, high contrast, Hindi + English strings, action card with 1930, vibration patterns.
- **Done when:** legible at arm's length; a non-technical person understands the red state without explanation

### S1.6 — Eval Round 1 `[19:00–22:00]` — scored
Demo whatever is stable. Tier 1 alone is a complete story: *"deterministic core today, model adjudication tonight."*

### S1.7 — `DemoMode` and FREEZE IT `[22:00–00:00]`
Replay a fixture WAV through the **identical** pipeline. Works in airplane mode with no mic.
- **Done when:** it runs three times consecutively without variance. Then `touch demo/DEMO_FROZEN` and never touch that directory again.

---

# PHASE 2 — Saturday night → Sunday

### S2.1 — Tier-2 LLM adjudication `[00:00–03:00]`
LiteRT-LM + Gemma 4 E2B, GPU. Prompt + `VerdictSchema` + repair retry + 4 s timeout + `DROP_OLDEST` channel.
- **Write the failure path first.**
- **90-minute spike box.** Not working by 01:30? Ship Tier 1 only and move to S2.3. This is a *feature*, not the product.
- **Done when:** LLM can be killed mid-run and the user notices nothing

### S2.2 — Incident log + report export `[03:00–04:30]`
Metadata only: timestamp, tactic IDs, score, duration. One-page report export → Office Kit file transfer → opens on laptop.

### S2.3 — Hindi/Hinglish coverage `[04:30–06:00]`
Romanised Hinglish spellings in the lexicon so keyword matching survives imperfect ASR. Hindi UI strings.

### S2.4 — Threshold tuning `[06:00–06:30]`
Tune **only** against fixtures. Report the false-positive rate on `fixtures/negative/` after every change.

### S2.5 — FREEZE `[06:30]`
No new features. No refactors. Rehearse the pitch twice out loud on the actual device. Charge everything.

### S2.6 — Eval Round 2 `[09:00]` → Top 10 `[13:30]` → pitch `[13:45]` → awards `[16:15]`

---

## P2 stretch — only if genuinely ahead

- **S3.1** `UpiLinkAnalyzer` + camera QR scan (4 patterns in ARCHITECTURE §6). Pure logic, ~90 min, and it uses the camera — which HackTracker measures.

---

## The rule that matters most

**At every moment you must be ≤2 hours from a working, demoable build.** If that stops being true, stop building and get back to it.
