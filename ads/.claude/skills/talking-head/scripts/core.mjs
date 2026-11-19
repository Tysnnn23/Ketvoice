import { createHash } from 'node:crypto';
import { readFile, writeFile, rename, mkdir } from 'node:fs/promises';
import { dirname, resolve, extname } from 'node:path';
import { execFileSync } from 'node:child_process';

export const ENDPOINT = 'minimax/h3/reference-to-video';
export const TTS_MODEL = 'eleven_multilingual_v2';
export const hash = value => createHash('sha256').update(value).digest('hex');
export const readJSON = async path => JSON.parse(await readFile(path, 'utf8'));
export async function saveJSON(path, value) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(`${path}.tmp`, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await rename(`${path}.tmp`, path);
}
export async function optionalJSON(path) {
  try { return await readJSON(path); } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
}
export function probe(path) {
  const data = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_format', '-show_streams', '-of', 'json', path], { encoding: 'utf8' }));
  const duration = Number(data.format.duration);
  if (!(duration > 0)) throw new Error(`Cannot measure duration: ${path}`);
  return { duration, video: data.streams.some(s => s.codec_type === 'video'), audio: data.streams.some(s => s.codec_type === 'audio') };
}
export function validatePlan(plan) {
  if (!plan || typeof plan !== 'object') throw new Error('Plan must be an object.');
  if (!/^[a-zA-Z0-9_-]{4,100}$/.test(plan.voice_id || '')) throw new Error('Set voice_id from clone-voice or an existing ElevenLabs voice.');
  if (typeof plan.language !== 'string' || !/^[\p{L} ()-]{2,60}$/u.test(plan.language)) throw new Error('Set language, e.g. English or Mandarin Chinese.');
  if (!['16:9', '9:16', '1:1'].includes(plan.aspect_ratio)) throw new Error('aspect_ratio must be 16:9, 9:16 or 1:1.');
  if (!['480P', '768P'].includes(plan.resolution)) throw new Error('resolution must be 480P or 768P.');
  if (!Array.isArray(plan.images) || !plan.images.length || plan.images.length > 9 || plan.images.some(p => typeof p !== 'string' || !/\.(png|jpe?g|webp)$/i.test(p))) throw new Error('images must contain 1–9 local PNG, JPEG or WebP paths. First image sets clothing and room.');
  if (!Array.isArray(plan.scenes) || !plan.scenes.length) throw new Error('Add scenes to the plan.');
  const ids = new Set();
  for (const scene of plan.scenes) {
    if (!/^[a-z][a-z0-9-]{0,39}$/.test(scene.id || '') || ids.has(scene.id)) throw new Error('Scene IDs must be unique lowercase names (letters, digits, hyphens).');
    ids.add(scene.id);
    if (!['talking', 'motion'].includes(scene.kind)) throw new Error(`${scene.id}: kind must be talking or motion.`);
    if (typeof scene.text !== 'string' || !scene.text.trim() || scene.text.length > 1000 || /[<>]/.test(scene.text)) throw new Error(`${scene.id}: supply spoken text, at most 1000 characters, without markup.`);
    if (scene.kind === 'talking' && estimatedSpeech(scene.text) > 11) throw new Error(`${scene.id}: split this on-camera line at a sentence boundary (target at most 11 seconds of speech).`);
    if (typeof scene.heading !== 'string' || !scene.heading.trim() || scene.heading.length > 100) throw new Error(`${scene.id}: supply a short heading (at most 100 characters).`);
    if (scene.points && (!Array.isArray(scene.points) || scene.points.length > 4 || scene.points.some(x => typeof x !== 'string' || x.length > 100))) throw new Error(`${scene.id}: points must be at most four short strings.`);
  }
  return plan;
}
export function estimatedSpeech(text) {
  const cjk = (text.match(/[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]/g) || []).length;
  const words = text.replace(/[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]/g, ' ').split(/\s+/).filter(w => /[\p{L}\p{N}]/u.test(w)).length;
  return cjk / 4 + words / 2.6;
}
export function shotDuration(seconds) {
  // Do not clamp long speech: that would silently cut words off at the 15-second provider limit.
  if (!(seconds > 0) || seconds > 12.7) throw new Error(`Reference speech is ${seconds.toFixed(2)}s; split a long one. Need speech under 12.7s.`);
  return Math.max(5, Math.ceil(seconds * 1.1 + 1));
}
export function spokenPrompt(line, language, count) {
  const pictures = Array.from({ length: count }, (_, i) => `<Picture ${i + 1}>`).join(', ');
  return `subject_definitions:
<Subject 1> is the person in ${pictures}. Their face, hair and build come from all the pictures; their clothing and the room come from <Picture 1>.
<Audio 1> is the voice reference for <Subject 1> (S1): their voice, pace and delivery of the line.

summary:
[reference generation + audio reference] <Subject 1> says one line to the camera in the room from <Picture 1>, in the voice from <Audio 1>, then stays silent.

retention_analysis:
<Subject 1> (appears in [Shot 1]): fully_preserved - their face, hair, build, clothing and the room are retained.
<Audio 1>: reference - its voice timbre, pace and delivery guide the line of <Subject 1> without copying the signal.

detailed_description:
The target video is one realistic, static medium close-up at eye level in soft natural light.
[Shot 1] <Subject 1> (S1) looks straight into the camera and says, in the voice and pace referenced from <Audio 1>, <d>[${language}] ${line.trim()}</d> They then close their lips and hold a calm, slight smile, looking into the camera without speaking until the final frame.

overall_soundscape:
Quiet room tone. The only speech in the video is the line of <Subject 1>.

non_diegetic_music:
N/A`;
}
export function speechComparison(expected, actual) {
  const normalize = x => x.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  const a = Array.from(normalize(expected)), b = Array.from(normalize(actual));
  let row = b.map((_, i) => i + 1); row.unshift(0);
  for (let i = 1; i <= a.length; i++) {
    const next = [i];
    for (let j = 1; j <= b.length; j++) next[j] = Math.min(next[j - 1] + 1, row[j] + 1, row[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    row = next;
  }
  const distance = row[b.length];
  return { expected, actual, distance, status: distance === 0 ? 'match' : 'review' };
}
export function alignmentWords(alignment) {
  if (!alignment?.characters?.length) throw new Error('TTS response has no alignment.');
  const words = []; let text = '', start = 0, end = 0;
  const flush = () => { if (text) words.push({ text, start, end }); text = ''; };
  alignment.characters.forEach((c, i) => {
    if (/\s/.test(c)) return flush();
    if (/[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]/.test(c)) flush();
    if (!text) start = alignment.character_start_times_seconds[i];
    text += c; end = alignment.character_end_times_seconds[i];
  }); flush();
  if (words.some(w => !Number.isFinite(w.start) || !Number.isFinite(w.end) || w.end < w.start)) throw new Error('Invalid TTS alignment.');
  return words;
}
export function vtt(words, offset = 0) {
  const stamp = n => new Date(Math.round(n * 1000)).toISOString().slice(11, 23);
  return words.map(w => `${stamp(w.start + offset)} --> ${stamp(w.end + offset)}\n${w.text.replace(/[\r\n]/g, ' ')}\n`).join('\n');
}
export async function loadPlan(file) {
  const plan = validatePlan(await readJSON(file));
  const images = await Promise.all(plan.images.map(async p => {
    const path = resolve(dirname(file), p), bytes = await readFile(path);
    if (!bytes.length || bytes.length > 20 * 1024 * 1024) throw new Error(`Image must be nonempty and under 20MB: ${p}`);
    return { path, digest: hash(bytes) };
  }));
  return { plan, images, fingerprint: hash(JSON.stringify({ version: 1, endpoint: ENDPOINT, model: TTS_MODEL, plan, images: images.map(i => i.digest) })) };
}
export function mime(path) {
  return ({ '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp', '.mp3': 'audio/mpeg', '.wav': 'audio/wav', '.m4a': 'audio/mp4', '.mp4': 'video/mp4' })[extname(path).toLowerCase()] || 'application/octet-stream';
}
