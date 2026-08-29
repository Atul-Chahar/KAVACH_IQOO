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

**Android has blocked third-party apps from reading call audio since Android 10.** `MediaRecorder.AudioSource.VOICE_CALL` is unavailable without root. `CallScreeningService` provides caller number, direction, and STIR/SHAKEN verification status — and no audio at all.

**So we do not tap the call. We capture ambient room audio.**

| | Why this is the right design, not a workaround |
|---|---|
| Legally cleaner | We are not recording a call. We hold a rolling in-memory buffer of ambient audio on the user's own device. India follows one-party consent. |
| Technically simpler | Needs only `RECORD_AUDIO` + a `microphone` foreground service. No accessibility hacks, no root, no OEM-specific behaviour. |
| Strictly more general | Works identically for cellular, WhatsApp, Telegram, Meet. **Digital-arrest scams run mostly on WhatsApp video calls**, which a call-audio tap would miss entirely. |
| Matches the victim's actual posture | Digital-arrest victims are told to stay on speakerphone/video for hours, phone propped up. Ambient capture is exactly right for that situation. |
| Demoable honestly | A second device plays a scripted scam aloud; the phone hears it through its mic. Nothing stubbed, nothing faked. |

**Do not attempt `VOICE_CALL` capture. It will not work, and trying will cost hours.**

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
