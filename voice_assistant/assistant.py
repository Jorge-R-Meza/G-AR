#!/usr/bin/env python3
"""The glasses' voice assistant, laptop side. The glasses only record and play.

    glasses mic --(link.py, TCP 32890)--> this program
        1. stt.py      Deepgram turns the audio into sentences
        2. router.py   a decision model: is it for the assistant, and what kind of question
        3. sources.py  the chart and lookups for that kind of question
        4. answer.py   a small LLM writes one spoken line and the lens text
        5. tts.py      Deepgram turns the spoken line into audio
    glasses lens and speaker <-- SHOW and SPEAK frames --

While the assistant speaks, the microphone audio is replaced with silence, so it never
transcribes or answers its own voice (EchoGate).

Run:   python3 assistant.py --glasses <glasses-ip>      (default 127.0.0.1: fake_glasses.py, or adb forward)
Try:   python3 assistant.py --dry --ask "When did they start metformin?"     no glasses, no audio
Keys go in .env (see .env.example). Each stage has a stand-in for when its key is missing.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shutil
import sys
import threading
import time
from pathlib import Path
from typing import AsyncIterator, Optional

import answer as answer_stage
import router
import sources
import stt
import tts
from link import HELLO, MIC, PORT, SHOW, SPEAK, SPEAKER_RATE, read_frame, write_frame


def load_env(path: Path) -> None:
    """KEY=value lines from .env, without replacing anything already set in the shell."""
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        name, _, value = line.strip().partition("=")
        value = value.strip().strip("\"'")
        if name and not name.startswith("#") and value:
            os.environ.setdefault(name.strip(), value)


class EchoGate:
    """Silences the microphone while the glasses are speaking, and for a moment after."""

    TAIL = 0.5   # seconds: the speaker's buffer, and the room, after the last sample is sent

    def __init__(self) -> None:
        self._until = 0.0
        self.muted_bytes = 0

    def speaking(self, seconds: float) -> None:
        self._until = max(self._until, time.monotonic()) + seconds

    def muted(self) -> bool:
        return time.monotonic() < self._until + self.TAIL

    def filter(self, pcm: bytes) -> bytes:
        if not self.muted():
            return pcm
        self.muted_bytes += len(pcm)
        return bytes(len(pcm))   # silence, not nothing: the transcriber's clock keeps running


class Assistant:
    def __init__(self, writer: Optional[asyncio.StreamWriter], reports: Path, voice: bool) -> None:
        self.writer = writer     # None for --dry: answers are only printed
        self.reports = reports
        self.voice = voice
        self.gate = EchoGate()
        self.busy = False

    async def handle(self, text: str) -> None:
        """One sentence, heard or typed: stay silent, or answer on the lens and the speaker."""
        text = text.strip()
        if not text:
            return
        if self.busy:   # one answer at a time: a second question while speaking is dropped
            print(f"[skip]   still answering, dropped {text!r}")
            return
        self.busy = True
        started = time.monotonic()
        try:
            route = await router.route(text)
            if not route.for_assistant:
                print(f"[quiet]  {text!r}  ({route.by}, {route.score:.2f})")
                return
            text = route.question   # without "chart check"
            data = await sources.gather(route.kind, text, self.reports)
            reply = await answer_stage.answer(text, route.kind, data)
            print(f"[answer] {int((time.monotonic() - started) * 1000)}ms {route.kind} "
                  f"({route.by}, {route.score:.2f}): {text!r}\n"
                  f"         say:  {reply['say']!r}\n         lens: {reply['detail']}")
            if self.writer is None:
                return
            # The lens first: it is the full answer, and the spoken line takes a moment to make.
            await write_frame(self.writer, SHOW,
                              json.dumps({"question": text, "kind": route.kind, **reply}).encode())
            if self.voice and reply["say"]:
                await self._say(reply["say"])
        except (ConnectionError, OSError) as e:
            print(f"[link]   lost while answering: {e!r}")
        finally:
            self.busy = False

    async def _say(self, line: str) -> None:
        pcm = await tts.speak(line)
        if not pcm:
            return
        self.gate.speaking(len(pcm) / 2 / SPEAKER_RATE)   # before the first sample leaves
        step = SPEAKER_RATE * 2 // 10                      # 100 ms a frame
        for start in range(0, len(pcm), step):
            await write_frame(self.writer, SPEAK, pcm[start:start + step])


async def pump_mic(reader: asyncio.StreamReader, audio: asyncio.Queue, gate: EchoGate) -> None:
    """Glasses -> queue, until the glasses go away. None on the queue marks the end."""
    try:
        while True:
            kind, payload = await read_frame(reader)
            if kind == MIC:
                audio.put_nowait(gate.filter(payload))
    except (asyncio.IncompleteReadError, ConnectionError, ValueError):
        pass
    finally:
        audio.put_nowait(None)


async def chunks(audio: asyncio.Queue) -> AsyncIterator[bytes]:
    while True:
        chunk = await audio.get()
        if chunk is None:
            return
        yield chunk


async def listen(assistant: Assistant, audio: asyncio.Queue, rate: int) -> None:
    answering = set()   # keeps the tasks alive; handling must not hold up the transcriber
    try:
        async for text in stt.transcribe(chunks(audio), rate):
            task = asyncio.create_task(assistant.handle(text))
            answering.add(task)
            task.add_done_callback(answering.discard)
    except asyncio.CancelledError:
        raise
    except Exception as e:
        print(f"[stt]    stopped: {e!r}. Typed questions still work.")
        async for _ in chunks(audio):   # keep the queue from growing
            pass


async def typed(assistant: Assistant) -> None:
    """Questions typed in the terminal go through the same pipeline as spoken ones."""
    loop = asyncio.get_running_loop()
    lines: asyncio.Queue = asyncio.Queue()
    threading.Thread(   # a daemon thread, so a blocked readline never holds up Ctrl-C
        target=lambda: [loop.call_soon_threadsafe(lines.put_nowait, line) for line in sys.stdin],
        daemon=True).start()
    while True:
        await assistant.handle(await lines.get())


async def connect(host: str, port: int):
    waiting = False
    while True:
        try:
            return await asyncio.open_connection(host, port)
        except OSError:
            if not waiting:
                print(f"Waiting for the glasses at {host}:{port} ...")
                waiting = True
            await asyncio.sleep(2)


async def session(args: argparse.Namespace) -> None:
    """One connection to the glasses, from hello to hang-up."""
    reader, writer = await connect(args.glasses, args.port)
    tasks = []
    assistant = Assistant(writer, args.reports, not args.no_voice)
    rate = SPEAKER_RATE
    try:
        kind, payload = await read_frame(reader)
        if kind != HELLO:
            raise ValueError(f"expected the glasses' hello, got frame type {kind}")
        rate = int(json.loads(payload)["rate"])
        print(f"Glasses connected at {args.glasses}: microphone at {rate} Hz. Ask out loud, or type a question.")
        audio: asyncio.Queue = asyncio.Queue()
        mic = asyncio.create_task(pump_mic(reader, audio, assistant.gate))
        tasks = [mic, asyncio.create_task(listen(assistant, audio, rate))]
        if args.ask:
            await assistant.handle(args.ask)
            while assistant.gate.muted():   # let the glasses finish speaking before hanging up
                await asyncio.sleep(0.1)
        else:
            if sys.stdin.isatty():
                tasks.append(asyncio.create_task(typed(assistant)))
            await mic
    except (asyncio.IncompleteReadError, ConnectionError, ValueError) as e:
        print(f"[link]   {e!r}")
    finally:
        for task in tasks:
            task.cancel()
        writer.close()
        if assistant.gate.muted_bytes:
            print(f"Microphone muted for {assistant.gate.muted_bytes / 2 / rate:.1f} s while the assistant spoke.")


async def run(args: argparse.Namespace) -> None:
    if args.dry:
        assistant = Assistant(None, args.reports, voice=False)
        for line in [args.ask] if args.ask else sys.stdin:
            await assistant.handle(line)
        return
    while True:
        await session(args)
        if args.ask:
            return
        print("Glasses disconnected.")
        await asyncio.sleep(1)


def describe_stages(reports: Path) -> None:
    deepgram = bool(os.getenv("DEEPGRAM_API_KEY"))
    print("Speech to text  " + (f"Deepgram {os.getenv('DEEPGRAM_STT_MODEL', 'nova-3')}" if deepgram
                                else "off (no DEEPGRAM_API_KEY): type questions instead"))
    print("Router          " + router.name())
    print("Answers         " + (answer_stage.model()
                                or "off (no META_API_KEY or ANTHROPIC_API_KEY): always \"I don't have that.\""))
    print("Voice           " + (f"Deepgram {os.getenv('DEEPGRAM_VOICE', 'aura-2-asteria-en')}" if deepgram
                                else "macOS say (no DEEPGRAM_API_KEY)" if shutil.which("say") else "off"))
    print("Chart           " + ("a patient report is saved" if sources.chart(reports) is not None
                                else f"no patient report in {reports}"))


def main() -> None:
    parser = argparse.ArgumentParser(description="The glasses' voice assistant, laptop side.")
    parser.add_argument("--glasses", default="127.0.0.1", metavar="HOST",
                        help="where the glasses are (default 127.0.0.1: fake_glasses.py, or adb forward)")
    parser.add_argument("--port", type=int, default=PORT, help=f"the glasses' voice port (default {PORT})")
    parser.add_argument("--reports", type=Path, default=sources.REPORTS, metavar="DIR",
                        help="where the stream viewer saves patient reports")
    parser.add_argument("--ask", metavar="TEXT", help="ask this one question as if it had been heard, then exit")
    parser.add_argument("--dry", action="store_true",
                        help="no glasses: print the answers to --ask, or to lines typed on stdin")
    parser.add_argument("--no-voice", action="store_true", help="show answers on the lens without speaking them")
    args = parser.parse_args()

    load_env(Path(__file__).with_name(".env"))
    describe_stages(args.reports)
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
