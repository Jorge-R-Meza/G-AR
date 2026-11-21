"""Stage 2, gate and router: is this sentence for the assistant, and what kind of question is it?

CONTRACT: route(text) returns a Route. assistant.py stays silent unless for_assistant is
true, and uses kind to choose which data the answer model is given (sources.py).

SubQ's Snap does this. It is a decision model: it scores and picks from the options given
here, and writes nothing. One request asks both questions. Without SUBQ_API_KEY a keyword
rule stands in, which is only good enough to exercise the rest of the pipeline.

"CHART CHECK" is the override for when the gate gets it wrong: a sentence that starts with
it always goes to the assistant ("Chart check, does penicillin interact with anything?").
Said on its own, it sends the next sentence instead. Snap still picks the kind.
"""

from __future__ import annotations

import asyncio
import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

URL = "https://api.subq.ai/v1/systemone"
MODEL = "subq-snap-1.2"   # ROUTER_MODEL in .env overrides it

# Measured on 12 made-up sentences: questions for the assistant scored 0.73 to 1.00, everything
# else 0.00 to 0.11. Tune on real consultations.
GATE_MIN = 0.5   # below this the sentence was not meant for the assistant
KIND_MIN = 0.6   # a kind less sure than this is treated as "other"

PHRASE = "chart check"   # TRIGGER_PHRASE in .env overrides it
ARMED_SECONDS = 8.0      # how long the phrase, said on its own, waits for the question

QUESTIONS = {
    "for_assistant": {
        "type": "noul",
        "instructions": (
            "A clinician wearing smart glasses with a voice assistant is in a consultation with a "
            "patient. Is this sentence the clinician asking the assistant something about the "
            "patient's chart, medications or results? Things said to the patient, the patient's own "
            "words, small talk and thinking aloud are not for the assistant."),
    },
    "kind": {
        "type": "choice",
        "instructions": "What kind of question is the clinician asking the assistant?",
        "criteria": {
            "INTERACTION": "Asks whether a drug, food or treatment interacts or interferes with, or is "
                           "safe alongside, the patient's medications, conditions or allergies.",
            "CHART": "Asks for something recorded in the patient's chart: a medication, a start date, "
                     "a condition, a lab result or a vital sign.",
            "OTHER": "Anything else.",
        },
    },
}


@dataclass
class Route:
    for_assistant: bool
    kind: str      # "interaction", "chart" or "other"
    score: float   # how sure the gate was, 0 to 1
    by: str        # which router decided, for the log
    question: str  # what to answer: the sentence, without the trigger phrase


def name() -> str:
    """What will do the routing, for the start-up summary."""
    return f"SubQ {os.getenv('ROUTER_MODEL', MODEL)}" if os.getenv("SUBQ_API_KEY") else "keyword stand-in (no SUBQ_API_KEY)"


def phrase() -> str:
    return os.getenv("TRIGGER_PHRASE", PHRASE).strip().lower()


def _after_phrase(text: str):
    """What follows the trigger phrase, or None if it wasn't said. "" when it was said on its own."""
    words = [re.escape(word) for word in phrase().split()]
    if not words:
        return None
    # The transcriber may write "Chart check,", "chart-check" or "Chart. Check": allow all of them.
    found = re.search(r"\b" + r"[\s,.\-]*".join(words) + r"\b[\s,.:;!?\-]*", text, re.I)
    return None if found is None else text[found.end():].strip()


_armed_until = 0.0   # the phrase was said on its own: the next sentence before this time is the question


async def route(text: str) -> Route:
    global _armed_until
    said = _after_phrase(text)
    if said == "":
        _armed_until = time.monotonic() + ARMED_SECONDS
        return Route(False, "other", 1.0, f"heard \"{phrase()}\": waiting for the question", "")
    forced = said is not None or time.monotonic() < _armed_until
    _armed_until = 0.0
    question = said if said is not None else text

    if not forced and os.getenv("TRIGGER_ONLY"):
        return Route(False, "other", 0.0, f"no \"{phrase()}\"", question)
    if not os.getenv("SUBQ_API_KEY"):
        decided = _keywords(question)
    else:
        try:
            decided = await asyncio.to_thread(_snap, question)
        except Exception as e:
            print(f"[route] decision model failed: {e!r}")
            # A failed gate must read as "not for me": better silent than wrong. But the
            # phrase is the clinician asking outright, so that still goes through.
            decided = _keywords(question) if forced else Route(False, "other", 0.0, "error", question)
    if forced:
        decided.for_assistant, decided.by = True, phrase()
    return decided


def _snap(text: str) -> Route:
    body = {"model": os.getenv("ROUTER_MODEL", MODEL), "state": {"sentence": text}, "questions": QUESTIONS}
    request = urllib.request.Request(
        URL, data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": f"Bearer {os.environ['SUBQ_API_KEY']}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            answers = json.load(response)["answers"]
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"SubQ API {e.code}: {e.read()[:300].decode(errors='replace')}") from None
    score = answers["for_assistant"]["noul"]
    kind = answers["kind"]
    sure = kind["confidence"] >= KIND_MIN
    return Route(score >= GATE_MIN, kind["choice"].lower() if sure else "other", score, "snap", text)


_QUESTION = re.compile(r"\?\s*$|^(do|does|did|is|are|was|were|has|have|what|when|which|who|how|can|could|"
                       r"should|any|list|show|check)\b", re.I)
_INTERACTION = re.compile(r"interact|interfer|contraindicat|allerg|safe (to|with)|combin|together with", re.I)


def _keywords(text: str) -> Route:
    """The stand-in: every question is for the assistant. It cannot tell who is being asked."""
    asked = bool(_QUESTION.search(text.strip()))
    return Route(asked, "interaction" if _INTERACTION.search(text) else "chart", 1.0 if asked else 0.0,
                 "keywords", text)
