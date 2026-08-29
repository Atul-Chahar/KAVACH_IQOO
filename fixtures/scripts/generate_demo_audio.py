#!/usr/bin/env python3
"""
Render a Kavach fixture into demo audio, using Azure AI Speech.

Why this exists
---------------
DemoMode replays a fixture through the identical detection pipeline, and it
plays audio while it does. Audio that sounds synthetic undermines the one claim
the demo is making — that this is what a real scam call sounds like — so the
voices have to carry conviction, not just words.

What it produces, next to the source .txt:

    <fixture>.wav   intermediate, deleted unless --keep-wav
    <fixture>.mp3   what MediaPlayer plays
    <fixture>.json  [{speaker, text, startMs, endMs}, ...]

The JSON is the contract with FixtureTranscriptSource: it emits each
TranscriptWindow at the moment that line is actually spoken, so the score moves
in step with the audio instead of on a metronome. Timings are measured from the
rendered PCM, never estimated, so they cannot drift.

Credentials come from the environment and are never written anywhere:

    export AZURE_SPEECH_KEY=...
    export AZURE_SPEECH_REGION=eastus

Usage
-----
    python3 scripts/generate_demo_audio.py --list-voices
    python3 scripts/generate_demo_audio.py fixtures/positive/trai-disconnect-01.txt
    python3 scripts/generate_demo_audio.py --all-english
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

try:
    import azure.cognitiveservices.speech as speechsdk
except ImportError:
    sys.exit(
        "azure-cognitiveservices-speech is not installed.\n"
        "  python3 -m venv .venv && .venv/bin/pip install azure-cognitiveservices-speech"
    )

REPO = Path(__file__).resolve().parent.parent

# 24 kHz mono PCM: the recogniser downsamples to 16 kHz anyway, and WAV keeps
# concatenation lossless. One MP3 encode happens at the end.
AUDIO_FORMAT = speechsdk.SpeechSynthesisOutputFormat.Riff24Khz16BitMonoPcm

# Silence between turns. Real callers overlap and rush; a flat gap everywhere is
# one of the things that makes assembled dialogue sound assembled.
GAP_MS_AFTER_QUESTION = 420
GAP_MS_DEFAULT = 620
GAP_MS_SAME_SPEAKER = 320

# Preference order per role. DragonHD first — it is the least synthetic of the
# families — then standard Neural. Indian English before US: the script is an
# Indian scam script, and an American accent reading it is its own kind of wrong.
VOICE_PREFERENCE = {
    "CALLER": [
        "en-IN-AaravNeural",
        "en-IN-PrabhatNeural",
        "en-US-Adam:DragonHDLatestNeural",
        "en-US-Andrew:DragonHDLatestNeural",
    ],
    "CALLER2": [
        "en-IN-KunalNeural",
        "en-IN-RehaanNeural",
        "en-US-Brian:DragonHDLatestNeural",
        "en-US-Steffan:DragonHDLatestNeural",
    ],
    "VICTIM": [
        "en-IN-AnanyaNeural",
        "en-IN-NeerjaNeural",
        "en-US-Ava:DragonHDLatestNeural",
        "en-US-Emma:DragonHDLatestNeural",
    ],
}
FALLBACK_VOICE = "en-US-Adam:DragonHDLatestNeural"


@dataclass
class Turn:
    speaker: str
    text: str


def config() -> speechsdk.SpeechConfig:
    key = os.environ.get("AZURE_SPEECH_KEY")
    region = os.environ.get("AZURE_SPEECH_REGION")
    if not key or not region:
        sys.exit(
            "Set AZURE_SPEECH_KEY and AZURE_SPEECH_REGION first.\n"
            "  export AZURE_SPEECH_KEY=...\n"
            "  export AZURE_SPEECH_REGION=eastus"
        )
    speech_config = speechsdk.SpeechConfig(subscription=key, region=region)
    speech_config.set_speech_synthesis_output_format(AUDIO_FORMAT)
    return speech_config


def available_voices(speech_config: speechsdk.SpeechConfig) -> list[str]:
    """Ask the service what it actually has, rather than trusting a hardcoded name."""
    synthesizer = speechsdk.SpeechSynthesizer(speech_config=speech_config, audio_config=None)
    result = synthesizer.get_voices_async("en").get()
    if result.reason != speechsdk.ResultReason.VoicesListRetrieved:
        sys.exit(f"could not list voices: {result.error_details}")
    return [v.short_name for v in result.voices]


def pick_voices(catalogue: list[str]) -> dict[str, str]:
    """First available preference per role, so a missing voice degrades instead of failing."""
    chosen: dict[str, str] = {}
    for role, preferences in VOICE_PREFERENCE.items():
        chosen[role] = next((v for v in preferences if v in catalogue), FALLBACK_VOICE)
    return chosen


def parse_fixture(path: Path) -> list[Turn]:
    """`SPEAKER: line` into turns, dropping the `# expected:` header comments."""
    turns: list[Turn] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = re.match(r"^([A-Z][A-Z0-9_]*)\s*:\s*(.+)$", line)
        speaker, text = (match.group(1), match.group(2)) if match else ("CALLER", line)
        turns.append(Turn(speaker=speaker, text=text.strip()))
    return turns


def ssml(voice: str, text: str) -> str:
    """
    Minimal SSML on purpose.

    DragonHD voices already model delivery from punctuation and meaning; piling
    prosody and express-as on top fights the model and is what makes output
    sound over-directed. The text is escaped and handed over close to as-written.
    """
    escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return (
        '<speak version="1.0" xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="en-IN">'
        f'<voice name="{voice}">{escaped}</voice></speak>'
    )


def synthesize(speech_config, voice: str, text: str, out: Path) -> int:
    """Renders one turn to WAV and returns its true duration in milliseconds."""
    audio_config = speechsdk.audio.AudioOutputConfig(filename=str(out))
    synthesizer = speechsdk.SpeechSynthesizer(speech_config=speech_config, audio_config=audio_config)
    result = synthesizer.speak_ssml_async(ssml(voice, text)).get()

    if result.reason == speechsdk.ResultReason.Canceled:
        details = result.cancellation_details
        sys.exit(f"synthesis canceled: {details.reason}\n{details.error_details}")
    if result.reason != speechsdk.ResultReason.SynthesizingAudioCompleted:
        sys.exit(f"synthesis failed for: {text[:60]!r}")

    with wave.open(str(out), "rb") as handle:
        return round(handle.getnframes() * 1000 / handle.getframerate())


def gap_after(turn: Turn, following: Turn | None) -> int:
    if following is None:
        return 0
    if following.speaker == turn.speaker:
        return GAP_MS_SAME_SPEAKER
    if turn.text.rstrip().endswith("?"):
        return GAP_MS_AFTER_QUESTION
    return GAP_MS_DEFAULT


def render(fixture: Path, speech_config, voices: dict[str, str], keep_wav: bool) -> None:
    turns = parse_fixture(fixture)
    if not turns:
        sys.exit(f"no dialogue found in {fixture}")

    print(f"\n{fixture.relative_to(REPO)}  ({len(turns)} turns)")
    timeline: list[dict] = []

    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        pieces: list[Path] = []
        cursor = 0

        for index, turn in enumerate(turns):
            voice = voices.get(turn.speaker, voices["CALLER"])
            clip = tmpdir / f"{index:03d}.wav"
            duration = synthesize(speech_config, voice, turn.text, clip)
            pieces.append(clip)

            timeline.append(
                {
                    "speaker": turn.speaker,
                    "text": turn.text,
                    "startMs": cursor,
                    "endMs": cursor + duration,
                }
            )
            print(f"  {cursor / 1000:6.1f}s  {turn.speaker:<8} {voice:<34} {turn.text[:44]}")
            cursor += duration

            gap = gap_after(turn, turns[index + 1] if index + 1 < len(turns) else None)
            if gap:
                silence = tmpdir / f"{index:03d}_gap.wav"
                subprocess.run(
                    ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi",
                     "-i", f"anullsrc=r=24000:cl=mono", "-t", f"{gap / 1000}", str(silence)],
                    check=True,
                )
                pieces.append(silence)
                cursor += gap

        listing = tmpdir / "pieces.txt"
        listing.write_text("".join(f"file '{p}'\n" for p in pieces), encoding="utf-8")

        wav_out = fixture.with_suffix(".wav")
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0",
             "-i", str(listing), "-c", "copy", str(wav_out)],
            check=True,
        )
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav_out),
             "-codec:a", "libmp3lame", "-b:a", "64k", "-ac", "1", str(fixture.with_suffix(".mp3"))],
            check=True,
        )
        if not keep_wav:
            wav_out.unlink()

    fixture.with_suffix(".json").write_text(
        json.dumps(timeline, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    mp3 = fixture.with_suffix(".mp3")
    print(f"  -> {mp3.name} ({mp3.stat().st_size // 1024} KB), {fixture.with_suffix('.json').name}, "
          f"{cursor / 1000:.1f}s total")


def english_fixtures() -> list[Path]:
    """Fixtures with no Devanagari and no romanised-Hindi markers."""
    hinglish = re.compile(r"\b(aapke|hoon|kijiye|nahi|bataiye|karna|hai|mat|jayega)\b", re.I)
    devanagari = re.compile(r"[ऀ-ॿ]")
    found = []
    for path in sorted((REPO / "fixtures").glob("*/*.txt")):
        body = path.read_text(encoding="utf-8")
        if not devanagari.search(body) and not hinglish.search(body):
            found.append(path)
    return found


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("fixtures", nargs="*", type=Path, help="fixture .txt files to render")
    parser.add_argument("--all-english", action="store_true", help="render every English fixture")
    parser.add_argument("--list-voices", action="store_true", help="print the English voices this key can use")
    parser.add_argument("--keep-wav", action="store_true", help="keep the lossless intermediate")
    args = parser.parse_args()

    speech_config = config()
    catalogue = available_voices(speech_config)

    if args.list_voices:
        for name in sorted(catalogue):
            marker = " <- DragonHD" if "DragonHD" in name else ""
            print(f"  {name}{marker}")
        return

    targets = args.fixtures or (english_fixtures() if args.all_english else [])
    if not targets:
        parser.error("name at least one fixture, or pass --all-english")

    voices = pick_voices(catalogue)
    print("Voices:")
    for role, voice in voices.items():
        print(f"  {role:<8} {voice}")

    for fixture in targets:
        render(fixture.resolve(), speech_config, voices, args.keep_wav)


if __name__ == "__main__":
    main()
