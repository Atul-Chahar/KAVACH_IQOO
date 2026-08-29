# Kavach — safety and privacy requirements

**Read this before writing anything in `capture/`, `inference/`, or the alert UI.**

These are not aspirations. Each one maps to a test or a build-time check.

---

## 1. Advisory only — never autonomous

The app **must not**:

- end, reject, or silence a call
- block a number without an explicit user tap
- initiate, cancel, or modify any payment
- send anything anywhere

**Why:** the failure modes are asymmetric. A false positive that hangs up on a real hospital, a real bank, or a real family emergency is a far worse outcome than a missed scam. Advisory design bounds our worst case to "two seconds of the user's attention."

**This is also the complete answer to a judge asking "what if you're wrong?"** — the human is always the decision-maker.

`Test:` grep the codebase for `endCall`, `rejectCall`, `disallowCall`, `setSilenceCall`. There should be zero hits outside comments.

---

## 2. "No alert is not a guarantee of safety"

An anti-scam tool that creates false confidence is **more dangerous than no tool at all**. ASR degrades on unfamiliar accents and dialects, so false negatives are certain.

This sentence must appear:
- in the onboarding screen, before consent
- in the persistent foreground notification
- in the pitch, said out loud, unprompted

`Test:` string resource `safety_no_guarantee` exists and is referenced in both the onboarding composable and the notification builder.

---

## 3. No fabricated authority

Copy says **"this matches known scam patterns."** Never "this is a scam", never "you are being defrauded."

Kavach is not making a legal or financial determination, and phrasing it as one is both dishonest and unsafe.

`Test:` review all strings in `strings.xml` / `strings-hi.xml` before the freeze.

---

## 4. Explainability is mandatory

Every `CAUTION` and `HIGH_RISK` state must name **which tactic families matched**, in plain language.

A bare score is unfalsifiable and untrustworthy. "This caller claims to be police and is asking for your OTP" can be checked by the user in one second — that verifiability *is* the trust mechanism.

`Test:` `ShieldUiState.HighRisk` cannot be constructed without a non-empty `matchedTactics` list. Make it a constructor requirement, not a convention.

---

## 5. Vulnerable-user framing

The person seeing this alert is often elderly and **already frightened by the scammer**. The UI must not add to that.

- Calm, instructional copy. No panic aesthetics, no sirens, no countdowns.
- Large type, high contrast, minimum 18sp body text.
- Never blame or shame the user.
- Hindi-first when the device locale is Hindi.

---

## 6. Data minimisation — enforced, not promised

| Guarantee | Enforcement |
|---|---|
| Audio never leaves the device | No `INTERNET` permission. CI task `assertNoInternetPermission` fails the build. |
| Audio never hits disk | Ring buffer in RAM only. No `File`/`OutputStream` in `capture/` or `inference/`. |
| Transcript is ephemeral | Rolling 60 s window, discarded on session end. |
| Incident log is metadata | Timestamp, tactic IDs, score, duration. Never audio. Transcript retention is per-incident and opt-in. |
| No telemetry | No analytics, no crash reporting, no third-party SDK of any kind. |

`Test:` CI check + a code review pass on `capture/` before the freeze.

---

## 7. Consent

- Monitoring is **off by default**.
- Onboarding states plainly: what is captured (ambient audio), where it goes (nowhere), what is kept (metadata only), and how to stop.
- Persistent notification while listening. Android mandates this for a `microphone` foreground service — treat it as a feature, not a nuisance.
- One-tap stop, always reachable.
- Default to monitoring **unknown callers only**; contacts excluded.

---

## 8. Legal footing — have this answer ready

> "We are not recording calls, and we are not touching the call stream — Android blocks that anyway. We hold a rolling in-memory buffer of ambient audio that is never written to disk, with explicit opt-in, on the user's own device. India follows one-party consent."

Do not improvise this on stage. Know it.

---

## 9. Known limitations — state these before you are asked

1. Detection is pattern-based. A novel scam script will not be caught.
2. ASR accuracy varies by accent and dialect; false negatives are certain.
3. Hindi and English only.
4. A 30-hour build has no production-grade labelled dataset. Our fixtures are scripted from published I4C, bank, and security-vendor advisories — realistic, but not field data.
5. Ambient capture requires speakerphone or video mode; a call held to the ear is not covered.

**Saying these first is a strength.** A team that knows its own limits reads as competent; a team that oversells gets dismantled in Q&A.
