# UI screenshots

Captured from the running debug build on an Android 15 device, 28 Aug 2026.
Not mockups — every frame is the real app, driven end to end.

Shareable gallery: https://claude.ai/code/artifact/a9f0f181-d566-4e82-8900-48da26f7d4c7

| File | State |
|---|---|
| `01-idle.png` | Monitoring off — the default. Nothing captured until an explicit tap. |
| `05-watching.png` | `WATCHING` — quiet green, elapsed time only |
| `06-caution.png` | `CAUTION` (40–69) — names the tactics seen so far |
| `07-high-risk.png` | `HIGH_RISK` (70+) — four families named, action card with 1930 |
| `02-model-setup-absent.png` | Model setup: catalogue entry, size, licence, free space |
| `03-model-rejected.png` | A truncated download refused, with the size actually found |
| `04-fixture-picker.png` | DemoMode script picker (▲ scam, ● legitimate) |
| `08-report.png` | Incident report — metadata only, no audio, no transcript |
| `09-hindi-idle.png` | Hindi locale, at rest |
| `10-hindi-high-risk.png` | Hindi locale, high risk |

Frames 01 → 05 → 06 → 07 are one unbroken replay of `fixtures/positive/digital-arrest-01.txt`,
about fifty seconds end to end, with no reset between them.

## Recapturing

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
```
