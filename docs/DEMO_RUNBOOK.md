# Demo runbook

Two scored evaluation rounds feed the Top 10; the final pitch is **3–5 minutes, live, demoed on the iQOO phone**. Target 4:00 and leave air.

---

## Setup — done before you walk in

- [ ] Phone mirrored to the laptop/projector over **Office Kit screen mirror** so the jury watches the phone UI, not your hands
- [ ] Second device loaded with `fixtures/positive/digital-arrest-01` and `fixtures/negative/real-bank-fraud-desk-01`, volume tested in the actual room
- [ ] `DemoMode` verified working with the mic disabled — your fallback if room noise ruins live capture
- [ ] Airplane mode ON. Say it out loud. It is the most persuasive thing in the room.
- [ ] Both devices at 100%, power bank in reach
- [ ] Incident report already exported to the laptop over Office Kit file transfer

---

## The four minutes

### 0:00–0:20 — The number
> "Karnataka lost ₹109 crore to digital arrest scams across 641 cases. ₹42.41 crore of that was Bengaluru — 480 cases, in this city. Google built AI that catches these calls in real time, then shipped it only to Pixel 9 and later. That's under 1% of Indian phones."

Do not open with the app. Open with the loss.

### 0:20–1:40 — Live detection
Start the second device playing the digital-arrest script. Say nothing for the first fifteen seconds; let the jury watch.

Narrate only the transitions:
> "Authority impersonation — it's picked up the fake CBI claim."
> "Now isolation — 'don't tell anyone, stay on the video call.'"
> "And there's the money request. Three different tactic families. That's high risk."

Point at the screen: **"It's telling her *why*, not just showing a number. She can check that in one second."**

### 1:40–2:20 — The hard negative *(the slide most teams don't have)*
Play the legitimate bank fraud-desk recording. It stays green.

> "This is a real bank fraud desk. It says 'account', 'suspicious transaction', 'urgent' — every keyword a naive detector fires on. Ours doesn't, because high risk requires three *different* tactic families, and a real bank never asks for an OTP or for AnyDesk. A scam detector that cries wolf gets uninstalled in a week."

**This is the moment that wins technical credibility.** Do not cut it for time.

### 2:20–3:10 — Architecture
> "Android has blocked apps from tapping call audio since Android 10. So we don't tap the call — we listen to the room. That needs only the microphone permission, and it works on WhatsApp video calls, which is where digital arrest actually happens."

> "Whisper runs speech recognition on the Hexagon NPU. Gemma 4 runs the reasoning on the GPU through LiteRT-LM. A deterministic rules engine sits underneath both, so it still works if either model fails."

Then the closer on privacy — pull up the manifest if there's a screen:
> "There is no internet permission in this build. Our CI fails the build if anyone adds one. The phone is in airplane mode right now."

Show the incident report on the laptop, moved there over Office Kit.

### 3:10–3:40 — Limits, unprompted
> "It's advisory only — it never hangs up a call, because a false positive that cuts off a real hospital is worse than a missed scam. It's pattern-based, so a novel script gets past it. Hindi and English today. And we say in the app that no alert is not a guarantee of safety — a tool that creates false confidence is worse than no tool."

Saying this before you're asked converts your weakest ground into your strongest.

### 3:40–4:00 — Close
> "51% of victims never file a complaint. This gives them the evidence trail — and it runs on every phone in this room. That was the whole point."

---

## Q&A — rehearse these out loud

| Question | Answer |
|---|---|
| "What if it flags a real call?" | Advisory only, never hangs up. Requires 3 distinct tactic families. Tuned against a corpus of deliberately hard negatives — real bank calls, courier calls, family money conversations. |
| "What if it misses a scam?" | It will. We say so in-app. We reduce exposure, we don't eliminate it — which is exactly why we refuse to auto-block anything. |
| "How is this different from Truecaller?" | Truecaller scores the *number*. We score the *conversation*. A fresh SIM or a WhatsApp call has no reputation — but the script is the same every time, and the script is what we detect. |
| "Won't Google ship this everywhere?" | Maybe. It's been Pixel-only and English-only since launch and still is. We build for the phones people actually hold. |
| "Is recording calls legal?" | We're not recording, and we're not touching the call stream — Android blocks that anyway. Rolling in-memory buffer of ambient audio, explicit opt-in, user's own device. India follows one-party consent. |
| "Prove nothing leaves the phone." | Airplane mode is on. There's no INTERNET permission in the manifest. CI fails the build if anyone adds one. |
| "How accurate is it?" | On our fixture corpus: [FILL IN real numbers before Eval Round 2]. That's a scripted corpus from published advisories, not field data — a real launch needs a labelled dataset of actual scam calls, which 30 hours can't produce. |

**Fill in that last row with real measured numbers.** "We don't know" is a bad answer; an honest number with an honest caveat is a great one.

---

## If something breaks

| Failure | Move |
|---|---|
| Mic won't capture in the room | Switch to `DemoMode`. Say "switching to our recorded path" — calm, unbothered. |
| Model won't load | Tier 1 still runs. "The deterministic engine is what you're seeing — the model layer adds nuance on top." Nobody can tell. |
| Phone won't mirror | Demo on the handset, hold it up. You rehearsed for this. |
| Total device failure | Talk through the architecture. You have the numbers, the constraint insight, and the safety argument — all of that lands without a screen. |

Never say "it was working earlier." Move to the fallback without commentary and keep going.
