"""Stage 5, text to speech: the spoken line as audio for the glasses' speaker.

CONTRACT: speak(text) returns 16-bit little-endian PCM, mono, 16 kHz (link.SPEAKER_RATE),
or b"" when no voice is available, in which case the answer is only shown.

Deepgram's Aura voices, the same family Jamie spoke with. Without DEEPGRAM_API_KEY the
Mac's own "say" command stands in, so the whole loop can be heard with no account.
"""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import tempfile
import urllib.error
import urllib.request
import wave
from urllib.parse import urlencode

from link import SPEAKER_RATE

URL = "https://api.deepgram.com/v1/speak"


async def speak(text: str) -> bytes:
    key = os.getenv("DEEPGRAM_API_KEY")
    try:
        if key:
            return await asyncio.to_thread(_deepgram, text, key)
        if shutil.which("say"):
            return await _mac_say(text)
    except (OSError, urllib.error.URLError, wave.Error) as e:
        print(f"[tts] failed: {e!r}")
    return b""


def _deepgram(text: str, key: str) -> bytes:
    query = urlencode({"model": os.getenv("DEEPGRAM_VOICE", "aura-2-asteria-en"),
                       "encoding": "linear16", "sample_rate": SPEAKER_RATE,
                       "container": "none"})   # raw PCM: no WAV header to strip
    request = urllib.request.Request(
        f"{URL}?{query}", data=json.dumps({"text": text}).encode(), method="POST",
        headers={"Authorization": f"Token {key}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return response.read()


async def _mac_say(text: str) -> bytes:
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, "say.wav")
        process = await asyncio.create_subprocess_exec(
            "say", "-o", path, f"--data-format=LEI16@{SPEAKER_RATE}", text)
        if await process.wait() != 0:
            raise OSError("say failed")
        with wave.open(path, "rb") as audio:
            return audio.readframes(audio.getnframes())
