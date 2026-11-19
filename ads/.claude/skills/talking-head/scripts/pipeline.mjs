import { writeFile, readFile, mkdir, open, unlink, copyFile, access } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { ENDPOINT, TTS_MODEL, loadPlan, optionalJSON, saveJSON, probe, shotDuration, spokenPrompt, alignmentWords, speechComparison, vtt, hash } from './core.mjs';

export async function withLock(path, work) {
  await mkdir(dirname(path), { recursive: true });
  let lock;
  try { lock = await open(path, 'wx', 0o600); }
  catch (e) {
    if (e.code !== 'EEXIST') throw e;
    throw new Error(`Another run owns ${path}. If its process has stopped, remove only this lock file and resume.`);
  }
  try { await lock.writeFile(`${process.pid}\n`); return await work(); }
  finally { await lock.close(); await unlink(path); }
}
const exists = async path => { try { await access(path); return true; } catch { return false; } };
const pendingMessage = phase => `${phase} was started but its response was not saved. Check the provider dashboard before retrying; it may already have been billed.`;

export async function cloneVoice({ name, sample, out, consent }, api) {
  if (!consent) throw new Error('Voice cloning requires --consent: use your own voice or one you have permission to clone.');
  if (!name?.trim() || !sample || !out) throw new Error('Supply --name, --sample and --out.');
  const media = probe(sample);
  if (!media.audio) throw new Error('The voice sample has no audio track.');
  const fingerprint = hash(JSON.stringify({ name, sample: hash(await readFile(sample)) }));
  return withLock(`${out}.lock`, async () => {
    const previous = await optionalJSON(out);
    if (previous) {
      if (previous.fingerprint !== fingerprint) throw new Error('This output belongs to a different clone. Use another --out path.');
      if (previous.voice_id) return previous;
      throw new Error(`${pendingMessage('Cloning')} Find the voice in ElevenLabs and use its voice_id in your plan.`);
    }
    const state = { name, fingerprint, status: 'submitting', consent: true };
    await saveJSON(out, state);
    const result = await api.clone(name, sample);
    if (!result.voice_id) throw new Error('Clone response has no voice_id. Check ElevenLabs before retrying.');
    Object.assign(state, { status: 'created', voice_id: result.voice_id, requires_verification: !!result.requires_verification });
    await saveJSON(out, state);
    return state;
  });
}

