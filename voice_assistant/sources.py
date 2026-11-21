"""Stage 3, the data: what the answer model is allowed to know for this kind of question.

CONTRACT: gather(kind, question, reports) returns a dict that goes to answer.py as it is.
The answer model sees nothing else, so whatever is missing here it must say it doesn't have.

The chart is the patient report the stream viewer saved when it read the QR code.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

REPORTS = Path(__file__).resolve().parent.parent / "stream_viewer" / "reports"


def chart(reports: Path = REPORTS) -> Optional[dict]:
    """The patient on the glasses: the report scanned last, or failing that the newest one saved."""
    latest = reports / "latest.json"   # the viewer removes this when the last code scanned wasn't a report
    files = [latest] if latest.exists() else sorted(
        reports.glob("*.json"), key=lambda f: f.stat().st_mtime, reverse=True)
    for file in files:
        try:
            report = json.loads(file.read_text())
        except (OSError, ValueError):
            continue
        if isinstance(report, dict):
            return report
    return None


async def interactions(question: str, patient: Optional[dict]) -> Optional[list]:
    """TODO: look the question's drug up against the patient's medications.

    Not connected to anything yet. It needs the drug named in the question (the answer model
    or a small extraction call can pull it out) and a source to look it up in. Until then
    this returns None and the assistant says it doesn't have that.
    """
    return None


async def gather(kind: str, question: str, reports: Path = REPORTS) -> dict:
    patient = chart(reports)
    data: dict = {"chart": patient if patient is not None else "No patient report has been scanned."}
    if kind == "interaction":
        found = await interactions(question, patient)
        data["interactions"] = found if found is not None else "No interaction database is connected."
    return data
