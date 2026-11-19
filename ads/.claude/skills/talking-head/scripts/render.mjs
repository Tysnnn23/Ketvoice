import { writeFile, mkdir, rename } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { pathToFileURL } from 'node:url';
import { spawn, execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { readJSON, probe, saveJSON } from './core.mjs';
import { withLock } from './pipeline.mjs';

const shapes = { '16:9': [1280, 720], '9:16': [720, 1280], '1:1': [960, 960] };
export async function renderFilm({ manifestFile, artwork, out }) {
  const { createCanvas, GlobalFonts } = await import('@napi-rs/canvas');
  const film = await readJSON(manifestFile), folder = dirname(resolve(manifestFile));
  if (!shapes[film.aspect_ratio] || film.fps !== 30 || !film.scenes?.length) throw new Error('Invalid film.json. Generate a film first.');
  const [width, height] = shapes[film.aspect_ratio];
  const { draw } = await import(pathToFileURL(resolve(artwork || resolve(folder, 'visuals.mjs'))).href);
  if (typeof draw !== 'function') throw new Error('visuals.mjs must export draw(ctx, scene, time, environment).');
  const targetFile = resolve(out || resolve(folder, 'film.mp4'));
  if (film.scenes.some(s => resolve(folder, s.media) === targetFile)) throw new Error('Output must not overwrite source media.');
  for (const scene of film.scenes) {
    if (!/^[a-z][a-z0-9-]{0,39}$/.test(scene.id) || !['talking', 'motion'].includes(scene.kind) || !(scene.duration > 0) || !Array.isArray(scene.words)) throw new Error('Invalid scene in film.json.');
    const measured = probe(resolve(folder, scene.media));
    if (!measured.audio || (scene.kind === 'talking' && !measured.video)) throw new Error(`${scene.id}: missing audio or video.`);
    if (Math.abs(measured.duration - scene.duration) > 0.15) throw new Error(`${scene.id}: media changed since generation.`);
  }
  return withLock(resolve(folder, '.render.lock'), async () => {
    const frames = resolve(folder, 'review'); await mkdir(frames, { recursive: true });
    const encoded = resolve(folder, 'rendered'); await mkdir(encoded, { recursive: true });
    const canvas = createCanvas(width, height), ctx = canvas.getContext('2d');
    const clips = [];
    for (const scene of film.scenes) {
      const count = Math.ceil(scene.duration * film.fps), duration = count / film.fps;
      const target = resolve(encoded, `${scene.id}.mp4`), temp = `${target}.part.mp4`;
      const args = ['-hide_banner', '-loglevel', 'error', '-y'];
      if (scene.kind === 'talking') {
        args.push('-i', resolve(folder, scene.media), '-f', 'image2pipe', '-framerate', String(film.fps), '-i', 'pipe:0', '-filter_complex', `[0:v]scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2:color=0x121b24,setsar=1,tpad=stop_mode=clone:stop_duration=1[base];[base][1:v]overlay=shortest=1:format=auto,format=yuv420p[v];[0:a]apad[a]`, '-map', '[v]', '-map', '[a]');
      } else {
        args.push('-f', 'image2pipe', '-framerate', String(film.fps), '-i', 'pipe:0', '-i', resolve(folder, scene.media), '-map', '0:v:0', '-map', '1:a:0', '-af', 'apad');
      }
      args.push('-t', String(duration), '-r', String(film.fps), '-c:v', 'libx264', '-preset', 'fast', '-crf', '19', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-ar', '48000', '-ac', '2', '-movflags', '+faststart', temp);
      console.log(`Rendering ${scene.id}: ${count} frames`);
      const child = spawn('ffmpeg', args, { stdio: ['pipe', 'ignore', 'pipe'] });
      let stderr = '';
      child.stderr.on('data', b => { stderr = (stderr + b.toString()).slice(-4000); });
      // Register before drawing; an early ffmpeg failure must not become an unhandled rejection.
      let processError;
      child.on('error', e => { processError = e; }); child.stdin.on('error', e => { processError = e; });
      const finished = new Promise(r => child.once('close', code => r(code)));
      try {
        for (let frame = 0; frame < count; frame++) {
          if (processError || child.exitCode !== null) throw processError || new Error(stderr || 'ffmpeg exited early');
          ctx.clearRect(0, 0, width, height); ctx.save();
          draw(ctx, scene, frame / film.fps, { width, height, fps: film.fps, registerFont: (path, family) => GlobalFonts.registerFromPath(path, family) }); ctx.restore();
          const bytes = canvas.toBuffer('image/png');
          if (!child.stdin.write(bytes)) await Promise.race([once(child.stdin, 'drain'), finished.then(() => { throw new Error(stderr || 'ffmpeg stopped'); })]);
        }
        child.stdin.end();
        if (await finished !== 0) throw new Error(stderr || 'ffmpeg failed');
      } catch (error) { child.kill('SIGTERM'); await finished; throw error; }
      await rename(temp, target); clips.push(target);
      // These are composited frames, including the real speaker, not just the artwork layer.
      for (const [label, second] of [['early', Math.min(0.5, duration / 4)], ['middle', duration / 2], ['late', Math.max(0, duration - 0.3)]]) {
        execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-ss', String(second), '-i', target, '-frames:v', '1', resolve(frames, `${scene.id}-${label}.jpg`)]);
      }
    }
    // Use local relative filenames in the concat list, which also handles apostrophes in the project path.
    const list = resolve(encoded, 'concat.txt');
    await writeFile(list, film.scenes.map(s => `file '${s.id}.mp4'`).join('\n') + '\n');
    out = resolve(out || resolve(folder, 'film.mp4')); await mkdir(dirname(out), { recursive: true });
    const temp = `${out}.part.mp4`;
    execFileSync('ffmpeg', ['-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '1', '-i', list, '-c', 'copy', '-movflags', '+faststart', temp]);
    await rename(temp, out);
    const measured = probe(out);
    if (!measured.audio || !measured.video) throw new Error('Final film is missing audio or video.');
    const expected = film.scenes.reduce((sum, s) => sum + Math.ceil(s.duration * film.fps) / film.fps, 0);
    if (Math.abs(expected - measured.duration) > Math.max(0.2, film.scenes.length * 0.05)) throw new Error('Final duration does not match the timeline.');
    // Rebase captions to the frame-rounded joins used by the actual assembled film.
    const { vtt } = await import('./core.mjs'); let offset = 0;
    const captions = film.scenes.map(s => { const result = vtt(s.words, offset); offset += Math.ceil(s.duration * film.fps) / film.fps; return result; });
    await writeFile(resolve(folder, 'speech.vtt'), `WEBVTT\n\n${captions.join('\n')}`);
    await saveJSON(resolve(folder, 'render-report.json'), { width, height, fps: film.fps, duration: measured.duration, sceneCount: clips.length, audio: 'Original H3 sound on talking scenes; ElevenLabs audio on motion scenes.', transcriptReview: film.scenes.filter(s => s.comparison?.status === 'review').map(s => s.id) });
    return { file: out, duration: measured.duration, review: frames };
  });
}
