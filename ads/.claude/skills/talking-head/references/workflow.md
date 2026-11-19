# Local workflow

## Choose the identity input

- **Your photos + your voice:** use 1–9 clear photos and a clean voice recording, or reuse an existing authorized ElevenLabs voice ID. Continue with Voice below.
- **Your self-recorded video:** record yourself speaking with a phone or webcam, or use an existing personal recording. Extract candidate photos and voice locally as follows, then use those to generate yourself saying a **new script**. The original recording's words do not become the new script unless requested.

## Self-recorded video

Save a video such as `inputs/me.mov` or `inputs/me.mp4`. Aim for 30–60 seconds of one speaker, a well-lit unobstructed face, open eyes, a steady camera and clean speech without music. The package has no built-in camera capture interface; use your phone or system camera app and give the saved file to the agent.

```bash
# Entirely local; no keys, uploads, cloning or model charges.
node <skill>/scripts/talking-head.mjs prepare-person \
  --video inputs/me.mov --out inputs/person
```

This writes three candidate portraits, `frames.jpg` (a contact sheet), `voice.wav` (mono 44.1 kHz), and `person.json` (timestamps and relative output paths). By default the audio is the first 60 seconds, or the whole clip if shorter; candidates come from 10%, 50% and 90% of that interval. Frames are candidates, not automatically verified face photos. Inspect them before use and keep the best one first. The original file is unchanged, and an existing output directory is refused.

For a better speech interval or known good face frames, all times below are seconds from the original recording's start:

```bash
node <skill>/scripts/talking-head.mjs prepare-person \
  --video inputs/me.mov --out inputs/person-selected \
  --frame-times 3,12,24 --audio-start 2 --audio-duration 30
```

The selected interval and timestamps must fit the clip. Video without an audio track is rejected: record speech or use photos with a separate voice sample. An audio track can still contain silence or noise, so listen before cloning. HDR phone recordings must first be exported as SDR so extracted photos have correct colors. If extraction fails, inspect the error and choose a fresh output folder; do not overwrite the original or treat a partial folder as ready.

After reviewing the candidates and speech, clone with `--sample inputs/person/voice.wav` (command below). Put the returned voice ID into the plan and use, for example, `"images": ["inputs/person/portrait-02.jpg", "inputs/person/portrait-01.jpg"]` if frame 02 is the clearest. These paths assume `plan.json` is in the project root; image paths are relative to the plan file. `person.json` is a preparation report, not a generation plan.

Then run the existing `generate` and `render` commands. ElevenLabs speaks the **new script** using the cloned voice, and H3 receives the chosen face frames plus that synthesized speech. The H3 reference-audio limit applies to each newly synthesized line, not to the length of the source recording used for cloning.

## Voice

Use a clean recording of yourself, or a speaker who has authorized the clone. A clean 30–60 second sample is a useful starting point; quality matters more than padding it out. A short sample may work less reliably. Cloning needs a suitable ElevenLabs plan.

```bash
node <skill>/scripts/talking-head.mjs clone-voice \
  --name 'My voice' --sample inputs/voice.wav --out voice.json --consent
```

For the self-recorded-video path, replace `inputs/voice.wav` with `inputs/person/voice.wav`.

`voice.json` contains the voice ID and whether provider verification is required. Complete verification in ElevenLabs when requested. Reusing this command with the same sample and output returns the saved voice; it does not create another clone. An existing authorized `voice_id` can be put straight into the plan instead.

## Plan

Image paths are relative to the plan file. `heading` is the on-screen heading; `text` is spoken. `points` is optional artwork content, not additional speech. All strings are plain text.

```json
{
  "language": "English",
  "voice_id": "YOUR_ELEVENLABS_VOICE_ID",
  "images": ["inputs/portrait.jpg"],
  "aspect_ratio": "16:9",
  "resolution": "768P",
  "scenes": [
    {
      "id": "intro",
      "kind": "talking",
      "text": "Your next video can start with a sentence. Here is how it works.",
      "heading": "Start with a sentence"
    },
    {
      "id": "steps",
      "kind": "motion",
      "text": "Write your message, choose your on-camera lines, and explain the rest with motion.",
      "heading": "From words to a film",
      "points": ["Write", "Speak", "Explain"]
    }
  ]
}
```

