#!/usr/bin/env python3
"""Stands in for the M6 glasses' microphone, speaker and lens, so the assistant can be
tested without them. Speaks the same protocol as glasses/VoiceLink.kt (see link.py).

Run it, then run assistant.py in another terminal (it connects to 127.0.0.1 by default).

    python3 fake_glasses.py                         a silent microphone: type questions into assistant.py
    python3 fake_glasses.py --say "Do their medications interfere with penicillin?"
                                                    the Mac's voice asks the question into the microphone
    python3 fake_glasses.py --wav question.wav      a recording instead (16 kHz, mono, 16-bit)
    python3 fake_glasses.py --mic                   this Mac's microphone (needs: pip install sounddevice)

What the assistant sends back is printed (the lens) and played (the speaker).
    --echo    also feeds the speaker's sound back into the microphone, as real glasses do,
              to check that the assistant does not answer itself
    --quiet   don't play anything out loud
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import queue
import subprocess
import sys
import tempfile
import wave

from link import HELLO, MIC, PORT, SHOW, SPEAK, SPEAKER_RATE, read_frame, write_frame

RATE = 16000                     # what the real glasses' microphone opens at
STEP = RATE * 2 * 40 // 1000     # 40 ms of 16-bit mono: small enough for one Wi-Fi packet


def question_audio(args: argparse.Namespace) -> bytes:
    """What the microphone hears first, as PCM. Empty for a silent room."""
    path = args.wav
    with tempfile.TemporaryDirectory() as folder:
        if args.say:
            path = os.path.join(folder, "question.wav")
            subprocess.run(["say", "-o", path, f"--data-format=LEI16@{RATE}", args.say], check=True)
        if not path:
            return b""
        with wave.open(path, "rb") as audio:
            if (audio.getframerate(), audio.getnchannels(), audio.getsampwidth()) != (RATE, 1, 2):
                sys.exit(f"{path} must be {RATE} Hz, mono, 16-bit. "
                         f"Convert it with: afconvert -f WAVE -d LEI16@{RATE} -c 1 in.wav out.wav")
            return audio.readframes(audio.getnframes())


def live_microphone() -> "queue.Queue[bytes]":
    """This Mac's microphone in 40 ms chunks. Not tested here: it needs sounddevice and mic permission."""
    try:
        import sounddevice
    except ImportError:
        sys.exit("--mic needs the sounddevice package: python3 -m pip install sounddevice")
    heard: "queue.Queue[bytes]" = queue.Queue()
    stream = sounddevice.RawInputStream(samplerate=RATE, channels=1, dtype="int16", blocksize=STEP // 2,
                                        callback=lambda data, frames, when, status: heard.put(bytes(data)))
    stream.start()
    live_microphone.stream = stream   # keeps it from being collected
    return heard


def play(pcm: bytes, quiet: bool) -> None:
    print(f"SPEAKER  {len(pcm) / 2 / SPEAKER_RATE:.1f} s of speech" + ("" if quiet else " (playing)"))
    if quiet:
        return
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as file:
        with wave.open(file, "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(SPEAKER_RATE)
            audio.writeframes(pcm)
    subprocess.Popen(["afplay", file.name])


async def serve(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, args: argparse.Namespace) -> None:
    print("Laptop connected.")
    loop = asyncio.get_running_loop()
    question = question_audio(args)
    live = live_microphone() if args.mic else None
    echo = bytearray()      # speaker sound still to come back through the microphone
    speech = bytearray()    # the sentence being received
    last_speech = 0.0

    async def microphone() -> None:
        nonlocal question, last_speech
        await write_frame(writer, HELLO, json.dumps({"rate": RATE}).encode())
        due = loop.time()
        while True:
            if question:
                chunk, question = question[:STEP], question[STEP:]
            elif live is not None:
                chunk = b"".join(live.get_nowait() for _ in range(live.qsize()))
            elif echo:
                chunk = bytes(echo[:STEP])
                del echo[:STEP]
            else:
                chunk = b""
            if live is None:
                chunk = chunk.ljust(STEP, b"\0")   # a quiet room is silence, not a gap
            if chunk:
                await write_frame(writer, MIC, chunk)
            if speech and loop.time() - last_speech > 0.3:   # the sentence has all arrived
                play(bytes(speech), args.quiet)
                speech.clear()
            due += STEP / 2 / RATE
            await asyncio.sleep(max(0.0, due - loop.time()))

    async def speaker_and_lens() -> None:
        nonlocal last_speech
        while True:
            kind, payload = await read_frame(reader)
            if kind == SPEAK:
                speech.extend(payload)
                last_speech = loop.time()
                if args.echo:
                    echo.extend(payload)
            elif kind == SHOW:
                shown = json.loads(payload)
                print(f"LENS     {shown.get('question', '')}")
                for line in shown.get("detail", []):
                    print(f"           {line}")
                print(f"         (spoken: {shown.get('say', '')})")

    tasks = [asyncio.create_task(microphone()), asyncio.create_task(speaker_and_lens())]
    try:
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if speech:
            play(bytes(speech), args.quiet)
        writer.close()
        print("Laptop disconnected.")


async def main() -> None:
    parser = argparse.ArgumentParser(description="Stands in for the glasses' microphone, speaker and lens.")
    parser.add_argument("--host", default="127.0.0.1", help="address to listen on (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=PORT)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--say", metavar="TEXT", help="a question for the Mac's voice to ask into the microphone")
    source.add_argument("--wav", metavar="FILE", help="a recording to play into the microphone")
    source.add_argument("--mic", action="store_true", help="use this Mac's microphone")
    parser.add_argument("--echo", action="store_true", help="feed the speaker's sound back into the microphone")
    parser.add_argument("--quiet", action="store_true", help="don't play what the assistant says")
    args = parser.parse_args()

    server = await asyncio.start_server(lambda r, w: serve(r, w, args), args.host, args.port)
    print(f"Fake glasses listening on {args.host}:{args.port}. Now run: python3 assistant.py")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
