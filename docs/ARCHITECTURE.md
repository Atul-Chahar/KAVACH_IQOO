# Kavach — Architecture

---

## 1. Layers

```
┌─────────────────────────────────────────────────────────────┐
│ UI      Compose · ShieldScreen, IncidentList, QrGuard       │
└──────────────▲──────────────────────────────────────────────┘
               │ StateFlow<ShieldUiState>
┌──────────────┴──────────────────────────────────────────────┐
│ DOMAIN  pure Kotlin, zero android.* imports, 100% tested    │
│         TacticMatcher · SignalAggregator · RiskEngine       │
│         VerdictSchema · UpiLinkAnalyzer · IncidentRecorder  │
└──────────────▲──────────────────────────────────────────────┘
               │ Signal / Verdict (sealed types)
┌──────────────┴──────────────────────────────────────────────┐
│ INFERENCE  AsrEngine (NPU) · LlmAdjudicator (GPU)           │
└──────────────▲──────────────────────────────────────────────┘
               │ PCM 16 kHz mono
┌──────────────┴──────────────────────────────────────────────┐
│ CAPTURE  AudioCaptureService (FGS type=microphone)          │
│          ring buffer in RAM · VAD gate · never persisted    │
└─────────────────────────────────────────────────────────────┘
```

**Invariant:** nothing above CAPTURE writes audio to disk; nothing anywhere opens a socket.

---

## 2. The constraint that shapes everything

**Android has blocked third-party apps from reading call audio since Android 10.** `MediaRecorder.AudioSource.VOICE_CALL` is unavailable without `CAPTURE_AUDIO_OUTPUT`, which is `signature|privileged` and cannot be granted by `pm grant`. `CallScreeningService` provides caller number, direction, and STIR/SHAKEN verification status — and no audio at all.

**So we do not tap the call. We capture ambient room audio.**

### 2.1 Ambient capture is silenced too — and this document used to be wrong about that

An earlier version of this section claimed ambient capture "works identically for cellular, WhatsApp, Telegram, Meet". It does not, and the reason matters more than the correction.

Android's audio policy does not key on the audio *source*. It keys on the audio *mode*. From [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input):

> A voice call is active if the audio mode returned by `AudioManager.getMode()` is `MODE_IN_CALL` or `MODE_IN_COMMUNICATION`. […] The call always receives audio. The app can capture audio if it is an accessibility service. The app can capture the voice call if it is a privileged (pre-installed) app with permission `CAPTURE_AUDIO_OUTPUT`.

A cellular call sets `MODE_IN_CALL`. WhatsApp, Meet and Telegram set `MODE_IN_COMMUNICATION`. Speakerphone changes nothing. In all of those, an ordinary app's `AudioRecord` is **silenced — not failed**: `read()` returns frames of zeroes, no exception is thrown, and nothing appears in logcat. An app that does not measure its own input cannot tell this apart from a quiet room.

### 2.2 The one exemption a third party can reach

From the same page:

> **Accessibility service + ordinary app.** If the service's UI is on top, both the service and the app receive audio input.

Three conditions, all of which Kavach controls, and one that is easy to miss:

1. **An enabled `AccessibilityService` in our package.** `KavachAccessibilityService` reads nothing — `onAccessibilityEvent` is empty and `canRetrieveWindowContent` is false. It exists to place our UID on the list the audio policy consults.
2. **Audio source `VOICE_RECOGNITION` or `HOTWORD`.** `MIC` is not on the list, so `MicCapture` refuses to fall back to it while the mode says a call is active — falling back would open successfully and then return silence.
3. **Our UI on top.** A foreground service is not enough; the process must be at `PROCESS_STATE_TOP`. `ShieldOverlayActivity` therefore stays on screen for the whole call, translucent and untouchable, rather than flashing and finishing.
4. **We must own the recording.** The exemption is matched against the UID that opened the `AudioRecord`. `SpeechRecognizer` opens the microphone in the recogniser's own process, so handing it the job — the obvious implementation, and the one Kavach shipped first — is silenced in-call no matter what the other three conditions say. `PipedAsrTranscriptSource` opens the microphone here and feeds the recogniser through a `ParcelFileDescriptor` pipe via `RecognizerIntent.EXTRA_AUDIO_SOURCE`.

### 2.3 Starting without the user opening the app

`SYSTEM_ALERT_WINDOW` is a documented exemption from the background-activity-launch restriction. `KavachAccessibilityService` watches `AudioManager`'s mode — one integer, no notifications read, no numbers read, and it covers VoIP and cellular alike — and raises `ShieldOverlayActivity` when a call begins. Once that activity is visible Kavach is a foreground app, so the Android 14+ while-in-use restriction on background-started microphone foreground services does not apply: there is no exemption to claim, because the start is not a background start.

The two constraints solve each other. The window that has to be on screen for the exemption is the same window the warning is drawn in.

### 2.4 What we hear, precisely

We capture the microphone, not the call. Say this out loud rather than let it be discovered.

