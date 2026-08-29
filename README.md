# KAVACH (कवच) 🛡️

**On-Device Real-Time Scam Call Detection for India’s 99% Android Ecosystem.**  
*100% Offline · Zero Network Permission · Hindi, Hinglish & English · LiteRT-LM & Deterministic Dual-Tier Engine*

[![Build Status](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)](https://github.com/Atul-Chahar/KAVACH_IQOO)
[![Privacy Guarantee](https://img.shields.io/badge/INTERNET_Permission-NONE_(Enforced)-blue?style=flat-square)](#-zero-internet-privacy-guarantee)
[![Language Support](https://img.shields.io/badge/Languages-Hindi%20%7C%20Hinglish%20%7C%20English-orange?style=flat-square)](#-code-switched-multi-language-speech-pipeline)
[![Hardware Target](https://img.shields.io/badge/Optimized_for-iQOO_15_%7C_Snapdragon_8_Elite-red?style=flat-square)](#-optimized-for-iqoo-15--snapdragon-8-elite)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square)](LICENSE)

---

## 🎯 The Pitch in One Line

> **Google launched on-device AI scam-call detection, then locked it to Pixel 9+ (less than 1% of India’s smartphone market). KAVACH brings zero-latency, zero-cloud scam protection to the other 99% — running natively on iQOO 15 and Snapdragon 8 Elite hardware in Hindi, Hinglish, and English.**

---

## ⚡ The Problem & The Solution

Indian citizens lose over **₹1,750+ Crore annually** to organized cybercrime: **Digital Arrest scams**, fake **CBI/Police extortion**, **electricity bill deactivation threats**, and **urgent UPI/OTP traps**.

Traditional spam filters (Truecaller, Google Phone) only look at static caller ID databases — they are completely blind the moment a scammer calls from an unflagged VoIP or spoofed number.

**KAVACH listens to the conversation in real time, entirely on-device, and raises an emergency shield the moment the caller’s script matches known psychological scam tactics.**

```
                                  KAVACH LIVE DEFENSE PIPELINE
┌─────────────────┐       ┌────────────────────────┐       ┌──────────────────────────────┐
│  Live Call Audio│ ────► │ Piped Mic Capture Pipe │ ────► │ Dual-Language On-Device ASR  │
│ (VoIP / Cellular│       │ (Accessibility Exemption│      │ (hi-IN Devanagari + en-IN)   │
└─────────────────┘       └────────────────────────┘       └──────────────┬───────────────┘
                                                                          │
                                ┌─────────────────────────────────────────┴───────────────┐
                                │                                                         │
                                ▼                                                         ▼
                 ┌─────────────────────────────┐                           ┌─────────────────────────────┐
                 │  Tier 1: Tactic Lexicon     │                           │  Tier 2: LiteRT-LM (Gemma)  │
                 │  • 7 Tactic Families        │                           │  • Google AI Edge Runtime   │
                 │  • 120+ Hinglish/Hindi rules│                           │  • 4-bit Quantized on GPU   │
                 │  • Sub-millisecond Latency  │                           │  • Semantic Reasoning JSON  │
                 └──────────────┬──────────────┘                           └──────────────┬──────────────┘
                                │                                                         │
                                └─────────────────────────┬───────────────────────────────┘
                                                          ▼
                                           ┌─────────────────────────────┐
                                           │ Merged Risk Engine          │
                                           │ • Diversity Bonus Score     │
                                           │ • Time Decay (120s t½)      │
                                           └──────────────┬──────────────┘
                                                          ▼
                                           ┌─────────────────────────────┐
                                           │ Non-Intrusive Call Shield   │
                                           │ Green ➔ Amber ➔ Red (1930)  │
                                           └─────────────────────────────┘
```

---

## 🚀 Key Innovations & Architecture

### 1. 🔒 Zero-Internet Privacy Guarantee
* **No `android.permission.INTERNET`**: KAVACH contains **zero network access permissions**. It is physically impossible for the app to send private call audio or transcripts to any server.
* **Audio Never Touches Disk**: Audio is processed entirely in memory via circular PCM16 ring buffers and piped directly to on-device recognizers through `ParcelFileDescriptor` pipes.
* **Automated CI Guard**: Gradle builds fail immediately via `:app:assertNoInternetPermission` if any dependency attempts to pull in internet permissions.

### 2. 🎙️ In-Call Live Microphone Capture (Accessibility Exemption)
Android mutes normal third-party apps from recording audio while a phone call is active (`MODE_IN_CALL` / `MODE_IN_COMMUNICATION`). KAVACH solves this through a documented architectural mechanism:
* An **Accessibility Service** registers KAVACH's UID for ambient assistance.
* KAVACH opens `AudioRecord` under its own UID and feeds the speech recognizer via a `ParcelFileDescriptor` pipe using `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
* The recognizer never holds the microphone itself, ensuring capture is never silenced mid-call.

### 3. 🇮🇳 Code-Switched Multi-Language Speech Pipeline (Hindi + Hinglish + English)
Indian scam calls are heavily code-switched: combining Hindi verbs, English nouns, and romanized jargon (*"Main CBI officer bol raha hoon, aapka account block ho jayega, OTP share kijiye"*).
* KAVACH rotates speech recognition between **`hi-IN` (Hindi Devanagari)** and **`en-IN` (Indian English / Latin Hinglish)** at every utterance boundary.
* Integrated with Android 14+ (API 34/35) `SpeechRecognizer.triggerModelDownload()` and `checkRecognitionSupport()` to dynamically download and manage on-device models on OEM devices like iQOO 15 without confusing settings menus.

### 4. 🧠 Dual-Tier Hybrid AI Engine
* **Tier 1 (Deterministic Lexicon Engine)**:
  * 7 tactic families: `AUTHORITY_IMPERSONATION`, `ISOLATION_AND_SECRECY`, `URGENCY_AND_THREAT`, `CREDENTIAL_EXTRACTION`, `REMOTE_ACCESS_AND_TRANSFER`, `DIGITAL_ARREST`, `FINANCIAL_COERCION`.
  * **Tactical Diversity Rule**: High Risk alerts require at least 3 distinct tactic families firing together, eliminating false alarms on legitimate calls.
  * Exponential time decay ($t_{1/2} = 120\text{s}$) prevents ancient conversation context from lingering.
* **Tier 2 (LiteRT-LM Gemma On-Device LLM)**:
  * Powered by Google's **LiteRT-LM** (`com.google.ai.edge.litertlm`) running a 4-bit quantized Gemma model on the phone's Adreno GPU.
  * Provides semantic reasoning and structured JSON output to catch subtle, novel phrasing.

---

## 📱 App Walkthrough & UI Progression

KAVACH is designed to be calm, legible at arm's length (18sp+ typography), and free of panic sirens:

<p align="center">
  <img src="docs/screenshots/redesign/01-home.png" width="30%" alt="Home Readiness Screen" />
  <img src="docs/screenshots/redesign/03-watching.png" width="30%" alt="Watching State (Green)" />
  <img src="docs/screenshots/redesign/04-caution.png" width="30%" alt="Caution State (Amber)" />
</p>

| State | Indicator | Trigger Condition | User Action |
|---|---|---|---|
| **WATCHING** | 🟢 Calm Dot | Call connected, 0–1 tactics detected | Touch passes through to call; no disruption |
| **CAUTION** | 🟡 Amber Banner | 2 distinct tactic families detected | Highlights identified tactics; advises caution |
| **HIGH RISK** | 🔴 Red Shield | 3+ distinct tactic families detected | Presents **Call 1930 Helpline** & **Hang Up** buttons |

<p align="center">
  <img src="docs/screenshots/redesign/05-high-risk.png" width="30%" alt="High Risk Alert" />
  <img src="docs/screenshots/08-report.png" width="30%" alt="Incident Metadata Report" />
  <img src="docs/screenshots/10-hindi-high-risk.png" width="30%" alt="Hindi High Risk Alert" />
</p>

---

## 📊 Benchmark & Accuracy

Evaluated across the standardized KAVACH corpus (Digital Arrest, KYC Expiry, Parcel Narcotics, Bank Officer, Electricity Disconnection, and Legitimate Hospital / Courier calls):

| Test Category | Scenario Count | Detection Rate | False Positive Rate |
|---|---|---|---|
| **Scam Scripts (Positive)** | 12 scenarios | **100% (12/12 reached HIGH_RISK)** | — |
| **Legitimate Calls (Negative)** | 10 scenarios | **0% reached CAUTION / HIGH_RISK** | **0.0%** |
| **Code-Switched Hinglish** | 8 scenarios | **100% matched across scripts** | **0.0%** |

*Re-verify anytime with:*
```bash
./gradlew :domain:test --tests "*FixtureCorpusTest*" -i
```

---

## 🛠️ Quick Start & Installation

### Prerequisites
* **Android Device**: Android 12+ (API 31+) — *Optimized for iQOO 15 (Snapdragon 8 Elite, Android 15/16)*.
* **JDK**: JDK 21 (Toolchain pinned).
* **Android SDK**: `compileSdk = 35`, `targetSdk = 35`.

### 1. Clone & Build
```bash
git clone https://github.com/Atul-Chahar/KAVACH_IQOO.git
cd KAVACH_IQOO

# Run full quality check & tests
./gradlew check

# Build debug APK
./gradlew assembleDebug
```

### 2. Install on Device
```bash
# Enable USB Debugging on your phone, then run:
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

### 3. One-Click Device Smoke Test
```bash
./scripts/device-check.sh
```
*Validates device chipset, grants necessary accessibility & overlay appops, checks Google on-device ASR availability, and launches the app.*

---

## 🧪 Testing Live Voice on iQOO 15

1. Open **KAVACH** on the phone.
2. Tap **"Start listening"** (or receive a real phone / WhatsApp call).
3. Speak a standard multi-tactic scam line aloud in Hindi or Hinglish:
   > *"Main CBI head office se bol raha hoon. Aapke naam par illegal narcotics parcel mila hai aur arrest warrant issue hua hai. Kisi ko mat batana, apna bank account verify karne ke liye OTP bataiye."*
4. Watch the live score climb from **Green (0) ➔ Amber (40) ➔ Red (80+)** and raise the **Call 1930** emergency alert.

---

## 🏗️ Project Structure

```
KAVACH/
├── app/                  # Android Application Module (Jetpack Compose UI, ASR, Capture, LiteRT-LM)
│   ├── src/main/kotlin/  # Live pipeline, speech recognizer, shield overlay
│   └── src/test/         # Unit & integration tests for ASR and model managers
├── domain/               # Pure Kotlin JVM Module (Zero Android SDK dependencies)
│   ├── src/main/kotlin/  # RiskEngine, TacticMatcher, SignalAggregator, Lexicon
│   └── src/test/         # 80+ unit tests, mathematical decay and corpus regressions
├── demo/                 # Deterministic fixture replay engine for airplane mode demos
├── data/
│   └── tactic_lexicon.json # Single source of truth for scam tactics & weights
└── fixtures/             # Positive (scams) and negative (benign calls) test scripts
```

---

## 🏆 iQOO Hackathon 2026

* **Track**: AI & System Innovation on Snapdragon 8 Elite / iQOO 15
* **Team**: **Kavach** (*Atul Chahar & Anant Sharma*)
* **Repository**: [https://github.com/Atul-Chahar/KAVACH_IQOO](https://github.com/Atul-Chahar/KAVACH_IQOO)
* **Official Hackathon Portal**: [iQOO Reskilll Hackathon](https://iqoo.reskilll.com/)

---

## 📜 License

Licensed under the **Apache License, Version 2.0**. See the [LICENSE](LICENSE) file for details.
