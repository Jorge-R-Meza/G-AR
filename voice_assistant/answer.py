"""Stage 4, the answer: a small LLM writes one spoken line and a few lines for the lens.

CONTRACT: answer(question, kind, data) returns {"say": str, "detail": [str, ...]}.
"say" is spoken on the glasses, where the patient hears it too. "detail" goes on the lens.

The model is given only what sources.py gathered, and told to say it doesn't have
anything that isn't there. Which model answers depends on the key in .env:
META_API_KEY (Meta's Model API) first, then ANTHROPIC_API_KEY (Claude). With neither,
the answer is always "I don't have that."
"""

from __future__ import annotations

import asyncio
import json
import os
import urllib.error
import urllib.request
from typing import Optional

META_URL = "https://api.meta.ai/v1/chat/completions"
META_MODEL = "muse-ember-1.0"        # Meta's model for latency-sensitive turns
CLAUDE_MODEL = "claude-haiku-5-5"    # ANSWER_MODEL in .env overrides either
DONT_HAVE = "I don't have that."

SYSTEM = """You are the voice assistant in a clinician's smart glasses. During a consultation the \
clinician asks you a question out loud. You reply with one spoken line, played through the glasses' \
speaker, and a few lines of text shown on the lens.

Answer only from the DATA in the message: the patient's chart and any lookup results. It is the \
whole of what you know about this patient. A wrong or guessed answer here could harm someone, so \
do not fill gaps from general medical knowledge. If the DATA does not settle the question, "say" is \
exactly "I don't have that." and "detail" names what is missing.

"say": one short sentence, 15 words at most, in plain speech. The patient can hear it, so give the \
answer itself and nothing else from the chart.

"detail": at most 4 lines for the lens, each at most 34 characters, most important first: the values, \
drug names and dates behind the answer. The lens is small and shows one colour, so write short labels \
and numbers, not sentences."""

SCHEMA = {
    "type": "object",
    "properties": {"say": {"type": "string"}, "detail": {"type": "array", "items": {"type": "string"}}},
    "required": ["say", "detail"],
    "additionalProperties": False,
}


def model() -> Optional[str]:
    """The model that will answer, or None when no key is set."""
    if os.getenv("META_API_KEY"):
        return os.getenv("ANSWER_MODEL", META_MODEL)
    if os.getenv("ANTHROPIC_API_KEY") or os.getenv("ANTHROPIC_AUTH_TOKEN"):
        return os.getenv("ANSWER_MODEL", CLAUDE_MODEL)
    return None


async def answer(question: str, kind: str, data: dict) -> dict:
    name = model()
    if name is None:
        return {"say": DONT_HAVE, "detail": ["ANSWER MODEL NOT SET UP"]}
    prompt = json.dumps({"question": question, "kind": kind, "DATA": data})
    try:
        if os.getenv("META_API_KEY"):
            text = await asyncio.to_thread(_meta, name, prompt)
        else:
            text = await _claude(name, prompt)
        reply = json.loads(text)
        return {"say": reply["say"].strip(), "detail": [line.strip() for line in reply["detail"]][:4]}
    except Exception as e:   # whatever went wrong, the glasses must not say something made up
        print(f"[answer] failed: {e!r}")
        return {"say": DONT_HAVE, "detail": ["ANSWER FAILED"]}


def _meta(name: str, prompt: str) -> str:
    """Meta's Model API, through its OpenAI-style chat completions endpoint."""
    body = {
        "model": name,
        "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}],
        "reasoning_effort": "low",        # these models always reason; someone is waiting to hear this
        "max_completion_tokens": 2000,
        "response_format": {"type": "json_schema",
                            "json_schema": {"name": "answer", "schema": SCHEMA, "strict": True}},
    }
    request = urllib.request.Request(
        META_URL, data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": f"Bearer {os.environ['META_API_KEY']}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            choice = json.load(response)["choices"][0]
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Meta API {e.code}: {e.read()[:300].decode(errors='replace')}") from None
    if choice.get("finish_reason") != "stop":
        raise RuntimeError(f"Meta API stopped early: {choice.get('finish_reason')}")
    return choice["message"]["content"]


_client = None


async def _claude(name: str, prompt: str) -> str:
    global _client
    import anthropic  # imported here so the rest runs without it installed

    if _client is None:
        _client = anthropic.AsyncAnthropic(timeout=15.0, max_retries=1)
    try:
        response = await _client.messages.create(
            model=name,
            max_tokens=2000,   # thinking counts towards this, so leave room above the short answer
            system=SYSTEM,
            output_config={"effort": "low", "format": {"type": "json_schema", "schema": SCHEMA}},
            messages=[{"role": "user", "content": prompt}])
    except anthropic.RateLimitError:
        raise RuntimeError("Claude API: rate limited") from None
    except anthropic.APIStatusError as e:
        raise RuntimeError(f"Claude API {e.status_code}: {e.message}") from None
    except anthropic.APIConnectionError:
        raise RuntimeError("Claude API: no connection") from None
    if response.stop_reason != "end_turn":   # "refusal", or "max_tokens" before the JSON was complete
        raise RuntimeError(f"Claude API stopped early: {response.stop_reason}")
    return next(block.text for block in response.content if block.type == "text")
