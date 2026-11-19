// The music is ElevenLabs Music take C, cut 4 beats before its first drop (song 13.553 s).
// It runs at 124 BPM: drop 1 at ad 1.935 s, breakdown at 15.95 s, drop 2 at 17.419 s, last hit near 32.9 s.
export const FPS = 60;
export const BPM = 124;
export const BEAT_S = 60 / BPM;
export const DROP_S = 1.935;
export const WIDTH = 1080;
export const HEIGHT = 1920;

/** Frame of beat n counted from the first drop (n may be negative or fractional). */
export const PER = BEAT_S * FPS;
export const beat = (n: number) => Math.round((DROP_S + n * BEAT_S) * FPS);

export const S = {
  hook: [0, beat(0)],
  home: [beat(0), beat(8)],
  player: [beat(8), beat(16)],
  lyrics: [beat(16), beat(24)],
  stats: [beat(24), beat(28)],
  ketmixTitle: [beat(28), beat(32)],
  ketmix: [beat(32), beat(44)],
  editor: [beat(44), beat(52)],
  montage: [beat(52), beat(56)],
  cta: [beat(56), beat(64)],
  credits: [beat(64), beat(64) + 96],
} as const;

export const TOTAL = S.credits[1];
export const len = (s: readonly [number, number]) => s[1] - s[0];