| Situation | What Kavach hears |
|---|---|
| Earpiece call | The victim's half only |
| **Speakerphone / video call** | **Both halves, through room acoustics.** The digital-arrest posture, and our demo default |
| Wired or Bluetooth headset | Almost nothing. Detected and reported, never hidden |

`CaptureDiagnostics` measures `isClientSilenced` and per-frame RMS continuously, and `ShieldOverlay` renders "Kavach can't hear this call" instead of a calm all-clear whenever the stream is provably silent. A false reassurance is worse than no app.

### 2.5 Why ambient capture is still the right design

| | |
|---|---|
| Legally cleaner | We are not recording a call. We hold a rolling in-memory buffer of ambient audio on the user's own device, and it never touches disk. |
| Strictly more general | Covers WhatsApp and Meet, where digital-arrest scams actually run, as well as in-person pressure and a call on someone else's phone. |
| Matches the victim's posture | Digital-arrest victims are told to stay on speakerphone or video for hours with the phone propped up. |
| Demoable honestly | A second device plays a scripted scam aloud; the phone hears it through its microphone. Nothing stubbed. |

**Do not attempt `VOICE_CALL` capture.** Nor `MediaProjection`/`AudioPlaybackCapture`, which cannot capture `USAGE_VOICE_COMMUNICATION`, nor `CONCURRENT_AUDIO_RECORD_BYPASS`, which is privileged. Becoming the default dialer does not grant call-audio capture either; it grants the in-call screen. Google's Scam Detection is privileged, not merely default.

**Play policy note:** Google prohibits using the Accessibility API for call recording. That is store policy, not a platform block — this build is sideloaded, and the shipping path is the dialer role. Say so from the stage rather than get caught.

---

## 3. Detection engine — three tiers

The tiering exists so that **Tier 1 alone can carry the entire demo** if every model fails.

### Tier 1 — deterministic tactic matcher (the backbone)

Pure Kotlin, no ML, fully unit-tested. Matches the rolling transcript against `data/tactic_lexicon.json` across five tactic families:

| Family | What it detects |
|---|---|
| `AUTHORITY_IMPERSONATION` | Caller claims to be police, CBI, ED, customs, TRAI, a bank's fraud desk |
| `ISOLATION_AND_SECRECY` | "Don't tell anyone", "stay on this video call", "the case is confidential" |
| `URGENCY_AND_THREAT` | Arrest warrants, account freezes, deadlines in hours |
| `CREDENTIAL_EXTRACTION` | Requests for OTP, PIN, CVV, passwords |
| `REMOTE_ACCESS_AND_TRANSFER` | AnyDesk/TeamViewer, screen sharing, "verification deposit", account transfers |

Each hit emits `Signal(family, weight, evidenceSpan, timestampMs)`.

**`SignalAggregator` scoring — two mechanisms that matter more than the weights:**

1. **Time decay.** A marker from 8 minutes ago counts less than one from 8 seconds ago. Half-life ≈ 120 s.
2. **Family-diversity bonus.** Three *different* families firing is far more diagnostic than one family firing three times. This is the single most important false-positive defence: a real bank fraud desk legitimately triggers `AUTHORITY_IMPERSONATION` and mild `URGENCY`, but will never ask for an OTP or for AnyDesk.

Requirement: **`HIGH_RISK` (≥70) requires at least 3 distinct families.** A single family, however loud, caps at `CAUTION`.

### Tier 2 — on-device LLM adjudication (the differentiator)

Every ~8 s, pass the rolling 60 s transcript + accumulated signals to Gemma 4 E2B and demand strict JSON:

```json
{
  "risk": 73,
  "tactics": ["AUTHORITY_IMPERSONATION", "CREDENTIAL_EXTRACTION"],
  "one_line_reason": "Caller claims to be police and is asking for an OTP.",
  "recommended_action": "HANG_UP"
}
```

Handling rules, all mandatory:

- Parse via `VerdictSchema.parseOrNull()`. **Raw model text never reaches the UI.**
- One repair retry on malformed JSON, then give up silently.
- Bounded channel, `DROP_OLDEST` — if inference is slower than speech, skip windows rather than lag.
- Hard timeout (4 s). On timeout or parse failure, the UI keeps showing the Tier-1 score and the user notices nothing.
- Final displayed score = `max(tier1, tier2)` when Tier 2 is valid; `tier1` otherwise.

### Tier 3 — advisory escalation

See `docs/PRD.md` §5. Never auto-acts. Always names the matched tactics.

---

## 4. Models and runtimes

| Component | Choice | Backend | Licence |
|---|---|---|---|
| LLM runtime | **LiteRT-LM** — Google's production on-device framework. MediaPipe LLM Inference API is **deprecated**; do not use it | — | Apache 2.0 |
| LLM | **Gemma 4 E2B** (2.58 GB, ~710 MB–3.5 GB peak, 128K ctx) | GPU | Apache 2.0 |
| ASR primary | **Whisper Tiny/Base** exported via Qualcomm AI Hub Models | **NPU (Hexagon/HTP)**, INT8 | BSD-3-Clause |
| ASR Indic upgrade | **AI4Bharat IndicConformer-600M**, 22 languages, hybrid CTC+RNNT, ONNX export, Hindi WER 13.2 | NPU/GPU | MIT |
| Reference code | **Google AI Edge Gallery** — study its Audio Scribe flow; do not fork wholesale | — | Apache 2.0 |

