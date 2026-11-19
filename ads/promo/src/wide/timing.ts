// 16:9 cut. Two soundtracks, one edit: every cue is a frame number taken from the track.
export const FPS = 60;
export const W = 1920;
export const H = 1080;

type Span = readonly [number, number];
export type WidePlan = {
  total: number;
  S: { hook: Span; logo: Span; home: Span; player: Span; lyrics: Span; voice: Span; meet: Span | null; ketmix: Span; editor: Span; free: Span; end: Span };
  pops: number[]; swap: number; mergeFrom: number; mergeTo: number;
  freeAt: number[];
  punches: number[];
  music: { src: string; volume: number; fade: number };
  /** False when the track already carries its own effects: only the pieces it cannot know get a sound. */
  fullSfx: boolean;
  riserAt: number | null;
};

// ElevenLabs Music take C from song 9.198 s: 124 BPM, first drop at 6.290 s, breakdown at beat 28.95, second drop at beat 32.
const BEAT_S = 60 / 124;
const wb = (n: number) => Math.round((6.29 + n * BEAT_S) * FPS);
export const PLAN_EL: WidePlan = {
  total: wb(58) + 30,
  S: { hook: [0, wb(0)], logo: [wb(0), wb(4)], home: [wb(4), wb(10)], player: [wb(10), wb(16)], lyrics: [wb(16), wb(22)], voice: [wb(22), wb(28.95)],
    meet: [wb(28.95), wb(32)], ketmix: [wb(32), wb(40)], editor: [wb(40), wb(46)], free: [wb(46), wb(50)], end: [wb(50), wb(58) + 30] },
  pops: [-12, -10.5, -9, -7.5, -6].map(wb), swap: wb(-4), mergeFrom: wb(-3.6), mergeTo: wb(-0.15),
  freeAt: [46, 47.33, 48.66].map(wb),
  punches: [wb(32)],
  music: { src: "music/track-wide.wav", volume: 0.78, fade: 70 },
  fullSfx: true,
  riserAt: wb(0) - 122,
};

// The sound of the owner's reference video (first 24.45 s, before the platform's outro). Cues read from its onsets:
// five pops, the swell from 3.0 s, the hit at 4.97 s, then a cut about every four beats at ~100 BPM.
const s = (sec: number) => Math.round(sec * FPS);
export const PLAN_REF: WidePlan = {
  total: s(24.45),
  S: { hook: [0, s(4.97)], logo: [s(4.97), s(6.5)], home: [s(6.5), s(8.96)], player: [s(8.96), s(11.26)], lyrics: [s(11.26), s(13.26)], voice: [s(13.26), s(15.72)],
    meet: null, ketmix: [s(15.72), s(18.18)], editor: [s(18.18), s(20.18)], free: [s(20.18), s(21.95)], end: [s(21.95), s(24.45)] },
  pops: [0.07, 0.65, 1.25, 1.88, 2.48].map(s), swap: s(3.0), mergeFrom: s(3.05), mergeTo: s(4.9),
  freeAt: [20.3, 20.92, 21.5].map(s),
  punches: [],
  music: { src: "music/track-ref.wav", volume: 0.95, fade: 12 },
  fullSfx: false,
  riserAt: null,
};
export const wlen = (x: Span) => x[1] - x[0];
