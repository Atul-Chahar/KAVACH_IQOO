# Building phone-first — the dev environment

**This is the question that decides whether Saturday goes well.** Set it up on Friday, not on the floor.

---

## 1. What the rules actually say

From the event site, verbatim:

> **RED LIGHT, GREEN LIGHT.** 55% of pure build time is Red Light: iQOO phone only, every route through Office Kit, laptops restricted. The other 45% is Green Light: both devices... **~10.5H RED · ~8.5H GREEN ACROSS 19H OF PURE BUILD TIME**

> During Red Light the laptop is closed as a build machine, so Office Kit is the only route between the two.

And the Office Kit capabilities, verbatim:

> **Screen mirror** — The phone UI on the laptop display, live. Demo, debug, and drive the build without picking the device up.
> **Remote control** — Laptop keyboard and trackpad driving the phone. Type into the device at full speed during Red Light.

**Read those two together and the mechanic is unambiguous:**

> During Red Light, **the laptop becomes a monitor and a keyboard for the phone.** The phone is the machine. The laptop is a dumb terminal.

That is the whole trick. You are not expected to type on a 6.85-inch screen for ten hours — you are expected to *drive the phone* using the laptop's screen and keyboard, with the laptop doing no building of its own.

---

## 2. The hard truth: you cannot compile this app on the phone

I checked the on-device build paths. They do not work for this project:

- **Termux + Gradle + Android SDK:** the working examples on GitHub explicitly bypass Gradle entirely and state **"No AndroidX/Jetpack libraries... No Compose — XML layouts only,"** Java 8 bytecode only, no dependency management. Kavach is Kotlin + Compose. This path is dead.
- **Claude Code + Termux APK builds:** the published walkthroughs generate Dalvik bytecode by hand with Python scripts and sign with OpenSSL. It produces a 3.6 KB toy app. Also dead for our purposes.
- **Android 15+ Phantom Process Killer** aggressively terminates long-running background Java processes, which breaks on-device `javac`/Gradle daemons even where they'd otherwise work.
- **Android Studio does not run on Android.**

So: **do not try to run Gradle on the phone.** Anyone who spends Saturday morning on this will lose the day.

---

## 3. The correct architecture

> **The phone is the control surface and the test device. The compute lives on a remote machine. Every route goes through the phone.**

```
   ┌──────────────┐  Office Kit   ┌───────────────┐
   │   LAPTOP     │◄─────────────►│  iQOO PHONE   │
   │ screen +     │  mirror +     │  the machine  │
   │ keyboard     │  remote ctrl  │  + test device│
   │ (no builds)  │               └───────┬───────┘
   └──────────────┘                       │ SSH / browser
                                          ▼
                            ┌──────────────────────────┐
                            │  REMOTE BUILD BOX        │
                            │  Claude Code             │
                            │  Android SDK + Gradle    │
                            │  JDK 21                  │
                            └──────────────────────────┘
                                          │ APK over HTTPS
                                          ▼
                                    installed on the phone
```

Why this is compliant, not a loophole: the restriction is on **the laptop as a build machine**. Here the laptop compiles nothing — it is a peripheral. The phone originates every action. The event provides free AI credits and expects cloud LLM use, so network dependence is clearly in bounds.

**Confirm this reading at the Saturday 10:00 teach-in anyway.** One question costs nothing; an assumption could cost the weekend. Ask: *"During Red Light, may we SSH from the phone into a cloud build machine, with the laptop used only as an Office Kit display and keyboard?"*

---

## 4. Setup — do this Friday

### On the phone (30 minutes)

```bash
# Termux — from F-Droid or GitHub, NOT Google Play (the Play build is abandoned)
pkg update && pkg upgrade
pkg install openssh mosh git tmux nodejs
```

Install **Termius** as well (Android, cross-platform, cloud-synced hosts) — a fine GUI fallback if Termux misbehaves under Office Kit remote control.

### The remote build box

Pick one:

| Option | Best for | Notes |
|---|---|---|
| **GitHub Codespaces** | Fastest to set up | Web UI works in a mobile browser; `gh codespace ssh` from Termux. Watch the monthly quota — stop it when idle. |
| **Cloud VM** (any provider, 4 vCPU / 16 GB) | Most control | Full root, install exactly what you need, no quota surprises. Recommended. |
| **Your own laptop over Tailscale** | Offline resilience | See §6. Gray area under the rules — ask first. |

