# Voice assistant

The clinician asks a question out loud; the glasses answer with one short spoken line and put
the detail on the lens. The glasses only record and play. Everything else runs on the laptop,
next to `stream_viewer`.

This is a skeleton. Tested against `fake_glasses.py` with real services: Deepgram hears the
question, SubQ's Snap decides whether it is for the assistant, Meta's model answers from the
chart, Deepgram speaks the answer, and the assistant does not answer its own voice. Not run yet:
the Claude answer path, the glasses' end of the link, and anything on the real glasses.

```
glasses mic ──(TCP 32890)──▶ assistant.py
                               1. stt.py      Deepgram: audio → sentences
                               2. router.py   SubQ Snap: for the assistant? what kind?        no ─▶ stay silent
                               3. sources.py  the chart, and lookups for that kind of question
                               4. answer.py   small LLM: one spoken line + lens text, from that data only
                               5. tts.py      Deepgram: spoken line → audio
glasses lens + speaker ◀── SHOW and SPEAK frames ──┘
```

## Run it without glasses

Once, to get the one package it needs (into `.venv`, which git ignores):

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```

Then, in two terminals:

```bash
.venv/bin/python fake_glasses.py --say "When did they start metformin?"
```

```bash
.venv/bin/python assistant.py
```

`fake_glasses.py` stands in for the glasses' microphone, speaker and lens: the Mac's voice asks
the question, the lens text is printed and the answer is played. You can also type a question
into `assistant.py`. To try the pipeline with no audio at all:

```bash
.venv/bin/python assistant.py --dry --ask "When did they start metformin?"
```

## "Chart check"

The router decides by itself whether a sentence is for the assistant. When it gets that wrong,
start the sentence with **chart check** and it always goes through:

> Chart check, does penicillin interact with anything?

Said on its own, "chart check" sends the next sentence (within 8 seconds) instead. To answer
*only* when the phrase is said, set `TRIGGER_ONLY=1` in `.env`; `TRIGGER_PHRASE` changes the words.

## Keys

They go in `.env` (git-ignored; a dot-file, so Finder hides it: Cmd+Shift+. shows it).

| Stage | With its key | Without |
|---|---|---|
| Speech to text | Deepgram `nova-3` (`DEEPGRAM_API_KEY`) | audio is measured, not transcribed: type questions |
| Gate and router | SubQ `subq-snap-1.2` (`SUBQ_API_KEY`) | keyword rule: every question is for the assistant |
| Answer | Meta `muse-ember-1.0` (`META_API_KEY`), else Claude `claude-haiku-5-5` (`ANTHROPIC_API_KEY`) | always "I don't have that." |
| Voice | Deepgram `aura-2-asteria-en` | macOS `say` |


## With the real glasses

`glasses/VoiceLink.kt` is the glasses' end of the link. It is not in the app yet and has not
been compiled; the four steps to wire it in are at the top of the file. Then:

```bash
.venv/bin/python assistant.py --glasses <glasses-ip>
```

Over USB instead of Wi-Fi: `adb forward tcp:32890 tcp:32890`, and leave `--glasses` off.

## What is still to do

- **Drug interactions** (`sources.py`, `interactions`): no lookup is connected, so interaction
  questions get "I don't have that." It needs the drug pulled out of the question and a source.
- **The lens**: the app has to draw the `SHOW` message (`MainActivity.deliverAnswer`).
- **The gate**: Snap sorted 12 made-up sentences correctly (questions for the assistant scored
  0.73 to 1.00, everything else 0.00 to 0.11; the threshold is 0.5). That is not a real
  consultation: tune `GATE_MIN` and the instructions in `router.py` on real ones.
- **Echo**: handled on the laptop by silencing the microphone while the assistant speaks, timed
  from the length of the audio sent. If the glasses' speaker runs late, raise `EchoGate.TAIL`.

## Notes

- The patient hears the spoken line too. `answer.py` is told to keep it to one short sentence and
  put the detail on the lens.
- The chart is whatever `stream_viewer` saved in `stream_viewer/reports`. It is sent to the answer
  model, and the audio to Deepgram: fine for the demo's made-up patients, not for real ones.
- Port 32890 on the glasses is open to anyone on the same network, like the report server.
