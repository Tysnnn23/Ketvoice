import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import { ENDPOINT, TTS_MODEL, mime } from './core.mjs';

async function checked(response, operation) {
  if (!response.ok) {
    // Provider bodies can contain private inputs/URLs. Keep those out of ordinary console output.
    throw new Error(`${operation}: HTTP ${response.status}. Check the provider dashboard; do not automatically repeat paid requests.`);
  }
  return response.json();
}
export function requireKeys() {
  if (!process.env.FAL_KEY && !process.env.FAL_API_KEY) throw new Error('Set FAL_KEY in your environment.');
  if (!process.env.ELEVENLABS_API_KEY) throw new Error('Set ELEVENLABS_API_KEY in your environment.');
}
export function providers() {
  const elevenHeaders = () => {
    if (!process.env.ELEVENLABS_API_KEY) throw new Error('Set ELEVENLABS_API_KEY.');
    return { 'xi-api-key': process.env.ELEVENLABS_API_KEY };
  };
  const falHeaders = () => ({ Authorization: `Key ${process.env.FAL_KEY || process.env.FAL_API_KEY}` });
  return {
    async clone(name, sample) {
      const form = new FormData();
      form.set('name', name);
      form.append('files', new Blob([await readFile(sample)], { type: mime(sample) }), basename(sample));
      return checked(await fetch('https://api.elevenlabs.io/v1/voices/add', { method: 'POST', headers: elevenHeaders(), body: form, signal: AbortSignal.timeout(120_000) }), 'Voice clone');
    },
    async tts(voiceId, text) {
      return checked(await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}/with-timestamps?output_format=mp3_44100_128`, {
        method: 'POST', headers: { ...elevenHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ text, model_id: TTS_MODEL }), signal: AbortSignal.timeout(120_000),
      }), 'TTS');
    },
    async upload(path) {
      const { createFalClient } = await import('@fal-ai/client');
      const fal = createFalClient({ credentials: process.env.FAL_KEY || process.env.FAL_API_KEY });
      return fal.storage.upload(new File([await readFile(path)], basename(path), { type: mime(path) }));
    },
    async submit(input) {
      return checked(await fetch(`https://queue.fal.run/${ENDPOINT}`, { method: 'POST', headers: { ...falHeaders(), 'Content-Type': 'application/json' }, body: JSON.stringify(input), signal: AbortSignal.timeout(60_000) }), 'H3 submission');
    },
    async status(requestId) {
      return checked(await fetch(`https://queue.fal.run/minimax/h3/requests/${encodeURIComponent(requestId)}/status`, { headers: falHeaders(), signal: AbortSignal.timeout(30_000) }), 'H3 status');
    },
    async result(requestId) {
      const response = await fetch(`https://queue.fal.run/minimax/h3/requests/${encodeURIComponent(requestId)}`, { headers: falHeaders(), signal: AbortSignal.timeout(30_000) });
      const result = await checked(response, 'H3 result');
      return { ...result, billable_units: response.headers.get('x-fal-billable-units') };
    },
    async download(url) {
      if (new URL(url).protocol !== 'https:') throw new Error('Provider output must be an HTTPS URL.');
      const response = await fetch(url, { signal: AbortSignal.timeout(180_000) });
      if (!response.ok) throw new Error(`Download: HTTP ${response.status}`);
      return Buffer.from(await response.arrayBuffer());
    },
    async transcribe(path) {
      const form = new FormData();
      form.set('model_id', 'scribe_v2'); form.set('timestamps_granularity', 'word'); form.set('tag_audio_events', 'false');
      form.append('file', new Blob([await readFile(path)], { type: mime(path) }), basename(path));
      return checked(await fetch('https://api.elevenlabs.io/v1/speech-to-text', { method: 'POST', headers: elevenHeaders(), body: form, signal: AbortSignal.timeout(180_000) }), 'Transcription');
    },
  };
}
