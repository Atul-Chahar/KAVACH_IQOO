# Redesign screenshots

Captured on the `aegis` emulator (android-35, 1080×2340) from the build at the
commit that introduced them. DemoMode drives these, so they can be reproduced
offline on any machine:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Home → Demo call → digital arrest 01
```

| File | Screen |
|---|---|
| `01-home.png` | Idle. One hero action, four quiet tiles, pill nav. |
| `02-demo-picker.png` | Fixture picker. Amber shield = scam script, cyan = legitimate call. |
| `03-watching.png` | Listening. Cyan paper; states the three-family rule it follows. |
| `04-caution.png` | Caution. Amber paper; families ranked, numbered and timed. |
| `05-high-risk.png` | Caution at 3 of 5 families on the digital-arrest fixture. |

**On `05-high-risk.png`:** the full-bleed press-red HIGH_RISK state is not yet
captured here. Played back in real time, `digital-arrest-01` reaches three
families but decays below the score threshold before a fourth arrives, so the
device run settles at CAUTION. The JVM corpus test feeds the same fixture
without real-time decay and does reach HIGH_RISK (3/3). That gap between the
test harness and a real-time replay is a real finding about the decay constants,
not a rendering problem — see `docs/HANDOFF.md`.