**Division of silicon (say this to the jury):** *speech on the Hexagon NPU, reasoning on the Adreno GPU, nothing on a server.*

**Two facts to verify on the device in hour one — do not design around either until proven:**

1. Whether Gemma 4 E2B's native audio input (model card says audio supported on E2B/E4B, ≤30 s) is exposed through LiteRT-LM on Android. If yes, it could collapse ASR+LLM into one model. If no, use the two-model path above.
2. Actual NPU delegate availability on the loaner's OriginOS 6 build.

**Model delivery:** the weights cannot ship in an APK. Two routes, both supported:

1. **In-app staging (default, built 28 Aug).** `ModelSetupScreen` shows the catalogue entry, opens the official URL in the **browser** via `ACTION_VIEW`, and imports the finished file through the Storage Access Framework picker. **The app never fetches it** — it has no `INTERNET` permission and must never acquire one, so the download belongs to the browser and the handover belongs to the user. The picker also avoids any storage permission: access is granted to exactly one file, once.
2. **Office Kit file transfer** at 08:00 check-in, then the same import step. Budget 20 minutes.

The import writes to a `.part` file, verifies the **exact** byte count, and only then renames — a truncated download on venue wifi is the likeliest real failure and must be caught here, not as a crash inside the runtime. Have Whisper-Tiny-only as the fallback if the model is not on the device by 11:00.

**Catalogue** (read from the Hugging Face API on 28 Aug 2026, not from memory — a wrong URL is a silent, unrecoverable failure on the day). Repo `litert-community` is Google's own LiteRT distribution org and is **ungated**: no account, no licence click-through, no token.

| Variant | File | Size |
|---|---|---|
| **Gemma 4 E4B GPU** (shipped in the catalogue) | `gemma-4-E4B-it-gpu.litertlm` | 2.77 GB |
| Gemma 4 E4B general | `gemma-4-E4B-it.litertlm` | 3.41 GB |
| Gemma 4 E2B GPU | `gemma-4-E2B-it-gpu.litertlm` | 1.87 GB |
| Gemma 4 E2B **Qualcomm sm8750 (NPU)** | `gemma-4-E2B-it_qualcomm_sm8750.litertlm` | 2.81 GB |

The sm8750 build is prebuilt for Snapdragon 8 Elite. **Check the loaner's chipset at check-in** — if it matches, that variant is the only one that genuinely puts reasoning on the NPU, which is worth a sentence in the pitch. Adding it is a one-line change to `ModelCatalog`.

---

## 5. Data flow

```
mic → AudioRecord(16 kHz mono) → 20 ms frames → VAD gate
    → 10 s ring buffer (RAM only, overwritten, never persisted)
    → ASR (NPU) → partial transcript → rolling 60 s window
        ├─► Tier 1 matcher ──── Signals ────┐
        └─► every 8 s ─► LLM (GPU) ─────────┤
                                            ▼
                                SignalAggregator → RiskScore
                                            ▼
                                  StateFlow<ShieldUiState>
                                            ▼
                        (on HIGH_RISK) IncidentRecorder writes:
                        timestamp · tactic IDs · score · duration
                        NOT audio. NOT transcript (opt-in only).
```

---

## 6. `UpiLinkAnalyzer` — deterministic, no ML

Parse a scanned QR or pasted link. Flag four patterns:

1. **Collect-request disguised as a payment** — a `upi://` intent that debits rather than credits. The classic "I'll send you the money" reversal.
2. **Payee VPA vs. displayed-name mismatch** — the handle and the shown merchant name disagree.
3. **URL shorteners and redirect chains** in a payment context.
4. **Punycode / lookalike domains** — homoglyph substitution in a bank or wallet domain.

Pure string/URI logic. Trivially unit-testable. Directly addresses the 20% QR / 40% link figures.

---

## 7. Concurrency

- One service-scoped `CoroutineScope`, cancelled with the service.
- Stages connected by `Channel(capacity = N, onBufferOverflow = DROP_OLDEST)`.
- Mic read loop on `Dispatchers.IO` at high priority; **never** blocked by inference.
- Inference on a single-threaded dispatcher — model sessions are not thread-safe.
- UI observes one `StateFlow`. No shared mutable state across threads anywhere.

---

## 8. Failure behaviour — required, not optional

| Failure | Behaviour |
|---|---|
| Model file missing | App opens, Tier 1 works, banner: "Advanced analysis unavailable" |
| NPU delegate unavailable | Fall back to GPU, then CPU. Log it. User sees nothing. |
| ASR produces garbage | Tier 1 still matches romanised spellings. Score simply stays low. |
| LLM times out / bad JSON | Silent. Tier-1 score shown. |
| Mic permission revoked mid-session | Stop service, clear state, plain-language explanation |
| Mic unavailable (in-call restriction) | Show the honest message and offer `DemoMode`. Do not crash, do not pretend to listen. |
