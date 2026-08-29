# Test fixtures

Scripted transcripts used to tune and regression-test the detection engine. **These are test data for a defensive tool** — every marker in them is drawn from publicly published scam advisories (I4C, PhonePe Trust & Safety, Seqrite, RBI remote-access-fraud warnings) so that Kavach can be tuned to recognise them.

## Structure

- `positive/` — conversations that **should** trigger `HIGH_RISK` (≥70)
- `negative/` — legitimate conversations that **must not** exceed `CAUTION` (<70)

The negatives matter more than the positives. Anyone can build something that fires on the word "OTP." The engineering problem is not firing on a real bank's fraud desk, and that is what a judge will probe.

## Acceptance targets

| Set | Requirement |
|---|---|
| `positive/` | 100% reach ≥40 (CAUTION); ≥80% reach ≥70 (HIGH_RISK) |
| `negative/` | 0% reach ≥70; ≤30% reach ≥40 |

Report both numbers after **every** threshold change. Never tune by intuition.

## Recording them as audio

1. Open the `.txt` on a second phone or laptop.
2. Read it aloud at natural pace, or use any TTS.
3. Record on the iQOO device through the app's capture path, or save as 16 kHz mono WAV alongside the `.txt`.
4. Aim for two voices where the script has two speakers — a single flat voice is an unrealistically easy case.

Vary at least one recording each: background noise, a fast speaker, and heavy code-switching. Those are the conditions the demo room will actually have.

## Adding more

Name as `<family-emphasis>-<variant>.txt`. Keep each to 60–120 seconds spoken. Add the expected band as a comment on line 1.
