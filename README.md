# Kavach

On-device scam-call detection for Android. Runs entirely offline, in Hindi and English.

**iQOO Hackathon 2026 · Bengaluru City Battle, 29–30 Aug · Team Kavach (Anant Sharma, Atul Chahar)**

> Google shipped on-device AI that flags scam calls in real time — then locked it to Pixel 9+, under 1% of India's Android market. Kavach brings it to the other 99%.

## Status — 28 Aug 2026

Working prototype, verified end to end on a device.

| | |
|---|---|
| Tier-1 detection engine | matcher · time decay · family diversity · negative guards — 72 JVM tests |
| Risk UI | three states, Hindi + English, action card, vibration |
| `DemoMode` | fixture replay through the identical pipeline, **verified in airplane mode** |
| Incident log | metadata-only report, shareable to a laptop |
| `UpiLinkAnalyzer` | four patterns, unit-tested (camera QR capture not yet wired) |
| Live ASR | Android on-device recogniser; degrades honestly where unavailable |
| Tier-2 LLM | seam in place (`LlmAdjudicator`), model not yet on device |
| Model staging | in-app catalogue → browser download → file-picker import, size-verified |

**Screenshots:** `docs/screenshots/` — ten states captured from the running app.

**Measured on the fixture corpus:** 3/3 scam scripts reach HIGH_RISK · 0/4 legitimate calls reach HIGH_RISK · 0/4 even reach CAUTION.
Re-measure with `./gradlew :domain:test --tests '*FixtureCorpusTest*' -i`; the rates print on every run.

## Read in this order

| File | What it is |
|---|---|
| `CLAUDE.md` | **Agent operating rules. Read first.** Hard constraints, stack, definition of done. |
| `docs/PRD.md` | What we're building and what is deliberately out of scope |
| `docs/ARCHITECTURE.md` | Layers, the Android call-audio constraint, the three-tier engine, models |
| `docs/SAFETY.md` | Safety and privacy requirements, as testable rules |
| `docs/TASKS.md` | Ordered build slices with acceptance criteria |
| `docs/MOBILE_FIRST_SETUP.md` | **How to develop phone-first.** Set this up before the event. |
| `docs/DEMO_RUNBOOK.md` | The 4-minute pitch, minute by minute |
| `data/tactic_lexicon.json` | The detection patterns — the core IP |
| `fixtures/` | Test transcripts. Negatives matter more than positives. |

## Non-negotiables

1. No `android.permission.INTERNET`. CI enforces it.
2. Audio never touches disk.
3. Advisory only — never auto-rejects a call.
4. `domain/` has zero `android.*` imports.
5. All model output is schema-validated before use.

## Quick start

Toolchain: **JDK 21** and an **Android SDK** (compileSdk 35). `mise.toml` pins the
JDK — run `mise install` and `eval "$(mise activate bash)"`, or use your own JDK 21.
Point `local.properties` at your SDK (`sdk.dir=$HOME/Android/Sdk`); it is gitignored.

```bash
./gradlew :domain:test              # JVM tests for domain/ (JUnit5)
./gradlew assembleDebug             # build the APK
./gradlew ktlintCheck detekt        # style + static analysis
./gradlew ktlintFormat              # auto-fix formatting
./gradlew check                     # everything, including both invariants below
```

Install on the device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# or, during Red Light: serve it and download on the phone
cd app/build/outputs/apk/debug && python3 -m http.server 8000
```

## Invariants enforced by the build

| Task | Enforces |
|---|---|
| `:app:assertNoInternetPermission` | No `android.permission.INTERNET` in the merged manifest. The manifest also carries `tools:node="remove"` so a dependency cannot add it transitively. **Both paths verified red-able, 27 Aug 2026.** |
| `:domain:assertDomainIsPureKotlin` | Zero `android.*` imports in `domain/`. |

Both are wired into `check` and into CI.

## Module map

```
:domain   pure Kotlin JVM. No Android SDK on its classpath — the build enforces it.
:app      Android app. Compose UI, capture/, inference/. minSdk 30, targetSdk 35.
:demo     fixture replay path. FROZEN once demo/DEMO_FROZEN exists.
```
