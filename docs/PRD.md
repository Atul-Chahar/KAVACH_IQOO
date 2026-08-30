# Kavach — Product Requirements

**Version 2 · 27 Aug 2026 · Shortlisted for Bengaluru City Battle, 29–30 Aug**

---

## 1. What this is

An Android app that warns a person, in real time and entirely on-device, when the conversation they are in matches a known scam script — and that checks a UPI QR code or payment link before they pay.

## 2. Why it exists

| Fact | Source |
|---|---|
| ₹22,495 crore lost to cybercrime in India in 2025, up 24% YoY | ThePrint, citing national figures |
| ₹109 crore lost to "digital arrest" scams in Karnataka across 641 cases; ₹42.41 crore from 480 cases in Bengaluru | Deccan Herald, Dec 2024, citing the state Home Minister |
| 1 in 5 Indian families with a UPI user defrauded in 3 years; 51% never filed a complaint | LocalCircles, 32,000+ respondents, 365 districts |
| Of UPI victims: 40% clicked a malicious link, 20% scanned a malicious QR, 20% shared an OTP with a fake "bank official" | same survey |
| Google's on-device scam detection is Pixel 9+ only and English-only; Pixel is <1% of India's Android market | TechCrunch / Gulf News, Nov 2025 |

**The gap:** the capability is proven and shipping — just not to the phones Indians actually own, or in the languages they actually mix.

## 3. MVP scope — what ships in 30 hours

**In scope. Build only these.**

| # | Capability | Acceptance |
|---|---|---|
| P0 | Ambient audio capture with explicit opt-in, foreground service, persistent notification | Survives 30 min continuous, no memory growth |
| P0 | On-device Hindi/English speech-to-text over a rolling window | Partial transcript visible within 3 s of speech |
| P0 | Tier-1 deterministic tactic matcher over 5 tactic families | 100% of `fixtures/positive/` reach ≥40; 0% of `fixtures/negative/` reach ≥70 |
| P0 | Risk UI: three states, naming the matched tactics in plain language | Legible at arm's length, works in Hindi and English |
| P0 | `DemoMode` — scripted fixture replay through the identical pipeline | Runs with airplane mode on and no mic |
| P1 | Tier-2 on-device LLM adjudication returning schema-validated JSON | Never blocks UI; failure is invisible to the user |
| P1 | Incident log — metadata only, exportable as a one-page report | Report opens on a laptop after Office Kit transfer |
| P2 | UPI QR / payment-link safety check via camera | Flags the 4 patterns in `docs/ARCHITECTURE.md` §6 |
| P1 | Message Guard — scores incoming message notifications with the Tier-1 lexicon and warns on the lock screen | 0% of `fixtures/negative/` reach HIGH_RISK on the message path (`SmsCorpusTest`); a warning can be reported or dismissed without opening the app |

**Explicitly out of scope. Do not build these; say so before a judge asks.**

- Auto-blocking, auto-rejecting, or hanging up calls
- Alerting a *different person's* device (needs a push backend — real product, not this weekend)
- Languages beyond Hindi and English
- Accounts, cloud sync, settings screens, onboarding carousels
- Any claim of production-grade detection accuracy
- Reading, sending, or storing SMS. Message Guard reads *notifications*, not the
  SMS database, and holds no `READ_SMS` permission.

## 4. Users

**Primary:** an adult in an Indian city who is partly responsible for an older relative's phone and money — installs it, sets it up, and is often the one who explains what happened afterwards.
**Secondary:** the older relative themselves, who is the one actually on the call.

Design consequence: the alert must be legible and calm for someone who is **frightened and being actively manipulated**. Large type, high contrast, plain language, no jargon, no alarm-red panic aesthetic.

## 5. The three states

| State | Trigger | UI |
|---|---|---|
| `WATCHING` | monitoring, score < 40 | Quiet green. Elapsed time. Nothing else. |
| `CAUTION` | score 40–69 | Amber. Names the tactics seen so far. Gentle single vibration. |
| `HIGH_RISK` | score ≥ 70 | Red. Names matched tactics. Action card: hang up · never share an OTP · call 1930. Double vibration. |

## 6. Non-negotiable product principles

1. **Advisory, never autonomous.** A false positive that hangs up on a real hospital is a worse failure than a missed scam.
2. **Every alert is explainable.** "87% risk" is unfalsifiable. "This caller claims to be police and is asking for your OTP" is checkable in one second.
3. **No alert is not a guarantee of safety.** Stated in onboarding, in the notification, and in the pitch. An anti-scam tool that creates false confidence is worse than no tool.
4. **We say "matches known scam patterns", never "this is a scam."** The app is not making a legal or financial determination.
5. **Nothing leaves the device.** Enforced by the absence of the `INTERNET` permission and by CI.

## 7. What changed in v2

We are shortlisted and prototyping before the event is permitted (organiser-confirmed, disclosed in the submission). That inverts the build plan:

- **Before the event:** build the *risky infrastructure* — audio capture, ASR integration, model loading, CI, fixtures. These are the things that fail unpredictably on unfamiliar hardware and would otherwise eat Saturday.
- **During the 30 hours:** build the *product* — the detection engine, the UI, the incident log, the QR guard, threshold tuning against fixtures, and the demo.

Disclose exactly this split in the submission and say it out loud at Eval Round 1. A team that arrives prepared and says so reads as professional; a team that arrives prepared and hides it reads as something else.