export async function generate({ planFile, out, maxVideoSeconds = 30, waitSeconds = 1200, retryTts, retryTranscription, dryRun = false }, api, runtime = {}) {
  const { plan, images, fingerprint } = await loadPlan(planFile);
  if (!(maxVideoSeconds >= 5) || !(waitSeconds >= 0)) throw new Error('Invalid time limit.');
  for (const id of [retryTts, retryTranscription].filter(Boolean)) if (!plan.scenes.some(s => s.id === id)) throw new Error(`Unknown scene: ${id}`);
  if (dryRun) return { scenes: plan.scenes.map(s => ({ id: s.id, kind: s.kind, text: s.text })), images: images.length, endpoint: ENDPOINT, tts_model: TTS_MODEL, maxVideoSeconds, paidRequests: 0 };
  const measure = runtime.probe || probe;
  const sleep = runtime.sleep || (ms => new Promise(r => setTimeout(r, ms)));
  const log = runtime.log || console.log;
  out = resolve(out);
  return withLock(resolve(out, '.lock'), async () => {
    const stateFile = resolve(out, 'state.json');
    const state = await optionalJSON(stateFile) || { version: 1, fingerprint, plan, images: [], scenes: {} };
    if (state.fingerprint !== fingerprint) throw new Error('Plan or image contents changed. Use a new output folder; this one contains paid work for the previous inputs.');
    const save = () => saveJSON(stateFile, state);
    await save();
    // Finish all speech first: actual lengths, not estimated word counts, decide whether H3 can fit.
    for (const scene of plan.scenes) {
      const job = state.scenes[scene.id] ||= {};
      const audioPath = resolve(out, `${scene.id}.mp3`);
      if (job.tts?.status === 'submitting' && await exists(resolve(out, `${scene.id}.tts.json`))) { job.tts = { status: 'saved' }; await save(); }
      if (retryTts === scene.id && job.tts?.status === 'submitting') { delete job.tts; await save(); }
      if (job.tts?.status === 'submitting') throw new Error(`${scene.id}: ${pendingMessage('TTS')} Use --retry-tts ${scene.id} only after checking.`);
      if (!job.tts) {
        log(`${scene.id}: synthesizing speech`);
        job.tts = { status: 'submitting' }; await save();
        const response = await api.tts(plan.voice_id, scene.text);
        // Save the response immediately so a later disk/probe failure cannot repeat paid TTS.
        await saveJSON(resolve(out, `${scene.id}.tts.json`), response);
        job.tts = { status: 'saved' }; await save();
      }
      if (job.tts.status === 'saved') {
        const response = await optionalJSON(resolve(out, `${scene.id}.tts.json`));
        const bytes = Buffer.from(response?.audio_base64 || '', 'base64');
        if (bytes.length < 100) throw new Error(`${scene.id}: unusable TTS audio; response retained.`);
        await writeFile(audioPath, bytes, { mode: 0o600 });
        const media = measure(audioPath);
        if (!media.audio) throw new Error(`${scene.id}: TTS output contains no audio.`);
        job.tts = { status: 'done', duration: media.duration, words: alignmentWords(response.normalized_alignment || response.alignment) }; await save();
      }
      if (!await exists(audioPath)) throw new Error(`${scene.id}: saved audio is missing. Restore it; do not regenerate the whole run.`);
      if (scene.kind === 'talking') job.duration = shotDuration(job.tts.duration);
    }
    const seconds = plan.scenes.filter(s => s.kind === 'talking').reduce((sum, s) => sum + state.scenes[s.id].duration, 0);
    if (seconds > maxVideoSeconds) throw new Error(`Plan needs ${seconds}s of H3, exceeding --max-video-seconds ${maxVideoSeconds}. TTS is saved. Increase the limit to resume if intended.`);
    log(`H3 total: ${seconds}s; narration: ${plan.scenes.filter(s => s.kind === 'motion').length} scenes.`);
    const deadline = Date.now() + waitSeconds * 1000;
    for (const scene of plan.scenes.filter(s => s.kind === 'talking')) {
      const job = state.scenes[scene.id];
      const videoPath = resolve(out, `${scene.id}.mp4`);
      if (!job.fal) {
        for (let i = state.images.length; i < images.length; i++) { state.images[i] = await api.upload(images[i].path); await save(); }
        if (!job.audioUrl) {
          let reference = resolve(out, `${scene.id}.mp3`);
          if (job.tts.duration < 2) {
            // fal requires at least two seconds of reference audio. Pad locally; never buy another TTS take just for this limit.
            reference = resolve(out, `${scene.id}.reference.mp3`);
            execFileSync('ffmpeg', ['-v', 'error', '-y', '-i', resolve(out, `${scene.id}.mp3`), '-af', 'apad', '-t', '2.1', '-c:a', 'libmp3lame', reference]);
          }
          job.audioUrl = await api.upload(reference); await save();
        }
        const input = { prompt: spokenPrompt(scene.text, plan.language, images.length), duration: job.duration, aspect_ratio: plan.aspect_ratio, resolution: plan.resolution, reference_image_urls: state.images, reference_audio_urls: [job.audioUrl], prompt_expansion_mode: 'disabled', enable_safety_checker: true };
        await saveJSON(resolve(out, `${scene.id}.request.json`), input);
        job.fal = { status: 'submitting' }; await save();
        log(`${scene.id}: submitting ${job.duration}s to H3`);
        const response = await api.submit(input);
        if (!response.request_id) throw new Error(`${scene.id}: H3 returned no request ID. Check fal before submitting again.`);
        job.fal = { status: 'queued', request_id: response.request_id }; await save();
      }
      if (job.fal.status === 'submitting') throw new Error(`${scene.id}: ${pendingMessage('H3')} Use recover with the request ID from fal.`);
      if (!job.fal.result) {
        for (;;) {
          const status = await api.status(job.fal.request_id);
          if (status.status === 'COMPLETED') break;
          if (!['IN_QUEUE', 'IN_PROGRESS'].includes(status.status)) throw new Error(`${scene.id}: unexpected H3 status ${status.status}. Request retained.`);
          if (Date.now() >= deadline) throw new Error('Waiting limit reached. Run the same generate command to resume the existing request.');
          log(`${scene.id}: ${status.status}`); await sleep(15_000);
        }
        const result = await api.result(job.fal.request_id);
        if (!result.video?.url) throw new Error(`${scene.id}: H3 result has no video. Request retained.`);
        job.fal.result = result; job.fal.status = 'completed'; await save();
      }
      if (!await exists(videoPath)) {
        await writeFile(`${videoPath}.part`, await api.download(job.fal.result.video.url), { mode: 0o600 });
        const { rename } = await import('node:fs/promises'); await rename(`${videoPath}.part`, videoPath);
      }
      const media = measure(videoPath);
      if (!media.audio || !media.video) throw new Error(`${scene.id}: H3 output must contain both picture and sound.`);
      job.mediaDuration = media.duration;
      if (job.transcription?.status === 'submitting' && await exists(resolve(out, `${scene.id}.transcript.json`))) { job.transcription = { status: 'saved' }; await save(); }
      if (retryTranscription === scene.id && job.transcription?.status === 'submitting') { delete job.transcription; await save(); }
      if (job.transcription?.status === 'submitting') throw new Error(`${scene.id}: ${pendingMessage('Transcription')} Use --retry-transcription ${scene.id} after checking.`);
      if (!job.transcription) {
        job.transcription = { status: 'submitting' }; await save();
        const transcript = await api.transcribe(videoPath);
        await saveJSON(resolve(out, `${scene.id}.transcript.json`), transcript);
        job.transcription = { status: 'saved' }; await save();
      }
      if (job.transcription.status === 'saved') {
        const transcript = await optionalJSON(resolve(out, `${scene.id}.transcript.json`));
        const words = (transcript.words || []).filter(w => w.type === 'word').map(w => ({ text: w.text, start: w.start, end: w.end }));
        if (!words.length || words.some(w => !Number.isFinite(w.start) || !Number.isFinite(w.end) || w.end < w.start || w.start < 0 || w.end > media.duration + 0.5)) throw new Error(`${scene.id}: invalid measured word timings. Response saved; inspect it before retrying.`);
        job.transcription = { status: 'done', words, comparison: speechComparison(scene.text, transcript.text || words.map(w => w.text).join(' ')) }; await save();
      }
    }
    let offset = 0;
    const scenes = plan.scenes.map(scene => {
      const job = state.scenes[scene.id], talking = scene.kind === 'talking';
      const duration = talking ? job.mediaDuration : job.tts.duration;
      const item = { ...scene, start: offset, duration, media: `${scene.id}.${talking ? 'mp4' : 'mp3'}`, words: talking ? job.transcription.words : job.tts.words, ...(talking ? { comparison: job.transcription.comparison } : {}) };
      offset += duration; return item;
    });
    const manifest = { version: 1, aspect_ratio: plan.aspect_ratio, fps: 30, duration: offset, scenes };
    await saveJSON(resolve(out, 'film.json'), manifest);
    await writeFile(resolve(out, 'speech.vtt'), `WEBVTT\n\n${scenes.map(s => vtt(s.words, s.start)).join('\n')}`);
    await saveJSON(resolve(out, 'verification.json'), { note: 'Transcription checks wording, not voice likeness or lip sync. Listen and watch before delivery.', scenes: scenes.filter(s => s.comparison).map(s => ({ id: s.id, ...s.comparison })) });
    const artwork = resolve(out, 'visuals.mjs');
    if (!await exists(artwork)) await copyFile(fileURLToPath(new URL('../templates/visuals.mjs', import.meta.url)), artwork);
    return manifest;
  });
}

export async function recover({ out, scene, requestId }) {
  if (!/^[a-zA-Z0-9_-]{8,150}$/.test(requestId || '')) throw new Error('Supply the fal request ID from its dashboard.');
  return withLock(resolve(out, '.lock'), async () => {
    const file = resolve(out, 'state.json'), state = await optionalJSON(file);
    if (state?.scenes?.[scene]?.fal?.status !== 'submitting') throw new Error('Recovery only applies to an H3 submission with an unknown result.');
    state.scenes[scene].fal = { status: 'queued', request_id: requestId }; await saveJSON(file, state);
  });
}
