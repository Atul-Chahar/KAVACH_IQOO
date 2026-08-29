# CLAUDE.md — Kavach operating rules

You are building **Kavach**, an on-device scam-call detection app for Android, for the iQOO Hackathon 2026 (Bengaluru, 29–30 Aug).

Read `docs/PRD.md` and `docs/ARCHITECTURE.md` before your first edit. Read `docs/SAFETY.md` before touching anything in `capture/` or the alert UI.

---

## Prime directive

**A simple thing that indisputably works beats a complex thing that half works.** This is a judged 30-hour build. Every decision defers to "will this still run at hour 29?"

---

## Hard rules — never violate these

1. **Never add `android.permission.INTERNET` to the app manifest.** The CI job `assertNoInternetPermission` fails the build if it appears. This is our headline privacy claim; it must be verifiable, not asserted. If you think you need network access, stop and ask.
2. **`domain/` must have ZERO `android.*` imports.** It is pure Kotlin, JVM-testable. If you need Android APIs, the code belongs in `app/`, not `domain/`.
3. **Audio never touches disk.** `AudioRecord` → fixed in-memory ring buffer → discarded. No `File`, no `OutputStream`, no cache dir, ever, in the capture or inference path.
4. **All model output is schema-validated before use.** LLM responses parse through `VerdictSchema.parseOrNull()`. Raw model text must never reach the UI. On parse failure, fall back silently to the Tier-1 rules score.
5. **The app is advisory only.** It never calls `endCall()`, never auto-rejects, never blocks a number without explicit user action, never initiates a payment. If a task seems to ask for autonomous action, stop and ask.
6. **Never modify `demo/` after it is frozen.** A `DEMO_FROZEN` marker file will appear in that directory. Once it exists, treat the whole directory as read-only.
7. **Bounded channels only**, with `BufferOverflow.DROP_OLDEST`. No unbounded queues anywhere in the audio pipeline — a slow consumer must degrade quality, never leak memory or block the mic thread.

---

## Working style

- **One vertical slice at a time.** Do not start slice N+1 until slice N runs end to end on a device and its tests pass. `docs/TASKS.md` has the order. Follow it.
- **If a change spans more than 3 files, stop and propose a plan first.** Wait for approval.
- **Write the fallback before the risky path.** Any integration that can fail on unfamiliar hardware (NPU delegate, model loading, mic capture during a call) gets its `catch` branch and its degraded-mode behaviour written *first*.
- **Every new `domain/` function needs a JVM unit test in the same commit.** No exceptions. These tests are what let us trust the codebase at 3am.
- **Prefer deleting to adding.** If a feature is not in `docs/PRD.md` §3 (MVP scope), do not build it.
- **Never refactor unprompted.** Not during a hackathon.

---

## Stack — do not substitute without asking

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 |
| Min / target SDK | `minSdk 30`, `targetSdk 35` |
| Architecture | Single activity, unidirectional data flow, one `StateFlow<ShieldUiState>` |
| LLM runtime | LiteRT-LM (Apache 2.0) — **not** MediaPipe LLM Inference, which is deprecated |
| LLM | Gemma 4 E2B (`.litertlm`), GPU backend |
| ASR | Whisper Tiny/Base via Qualcomm AI Hub export, QNN/NPU backend; AI4Bharat IndicConformer as the Indic upgrade path |
| DI | Manual constructor injection. **No Hilt/Dagger** — the annotation processor costs more build time than it saves at this scale |
| Async | Coroutines + Flow, service-scoped `CoroutineScope` |
| Tests | JUnit5 + kotlin.test on the JVM for `domain/`. No instrumented tests unless asked |

---

## Layout

```
domain/       pure Kotlin. RiskEngine, TacticMatcher, SignalAggregator,
              VerdictSchema, UpiLinkAnalyzer. 100% JVM-testable.
app/
  capture/    AudioCaptureService (FGS type=microphone), ring buffer, VAD
  inference/  AsrEngine, LlmAdjudicator, model loading
  ui/         Compose screens, ShieldViewModel
demo/         scripted fixture replay path. FROZEN after hour 24.
data/         tactic_lexicon.json — the detection patterns
fixtures/     test transcripts (positive = scams, negative = legitimate)
```

---

## Definition of done, per slice

A slice is done when **all** of these are true:

- [ ] It runs end to end on the physical iQOO device (never trust an emulator — LiteRT/MediaPipe do not reliably support them)
- [ ] `./gradlew test` passes
- [ ] `./gradlew ktlintCheck detekt` passes
- [ ] CI is green, including `assertNoInternetPermission`
- [ ] It degrades safely: if the model/mic/NPU is unavailable, the app still opens and still shows *something* honest
- [ ] Tagged: `git tag demo-safe-h<NN>`

---

## Things that will waste our time — do not do them

- Do not add Hilt, Room, Retrofit, or any networking library.
- Do not write instrumented (`androidTest`) tests. Manual device testing plus JVM unit tests is the right trade at this timescale.
- Do not build a settings screen, onboarding carousel, or account system.
- Do not add analytics, crash reporting, or telemetry of any kind. (Also: see hard rule 1.)
- Do not attempt to tap call audio via `MediaRecorder.AudioSource.VOICE_CALL`. Android blocks it. We capture ambient audio — see `docs/ARCHITECTURE.md` §2.
- Do not tune detection thresholds by intuition. Tune them against `fixtures/`, and report the false-positive rate on `fixtures/negative/` every time you change one.

---

## When you are stuck

Say so plainly and propose the smallest fallback that keeps the demo alive. "The NPU delegate is not initialising; I propose falling back to the GPU delegate and logging it, which costs ~200ms per window and keeps everything else intact" is a good message. Silently working around a problem for an hour is not.