- Aspect ratio: `16:9`, `9:16` or `1:1`. Local output: 1280×720, 720×1280 or 960×960, at 30 fps. fal resolution describes the source shot, not the assembled output.
- H3 resolution: `480P` or `768P`, default supplied explicitly in the plan. No automatic model or resolution upgrades.
- H3 input: 1–9 JPG/PNG/WebP images plus one synthesized audio clip. First image sets the room and clothing.
- Each reference audio clip must fit the provider's 2–15 second range. Speech shorter than two seconds is padded with trailing silence locally, without another TTS call. Speech longer than 12.7 seconds is rejected to leave delivery headroom in a 5–15 second video; it is never silently cut off.
- TTS: `eleven_multilingual_v2`; transcript: `scribe_v2`; video: `minimax/h3/reference-to-video` on fal. OpenRouter is not an implemented provider in this release.

## Generate and resume

```bash
node <skill>/scripts/talking-head.mjs generate --plan plan.json --out runs/intro --dry-run
node <skill>/scripts/talking-head.mjs generate --plan plan.json --out runs/intro --max-video-seconds 30
```

Dry-run reads local inputs only. The second command is paid. Both keys must be set. `--max-video-seconds` caps the sum of H3 output seconds; it is **not a dollar budget** and does not cap TTS/transcription charges. TTS is generated before the measured H3 duration check. Read current rates in your provider accounts before a run; no price is hard-coded here.

`FAL_API_KEY` is accepted as an alias for `FAL_KEY`.

`--wait-seconds` defaults to 1200. A timeout stops local waiting; it does not cancel a provider job. Resume with the identical command and folder. A changed plan or photo is rejected so you cannot accidentally mix old takes and a new script. Do not edit `state.json` while a run is active.

If H3 submission timed out before its ID was saved, find that request in the fal dashboard, then:

```bash
node <skill>/scripts/talking-head.mjs recover --out runs/intro --scene intro --request-id REQUEST_ID
node <skill>/scripts/talking-head.mjs generate --plan plan.json --out runs/intro
```

For a TTS/transcription response lost in transit, check provider history first. `--retry-tts intro` or `--retry-transcription intro` permits repeating only that unfinished operation and may incur another charge. It does not overwrite a completed result. A confirmed failed H3 request stays saved; inspect its error in fal rather than blindly regenerating.

The lock prevents two processes from buying the same work. After a forced process termination, check that the old process is stopped, remove only `.lock` (or `.render.lock` for rendering), then resume. A clone with a lost response must be located in ElevenLabs; use its existing ID instead of creating another clone.

## Files

`film.json` contains source paths, durations and measured word times. `verification.json` compares the requested and transcribed text; formatting-only differences are ignored, other differences are marked for review. It does not certify voice similarity or lip sync. `speech.vtt` uses final, measured speech timings and is adjusted to frame-rounded joins on render.

`visuals.mjs` is editable artwork. `film.mp4` is the final output; `review/` has three composited frames per scene; `render-report.json` records media checks and outstanding transcript differences. The first render may use the starter design; replace the artwork to fit the actual brief.

Private outputs include the input text, synthesized audio, downloaded footage, voice IDs and provider asset URLs. Keep `runs/`, `inputs/`, `.env` and `voice.json` out of git. Deleting local files does not delete a provider-side voice or upload; manage those in the relevant provider account.

## Provider references

- [fal H3 reference-to-video schema](https://fal.ai/models/minimax/h3/reference-to-video/api)
- [fal machine-readable OpenAPI](https://fal.ai/api/openapi/queue/openapi.json?endpoint_id=minimax/h3/reference-to-video)
- [ElevenLabs instant voice cloning](https://elevenlabs.io/docs/api-reference/voices/ivc/create)
- [ElevenLabs speech with timing](https://elevenlabs.io/docs/api-reference/text-to-speech/convert-with-timestamps)
- [ElevenLabs transcription](https://elevenlabs.io/docs/api-reference/speech-to-text/convert)

Model IDs and input limits checked 2026-10-25. Provider availability and prices can change.
