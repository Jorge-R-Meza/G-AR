"""Stage 1, speech to text: microphone audio in, finished sentences out.

CONTRACT: transcribe(chunks, rate) takes the glasses' PCM as it arrives and yields each
utterance once the speaker has finished it. assistant.py feeds every one to the router.

Deepgram's live API over a WebSocket. Without DEEPGRAM_API_KEY nothing is transcribed:
the audio is only measured, so the link can be tested, and questions are typed instead.
"""

from __future__ import annotations

import array
import asyncio
import json
import math
import os
from typing import AsyncIterator
from urllib.parse import urlencode

URL = "wss://api.deepgram.com/v1/listen"


def _query(rate: int) -> str:
    import router
    return urlencode({
        # "nova-3-medical" is Deepgram's variant for drug and condition names: try it here.
        "model": os.getenv("DEEPGRAM_STT_MODEL", "nova-3"),
        "language": "en",
        "encoding": "linear16", "sample_rate": rate, "channels": 1,
        "smart_format": "true",
        "interim_results": "true",     # needed for utterance_end_ms
        "endpointing": 300,            # ms of silence that ends a sentence (speech_final)
        "utterance_end_ms": 1000,      # the backstop when background noise hides the silence
        "keyterm": router.phrase(),    # so "chart check" is heard as that, not as something similar
    })


async def transcribe(chunks: AsyncIterator[bytes], rate: int) -> AsyncIterator[str]:
    key = os.getenv("DEEPGRAM_API_KEY")
    if not key:
        print("[stt] no DEEPGRAM_API_KEY: audio is measured, not transcribed. Type questions instead.")
        await _measure(chunks, rate)
        return

    import websockets  # imported here so the rest runs without it installed

    async with websockets.connect(f"{URL}?{_query(rate)}",
                                  additional_headers={"Authorization": f"Token {key}"}) as ws:
        async def send() -> None:
            # Audio never stops while the glasses are connected (silence is sent while the
            # assistant speaks), so Deepgram's 10-second idle timeout needs no KeepAlive.
            async for chunk in chunks:
                await ws.send(chunk)
            await ws.send(json.dumps({"type": "CloseStream"}))

        sender = asyncio.create_task(send())
        try:
            parts: list = []   # the finalised pieces of the sentence being spoken
            async for message in ws:
                if isinstance(message, bytes):
                    continue
                event = json.loads(message)
                kind = event.get("type")
                if kind == "Results":
                    text = event["channel"]["alternatives"][0].get("transcript", "").strip()
                    if event.get("is_final") and text:
                        parts.append(text)
                    if event.get("speech_final") and parts:
                        yield " ".join(parts)
                        parts = []
                elif kind == "UtteranceEnd" and parts:
                    yield " ".join(parts)
                    parts = []
                elif kind == "Error":
                    print(f"[stt] Deepgram error: {event.get('description') or event}")
        finally:
            sender.cancel()


async def _measure(chunks: AsyncIterator[bytes], rate: int) -> None:
    """Stands in for Deepgram: reports that audio is arriving, and how loud it is."""
    seconds, peak, every = 0.0, 0, 3.0
    async for chunk in chunks:
        samples = array.array("h", chunk[: len(chunk) & ~1])
        peak = max(peak, max((abs(s) for s in samples), default=0))
        seconds += len(samples) / rate
        if seconds >= every:
            level = f"peak {round(20 * math.log10(peak / 32768))} dBFS" if peak else "silent"
            print(f"[stt] mic: {seconds:.1f} s received, {level}")
            seconds, peak = 0.0, 0
