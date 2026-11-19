#!/usr/bin/env node
import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import { cloneVoice, generate, recover } from './pipeline.mjs';
import { providers, requireKeys } from './providers.mjs';

const help = `Talking Head Skills — fal H3 + ElevenLabs

prepare-person --video inputs/me.mov --out inputs/person
               [--frame-times 2,8,14] [--audio-start 0] [--audio-duration 60]
clone-voice --name NAME --sample voice.wav --out voice.json --consent
generate --plan plan.json --out runs/intro [--dry-run]
         [--max-video-seconds 30] [--wait-seconds 1200]
         [--retry-tts SCENE] [--retry-transcription SCENE]
render --manifest runs/intro/film.json [--artwork visuals.mjs] [--out film.mp4]
recover --out runs/intro --scene intro --request-id ID

Node 22+, ffmpeg/ffprobe. Set FAL_KEY and ELEVENLABS_API_KEY.
prepare-person extracts candidate photos and voice locally; it needs no API keys.
Record with your phone or webcam and supply the saved file; no camera UI is included.
Run the SAME generate command to resume. A timeout never resubmits H3.
--dry-run validates local inputs without uploads or paid requests.
--max-video-seconds caps H3 output seconds, not dollar spend or TTS.
Clone your own voice or one you have permission to use; --consent records that.
Keys and input media are sent only to the relevant provider. Do not commit run folders.
`;
try {
  const { values: v, positionals } = parseArgs({ options: {
    help: { type: 'boolean' }, consent: { type: 'boolean' }, 'dry-run': { type: 'boolean' },
    name: { type: 'string' }, sample: { type: 'string' }, out: { type: 'string' }, plan: { type: 'string' },
    video: { type: 'string' }, 'frame-times': { type: 'string' }, 'audio-start': { type: 'string', default: '0' }, 'audio-duration': { type: 'string' },
    manifest: { type: 'string' }, artwork: { type: 'string' }, scene: { type: 'string' }, 'request-id': { type: 'string' },
    'max-video-seconds': { type: 'string', default: '30' }, 'wait-seconds': { type: 'string', default: '1200' },
    'retry-tts': { type: 'string' }, 'retry-transcription': { type: 'string' },
  }, allowPositionals: true });
  const command = positionals[0];
  if (v.help || !command) console.log(help);
  else if (command === 'prepare-person') {
    const { preparePerson } = await import('./prepare-person.mjs');
    const frameTimes = v['frame-times'] === undefined ? undefined : v['frame-times'].split(',').map(x => x.trim() ? Number(x) : NaN);
    const result = await preparePerson({ video: v.video, out: v.out, frameTimes, audioStart: Number(v['audio-start']), audioDuration: v['audio-duration'] === undefined ? undefined : Number(v['audio-duration']) });
    console.log(JSON.stringify(result, null, 2));
  } else if (command === 'clone-voice') {
    if (!process.env.ELEVENLABS_API_KEY) throw new Error('Set ELEVENLABS_API_KEY.');
    const result = await cloneVoice({ name: v.name, sample: v.sample, out: v.out, consent: v.consent }, providers());
    console.log(`Voice ID: ${result.voice_id}${result.requires_verification ? '\nComplete voice verification in ElevenLabs before generating.' : ''}`);
  } else if (command === 'generate') {
    if (!v.plan || !v.out) throw new Error('Supply --plan and --out.');
    if (!v['dry-run']) requireKeys();
    const result = await generate({ planFile: resolve(v.plan), out: resolve(v.out), dryRun: v['dry-run'], maxVideoSeconds: Number(v['max-video-seconds']), waitSeconds: Number(v['wait-seconds']), retryTts: v['retry-tts'], retryTranscription: v['retry-transcription'] }, providers());
    console.log(v['dry-run'] ? JSON.stringify(result, null, 2) : `Generated ${result.scenes.length} scenes. Review ${resolve(v.out, 'verification.json')}, edit visuals.mjs, then run render.`);
  } else if (command === 'render') {
    if (!v.manifest) throw new Error('Supply --manifest.');
    const { renderFilm } = await import('./render.mjs');
    console.log(JSON.stringify(await renderFilm({ manifestFile: v.manifest, artwork: v.artwork, out: v.out }), null, 2));
  } else if (command === 'recover') {
    if (!v.out || !v.scene) throw new Error('Supply --out and --scene.');
    await recover({ out: v.out, scene: v.scene, requestId: v['request-id'] }); console.log('Request adopted. Resume the original generate command.');
  } else throw new Error(`Unknown command: ${command}`);
} catch (e) { console.error(e.message); process.exitCode = 1; }