Provision it with:

```bash
# JDK + Android command-line tools
sudo apt install -y openjdk-21-jdk unzip
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
# download commandlinetools-linux-*.zip from developer.android.com, unzip as 'latest'
export ANDROID_HOME=$HOME/android-sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# Claude Code
npm install -g @anthropic-ai/claude-code

# Warm the Gradle cache NOW, on good wifi — this is the step people forget
cd ~/kavach && ./gradlew assembleDebug --no-daemon
```

### The golden rule

```bash
# ALWAYS run Claude Code inside tmux. Always.
tmux new -s kavach
claude
# detach: Ctrl-b d      reattach: tmux attach -t kavach
```

Your phone will switch networks, sleep, and drop the connection a dozen times over 30 hours. Without tmux, every drop kills your session and your context. With it, you reattach and nothing was lost. Use `mosh` instead of `ssh` where you can — it survives network changes automatically.

---

## 5. The build → install loop

On the remote box, after Claude Code finishes a slice:

```bash
./gradlew assembleDebug
cd app/build/outputs/apk/debug && python3 -m http.server 8000
```

On the phone: open `http://<box-ip>:8000/app-debug.apk` in Chrome → download → install. Enable "install unknown apps" for Chrome once, on Friday.

**Practise this loop on Friday until it takes under 60 seconds.** You will run it fifty times.

Faster variant once it's working: a one-line script on the box that builds, then pushes the APK to a fixed URL, so the phone just refreshes a bookmark.

---

## 6. The failure mode that will actually bite you

**Venue wifi.** A cloud-only workflow dies the moment the network does, and you will be ten hours into Red Light when it happens.

Three layers of defence, set up in this order:

1. **Mobile hotspot on a second phone**, tested Friday, with data confirmed.
2. **Tailscale on both the laptop and the phone.** Then, if the internet dies but the local network lives, the phone can SSH into the laptop as a headless build server over Tailscale. Set this up Friday even if you never use it — it takes fifteen minutes and it is your only offline path. *Ask the organisers whether this is acceptable under Red Light before relying on it; the laptop-as-build-server reading is arguable and you want their answer, not mine.*
3. **Pre-cache everything.** Gradle dependencies, Android SDK, the Gemma model, `node_modules`. Nothing that can be downloaded on Friday should be downloaded on Saturday.

---

## 7. Making Office Kit usage genuine (it's 10% of the rubric)

HackTracker reads Office Kit usage off device telemetry — counts and durations, not self-reporting. Red Light forces most of it anyway, but don't accidentally under-use it out of habit:

- **Shared clipboard** for every token, snippet, error message, and log line. Never a chat window, never email-to-self.
- **File transfer** for every APK and model file. Never a USB cable.
- **Screen mirror** as your primary working display during Red Light, and again during both eval rounds so judges watch the phone UI on a big screen.
- **Remote control** — laptop keyboard driving the phone terminal. This is the ergonomic win that makes ten hours of Red Light survivable.

The other device-data line is **creative phone use (15%): camera, voice, on-device AI**. Kavach uses the mic and on-device models continuously by design, and the QR guard (S3.1) adds the camera. Testing on the real device all weekend generates this telemetry honestly — no gaming required.

---

## 8. Friday checklist

- [ ] Remote build box provisioned; `./gradlew assembleDebug` succeeds on it
- [ ] Termux + tmux + mosh installed on the phone; SSH into the box works
- [ ] Claude Code installed on the box, authenticated, running inside tmux
- [ ] Build → download → install loop done end to end, under 60 seconds
- [ ] "Install unknown apps" enabled for Chrome on the phone
- [ ] Tailscale on phone + laptop, tested
- [ ] Hotspot tested with real data
- [ ] Gemma 4 E2B (2.58 GB) downloaded to the laptop, ready for Office Kit transfer at check-in
- [ ] Gradle cache warm
- [ ] Fixture WAVs recorded (`fixtures/`)
- [ ] Office Kit installed on the laptop from pc.vivoglobal.com and paired once
- [ ] Both chargers, a power bank, and a 3.5mm/USB-C speaker for playing fixture audio at the demo

> Note: the loaner iQOO phone is handed over at Saturday 08:00 check-in, so the phone-side steps get repeated on the loaner that morning. Do them on your own Android phone first so you are repeating a known-good process under time pressure, not learning one.
