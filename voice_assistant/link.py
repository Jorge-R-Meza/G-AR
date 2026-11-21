"""The wire between the glasses and the laptop: one TCP connection, used in both directions.

The glasses listen (as their report server does on 32889) and the laptop connects.
Every message is a frame:

    byte 0      type
    bytes 1-4   payload length, big-endian
    bytes 5...  payload

    HELLO  glasses -> laptop   JSON {"rate": 16000}: the microphone's sample rate. Sent once, first.
    MIC    glasses -> laptop   microphone audio: 16-bit little-endian PCM, mono, at HELLO's rate
    SPEAK  laptop  -> glasses  audio to play: 16-bit little-endian PCM, mono, 16 kHz
    SHOW   laptop  -> glasses  JSON for the lens: {"question", "kind", "say", "detail": [...]}

The other end is glasses/VoiceLink.kt, or fake_glasses.py when there are no glasses.
Over USB instead of Wi-Fi:  adb forward tcp:32890 tcp:32890, then --glasses 127.0.0.1
"""

from __future__ import annotations

import asyncio
import struct
from typing import Tuple

PORT = 32890          # the port after the glasses' report server
MIC, SPEAK, SHOW, HELLO = 1, 2, 3, 4
SPEAKER_RATE = 16000  # what SPEAK audio is always sent at
LIMIT = 1 << 20       # no frame is anywhere near this; a bigger one means the stream is out of step


def frame(kind: int, payload: bytes) -> bytes:
    return struct.pack(">BI", kind, len(payload)) + payload


async def write_frame(writer: asyncio.StreamWriter, kind: int, payload: bytes) -> None:
    writer.write(frame(kind, payload))
    await writer.drain()


async def read_frame(reader: asyncio.StreamReader) -> Tuple[int, bytes]:
    """The next frame. Raises asyncio.IncompleteReadError when the other end has gone."""
    kind, length = struct.unpack(">BI", await reader.readexactly(5))
    if length > LIMIT:
        raise ValueError(f"frame of {length} bytes: not this protocol")
    return kind, await reader.readexactly(length)
