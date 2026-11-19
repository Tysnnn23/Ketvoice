import { execFileSync } from 'node:child_process';
import { mkdir, stat } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { saveJSON, probe } from './core.mjs';

// Local preparation only. Frame selection is a visual review step, not face detection.
export async function preparePerson({ video, out, frameTimes, audioStart = 0, audioDuration }) {
  if (!video || !out) throw new Error('Supply --video and --out.');
  const source = resolve(video), folder = resolve(out);
  if (!(await stat(source)).isFile()) throw new Error('Video input must be a local file.');
  const data = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_format', '-show_streams', '-of', 'json', source], { encoding: 'utf8' }));
  const picture = data.streams.find(s => s.codec_type === 'video' && !s.disposition?.attached_pic);
  if (!picture) throw new Error('The recording has no video track. Use the photo + voice path for audio-only input.');
  if (!data.streams.some(s => s.codec_type === 'audio')) throw new Error('The recording has no audio track. Record yourself speaking, or use photos with a separate voice sample.');
  if (['smpte2084', 'arib-std-b67'].includes(picture.color_transfer) || picture.side_data_list?.some(s => /DOVI/i.test(s.side_data_type))) throw new Error('HDR recording detected. Export an SDR copy first so the reference photos retain correct colors.');
  const duration = Number(data.format.duration);
  if (!(duration > 0) || !Number.isFinite(duration)) throw new Error('Cannot measure the recording duration.');
  if (!Number.isFinite(audioStart) || audioStart < 0 || audioStart >= duration) throw new Error('--audio-start must be inside the recording (seconds).');
  const length = audioDuration ?? Math.min(60, duration - audioStart);
  if (!Number.isFinite(length) || length <= 0 || audioStart + length > duration + 0.01) throw new Error('--audio-duration must be positive and fit inside the recording.');
  const times = frameTimes ?? [0.1, 0.5, 0.9].map(fraction => audioStart + length * fraction);
  if (!Array.isArray(times) || !times.length || times.length > 9 || times.some(t => !Number.isFinite(t) || t < 0 || t >= duration)) throw new Error('--frame-times must contain 1–9 comma-separated timestamps inside the recording (seconds).');
  await mkdir(dirname(folder), { recursive: true });
  try { await mkdir(folder, { mode: 0o700 }); }
  catch (e) { if (e.code === 'EEXIST') throw new Error('Output folder already exists. Choose a new --out folder; existing files are never overwritten.'); throw e; }

  const frames = times.map((time, i) => ({ file: `portrait-${String(i + 1).padStart(2, '0')}.jpg`, time }));
  for (const frame of frames) {
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-nostdin', '-n', '-ss', String(frame.time), '-i', source, '-map', `0:${picture.index}`, '-frames:v', '1', '-q:v', '2', resolve(folder, frame.file)]);
    if (!(await stat(resolve(folder, frame.file))).size) throw new Error(`Could not extract frame at ${frame.time}s. Choose another time in a new output folder.`);
  }
  const voice = resolve(folder, 'voice.wav');
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-nostdin', '-n', '-ss', String(audioStart), '-i', source, '-t', String(length), '-map', '0:a:0', '-vn', '-ac', '1', '-ar', '44100', '-c:a', 'pcm_s16le', voice]);
  const audio = probe(voice);
  if (!audio.audio || Math.abs(audio.duration - length) > 0.2) throw new Error('Extracted audio does not cover the requested interval. Inspect the source and select another interval.');
  const columns = Math.min(3, frames.length), rows = Math.ceil(frames.length / columns);
  execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-nostdin', '-n', '-framerate', '1', '-i', resolve(folder, 'portrait-%02d.jpg'), '-vf', `scale=320:-2,tile=${columns}x${rows}`, '-frames:v', '1', resolve(folder, 'frames.jpg')]);
  const result = {
    source, source_duration: duration, frames, images: frames.map(f => f.file), voice_sample: 'voice.wav',
    audio_start: audioStart, audio_duration: audio.duration, contact_sheet: 'frames.jpg',
    review: 'Candidate frames only: choose a sharp, unobstructed face with open eyes; listen for clean speech from one speaker before cloning. Paths are relative to this file. No provider calls were made.',
  };
  await saveJSON(resolve(folder, 'person.json'), result);
  return result;
}
